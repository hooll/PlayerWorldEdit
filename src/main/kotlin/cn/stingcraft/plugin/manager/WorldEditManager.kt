package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.hook.DominionCheck
import cn.stingcraft.plugin.hook.DominionHook
import cn.stingcraft.plugin.until.AreaDisplay
import cn.stingcraft.plugin.until.EditBudget
import cn.stingcraft.plugin.until.WorldEditTool
import cn.stingcraft.plugin.until.displayName
import cn.stingcraft.plugin.until.markAsTool
import cn.stingcraft.plugin.until.parseBlockOrNull
import cn.stingcraft.plugin.until.runOnChunkThreads
import cn.stingcraft.plugin.until.runOnOwnThread
import cn.stingcraft.plugin.until.splitByChunk
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.block.BlockTypes
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning
import taboolib.common.platform.service.PlatformExecutor
import taboolib.library.xseries.XMaterial
import taboolib.library.xseries.parseToXMaterial
import taboolib.module.effect.createCube
import taboolib.platform.util.buildItem
import taboolib.platform.util.runTask
import taboolib.platform.util.sendLang
import taboolib.platform.util.toBukkitLocation
import taboolib.platform.util.toProxyLocation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

class WorldEditManager {

    // 选区与粒子任务会被玩家所在区域线程和异步操作线程同时读写, 用并发容器
    private val playerpos = ConcurrentHashMap<Player, Pair<Location?, Location?>>()

    private val particleTasks = ConcurrentHashMap<Player, PlatformExecutor.PlatformTask>()

    // 正在执行编辑的玩家, 避免同一玩家并发跑两个选区操作
    private val editing = ConcurrentHashMap.newKeySet<UUID>()

    // 选区可视化 (半透明玻璃盒子), 用不了时退回粒子边框
    private val areaDisplay = AreaDisplay()

    private val settingManager = PlayerWorldEdit.settingManager

    // 配置在 TabooLib 注入阶段才可用, 不能在建类时读取; 重载后由 reloadCache() 丢弃重建
    @Volatile
    private var posToolCache: ItemStack? = null

    /**
     * 获取创世神镐 (返回副本, 避免玩家修改到插件缓存的实例)
     */
    fun getPosTool(): ItemStack {
        return (posToolCache ?: buildPosTool().also { posToolCache = it }).clone()
    }

    /**
     * 配置重载后丢弃缓存的创世神镐, 下次取用时按新配置重建
     */
    fun reloadCache() {
        posToolCache = null
    }

    private fun buildPosTool(): ItemStack {
        return buildItem(settingManager.getWorldEditSettings().getString("tool.type")?.parseToXMaterial() ?:XMaterial.DIAMOND_PICKAXE){
            name = settingManager.getWorldEditSettings().getString("tool.name") ?: "&7[&f创世神镐&7]"
            lore += settingManager.getWorldEditSettings().getStringList("tool.lore")
            customModelData = settingManager.getWorldEditSettings().getInt("tool.custom-model-data")
            colored()
        }.apply {
            markAsTool()
        }
    }

    /**
     * 设置点1
     * @param player 玩家
     * @param pos1 点1
     */
    fun setPlayerPos1(player: Player, pos1: Location) {
        if (playerpos[player] == null) {
            playerpos[player] = Pair(pos1,null)
            return
        }
        playerpos[player] = Pair(pos1,playerpos[player]!!.second)
        showSelection(player)
    }

    /**
     * 设置点2
     * @param player 玩家
     * @param pos2 点2
     */
    fun setPlayerPos2(player: Player, pos2: Location) {
        if (playerpos[player] == null) {
            playerpos[player] = Pair(null,pos2)
            return
        }
        playerpos[player] = Pair(playerpos[player]?.first,pos2)
        showSelection(player)
    }

    /**
     * 刷新选区可视化
     *
     * 默认用半透明玻璃盒子 (见 [AreaDisplay]), 关掉 `region-display` 或盒子建不出来时退回
     * 粒子边框。
     *
     * @param player 玩家
     */
    fun showSelection(player: Player) {
        val region = getWERegion(player)
        if (region == null) {
            hideSelection(player)
            return
        }
        if (displayEnabled()) {
            stopParticle(player)
            areaDisplay.show(
                player,
                region,
                displayMaterial(),
                ownerOnly = displayOwnerOnly(),
                durationTicks = displayDurationTicks()
            ) { showParticle(player) }
        } else {
            areaDisplay.hide(player)
            showParticle(player)
        }
    }

    /**
     * 收掉选区可视化 (盒子和粒子都收)
     */
    fun hideSelection(player: Player) {
        stopParticle(player)
        areaDisplay.hide(player)
    }

    /**
     * 显示粒子
     *
     * 边框粒度随选区尺寸自适应 (最长边固定 100 个点, 总点数另有上限), 而且**按区块合并成一次任务**:
     * 原来是每个粒子点提交一个区域任务, 一个百来格的选区每秒钟会提交两千多个任务,
     * 区域线程光排队就卡住了。
     *
     * 显示时长跟着选区: 选区被清除或玩家退出才停 (与玻璃盒一致)。
     *
     * @param player 玩家
     */
    private fun showParticle(player: Player) {
        stopParticle(player)
        val pos1 = getPlayerPos1(player) ?: return
        val pos2 = getPlayerPos2(player) ?: return
        val particle = resolveParticle(settingManager.getWorldEditSettings().getString("region-particle")) ?: return
        val world = player.world
        val step = max(0.5, max(abs(pos1.x - pos2.x), max(abs(pos1.y - pos2.y), abs(pos1.z - pos2.z))) / PARTICLE_POINTS_PER_EDGE)
        val cube = createCube(pos1.toProxyLocation(), pos2.toProxyLocation(), step)
        var points = cube.calculateLocations().map { it.toBukkitLocation() }
        // "最长边 100 点"只约束了单条边, 立方体有 12 条边, 大选区算出来的总点数还是很多 ——
        // 每 20 tick 要给每个有粒子的区块排一个任务, 点数就是任务数。总点数超上限就加大步长重算。
        var reductions = 0
        while (points.size > PARTICLE_MAX_POINTS && reductions++ < PARTICLE_MAX_REDUCTIONS) {
            cube.step = cube.step * points.size / PARTICLE_MAX_POINTS.toDouble()
            points = cube.calculateLocations().map { it.toBukkitLocation() }
        }
        // 按区块分组, 每秒每个区块只提交一个任务
        val pointsByChunk = points.groupBy { (it.blockX shr 4) to (it.blockZ shr 4) }
        particleTasks[player] = submitAsync(period = 20) {
            // 粒子跟着选区走 (和玻璃盒一致): 只在自己下线时停, 选区被清除时由 stopParticle 停。
            // 原来还有一条 60 秒自停, 那样会变成"选区还在、提示没了", 和"成功后保留选区"矛盾。
            if (!player.isOnline) {
                cancel()
                particleTasks.remove(player)
                return@submitAsync
            }
            pointsByChunk.forEach { (chunk, locations) ->
                world.runTask(chunk.first * 16.0, chunk.second * 16.0, Runnable {
                    if (!player.isOnline) return@Runnable
                    locations.forEach { player.spawnParticle(particle, it, 1) }
                })
            }
        }
    }

    private fun stopParticle(player: Player) {
        particleTasks.remove(player)?.cancel()
    }


    /**
     * 获取点1
     * @param player 玩家
     * @return 点1
     * @return null if not set
     */
    fun getPlayerPos1(player: Player): Location? {
        return playerpos[player]?.first
    }

    /**
     * 获取点2
     * @param player 玩家
     * @return 点2
     * @return null if not set
     */
    fun getPlayerPos2(player: Player): Location? {
        return playerpos[player]?.second
    }

    /**
     * 清空点 (玩家退出 / 主动清除时用)
     * @param player 玩家
     */
    fun clearPlayerPos(player: Player) {
        playerpos.remove(player)
        stopParticle(player)
        // 用 forget 而不是 hideSelection: 选区彻底没了, 顺手把代号表的条目一起清掉,
        // 免得它按 UUID 只增不删
        areaDisplay.forget(player)
        endEdit(player)
    }

    /**
     * 收掉所有玩家的选区显示体 (关插件/重载时用, 免得留下孤儿实体)
     */
    fun clearDisplays() {
        particleTasks.keys.toList().forEach { stopParticle(it) }
        areaDisplay.hideAll()
    }

    /**
     * 判断是否有区域
     * @param player 玩家
     */
    fun hasRegion(player: Player):Boolean{
        if (playerpos[player] == null || playerpos[player]?.first == null || playerpos[player]?.second == null) return false
        return getWERegion(player) != null
    }

    /**
     * 获取区域大小
     * @param player 玩家
     * @return 区域大小
     */
    fun getCountInRegion(player: Player): Int {
        val region = getWERegion(player) ?: return 0
        return region.volume.toInt()
    }

    /**
     * 填充选区。
     *
     * Folia 下一个区域线程只能改自己拥有的区块, 越界修改会被 WorldEdit 静默丢弃 (不报错),
     * 所以选区按区块拆开, 每块在它自己的区域线程上"先读后写"。各区块互不重叠, 读到的
     * 一定还是填充前的方块, 可以直接当撤销快照。大选区再按 [EditBudget] 一片一片来,
     * 不会把区域线程一次占住。
     */
    fun trySetArea(player: Player, material: Material) {
        val region = validatedRegion(player, needBreak = false) ?: return
        val blockType = resolveBlockType(material) ?: run {
            player.sendLang("message-invalid-material", pluginId to "pluginId", material.name to "material")
            return
        }
        val amount = region.volume.toInt()
        if (amount <= 0) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        if (!beginEdit(player)) return
        // 检查与扣费必须在一次原子操作里完成: 原来是"查够不够"和"扣"两次调用,
        // 中间别的线程也能通过检查, 同一个玩家并发操作时会被各扣一次。
        // 先扣材料, 有区块没执行成功再按体积退还。
        if (!PlayerWorldEdit.packManager.tryConsume(player, material, amount)) {
            player.sendLang("message-materialpack-not-enough",
                pluginId to "pluginId",
                material.displayName(player) to "material",
                amount to "amount")
            endEdit(player)
            return
        }
        prepareEdit(amount, player)
        val actor = actorOf(player)
        val regions = region.splitByChunk()
        val snapshots = AtomicReferenceArray<Array<BlockState?>?>(regions.size)
        runOnChunkThreads(regions, player.world,
            work = { index, slice ->
                val chunk = regions[index]
                val states = snapshots.get(index)
                    ?: arrayOfNulls<BlockState>(chunk.volume.toInt()).also { snapshots.set(index, it) }
                WorldEditTool.readBlocksInto(player.world, slice.region, states, slice.offset)
                WorldEditTool.setBlocks(player.world, slice.region, blockType.defaultState, actor)
            },
            done = { _ ->
                onPlayerThread(player) {
                    finishEdit(
                        player,
                        regions,
                        regions.indices.map { snapshots[it] },
                        regions.map { it.volume.toInt() },
                        material,
                        amount,
                        "message-set-susses"
                    )
                }
            }
        )
    }

    /**
     * 替换选区内的方块。
     *
     * 分两轮逐区块执行: 第一轮只读 (统计实际会被替换的方块数量并留作撤销快照), 校验材料
     * 扣费后再跑第二轮写入。收费按实际替换数量算, 收费和写入用的是同一份快照。
     */
    fun tryReplaceArea(player: Player, from: Material, to: Material) {
        val region = validatedRegion(player, needBreak = true) ?: return
        val fromType = resolveBlockType(from)
        val toType = resolveBlockType(to)
        if (fromType == null || toType == null) {
            player.sendLang("message-invalid-material", pluginId to "pluginId",
                (if (fromType == null) from else to).name to "material")
            return
        }
        if (!beginEdit(player)) return
        val regions = region.splitByChunk()
        val snapshots = AtomicReferenceArray<Array<BlockState?>?>(regions.size)
        runOnChunkThreads(regions, player.world,
            work = { index, slice ->
                val chunk = regions[index]
                val states = snapshots.get(index)
                    ?: arrayOfNulls<BlockState>(chunk.volume.toInt()).also { snapshots.set(index, it) }
                WorldEditTool.readBlocksInto(player.world, slice.region, states, slice.offset)
            },
            done = { _ ->
                onPlayerThread(player) {
                    val targets = regions.indices.filter { isComplete(snapshots[it]) }
                    // 每个区块实际会被替换的数量, 收费和失败退费都以它为准
                    val costs = targets.map { replaceTargets(snapshots[it]!!, fromType.defaultState) }
                    val amount = costs.sum()
                    if (amount <= 0) {
                        player.sendLang("message-replace-none", pluginId to "pluginId", from.displayName(player) to "material")
                        endEdit(player)
                        return@onPlayerThread
                    }
                    // 同 trySetArea: 查与扣合成一次原子操作
                    if (!PlayerWorldEdit.packManager.tryConsume(player, to, amount)) {
                        player.sendLang("message-materialpack-not-enough",
                            pluginId to "pluginId",
                            to.displayName(player) to "material",
                            amount to "amount")
                        endEdit(player)
                        return@onPlayerThread
                    }
                    prepareEdit(amount, player)
                    val actor = actorOf(player)
                    val batchRegions = targets.map { regions[it] }
                    val batchSnapshots = targets.map { snapshots[it] }
                    runOnChunkThreads(batchRegions, player.world,
                        work = { index, slice ->
                            WorldEditTool.replaceBlocks(player.world, slice.region, fromType.defaultState, toType.defaultState, actor)
                        },
                        done = { _ ->
                            onPlayerThread(player) {
                                finishEdit(
                                    player,
                                    batchRegions,
                                    batchSnapshots,
                                    costs,
                                    to,
                                    amount,
                                    "message-replace-susses"
                                )
                            }
                        }
                    )
                }
            }
        )
    }

    /**
     * 生成墙: 选区四周的四面竖墙 (等同 WorldEdit 的 //walls, 不含顶和底)
     *
     * @param player 玩家
     * @param material 方块 (空气 = 清空这些位置)
     */
    fun tryGenerateWall(player: Player, material: Material) {
        generateShape(player, ShapeKind.WALL, material, radius = 0, height = 0, successKey = "message-wall-susses")
    }

    /**
     * 生成实心圆柱 (轴竖直 Y)
     *
     * 底面在选区最小 Y、水平中心取选区中心、向上高 [height]; 超出选区的部分一律裁掉。
     *
     * @param radius 半径, 1..[SHAPE_MAX_SIZE]
     * @param height 高度, 1..[SHAPE_MAX_SIZE]
     */
    fun tryGenerateCylinder(player: Player, material: Material, radius: Int, height: Int) {
        if (!validateShapeSize(player, radius, height)) return
        generateShape(player, ShapeKind.CYLINDER, material, radius, height, "message-cyl-susses")
    }

    /** 生成空心圆柱: 只写半径处那一圈壳 (径向厚 1), 内部不动 */
    fun tryGenerateHollowCylinder(player: Player, material: Material, radius: Int, height: Int) {
        if (!validateShapeSize(player, radius, height)) return
        generateShape(player, ShapeKind.HOLLOW_CYLINDER, material, radius, height, "message-hcyl-susses")
    }

    /**
     * 半径 / 高度的范围校验
     *
     * 下限 1 是真约束 (0 或负数没有意义); 上限 [SHAPE_MAX_SIZE] 只是 sanity cap —— 形状本来就被
     * 裁剪在选区内, 给得再大也只是"填满选区", 卡个上限是为了挡住手滑敲出的一长串数字。
     */
    private fun validateShapeSize(player: Player, radius: Int, height: Int): Boolean {
        if (radius in 1..SHAPE_MAX_SIZE && height in 1..SHAPE_MAX_SIZE) return true
        player.sendLang("message-shape-invalid-size", pluginId to "pluginId", SHAPE_MAX_SIZE to "max")
        return false
    }

    /**
     * 形状生成的公共流程 —— 和 [trySetArea] 走**同一条写方块链路**:
     * validatedRegion (含 Dominion fail-closed) → beginEdit → 计费 → tryConsume →
     * runOnChunkThreads → finishEdit (撤销记录 + 成功保留选区)。
     *
     * 计费在玩家线程上做, 但只是纯算术数一遍 X/Z 每列 (见 [ShapeVolume.countIn]): 不读世界、
     * 不遍历体积。真正的写入全部丢进 [runOnChunkThreads], 在区块所属区域线程上执行并受
     * [EditBudget] 每 tick 限流, 与 /pwe set 的开销特征一致。
     */
    private fun generateShape(
        player: Player,
        kind: ShapeKind,
        material: Material,
        radius: Int,
        height: Int,
        successKey: String
    ) {
        val region = validatedRegion(player, needBreak = false) ?: return
        val blockType = resolveBlockType(material) ?: run {
            player.sendLang("message-invalid-material", pluginId to "pluginId", material.name to "material")
            return
        }
        // 用 BlockVector3.x()/y()/z(): getX()/getY()/getZ() 在 7.4.6 已经标记弃用, 新代码别再添警告
        val min = region.minimumPoint
        val max = region.maximumPoint
        val shape = ShapeVolume(
            kind,
            min.x(), max.x(),
            min.y(), max.y(),
            min.z(), max.z(),
            radius,
            // 墙的高度就是整个选区 (//walls 取全部 Y 范围); 圆柱用玩家给的高度
            if (kind == ShapeKind.WALL) max.y() - min.y() + 1 else height
        )
        val regions = region.splitByChunk()
        // 计费: 每个区块各数自己那一块里的形状方块, 加总就是总量。失败退费按区块退, 口径一致。
        // 这里数的是"列 × 层", 与下面 work 里逐块判定的结果严格相等 (见 ShapeVolume.countIn)
        val costs = regions.map { chunk ->
            shape.countIn(chunk.minimumPoint.x(), chunk.maximumPoint.x(), chunk.minimumPoint.z(), chunk.maximumPoint.z())
        }
        val amount = costs.sum()
        if (amount <= 0) {
            // 形状一点都没落在选区里 (例如 1x1 的选区放半径 1 的空心圆柱): 别白留一条 0 材料的撤销记录
            player.sendLang("message-shape-none", pluginId to "pluginId")
            return
        }
        if (!beginEdit(player)) return
        // 同 trySetArea: 查与扣合成一次原子操作; 先扣, 有区块没跑成再按区块退
        if (!PlayerWorldEdit.packManager.tryConsume(player, material, amount)) {
            player.sendLang("message-materialpack-not-enough",
                pluginId to "pluginId",
                material.displayName(player) to "material",
                amount to "amount")
            endEdit(player)
            return
        }
        prepareEdit(amount, player)
        val actor = actorOf(player)
        val snapshots = AtomicReferenceArray<Array<BlockState?>?>(regions.size)
        // 绑定一次, 别在每个切片里现建函数引用
        val inShape: (Int, Int, Int) -> Boolean = shape::contains
        runOnChunkThreads(regions, player.world,
            work = { index, slice ->
                val chunk = regions[index]
                val states = snapshots.get(index)
                    ?: arrayOfNulls<BlockState>(chunk.volume.toInt()).also { snapshots.set(index, it) }
                // 快照照旧读整块: 撤销要把整块还原回去, 没改过的方块还原回去也没有副作用
                WorldEditTool.readBlocksInto(player.world, slice.region, states, slice.offset)
                WorldEditTool.setBlocksIf(player.world, slice.region, blockType.defaultState, actor, inShape)
            },
            done = { _ ->
                onPlayerThread(player) {
                    finishEdit(
                        player,
                        regions,
                        regions.indices.map { snapshots[it] },
                        costs,
                        material,
                        amount,
                        successKey
                    )
                }
            }
        )
    }

    /** 形状的水平投影种类 */
    private enum class ShapeKind { WALL, CYLINDER, HOLLOW_CYLINDER }

    /**
     * 一次形状生成的几何定义。
     *
     * **写入判定 ([contains]) 与计费 ([countIn]) 共用同一份判定** —— 任务 9C 的教训是"统计口径 ≠
     * 执行口径"就会多扣/少扣材料, 所以这里只留 [columnContains] 一个谓词, 计费只是把它按 X/Z
     * 每列数一遍。全部是纯算术, 一次世界访问都没有。
     *
     * 坐标全部取整到方块坐标 (和 /pwe set 一样按方块算), 不引入小数偏移。
     */
    private class ShapeVolume(
        private val kind: ShapeKind,
        private val minX: Int,
        private val maxX: Int,
        private val minY: Int,
        private val maxY: Int,
        private val minZ: Int,
        private val maxZ: Int,
        radius: Int,
        height: Int
    ) {
        /** 形状最上面一层的 Y; 可以超出选区顶, 计费与写入时再按选区裁掉 */
        private val topY = minY + height - 1

        // 选区的水平中心。选区覆盖 [minX, maxX+1), 方块 x 的中心是 x+0.5, 所以方块到中心的水平
        // 距离正好是 x - (minX+maxX)/2 —— 直接用方块坐标算就行, 不需要再 +0.5
        private val centerX = (minX + maxX) / 2.0
        private val centerZ = (minZ + maxZ) / 2.0
        private val outerRadiusSquared = (radius * radius).toDouble()
        private val innerRadiusSquared = (radius - 1).toDouble().let { it * it }

        /**
         * 这一列 (x, z) 的水平投影在不在形状里 (纯算术, 与 Y 无关)
         *
         * 墙按"贴到选区某一条边"算, 所以 1 格宽的选区整条都算墙 (和 //walls 一致)。
         */
        fun columnContains(x: Int, z: Int): Boolean {
            return when (kind) {
                ShapeKind.WALL -> x == minX || x == maxX || z == minZ || z == maxZ
                ShapeKind.CYLINDER -> distanceSquared(x, z) <= outerRadiusSquared
                // 空心 = 半径 r 的圆盘减去半径 r-1 的圆盘, 也就是最外那一圈 (径向厚度 1)。
                // r=1 时内圈半径为 0, 只剩中心列的正交邻居 —— 这是"壳"的定义, 不是漏算。
                ShapeKind.HOLLOW_CYLINDER ->
                    distanceSquared(x, z).let { it > innerRadiusSquared && it <= outerRadiusSquared }
            }
        }

        /** 方块在不在形状里 —— 写入判定用的就是它 */
        fun contains(x: Int, y: Int, z: Int): Boolean = y in minY..topY && columnContains(x, z)

        /**
         * 选区 (X/Z 取 [x1]..[x2] / [z1]..[z2]) 里有多少方块落在形状里, 纯算术
         *
         * = 落在形状里的列数 × 每列落在形状里的层数。因为 [contains] 是"列判定 AND Y ∈
         * [minY, topY]", 每列的层数是同一个常数, 所以这个乘积与逐块调用 [contains] 数出来的
         * 结果**严格相等** —— 但不用遍历整个体积, 也不读世界。
         */
        fun countIn(x1: Int, x2: Int, z1: Int, z2: Int): Int {
            val layers = (minOf(topY, maxY) - minY + 1).coerceAtLeast(0)
            if (layers == 0) return 0
            var columns = 0
            for (x in x1..x2) {
                for (z in z1..z2) {
                    if (columnContains(x, z)) columns++
                }
            }
            return columns * layers
        }

        private fun distanceSquared(x: Int, z: Int): Double {
            val dx = x - centerX
            val dz = z - centerZ
            return dx * dx + dz * dz
        }
    }

    /**
     * 朝玩家朝向的那一面扩 / 缩选区
     *
     * 这是**选区编辑**, 不是方块编辑: 一个方块都不写, 所以不扣材料、不记撤销, 也因此不碰
     * [beginEdit]/[tryConsume]/[runOnChunkThreads]/[finishEdit] 那条写方块链路 —— 那条链路
     * 的每一个环节都以"要改的方块"为对象, 这里没有对象; 白走一遍反而会扣掉一次材料。
     *
     * 只动视线吸附轴 (|分量|最大的 ±X/±Y/±Z) 上对应的那一面, 另外 5 个面的坐标原样保留;
     * pos1/pos2 哪个大哪个小都行, 每次现算 —— 注意 CuboidRegion 的构造器**不会**把两点摆成
     * 最小/最大 (它只在**持有世界时**把 Y 夹进世界高度; [getWERegion] 走的是 2 参构造器, 世界为
     * null, 此时 clampY 拿到的是 Integer.MIN/MAX_VALUE, 等于没夹), 但 getMinimumPoint()/
     * getMaximumPoint()/getVolume() 都是现算两点的 min/max, 所以上下颠倒的选区在 WorldEdit 侧依旧算得对。
     *
     * @param player 玩家
     * @param blocks 扩缩的格数, 内部 clamp 到 1..[SELECTION_EDIT_MAX_BLOCKS]
     * @param expand true=扩展, false=收缩
     */
    fun resizeSelection(player: Player, blocks: Int, expand: Boolean) {
        // 和 set/replace 同一个前置校验: 没选齐两点 -> message-no-region; 有领地且没权限 -> 拒绝
        validatedRegion(player, needBreak = false) ?: return
        val pos1 = getPlayerPos1(player) ?: return
        val pos2 = getPlayerPos2(player) ?: return
        val steps = blocks.coerceIn(1, SELECTION_EDIT_MAX_BLOCKS)
        // 选区本来就按方块坐标存 (点的中心 5.5 落到方块 5), 这里同样取整: 全程按方块算, 不引入小数偏移
        val first = intArrayOf(floor(pos1.x).toInt(), floor(pos1.y).toInt(), floor(pos1.z).toInt())
        val second = intArrayOf(floor(pos2.x).toInt(), floor(pos2.y).toInt(), floor(pos2.z).toInt())
        val (axis, positive) = snapAxis(player.eyeLocation.direction)
        // 大小关系现算, 不假设 pos1 就是小的一头。
        // pos1IsFirst 必须在这里定下来: lower/upper 是 first/second 的别名, 下面的扩缩会就地改掉
        // 数组内容, 等移动完再比较就只能读到改后的值 —— 收缩把该轴压成单层时两点在该轴上相等,
        // pos1/pos2 的标签归属会跟着互换
        val pos1IsFirst = first[axis] <= second[axis]
        val lower = if (pos1IsFirst) first else second
        val upper = if (pos1IsFirst) second else first
        if (expand) {
            val moved = if (positive) upper else lower
            // 记下被移动这个点移动前的 Y, 用来区分"被世界高度完全挡住"和"只被吃掉几格"
            val movedYBefore = moved[AXIS_Y]
            moved[axis] += if (positive) steps else -steps
            // 世界高度夹取 (只做 Y): 越界的选区点会让框画到世界外, 而且之后 /pwe set 是按选区体积
            // 扣材料的 —— 界外那一层根本放不上, 等于白扣。被世界高度吃掉的那几格就丢掉, 但夹取后的
            // 结果照旧应用 (不整体放弃), 只额外提示一句, 免得玩家以为命令没生效。
            // X/Z 不做边界处理 (那是另一个已知限制, 不在本次修复范围)。
            if (axis == AXIS_Y) {
                // 绕 WorldEdit 取边界: 编译期用的精简 API 里 org.bukkit.World 没有 getMinHeight()/
                // getMaxHeight(); BukkitWorld.getMinY()/getMaxY() 的实现正好就是
                // world.getMinHeight() 和 world.getMaxHeight() - 1 (上界即最高可放置层)
                val weWorld = BukkitAdapter.adapt(player.world)
                val clamped = moved[AXIS_Y].coerceIn(weWorld.minY, weWorld.maxY)
                if (clamped == movedYBefore) {
                    // 这一面已经顶在世界边界上, 一格都推不动: 只提示被世界高度挡住就收工。不能走到
                    // 下面那条成功提示 —— 坐标一步没动, 再报一句"已扩展选区到 ..."会自相矛盾。
                    player.sendLang("message-selection-expand-limit",
                        pluginId to "pluginId",
                        clamped to "y")
                    return
                }
                if (clamped != moved[AXIS_Y]) {
                    moved[AXIS_Y] = clamped
                    player.sendLang("message-selection-expand-limit",
                        pluginId to "pluginId",
                        clamped to "y")
                }
            }
        } else {
            // 收缩的下限: 该轴至少留 1 格。会缩成 0 格或反向就整体不动作 —— 宁可不动, 也不要
            // 出现空选区或反向选区 (反向之后"扩展"会朝反方向长, 玩家看到的框和预期完全相反)
            val extent = upper[axis] - lower[axis] + 1
            if (extent - steps < 1) {
                player.sendLang("message-selection-contract-limit",
                    pluginId to "pluginId",
                    extent to "extent")
                return
            }
            if (positive) upper[axis] -= steps else lower[axis] += steps
        }
        // 两个 setter 各自触发一次 showSelection (重画选区框), 位置和原来一样:
        // 点1 传 pos1 算出来的坐标, 点2 传 pos2 算出来的
        val newPos1 = if (pos1IsFirst) lower else upper
        val newPos2 = if (pos1IsFirst) upper else lower
        setPlayerPos1(player, locationOf(player, newPos1))
        setPlayerPos2(player, locationOf(player, newPos2))
        val key = if (expand) "message-selection-expand-susses" else "message-selection-contract-susses"
        // 坐标和方块数都告诉玩家: 他刚动的是哪一面, 现在选中的是哪一块。
        // 方块数按移动后的两个点现算 —— 不能拿上面的 region.volume, 那是移动前的选区
        val amount = (abs(newPos1[0] - newPos2[0]) + 1).toLong() *
            (abs(newPos1[1] - newPos2[1]) + 1).toLong() *
            (abs(newPos1[2] - newPos2[2]) + 1).toLong()
        player.sendLang(key,
            pluginId to "pluginId",
            newPos1[0] to "x",
            newPos1[1] to "y",
            newPos1[2] to "z",
            amount.toInt() to "amount")
    }

    /**
     * 把视线方向吸附到最接近的 6 个轴向之一
     *
     * 取视线向量里**绝对值最大**的那个分量决定轴; 平局时按 X > Y > Z 的固定顺序取先遇到的那个,
     * 保证同一个朝向每次都算出同一面 (玩家斜着看时不会一会儿扩 X 一会儿扩 Y)。
     *
     * @return 轴 (0=X, 1=Y, 2=Z) 与该轴的正负方向
     */
    private fun snapAxis(direction: Vector): Pair<Int, Boolean> {
        val components = doubleArrayOf(direction.x, direction.y, direction.z)
        var axis = 0
        for (candidate in 1..2) {
            if (abs(components[candidate]) > abs(components[axis])) {
                axis = candidate
            }
        }
        return axis to (components[axis] >= 0)
    }

    /** 把方块坐标拼成 Location (setPlayerPos1/2 只取整数部分, 小数位没有意义) */
    private fun locationOf(player: Player, block: IntArray): Location {
        return Location(player.world, block[0].toDouble(), block[1].toDouble(), block[2].toDouble())
    }

    /**
     * 撤销最近的操作
     *
     * 必须**严格串行**: 第 i 条完全还原、返还落库之后才取第 i+1 条。
     * [runOnChunkThreads] 是异步返回的, 原来的 repeat 会把 N 组区块任务同时铺开 ——
     * 返还互相覆盖 (同一份材料包的读改写), 后一条的旧快照还会盖掉前一条刚还原的状态,
     * `/pwe undo 3` 的最终结果不可预测。
     *
     * 所以这里改成一条接一条的链式回调: 异步线程取记录 → 区块线程还原 → 玩家线程返还 →
     * 回到异步线程取吓一条。
     *
     * 某一条超时 (见 [runOnChunkThreads] 的超时兜底) 时不退还材料、也不丢记录, 而是把它放回
     * 队列并中止整轮 —— 只还原了一部分还照常退料等于白送, 直接丢掉则剩下的区块再也撤不回去。
     *
     * @param player 玩家
     * @param times 次数
     * @param onDone 整轮结束时回调一次, 参数是实际撤销的条数和"这轮是不是因为超时提前收的尾"
     */
    fun undo(player: Player, times: Int, onDone: (done: Int, timedOut: Boolean) -> Unit) {
        if (!beginEdit(player)) {
            // 和其它出口一样走 finishUndo, 保证回调线程一致 (endEdit 对没进过集合的玩家是空操作)
            finishUndo(player, 0, timedOut = false, onDone)
            return
        }
        // 第一次取记录要读文件, 丢到异步线程; 之后每一步由链自己挑线程
        submitAsync { undoStep(player, times, 0, onDone) }
    }

    private fun undoStep(player: Player, times: Int, done: Int, onDone: (done: Int, timedOut: Boolean) -> Unit) {
        if (done >= times) {
            finishUndo(player, done, timedOut = false, onDone)
            return
        }
        // poll 要读撤销记录文件, 这一步在异步线程上跑
        val record = UndoManager.poll(player) ?: return finishUndo(player, done, timedOut = false, onDone)
        val bukkitWorld = Bukkit.getWorld(record.worldName)
        if (bukkitWorld == null) {
            // 世界未加载: 把记录放回去并**中止整轮** —— 跳过后面的记录只会让撤销顺序错乱
            UndoManager.push(player, record, undoLimit())
            return finishUndo(player, done, timedOut = false, onDone)
        }
        EditBudget.configure(blocksPerTick())
        val actor = actorOf(player)
        val regions = record.batches.map { it.region }
        val started = runCatching {
            runOnChunkThreads(regions, bukkitWorld,
                work = { index, slice ->
                    WorldEditTool.restoreBlocks(bukkitWorld, slice, record.batches[index].states, actor)
                },
                done = { timedOut ->
                    // 同样不能只依赖 runOnOwnThread: 玩家中途退出时这里不执行的话,
                    // 这一条的返还就丢了, 而且整条撤销链会断在半路
                    onPlayerThread(player) {
                        if (timedOut) {
                            // 超时: 这条记录只还原了一部分, 不能按"已撤销"退还材料 (那等于白送),
                            // 也不能就这么丢掉 (剩下的区块就再也撤不回去了)。还原到快照是幂等的,
                            // 所以把记录放回队列, 让玩家再 undo 一次 —— 已还原的区块重做一遍没有副作用。
                            // 写盘是同步 IO, 丢到异步线程做。
                            warning("[PlayerWorldEdit] 撤销超时 (${player.name}), 本条记录已放回队列, 可重试")
                            submitAsync {
                                UndoManager.push(player, record, undoLimit())
                                // 告诉调用方这轮是被超时截断的: 记录已经放回去, 不是"没有可撤销的"
                                finishUndo(player, done, timedOut = true, onDone)
                            }
                        } else {
                            // 返还先落库, 再取吓一条: 两条的返还都是同一份材料包的读改写, 不能并发
                            chargeMaterial(player, record.material, -record.amount)
                            // 回到异步线程取吓一条 (那里才能读文件)
                            submitAsync { undoStep(player, times, done + 1, onDone) }
                        }
                    }
                }
            )
        }.onFailure {
            // 排队阶段就炸了: 这条当作没撤成, 结束整轮
            warning("[PlayerWorldEdit] 撤销失败: $it")
        }.isSuccess
        if (!started) {
            finishUndo(player, done, timedOut = false, onDone)
        }
    }

    /**
     * 撤销的收尾: 释放 editing 并回报次数 (所有退出路径都要走这里)
     *
     * 回调必须回到**玩家线程**: 两个调用点都在回调里给玩家发消息, 而 Folia 下从异步线程
     * 碰玩家会抛线程归属异常。玩家已经下线时没有玩家线程可回, 只能就地回调 —— 调用方那句
     * 消息发给离线玩家本来就是空操作。
     */
    private fun finishUndo(player: Player, done: Int, timedOut: Boolean, onDone: (done: Int, timedOut: Boolean) -> Unit) {
        endEdit(player)
        if (!player.isOnline) {
            onDone(done, timedOut)
            return
        }
        // runOnOwnThread 自身失败 (实体已退役等) 时也不能让调用方永远等不到结果
        if (runCatching { player.runOnOwnThread { onDone(done, timedOut) } }.isFailure) {
            onDone(done, timedOut)
        }
    }

    /**
     * 需要玩家数据的收尾都从这里跑: 玩家在线就切回他自己的线程 (要发消息、碰玩家对象),
     * 已经下线就地在当前线程跑完。
     *
     * 不能只依赖 [runOnOwnThread]: 玩家中途退出时那个任务不会再执行, 收尾就整个丢了 ——
     * 材料扣了不退、撤销记录也不落库 (2026-10-13 的任务 9D)。退费和落库只依赖 uuid 和
     * 世界数据, 本来就不需要玩家对象; 给一个已经离开的玩家发提示是空操作。
     * [runOnOwnThread] 自身失败 (实体已退役等) 时同样就地跑, 否则收尾一样会丢。
     */
    private fun onPlayerThread(player: Player, block: () -> Unit) {
        if (!player.isOnline) {
            block()
            return
        }
        if (runCatching { player.runOnOwnThread(block) }.isFailure) {
            block()
        }
    }

    /**
     * 编辑收尾 (走 [onPlayerThread]): 记录撤销、退还失败部分的材料并提示
     *
     * @param snapshots 与 regions 一一对应, null 或含 null 表示该区块没跑完
     * @param costs 与 regions 一一对应, 表示该区块已经扣掉的材料数量 (失败时按它退还)
     */
    private fun finishEdit(
        player: Player,
        regions: List<CuboidRegion>,
        snapshots: List<Array<BlockState?>?>,
        costs: List<Int>,
        material: Material,
        amount: Int,
        successKey: String
    ) {
        endEdit(player)
        val succeeded = regions.indices.filter { isComplete(snapshots[it]) }
        val refund = regions.indices.filter { !isComplete(snapshots[it]) }.sumOf { costs[it] }
        if (refund > 0) {
            chargeMaterial(player, material, -refund)
        }
        // 成功后**保留选区** (和 WorldEdit 的手感一致): 想接着改同一块地方不用重新选点。
        // 选区只在玩家退出或主动清除时清 —— 原来这里调 clearPlayerPos, 而 tryReplaceArea 的
        // "没有可替换方块"分支只 endEdit 不清选区, 两条路行为不一致。
        if (succeeded.isEmpty()) {
            player.sendLang("message-edit-failed", pluginId to "pluginId")
            return
        }
        val changed = amount - refund
        UndoManager.pushAsync(
            player,
            UndoRecord(
                player.world.name,
                succeeded.map { UndoBatch(regions[it], snapshots[it]!!.filterNotNull()) },
                material,
                changed
            ),
            undoLimit()
        )
        if (refund > 0) {
            player.sendLang("message-edit-failed", pluginId to "pluginId")
        } else {
            player.sendLang(successKey, pluginId to "pluginId")
        }
    }

    /**
     * 选区校验: 区域必须完整, 且没有领地拦截
     */
    private fun validatedRegion(player: Player, needBreak: Boolean): CuboidRegion? {
        if (!hasRegion(player)) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return null
        }
        val region = getWERegion(player) ?: return null
        val pos1 = getPlayerPos1(player) ?: return null
        val pos2 = getPlayerPos2(player) ?: return null
        when (val check = DominionHook.checkAreaEdit(player, pos1, pos2, needBreak = needBreak)) {
            is DominionCheck.Allowed -> Unit
            is DominionCheck.Denied -> {
                player.sendLang("message-dominion-denied", pluginId to "pluginId", check.dominion to "dominion")
                return null
            }
            is DominionCheck.Failed -> {
                // 校验过程出错 = 无法确认有没有领地, 按 fail-closed 拒绝
                // (具体异常已经打进控制台, 这里只告诉玩家操作被拒)
                player.sendLang("message-dominion-check-failed", pluginId to "pluginId")
                return null
            }
        }
        return region
    }

    /**
     * 标记玩家开始编辑; 已经在跑别的选区操作就拒绝
     *
     * 大选区现在会跨很多个 tick 才做完, 玩家容易以为没生效而重复点, 两个操作叠在一起
     * 会重复扣材料、撤销记录也会错位。
     */
    private fun beginEdit(player: Player): Boolean {
        if (!editing.add(player.uniqueId)) {
            player.sendLang("message-edit-busy", pluginId to "pluginId")
            return false
        }
        return true
    }

    private fun endEdit(player: Player) {
        editing.remove(player.uniqueId)
    }

    /**
     * 按当前配置准备这次编辑: 套用每 tick 预算, 大操作提前给玩家一个"在处理"的提示
     */
    private fun prepareEdit(amount: Int, player: Player) {
        EditBudget.configure(blocksPerTick())
        if (amount > EditBudget.perTick) {
            player.sendLang("message-edit-processing", pluginId to "pluginId", amount to "amount")
        }
    }

    /** 区块快照是否完整 (跑失败了会留下 null) */
    private fun isComplete(states: Array<BlockState?>?): Boolean {
        return states != null && states.none { it == null }
    }

    /**
     * 给 EditSession 挂的玩家 actor, 关掉时返回 null (改回"不带身份"的编辑)
     *
     * CoreProtect 的 WorldEdit 记录只看 `EditSessionEvent.getActor() != null`, 不带 actor 的
     * session 它整条跳过, 于是插件改了几十万方块 CoreProtect 一条都没记 (2026-10-12 的事故)。
     * 代价是大范围操作会往 CoreProtect 写同样数量的记录, 觉得吵可以用这个开关关掉。
     */
    private fun actorOf(player: Player): Actor? {
        if (!settingManager.getWorldEditSettings().getBoolean(COREPROTECT_LOGGING_KEY, true)) {
            return null
        }
        return runCatching { BukkitAdapter.adapt(player) }.getOrNull()
    }

    /**
     * 增减创世神背包里的材料 (空气不计数)
     * @param delta 正数为扣除, 负数为退还
     */
    private fun chargeMaterial(player: Player, material: Material, delta: Int) {
        if (material.isAir || delta == 0) return
        if (delta > 0) {
            PlayerWorldEdit.packManager.removeMaterial(player, material, delta)
        } else {
            PlayerWorldEdit.packManager.addMaterial(player, material, -delta)
        }
    }

    /**
     * 统计快照里有多少方块会被替换
     */
    private fun replaceTargets(states: Array<BlockState?>, from: BlockState): Int {
        val fromBase = from.toBaseBlock()
        return states.count { it != null && it.toBaseBlock() == fromBase }
    }

    /**
     * 解析方块类型
     *
     * WorldEdit 注册表的键带命名空间 (minecraft:stone), Bukkit 的 Material 名没有,
     * 直接传裸名会查不到 (注册表不做补全)
     */
    private fun resolveBlockType(material: Material): BlockType? {
        val id = material.name.lowercase()
        return BlockTypes.get("minecraft:$id") ?: BlockTypes.get(id)
    }

    private fun undoLimit(): Int {
        return settingManager.getWorldEditSettings().getInt("undo-limit").takeIf { it > 0 } ?: 10
    }

    /** 全服每 tick 允许改动的方块总数 */
    private fun blocksPerTick(): Int {
        return settingManager.getWorldEditSettings().getInt("blocks-per-tick").takeIf { it > 0 } ?: EditBudget.perTick
    }

    /** 是否用半透明玻璃盒子做选区可视化 (关掉则用粒子边框) */
    private fun displayEnabled(): Boolean {
        return settingManager.getWorldEditSettings().getBoolean(DISPLAY_KEY, true)
    }

    private fun displayMaterial(): Material {
        val raw = settingManager.getWorldEditSettings().getString(DISPLAY_MATERIAL_KEY)
        return raw?.let { parseBlockOrNull(it) } ?: Material.WHITE_STAINED_GLASS
    }

    /** 选区盒子是否只发给选区主人 */
    private fun displayOwnerOnly(): Boolean {
        return settingManager.getWorldEditSettings().getBoolean(DISPLAY_OWNER_ONLY_KEY, true)
    }

    /** 选区盒子显示多少秒后自动收掉 (0 表示不自动收) */
    private fun displayDurationTicks(): Long {
        val seconds = settingManager.getWorldEditSettings().getInt(DISPLAY_DURATION_KEY, DISPLAY_DURATION_DEFAULT)
        return seconds.coerceAtLeast(0) * 20L
    }

    /**
     * 获取区域
     * @param player 玩家
     * @return 区域
     */
    private fun getWERegion(player: Player): CuboidRegion? {
        val pos1 = getPlayerPos1(player) ?: return null
        val pos2 = getPlayerPos2(player) ?: return null
        val vec1 = BlockVector3.at(pos1.x, pos1.y, pos1.z)
        val vec2 = BlockVector3.at(pos2.x, pos2.y, pos2.z)
        return CuboidRegion(vec1, vec2)
    }

    /**
     * 粒子名跨版本解析
     *
     * 1.20.5 起粒子枚举改名 (VILLAGER_HAPPY -> HAPPY_VILLAGER 等), 所以按名字查:
     * 配置里写新名或旧名都能用, 都解析不到时退回 CRIT (各版本都有)
     */
    private fun resolveParticle(rawName: String?): Particle? {
        val name = rawName?.trim()?.uppercase().orEmpty().ifEmpty { "VILLAGER_HAPPY" }
        for (candidate in listOfNotNull(name, PARTICLE_ALIASES[name])) {
            runCatching { Particle.valueOf(candidate) }.getOrNull()?.let { return it }
        }
        return runCatching { Particle.valueOf("CRIT") }.getOrNull()
    }

    private companion object {

        /** 是否用半透明玻璃盒子做选区可视化 */
        const val DISPLAY_KEY = "region-display"

        /** 盒子材质 (默认白色染色玻璃) */
        const val DISPLAY_MATERIAL_KEY = "region-display-material"

        /** 是否只把盒子发给选区主人 */
        const val DISPLAY_OWNER_ONLY_KEY = "region-display-owner-only"

        /** 盒子显示的秒数 */
        const val DISPLAY_DURATION_KEY = "region-display-duration"

        /**
         * 盒子显示时长的默认值: 0 = 不自动收
         *
         * 成功后保留选区 (8A) 之后, 盒子应该跟着选区一起存在 —— 选区被清除或玩家退出时才收,
         * 否则玩家会看到"选区还在、盒子没了"。这是默认行为的变更 (原来是 10 秒)。
         * 服务器配置里显式写了值的仍以配置为准。
         */
        const val DISPLAY_DURATION_DEFAULT = 0

        /** 是否让外部 WE 记录插件 (CoreProtect 等) 按玩家身份记录本插件的编辑 */
        const val COREPROTECT_LOGGING_KEY = "coreprotect-logging"

        /**
         * 一次扩 / 缩的格数上限
         *
         * 选区编辑不写方块, 理论上多大都行; 设上限是为了防止手滑 (例如把 1000 敲成 10000)
         * 一下子把选区撑到几个区块以外 —— 之后一个 set 就要跑很久。
         */
        const val SELECTION_EDIT_MAX_BLOCKS = 100

        /**
         * 圆柱半径 / 高度的上限
         *
         * 只是 sanity cap: 形状本来就被裁剪在选区内, 给得再大也只是"填满选区"; 卡个上限是为了
         * 挡住手滑敲出来的一长串数字。
         */
        const val SHAPE_MAX_SIZE = 256

        /** 轴索引里的 Y (与 [snapAxis] 的 0=X, 1=Y, 2=Z 顺序一致) —— 6 个面里只有 Y 需要世界高度夹取 */
        const val AXIS_Y = 1

        /** 选区粒子边框在最长边上画多少个点 (其余边按同样步长, 总点数因此有上限) */
        const val PARTICLE_POINTS_PER_EDGE = 100.0

        /** 粒子边框的总点数上限 (超过就加大步长) —— 点数直接决定每 20 tick 提交多少个区块任务 */
        const val PARTICLE_MAX_POINTS = 2000

        /** 为了压到总点数上限, 最多重算几次 (防止步长算法意外不收敛时死循环) */
        const val PARTICLE_MAX_REDUCTIONS = 5

        /** 旧名 <-> 新名 (1.20.5 起的粒子枚举改名), 双向可查 */
        val PARTICLE_ALIASES: Map<String, String> = buildMap {
            listOf(
                "VILLAGER_HAPPY" to "HAPPY_VILLAGER",
                "VILLAGER_ANGRY" to "ANGRY_VILLAGER",
                "EXPLOSION_NORMAL" to "POOF",
                "EXPLOSION_LARGE" to "EXPLOSION",
                "EXPLOSION_HUGE" to "EXPLOSION_EMITTER",
                "FIREWORKS_SPARK" to "FIREWORK",
                "SMOKE_NORMAL" to "SMOKE",
                "SMOKE_LARGE" to "LARGE_SMOKE",
                "WATER_BUBBLE" to "BUBBLE",
                "WATER_SPLASH" to "SPLASH",
                "WATER_WAKE" to "FISHING",
                "SPELL_INSTANT" to "INSTANT_EFFECT",
                "SPELL_MOB" to "ENTITY_EFFECT",
                "SPELL_MOB_AMBIENT" to "AMBIENT_ENTITY_EFFECT",
                "SPELL_WITCH" to "WITCH",
                "ENCHANTMENT_TABLE" to "ENCHANT",
                "CRIT_MAGIC" to "ENCHANTED_HIT",
                "REDSTONE" to "DUST",
                "DRIP_WATER" to "DRIPPING_WATER",
                "DRIP_LAVA" to "DRIPPING_LAVA",
                "SNOWBALL" to "ITEM_SNOWBALL",
                "SLIME" to "ITEM_SLIME",
                "TOTEM" to "TOTEM_OF_UNDYING",
            ).forEach { (oldName, newName) ->
                put(oldName, newName)
                put(newName, oldName)
            }
        }
    }

}

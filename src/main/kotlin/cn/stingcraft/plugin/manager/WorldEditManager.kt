package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.hook.DominionCheck
import cn.stingcraft.plugin.hook.DominionHook
import cn.stingcraft.plugin.until.AreaDisplay
import cn.stingcraft.plugin.until.EditBudget
import cn.stingcraft.plugin.until.RegionSlice
import cn.stingcraft.plugin.until.WorldEditTool
import cn.stingcraft.plugin.until.displayName
import cn.stingcraft.plugin.until.markAsTool
import cn.stingcraft.plugin.until.parseBlockOrNull
import cn.stingcraft.plugin.until.runOnChunkThreads
import cn.stingcraft.plugin.until.runOnOwnThread
import cn.stingcraft.plugin.until.splitByChunk
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extent.transform.BlockTransformExtent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.math.transform.Transform
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

    /**
     * 每个玩家一份的剪贴板 (copy / rotate / paste 用)
     *
     * 内存态、不持久化、不设上限 (与 WE 一致): 玩家退出时在 [clearPlayerPos] 里清掉, 关服随内存消失。
     * 值是**不可变快照**, 替换时换整份新对象而不是改里面的数组, 所以并发读的一方永远看到一份完整的
     * 剪贴板 (线程安全论证见 [ClipboardVolume])。
     */
    private val clipboards = ConcurrentHashMap<UUID, ClipboardVolume>()

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
        // 剪贴板同样按 UUID 只增不删, 退出时清掉 (不持久化, 下次登录就是空剪贴板)
        clipboards.remove(player.uniqueId)
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
                        mapOf(material to amount),
                        regions.map { mapOf(material to it.volume.toInt()) },
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
                    val costs = targets.map { mapOf(to to replaceTargets(snapshots[it]!!, fromType.defaultState)) }
                    val amount = costs.sumOf { it.values.sum() }
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
                                    mapOf(to to amount),
                                    costs,
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
        // 墙保持"选区四周"的口径不变: 选区校验 (含 Dominion fail-closed) 与以前完全一样
        val region = validatedRegion(player, needBreak = false) ?: return
        generateShape(player, ShapeKind.WALL, material, region, radius = 0, successKey = "message-wall-susses")
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
        generateCylinder(player, material, radius, height, hollow = false, successKey = "message-cyl-susses")
    }

    /** 生成空心圆柱: 只写半径处那一圈壳 (径向厚 1), 内部不动 */
    fun tryGenerateHollowCylinder(player: Player, material: Material, radius: Int, height: Int) {
        generateCylinder(player, material, radius, height, hollow = true, successKey = "message-hcyl-susses")
    }

    /**
     * 圆柱的几何与校验 —— 和墙的区别在于它**完全不看选区**
     *
     * 中心 = 玩家所在的那一格, 底面 = 玩家脚下方块 (低于世界最低高度时先往上贴到 minY), 向上
     * [height] 层; 顶面夹进世界高度 (被世界高度吃掉的那几层直接丢掉), 夹完一层都不剩 (例如站在
     * 世界顶) 时提示并返回。
     *
     * 没有选区就走不了 [validatedRegion], 但领地校验一个都不能少: 对圆柱的**外接长方体**做同一套
     * fail-closed 校验 —— 要动的方块必须全部有权限, 和 /pwe set 检查选区是同一个道理。
     */
    private fun generateCylinder(
        player: Player,
        material: Material,
        radius: Int,
        height: Int,
        hollow: Boolean,
        successKey: String
    ) {
        if (!validateShapeSize(player, radius, height)) return
        val weWorld = BukkitAdapter.adapt(player.world)
        val location = player.location
        val centerX = location.blockX
        val centerZ = location.blockZ
        val baseY = location.blockY.coerceIn(weWorld.minY, weWorld.maxY)
        // 顶面按**夹取后**的底面算 (WE 的语义): 站在世界最低高度以下时底面先往上贴, 高度不会被
        // 凭空吃掉几层; 反过来贴完仍然一层都放不下 (站在世界顶) 才交给下面的守卫兜底
        val topY = (baseY + height - 1).coerceAtMost(weWorld.maxY)
        if (baseY > topY) {
            // 夹取后一层都不剩: 直接提示返回, 别留下一条 0 材料的撤销记录
            player.sendLang("message-shape-none", pluginId to "pluginId")
            return
        }
        val world = player.world
        val box = CuboidRegion(
            BlockVector3.at(centerX - radius, baseY, centerZ - radius),
            BlockVector3.at(centerX + radius, topY, centerZ + radius)
        )
        if (!checkDominion(
                player,
                Location(world, (centerX - radius).toDouble(), baseY.toDouble(), (centerZ - radius).toDouble()),
                Location(world, (centerX + radius).toDouble(), topY.toDouble(), (centerZ + radius).toDouble()),
                needBreak = false
            )
        ) {
            return
        }
        generateShape(
            player,
            if (hollow) ShapeKind.HOLLOW_CYLINDER else ShapeKind.CYLINDER,
            material,
            box,
            radius,
            successKey
        )
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
        box: CuboidRegion,
        radius: Int,
        successKey: String
    ) {
        val blockType = resolveBlockType(material) ?: run {
            player.sendLang("message-invalid-material", pluginId to "pluginId", material.name to "material")
            return
        }
        // 用 BlockVector3.x()/y()/z(): getX()/getY()/getZ() 在 7.4.6 已经标记弃用, 新代码别再添警告
        val min = box.minimumPoint
        val max = box.maximumPoint
        val shape = ShapeVolume(
            kind,
            min.x(), max.x(),
            min.y(), max.y(),
            min.z(), max.z(),
            radius
        )
        val regions = box.splitByChunk()
        // 计费: 每个区块各数自己那一块里的形状方块, 加总就是总量。失败退费按区块退, 口径一致。
        // 这里数的是"列 × 层", 与下面 work 里逐块判定的结果严格相等 (见 ShapeVolume.countIn)
        val costs = regions.map { chunk ->
            mapOf(material to shape.countIn(chunk.minimumPoint.x(), chunk.maximumPoint.x(), chunk.minimumPoint.z(), chunk.maximumPoint.z()))
        }
        val amount = costs.sumOf { it.values.sum() }
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
                        mapOf(material to amount),
                        costs,
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
     * 坐标全部取整到方块坐标 (和 /pwe set 一样按方块算), 不引入小数偏移。圆柱的光栅化与
     * WorldEdit 的 `makeCylinder` 对齐: 半径先 +0.5, 再按**格元**判定 (见 [inCircle])。
     */
    private class ShapeVolume(
        private val kind: ShapeKind,
        private val minX: Int,
        private val maxX: Int,
        private val minY: Int,
        private val maxY: Int,
        private val minZ: Int,
        private val maxZ: Int,
        radius: Int
    ) {
        // 圆心 = 外接框的水平中心。圆柱的外接框是 [cx-r, cx+r], 宽度 2r+1 是奇数, 所以整数除法
        // 算出来正好是玩家所在的那一格 cx; 墙的判定不看圆心, 算出来是什么都无所谓。
        private val centerX = (minX + maxX) / 2
        private val centerZ = (minZ + maxZ) / 2

        // 判定阈值 (2r+1)², 即 (2(r+0.5))²: 半径先 +0.5 是 WE 的格元口径, ×4 是为了把
        // a² + b² ≤ (r+0.5)² 变成纯整数比较, 不引入浮点。r ≤ SHAPE_MAX_SIZE, 不会溢出。
        private val circleLimit = (2 * radius + 1) * (2 * radius + 1)

        /**
         * 这一列 (x, z) 的水平投影在不在形状里 (纯算术, 与 Y 无关)
         *
         * 墙按"贴到选区某一条边"算, 所以 1 格宽的选区整条都算墙 (和 //walls 一致)。
         */
        fun columnContains(x: Int, z: Int): Boolean {
            // 圆柱的两个分支都要"到圆心的格数偏移", 在这里算一次, 不在分支里各抄一遍
            val a = abs(x - centerX)
            val b = abs(z - centerZ)
            return when (kind) {
                ShapeKind.WALL -> x == minX || x == maxX || z == minZ || z == maxZ
                ShapeKind.CYLINDER -> inCircle(a, b)
                // 空心 = 圆内挖掉"往 +X、+Z 各走一格都还在圆内"的格元 (WE 的判据), 剩下的就是径向
                // 厚 1 的壳; 偏移取绝对值, 挖的四个方向对称, 形状天然四象限镜像。
                // r=1 时壳是 3×3 的圈、实心是 3×3 的盘 —— 同一套谓词的自然结果, 与 WE 一致。
                ShapeKind.HOLLOW_CYLINDER ->
                    inCircle(a, b) && !(inCircle(a + 1, b) && inCircle(a, b + 1))
            }
        }

        /** 方块在不在形状里 —— 写入判定用的就是它 */
        fun contains(x: Int, y: Int, z: Int): Boolean = y in minY..maxY && columnContains(x, z)

        /**
         * 选区 (X/Z 取 [x1]..[x2] / [z1]..[z2]) 里有多少方块落在形状里, 纯算术
         *
         * = 落在形状里的列数 × 每列落在形状里的层数。因为 [contains] 是"列判定 AND Y ∈
         * [minY, topY]", 每列的层数是同一个常数, 所以这个乘积与逐块调用 [contains] 数出来的
         * 结果**严格相等** —— 但不用遍历整个体积, 也不读世界。
         */
        fun countIn(x1: Int, x2: Int, z1: Int, z2: Int): Int {
            val layers = maxY - minY + 1
            var columns = 0
            for (x in x1..x2) {
                for (z in z1..z2) {
                    if (columnContains(x, z)) columns++
                }
            }
            return columns * layers
        }

        /**
         * 格元 (a, b) 在不在圆内 —— a / b 是**到圆心的格数偏移的绝对值**
         *
         * 与 WorldEdit `EditSession.makeCylinder` 同一套口径: 半径 +0.5 后按格元判定, 格子的
         * 近角落在半径 r+0.5 的圆内才算入圆, 整数形式 4(a² + b²) ≤ (2r+1)²。
         */
        private fun inCircle(a: Int, b: Int): Boolean = 4 * (a * a + b * b) <= circleLimit
    }

    /**
     * 朝玩家朝向的那一面扩展 / 收缩选区, 并且**改动方块**
     *
     * - 扩展: 把选区最外那一层 (朝视线那一面) 的方块**原样复制**到新增的每一层 (逐块对应)。
     *   只有非空气的方块才写、才计费 —— 源层是空气的位置写空气 = 不写。
     * - 收缩: 把被砍掉的那几层**全部清成空气** (只记账, 不产生材料返还, 与 /pwe set air 一致)。
     *
     * 两者都是写方块的操作, 所以走全 beginEdit → 扣费 → runOnChunkThreads → finishEdit
     * (撤销记录 + 成功保留选区)。领地校验对"老选区 ∪ 新选区"的包围盒做: 扩展动的是新层 (属于
     * 新选区), 收缩清掉的是被砍掉的那几层 (属于老选区), 只查新选区会漏掉后者。
     *
     * 只动视线吸附轴 (|分量|最大的 ±X/±Y/±Z) 上对应的那一面, 另外 5 个面原样保留;
     * pos1/pos2 哪个大哪个小都行, 每次现算。
     *
     * @param player 玩家
     * @param blocks 扩缩的格数, 内部 clamp 到 1..[SELECTION_EDIT_MAX_BLOCKS]
     * @param expand true=扩展, false=收缩
     */
    fun resizeSelection(player: Player, blocks: Int, expand: Boolean) {
        // 和 set/replace 一样: 没选齐两点就提示先选点。这里不走 validatedRegion —— 领地校验要等
        // 移动后的包围盒算出来再做, 见下
        val pos1 = getPlayerPos1(player) ?: run {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        val pos2 = getPlayerPos2(player) ?: run {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        val steps = blocks.coerceIn(1, SELECTION_EDIT_MAX_BLOCKS)
        // 选区本来就按方块坐标存 (点的中心 5.5 落到方块 5), 这里同样取整: 全程按方块算, 不引入小数偏移
        val first = intArrayOf(floor(pos1.x).toInt(), floor(pos1.y).toInt(), floor(pos1.z).toInt())
        val second = intArrayOf(floor(pos2.x).toInt(), floor(pos2.y).toInt(), floor(pos2.z).toInt())
        val (axis, positive) = snapAxis(player.eyeLocation.direction)
        val moved = moveSelectionFace(player, first, second, axis, positive, steps, expand) ?: return
        val min = IntArray(3) { minOf(first[it], second[it]) }
        val max = IntArray(3) { maxOf(first[it], second[it]) }
        // 领地校验覆盖**所有会被改动的方块**: 老选区 ∪ 新选区的包围盒。扩展动的是新层 (新选区 ⊇
        // 老选区), 收缩清掉的是被砍掉的层 (老选区 ⊇ 新选区), 取并集两条路都不会漏。fail-closed。
        val checkMin = IntArray(3) { minOf(min[it], moved.first[it], moved.second[it]) }
        val checkMax = IntArray(3) { maxOf(max[it], moved.first[it], moved.second[it]) }
        if (!checkDominion(player, locationOf(player, checkMin), locationOf(player, checkMax), needBreak = false)) {
            return
        }
        if (expand) expandSelection(player, moved, axis, positive, min, max)
        else contractSelection(player, moved, axis, positive, min, max)
    }

    /** [moveSelectionFace] 的结果: 移动后的两个点 (保持 pos1/pos2 的角色) */
    private class MovedFace(val first: IntArray, val second: IntArray)

    /**
     * 把选区在 [axis] 轴上朝视线那一面移动 [steps] 格 —— 扩展与收缩共用
     *
     * 只算几何、只做校验: 不写方块, 也不改传进来的数组 (改的是副本)。
     * - 扩展: 往外推。只对 Y 轴做世界高度夹取 (被吃掉的那几格丢掉, 夹取后的结果照旧应用);
     *   一格都推不动时发 limit 提示并返回 null。
     * - 收缩: 往里收。该轴会缩到 1 格以下就整体不动作, 发 limit 提示并返回 null。
     */
    private fun moveSelectionFace(
        player: Player,
        first: IntArray,
        second: IntArray,
        axis: Int,
        positive: Boolean,
        steps: Int,
        expand: Boolean
    ): MovedFace? {
        val newFirst = first.copyOf()
        val newSecond = second.copyOf()
        // 大小关系现算, 不假设 pos1 就是小的一头。pos1IsFirst 必须在这里定下来: lower/upper 是
        // 新数组的别名, 下面会就地改掉内容, 等移动完再比较就只能读到改后的值
        val pos1IsFirst = first[axis] <= second[axis]
        val lower = if (pos1IsFirst) newFirst else newSecond
        val upper = if (pos1IsFirst) newSecond else newFirst
        // 要动的是朝向那一面: 正方向动 upper, 负方向动 lower
        val target = if (positive) upper else lower
        if (!expand) {
            // 收缩的下限: 该轴至少留 1 格。会缩成 0 格或反向就整体不动作 —— 宁可不动, 也不要
            // 出现空选区或反向选区 (反向之后"扩展"会朝反方向长, 玩家看到的框和预期完全相反)
            val extent = upper[axis] - lower[axis] + 1
            if (extent - steps < 1) {
                player.sendLang("message-selection-contract-limit", pluginId to "pluginId", extent to "extent")
                return null
            }
            target[axis] += if (positive) -steps else steps
            return MovedFace(newFirst, newSecond)
        }
        val before = target[axis]
        val after = before + if (positive) steps else -steps
        // 世界高度夹取 (只做 Y): 越界的选区点会让框画到世界外, 而且之后复制是按层数算的 —— 界外
        // 那一层根本放不上, 等于白算。被世界高度吃掉的那几格就丢掉, 但夹取后的结果照旧应用
        // (不整体放弃), 只额外提示一句, 免得玩家以为命令没生效。
        // X/Z 不做边界处理 (那是另一个已知限制 REV-06, 不在本次范围)。
        val clamped = if (axis == AXIS_Y) {
            // 绕 WorldEdit 取边界: 编译期用的精简 API 里 org.bukkit.World 没有 getMinHeight()/
            // getMaxHeight(); BukkitWorld.getMinY()/getMaxY() 的实现正好就是
            // world.getMinHeight() 和 world.getMaxHeight() - 1 (上界即最高可放置层)
            val weWorld = BukkitAdapter.adapt(player.world)
            after.coerceIn(weWorld.minY, weWorld.maxY)
        } else {
            after
        }
        if (clamped == before) {
            // 这一面已经顶在世界边界上, 一格都推不动: 只提示, 不写方块也不动选区
            player.sendLang("message-selection-expand-limit", pluginId to "pluginId", clamped to "y")
            return null
        }
        target[axis] = clamped
        if (clamped != after) {
            player.sendLang("message-selection-expand-limit", pluginId to "pluginId", clamped to "y")
        }
        return MovedFace(newFirst, newSecond)
    }

    /**
     * 扩展: 把选区最外那一层 (朝视线那一面) 原样复制到新增的每一层
     *
     * 分两轮 (和 [tryReplaceArea] 同一个套路):
     * 1. 在区块线程上**只读**源层, 统计每种方块各需要多少个 —— 玩家线程一行方块都不读;
     * 2. 逐种扣材料: 任何一种不够就把已经扣掉的原样退回并整体放弃, 一个方块都不写; 全部扣成功
     *    才进第二轮写入。所以"要么全够要么全不写"成立, 不依赖"扣费期间别人不会同时扣"这种假设。
     */
    private fun expandSelection(
        player: Player,
        moved: MovedFace,
        axis: Int,
        positive: Boolean,
        min: IntArray,
        max: IntArray
    ) {
        val world = player.world
        // 源层 = 老选区在朝向那一面最外的一层; 目标面 = 移动后的那一面 (被世界高度夹过, 可能少几层)
        val sourceCoord = if (positive) max[axis] else min[axis]
        val targetCoord =
            if (positive) maxOf(moved.first[axis], moved.second[axis]) else minOf(moved.first[axis], moved.second[axis])
        val layers = abs(targetCoord - sourceCoord)
        if (!beginEdit(player)) return
        EditBudget.configure(blocksPerTick())
        // 源层沿 [axis] 平移复制, 平面内两个坐标原样对应, 所以把源层压成一维数组存起来最省事
        val plane = PlaneIndex(axis, min, max)
        val source = arrayOfNulls<BlockState>(plane.size)
        runOnChunkThreads(
            slabRegion(axis, sourceCoord, sourceCoord, min, max).splitByChunk(),
            world,
            work = { _, slice ->
                val weWorld = BukkitAdapter.adapt(world)
                for (vector in slice.region) {
                    source[plane.indexOf(vector.x(), vector.y(), vector.z())] = weWorld.getBlock(vector)
                }
            },
            done = { timedOut ->
                // 统计是纯内存操作, 顺手在区块线程上做, 玩家线程只拿结果
                val perLayer = if (timedOut) emptyMap() else countMaterials(source)
                onPlayerThread(player) {
                    if (timedOut) {
                        // 源层没读完: 什么都还没扣、也没写, 直接收尾
                        endEdit(player)
                        player.sendLang("message-edit-failed", pluginId to "pluginId")
                        return@onPlayerThread
                    }
                    if (perLayer.isEmpty()) {
                        // 源层全是空气: 没有可复制的, 一个方块都不写, 选区也不动
                        endEdit(player)
                        player.sendLang("message-selection-expand-empty", pluginId to "pluginId")
                        return@onPlayerThread
                    }
                    // 每一层都照抄源层, 所以每种材料的用量 = 源层里该材料的个数 × 层数
                    val needed = perLayer.mapValues { it.value * layers }
                    if (!consumeAll(player, needed)) {
                        endEdit(player)
                        return@onPlayerThread
                    }
                    updateSelection(player, moved.first, moved.second)
                    prepareEdit(needed.values.sum(), player)
                    val actor = actorOf(player)
                    val targetChunks =
                        slabRegion(axis, sourceCoord + if (positive) 1 else -1, targetCoord, min, max).splitByChunk()
                    // 每个区块实际写下去多少 (按材料记), 收尾时当成本表用
                    val written = AtomicReferenceArray<HashMap<Material, Int>?>(targetChunks.size)
                    writeChunks(
                        player,
                        targetChunks,
                        "message-selection-expand-susses",
                        charged = needed,
                        costs = { targetChunks.indices.map { written.get(it) ?: emptyMap() } }
                    ) { index, slice, _ ->
                        val counts = written.get(index)
                            ?: HashMap<Material, Int>().also { written.set(index, it) }
                        WorldEditTool.writeBlocks(
                            world,
                            slice.region,
                            actor,
                            stateAt = { x, y, z -> source[plane.indexOf(x, y, z)]?.takeUnless { it.blockType.isAir() } },
                            onWritten = { state -> counts.add(state.blockType) }
                        )
                    }
                }
            }
        )
    }

    /**
     * 收缩: 把被砍掉的那几层全部清成空气
     *
     * 只跑一轮: 空气不计材料, 没有"先扣费再写"的需求, 所以快照 (撤销用) 与清空气在同一个
     * [runOnChunkThreads] 里完成。被清掉的非空气方块数从快照里数出来记进撤销记录 —— 只记账,
     * 不产生材料返还 (与 /pwe set air 一致)。一层空气都没清到时连撤销记录都不写。
     */
    private fun contractSelection(
        player: Player,
        moved: MovedFace,
        axis: Int,
        positive: Boolean,
        min: IntArray,
        max: IntArray
    ) {
        if (!beginEdit(player)) return
        updateSelection(player, moved.first, moved.second)
        val newCoord =
            if (positive) maxOf(moved.first[axis], moved.second[axis]) else minOf(moved.first[axis], moved.second[axis])
        // 被砍掉的层 = 老选区里、新选区之外的那几层
        val from = if (positive) newCoord + 1 else min[axis]
        val to = if (positive) max[axis] else newCoord - 1
        val regions = slabRegion(axis, from, to, min, max).splitByChunk()
        // 每个区块被清掉多少非空气方块 —— 同一区块的切片在同一线程上顺序执行, 普通 IntArray 就够
        val cleared = IntArray(regions.size)
        // 处理提示用上界 (这一层里所有方块数), 真正扣的是 0 (空气不计材料)
        prepareEdit(regions.sumOf { it.volume.toInt() }, player)
        val actor = actorOf(player)
        // AIR 一定存在 (哪个版本都有), WorldEdit 把这些常量标成可空只是因为老版本可能没有新方块
        val air = BlockTypes.AIR!!.defaultState
        writeChunks(
            player,
            regions,
            "message-selection-contract-susses",
            charged = emptyMap(),
            costs = { regions.indices.map { index -> if (cleared[index] == 0) emptyMap() else mapOf(Material.AIR to cleared[index]) } }
        ) { index, slice, states ->
            for (position in slice.offset until slice.offset + slice.size) {
                val state = states[position] ?: continue
                if (!state.blockType.isAir()) cleared[index]++
            }
            WorldEditTool.setBlocksIf(player.world, slice.region, air, actor) { _, _, _ -> true }
        }
    }

    /**
     * 跑一遍写方块管线: 每个区块先读整片快照 (写之前), 再把这一片交给 [writeSlice] 写; 全部跑完
     * 后回玩家线程收尾 ([finishEdit])。
     *
     * 快照整片读、整片还原是这套撤销机制的前提 (见 [UndoManager]): 没被改到的方块还原回去也
     * 无副作用, 所以只改一部分方块的操作用不着更细的快照。
     *
     * @param charged 这次操作预扣的材料 (整笔), 收尾时按"预扣 - 实际写入"退费
     * @param costs 收尾时按区块给出"这一块实际写入了多少材料"
     * @param writeSlice 在区块线程上执行, 参数是该区块的快照数组与这一片
     */
    private fun writeChunks(
        player: Player,
        regions: List<CuboidRegion>,
        successKey: String,
        charged: Map<Material, Int>,
        costs: () -> List<Map<Material, Int>>,
        writeSlice: (index: Int, slice: RegionSlice, states: Array<BlockState?>) -> Unit
    ) {
        val snapshots = AtomicReferenceArray<Array<BlockState?>?>(regions.size)
        runOnChunkThreads(regions, player.world,
            work = { index, slice ->
                val chunk = regions[index]
                val states = snapshots.get(index)
                    ?: arrayOfNulls<BlockState>(chunk.volume.toInt()).also { snapshots.set(index, it) }
                WorldEditTool.readBlocksInto(player.world, slice.region, states, slice.offset)
                writeSlice(index, slice, states)
            },
            done = { _ ->
                onPlayerThread(player) {
                    finishEdit(player, regions, regions.indices.map { snapshots[it] }, charged, costs(), successKey)
                }
            })
    }

    /**
     * 逐种扣材料, 全有全无
     *
     * 先逐种 [MaterialPackManager.tryConsume]; 任何一种不够就把**已经扣掉的**原样加回去再整体
     * 放弃 —— 这样"要么全扣要么全不扣"不依赖"扣费期间别的入口不会同时扣"这种假设 (材质包界面
     * 并不受 [beginEdit] 约束, 玩家可以一边等扩展一边从包里取东西)。
     */
    private fun consumeAll(player: Player, charges: Map<Material, Int>): Boolean {
        val done = ArrayList<Pair<Material, Int>>()
        for ((material, amount) in charges) {
            if (!PlayerWorldEdit.packManager.tryConsume(player, material, amount)) {
                // 已经扣掉的退回去 (加回去不会失败), 保证一个都不少
                done.forEach { (charged, count) -> PlayerWorldEdit.packManager.addMaterial(player, charged, count) }
                player.sendLang("message-materialpack-not-enough",
                    pluginId to "pluginId",
                    material.displayName(player) to "material",
                    amount to "amount")
                return false
            }
            done += material to amount
        }
        return true
    }

    /** 把扩缩后的两个点写回选区 (两个 setter 各自会重画一次选区框) */
    private fun updateSelection(player: Player, first: IntArray, second: IntArray) {
        setPlayerPos1(player, locationOf(player, first))
        setPlayerPos2(player, locationOf(player, second))
    }

    /**
     * 建一个沿 [axis] 从 [from] 到 [to] 层、另外两轴取 [min]..[max] 的长方体
     *
     * 扩缩用到的"源层 / 新层 / 被砍掉的层"都是这种薄片, 所以抽出来共用 (from/to 会自己摆正)。
     */
    private fun slabRegion(axis: Int, from: Int, to: Int, min: IntArray, max: IntArray): CuboidRegion {
        val low = intArrayOf(min[0], min[1], min[2])
        val high = intArrayOf(max[0], max[1], max[2])
        low[axis] = minOf(from, to)
        high[axis] = maxOf(from, to)
        return CuboidRegion(BlockVector3.at(low[0], low[1], low[2]), BlockVector3.at(high[0], high[1], high[2]))
    }

    /** 数一层方块里每种材料各有多少个 (跳过空气); 纯内存, 不读世界 */
    private fun countMaterials(states: Array<BlockState?>): Map<Material, Int> {
        val counts = LinkedHashMap<Material, Int>()
        states.forEach { state ->
            if (state != null && !state.blockType.isAir()) counts.add(state.blockType)
        }
        return counts
    }

    /**
     * 把一个方块类型累加进"按 Material 记的数"里
     *
     * 认不出来的类型跳过 (正常不会发生: 方块类型都能对上 Material), 这类方块会被复制但不计费。
     * 计费与写入共用这一份口径, 所以两边不会对不上。
     */
    private fun MutableMap<Material, Int>.add(type: BlockType) {
        materialOf(type)?.let { merge(it, 1, Int::plus) }
    }

    /** WorldEdit 的方块类型 → Bukkit 的 Material (材质包按 Material 记账) */
    private fun materialOf(type: BlockType): Material? = BukkitAdapter.adapt(type)

    /**
     * 方块类型是不是空气
     *
     * WorldEdit 的 BlockType 没有 isAir(), 这里显式列出三种空气 —— 口径与 Bukkit 的
     * `Material.isAir` 一致 (洞穴空气 / 虚空空气也算空气), 免得把它们当方块复制、计费。
     */
    private fun BlockType.isAir(): Boolean =
        this == BlockTypes.AIR || this == BlockTypes.CAVE_AIR || this == BlockTypes.VOID_AIR

    /**
     * 与 [axis] 垂直的那一层平面的下标器
     *
     * 复制时"源层 → 新层"只沿 [axis] 平移, 平面内的两个坐标原样对应, 所以把位置压成一个连续
     * 下标 (u * sizeV + v) 就能用数组存源层、再按同样的下标写新层 —— 不用按坐标查哈希表。
     */
    private class PlaneIndex(axis: Int, private val min: IntArray, private val max: IntArray) {

        private val axisU: Int
        private val axisV: Int
        private val minU: Int
        private val minV: Int
        private val sizeV: Int

        init {
            val (u, v) = when (axis) {
                AXIS_X -> AXIS_Y to AXIS_Z
                AXIS_Y -> AXIS_X to AXIS_Z
                else -> AXIS_X to AXIS_Y
            }
            axisU = u
            axisV = v
            minU = min[u]
            minV = min[v]
            sizeV = max[v] - min[v] + 1
        }

        /** 平面里一共有多少格 (源层数组的长度) */
        val size: Int get() = (max[axisU] - minU + 1) * sizeV

        fun indexOf(x: Int, y: Int, z: Int): Int {
            val u = when (axisU) {
                AXIS_X -> x
                AXIS_Y -> y
                else -> z
            }
            val v = when (axisV) {
                AXIS_X -> x
                AXIS_Y -> y
                else -> z
            }
            return (u - minU) * sizeV + (v - minV)
        }
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
     * 把当前选区复制进玩家自己的剪贴板 (对齐 WE 的 `//copy`)
     *
     * 参考点 = **复制时玩家所在的那一格** (WE 的 clipboard origin 语义), 方块按相对原点的偏移存储。
     * 不写方块、不扣材料、不记撤销, 成功后保留选区。读世界照旧走 [runOnChunkThreads]: 大选区不能在
     * 玩家线程上读。
     */
    fun tryCopy(player: Player) {
        val region = validatedRegion(player, needBreak = false) ?: return
        if (!beginEdit(player)) return
        val world = player.world
        val origin = player.location
        val clipboard = ClipboardVolume.of(region, origin.blockX, origin.blockY, origin.blockZ)
        runOnChunkThreads(
            region.splitByChunk(),
            world,
            work = { _, slice ->
                val weWorld = BukkitAdapter.adapt(world)
                for (vector in slice.region) {
                    clipboard.setBlock(vector.x(), vector.y(), vector.z(), weWorld.getBlock(vector))
                }
            },
            done = { timedOut ->
                onPlayerThread(player) {
                    endEdit(player)
                    // 超时、或者某个区块没读完 (数组里留下 null) 都算失败: 半份剪贴板贴出去比不贴更糟
                    if (timedOut || !clipboard.isComplete()) {
                        player.sendLang("message-edit-failed", pluginId to "pluginId")
                        return@onPlayerThread
                    }
                    clipboards[player.uniqueId] = clipboard
                    player.sendLang("message-copy-susses", pluginId to "pluginId", clipboard.volume to "amount")
                }
            }
        )
    }

    /**
     * 绕 Y 轴旋转剪贴板 (对齐 WE 的 `//rotate <角度>`)
     *
     * 绕**剪贴板原点**转 (WE 的 `ClipboardHolder.transform` 保持 origin 不动), 所以 rotate 之后再
     * paste, 原点还是落到玩家脚下。纯内存操作: 在玩家线程上做完即可, 不碰世界、不扣料、不记撤销。
     */
    fun rotateClipboard(player: Player, degrees: Int) {
        if (degrees !in ROTATE_ANGLES) {
            player.sendLang(
                "message-rotate-invalid-angle",
                pluginId to "pluginId",
                ROTATE_ANGLES.joinToString("/") to "angles"
            )
            return
        }
        val clipboard = clipboards[player.uniqueId] ?: run {
            player.sendLang("message-clipboard-empty", pluginId to "pluginId")
            return
        }
        if (!beginEdit(player)) return
        // 换一份新对象进表, 不动已经发布的那一份: 并发读的操作要么看到旋转前、要么看到旋转后
        clipboards[player.uniqueId] = clipboard.rotated(degrees)
        endEdit(player)
        player.sendLang("message-rotate-susses", pluginId to "pluginId", degrees to "angle")
    }

    /**
     * 把剪贴板贴到玩家所在的位置 (对齐 WE 的 `//paste`)
     *
     * 锚点: WE 的 `getPlacementPosition` 对玩家就是"玩家脚下那一格", 剪贴板**原点**落到这里 ——
     * 所以"原地 copy → paste"就是原样贴回, 走 5 格再 paste 就是平移 5 格的副本。空气照写 (与 WE
     * 一致: 剪贴板里的空气会把目标位置的方块清掉), 计费只算非空气 (与 `/pwe set air` 一致)。
     *
     * 写方块走 [writeChunks] 这条既有链路 (区域线程 + 每 tick 预算 + 看门狗 + 快照撤销), 玩家线程上
     * 只做纯算术的计费和调度。
     */
    fun tryPaste(player: Player) {
        val clipboard = clipboards[player.uniqueId] ?: run {
            player.sendLang("message-clipboard-empty", pluginId to "pluginId")
            return
        }
        val world = player.world
        val weWorld = BukkitAdapter.adapt(world)
        val anchor = player.location
        val anchorX = anchor.blockX
        val anchorY = anchor.blockY
        val anchorZ = anchor.blockZ
        // 目标框 = 剪贴板的偏移框平移到锚点。Y 夹进世界高度: 被夹掉的格子不写、也不计费
        // (与形状生成同一个口径 —— 计费必须等于"真正会写的方块", 否则会多扣)
        val minX = anchorX + clipboard.minX
        val maxX = anchorX + clipboard.maxX
        val minZ = anchorZ + clipboard.minZ
        val maxZ = anchorZ + clipboard.maxZ
        val minY = maxOf(anchorY + clipboard.minY, weWorld.minY)
        val maxY = minOf(anchorY + clipboard.maxY, weWorld.maxY)
        if (minY > maxY) {
            // 整份剪贴板都落在世界高度之外
            player.sendLang("message-shape-none", pluginId to "pluginId")
            return
        }
        val box = CuboidRegion(BlockVector3.at(minX, minY, minZ), BlockVector3.at(maxX, maxY, maxZ))
        // 领地校验覆盖整份要写的范围 (含空气会清到的那些格子); 粘贴会覆盖已有建筑, 所以按替换操作
        // 要求破坏权限 (needBreak = true)
        val corner1 = locationOf(player, intArrayOf(minX, minY, minZ))
        val corner2 = locationOf(player, intArrayOf(maxX, maxY, maxZ))
        if (!checkDominion(player, corner1, corner2, needBreak = true)) return
        // 计费: 目标框里逐格查剪贴板, 非空气按材料累加 —— 纯算术、不读世界, 且与下面写的是同一份内容
        val charges = LinkedHashMap<Material, Int>()
        for (y in minY..maxY) {
            for (z in minZ..maxZ) {
                for (x in minX..maxX) {
                    val state = clipboard.stateAt(x - anchorX, y - anchorY, z - anchorZ) ?: continue
                    if (!state.blockType.isAir()) charges.add(state.blockType)
                }
            }
        }
        if (!beginEdit(player)) return
        // 全有全无: 任何一种材料不够就一个方块都不写 (consumeAll 会把已扣的原样加回去)
        if (!consumeAll(player, charges)) {
            endEdit(player)
            return
        }
        prepareEdit(box.volume.toInt(), player)
        val actor = actorOf(player)
        val regions = box.splitByChunk()
        // 每个区块实际写下去多少 (空气也记: 撤销要能还原被清掉的位置), 收尾时当成本表用
        val written = AtomicReferenceArray<HashMap<Material, Int>?>(regions.size)
        writeChunks(
            player,
            regions,
            "message-paste-susses",
            charged = charges,
            costs = { regions.indices.map { written.get(it) ?: emptyMap() } }
        ) { index, slice, _ ->
            val counts = written.get(index)
                ?: HashMap<Material, Int>().also { written.set(index, it) }
            WorldEditTool.writeBlocks(
                world,
                slice.region,
                actor,
                // 剪贴板就在内存里: 这里的查表是纯算术, 不读世界
                stateAt = { x, y, z -> clipboard.stateAt(x - anchorX, y - anchorY, z - anchorZ) },
                onWritten = { state -> counts.add(state.blockType) }
            )
        }
    }

    /**
     * 剪贴板支持的角度
     *
     * 命令补全与 GUI 的角度按钮都用它, 免得"能补全的角度"和"真能旋转的角度"各写一份对不上。
     */
    fun rotateAngles(): List<Int> = ROTATE_ANGLES

    /**
     * 一次 copy 的结果: 剪贴板原点 + 相对原点的偏移框 + 逐格方块状态
     *
     * 原点 = 复制时玩家所在的那一格 (WE 的 clipboard origin 语义): [rotated] 绕它转, 粘贴时把它
     * 落到玩家所在的那一格 —— 所以"原地 copy → paste"就是原样贴回。
     *
     * 线程安全: [states] 只在 copy 的区块线程阶段被填 (每个区块只碰自己那些下标), 填完才由
     * [clipboards] 的 put 发布 —— ConcurrentHashMap 的 put/get 是 volatile 读写, 读的一方拿到引用
     * 时能看到此前全部写入; 发布之后 [states] 一个格子都不再改, 旋转换的是整份新对象, 所以读的一方
     * 不会看到半份剪贴板。同一玩家身上的 copy/rotate/paste 还被 [beginEdit] 串行化。
     */
    private class ClipboardVolume(
        val originX: Int,
        val originY: Int,
        val originZ: Int,
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
        private val states: Array<BlockState?>
    ) {
        /** 剪贴板里的格子数 (提示玩家复制了多少格) */
        val volume = sizeOf(minX, maxX, minY, maxY, minZ, maxZ)

        private val sizeX = maxX - minX + 1
        private val sizeZ = maxZ - minZ + 1

        /** 相对偏移 → 一维下标 (y → z → x 展开, 与 WE 的区域遍历顺序一致) */
        private fun indexOf(dx: Int, dy: Int, dz: Int): Int =
            ((dy - minY) * sizeZ + (dz - minZ)) * sizeX + (dx - minX)

        /** 按**绝对**方块坐标填一格 (copy 阶段用: 区块线程只碰自己那些下标) */
        fun setBlock(x: Int, y: Int, z: Int, state: BlockState) {
            put(x - originX, y - originY, z - originZ, state)
        }

        /** 相对偏移处的方块状态; 偏移落在剪贴板框外返回 null (调用方按框内遍历, 正常取不到 null) */
        fun stateAt(dx: Int, dy: Int, dz: Int): BlockState? =
            if (dx in minX..maxX && dy in minY..maxY && dz in minZ..maxZ) states[indexOf(dx, dy, dz)] else null

        /** 每格都读到了 (区块跑失败会留下 null) */
        fun isComplete(): Boolean = states.none { it == null }

        /**
         * 绕 Y 轴旋转 [degrees] 度 (只允许 90 的整数倍), 返回一份**新**剪贴板 (原点不动)
         *
         * 方块状态用 WE 自己的 [BlockTransformExtent.transform]: 它会按属性逐个旋转 ——
         * facing 这类 [com.sk89q.worldedit.registry.state.DirectionalProperty] 走
         * `Direction.findClosest(transform.apply(direction.toVector()))`, axis / half / shape /
         * orientation / rotation 也各有处理, 所以楼梯、门、告示牌转完朝向跟着转。偏移用同一个
         * [AffineTransform.rotateY] 变换 —— 与 WE 的 `ClipboardHolder.transform` 是同一套做法。
         */
        fun rotated(degrees: Int): ClipboardVolume {
            // 90 的整数倍下 rotateY 的系数精确为 0/±1 (实测), 所以整数偏移转出来还是整数
            val transform = AffineTransform().rotateY(degrees.toDouble())
            // 先转 8 个角求出新的偏移框: 旋转后的框要用新下标, 得先知道边界再逐格搬
            var newMinX = Int.MAX_VALUE
            var newMaxX = Int.MIN_VALUE
            var newMinY = Int.MAX_VALUE
            var newMaxY = Int.MIN_VALUE
            var newMinZ = Int.MAX_VALUE
            var newMaxZ = Int.MIN_VALUE
            for (dy in intArrayOf(minY, maxY)) {
                for (dz in intArrayOf(minZ, maxZ)) {
                    for (dx in intArrayOf(minX, maxX)) {
                        val corner = transformedOffset(transform, dx, dy, dz)
                        newMinX = minOf(newMinX, corner[0])
                        newMaxX = maxOf(newMaxX, corner[0])
                        newMinY = minOf(newMinY, corner[1])
                        newMaxY = maxOf(newMaxY, corner[1])
                        newMinZ = minOf(newMinZ, corner[2])
                        newMaxZ = maxOf(newMaxZ, corner[2])
                    }
                }
            }
            val rotated = ClipboardVolume(
                originX, originY, originZ,
                newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ,
                arrayOfNulls(sizeOf(newMinX, newMaxX, newMinY, newMaxY, newMinZ, newMaxZ))
            )
            for (dy in minY..maxY) {
                for (dz in minZ..maxZ) {
                    for (dx in minX..maxX) {
                        val state = states[indexOf(dx, dy, dz)] ?: continue
                        val target = transformedOffset(transform, dx, dy, dz)
                        rotated.put(target[0], target[1], target[2], BlockTransformExtent.transform(state, transform))
                    }
                }
            }
            return rotated
        }

        /** 相对偏移写一格 (copy 与 rotate 共用; 下标一定在框内) */
        private fun put(dx: Int, dy: Int, dz: Int, state: BlockState) {
            states[indexOf(dx, dy, dz)] = state
        }

        /**
         * 把一个整数偏移交给 WE 的变换, 再取回整数偏移
         *
         * 取整用 floor: 与 WE 的 `BlockVector3.at(double)` 一致 (90 的整数倍下系数是精确的 0/±1,
         * 取整方式不影响结果, 这里只是跟 WE 保持同一口径)。返回 IntArray 是为了省掉每格的装箱。
         */
        private fun transformedOffset(transform: Transform, dx: Int, dy: Int, dz: Int): IntArray {
            val vector = transform.apply(Vector3.at(dx.toDouble(), dy.toDouble(), dz.toDouble()))
            return intArrayOf(floor(vector.x()).toInt(), floor(vector.y()).toInt(), floor(vector.z()).toInt())
        }

        companion object {

            /** 偏移框的格子数 —— copy 与 rotate 都要按框分配数组, 所以共用一个算式 */
            private fun sizeOf(minX: Int, maxX: Int, minY: Int, maxY: Int, minZ: Int, maxZ: Int): Int =
                (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)

            /** 按选区与剪贴板原点建一份空剪贴板 (方块状态由 copy 的区块线程逐个填) */
            fun of(region: CuboidRegion, originX: Int, originY: Int, originZ: Int): ClipboardVolume {
                val min = region.minimumPoint
                val max = region.maximumPoint
                val minX = min.x() - originX
                val minY = min.y() - originY
                val minZ = min.z() - originZ
                val maxX = max.x() - originX
                val maxY = max.y() - originY
                val maxZ = max.z() - originZ
                return ClipboardVolume(
                    originX, originY, originZ,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    arrayOfNulls(sizeOf(minX, maxX, minY, maxY, minZ, maxZ))
                )
            }
        }
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
                            // 返还先落库, 再取吓一条: 两条的返还都是同一份材料包的读改写, 不能并发。
                            // 一次操作可能扣了多种材料 (扩展), 所以按逐材料账逐项加回
                            record.refundableCharges().forEach { (material, count) ->
                                chargeMaterial(player, material, -count)
                            }
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
     * @param charged 这次操作**预扣**的材料 (整笔; 填充/替换/形状是整块预扣, 扩展是按源层算出的整笔)
     * @param costs 与 regions 一一对应, 表示该区块**实际写入**的材料数量
     */
    private fun finishEdit(
        player: Player,
        regions: List<CuboidRegion>,
        snapshots: List<Array<BlockState?>?>,
        charged: Map<Material, Int>,
        costs: List<Map<Material, Int>>,
        successKey: String
    ) {
        endEdit(player)
        val succeeded = regions.indices.filter { isComplete(snapshots[it]) }
        // 真正写进世界的账 = 跑成功那些区块的成本之和; 没跑完的区块按"没写成"处理
        val actual = LinkedHashMap<Material, Int>()
        succeeded.forEach { index ->
            costs[index].forEach { (material, count) -> actual.merge(material, count, Int::plus) }
        }
        // 退费 = 预扣 - 实际写入。只退预扣过的材料: 收缩清空气没有预扣 (charged 为空), 也就没有退费。
        charged.forEach { (material, count) ->
            val used = actual[material] ?: 0
            if (count > used) chargeMaterial(player, material, -(count - used))
        }
        if (succeeded.isEmpty()) {
            player.sendLang("message-edit-failed", pluginId to "pluginId")
            return
        }
        // 有实际改动才记撤销: 收缩的层全是空气时 actual 为空, 没有可撤的东西, 别留一条 0 材料的记录。
        // 只有一种材料就写成 material + amount (旧格式, 文件更小也更好读); 多种材料才写 charges
        // (扩展会一次扣多种)。
        if (actual.isNotEmpty()) {
            val single = actual.entries.singleOrNull()
            UndoManager.pushAsync(
                player,
                UndoRecord(
                    player.world.name,
                    succeeded.map { UndoBatch(regions[it], snapshots[it]!!.filterNotNull()) },
                    single?.key ?: Material.AIR,
                    single?.value ?: 0,
                    if (single == null) actual.map { it.key to it.value } else emptyList()
                ),
                undoLimit()
            )
        }
        // 只要有一个区块没跑完就算失败: 收缩这类不产生退费的操作, 只看退费会把它误报成成功。
        // {amount} 给扩展 / 收缩报"实际写入 / 清掉的方块数"; 其它操作的提示里没这个占位符, 忽略即可。
        if (succeeded.size < regions.size) {
            player.sendLang("message-edit-failed", pluginId to "pluginId")
        } else {
            player.sendLang(successKey, pluginId to "pluginId", actual.values.sum() to "amount")
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
        if (!checkDominion(player, pos1, pos2, needBreak)) return null
        return region
    }

    /**
     * 领地校验 (fail-closed), 通过返回 true
     *
     * 抽出来是因为有两个调用方: 选区操作拿 pos1/pos2 去查, 而圆柱**没有选区**, 只能拿它的
     * 外接长方体去查 —— 但"拒绝 / 无法确认"的处理必须一模一样, 不能各写一份。
     */
    private fun checkDominion(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): Boolean {
        return when (val check = DominionHook.checkAreaEdit(player, corner1, corner2, needBreak)) {
            is DominionCheck.Allowed -> true
            is DominionCheck.Denied -> {
                player.sendLang("message-dominion-denied", pluginId to "pluginId", check.dominion to "dominion")
                false
            }
            is DominionCheck.Failed -> {
                // 校验过程出错 = 无法确认有没有领地, 按 fail-closed 拒绝
                // (具体异常已经打进控制台, 这里只告诉玩家操作被拒)
                player.sendLang("message-dominion-check-failed", pluginId to "pluginId")
                false
            }
        }
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
         * 扩 / 缩现在会真的复制 / 清掉方块, 格数直接决定要改动的方块数; 设上限是为了防止手滑
         * (例如把 1000 敲成 10000) 一下子把选区撑到几个区块以外 —— 那样一次操作要跑很久。
         */
        const val SELECTION_EDIT_MAX_BLOCKS = 100

        /**
         * 圆柱半径 / 高度的上限
         *
         * 只是 sanity cap: 形状本来就被裁剪在选区内, 给得再大也只是"填满选区"; 卡个上限是为了
         * 挡住手滑敲出来的一长串数字。
         */
        const val SHAPE_MAX_SIZE = 256

        /**
         * 剪贴板允许的旋转角度
         *
         * 只收 90 的整数倍: 方块状态里的朝向属性只能落到四个正交方向上, WE 的 `//rotate` 给别的角度
         * 时也只能把朝向吸附到最接近的那个值, 与其做一件做不到位的事, 不如直接拒绝。
         */
        val ROTATE_ANGLES = listOf(90, 180, 270)

        /** 轴索引 (与 [snapAxis] 的 0=X, 1=Y, 2=Z 顺序一致) —— 6 个面里只有 Y 需要世界高度夹取 */
        const val AXIS_X = 0

        /** 见 [AXIS_X] */
        const val AXIS_Y = 1

        /** 见 [AXIS_X] */
        const val AXIS_Z = 2

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

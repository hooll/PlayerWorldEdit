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
     * 边框粒度随选区尺寸自适应 (最长边固定 100 个点), 而且**按区块合并成一次任务**:
     * 原来是每个粒子点提交一个区域任务, 一个百来格的选区每秒钟会提交两千多个任务,
     * 区域线程光排队就卡住了。
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
        val points = createCube(pos1.toProxyLocation(), pos2.toProxyLocation(), step).calculateLocations()
            .map { it.toBukkitLocation() }
        // 按区块分组, 每秒每个区块只提交一个任务
        val pointsByChunk = points.groupBy { (it.blockX shr 4) to (it.blockZ shr 4) }
        var tick = 0
        particleTasks[player] = submitAsync(period = 20) {
            if (tick >= 60*20 || !player.isOnline) {
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
            tick += 20
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
     * 清空点
     * @param player 玩家
     */
    fun clearPlayerPos(player: Player) {
        playerpos.remove(player)
        hideSelection(player)
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
            done = {
                player.runOnOwnThread {
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
            done = {
                player.runOnOwnThread {
                    val targets = regions.indices.filter { isComplete(snapshots[it]) }
                    // 每个区块实际会被替换的数量, 收费和失败退费都以它为准
                    val costs = targets.map { replaceTargets(snapshots[it]!!, fromType.defaultState) }
                    val amount = costs.sum()
                    if (amount <= 0) {
                        player.sendLang("message-replace-none", pluginId to "pluginId", from.displayName(player) to "material")
                        endEdit(player)
                        return@runOnOwnThread
                    }
                    // 同 trySetArea: 查与扣合成一次原子操作
                    if (!PlayerWorldEdit.packManager.tryConsume(player, to, amount)) {
                        player.sendLang("message-materialpack-not-enough",
                            pluginId to "pluginId",
                            to.displayName(player) to "material",
                            amount to "amount")
                        endEdit(player)
                        return@runOnOwnThread
                    }
                    prepareEdit(amount, player)
                    val actor = actorOf(player)
                    val batchRegions = targets.map { regions[it] }
                    val batchSnapshots = targets.map { snapshots[it] }
                    runOnChunkThreads(batchRegions, player.world,
                        work = { index, slice ->
                            WorldEditTool.replaceBlocks(player.world, slice.region, fromType.defaultState, toType.defaultState, actor)
                        },
                        done = {
                            player.runOnOwnThread {
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
     * @param player 玩家
     * @param times 次数
     * @param onDone 整轮结束时回调一次, 参数是实际撤销的条数
     */
    fun undo(player: Player, times: Int, onDone: (Int) -> Unit) {
        if (!beginEdit(player)) {
            // 和其它出口一样走 finishUndo, 保证回调线程一致 (endEdit 对没进过集合的玩家是空操作)
            finishUndo(player, 0, onDone)
            return
        }
        // 第一次取记录要读文件, 丢到异步线程; 之后每一步由链自己挑线程
        submitAsync { undoStep(player, times, 0, onDone) }
    }

    private fun undoStep(player: Player, times: Int, done: Int, onDone: (Int) -> Unit) {
        if (done >= times) {
            finishUndo(player, done, onDone)
            return
        }
        // poll 要读撤销记录文件, 这一步在异步线程上跑
        val record = UndoManager.poll(player) ?: return finishUndo(player, done, onDone)
        val bukkitWorld = Bukkit.getWorld(record.worldName)
        if (bukkitWorld == null) {
            // 世界未加载: 把记录放回去并**中止整轮** —— 跳过后面的记录只会让撤销顺序错乱
            UndoManager.push(player, record, undoLimit())
            return finishUndo(player, done, onDone)
        }
        EditBudget.configure(blocksPerTick())
        val actor = actorOf(player)
        val regions = record.batches.map { it.region }
        val started = runCatching {
            runOnChunkThreads(regions, bukkitWorld,
                work = { index, slice ->
                    WorldEditTool.restoreBlocks(bukkitWorld, slice, record.batches[index].states, actor)
                },
                done = {
                    player.runOnOwnThread {
                        // 返还先落库, 再取吓一条: 两条的返还都是同一份材料包的读改写, 不能并发
                        chargeMaterial(player, record.material, -record.amount)
                        // 回到异步线程取吓一条 (那里才能读文件)
                        submitAsync { undoStep(player, times, done + 1, onDone) }
                    }
                }
            )
        }.onFailure {
            // 排队阶段就炸了: 这条当作没撤成, 结束整轮
            warning("[PlayerWorldEdit] 撤销失败: $it")
        }.isSuccess
        if (!started) {
            finishUndo(player, done, onDone)
        }
    }

    /**
     * 撤销的收尾: 释放 editing 并回报次数 (所有退出路径都要走这里)
     *
     * 回调必须回到**玩家线程**: 两个调用点都在回调里给玩家发消息, 而 Folia 下从异步线程
     * 碰玩家会抛线程归属异常。玩家已经下线时没有玩家线程可回, 只能就地回调 —— 调用方那句
     * 消息发给离线玩家本来就是空操作。
     */
    private fun finishUndo(player: Player, done: Int, onDone: (Int) -> Unit) {
        endEdit(player)
        if (!player.isOnline) {
            onDone(done)
            return
        }
        // runOnOwnThread 自身失败 (实体已退役等) 时也不能让调用方永远等不到结果
        if (runCatching { player.runOnOwnThread { onDone(done) } }.isFailure) {
            onDone(done)
        }
    }

    /**
     * 编辑收尾 (必须在玩家所在线程执行): 记录撤销、退还失败部分的材料并提示
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
        clearPlayerPos(player)
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

        const val DISPLAY_DURATION_DEFAULT = 10

        /** 是否让外部 WE 记录插件 (CoreProtect 等) 按玩家身份记录本插件的编辑 */
        const val COREPROTECT_LOGGING_KEY = "coreprotect-logging"

        /** 选区粒子边框在最长边上画多少个点 (其余边按同样步长, 总点数因此有上限) */
        const val PARTICLE_POINTS_PER_EDGE = 100.0

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

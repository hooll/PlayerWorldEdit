package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.api.event.OperationType
import cn.stingcraft.plugin.api.event.WorldEditOperationCompleteEvent
import cn.stingcraft.plugin.api.event.WorldEditOperationEvent
import cn.stingcraft.plugin.hook.ProtectionCheck
import cn.stingcraft.plugin.hook.ProtectionHooks
import cn.stingcraft.plugin.util.EditBudget
import cn.stingcraft.plugin.util.RegionSlice
import cn.stingcraft.plugin.util.WorldEditTool
import cn.stingcraft.plugin.util.displayName
import cn.stingcraft.plugin.util.runOnChunkThreads
import cn.stingcraft.plugin.util.runOnOwnThread
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.block.BlockTypes
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.common.platform.function.pluginId
import taboolib.platform.util.sendLang
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray

/** WorldEdit 的方块类型 → Bukkit 的 Material (材质包按 Material 记账) */
internal fun materialOf(type: BlockType): Material? = BukkitAdapter.adapt(type)

/**
 * 把一个方块类型累加进"按 Material 记的数"里
 *
 * 认不出来的类型跳过 (正常不会发生: 方块类型都能对上 Material), 这类方块会被复制但不计费。
 * 计费与写入共用这一份口径, 所以两边不会对不上。
 */
internal fun MutableMap<Material, Int>.add(type: BlockType) {
    materialOf(type)?.let { merge(it, 1, Int::plus) }
}

/** 数一层方块里每种材料各有多少个 (跳过空气); 纯内存, 不读世界 */
internal fun countMaterials(states: Array<BlockState?>): Map<Material, Int> {
    val counts = LinkedHashMap<Material, Int>()
    states.forEach { state ->
        if (state != null && !state.blockType.isAir()) counts.add(state.blockType)
    }
    return counts
}

/**
 * 方块类型是不是空气
 *
 * WorldEdit 的 BlockType 没有 isAir(), 这里显式列出三种空气 —— 口径与 Bukkit 的
 * `Material.isAir` 一致 (洞穴空气 / 虚空空气也算空气), 免得把它们当方块复制、计费。
 */
internal fun BlockType.isAir(): Boolean =
    this == BlockTypes.AIR || this == BlockTypes.CAVE_AIR || this == BlockTypes.VOID_AIR

/**
 * 写操作的共享基础设施: 编辑锁、领地校验、材料扣退、区块写管线与收尾
 *
 * 从 WorldEditManager 拆出来的第二块 (第 9 轮重构)。set / replace / 形状 / 扩缩 / move / paste
 * 六条链路都从这里走, 所以它只依赖 [SelectionManager] (取选区、把方块坐标拼成 Location), 反过来
 * 不被它依赖 —— 依赖图中间层。
 *
 * 上面四个无状态工具 ([materialOf] / [add] / [countMaterials] / [isAir]) 放顶层同理: 计费与写入
 * 分处 ShapeManager / ClipboardManager / 门面, 一份定义放顶层谁都能用, 不用各自抄一遍。
 */
class EditPipeline(private val selection: SelectionManager) {

    // 正在执行编辑的玩家, 避免同一玩家并发跑两个选区操作
    private val editing = ConcurrentHashMap.newKeySet<UUID>()

    private val settingManager = PlayerWorldEdit.settingManager

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
    internal fun writeChunks(
        player: Player,
        regions: List<CuboidRegion>,
        successKey: String,
        charged: Map<Material, Int>,
        costs: () -> List<Map<Material, Int>>,
        operation: OperationType,
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
                    finishEdit(
                        player,
                        regions,
                        regions.indices.map { snapshots[it] },
                        charged,
                        costs(),
                        successKey,
                        operation
                    )
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
    internal fun consumeAll(player: Player, charges: Map<Material, Int>): Boolean {
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

    /**
     * 需要玩家数据的收尾都从这里跑: 玩家在线就切回他自己的线程 (要发消息、碰玩家对象),
     * 已经下线就地在当前线程跑完。
     *
     * 不能只依赖 [runOnOwnThread]: 玩家中途退出时那个任务不会再执行, 收尾就整个丢了 ——
     * 材料扣了不退、撤销记录也不落库 (2026-10-13 的任务 9D)。退费和落库只依赖 uuid 和
     * 世界数据, 本来就不需要玩家对象; 给一个已经离开的玩家发提示是空操作。
     * [runOnOwnThread] 自身失败 (实体已退役等) 时同样就地跑, 否则收尾一样会丢。
     */
    internal fun onPlayerThread(player: Player, block: () -> Unit) {
        if (!player.isOnline) {
            block()
            return
        }
        if (runCatching { player.runOnOwnThread(block) }.isFailure) {
            block()
        }
    }

    /**
     * 编辑收尾 (走 [onPlayerThread]): 记录撤销、退还失败部分的材料、发完成事件并提示
     *
     * @param snapshots 与 regions 一一对应, null 或含 null 表示该区块没跑完
     * @param charged 这次操作**预扣**的材料 (整笔; 填充/替换/形状是整块预扣, 扩展是按源层算出的整笔)
     * @param costs 与 regions 一一对应, 表示该区块**实际写入**的材料数量
     * @param operation 完成事件里报的操作类型 (与 [fireOperation] 用的是同一个值)
     */
    internal fun finishEdit(
        player: Player,
        regions: List<CuboidRegion>,
        snapshots: List<Array<BlockState?>?>,
        charged: Map<Material, Int>,
        costs: List<Map<Material, Int>>,
        successKey: String,
        operation: OperationType
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
        // 完成事件: 整个流程里只在这里发一次 (上面"没有区块成功"已经 return, 所以失败不发)。
        // 报的是**实际写入**的统计而不是预扣量 —— 部分区块跑失败时与退费口径一致。
        Bukkit.getPluginManager().callEvent(
            WorldEditOperationCompleteEvent(player, operation, actual.values.sum(), actual)
        )
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
    internal fun validatedRegion(player: Player, needBreak: Boolean): CuboidRegion? {
        val region = selection.selectionRegion(player) ?: return null
        val pos1 = selection.getPlayerPos1(player) ?: return null
        val pos2 = selection.getPlayerPos2(player) ?: return null
        if (!checkProtection(player, pos1, pos2, needBreak)) return null
        return region
    }

    /**
     * 领地保护校验 (fail-closed), 通过返回 true
     *
     * 这里是全部"写方块 / 读选区"操作的**唯一收口**: 装了哪几层保护 (Dominion / Residence /
     * WorldGuard) 交给 [ProtectionHooks] 逐个问, 任何一层拒绝、或任何一层无法确认都拒绝。
     *
     * 抽成一处的理由没变: 选区操作拿 pos1/pos2 去查, 而圆柱**没有选区**, 只能拿它的外接长方体
     * 去查 —— 但"拒绝 / 无法确认"的处理必须一模一样, 不能各写一份。
     */
    internal fun checkProtection(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): Boolean {
        return when (val check = ProtectionHooks.checkAreaEdit(player, corner1, corner2, needBreak)) {
            is ProtectionCheck.Allowed -> true
            is ProtectionCheck.Denied -> {
                // 提示里带上是谁拦的 (source) 与它的原因 (detail): 三个钩子共用这一条消息
                player.sendLang(
                    "message-protection-denied",
                    pluginId to "pluginId",
                    check.source to "source",
                    check.detail to "detail"
                )
                false
            }
            is ProtectionCheck.Failed -> {
                // 校验过程出错 = 无法确认有没有领地, 按 fail-closed 拒绝
                // (具体异常已经打进控制台, 这里只告诉玩家操作被拒)
                player.sendLang("message-protection-check-failed", pluginId to "pluginId")
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
    internal fun beginEdit(player: Player): Boolean {
        if (!editing.add(player.uniqueId)) {
            player.sendLang("message-edit-busy", pluginId to "pluginId")
            return false
        }
        return true
    }

    internal fun endEdit(player: Player) {
        editing.remove(player.uniqueId)
    }

    /**
     * 按当前配置准备这次编辑: 套用每 tick 预算, 大操作提前给玩家一个"在处理"的提示
     */
    internal fun prepareEdit(amount: Int, player: Player) {
        EditBudget.configure(blocksPerTick())
        if (amount > EditBudget.perTick) {
            player.sendLang("message-edit-processing", pluginId to "pluginId", amount to "amount")
        }
    }

    /**
     * 操作前的统一触发点: 返回 false = 被监听方取消, 调用方直接 return (零写入、零扣料、不记撤销)
     *
     * 事件对象只在这里构造一次, 各操作各调一行 —— 字段与取消语义只有一份实现, 不在每个方法里
     * 各写一遍 callEvent。调用点都在领地校验之后、[beginEdit] 之前, 所以被取消时玩家不会先看到
     * 一条"没有权限", 也不会占住编辑锁。
     */
    internal fun fireOperation(player: Player, operation: OperationType, box: CuboidRegion): Boolean {
        val min = box.minimumPoint
        val max = box.maximumPoint
        val corners = locationOf(player, intArrayOf(min.x(), min.y(), min.z())) to
            locationOf(player, intArrayOf(max.x(), max.y(), max.z()))
        val event = WorldEditOperationEvent(player, operation, corners)
        Bukkit.getPluginManager().callEvent(event)
        return !event.isCancelled
    }

    internal fun isComplete(states: Array<BlockState?>?): Boolean {
        return states != null && states.none { it == null }
    }

    /**
     * 给 EditSession 挂的玩家 actor, 关掉时返回 null (改回"不带身份"的编辑)
     *
     * CoreProtect 的 WorldEdit 记录只看 `EditSessionEvent.getActor() != null`, 不带 actor 的
     * session 它整条跳过, 于是插件改了几十万方块 CoreProtect 一条都没记 (2026-10-12 的事故)。
     * 代价是大范围操作会往 CoreProtect 写同样数量的记录, 觉得吵可以用这个开关关掉。
     */
    internal fun actorOf(player: Player): Actor? {
        if (!settingManager.getWorldEditSettings().getBoolean(COREPROTECT_LOGGING_KEY, true)) {
            return null
        }
        return runCatching { BukkitAdapter.adapt(player) }.getOrNull()
    }

    /**
     * 增减创世神背包里的材料 (空气不计数)
     * @param delta 正数为扣除, 负数为退还
     */
    internal fun chargeMaterial(player: Player, material: Material, delta: Int) {
        if (material.isAir || delta == 0) return
        if (delta > 0) {
            PlayerWorldEdit.packManager.removeMaterial(player, material, delta)
        } else {
            PlayerWorldEdit.packManager.addMaterial(player, material, -delta)
        }
    }

    /**
     * 解析方块类型
     *
     * WorldEdit 注册表的键带命名空间 (minecraft:stone), Bukkit 的 Material 名没有,
     * 直接传裸名会查不到 (注册表不做补全)
     */
    internal fun resolveBlockType(material: Material): BlockType? {
        val id = material.name.lowercase()
        return BlockTypes.get("minecraft:$id") ?: BlockTypes.get(id)
    }

    internal fun undoLimit(): Int {
        return settingManager.getWorldEditSettings().getInt("undo-limit").takeIf { it > 0 } ?: 10
    }

    internal fun blocksPerTick(): Int {
        return settingManager.getWorldEditSettings().getInt("blocks-per-tick").takeIf { it > 0 } ?: EditBudget.perTick
    }

    private companion object {
        /** 是否让外部 WE 记录插件 (CoreProtect 等) 按玩家身份记录本插件的编辑 */
        const val COREPROTECT_LOGGING_KEY = "coreprotect-logging"
    }
}

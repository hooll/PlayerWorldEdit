package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.api.event.OperationType
import cn.stingcraft.plugin.api.event.WorldEditOperationCompleteEvent
import cn.stingcraft.plugin.api.event.WorldEditOperationEvent
import cn.stingcraft.plugin.hook.AreaPermission
import cn.stingcraft.plugin.hook.ProtectionCheck
import cn.stingcraft.plugin.hook.ProtectionHooks
import cn.stingcraft.plugin.util.ChunkBatchResult
import cn.stingcraft.plugin.util.EditBudget
import cn.stingcraft.plugin.util.RegionSlice
import cn.stingcraft.plugin.util.WorldEditTool
import cn.stingcraft.plugin.util.displayName
import cn.stingcraft.plugin.util.runOnChunkThreads
import cn.stingcraft.plugin.util.runOnOwnThread
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.block.BlockTypes
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import taboolib.common.platform.function.pluginId
import taboolib.platform.util.sendLang
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
internal fun countMaterials(states: Array<BaseBlock?>): Map<Material, Int> {
    val counts = LinkedHashMap<Material, Int>()
    states.forEach { state ->
        if (state != null && !state.blockType.isAir()) counts.add(state.blockType)
    }
    return counts
}

/**
 * 单个区块的写入成本: **一个方块都没写成**就返回空表
 *
 * 空表与"写了 0 个"在结算里含义不同: [EditPipeline.finishEdit] 用"有没有成本"判断这个区块算不算
 * 写成了 —— 一个方块都没写进去的区块不该进撤销记录, 也不该出现在完成事件的统计里 (R2)。
 */
internal fun costOf(material: Material, count: Int): Map<Material, Int> =
    if (count <= 0) emptyMap() else mapOf(material to count)

/**
 * 把"跑到哪了"翻译成撤销批次: 每个区块按**切片**记账, 只收完整读到的那些切片
 *
 * 为什么按切片而不是整块: 快照是"读一片、写一片"填出来的, 一个区块写失败时它后面那些切片根本没读
 * (states 里是 null)。整块记的话这条记录就含 null, 而还原是**按下标顺序**写回的, 长度对不上就会
 * 错位。所以只收"读完整了"的切片; 失败区块里正在写的那一片也收进来 —— 它读完了, 可能已经写进去
 * 一半, 还原它正好把这半截撤掉 (R2 的"已写部分必须可撤销")。
 *
 * 每片的 [UndoBatch.region] 都是原来那个区块里的一片 (X/Z 范围不变), 所以仍然落在同一个区块里 ——
 * 撤销按区块排任务的前提不能破 (见 [runOnChunkThreads])。
 *
 * 放顶层是因为它不依赖任何实例状态 (只有 [ChunkBatchResult] 与快照数组), 这样可以脱离服务端直接测。
 */
internal fun undoBatchesFor(result: ChunkBatchResult, snapshots: List<Array<BaseBlock?>?>): List<UndoBatch> {
    val batches = ArrayList<UndoBatch>()
    for (index in snapshots.indices) {
        val states = snapshots[index] ?: continue
        val layout = result.slices.getOrNull(index) ?: continue
        for (sliceIndex in 0 until result.reachedSlices(index)) {
            val slice = layout[sliceIndex]
            val end = minOf(slice.offset + slice.size, states.size)
            val sliceStates = states.copyOfRange(slice.offset, end)
            // 这一片没读完 (读的时候抛了异常): 后面的更不可能读完, 直接收工
            if (sliceStates.any { it == null }) break
            batches += UndoBatch(slice.region, sliceStates.map { it!! })
        }
    }
    return batches
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
 * 一次写操作的锁凭证
 *
 * 只是"这一次 [EditLocks.acquire] 拿到的号": [EditLocks.release] 要比对凭证才释放, 所以两类错误
 * 都堵住了 —— 没拿到锁的调用方 (例如 undo 撞上正在跑的大编辑) 不会把**别人的**锁删掉; 旧操作迟到
 * 的收尾回调也不会把**后来者**的锁删掉 (2026-10-14 的 R1)。
 */
class EditToken internal constructor(internal val id: Long) {
    override fun toString(): String = "EditToken#$id"
}

/**
 * 每个玩家一把编辑锁 (凭证式释放)
 *
 * 抽成独立的小类而不是直接写在 [EditPipeline] 里, 有两个原因: 它只依赖 UUID, 不碰玩家对象 ——
 * 所以可以**脱离服务端直接测** (R1 的验收: 未取得锁不得释放锁、旧操作不能清新锁); 而且锁的所有权
 * 规则只有这一份实现, 不会散在十几个 begin/end 调用点里。
 *
 * 没有 `forget(uuid)`: 锁**只能由持有者释放**。玩家退出时不能顺手清 (那个大编辑还在跑, 清了就
 * 变成两个操作并发写), 由操作自己的收尾释放。
 */
class EditLocks {

    private val holders = ConcurrentHashMap<UUID, EditToken>()

    /** 凭证流水号 (只要单调递增就够, 不参与任何业务判断) */
    private val sequence = AtomicLong()

    /** 拿到锁返回凭证; 这个玩家已经有一把没释放的锁时返回 null (调用方负责提示) */
    fun acquire(uuid: UUID): EditToken? {
        val token = EditToken(sequence.incrementAndGet())
        return if (holders.putIfAbsent(uuid, token) == null) token else null
    }

    /**
     * 释放锁 —— **只有拿着同一个凭证的那次操作**能释放
     *
     * `remove(key, value)` 是"值相等才删"的原子操作: 凭证对不上就什么都不做 (R1)。
     */
    fun release(uuid: UUID, token: EditToken) {
        holders.remove(uuid, token)
    }

    /** 这个玩家现在拿着的凭证 (没有则 null); 只给诊断/测试用 */
    fun holder(uuid: UUID): EditToken? = holders[uuid]
}

/**
 * 一次写操作的上下文: 启动时固定下来的目标世界 + 这次操作的锁
 *
 * 世界必须在这里定死: 领地校验用的是**选点所在世界**, 而写入原来每次都读 `player.world` —— 玩家
 * 在大编辑中途传送到另一个世界, 校验、调度、写入、撤销就会落在不同的世界上 (2026-10-14 的 R4)。
 * 之后所有读世界、写方块、排任务、记撤销都用 [world], 玩家中途传送不影响已经在跑的操作。
 */
internal class EditOperation(
    val world: World,
    internal val token: EditToken
)

/**
 * 校验通过的选区: 区域 + 这次操作固定下来的世界
 *
 * 两者一起返回是刻意的: 选区几何是"世界无关"的 (CuboidRegion 只有坐标), 光有区域拿不到该往哪个
 * 世界写 (R4)。
 */
internal class ValidatedSelection(val region: CuboidRegion, val world: World)

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

    /**
     * 正在执行编辑的玩家 → 那次操作的锁凭证
     *
     * 锁与凭证的规则都在 [EditLocks] 里 (那份逻辑不碰玩家对象, 可以脱离服务端直接测)。
     */
    private val locks = EditLocks()

    private val settingManager = PlayerWorldEdit.settingManager

    /**
     * 跑一遍写方块管线: 每个区块先读整片快照 (写之前), 再把这一片交给 [writeSlice] 写; 全部跑完
     * 后回玩家线程收尾 ([finishEdit])。
     *
     * 快照整片读、整片还原是这套撤销机制的前提 (见 [UndoManager]): 没被改到的方块还原回去也
     * 无副作用, 所以只改一部分方块的操作用不着更细的快照。
     *
     * @param charged 这次操作预扣的材料 (整笔), 收尾时按"预扣 - 实际写入"退费
     * @param costs 收尾时按区块给出"这一块**实际**写入了多少材料" —— 必须来自写入的返回值 /
     *    `onWritten`, 不能用"预计要写多少"顶替 (部分切片失败时两者会分叉, 见 [finishEdit])
     * @param writeSlice 在区块线程上执行, 参数是该区块的快照数组与这一片
     */
    internal fun writeChunks(
        player: Player,
        operation: EditOperation,
        regions: List<CuboidRegion>,
        successKey: String,
        charged: Map<Material, Int>,
        costs: () -> List<Map<Material, Int>>,
        type: OperationType,
        writeSlice: (index: Int, slice: RegionSlice, states: Array<BaseBlock?>) -> Unit
    ) {
        val snapshots = AtomicReferenceArray<Array<BaseBlock?>?>(regions.size)
        runOnChunkThreads(regions, operation.world,
            work = { index, slice ->
                val chunk = regions[index]
                val states = snapshots.get(index)
                    ?: arrayOfNulls<BaseBlock>(chunk.volume.toInt()).also { snapshots.set(index, it) }
                WorldEditTool.readBlocksInto(operation.world, slice.region, states, slice.offset)
                writeSlice(index, slice, states)
            },
            done = { result ->
                onPlayerThread(player) {
                    finishEdit(
                        player,
                        operation,
                        result,
                        regions.indices.map { snapshots[it] },
                        charged,
                        costs(),
                        successKey,
                        type
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
     * 编辑收尾 (走 [onPlayerThread]): 记录撤销、退还没写成的材料、发完成事件并提示
     *
     * 结算口径只有一条: **实际写进世界的量** ([ChunkBatchResult] + [costs])。原来是"快照读完了
     * 就算写成功", 于是快照完整但写入抛异常时会全额扣费、还留一条其实没生效的撤销记录 (2026-10-14
     * 的 R2)。现在:
     * - 退费 = 预扣 - 实际写入 (部分区块只算它写成的那些);
     * - 撤销只记**真的动过**的区块, 而且按切片粒度记 —— 写了一半的区块也能还原它写完的那部分;
     * - 完成事件、成功提示与撤销记录里的费用表全部用同一个 `actual`, 不会一个说 N 一个说 M。
     *
     * @param result 这一批区块的读写结果 (哪些区块跑完了 / 哪些抛了异常 / 超时)
     * @param snapshots 与写入时的区块一一对应, null 或含 null 表示该区块/该切片没读完
     * @param charged 这次操作**预扣**的材料 (整笔)
     * @param costs 与写入时的区块一一对应, 表示该区块**实际**写入的材料数量
     * @param type 完成事件里报的操作类型 (与 [beginOperation] 用的是同一个值)
     */
    internal fun finishEdit(
        player: Player,
        operation: EditOperation,
        result: ChunkBatchResult,
        snapshots: List<Array<BaseBlock?>?>,
        charged: Map<Material, Int>,
        costs: List<Map<Material, Int>>,
        successKey: String,
        type: OperationType
    ) {
        // 只释放自己那把锁: 没拿到锁的调用方 / 旧操作的迟到回调都不许动别人的锁 (R1)
        endEdit(player, operation.token)
        // 真正写进世界的账 = 每个区块实际写入量之和 (部分跑完的区块只贡献它写成的那些)
        val actual = LinkedHashMap<Material, Int>()
        costs.forEach { cost -> cost.forEach { (material, count) -> actual.merge(material, count, Int::plus) } }
        // 退费 = 预扣 - 实际写入。只退预扣过的材料: 收缩清空气没有预扣 (charged 为空), 也就没有退费。
        charged.forEach { (material, count) ->
            val used = actual[material] ?: 0
            if (count > used) chargeMaterial(player, material, -(count - used))
        }
        // 可撤销的部分 = 至少跑完一片的区块, 按切片记账 (写了一半的区块还原它写完的那几片)
        val batches = undoBatches(result, snapshots)
        if (batches.isEmpty()) {
            // 一片都没写成 (区块加载失败 / 第一片就抛异常): 没有可撤的东西, 材料已按上面退清
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
                    operation.world.name,
                    batches,
                    single?.key ?: Material.AIR,
                    single?.value ?: 0,
                    if (single == null) actual.map { it.key to it.value } else emptyList()
                ),
                undoLimit()
            )
        }
        // 完成事件: 整个流程里只在这里发一次 (上面"一片都没写成"已经 return, 所以失败不发)。
        // 报的是**实际写入**的统计而不是预扣量 —— 与退费、撤销记录同一口径。
        Bukkit.getPluginManager().callEvent(
            WorldEditOperationCompleteEvent(player, type, actual.values.sum(), actual)
        )
        // 有区块没跑完就算失败: 收缩这类不产生退费的操作, 只看退费会把它误报成成功。
        // {amount} 给扩展 / 收缩报"实际写入 / 清掉的方块数"; 其它操作的提示里没这个占位符, 忽略即可。
        if (result.isAllComplete()) {
            player.sendLang(successKey, pluginId to "pluginId", actual.values.sum() to "amount")
        } else {
            player.sendLang("message-edit-failed", pluginId to "pluginId")
        }
    }

    /**
     * 把"跑到哪了"翻译成撤销批次 —— 实现见顶层函数 [undoBatchesFor] (不依赖任何实例状态, 便于直接测)
     */
    internal fun undoBatches(result: ChunkBatchResult, snapshots: List<Array<BaseBlock?>?>): List<UndoBatch> =
        undoBatchesFor(result, snapshots)

    /**
     * 选区校验: 区域必须完整、两点与玩家同世界, 且没有领地拦截
     *
     * 返回的 [ValidatedSelection.world] 就是这次操作固定下来的世界 (见 [EditOperation])。
     *
     * @param requirement 这次操作需要哪几项权限 (见 [AreaPermission]) —— 由调用方按"真正会做什么"
     *   声明, 收口只负责把它原样传给三层保护
     */
    internal fun validatedSelection(player: Player, requirement: AreaPermission): ValidatedSelection? {
        // 世界先校验: 两点跨世界、或者玩家已经不在选区那个世界, 都不能往下走 (R4)
        val world = selection.selectionWorld(player) ?: return null
        val region = selection.selectionRegion(player) ?: return null
        val pos1 = selection.getPlayerPos1(player) ?: return null
        val pos2 = selection.getPlayerPos2(player) ?: return null
        if (!checkProtection(player, pos1, pos2, requirement)) return null
        return ValidatedSelection(region, world)
    }

    /**
     * 领地保护校验 (fail-closed), 通过返回 true
     *
     * 这里是全部"写方块 / 读选区"操作的**唯一收口**: 装了哪几层保护 (Dominion / Residence /
     * WorldGuard) 交给 [ProtectionHooks] 逐个问, 任何一层拒绝、或任何一层无法确认都拒绝。
     *
     * 权限要求由调用方声明成 [AreaPermission] (放置 / 破坏 / 两项都要 / 只读), 不再用一个"要不要破坏"
     * 的布尔 —— 覆盖类操作必须**两项都过**, 只用布尔会让"只许放置"的玩家也能清空/覆盖 (R5)。
     *
     * 抽成一处的理由没变: 选区操作拿 pos1/pos2 去查, 而圆柱**没有选区**, 只能拿它的外接长方体
     * 去查 —— 但"拒绝 / 无法确认"的处理必须一模一样, 不能各写一份。
     */
    internal fun checkProtection(
        player: Player,
        corner1: Location,
        corner2: Location,
        requirement: AreaPermission
    ): Boolean {
        return when (val check = ProtectionHooks.checkAreaEdit(player, corner1, corner2, requirement)) {
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
     * 标记玩家开始编辑, 返回这次操作的锁凭证; 已经在跑别的选区操作就拒绝 (返回 null)
     *
     * 大选区现在会跨很多个 tick 才做完, 玩家容易以为没生效而重复点, 两个操作叠在一起
     * 会重复扣材料、撤销记录也会错位。
     */
    internal fun beginEdit(player: Player): EditToken? {
        val token = locks.acquire(player.uniqueId)
        if (token == null) {
            player.sendLang("message-edit-busy", pluginId to "pluginId")
        }
        return token
    }

    /**
     * 释放编辑锁 —— **只有拿着凭证的那次操作**能释放 (规则见 [EditLocks.release])
     */
    internal fun endEdit(player: Player, token: EditToken) {
        locks.release(player.uniqueId, token)
    }

    /**
     * 写操作的统一开头: 拿锁 → 触发操作前事件 (被取消就把锁还回去)
     *
     * 事件必须在**拿到锁之后**发: 监听器如果在前置事件里同步调同一个玩家的写 API, 原来会直接绕开
     * 编辑锁重入 (事件发生在 [beginEdit] 之前), 递归下去就是无限嵌套 (2026-10-14 的 R9)。现在重入
     * 会拿到"你正在编辑"而被拒; 取消时零写入零扣料零撤销, 并且把自己的锁释放掉, 不占着。
     */
    internal fun beginOperation(
        player: Player,
        type: OperationType,
        box: CuboidRegion,
        world: World
    ): EditOperation? {
        val token = beginEdit(player) ?: return null
        if (!fireOperation(player, type, box, world)) {
            endEdit(player, token)
            return null
        }
        return EditOperation(world, token)
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
     * 各写一遍 callEvent。事件里的两个角点用**这次操作固定下来的世界** ([world]), 不是玩家当前
     * 所在的世界: 否则监听器拿到的坐标和真正会改的世界对不上 (R4)。
     */
    internal fun fireOperation(player: Player, type: OperationType, box: CuboidRegion, world: World): Boolean {
        val min = box.minimumPoint
        val max = box.maximumPoint
        val corners = locationOf(world, intArrayOf(min.x(), min.y(), min.z())) to
            locationOf(world, intArrayOf(max.x(), max.y(), max.z()))
        val event = WorldEditOperationEvent(player, type, corners)
        Bukkit.getPluginManager().callEvent(event)
        return !event.isCancelled
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

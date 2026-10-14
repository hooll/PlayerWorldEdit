package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.api.event.OperationType
import cn.stingcraft.plugin.until.EditBudget
import cn.stingcraft.plugin.until.WorldEditTool
import cn.stingcraft.plugin.until.displayName
import cn.stingcraft.plugin.until.runOnChunkThreads
import cn.stingcraft.plugin.until.runOnOwnThread
import cn.stingcraft.plugin.until.splitByChunk
import com.sk89q.worldedit.world.block.BlockState
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning
import taboolib.platform.util.sendLang
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 门面: 编排 + undo 链 + 全部对外入口
 *
 * 第 9 轮重构把原来 2448 行的 God Class 按职责拆成四个子 manager (见各自的类注释), 这里只剩两件事:
 *
 * 1. **编排**: 选 / 改 / 撤销的完整流程横跨多个子 manager (set = 选区校验 + 领地校验 + 扣料 + 写 +
 *    收尾), 这一层把 [SelectionManager] / [EditPipeline] / [ClipboardManager] / [ShapeManager] 串起来;
 * 2. **兼容入口**: 命令层 / GUI / listener / API 全部按老名字调这里的方法, 所以每个既有公开方法都保留
 *    成一行转发 (行为零变化, 调用点一行不改)。
 *
 * 依赖方向: SelectionManager (无依赖) ← EditPipeline ← {ClipboardManager, ShapeManager}; 子 manager
 * 之间没有引用, 都由这里注入。
 */
class WorldEditManager {

    private val selection = SelectionManager()

    private val pipeline = EditPipeline(selection)

    private val clipboards = ClipboardManager(pipeline, selection)

    private val shapes = ShapeManager(pipeline, selection)

    /**
     * 清空点 (玩家退出 / 主动清除时用)
     *
     * 四张 per-UUID 状态表分散在四个 manager 里, 清理统一聚合在这里 —— 少清一处就是"按 UUID 只增
     * 不删"的表 (与拆分前逐条清理等价, 只是按 owner 归了组)。
     *
     * @param player 玩家
     */
    fun clearPlayerPos(player: Player) {
        selection.forget(player)
        clipboards.forget(player)
        shapes.forgetShapeSize(player)
        pipeline.endEdit(player)
    }

    /**
     * 收掉所有玩家的选区显示体 (关插件/重载时用, 免得留下孤儿实体)
     */
    fun clearDisplays() {
        selection.clearDisplays()
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
        val region = pipeline.validatedRegion(player, needBreak = false) ?: return
        val blockType = pipeline.resolveBlockType(material) ?: run {
            player.sendLang("message-invalid-material", pluginId to "pluginId", material.name to "material")
            return
        }
        val amount = region.volume.toInt()
        if (amount <= 0) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        if (!pipeline.fireOperation(player, OperationType.SET, region)) return
        if (!pipeline.beginEdit(player)) return
        // 检查与扣费必须在一次原子操作里完成: 原来是"查够不够"和"扣"两次调用,
        // 中间别的线程也能通过检查, 同一个玩家并发操作时会被各扣一次。
        // 先扣材料, 有区块没执行成功再按体积退还。
        if (!PlayerWorldEdit.packManager.tryConsume(player, material, amount)) {
            player.sendLang("message-materialpack-not-enough",
                pluginId to "pluginId",
                material.displayName(player) to "material",
                amount to "amount")
            pipeline.endEdit(player)
            return
        }
        pipeline.prepareEdit(amount, player)
        val actor = pipeline.actorOf(player)
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
                pipeline.onPlayerThread(player) {
                    pipeline.finishEdit(
                        player,
                        regions,
                        regions.indices.map { snapshots[it] },
                        mapOf(material to amount),
                        regions.map { mapOf(material to it.volume.toInt()) },
                        "message-set-susses",
                        OperationType.SET
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
        val region = pipeline.validatedRegion(player, needBreak = true) ?: return
        val fromType = pipeline.resolveBlockType(from)
        val toType = pipeline.resolveBlockType(to)
        if (fromType == null || toType == null) {
            player.sendLang("message-invalid-material", pluginId to "pluginId",
                (if (fromType == null) from else to).name to "material")
            return
        }
        // 事件报的包围盒是整个选区: replace 只会改选区里匹配的那些方块, 但能改的范围就是选区
        if (!pipeline.fireOperation(player, OperationType.REPLACE, region)) return
        if (!pipeline.beginEdit(player)) return
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
                pipeline.onPlayerThread(player) {
                    val targets = regions.indices.filter { pipeline.isComplete(snapshots[it]) }
                    // 每个区块实际会被替换的数量, 收费和失败退费都以它为准
                    val costs = targets.map { mapOf(to to replaceTargets(snapshots[it]!!, fromType.defaultState)) }
                    val amount = costs.sumOf { it.values.sum() }
                    if (amount <= 0) {
                        player.sendLang("message-replace-none", pluginId to "pluginId", from.displayName(player) to "material")
                        pipeline.endEdit(player)
                        return@onPlayerThread
                    }
                    // 同 trySetArea: 查与扣合成一次原子操作
                    if (!PlayerWorldEdit.packManager.tryConsume(player, to, amount)) {
                        player.sendLang("message-materialpack-not-enough",
                            pluginId to "pluginId",
                            to.displayName(player) to "material",
                            amount to "amount")
                        pipeline.endEdit(player)
                        return@onPlayerThread
                    }
                    pipeline.prepareEdit(amount, player)
                    val actor = pipeline.actorOf(player)
                    val batchRegions = targets.map { regions[it] }
                    val batchSnapshots = targets.map { snapshots[it] }
                    runOnChunkThreads(batchRegions, player.world,
                        work = { index, slice ->
                            WorldEditTool.replaceBlocks(player.world, slice.region, fromType.defaultState, toType.defaultState, actor)
                        },
                        done = { _ ->
                            pipeline.onPlayerThread(player) {
                                pipeline.finishEdit(
                                    player,
                                    batchRegions,
                                    batchSnapshots,
                                    mapOf(to to amount),
                                    costs,
                                    "message-replace-susses",
                                    OperationType.REPLACE
                                )
                            }
                        }
                    )
                }
            }
        )
    }

    /**
     * 统计快照里有多少方块会被替换
     */
    private fun replaceTargets(states: Array<BlockState?>, from: BlockState): Int {
        val fromBase = from.toBaseBlock()
        return states.count { it != null && it.toBaseBlock() == fromBase }
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
        if (!pipeline.beginEdit(player)) {
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
            UndoManager.push(player, record, pipeline.undoLimit())
            return finishUndo(player, done, timedOut = false, onDone)
        }
        EditBudget.configure(pipeline.blocksPerTick())
        val actor = pipeline.actorOf(player)
        val regions = record.batches.map { it.region }
        val started = runCatching {
            runOnChunkThreads(regions, bukkitWorld,
                work = { index, slice ->
                    WorldEditTool.restoreBlocks(bukkitWorld, slice, record.batches[index].states, actor)
                },
                done = { timedOut ->
                    // 同样不能只依赖 runOnOwnThread: 玩家中途退出时这里不执行的话,
                    // 这一条的返还就丢了, 而且整条撤销链会断在半路
                    pipeline.onPlayerThread(player) {
                        if (timedOut) {
                            // 超时: 这条记录只还原了一部分, 不能按"已撤销"退还材料 (那等于白送),
                            // 也不能就这么丢掉 (剩下的区块就再也撤不回去了)。还原到快照是幂等的,
                            // 所以把记录放回队列, 让玩家再 undo 一次 —— 已还原的区块重做一遍没有副作用。
                            // 写盘是同步 IO, 丢到异步线程做。
                            warning("[PlayerWorldEdit] 撤销超时 (${player.name}), 本条记录已放回队列, 可重试")
                            submitAsync {
                                UndoManager.push(player, record, pipeline.undoLimit())
                                // 告诉调用方这轮是被超时截断的: 记录已经放回去, 不是"没有可撤销的"
                                finishUndo(player, done, timedOut = true, onDone)
                            }
                        } else {
                            // 返还先落库, 再取吓一条: 两条的返还都是同一份材料包的读改写, 不能并发。
                            // 一次操作可能扣了多种材料 (扩展), 所以按逐材料账逐项加回
                            record.refundableCharges().forEach { (material, count) ->
                                pipeline.chargeMaterial(player, material, -count)
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
        pipeline.endEdit(player)
        if (!player.isOnline) {
            onDone(done, timedOut)
            return
        }
        // runOnOwnThread 自身失败 (实体已退役等) 时也不能让调用方永远等不到结果
        if (runCatching { player.runOnOwnThread { onDone(done, timedOut) } }.isFailure) {
            onDone(done, timedOut)
        }
    }

    // ---------------- 以下都是转发: 命令 / GUI / listener / API 按老名字调用 ----------------

    /**
     * 获取创世神镐 (返回副本, 避免玩家修改到插件缓存的实例)
     */
    fun getPosTool(): ItemStack = selection.getPosTool()

    /**
     * 配置重载后丢弃缓存的创世神镐, 下次取用时按新配置重建
     */
    fun reloadCache() = selection.reloadCache()

    /**
     * 设置点1
     * @param player 玩家
     * @param pos1 点1
     */
    fun setPlayerPos1(player: Player, pos1: Location) = selection.setPlayerPos1(player, pos1)

    /**
     * 设置点2
     * @param player 玩家
     * @param pos2 点2
     */
    fun setPlayerPos2(player: Player, pos2: Location) = selection.setPlayerPos2(player, pos2)

    /**
     * 刷新选区可视化
     *
     * 默认用半透明玻璃盒子 (见 [cn.stingcraft.plugin.until.AreaDisplay]), 关掉 `region-display` 或
     * 盒子建不出来时退回粒子边框。
     *
     * @param player 玩家
     */
    fun showSelection(player: Player) = selection.showSelection(player)

    /**
     * 收掉选区可视化 (盒子和粒子都收)
     */
    fun hideSelection(player: Player) = selection.hideSelection(player)

    /**
     * 获取点1
     * @param player 玩家
     * @return 点1 / null if not set
     */
    fun getPlayerPos1(player: Player): Location? = selection.getPlayerPos1(player)

    /**
     * 获取点2
     * @param player 玩家
     * @return 点2 / null if not set
     */
    fun getPlayerPos2(player: Player): Location? = selection.getPlayerPos2(player)

    /**
     * 判断是否有区域
     * @param player 玩家
     */
    fun hasRegion(player: Player): Boolean = selection.hasRegion(player)

    /**
     * 获取区域大小
     * @param player 玩家
     * @return 区域大小
     */
    fun getCountInRegion(player: Player): Int = selection.getCountInRegion(player)

    /**
     * 生成墙: 选区四周的四面竖墙 (等同 WorldEdit 的 //walls, 不含顶和底)
     *
     * @param player 玩家
     * @param material 方块 (空气 = 清空这些位置)
     */
    fun tryGenerateWall(player: Player, material: Material) = shapes.tryGenerateWall(player, material)

    /**
     * 生成实心圆柱 (轴竖直 Y)
     *
     * @param radius 半径, 1..[ShapeManager.SHAPE_MAX_SIZE]
     * @param height 高度, 1..[ShapeManager.SHAPE_MAX_SIZE]
     */
    fun tryGenerateCylinder(player: Player, material: Material, radius: Int, height: Int) =
        shapes.tryGenerateCylinder(player, material, radius, height)

    /**
     * 生成空心圆柱: 只写半径处那一圈壳 (径向厚 1), 内部不动
     */
    fun tryGenerateHollowCylinder(player: Player, material: Material, radius: Int, height: Int) =
        shapes.tryGenerateHollowCylinder(player, material, radius, height)

    /**
     * 读取玩家正在调整的形状尺寸 (半径 to 高度)
     */
    fun pendingShapeSize(player: Player): Pair<Int, Int> = shapes.pendingShapeSize(player)

    /**
     * 增减形状尺寸选择器里的数值, 结果 clamp 在 1..[ShapeManager.SHAPE_MAX_SIZE]
     */
    fun adjustShapeSize(player: Player, radiusDelta: Int, heightDelta: Int) =
        shapes.adjustShapeSize(player, radiusDelta, heightDelta)

    /**
     * 丢掉进行中的形状尺寸 (确认生成后即用即弃; 玩家退出时由 [clearPlayerPos] 一并清掉)
     */
    fun forgetShapeSize(player: Player) = shapes.forgetShapeSize(player)

    /**
     * 形状尺寸的上限 (选择器的提示文案要用)
     */
    fun shapeSizeMax(): Int = shapes.shapeSizeMax()

    /**
     * 朝玩家朝向的那一面扩展 / 收缩选区, 并且**改动方块**
     *
     * @param player 玩家
     * @param blocks 扩缩的格数, 内部 clamp 到 1..[ShapeManager.SELECTION_EDIT_MAX_BLOCKS]
     * @param expand true=扩展, false=收缩
     */
    fun resizeSelection(player: Player, blocks: Int, expand: Boolean) = shapes.resizeSelection(player, blocks, expand)

    /**
     * 把选区里的建筑沿视线方向平移 [blocks] 格 (对齐 WE 的 `//move`)
     *
     * @param blocks 平移格数, 内部 clamp 到 1..[ShapeManager.SELECTION_EDIT_MAX_BLOCKS]
     */
    fun moveSelection(player: Player, blocks: Int) = shapes.moveSelection(player, blocks)

    /**
     * 把当前选区复制进玩家自己的剪贴板 (对齐 WE 的 `//copy`)
     */
    fun tryCopy(player: Player) = clipboards.tryCopy(player)

    /**
     * 绕 Y 轴旋转剪贴板 (对齐 WE 的 `//rotate <角度>`)
     */
    fun rotateClipboard(player: Player, degrees: Int) = clipboards.rotateClipboard(player, degrees)

    /**
     * 沿某个方向镜像剪贴板 (对齐 WE 的 `//flip`)
     */
    fun flipClipboard(player: Player, direction: String) = clipboards.flipClipboard(player, direction)

    /**
     * 把剪贴板贴到玩家所在的位置 (对齐 WE 的 `//paste`)
     */
    fun tryPaste(player: Player) = clipboards.tryPaste(player)

    /**
     * 预览粘贴: 只画目标框 + 报预估, 不写方块、不扣料、不记撤销
     */
    fun previewPaste(player: Player) = clipboards.previewPaste(player)

    /**
     * GUI 粘贴按钮的两段式入口: 预览还有效就直接贴, 否则先预览
     *
     * @return true 表示这次点击真的贴了 (调用方可以把菜单关掉), false 表示只显示了预览
     */
    fun pasteOrPreview(player: Player): Boolean = clipboards.pasteOrPreview(player)

    /**
     * 剪贴板支持的角度 (命令补全与 GUI 的角度按钮都用它)
     */
    fun rotateAngles(): List<Int> = clipboards.rotateAngles()

    /**
     * `/pwe flip` 的合法方向 (命令补全与"方向非法"的提示共用)
     */
    fun flipDirections(): List<String> = clipboards.flipDirections()
}
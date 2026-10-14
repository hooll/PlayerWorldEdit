package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.api.event.OperationType
import cn.stingcraft.plugin.hook.AreaPermission
import cn.stingcraft.plugin.util.EditBudget
import cn.stingcraft.plugin.util.WorldEditTool
import cn.stingcraft.plugin.util.displayName
import cn.stingcraft.plugin.util.runOnChunkThreads
import cn.stingcraft.plugin.util.runOnOwnThread
import cn.stingcraft.plugin.util.splitByChunk
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
import java.util.concurrent.atomic.AtomicIntegerArray
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
     * **编辑锁不在这里释放**: 玩家退出 / 主动清选区时可能还有一个大编辑在跑, 那把锁是它的, 由它
     * 自己的收尾释放 ([EditPipeline.endEdit] 只认自己的凭证)。在这里硬删会让"退出重登后再开一个
     * 操作"与旧操作并发跑 —— 重复扣料、撤销记录交错 (2026-10-14 的 R1)。
     *
     * @param player 玩家
     */
    fun clearPlayerPos(player: Player) {
        selection.forget(player)
        clipboards.forget(player)
        shapes.forgetShapeSize(player)
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
     *
     * 计费以**实际被接受的写入数**为准 (不是选区体积): 预扣按体积算 (保证"全有全无"),
     * 收尾时把"预扣 - 实际写入"退回去 —— 被 Folia 拒掉、或某片写失败的格子不该算钱 (R2)。
     */
    fun trySetArea(player: Player, material: Material) {
        // 权限按"真正会做什么"声明: 填空气只破坏, 填方块既破坏原有方块又放上新的 (R5)
        val requirement = if (material.isAir) AreaPermission.BREAK else AreaPermission.PLACE_AND_BREAK
        val validated = pipeline.validatedSelection(player, requirement) ?: return
        val world = validated.world
        val region = validated.region
        val blockType = pipeline.resolveBlockType(material) ?: run {
            player.sendLang("message-invalid-material", pluginId to "pluginId", material.name to "material")
            return
        }
        val amount = region.volume.toInt()
        if (amount <= 0) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        val operation = pipeline.beginOperation(player, OperationType.SET, region, world) ?: return
        // 检查与扣费必须在一次原子操作里完成: 原来是"查够不够"和"扣"两次调用,
        // 中间别的线程也能通过检查, 同一个玩家并发操作时会被各扣一次。
        // 先扣材料, 有区块没执行成功再按实际写入量退还。
        if (!PlayerWorldEdit.packManager.tryConsume(player, material, amount)) {
            player.sendLang("message-materialpack-not-enough",
                pluginId to "pluginId",
                material.displayName(player) to "material",
                amount to "amount")
            pipeline.endEdit(player, operation.token)
            return
        }
        pipeline.prepareEdit(amount, player)
        val actor = pipeline.actorOf(player)
        val regions = region.splitByChunk()
        val snapshots = AtomicReferenceArray<Array<BlockState?>?>(regions.size)
        // 每个区块**实际**被接受的写入数 (setBlocks 的返回值), 不是体积: 计费/退费/撤销都以它为准
        val written = AtomicIntegerArray(regions.size)
        runOnChunkThreads(regions, world,
            work = { index, slice ->
                val chunk = regions[index]
                val states = snapshots.get(index)
                    ?: arrayOfNulls<BlockState>(chunk.volume.toInt()).also { snapshots.set(index, it) }
                WorldEditTool.readBlocksInto(world, slice.region, states, slice.offset)
                written.addAndGet(index, WorldEditTool.setBlocks(world, slice.region, blockType.defaultState, actor))
            },
            done = { result ->
                pipeline.onPlayerThread(player) {
                    pipeline.finishEdit(
                        player,
                        operation,
                        result,
                        regions.indices.map { snapshots[it] },
                        mapOf(material to amount),
                        regions.indices.map { count -> costOf(material, written.get(count)) },
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
     *
     * **第二轮的结果单独跟踪** (R2): 第一轮快照读完只说明"知道要改哪些", 不能证明第二轮写成功。
     * 收尾时按第二轮的**实际写入数**结账, 第二轮整批失败就全额退费、不留撤销记录。
     */
    fun tryReplaceArea(player: Player, from: Material, to: Material) {
        val validated = pipeline.validatedSelection(player, AreaPermission.PLACE_AND_BREAK) ?: return
        val world = validated.world
        val region = validated.region
        val fromType = pipeline.resolveBlockType(from)
        val toType = pipeline.resolveBlockType(to)
        if (fromType == null || toType == null) {
            player.sendLang("message-invalid-material", pluginId to "pluginId",
                (if (fromType == null) from else to).name to "material")
            return
        }
        // 事件报的包围盒是整个选区: replace 只会改选区里匹配的那些方块, 但能改的范围就是选区
        val operation = pipeline.beginOperation(player, OperationType.REPLACE, region, world) ?: return
        val regions = region.splitByChunk()
        val snapshots = AtomicReferenceArray<Array<BlockState?>?>(regions.size)
        // 第一轮: 只读 (读不全的区块不算进"要替换的区块")
        runOnChunkThreads(regions, world,
            work = { index, slice ->
                val chunk = regions[index]
                val states = snapshots.get(index)
                    ?: arrayOfNulls<BlockState>(chunk.volume.toInt()).also { snapshots.set(index, it) }
                WorldEditTool.readBlocksInto(world, slice.region, states, slice.offset)
            },
            done = { readResult ->
                pipeline.onPlayerThread(player) {
                    // 第一轮跑完的区块才作数: 抛异常/没跑完的区块快照里有 null, 拿它统计会错
                    val targets = regions.indices.filter { readResult.isComplete(it) }
                    // 每个区块实际会被替换的数量, 收费和失败退费都以它为准
                    val costs = targets.map { mapOf(to to replaceTargets(snapshots[it]!!, fromType.defaultState)) }
                    val amount = costs.sumOf { it.values.sum() }
                    if (amount <= 0) {
                        player.sendLang("message-replace-none", pluginId to "pluginId", from.displayName(player) to "material")
                        pipeline.endEdit(player, operation.token)
                        return@onPlayerThread
                    }
                    // 同 trySetArea: 查与扣合成一次原子操作
                    if (!PlayerWorldEdit.packManager.tryConsume(player, to, amount)) {
                        player.sendLang("message-materialpack-not-enough",
                            pluginId to "pluginId",
                            to.displayName(player) to "material",
                            amount to "amount")
                        pipeline.endEdit(player, operation.token)
                        return@onPlayerThread
                    }
                    pipeline.prepareEdit(amount, player)
                    val actor = pipeline.actorOf(player)
                    val batchRegions = targets.map { regions[it] }
                    val batchSnapshots = targets.map { snapshots[it] }
                    // 第二轮**实际**被接受的写入数 (replaceBlocks 的返回值): 与第一轮的预扣量分开记,
                    // 第二轮写失败/少写时按它退费 (R2)
                    val written = AtomicIntegerArray(batchRegions.size)
                    runOnChunkThreads(batchRegions, world,
                        work = { index, slice ->
                            written.addAndGet(
                                index,
                                WorldEditTool.replaceBlocks(
                                    world,
                                    slice.region,
                                    fromType.defaultState,
                                    toType.defaultState,
                                    actor
                                )
                            )
                        },
                        done = { writeResult ->
                            pipeline.onPlayerThread(player) {
                                pipeline.finishEdit(
                                    player,
                                    operation,
                                    writeResult,
                                    batchSnapshots,
                                    mapOf(to to amount),
                                    batchRegions.indices.map { count -> costOf(to, written.get(count)) },
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
     * 回到异步线程取下一条。
     *
     * **任何一步没成都不退料、不丢记录, 而是把记录放回队列并中止整轮** (超时、区块抛异常、
     * 区块加载失败、记录读不出来、排队就炸了 —— 2026-10-14 的 R3): 只还原了一部分还照常退料
     * 等于白送, 直接丢掉则剩下的区块再也撤不回去。还原到快照是幂等的, 所以放回去让玩家重试,
     * 已还原的部分重做一遍没有副作用; 也绝不越过这条记录去取更早的。
     *
     * @param player 玩家
     * @param times 次数
     * @param onDone 整轮结束时回调一次, 参数是实际撤销的条数和"这轮是不是被中断提前收的尾"
     *   (超时 / 失败 / 世界没加载都算中断: 记录已经放回队列, 可以重试)
     */
    fun undo(player: Player, times: Int, onDone: (done: Int, interrupted: Boolean) -> Unit) {
        val token = pipeline.beginEdit(player)
        if (token == null) {
            // **拿不到锁时绝不能走 endEdit**: 那把锁是正在跑的那个操作的, 删掉它等于让两个操作
            // 并发跑 (R1)。只回报结果, 让调用方照常收到消息。
            reportUndo(player, 0, interrupted = false, onDone)
            return
        }
        // 第一次取记录要读文件, 丢到异步线程; 之后每一步由链自己挑线程
        submitAsync { undoStep(player, token, times, 0, onDone) }
    }

    private fun undoStep(
        player: Player,
        token: EditToken,
        times: Int,
        done: Int,
        onDone: (done: Int, interrupted: Boolean) -> Unit
    ) {
        if (done >= times) {
            finishUndo(player, token, done, interrupted = false, onDone)
            return
        }
        // poll 要读撤销记录文件, 这一步在异步线程上跑
        val polled = UndoManager.poll(player)
        if (polled !is UndoPoll.Record) {
            if (polled is UndoPoll.Failed) {
                // 记录读不出来: 文件**没被删掉** (poll 只在成功读到后才删), 报"被中断"而不是
                // "没有可撤销的操作" —— 后者会让玩家以为记录丢了 (R3)
                warning("[PlayerWorldEdit] 撤销记录读取失败 (${player.name}): ${polled.cause}")
                finishUndo(player, token, done, interrupted = true, onDone)
            } else {
                finishUndo(player, token, done, interrupted = false, onDone)
            }
            return
        }
        val record = polled.record
        val bukkitWorld = Bukkit.getWorld(record.worldName)
        if (bukkitWorld == null) {
            // 世界未加载: 把记录放回去并**中止整轮** —— 跳过后面的记录只会让撤销顺序错乱
            UndoManager.push(player, record, pipeline.undoLimit())
            finishUndo(player, token, done, interrupted = true, onDone)
            return
        }
        EditBudget.configure(pipeline.blocksPerTick())
        val actor = pipeline.actorOf(player)
        val regions = record.batches.map { it.region }
        val started = runCatching {
            runOnChunkThreads(regions, bukkitWorld,
                work = { index, slice ->
                    WorldEditTool.restoreBlocks(bukkitWorld, slice, record.batches[index].states, actor)
                },
                done = { result ->
                    // 同样不能只依赖 runOnOwnThread: 玩家中途退出时这里不执行的话,
                    // 这一条的返还就丢了, 而且整条撤销链会断在半路
                    pipeline.onPlayerThread(player) {
                        if (result.timedOut || !result.isAllComplete()) {
                            // 没还原完 (超时 / 某批抛异常 / 区块加载失败): 不能按"已撤销"退料 (那等于
                            // 白送), 也不能丢掉记录 (剩下的区块就再也撤不回去了)。还原是幂等的, 所以
                            // 放回队列让玩家再 undo 一次。写盘是同步 IO, 丢到异步线程做。
                            warning(
                                "[PlayerWorldEdit] 撤销未完成 (${player.name}): 超时=${result.timedOut} " +
                                    "失败区块=${result.failed}, 本条记录已放回队列, 可重试"
                            )
                            submitAsync {
                                UndoManager.push(player, record, pipeline.undoLimit())
                                finishUndo(player, token, done, interrupted = true, onDone)
                            }
                        } else {
                            // 返还先落库, 再取下一条: 两条的返还都是同一份材料包的读改写, 不能并发。
                            // 一次操作可能扣了多种材料 (扩展), 所以按逐材料账逐项加回
                            record.refundableCharges().forEach { (material, count) ->
                                pipeline.chargeMaterial(player, material, -count)
                            }
                            // 回到异步线程取下一条 (那里才能读文件)
                            submitAsync { undoStep(player, token, times, done + 1, onDone) }
                        }
                    }
                }
            )
        }.onFailure {
            // 排队阶段就炸了: 记录已经取出来了, **必须放回去** —— 原来直接收尾会让这条记录凭空
            // 消失 (既没还原也没法再撤) (R3)
            warning("[PlayerWorldEdit] 撤销排队失败: $it")
            runCatching { UndoManager.push(player, record, pipeline.undoLimit()) }
                .onFailure { pushError -> warning("[PlayerWorldEdit] 撤销记录放回失败: $pushError") }
        }.isSuccess
        if (!started) {
            finishUndo(player, token, done, interrupted = true, onDone)
        }
    }

    /**
     * 撤销的收尾: 释放 editing 并回报次数 (所有退出路径都要走这里)
     *
     * 回调必须回到**玩家线程**: 两个调用点都在回调里给玩家发消息, 而 Folia 下从异步线程
     * 碰玩家会抛线程归属异常。玩家已经下线时没有玩家线程可回, 只能就地回调 —— 调用方那句
     * 消息发给离线玩家本来就是空操作。
     */
    private fun finishUndo(
        player: Player,
        token: EditToken,
        done: Int,
        interrupted: Boolean,
        onDone: (done: Int, interrupted: Boolean) -> Unit
    ) {
        pipeline.endEdit(player, token)
        reportUndo(player, done, interrupted, onDone)
    }

    /**
     * 只回报结果、**不碰锁** —— 没拿到锁的那条路径用它
     *
     * 拿到锁的路径一律走 [finishUndo] (释放 + 回报)。
     */
    private fun reportUndo(
        player: Player,
        done: Int,
        interrupted: Boolean,
        onDone: (done: Int, interrupted: Boolean) -> Unit
    ) {
        if (!player.isOnline) {
            onDone(done, interrupted)
            return
        }
        // runOnOwnThread 自身失败 (实体已退役等) 时也不能让调用方永远等不到结果
        if (runCatching { player.runOnOwnThread { onDone(done, interrupted) } }.isFailure) {
            onDone(done, interrupted)
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
     * 默认用半透明玻璃盒子 (见 [cn.stingcraft.plugin.util.AreaDisplay]), 关掉 `region-display` 或
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
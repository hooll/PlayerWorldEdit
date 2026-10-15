package cn.stingcraft.plugin.util

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.world.block.BaseBlock
import org.bukkit.World

/**
 * WorldEdit 操作封装。
 *
 * 所有方法都必须在目标区块所属的线程上调用 (见 [runOnChunkThreads]):
 * 在 Folia 核心上从别的线程写入会被 WorldEdit 静默丢弃。
 *
 * **每个写入方法都返回"这次真正被接受的写入数"**, 调用方用它记账 (计费 / 退费 / 撤销统计),
 * 而不是用"遍历了多少格"去估。抛错的那一格无法证明有没有提交, 成本回调保守计一格,
 * 不将可能已经写入的改动免费送出; 撤销覆盖该片并退还所记成本。证据:
 * `FoliaExtentListener$1.setBlock` 对 `!Bukkit.isOwnedByCurrentRegion(pos)` 直接 `return false`
 * (字节码 `ifeq -> iconst_0`), 而 `EditSession.setBlocks` 只统计 `setBlock` 返回 true 的格子
 * (`RegionVisitor.resume` 里 `if (function.apply(pos)) affected++`), 所以返回值天然排除了
 * "越界被静默丢弃"的那些方块。
 *
 * 口径说明 (必须与报告一致): 这里的"写入"= **WE 的 extent 链接受了这次 setBlock**, 不是"方块状态
 * 真的变了" —— 同值重写由底层 NMS 适配器决定返回值, 离线无法验证, 所以计费口径定为"WE 接受了的
 * 写入", 与填充"按选区方块数扣材料"的既有口径一致。
 */
object WorldEditTool {

    /**
     * 读出一片方块, 直接写进 [target] 的下标 [offset] 开始的位置
     *
     * 顺序就是 [Region] 的迭代顺序 (y -> z -> x), [restoreBlocks] 按同样顺序写回。
     * 大选区不用先建临时 List 再拷一遍, 少一次分配和复制。
     *
     * 读的是 `getFullBlock` (带方块实体数据), 不是 `getBlock` (只有方块状态): 撤销要把箱子里的物品
     * 一起还原回去 (R8)。读到保存不了的实体数据时**在写入这一片之前**抛异常 —— 这一片会被
     * [runOnChunkThreads] 标成失败, 调用方不退费也不把它算成写成功, 而不是先改了世界、事后才发现
     * 撤销记录里少了数据。
     */
    fun readBlocksInto(world: World, region: Region, target: Array<BaseBlock?>, offset: Int) {
        val weWorld = BukkitAdapter.adapt(world)
        var index = offset
        for (vector in region) {
            target[index++] = captureBlock(weWorld.getFullBlock(vector))
        }
    }

    /** 每格接受后立即发布进度, 不依赖整个循环或 close 正常返回。 */
    fun setBlocks(
        world: World, region: Region, blockState: BaseBlock, actor: Actor? = null,
        onWritten: (BaseBlock) -> Unit = {}
    ): Int = writeBlocks(world, region, actor, { _, _, _ -> blockState }, onWritten)

    fun replaceBlocks(
        world: World, region: Region, from: BaseBlock, to: BaseBlock, actor: Actor? = null,
        onWritten: (BaseBlock) -> Unit = {}
    ): Int {
        val session = newSession(world, actor)
        return writeSession(session, { session.close() }, region, { vector ->
            if (session.getFullBlock(vector) == from) to else null
        }, onWritten)
    }

    fun writeBlocks(
        world: World,
        region: Region,
        actor: Actor?,
        stateAt: (x: Int, y: Int, z: Int) -> BaseBlock?,
        onWritten: (BaseBlock) -> Unit = {}
    ): Int {
        val session = newSession(world, actor)
        return writeSession(session, { session.close() }, region,
            { vector -> stateAt(vector.x(), vector.y(), vector.z()) }, onWritten)
    }

    /**
     * 与生产路径共用的 session 执行循环。接受进度立即发布, close 抛错也不丢掉进度。
     * setBlock 抛错的尝试保守占用一格成本; 正常返回 false 不记账。
     * 接受表示 extent 接受请求, 不声称关闭失败时已缓冲的写入一定全部落到世界。
     */
    internal fun writeSession(
        extent: Extent,
        close: () -> Unit,
        region: Region,
        stateAt: (BlockVector3) -> BaseBlock?,
        onWritten: (BaseBlock) -> Unit
    ): Int = AutoCloseable { close() }.use {
        var written = 0
        for (vector in region) {
            val state = stateAt(vector) ?: continue
            val accepted = try {
                extent.setBlock(vector, state)
            } catch (error: Throwable) {
                // 第三方 extent 可能在写后抛错。该格是否提交无法确认, 保守记一格成本;
                // 快照仍覆盖整片, 重试撤销后可退还此笔, 不将可能已写的格子免费送出。
                onWritten(state)
                throw error
            }
            if (accepted) {
                written++
                onWritten(state)
            }
        }
        written
    }

    fun setBlocksIf(
        world: World,
        region: Region,
        blockState: BaseBlock,
        actor: Actor?,
        contains: (x: Int, y: Int, z: Int) -> Boolean,
        onWritten: (BaseBlock) -> Unit = {}
    ): Int = writeBlocks(world, region, actor,
        { x, y, z -> if (contains(x, y, z)) blockState else null }, onWritten)

    /** 只有完整方块(含 NBT)已经相同才能跳过。真正拒写必须使本次撤销失败。 */
    fun restoreBlocks(world: World, slice: RegionSlice, states: List<BaseBlock>, actor: Actor? = null): Int {
        val session = newSession(world, actor)
        return restoreSession(session, { session.close() }, slice, states)
    }

    internal fun restoreSession(
        extent: Extent, close: () -> Unit, slice: RegionSlice, states: List<BaseBlock>
    ): Int = AutoCloseable { close() }.use {
        require(slice.offset + slice.size <= states.size) { "Incomplete undo snapshot" }
        var restored = 0
        var index = slice.offset
        for (vector in slice.region) {
            val target = states[index++]
            if (sameFullBlock(extent.getFullBlock(vector), target)) continue
            if (extent.setBlock(vector, target)) {
                restored++
            } else if (!sameFullBlock(extent.getFullBlock(vector), target)) {
                throw IllegalStateException("Undo write rejected at $vector")
            }
        }
        restored
    }

    private fun sameFullBlock(left: BaseBlock, right: BaseBlock): Boolean =
        left.toImmutableState() == right.toImmutableState() && nbtOf(left) == nbtOf(right)

    /**
     * 建 EditSession。
     *
     * [actor] 不是可有可无的: CoreProtect 的 WorldEdit 记录只看
     * `EditSessionEvent.getActor() != null` 这一个条件, session 不带 actor 时它整条跳过 ——
     * 带上了才会把这些方块改动记到该玩家名下, 出事时还能用 /co rollback 兜底。
     */
    private fun newSession(world: World, actor: Actor?): EditSession {
        val builder = WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world))
        if (actor != null) {
            builder.actor(actor)
        }
        return builder.build()
    }
}

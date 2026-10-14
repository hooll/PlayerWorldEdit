package cn.stingcraft.plugin.until

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.world.block.BlockState
import org.bukkit.World

/**
 * WorldEdit 操作封装。
 *
 * 所有方法都必须在目标区块所属的线程上调用 (见 [runOnChunkThreads]):
 * 在 Folia 核心上从别的线程写入会被 WorldEdit 静默丢弃。
 */
object WorldEditTool {

    /**
     * 读出一片方块, 直接写进 [target] 的下标 [offset] 开始的位置
     *
     * 顺序就是 [Region] 的迭代顺序 (y -> z -> x), [restoreBlocks] 按同样顺序写回。
     * 大选区不用先建临时 List 再拷一遍, 少一次分配和复制。
     */
    fun readBlocksInto(world: World, region: Region, target: Array<BlockState?>, offset: Int) {
        val weWorld = BukkitAdapter.adapt(world)
        var index = offset
        for (vector in region) {
            target[index++] = weWorld.getBlock(vector)
        }
    }

    fun setBlocks(world: World, region: Region, blockState: BlockState, actor: Actor? = null) {
        newSession(world, actor).use { session ->
            session.setBlocks(region, blockState)
        }
    }

    fun replaceBlocks(world: World, region: Region, from: BlockState, to: BlockState, actor: Actor? = null) {
        newSession(world, actor).use { session ->
            session.replaceBlocks(region, setOf(from.toBaseBlock()), to)
        }
    }

    /**
     * 逐块写入: 对区域里每个方块调 [stateAt] 取"要写的状态", 返回 null 就跳过这一格;
     * 实际写下去的每个状态都会回调 [onWritten] (调用方用它记账)。
     *
     * 形状 / 复制这类"要写的方块不是整块长方体"的操作用不了 `setBlocks(Region)` 那条快路径,
     * 只能逐个 [EditSession.setBlock] —— 和 [restoreBlocks] 是同一条写入路径, 所以开销特征和
     * 撤销一致: 都在区块所属的区域线程上跑, 都受每 tick 预算限流。[stateAt] 必须是纯判定或
     * 纯查表, 不读世界。
     */
    fun writeBlocks(
        world: World,
        region: Region,
        actor: Actor?,
        stateAt: (x: Int, y: Int, z: Int) -> BlockState?,
        onWritten: (BlockState) -> Unit = {}
    ) {
        newSession(world, actor).use { session ->
            for (vector in region) {
                val state = stateAt(vector.x(), vector.y(), vector.z()) ?: continue
                session.setBlock(vector, state)
                onWritten(state)
            }
        }
    }

    /** [writeBlocks] 的常量形式: 只写判定为真的那些方块 (形状生成用, 空气照写 = 清空) */
    fun setBlocksIf(
        world: World,
        region: Region,
        blockState: BlockState,
        actor: Actor?,
        contains: (x: Int, y: Int, z: Int) -> Boolean
    ) = writeBlocks(world, region, actor, { x, y, z -> if (contains(x, y, z)) blockState else null })

    /**
     * 按 [RegionSlice] 把整块快照里对应的那一段写回这片区域
     *
     * 逐格先跟快照比一次, 相同就跳过。快照是按**整块**读的, 撤销的大部分格子本来就没被改过,
     * 把原值写回去只有一个坏处: CoreProtect 会给这些"其实没动"的方块也记一笔 (2026-10-13 的
     * 遗留项)。代价是每个方块多一次区域线程上的读 —— 读与写在同一次 [RegionSlice] 迭代里,
     * 每 tick 的预算仍按写入量算, 所以不会额外多占 tick (见报告)。
     */
    fun restoreBlocks(world: World, slice: RegionSlice, states: List<BlockState>, actor: Actor? = null) {
        val end = minOf(slice.offset + slice.size, states.size)
        newSession(world, actor).use { session ->
            var index = slice.offset
            for (vector in slice.region) {
                if (index >= end) break
                val state = states[index++]
                if (session.getBlock(vector) == state) continue
                session.setBlock(vector, state)
            }
        }
    }

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

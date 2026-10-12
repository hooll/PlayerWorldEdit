package cn.stingcraft.plugin.until

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
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

    fun setBlocks(world: World, region: Region, blockState: BlockState) {
        newSession(world).use { session ->
            session.setBlocks(region, blockState)
        }
    }

    fun replaceBlocks(world: World, region: Region, from: BlockState, to: BlockState) {
        newSession(world).use { session ->
            session.replaceBlocks(region, setOf(from.toBaseBlock()), to)
        }
    }

    /** 按 [RegionSlice] 把整块快照里对应的那一段写回这片区域 */
    fun restoreBlocks(world: World, slice: RegionSlice, states: List<BlockState>) {
        val end = minOf(slice.offset + slice.size, states.size)
        newSession(world).use { session ->
            var index = slice.offset
            for (vector in slice.region) {
                if (index >= end) break
                session.setBlock(vector, states[index++])
            }
        }
    }

    private fun newSession(world: World): EditSession {
        return WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))
    }
}

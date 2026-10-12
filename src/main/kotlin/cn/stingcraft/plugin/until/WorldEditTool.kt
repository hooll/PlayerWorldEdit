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

    /** 读出区域内的全部方块, 顺序就是 [Region] 的迭代顺序, [restoreBlocks] 按同样顺序写回 */
    fun readBlocks(world: World, region: Region): List<BlockState> {
        val weWorld = BukkitAdapter.adapt(world)
        val states = ArrayList<BlockState>(region.volume.toInt())
        for (vector in region) {
            states += weWorld.getBlock(vector)
        }
        return states
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

    fun restoreBlocks(world: World, region: Region, states: List<BlockState>) {
        newSession(world).use { session ->
            var index = 0
            for (vector in region) {
                if (index >= states.size) break
                session.setBlock(vector, states[index++])
            }
        }
    }

    private fun newSession(world: World): EditSession {
        return WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))
    }
}

package cn.stingcraft.plugin.until

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.world.block.BlockState
import org.bukkit.entity.Player

object WorldEditTool {

    /**
     * 设置方块
     * @param player 玩家
     * @param region 区域
     * @param blockState 方块
     */
    fun setBlocks(player: Player, region: Region, blockState: BlockState) {
        WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(player.world)).use { session ->
            session.setBlocks(region, blockState)
        }
    }

}

package cn.stingcraft.plugin.until;

import com.fastasyncworldedit.core.FaweAPI;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import org.bukkit.entity.Player;

public class WorldEditTool {

    /**
     * 设置方块
     * @param player 玩家
     * @param region 区域
     * @param blockState 方块
     */
    public static void setBlocks(Player player, Region region, BlockState blockState){
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(FaweAPI.getWorld(player.getWorld().getName()))){
            editSession.setBlocks(region, blockState);
        }
    }

}

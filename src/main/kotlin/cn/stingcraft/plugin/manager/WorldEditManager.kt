package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.until.WorldEditTool
import com.fastasyncworldedit.core.FaweAPI
import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockTypes
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.service.PlatformExecutor
import taboolib.module.effect.createCube
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.getItemTag
import taboolib.platform.util.buildItem
import taboolib.platform.util.toBukkitLocation
import taboolib.platform.util.toProxyLocation

class WorldEditManager {

    private val playerpos = mutableMapOf<Player, Pair<Location?, Location?>>()

    private val particleTasks = mutableMapOf<Player, PlatformExecutor.PlatformTask>()

    private val posTool = buildItem(Material.DIAMOND_PICKAXE){
        name = "&7[&f创世神镐&7]"
        lore += listOf(
            "&7| 可在生存模式中使用的创世神功能",
            " ",
            "&f| 左键设置第一个点",
            "&f| 右键设置第二个点",
            " ",
            "&4| 仅可在领域区服使用"
        )
        colored()
    }.apply {
        val itemTag = getItemTag()
        itemTag.putDeep("playerworldedit", ItemTagData("true"))
        itemTag.saveTo(this)
    }

    /**
     * 获取创世神镐
     */
    fun getPosTool(): org.bukkit.inventory.ItemStack {
        return posTool
    }

    /**
     * 设置点1
     * @param player 玩家
     * @param pos1 点1
     */
    fun setPlayerPos1(player: Player, pos1: Location) {
        if (playerpos[player] == null) {
            playerpos[player] = Pair(pos1,null)
            return
        }
        playerpos[player] = Pair(pos1,playerpos[player]!!.second)
        showParticle(player)
    }

    /**
     * 设置点2
     * @param player 玩家
     * @param pos2 点2
     */
    fun setPlayerPos2(player: Player, pos2: Location) {
        if (playerpos[player] == null) {
            playerpos[player] = Pair(null,pos2)
            return
        }
        playerpos[player] = Pair(playerpos[player]?.first,pos2)
        showParticle(player)
    }

    /**
     * 显示粒子
     * @param player 玩家
     */
    fun showParticle(player: Player) {
        if (particleTasks[player] != null) {
            particleTasks[player]?.cancel()
            particleTasks.remove(player)
        }
        var tick = 0
        val pos1 = getPlayerPos1(player)?: return
        val pos2 = getPlayerPos2(player) ?: return
        val cube = createCube(pos1.toProxyLocation(), pos2.toProxyLocation(),0.5){
            player.spawnParticle(Particle.VILLAGER_HAPPY,it.toBukkitLocation(),1)
        }
        particleTasks[player] = submitAsync(period = 20) {
            if (tick >= 60*20) {
                cancel()
                particleTasks.remove(player)
                return@submitAsync
            }
            cube.show()
            tick += 20
        }

    }


    /**
     * 获取点1
     * @param player 玩家
     * @return 点1
     * @return null if not set
     */
    fun getPlayerPos1(player: Player): Location? {
        return playerpos[player]?.first
    }

    /**
     * 获取点2
     * @param player 玩家
     * @return 点2
     * @return null if not set
     */
    fun getPlayerPos2(player: Player): Location? {
        return playerpos[player]?.second
    }

    /**
     * 清空点
     * @param player 玩家
     */
    fun clearPlayerPos(player: Player) {
        playerpos.remove(player)
        if (particleTasks[player] != null) {
            particleTasks[player]?.cancel()
            particleTasks.remove(player)
        }
    }

    /**
     * 判断是否有区域
     * @param player 玩家
     */
    fun hasRegion(player: Player):Boolean{
        if (playerpos[player] == null || playerpos[player]?.first == null || playerpos[player]?.second == null) return false
        return getWERegion(player) != null
    }

    /**
     * 获取区域大小
     * @param player 玩家
     * @return 区域大小
     */
    fun getCountInRegion(player: Player): Int {
        val region = getWERegion(player) ?: return 0
        return region.size
    }

    /**
     * 设置区域方块
     * @param player 玩家
     * @param block 方块
     */
    fun setAreaBlock(player: Player, block: Material){
        val region = getWERegion(player) ?: return
        val weBlockType = BlockTypes.get(block.name.lowercase()) ?: return
        WorldEditTool.setBlocks(player, region, weBlockType.defaultState)
        clearPlayerPos(player)
    }

    /**
     * 替换区域方块
     * @param player
     * @param from
     * @param to
     */
    fun replaceAreaBlock(player: Player, from: Material,to: Material){
        val region = getWERegion(player) ?: return
        val fromType = BlockTypes.get(from.name.lowercase()) ?: return
        val toType = BlockTypes.get(to.name.lowercase()) ?: return
        val filterSet = setOf(fromType.defaultState.toBaseBlock())
        val weWorld = FaweAPI.getWorld(player.world.name)
        WorldEdit.getInstance()
            .newEditSession(weWorld)
            .use { session: EditSession ->
                session.replaceBlocks(region,filterSet,toType)
            }
        clearPlayerPos(player)
    }

    /**
     * 获取区域
     * @param player 玩家
     * @return 区域
     */
    private fun getWERegion(player: Player): CuboidRegion? {
        val pos1 = getPlayerPos1(player) ?: return null
        val pos2 = getPlayerPos2(player) ?: return null
        val vec1 = BlockVector3.at(pos1.x, pos1.y, pos1.z)
        val vec2 = BlockVector3.at(pos2.x, pos2.y, pos2.z)
        return CuboidRegion(vec1, vec2)
    }

}
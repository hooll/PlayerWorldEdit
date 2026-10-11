package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.hook.DominionHook
import cn.stingcraft.plugin.until.WorldEditTool
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockTypes
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.service.PlatformExecutor
import taboolib.common5.util.parseUUID
import taboolib.library.xseries.XMaterial
import taboolib.library.xseries.parseToXMaterial
import taboolib.module.effect.createCube
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.getI18nName
import taboolib.module.nms.getItemTag
import taboolib.platform.util.buildItem
import taboolib.platform.util.sendLang
import taboolib.platform.util.submit
import taboolib.platform.util.toBukkitLocation
import taboolib.platform.util.toProxyLocation

class WorldEditManager {

    private val playerpos = mutableMapOf<Player, Pair<Location?, Location?>>()

    private val particleTasks = mutableMapOf<Player, PlatformExecutor.PlatformTask>()

    private val settingManager = PlayerWorldEdit.settingManager

    // 配置在 TabooLib 注入阶段才可用, 不能在建类时读取
    private val posToolItem by lazy {
        buildItem(settingManager.getWorldEditSettings().getString("tool.type")?.parseToXMaterial() ?:XMaterial.DIAMOND_PICKAXE){
            name = settingManager.getWorldEditSettings().getString("tool.name") ?: "&7[&f创世神镐&7]"
            lore += settingManager.getWorldEditSettings().getStringList("tool.lore")
            customModelData = settingManager.getWorldEditSettings().getInt("tool.custom-model-data")
            colored()
        }.apply {
            val itemTag = getItemTag()
            itemTag.putDeep("playerworldedit", ItemTagData("true"))
            itemTag.saveTo(this)
        }
    }

    /**
     * 获取创世神镐
     */
    fun getPosTool(): org.bukkit.inventory.ItemStack {
        return posToolItem
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
            val particle = runCatching {
                Particle.valueOf(settingManager.getWorldEditSettings().getString("region-particle")?.uppercase() ?: "VILLAGER_HAPPY")
            }.getOrDefault(Particle.VILLAGER_HAPPY)
            val bukkitLocation = it.toBukkitLocation()
            bukkitLocation.submit {
                player.spawnParticle(particle, bukkitLocation, 1)
            }
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
        return region.volume.toInt()
    }

    /**
     * 设置区域方块
     * @param player 玩家
     * @param block 方块
     */
    fun setAreaBlock(player: Player, block: Material){
        val region = getWERegion(player) ?: return
        val weBlockType = BlockTypes.get(block.name.lowercase()) ?: return
        val record = UndoManager.snapshot(player.world.name, region, block)
        WorldEditTool.setBlocks(player, region, weBlockType.defaultState)
        if (record != null) UndoManager.push(player, record, undoLimit())
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
        val record = UndoManager.snapshot(player.world.name, region, to)
        val filterSet = setOf(fromType.defaultState.toBaseBlock())
        val weWorld = BukkitAdapter.adapt(player.world)
        WorldEdit.getInstance()
            .newEditSession(weWorld)
            .use { session: EditSession ->
                session.replaceBlocks(region,filterSet,toType.defaultState)
            }
        if (record != null) UndoManager.push(player, record, undoLimit())
        clearPlayerPos(player)
    }

    /**
     * 尝试填充选区, 校验区域与材料并发送提示
     */
    fun trySetArea(player: Player, material: Material) {
        if (!hasRegion(player)) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        val pos1 = getPlayerPos1(player) ?: return
        val pos2 = getPlayerPos2(player) ?: return
        DominionHook.findDeniedDominion(player, pos1, pos2, needBreak = false)?.let { dominion ->
            player.sendLang("message-dominion-denied", pluginId to "pluginId", dominion to "dominion")
            return
        }
        val amount = getCountInRegion(player)
        if (!PlayerWorldEdit.packManager.hasEnoughMaterial(player, material, amount)) {
            player.sendLang("message-materialpack-not-enough",
                pluginId to "pluginId",
                material.getI18nName() to "material",
                amount to "amount")
            return
        }
        PlayerWorldEdit.packManager.removeMaterial(player, material, amount)
        setAreaBlock(player, material)
        player.sendLang("message-set-susses", pluginId to "pluginId")
    }

    /**
     * 尝试替换选区方块, 校验区域与材料并发送提示
     */
    fun tryReplaceArea(player: Player, from: Material, to: Material) {
        if (!hasRegion(player)) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        val pos1 = getPlayerPos1(player) ?: return
        val pos2 = getPlayerPos2(player) ?: return
        DominionHook.findDeniedDominion(player, pos1, pos2, needBreak = true)?.let { dominion ->
            player.sendLang("message-dominion-denied", pluginId to "pluginId", dominion to "dominion")
            return
        }
        val amount = getCountInRegion(player)
        if (!PlayerWorldEdit.packManager.hasEnoughMaterial(player, to, amount)) {
            player.sendLang("message-materialpack-not-enough",
                pluginId to "pluginId",
                to.getI18nName() to "material",
                amount to "amount")
            return
        }
        PlayerWorldEdit.packManager.removeMaterial(player, to, amount)
        replaceAreaBlock(player, from, to)
        player.sendLang("message-replace-susses", pluginId to "pluginId")
    }

    /**
     * 撤销最近的操作
     * @param player 玩家
     * @param times 次数
     * @return 实际撤销次数
     */
    fun undo(player: Player, times: Int): Int {
        var done = 0
        repeat(times) {
            val record = UndoManager.poll(player) ?: return@repeat
            val weWorld = Bukkit.getWorld(record.worldName)?.let { BukkitAdapter.adapt(it) } ?: return@repeat
            WorldEdit.getInstance()
                .newEditSession(weWorld)
                .use { session: EditSession ->
                    var index = 0
                    for (vector in record.region) {
                        if (index >= record.states.size) break
                        session.setBlock(vector, record.states[index++])
                    }
                }
            PlayerWorldEdit.packManager.addMaterial(player, record.material, record.amount)
            done++
        }
        return done
    }

    private fun undoLimit(): Int {
        return settingManager.getWorldEditSettings().getInt("undo-limit").takeIf { it > 0 } ?: 10
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
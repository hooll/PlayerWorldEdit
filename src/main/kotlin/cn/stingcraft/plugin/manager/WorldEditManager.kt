package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.hook.DominionHook
import cn.stingcraft.plugin.until.WorldEditTool
import cn.stingcraft.plugin.until.displayName
import cn.stingcraft.plugin.until.markAsTool
import cn.stingcraft.plugin.until.runOnChunkThreads
import cn.stingcraft.plugin.until.runOnOwnThread
import cn.stingcraft.plugin.until.splitByChunk
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.block.BlockTypes
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.service.PlatformExecutor
import taboolib.library.xseries.XMaterial
import taboolib.library.xseries.parseToXMaterial
import taboolib.module.effect.createCube
import taboolib.platform.util.buildItem
import taboolib.platform.util.sendLang
import taboolib.platform.util.submit
import taboolib.platform.util.toBukkitLocation
import taboolib.platform.util.toProxyLocation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray

class WorldEditManager {

    // 选区与粒子任务会被玩家所在区域线程和异步操作线程同时读写, 用并发容器
    private val playerpos = ConcurrentHashMap<Player, Pair<Location?, Location?>>()

    private val particleTasks = ConcurrentHashMap<Player, PlatformExecutor.PlatformTask>()

    private val settingManager = PlayerWorldEdit.settingManager

    // 配置在 TabooLib 注入阶段才可用, 不能在建类时读取
    private val posToolItem by lazy {
        buildItem(settingManager.getWorldEditSettings().getString("tool.type")?.parseToXMaterial() ?:XMaterial.DIAMOND_PICKAXE){
            name = settingManager.getWorldEditSettings().getString("tool.name") ?: "&7[&f创世神镐&7]"
            lore += settingManager.getWorldEditSettings().getStringList("tool.lore")
            customModelData = settingManager.getWorldEditSettings().getInt("tool.custom-model-data")
            colored()
        }.apply {
            markAsTool()
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
        val particle = resolveParticle(settingManager.getWorldEditSettings().getString("region-particle")) ?: return
        val cube = createCube(pos1.toProxyLocation(), pos2.toProxyLocation(),0.5){
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
     * 填充选区。
     *
     * Folia 下一个区域线程只能改自己拥有的区块, 越界修改会被 WorldEdit 静默丢弃 (不报错),
     * 所以选区按区块拆开, 每块在它自己的区域线程上"先读后写"。各区块互不重叠, 读到的
     * 一定还是填充前的方块, 可以直接当撤销快照。
     */
    fun trySetArea(player: Player, material: Material) {
        val region = validatedRegion(player, needBreak = false) ?: return
        val blockType = resolveBlockType(material) ?: run {
            player.sendLang("message-invalid-material", pluginId to "pluginId", material.name to "material")
            return
        }
        val amount = region.volume.toInt()
        if (amount <= 0) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        if (!PlayerWorldEdit.packManager.hasEnoughMaterial(player, material, amount)) {
            player.sendLang("message-materialpack-not-enough",
                pluginId to "pluginId",
                material.displayName() to "material",
                amount to "amount")
            return
        }
        // 先扣材料, 有区块没执行成功再按体积退还
        chargeMaterial(player, material, amount)
        val regions = region.splitByChunk()
        val snapshots = AtomicReferenceArray<List<BlockState>?>(regions.size)
        runOnChunkThreads(regions, player.world,
            work = { index ->
                snapshots[index] = WorldEditTool.readBlocks(player.world, regions[index])
                WorldEditTool.setBlocks(player.world, regions[index], blockType.defaultState)
            },
            done = {
                player.runOnOwnThread {
                    finishEdit(
                        player,
                        regions,
                        regions.indices.map { snapshots[it] },
                        regions.map { it.volume.toInt() },
                        material,
                        amount,
                        "message-set-susses"
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
        val region = validatedRegion(player, needBreak = true) ?: return
        val fromType = resolveBlockType(from)
        val toType = resolveBlockType(to)
        if (fromType == null || toType == null) {
            player.sendLang("message-invalid-material", pluginId to "pluginId",
                (if (fromType == null) from else to).name to "material")
            return
        }
        val regions = region.splitByChunk()
        val snapshots = AtomicReferenceArray<List<BlockState>?>(regions.size)
        runOnChunkThreads(regions, player.world,
            work = { index ->
                snapshots[index] = WorldEditTool.readBlocks(player.world, regions[index])
            },
            done = {
                player.runOnOwnThread {
                    val targets = regions.indices.filter { snapshots[it] != null }
                    // 每个区块实际会被替换的数量, 收费和失败退费都以它为准
                    val costs = targets.map { replaceTargets(snapshots[it]!!, fromType.defaultState) }
                    val amount = costs.sum()
                    if (amount <= 0) {
                        player.sendLang("message-replace-none", pluginId to "pluginId", from.displayName() to "material")
                        return@runOnOwnThread
                    }
                    if (!PlayerWorldEdit.packManager.hasEnoughMaterial(player, to, amount)) {
                        player.sendLang("message-materialpack-not-enough",
                            pluginId to "pluginId",
                            to.displayName() to "material",
                            amount to "amount")
                        return@runOnOwnThread
                    }
                    chargeMaterial(player, to, amount)
                    val batchRegions = targets.map { regions[it] }
                    runOnChunkThreads(batchRegions, player.world,
                        work = { index ->
                            WorldEditTool.replaceBlocks(player.world, batchRegions[index], fromType.defaultState, toType.defaultState)
                        },
                        done = {
                            player.runOnOwnThread {
                                finishEdit(
                                    player,
                                    batchRegions,
                                    targets.map { snapshots[it] },
                                    costs,
                                    to,
                                    amount,
                                    "message-replace-susses"
                                )
                            }
                        }
                    )
                }
            }
        )
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
            val bukkitWorld = Bukkit.getWorld(record.worldName)
            if (bukkitWorld == null) {
                // 世界未加载, 把记录放回去, 避免白丢一条
                UndoManager.push(player, record, undoLimit())
                return@repeat
            }
            done++
            val regions = record.batches.map { it.region }
            runOnChunkThreads(regions, bukkitWorld,
                work = { index ->
                    WorldEditTool.restoreBlocks(bukkitWorld, regions[index], record.batches[index].states)
                },
                done = {
                    player.runOnOwnThread {
                        chargeMaterial(player, record.material, -record.amount)
                    }
                }
            )
        }
        return done
    }

    /**
     * 编辑收尾 (必须在玩家所在线程执行): 记录撤销、退还失败部分的材料并提示
     *
     * @param snapshots 与 regions 一一对应, null 表示该区块执行失败
     * @param costs 与 regions 一一对应, 表示该区块已经扣掉的材料数量 (失败时按它退还)
     */
    private fun finishEdit(
        player: Player,
        regions: List<CuboidRegion>,
        snapshots: List<List<BlockState>?>,
        costs: List<Int>,
        material: Material,
        amount: Int,
        successKey: String
    ) {
        val succeeded = regions.indices.filter { snapshots[it] != null }
        val refund = regions.indices.filter { snapshots[it] == null }.sumOf { costs[it] }
        if (refund > 0) {
            chargeMaterial(player, material, -refund)
        }
        clearPlayerPos(player)
        if (succeeded.isEmpty()) {
            player.sendLang("message-edit-failed", pluginId to "pluginId")
            return
        }
        UndoManager.push(
            player,
            UndoRecord(
                player.world.name,
                succeeded.map { UndoBatch(regions[it], snapshots[it]!!) },
                material,
                amount - refund
            ),
            undoLimit()
        )
        if (refund > 0) {
            player.sendLang("message-edit-failed", pluginId to "pluginId")
        } else {
            player.sendLang(successKey, pluginId to "pluginId")
        }
    }

    /**
     * 选区校验: 区域必须完整, 且没有领地拦截
     */
    private fun validatedRegion(player: Player, needBreak: Boolean): CuboidRegion? {
        if (!hasRegion(player)) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return null
        }
        val region = getWERegion(player) ?: return null
        val pos1 = getPlayerPos1(player) ?: return null
        val pos2 = getPlayerPos2(player) ?: return null
        DominionHook.findDeniedDominion(player, pos1, pos2, needBreak = needBreak)?.let { dominion ->
            player.sendLang("message-dominion-denied", pluginId to "pluginId", dominion to "dominion")
            return null
        }
        return region
    }

    /**
     * 增减创世神背包里的材料 (空气不计数)
     * @param delta 正数为扣除, 负数为退还
     */
    private fun chargeMaterial(player: Player, material: Material, delta: Int) {
        if (material.isAir || delta == 0) return
        if (delta > 0) {
            PlayerWorldEdit.packManager.removeMaterial(player, material, delta)
        } else {
            PlayerWorldEdit.packManager.addMaterial(player, material, -delta)
        }
    }

    /**
     * 统计快照里有多少方块会被替换
     */
    private fun replaceTargets(states: List<BlockState>, from: BlockState): Int {
        val fromBase = from.toBaseBlock()
        return states.count { it.toBaseBlock() == fromBase }
    }

    /**
     * 解析方块类型
     *
     * WorldEdit 注册表的键带命名空间 (minecraft:stone), Bukkit 的 Material 名没有,
     * 直接传裸名会查不到 (注册表不做补全)
     */
    private fun resolveBlockType(material: Material): BlockType? {
        val id = material.name.lowercase()
        return BlockTypes.get("minecraft:$id") ?: BlockTypes.get(id)
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

    /**
     * 粒子名跨版本解析
     *
     * 1.20.5 起粒子枚举改名 (VILLAGER_HAPPY -> HAPPY_VILLAGER 等), 所以按名字查:
     * 配置里写新名或旧名都能用, 都解析不到时退回 CRIT (各版本都有)
     */
    private fun resolveParticle(rawName: String?): Particle? {
        val name = rawName?.trim()?.uppercase().orEmpty().ifEmpty { "VILLAGER_HAPPY" }
        for (candidate in listOfNotNull(name, PARTICLE_ALIASES[name])) {
            runCatching { Particle.valueOf(candidate) }.getOrNull()?.let { return it }
        }
        return runCatching { Particle.valueOf("CRIT") }.getOrNull()
    }

    private companion object {

        /** 旧名 <-> 新名 (1.20.5 起的粒子枚举改名), 双向可查 */
        val PARTICLE_ALIASES: Map<String, String> = buildMap {
            listOf(
                "VILLAGER_HAPPY" to "HAPPY_VILLAGER",
                "VILLAGER_ANGRY" to "ANGRY_VILLAGER",
                "EXPLOSION_NORMAL" to "POOF",
                "EXPLOSION_LARGE" to "EXPLOSION",
                "EXPLOSION_HUGE" to "EXPLOSION_EMITTER",
                "FIREWORKS_SPARK" to "FIREWORK",
                "SMOKE_NORMAL" to "SMOKE",
                "SMOKE_LARGE" to "LARGE_SMOKE",
                "WATER_BUBBLE" to "BUBBLE",
                "WATER_SPLASH" to "SPLASH",
                "WATER_WAKE" to "FISHING",
                "SPELL_INSTANT" to "INSTANT_EFFECT",
                "SPELL_MOB" to "ENTITY_EFFECT",
                "SPELL_MOB_AMBIENT" to "AMBIENT_ENTITY_EFFECT",
                "SPELL_WITCH" to "WITCH",
                "ENCHANTMENT_TABLE" to "ENCHANT",
                "CRIT_MAGIC" to "ENCHANTED_HIT",
                "REDSTONE" to "DUST",
                "DRIP_WATER" to "DRIPPING_WATER",
                "DRIP_LAVA" to "DRIPPING_LAVA",
                "SNOWBALL" to "ITEM_SNOWBALL",
                "SLIME" to "ITEM_SLIME",
                "TOTEM" to "TOTEM_OF_UNDYING",
            ).forEach { (oldName, newName) ->
                put(oldName, newName)
                put(newName, oldName)
            }
        }
    }

}

package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.until.AreaDisplay
import cn.stingcraft.plugin.until.markAsTool
import cn.stingcraft.plugin.until.parseBlockOrNull
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.service.PlatformExecutor
import taboolib.library.xseries.XMaterial
import taboolib.library.xseries.parseToXMaterial
import taboolib.module.effect.createCube
import taboolib.platform.util.buildItem
import taboolib.platform.util.runTask
import taboolib.platform.util.sendLang
import taboolib.platform.util.toBukkitLocation
import taboolib.platform.util.toProxyLocation
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** 轴索引 (与 [snapAxis] 的 0=X, 1=Y, 2=Z 顺序一致) —— 6 个面里只有 Y 需要世界高度夹取 */
internal const val AXIS_X = 0

/** 见 [AXIS_X] */
internal const val AXIS_Y = 1

/** 见 [AXIS_X] */
internal const val AXIS_Z = 2

/**
 * 世界边界 (方块坐标闭区间, 依次是 minX / maxX / minZ / maxZ), 读不到就返回 null
 *
 * 编译期用的精简 API 里 `org.bukkit.WorldBorder` 只有 `getCenter()/getSize()/isInside()`,
 * **没有** Paper 的 `getMinX()/getMaxX()/getMinZ()/getMaxZ()` (javap 实测, 见报告), WE 的
 * `BukkitWorld` 也没有等价入口 —— 所以按 Bukkit 自己的算式从中心与边长推:
 * min = center - size / 2、max = center + size / 2, 再取"方块中心落在边界内"的方块范围
 * (方块 x 的中心是 x + 0.5, 所以 min 上取整、max 下取整后再各让半格)。
 */
internal fun worldBorderBounds(world: World): IntArray? {
    val border = runCatching { world.worldBorder }.getOrNull() ?: return null
    val half = border.size / 2.0
    // 边界还没初始化 (size <= 0) 时不把它当边界用, 免得把所有操作都夹没了
    if (half <= 0.0) return null
    val center = border.center
    return intArrayOf(
        ceil(center.x - half - 0.5).toInt(),
        floor(center.x + half - 0.5).toInt(),
        ceil(center.z - half - 0.5).toInt(),
        floor(center.z + half - 0.5).toInt()
    )
}

/**
 * 把某一轴的坐标夹进世界边界 (Y = 世界高度, X/Z = 世界边界); 读不到边界就原样返回
 *
 * expand 推选区那一面时用它 —— 夹取后照旧应用 (与 move 的"整体拒绝"正好相反)。
 */
internal fun clampAxis(world: World, axis: Int, value: Int): Int {
    if (axis == AXIS_Y) {
        val weWorld = BukkitAdapter.adapt(world)
        return value.coerceIn(weWorld.minY, weWorld.maxY)
    }
    val border = worldBorderBounds(world) ?: return value
    return when (axis) {
        AXIS_X -> value.coerceIn(border[0], border[1])
        else -> value.coerceIn(border[2], border[3])
    }
}

/**
 * 把长方体在 X/Z 上夹进世界边界, 返回夹取后还剩没剩东西
 *
 * 只改传进来的两个数组, 由调用方决定"夹取后照常执行"还是"整体拒绝"; 边界读不到时不动并返回
 * true (退回改动前的行为)。expand / 圆柱外接框 / paste 目标框都用它 —— 夹掉的部分不写也不
 * 计费 (9C 红线: 计费 = 真正会写的方块)。
 */
internal fun clampXZToBorder(world: World, min: IntArray, max: IntArray): Boolean {
    val border = worldBorderBounds(world) ?: return true
    min[AXIS_X] = maxOf(min[AXIS_X], border[0])
    max[AXIS_X] = minOf(max[AXIS_X], border[1])
    min[AXIS_Z] = maxOf(min[AXIS_Z], border[2])
    max[AXIS_Z] = minOf(max[AXIS_Z], border[3])
    return min[AXIS_X] <= max[AXIS_X] && min[AXIS_Z] <= max[AXIS_Z]
}

internal fun axisName(axis: Int): String = when (axis) {
    AXIS_X -> "X"
    AXIS_Y -> "Y"
    else -> "Z"
}

/**
 * 把视线方向吸附到最接近的 6 个轴向之一
 *
 * 取视线向量里**绝对值最大**的那个分量决定轴; 平局时按 X > Y > Z 的固定顺序取先遇到的那个,
 * 保证同一个朝向每次都算出同一面 (玩家斜着看时不会一会儿扩 X 一会儿扩 Y)。
 *
 * @return 轴 (0=X, 1=Y, 2=Z) 与该轴的正负方向
 */
internal fun snapAxis(direction: Vector): Pair<Int, Boolean> {
    val components = doubleArrayOf(direction.x, direction.y, direction.z)
    var axis = 0
    for (candidate in 1..2) {
        if (abs(components[candidate]) > abs(components[axis])) {
            axis = candidate
        }
    }
    return axis to (components[axis] >= 0)
}

internal fun locationOf(player: Player, block: IntArray): Location {
    return Location(player.world, block[0].toDouble(), block[1].toDouble(), block[2].toDouble())
}

/**
 * 选区状态 + 可视化 + 选区几何
 *
 * 从 WorldEditManager 拆出来的第一块 (第 9 轮重构)。只管"玩家的两个选区点"这一摊: 点本身、玻璃盒 /
 * 粒子显示、以及所有由点推出来的几何 (区域、方块坐标 → Location、轴向吸附、世界边界夹取)。不碰材料、
 * 不碰写方块管线, 所以它对其它子 manager 零依赖 —— 依赖图的最底层。
 *
 * 跨类共用的**无状态**小工具 ([locationOf] / [snapAxis] / [axisName] / [worldBorderBounds] /
 * [clampAxis] / [clampXZToBorder]) 与 [AXIS_X] 等常量放在本文件顶层而不是类里: 它们被
 * ShapeManager / ClipboardManager / EditPipeline 一起用, 放顶层才能"一份定义、零改动"地被各方调用
 * (做成成员就得给每个调用点加接收者前缀, 那是纯噪音)。带状态的成员仍然只在类里。
 */
class SelectionManager {

    // 选区与粒子任务会被玩家所在区域线程和异步操作线程同时读写, 用并发容器
    private val playerpos = ConcurrentHashMap<Player, Pair<Location?, Location?>>()

    private val particleTasks = ConcurrentHashMap<Player, PlatformExecutor.PlatformTask>()

    // 选区可视化 (半透明玻璃盒子), 用不了时退回粒子边框
    private val areaDisplay = AreaDisplay()

    private val settingManager = PlayerWorldEdit.settingManager

    // 配置在 TabooLib 注入阶段才可用, 不能在建类时读取; 重载后由 reloadCache() 丢弃重建
    @Volatile
    private var posToolCache: ItemStack? = null

    /**
     * 获取创世神镐 (返回副本, 避免玩家修改到插件缓存的实例)
     */
    fun getPosTool(): ItemStack {
        return (posToolCache ?: buildPosTool().also { posToolCache = it }).clone()
    }

    /**
     * 配置重载后丢弃缓存的创世神镐, 下次取用时按新配置重建
     */
    fun reloadCache() {
        posToolCache = null
    }

    private fun buildPosTool(): ItemStack {
        return buildItem(settingManager.getWorldEditSettings().getString("tool.type")?.parseToXMaterial() ?:XMaterial.DIAMOND_PICKAXE){
            name = settingManager.getWorldEditSettings().getString("tool.name") ?: "&7[&f创世神镐&7]"
            lore += settingManager.getWorldEditSettings().getStringList("tool.lore")
            customModelData = settingManager.getWorldEditSettings().getInt("tool.custom-model-data")
            colored()
        }.apply {
            markAsTool()
        }
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
        showSelection(player)
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
        showSelection(player)
    }

    /**
     * 刷新选区可视化
     *
     * 默认用半透明玻璃盒子 (见 [AreaDisplay]), 关掉 `region-display` 或盒子建不出来时退回
     * 粒子边框。
     *
     * @param player 玩家
     */
    fun showSelection(player: Player) {
        val region = getWERegion(player)
        if (region == null) {
            hideSelection(player)
            return
        }
        if (displayEnabled()) {
            stopParticle(player)
            areaDisplay.show(
                player,
                region,
                displayMaterial(),
                ownerOnly = displayOwnerOnly(),
                durationTicks = displayDurationTicks()
            ) { showParticle(player) }
        } else {
            areaDisplay.hide(player)
            showParticle(player)
        }
    }

    /**
     * 收掉选区可视化 (盒子和粒子都收)
     */
    fun hideSelection(player: Player) {
        stopParticle(player)
        areaDisplay.hide(player)
    }

    /**
     * 显示粒子
     *
     * 边框粒度随选区尺寸自适应 (最长边固定 100 个点, 总点数另有上限), 而且**按区块合并成一次任务**:
     * 原来是每个粒子点提交一个区域任务, 一个百来格的选区每秒钟会提交两千多个任务,
     * 区域线程光排队就卡住了。
     *
     * 显示时长跟着选区: 选区被清除或玩家退出才停 (与玻璃盒一致)。
     *
     * @param player 玩家
     */
    private fun showParticle(player: Player) {
        stopParticle(player)
        val pos1 = getPlayerPos1(player) ?: return
        val pos2 = getPlayerPos2(player) ?: return
        val particle = resolveParticle(settingManager.getWorldEditSettings().getString("region-particle")) ?: return
        val world = player.world
        val step = max(0.5, max(abs(pos1.x - pos2.x), max(abs(pos1.y - pos2.y), abs(pos1.z - pos2.z))) / PARTICLE_POINTS_PER_EDGE)
        val cube = createCube(pos1.toProxyLocation(), pos2.toProxyLocation(), step)
        var points = cube.calculateLocations().map { it.toBukkitLocation() }
        // "最长边 100 点"只约束了单条边, 立方体有 12 条边, 大选区算出来的总点数还是很多 ——
        // 每 20 tick 要给每个有粒子的区块排一个任务, 点数就是任务数。总点数超上限就加大步长重算。
        var reductions = 0
        while (points.size > PARTICLE_MAX_POINTS && reductions++ < PARTICLE_MAX_REDUCTIONS) {
            cube.step = cube.step * points.size / PARTICLE_MAX_POINTS.toDouble()
            points = cube.calculateLocations().map { it.toBukkitLocation() }
        }
        // 按区块分组, 每秒每个区块只提交一个任务
        val pointsByChunk = points.groupBy { (it.blockX shr 4) to (it.blockZ shr 4) }
        particleTasks[player] = submitAsync(period = 20) {
            // 粒子跟着选区走 (和玻璃盒一致): 只在自己下线时停, 选区被清除时由 stopParticle 停。
            // 原来还有一条 60 秒自停, 那样会变成"选区还在、提示没了", 和"成功后保留选区"矛盾。
            if (!player.isOnline) {
                cancel()
                particleTasks.remove(player)
                return@submitAsync
            }
            pointsByChunk.forEach { (chunk, locations) ->
                world.runTask(chunk.first * 16.0, chunk.second * 16.0, Runnable {
                    if (!player.isOnline) return@Runnable
                    locations.forEach { player.spawnParticle(particle, it, 1) }
                })
            }
        }
    }

    private fun stopParticle(player: Player) {
        particleTasks.remove(player)?.cancel()
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
     * 收掉所有玩家的选区显示体 (关插件/重载时用, 免得留下孤儿实体)
     */
    fun clearDisplays() {
        particleTasks.keys.toList().forEach { stopParticle(it) }
        areaDisplay.hideAll()
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

    internal fun updateSelection(player: Player, first: IntArray, second: IntArray) {
        setPlayerPos1(player, locationOf(player, first))
        setPlayerPos2(player, locationOf(player, second))
    }

    /**
     * 选区必须完整, 返回它的 WorldEdit 区域; 没选齐就提示并返回 null
     *
     * 抽出来是因为 move 要按"源区 ∪ 目标区"的包围盒单独查领地 (见 [moveSelection]), 那一处只取
     * 区域、不能走 [validatedRegion] 里那套"按选区查领地"。
     */
    internal fun selectionRegion(player: Player): CuboidRegion? {
        if (!hasRegion(player)) {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return null
        }
        return getWERegion(player)
    }

    private fun displayEnabled(): Boolean {
        return settingManager.getWorldEditSettings().getBoolean(DISPLAY_KEY, true)
    }

    private fun displayMaterial(): Material {
        val raw = settingManager.getWorldEditSettings().getString(DISPLAY_MATERIAL_KEY)
        return raw?.let { parseBlockOrNull(it) } ?: Material.WHITE_STAINED_GLASS
    }

    private fun displayOwnerOnly(): Boolean {
        return settingManager.getWorldEditSettings().getBoolean(DISPLAY_OWNER_ONLY_KEY, true)
    }

    private fun displayDurationTicks(): Long {
        val seconds = settingManager.getWorldEditSettings().getInt(DISPLAY_DURATION_KEY, DISPLAY_DURATION_DEFAULT)
        return seconds.coerceAtLeast(0) * 20L
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

    /**
     * 玩家退出 / 主动清除选区时的状态清理: 点、粒子、玻璃盒代号表
     *
     * 剪贴板 / paste 预览 / 形状尺寸这些 per-UUID 状态由各自的 manager 清, 统一由门面的
     * [WorldEditManager.clearPlayerPos] 聚合调用 —— 少清一处就是"按 UUID 只增不删"的表。
     */
    internal fun forget(player: Player) {
        playerpos.remove(player)
        stopParticle(player)
        // 用 forget 而不是 hideSelection: 选区彻底没了, 顺手把代号表的条目一起清掉,
        // 免得它按 UUID 只增不删
        areaDisplay.forget(player)
    }

    /**
     * 按当前显示配置画一个临时盒子 (paste 预览用): 复用选区的显示开关与材质, 但只发给本人
     *
     * 关掉玻璃盒显示 (region-display) 时什么都不画: 预览的粒子版本不做 —— 粒子边框是按选区的两个
     * 点画的, 而预览框是另一个区域, 为它再写一套粒子逻辑不值当。
     */
    internal fun showPreviewBox(player: Player, region: CuboidRegion, durationTicks: Long) {
        if (!displayEnabled()) return
        areaDisplay.show(player, region, displayMaterial(), ownerOnly = true, durationTicks = durationTicks)
    }

    private companion object {
        /** 是否用半透明玻璃盒子做选区可视化 */
        const val DISPLAY_KEY = "region-display"

        /** 盒子材质 (默认白色染色玻璃) */
        const val DISPLAY_MATERIAL_KEY = "region-display-material"

        /** 是否只把盒子发给选区主人 */
        const val DISPLAY_OWNER_ONLY_KEY = "region-display-owner-only"

        /** 盒子显示的秒数 */
        const val DISPLAY_DURATION_KEY = "region-display-duration"

        /**
         * 盒子显示时长的默认值: 0 = 不自动收
         *
         * 成功后保留选区 (8A) 之后, 盒子应该跟着选区一起存在 —— 选区被清除或玩家退出时才收,
         * 否则玩家会看到"选区还在、盒子没了"。这是默认行为的变更 (原来是 10 秒)。
         * 服务器配置里显式写了值的仍以配置为准。
         */
        const val DISPLAY_DURATION_DEFAULT = 0

        /** 选区粒子边框在最长边上画多少个点 (其余边按同样步长, 总点数因此有上限) */
        const val PARTICLE_POINTS_PER_EDGE = 100.0

        /** 粒子边框的总点数上限 (超过就加大步长) —— 点数直接决定每 20 tick 提交多少个区块任务 */
        const val PARTICLE_MAX_POINTS = 2000

        /** 为了压到总点数上限, 最多重算几次 (防止步长算法意外不收敛时死循环) */
        const val PARTICLE_MAX_REDUCTIONS = 5

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

package cn.stingcraft.plugin.until

import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import taboolib.common.platform.function.warning
import taboolib.platform.util.runTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 选区可视化: 半透明玻璃盒子 (仿 Dominion 的选区效果)
 *
 * 做法和 Dominion 一样: 在选区最小角放 6 个 [BlockDisplay] —— 底、顶、四个侧面各一片薄板,
 * 缩放成选区的宽高深, 亮度拉满(15/15), 看起来就是一个发光的半透明白玻璃盒。
 * 实体只发给选区主人自己 (`setVisibleByDefault(false)` + `showEntity`), 别人看不到。
 *
 * 为什么实体放在选区角上而不是玩家身上: Folia 里实体必须在**拥有它的区域线程**上创建/修改/删除,
 * 选区角所在的区域和玩家所在的区域不一定是同一个, 所以这些操作统一用 `World.runTask(x, z)`
 * 排到对应区域线程; 只有 `showEntity` 这一步回到玩家自己的线程做。
 */
class AreaDisplay(private val plugin: Plugin) {

    private class Box(val world: World, val x: Int, val z: Int, val entities: List<BlockDisplay>)

    private val boxes = ConcurrentHashMap<UUID, Box>()

    // 每次变更都 +1; 排队中的旧任务发现代号变了就自己把实体收掉, 免得快速连点留下孤儿实体
    private val generations = ConcurrentHashMap<UUID, Int>()

    /**
     * 画出选区盒子; 选区不完整或实体创建失败时什么都不做 (调用方会退回粒子边框)
     */
    fun show(player: Player, region: CuboidRegion, material: Material) {
        if (!player.isOnline) return
        val min = region.minimumPoint
        val max = region.maximumPoint
        // 选区是方块坐标, 盒子要盖住最后一个方块, 所以每一轴 +1
        val sizeX = (max.x - min.x + 1).toFloat()
        val sizeY = (max.y - min.y + 1).toFloat()
        val sizeZ = (max.z - min.z + 1).toFloat()
        if (sizeX <= 0f || sizeY <= 0f || sizeZ <= 0f) return
        val world = player.world
        val origin = Location(world, min.x.toDouble(), min.y.toDouble(), min.z.toDouble())
        val transformations = runCatching { boxFaces(sizeX, sizeY, sizeZ) }.getOrNull() ?: return
        hide(player)
        val generation = nextGeneration(player.uniqueId)
        world.runTask(origin.x, origin.z, Runnable {
            val blockData = material.createBlockData()
            val spawned = ArrayList<BlockDisplay>(transformations.size)
            transformations.forEach { transformation ->
                runCatching {
                    world.spawn(origin, BlockDisplay::class.java) { display ->
                        display.block = blockData
                        display.brightness = Display.Brightness(15, 15)
                        display.viewRange = VIEW_RANGE
                        display.isPersistent = false
                        display.isInvulnerable = true
                        display.isSilent = true
                        display.setGravity(false)
                        display.setVisibleByDefault(false)
                        display.transformation = transformation
                    }
                }.onSuccess {
                    spawned += it
                }.onFailure {
                    warning("[PlayerWorldEdit] 创建选区显示体失败: $it")
                }
            }
            // 选区已经变了或者玩家下线了, 这组实体就不要了
            if (spawned.isEmpty() || generations[player.uniqueId] != generation || !player.isOnline) {
                spawned.forEach { runCatching { it.remove() } }
                return@Runnable
            }
            boxes[player.uniqueId] = Box(world, min.x, min.z, spawned)
            // 只给选区主人看
            player.runOnOwnThread {
                runCatching { spawned.forEach { player.showEntity(plugin, it) } }.onFailure {
                    warning("[PlayerWorldEdit] 显示选区盒子失败: $it")
                }
            }
        })
    }

    /** 收掉这个玩家的盒子 */
    fun hide(player: Player) {
        nextGeneration(player.uniqueId)
        remove(boxes.remove(player.uniqueId))
    }

    /** 收掉所有盒子 (关插件/重载时用, 免得留下孤儿实体) */
    fun hideAll() {
        boxes.keys.toList().forEach { uuid -> remove(boxes.remove(uuid)) }
    }

    private fun remove(box: Box?) {
        if (box == null) return
        box.world.runTask(box.x.toDouble(), box.z.toDouble(), Runnable {
            box.entities.forEach { runCatching { it.remove() } }
        })
    }

    private fun nextGeneration(uuid: UUID): Int = generations.merge(uuid, 1, Int::plus) ?: 0

    /**
     * 6 个薄板: 每片只比选区厚 [WALL_THICKNESS], 拼成一个空心的盒子
     *
     * BlockDisplay 的方块模型在本地坐标里占 [0,1]³, 先按 scale 缩放、再按 translation 平移,
     * 所以"从 offset 开始、长 scale"这句话直接对应 translation = offset。
     */
    private fun boxFaces(sizeX: Float, sizeY: Float, sizeZ: Float): List<Transformation> {
        val t = WALL_THICKNESS
        return listOf(
            // 底 / 顶
            Joml.transformation(0f, 0f, 0f, sizeX, t, sizeZ),
            Joml.transformation(0f, sizeY - t, 0f, sizeX, t, sizeZ),
            // 北 / 南
            Joml.transformation(0f, 0f, 0f, sizeX, sizeY, t),
            Joml.transformation(0f, 0f, sizeZ - t, sizeX, sizeY, t),
            // 西 / 东
            Joml.transformation(0f, 0f, 0f, t, sizeY, sizeZ),
            Joml.transformation(sizeX - t, 0f, 0f, t, sizeY, sizeZ),
        )
    }

    private companion object {

        /** 薄板厚度 (格) */
        const val WALL_THICKNESS = 0.05f

        /** 显示体的可视距离倍数, 拉大一点, 站远了盒子也不会消失 */
        const val VIEW_RANGE = 8f
    }
}

/**
 * 反射构造 `org.joml.Vector3f` / `Quaternionf` / `Transformation`
 *
 * joml 不在编译依赖里 (由服务端自带), 而 `Transformation` 的构造函数参数是 joml 类型,
 * 所以只能反射。范围只有这三个类, 不涉及任何 NMS —— 上次 NMS 在这个核心上踩过坑。
 */
private object Joml {

    private val vector3f: Class<*> = Class.forName("org.joml.Vector3f")
    private val quaternionf: Class<*> = Class.forName("org.joml.Quaternionf")
    private val vector3fConstructor = vector3f.getConstructor(
        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
    )
    private val quaternionfConstructor = quaternionf.getConstructor()
    private val transformationConstructor =
        Transformation::class.java.getConstructor(vector3f, quaternionf, vector3f, quaternionf)

    fun transformation(
        offsetX: Float, offsetY: Float, offsetZ: Float,
        scaleX: Float, scaleY: Float, scaleZ: Float
    ): Transformation {
        return transformationConstructor.newInstance(
            vector3fConstructor.newInstance(offsetX, offsetY, offsetZ),
            quaternionfConstructor.newInstance(),
            vector3fConstructor.newInstance(scaleX, scaleY, scaleZ),
            quaternionfConstructor.newInstance()
        ) as Transformation
    }
}

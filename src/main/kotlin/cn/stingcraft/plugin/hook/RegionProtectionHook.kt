package cn.stingcraft.plugin.hook

import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * 领地保护的统一结果
 *
 * 三态而不是"有权限 / 没权限": 必须能把"确认没有保护"和"查询出错、无法确认"分开 —— 后者要按
 * fail-closed 拒绝, 不能因为某个保护插件内部出错就当成没领地放行 (任务 9C 的教训)。
 */
sealed interface ProtectionCheck {

    /** 确认可以编辑 */
    object Allowed : ProtectionCheck

    /**
     * 被某一层保护拒绝
     *
     * @param source 保护插件名 (提示里要告诉玩家是哪一个插件拦的)
     * @param detail 该插件给的原因 (领地 / 区域名等)
     */
    data class Denied(val source: String, val detail: String) : ProtectionCheck

    /** 查询过程本身出错, 无法确认 -> 调用方必须拒绝 */
    data class Failed(val cause: Throwable) : ProtectionCheck
}

/**
 * 一个领地保护插件的接入点 (Dominion / Residence / WorldGuard)
 *
 * 每个实现只负责"怎么查"这一件事; "全部放行才放行、任何 Failed 都 fail-closed"的收口逻辑在
 * [ProtectionHooks] 里只有一份 —— 三个钩子不许各写一遍。
 */
interface RegionProtectionHook {

    /** 保护插件名 (提示文案与控制台日志用) */
    val name: String

    /**
     * 这层保护现在生效吗 (插件装了并启用了)
     *
     * 必须每次现查, 不能用 lazy: 保护插件比本插件晚启用时, 第一次求值会把结果永久钉死成
     * "没装", 之后这层保护就再也不生效了。
     */
    fun isAvailable(): Boolean

    /**
     * 玩家能不能编辑这个范围内的所有领地
     *
     * @param needBreak 是否需要破坏权限 (替换 / 清空 / 移动这类操作要)
     */
    fun check(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): ProtectionCheck
}

/** 采样间距 (格): 与区块同尺度, 所以"按点查询"型钩子的粒度就是区块级 */
private const val PROBE_STEP = 16

/**
 * "按点查询"型钩子 (Residence / WorldGuard) 的采样点
 *
 * 这两个插件的公开查询入口都是"给我一个坐标", 没有 Dominion 那种"遍历相交领地"的批量入口,
 * 所以只能在外接框里按 [PROBE_STEP] 格取格点 (两端一定取到)。代价说清楚: 比采样间距还小、又
 * 正好落在两个采样点之间的领地可能查不到 —— 要 100% 覆盖只能逐格查, 那是 O(体积) 次查询。
 * 真正安全的做法是像 Dominion 那样遍历相交区域, 见报告里的取舍说明。
 */
internal fun protectionProbePoints(corner1: Location, corner2: Location): List<Location> {
    val world = corner1.world ?: return emptyList()
    val xs = probeCoordinates(minOf(corner1.blockX, corner2.blockX), maxOf(corner1.blockX, corner2.blockX))
    val ys = probeCoordinates(minOf(corner1.blockY, corner2.blockY), maxOf(corner1.blockY, corner2.blockY))
    val zs = probeCoordinates(minOf(corner1.blockZ, corner2.blockZ), maxOf(corner1.blockZ, corner2.blockZ))
    val points = ArrayList<Location>(xs.size * ys.size * zs.size)
    for (x in xs) {
        for (y in ys) {
            for (z in zs) {
                points += Location(world, x.toDouble(), y.toDouble(), z.toDouble())
            }
        }
    }
    return points
}

/** 一个轴上要采样的坐标: 从 [min] 起每 [PROBE_STEP] 格一个, 最后一个一定是 [max] */
private fun probeCoordinates(min: Int, max: Int): IntArray {
    val result = ArrayList<Int>()
    var value = min
    while (value < max) {
        result += value
        value += PROBE_STEP
    }
    result += max
    return result.toIntArray()
}

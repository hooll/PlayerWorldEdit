package cn.stingcraft.plugin.hook

/**
 * 方块坐标的闭区间盒 (世界名 + min/max), 领地保护钩子共用的几何件
 *
 * 抽成顶层的小类而不是塞在某个钩子里: 它不依赖任何插件 API, 所以**可以脱离服务端直接测**
 * (R6/R7 的正确性全靠这几段几何: 逐位置切分、领地独占范围), 三个钩子以后要用也直接复用。
 */
internal class ProtectionBox(val worldName: String, val min: IntArray, val max: IntArray) {

    /** 与 [other] 是否相交 (**贴边也算相交**, 与 Dominion 钩子的 `<=` / `>=` 口径一致) */
    fun overlaps(other: ProtectionBox): Boolean =
        min[0] <= other.max[0] && max[0] >= other.min[0] &&
            min[1] <= other.max[1] && max[1] >= other.min[1] &&
            min[2] <= other.max[2] && max[2] >= other.min[2]

    /** 两个盒的交集; 不相交返回 null */
    fun intersect(other: ProtectionBox): ProtectionBox? {
        if (!overlaps(other)) return null
        return ProtectionBox(
            worldName,
            intArrayOf(maxOf(min[0], other.min[0]), maxOf(min[1], other.min[1]), maxOf(min[2], other.min[2])),
            intArrayOf(minOf(max[0], other.max[0]), minOf(max[1], other.max[1]), minOf(max[2], other.max[2]))
        )
    }

    /** 体积 (闭区间: 每轴都是 max - min + 1) */
    fun volume(): Long =
        (max[0] - min[0] + 1).toLong() * (max[1] - min[1] + 1) * (max[2] - min[2] + 1)

    /**
     * 本盒挖掉 [cutter] 之后剩下的部分 (最多 6 片: X/Y/Z 三个轴各两侧)
     *
     * 不相交时原样返回; 完全被覆盖时返回空表。用于算"领地独占的那部分空间" (R7)。
     */
    fun minus(cutter: ProtectionBox): List<ProtectionBox> {
        val xLo = maxOf(min[0], cutter.min[0])
        val xHi = minOf(max[0], cutter.max[0])
        val yLo = maxOf(min[1], cutter.min[1])
        val yHi = minOf(max[1], cutter.max[1])
        val zLo = maxOf(min[2], cutter.min[2])
        val zHi = minOf(max[2], cutter.max[2])
        if (xLo > xHi || yLo > yHi || zLo > zHi) return listOf(this)
        val parts = ArrayList<ProtectionBox>(6)
        fun part(x1: Int, x2: Int, y1: Int, y2: Int, z1: Int, z2: Int) {
            if (x1 <= x2 && y1 <= y2 && z1 <= z2) {
                parts += ProtectionBox(worldName, intArrayOf(x1, y1, z1), intArrayOf(x2, y2, z2))
            }
        }
        // X 轴两侧
        part(min[0], xLo - 1, min[1], max[1], min[2], max[2])
        part(xHi + 1, max[0], min[1], max[1], min[2], max[2])
        // 中间那条 X 带里再切 Y 两侧
        part(xLo, xHi, min[1], yLo - 1, min[2], max[2])
        part(xLo, xHi, yHi + 1, max[1], min[2], max[2])
        // 中间那条 X/Y 带里再切 Z 两侧
        part(xLo, xHi, yLo, yHi, min[2], zLo - 1)
        part(xLo, xHi, yLo, yHi, zHi + 1, max[2])
        return parts
    }
}

/**
 * 盒减法: 把 [cut] 覆盖的部分从 [from] 里全部挖掉
 *
 * 逐个 cutter 依次挖, 每一步都可能把盒切成更多片 —— 片数会增长, 但一个领地只有几块区域、几层
 * 子领地, 量级很小。返回的是"剩下的空间", 非空就说明这个领地在那块地方是最终裁决者 (R7)。
 */
internal fun subtractBoxes(from: List<ProtectionBox>, cut: List<ProtectionBox>): List<ProtectionBox> {
    if (cut.isEmpty()) return from
    var result = from
    for (cutter in cut) {
        val next = ArrayList<ProtectionBox>(result.size)
        result.forEach { box -> next += box.minus(cutter) }
        result = next
        if (result.isEmpty()) return result
    }
    return result
}

/**
 * 某一轴上把 [low]..[high] 切开的边界点 (含两端): 区域的最小坐标与"最大坐标 + 1"
 *
 * 只收落在 (low, high] 里的点: 等于 low 的边界对切分没有意义, 超出 high 的夹掉。相邻的两个区域
 * (0..9 与 10..19) 因此在 10 处切一刀 —— 每个格元只会与它真正覆盖的那些区域相交, 不会把邻居算进来,
 * 所以既不会漏也不会因为贴边而误拒 (R6 的逐位置判定靠它保证"格元里适用哪些区域是常量")。
 */
internal fun axisCuts(low: Int, high: Int, bounds: List<Pair<Int, Int>>): List<Int> {
    val points = sortedSetOf(low, high + 1)
    bounds.forEach { (regionMin, regionMax) ->
        if (regionMin in (low + 1)..high) {
            points.add(regionMin)
        }
        if (regionMax + 1 in (low + 1)..high) {
            points.add(regionMax + 1)
        }
    }
    return points.toList()
}

package cn.stingcraft.plugin.util

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.max
import kotlin.math.min

fun getCuboidEdges(pos1: Location, pos2: Location, step: Double = 0.5): List<Location> {
    val world = pos1.world ?: return emptyList()

    val minX = minOf(pos1.x, pos2.x)
    val maxX = maxOf(pos1.x, pos2.x)
    val minY = minOf(pos1.y, pos2.y)
    val maxY = maxOf(pos1.y, pos2.y)
    val minZ = minOf(pos1.z, pos2.z)
    val maxZ = maxOf(pos1.z, pos2.z)

    // 8个顶点
    val corners = listOf(
        Vector(minX, minY, minZ), // 0
        Vector(maxX, minY, minZ), // 1
        Vector(maxX, minY, maxZ), // 2
        Vector(minX, minY, maxZ), // 3
        Vector(minX, maxY, minZ), // 4
        Vector(maxX, maxY, minZ), // 5
        Vector(maxX, maxY, maxZ), // 6
        Vector(minX, maxY, maxZ)  // 7
    )

    // 每条边用两个点表示
    val edgeIndices = listOf(
        // bottom
        0 to 1, 1 to 2, 2 to 3, 3 to 0,
        // top
        4 to 5, 5 to 6, 6 to 7, 7 to 4,
        // verticals
        0 to 4, 1 to 5, 2 to 6, 3 to 7
    )

    fun interpolate(start: Vector, end: Vector): List<Location> {
        val dir = end.clone().subtract(start)
        val len = dir.length()
        if (len == 0.0) return listOf(Location(world, start.x, start.y, start.z))

        dir.normalize()
        val result = mutableListOf<Location>()
        var d = 0.0
        while (d <= len) {
            val point = start.clone().add(dir.clone().multiply(d))
            result.add(Location(world, point.x, point.y, point.z))
            d += step
        }
        result.add(Location(world, end.x, end.y, end.z)) // 保证终点
        return result
    }

    return edgeIndices.flatMap { (i1, i2) ->
        interpolate(corners[i1], corners[i2])
    }
}
package cn.stingcraft.plugin.hook

import cn.lunadeer.dominion.api.DominionAPI
import cn.lunadeer.dominion.api.dtos.flag.Flags
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player

object DominionHook {

    private val available: Boolean by lazy {
        runCatching { Bukkit.getPluginManager().isPluginEnabled("Dominion") }.getOrDefault(false)
    }

    /**
     * 检查玩家是否有权编辑选区范围内的所有领地
     * @param needBreak 是否同时需要破坏权限 (替换操作需要)
     * @return 无权时返回拒绝的领地名, 有权或无重叠领地时返回 null
     */
    fun findDeniedDominion(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): String? {
        if (!available) {
            return null
        }
        return runCatching {
            val api = DominionAPI.getInstance() ?: return@runCatching null
            val world = corner1.world ?: return@runCatching null
            val minX = minOf(corner1.blockX, corner2.blockX)
            val minY = minOf(corner1.blockY, corner2.blockY)
            val minZ = minOf(corner1.blockZ, corner2.blockZ)
            val maxX = maxOf(corner1.blockX, corner2.blockX)
            val maxY = maxOf(corner1.blockY, corner2.blockY)
            val maxZ = maxOf(corner1.blockZ, corner2.blockZ)
            for (dominion in api.allDominions) {
                if (dominion.world?.uid != world.uid) {
                    continue
                }
                val pos1 = dominion.cuboid.pos1
                val pos2 = dominion.cuboid.pos2
                val overlap = minOf(pos1[0], pos2[0]) <= maxX && maxOf(pos1[0], pos2[0]) >= minX &&
                    minOf(pos1[1], pos2[1]) <= maxY && maxOf(pos1[1], pos2[1]) >= minY &&
                    minOf(pos1[2], pos2[2]) <= maxZ && maxOf(pos1[2], pos2[2]) >= minZ
                if (!overlap) {
                    continue
                }
                val allowed = api.checkPrivilegeFlag(dominion, Flags.PLACE, player) &&
                    (!needBreak || api.checkPrivilegeFlag(dominion, Flags.BREAK_BLOCK, player))
                if (!allowed) {
                    return@runCatching dominion.name
                }
            }
            null
        }.getOrNull()
    }
}

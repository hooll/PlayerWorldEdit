package cn.stingcraft.plugin.hook

import cn.lunadeer.dominion.api.DominionAPI
import cn.lunadeer.dominion.api.dtos.flag.Flags
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning

/**
 * 领地校验结果
 *
 * 三态而不是原来的"领地名 / null": 必须能把"校验过程出错、无法确认"和"确认没有重叠领地"
 * 分开 —— 前者按 fail-closed 要拒绝, 不能因为 API 出错就当成没领地放行。
 */
sealed interface DominionCheck {

    /** 确认可以编辑 */
    object Allowed : DominionCheck

    /** 选区压到了没有权限的领地 */
    data class Denied(val dominion: String) : DominionCheck

    /** 校验过程本身出错, 无法确认 -> 调用方必须拒绝 */
    data class Failed(val cause: Throwable) : DominionCheck
}

object DominionHook {

    /**
     * Dominion 现在装没装 / 启没启用。
     *
     * 每次现查, 不能用 lazy: Dominion 比本插件晚启用时, 第一次求值会把结果永久钉死成
     * "没装", 之后领地保护就再也不生效了。isPluginEnabled 只是查一下插件表, 很便宜。
     */
    private val available: Boolean
        get() = runCatching { Bukkit.getPluginManager().isPluginEnabled("Dominion") }.getOrDefault(false)

    /**
     * 检查玩家是否有权编辑选区范围内的所有领地
     *
     * 校验出错返回 [DominionCheck.Failed] 而不是放行: Dominion 版本不兼容、内部 NPE 之类的问题
     * 会让领地保护静默失效, 而"无法确认"不等于"允许"。异常会打进控制台日志。
     *
     * @param needBreak 是否同时需要破坏权限 (替换操作需要)
     */
    fun checkAreaEdit(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): DominionCheck {
        // 没装 / 没启用 Dominion 是"没有这层保护", 不是"无法确认"
        if (!available) {
            return DominionCheck.Allowed
        }
        return try {
            val api = DominionAPI.getInstance()
                ?: throw IllegalStateException("Dominion 已启用, 但 DominionAPI.getInstance() 返回 null")
            val world = corner1.world
                ?: throw IllegalStateException("选区所在世界未加载: $corner1")
            val minX = minOf(corner1.blockX, corner2.blockX)
            val minY = minOf(corner1.blockY, corner2.blockY)
            val minZ = minOf(corner1.blockZ, corner2.blockZ)
            val maxX = maxOf(corner1.blockX, corner2.blockX)
            val maxY = maxOf(corner1.blockY, corner2.blockY)
            val maxZ = maxOf(corner1.blockZ, corner2.blockZ)
            for (dominion in api.allDominions) {
                // 粗筛第一层: 直接读世界 UUID, 比 getWorld()?.uid 少一次世界查找
                if (dominion.worldUid != world.uid) {
                    continue
                }
                val cuboid = dominion.cuboid
                // 粗筛第二层: 用 int getter 取包围盒 —— 原来的 pos1()/pos2() 每次调用都新建 int[],
                // 领地多的服上这才是主要开销。
                // 语义保持"贴边也算相交"(<=/>=): CuboidDTO.intersectWith 用的是严格相交(< />),
                // 换成它会让紧贴领地边界的选区不再被拦, 等于放松保护。
                val overlap = minOf(cuboid.x1(), cuboid.x2()) <= maxX && maxOf(cuboid.x1(), cuboid.x2()) >= minX &&
                    minOf(cuboid.y1(), cuboid.y2()) <= maxY && maxOf(cuboid.y1(), cuboid.y2()) >= minY &&
                    minOf(cuboid.z1(), cuboid.z2()) <= maxZ && maxOf(cuboid.z1(), cuboid.z2()) >= minZ
                if (!overlap) {
                    continue
                }
                // 只有真的相交的领地才做权限检查 (checkPrivilegeFlag 是这里最贵的一步)
                val allowed = api.checkPrivilegeFlag(dominion, Flags.PLACE, player) &&
                    (!needBreak || api.checkPrivilegeFlag(dominion, Flags.BREAK_BLOCK, player))
                if (!allowed) {
                    return DominionCheck.Denied(dominion.name)
                }
            }
            DominionCheck.Allowed
        } catch (throwable: Throwable) {
            warning("[PlayerWorldEdit] 领地校验失败: $throwable")
            DominionCheck.Failed(throwable)
        }
    }
}

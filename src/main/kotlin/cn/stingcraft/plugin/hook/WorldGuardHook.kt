package cn.stingcraft.plugin.hook

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.regions.RegionQuery
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning

/**
 * WorldGuard 的领地保护钩子 (官方 API, 不反射)
 *
 * `worldguard-bukkit` + `worldguard-core` 7.0.14 放在 `libs/` 里 compileOnly; 没装 WG 时整条路径
 * 不会被走到 ([isAvailable] 先挡住)。用 `RegionQuery` 而不是遍历 `RegionManager`: 前者是 WG 官方
 * 推荐的只读查询入口, 线程安全 (WG 7.0.9+ 官方支持 Folia), 而且权限解析 (优先级 / 继承 / 成员)
 * 由 WG 自己做, 我们不去重新实现一遍它的 flag 规则。
 *
 * **flag 组合来自 `RegionQuery.testBuild` 的字节码**:
 * `testBuild(loc, player, flag)` = `StateFlag.test(combine(denyToNone(queryState(BUILD)), queryState(flag)))`,
 * 即"BUILD **或** 传入的那个 flag 允许"; 所以:
 * - `needBreak = false` -> `testBuild(..., Flags.BLOCK_PLACE)` = BUILD 或 PLACE
 * - `needBreak = true`  -> 再要求 `testBuild(..., Flags.BLOCK_BREAK)` = BUILD 或 BREAK
 * 两次取与 —— 与 Dominion 钩子的 `PLACE && (!needBreak || BREAK_BLOCK)` 结构逐字对应, 三个钩子
 * 对同一个操作给出的结论因此是一致的。
 */
object WorldGuardHook : RegionProtectionHook {

    override val name = "WorldGuard"

    override fun isAvailable(): Boolean =
        runCatching { Bukkit.getPluginManager().isPluginEnabled("WorldGuard") }.getOrDefault(false)

    override fun check(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): ProtectionCheck {
        // 没装 / 没启用 WorldGuard 是"没有这层保护", 不是"无法确认"
        if (!isAvailable()) {
            return ProtectionCheck.Allowed
        }
        return try {
            val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
            val local = WorldGuardPlugin.inst().wrapPlayer(player)
            for (point in protectionProbePoints(corner1, corner2)) {
                val wePoint = BukkitAdapter.adapt(point)
                val placeOk = query.testBuild(wePoint, local, Flags.BLOCK_PLACE)
                val breakOk = !needBreak || query.testBuild(wePoint, local, Flags.BLOCK_BREAK)
                if (!placeOk || !breakOk) {
                    return ProtectionCheck.Denied(name, regionNameAt(query, wePoint))
                }
            }
            ProtectionCheck.Allowed
        } catch (throwable: Throwable) {
            // WG 版本不兼容 (NoSuchMethodError 之类) = 无法确认, 按 fail-closed 拒绝
            warning("[PlayerWorldEdit] WorldGuard 领地校验失败: $throwable")
            ProtectionCheck.Failed(throwable)
        }
    }

    /**
     * 这一点上优先级最高的区域名, 只用于提示文案
     *
     * 只在"已经判定为拒绝"之后才多问一次 —— 允许的路径上一次都不查, 不给正常操作加开销。
     */
    private fun regionNameAt(query: RegionQuery, location: com.sk89q.worldedit.util.Location): String {
        return runCatching {
            query.getApplicableRegions(location).regions.maxByOrNull { it.priority }?.id
        }.getOrNull() ?: location.toString()
    }
}

package cn.stingcraft.plugin.hook

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.LocalPlayer
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.ApplicableRegionSet
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning

/** 相交查询用的临时区域 id (只用来做相交判定, 从不注册进 WG; 得是 WG 认的合法 id) */
private const val PROBE_REGION_ID = "pwe-probe"

/**
 * WorldGuard 的领地保护钩子 (官方 API, 不反射)
 *
 * `worldguard-bukkit` + `worldguard-core` 7.0.14 放在 `libs/` 里 compileOnly; 没装 WG 时整条路径
 * 不会被走到 ([isAvailable] 先挡住)。
 *
 * **一次相交查询覆盖整个选区框** (REV-R8-01 的修法): 按选区对角造一个临时 `ProtectedCuboidRegion`,
 * 交给 `RegionManager.getApplicableRegions(ProtectedRegion)` —— WG 用区域索引自己做精确相交判定,
 * 返回所有相交的区域 (含 `__global__`, 见下), 一次调用、零盲区。改之前是每 16 格取一个采样点逐点
 * 查, 小于采样间距又正好卡在两点之间的区域会被漏放行 —— 对保护插件来说等于保护失效。
 *
 * **为什么不用 `RegionQuery`**: 它只有"按点查询"的入口 (`testBuild(Location, ...)`), 要覆盖一个
 * 盒子就得逐格/逐采样点查; 区域相交的入口只在 `RegionManager` 上。两者最终都落到同一个
 * `ApplicableRegionSet` + 同一套 flag 解析 (见 [testBuild] 的字节码证据), 所以换成相交查询只改了
 * "集合里有谁", 没改判定规则。
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
        // Location 的 world 在正常调用路径上不会为 null, 拿不到世界就当这层保护管不到
        val world = corner1.world ?: return ProtectionCheck.Allowed
        return try {
            // 世界没被 WG 管 (未加载 / 未启用该世界): 这个世界里没有任何 WG 领地
            val regionManager = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))
                ?: return ProtectionCheck.Allowed
            val applicable = regionManager.getApplicableRegions(probeRegion(corner1, corner2))
            val local = WorldGuardPlugin.inst().wrapPlayer(player)
            if (testBuild(applicable, local, needBreak)) {
                ProtectionCheck.Allowed
            } else {
                ProtectionCheck.Denied(name, regionNameAt(applicable))
            }
        } catch (throwable: Throwable) {
            // WG 版本不兼容 (NoSuchMethodError 之类) = 无法确认, 按 fail-closed 拒绝
            warning("[PlayerWorldEdit] WorldGuard 领地校验失败: $throwable")
            ProtectionCheck.Failed(throwable)
        }
    }

    /** 按两个对角点造一个临时区域 (只用于相交查询, 从不注册进 WG) */
    private fun probeRegion(corner1: Location, corner2: Location): ProtectedCuboidRegion {
        val min = BlockVector3.at(
            minOf(corner1.blockX, corner2.blockX),
            minOf(corner1.blockY, corner2.blockY),
            minOf(corner1.blockZ, corner2.blockZ)
        )
        val max = BlockVector3.at(
            maxOf(corner1.blockX, corner2.blockX),
            maxOf(corner1.blockY, corner2.blockY),
            maxOf(corner1.blockZ, corner2.blockZ)
        )
        return ProtectedCuboidRegion(PROBE_REGION_ID, min, max)
    }

    /**
     * 判定"这个相交区域集允许玩家放置 (需要破坏时再要求破坏) 吗"
     *
     * 组合方式与 WG 的 `RegionQuery.testBuild` **逐字相同** —— 7.0.14 的字节码是
     * `StateFlag.test(StateFlag.combine(StateFlag.denyToNone(queryState(BUILD)), queryState(flag)))`,
     * 即"BUILD **或** 传入的那个 flag 允许":
     * - `needBreak = false` -> BUILD 或 PLACE
     * - `needBreak = true`  -> 再要求 BUILD 或 BREAK, 两次取与
     *
     * 结构上与 Dominion 钩子的 `PLACE && (!needBreak || BREAK_BLOCK)` 逐字对应, 三个钩子对同一个
     * 操作给出的结论因此是一致的。
     *
     * **不能用 `ApplicableRegionSet.testState`**: 它的字节码是 `StateFlag.test(queryState(flags))`,
     * 把传进去的多个 flag 当"与", 而且不含 BUILD —— `testState(player, BUILD, PLACE)` 会变成
     * "BUILD 且 PLACE", 与 `testBuild` 的"BUILD 或 PLACE"不是一个语义。
     */
    private fun testBuild(applicable: ApplicableRegionSet, player: LocalPlayer, needBreak: Boolean): Boolean {
        val buildState = applicable.queryState(player, Flags.BUILD)
        val flagState = applicable.queryState(player, if (needBreak) Flags.BLOCK_BREAK else Flags.BLOCK_PLACE)
        return StateFlag.test(StateFlag.combine(StateFlag.denyToNone(buildState), flagState))
    }

    /**
     * 相交区域里优先级最高的那个区域名, 只用于提示文案
     *
     * 相交集合里一定含 `__global__` (WG 的 `getApplicableRegions(ProtectedRegion)` 显式把它塞进
     * 结果集), 所以"只碰了全局区域"时提示里报的就是 `__global__` —— 与按点查询时的提示一致。
     */
    private fun regionNameAt(applicable: ApplicableRegionSet): String =
        applicable.regions.maxByOrNull { it.priority }?.id ?: PROBE_REGION_ID
}

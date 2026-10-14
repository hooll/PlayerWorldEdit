package cn.stingcraft.plugin.hook

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.LocalPlayer
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.ApplicableRegionSet
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.managers.RegionManager
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning

/** 相交查询用的临时区域 id (只用来做相交判定, 从不注册进 WG; 得是 WG 认的合法 id) */
private const val PROBE_REGION_ID = "pwe-probe"

/** WG 的全局区域 id (每个世界都有, 任何位置都适用) */
private const val GLOBAL_REGION_ID = "__global__"

/**
 * 逐位置判定最多允许切出多少个格元
 *
 * 格元数 = 各轴上"区域边界"把操作框切出来的段数之积 (相邻领地共用边界线, 所以正常只会切出几十个)。
 * 超过这个数说明操作框里叠了几十个边界互不相同的区域, 逐位置确认的代价会失控 —— 这时**明确失败**
 * (fail-closed, 见 [firstDenied]), 而不是退回按点采样: 采样会漏, 漏的正是保护。
 */
private const val MAX_CELLS = 4096

/**
 * WorldGuard 的领地保护钩子 (官方 API, 不反射)
 *
 * `worldguard-bukkit` + `worldguard-core` 7.0.14 放在 `libs/` 里 compileOnly; 没装 WG 时整条路径
 * 不会被走到 ([isAvailable] 先挡住)。
 *
 * **完整覆盖 + 逐位置判定** (REV-R8-01 + R6):
 *
 * 1. 按选区对角造一个临时 `ProtectedCuboidRegion`, 交给 `RegionManager.getApplicableRegions(ProtectedRegion)`
 *    —— WG 用区域索引自己做精确相交判定, 返回所有相交的区域 (含 `__global__`), 一次调用、零盲区。
 *    改之前是每 16 格取一个采样点逐点查, 小于采样间距又正好卡在两点之间的区域会被漏放行。
 * 2. 相交区域不止一个时, 把操作框按这些区域的**边界**切成格元, 每个格元单独查一次
 *    ([firstDenied])。理由: 一次查询把整个选区合成一个 `ApplicableRegionSet`, 而 WG 的 flag 是按
 *    **位置的区域组合 + 优先级**解析的 —— 选区里一处高优先级区域拒绝、另一处低优先级区域许可时,
 *    合成一个集合会把两处的结论混在一起 (2026-10-14 的 R6)。按边界切出来的格元里, "哪些区域适用"
 *    是常量, 所以逐格元查询 = 逐位置正确, 而且**没有采样**: 每一格都查了。
 * 3. 判定规则与 WG 自己逐字一致: 需要放置就要求 `BUILD 或 BLOCK_PLACE`, 需要破坏就要求
 *    `BUILD 或 BLOCK_BREAK` (见 [testBuild]), 两项都要时**两项都要求** —— 原来 needBreak=true 时
 *    把 PLACE 换成了 BREAK, 覆盖类操作因此漏了放置权限 (R5)。
 *
 * 格元按立方体近似多边形区域: WG 的区域可以是多边形 (2D), 一个格元里多边形可能只覆盖一部分, 这时
 * 该格元按"区域适用"处理 —— 方向是**更严**而不是漏 (与"贴边也算相交"同一个取向)。
 */
object WorldGuardHook : RegionProtectionHook {

    override val name = "WorldGuard"

    override fun isAvailable(): Boolean =
        runCatching { Bukkit.getPluginManager().isPluginEnabled("WorldGuard") }.getOrDefault(false)

    override fun check(player: Player, corner1: Location, corner2: Location, requirement: AreaPermission): ProtectionCheck {
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
            val local = WorldGuardPlugin.inst().wrapPlayer(player)
            val denied = firstDenied(regionManager, probeRegion(corner1, corner2), local, requirement)
            if (denied == null) ProtectionCheck.Allowed else ProtectionCheck.Denied(name, denied)
        } catch (throwable: Throwable) {
            // WG 版本不兼容 (NoSuchMethodError 之类) / 区域组合太复杂 = 无法确认, 按 fail-closed 拒绝
            warning("[PlayerWorldEdit] WorldGuard 领地校验失败: $throwable")
            ProtectionCheck.Failed(throwable)
        }
    }

    /**
     * 逐位置判定: 整个操作框都没有被拒时返回 null, 否则返回拦下来的那个区域名 (提示用)
     *
     * 只有全局区域适用时一次查询就够 (全局区域在任何位置都适用, 不存在"位置不同组合不同")。
     */
    private fun firstDenied(
        regionManager: RegionManager,
        box: ProtectedCuboidRegion,
        player: LocalPlayer,
        requirement: AreaPermission
    ): String? {
        val applicable = regionManager.getApplicableRegions(box)
        val named = applicable.regions.filter { it.id != GLOBAL_REGION_ID }
        if (named.isEmpty()) {
            return if (allowed(applicable, player, requirement)) null else regionNameAt(applicable)
        }
        for (cell in cellBoxes(box, named)) {
            val cellSet = regionManager.getApplicableRegions(cell)
            if (!allowed(cellSet, player, requirement)) {
                return regionNameAt(cellSet)
            }
        }
        return null
    }

    /**
     * 把操作框按相交区域的边界切成格元 (每个格元里"哪些区域适用"是常量)
     *
     * 边界取"区域的最小坐标"与"最大坐标 + 1": 相邻的两个区域 (0..9 与 10..19) 因此在 10 处切一刀,
     * 每个格元只会与它真正覆盖的那些区域相交 —— 不会把邻居也算进来 (所以既不会漏也不会因为贴边而
     * 误拒)。区域边界落在操作框外时对切分没有贡献 (整个框都在它里面或外面), 直接夹掉。
     */
    private fun cellBoxes(box: ProtectedCuboidRegion, regions: List<ProtectedRegion>): List<ProtectedCuboidRegion> {
        val min = box.minimumPoint
        val max = box.maximumPoint
        val xs = axisCuts(min.x(), max.x(), regions.map { it.minimumPoint.x() to it.maximumPoint.x() })
        val ys = axisCuts(min.y(), max.y(), regions.map { it.minimumPoint.y() to it.maximumPoint.y() })
        val zs = axisCuts(min.z(), max.z(), regions.map { it.minimumPoint.z() to it.maximumPoint.z() })
        val cells = (xs.size - 1).toLong() * (ys.size - 1) * (zs.size - 1)
        if (cells > MAX_CELLS) {
            throw IllegalStateException(
                "选区与 $cells 个区域格元相交 (上限 $MAX_CELLS): 逐位置确认领地权限的代价失控, 本次操作按无法确认处理"
            )
        }
        val result = ArrayList<ProtectedCuboidRegion>(cells.toInt())
        for (i in 0 until xs.size - 1) {
            for (j in 0 until ys.size - 1) {
                for (k in 0 until zs.size - 1) {
                    result += ProtectedCuboidRegion(
                        PROBE_REGION_ID,
                        BlockVector3.at(xs[i], ys[j], zs[k]),
                        BlockVector3.at(xs[i + 1] - 1, ys[j + 1] - 1, zs[k + 1] - 1)
                    )
                }
            }
        }
        return result
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
     * 这个位置上的区域组合允不允许 [requirement] 要求的每一项
     *
     * 放置与破坏**分别**按 WG 自己的组合规则判: 需要放置要求"BUILD 或 PLACE", 需要破坏要求
     * "BUILD 或 BREAK", 两项都要就是两次都通过。原来 needBreak=true 时把 PLACE 换成 BREAK, 等于
     * "BUILD 或 BREAK", 放置那一项从来没查过 (R5)。
     */
    private fun allowed(applicable: ApplicableRegionSet, player: LocalPlayer, requirement: AreaPermission): Boolean {
        if (requirement.needsPlace && !testBuild(applicable, player, Flags.BLOCK_PLACE)) {
            return false
        }
        if (requirement.needsBreak && !testBuild(applicable, player, Flags.BLOCK_BREAK)) {
            return false
        }
        return true
    }

    /**
     * 判定"这个相交区域集允许玩家放置/破坏 [flag] 吗"
     *
     * 组合方式与 WG 的 `RegionQuery.testBuild` **逐字相同** —— 7.0.14 的字节码是
     * `StateFlag.test(StateFlag.combine(StateFlag.denyToNone(queryState(BUILD)), queryState(flag)))`,
     * 即"BUILD **或** 传入的那个 flag 允许"。结构上与 Dominion 钩子的 `PLACE && BREAK_BLOCK` 逐字
     * 对应, 三个钩子对同一个操作给出的结论因此是一致的。
     *
     * **不能用 `ApplicableRegionSet.testState`**: 它的字节码是 `StateFlag.test(queryState(flags))`,
     * 把传进去的多个 flag 当"与", 而且不含 BUILD —— `testState(player, BUILD, PLACE)` 会变成
     * "BUILD 且 PLACE", 与 `testBuild` 的"BUILD 或 PLACE"不是一个语义。
     */
    private fun testBuild(applicable: ApplicableRegionSet, player: LocalPlayer, flag: StateFlag): Boolean {
        val buildState = applicable.queryState(player, Flags.BUILD)
        val flagState = applicable.queryState(player, flag)
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

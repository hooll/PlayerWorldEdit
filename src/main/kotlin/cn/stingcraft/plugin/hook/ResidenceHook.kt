package cn.stingcraft.plugin.hook

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import java.lang.reflect.Method

/**
 * Residence 的领地保护钩子 (反射实现)
 *
 * Residence 的 API jar 不在公共 Maven 上, 没法像 Dominion 那样 compileOnly, 所以整条调用链走反射;
 * 反射面很小, 全部集中在这个文件里。
 *
 * **覆盖方式: 遍历全部领地 + 包围盒相交预筛** (与 Dominion 钩子同一个模式)。原来是在外接框里每 16 格
 * 取一个采样点逐点 `getByLoc`, 小于采样间距、又正好卡在两个采样点之间的领地会被**漏放行** —— 对保护
 * 插件来说等于保护失效, 所以改成"一个都不漏"的遍历:
 *
 * 1. `ResidenceManager.getResidences()` 拿全部主领地;
 * 2. 每个领地用 `getAreaArray()` 的每块 `CuboidArea` 与操作框做**贴边也算相交**的包围盒预筛
 *    (一个领地可以有多块区域, 而且各带自己的世界名, 只比第一块会漏);
 * 3. 只有相交的领地才查旗 (旗查询是最贵的一步);
 * 4. 相交领地再递归 `getSubzones()` —— 子领地权限独立, 漏一个就是漏保护; 子领地同样先过相交预筛,
 *    所以"旗查询次数 = 相交领地数"对子领地也成立。
 *
 * 代价是 O(领地数) 次反射调用 (与 Dominion 的 `allDominions` 同量级), 换来的是零盲区。
 *
 * **语义与 Residence 自己的方块放置/破坏检查逐字一致** —— `ResidenceBlockListener` 的字节码是
 * `playerHas(player, Flags.place, playerHas(player, Flags.build, true))` (破坏那一路换 destroy),
 * 所以这里也这么问。`build` 的默认值传 **true** 而不是 false: Residence 的领地大多没有显式设过
 * `build` 旗, 传 false 会把本来有权限的玩家拦下来 (比 Residence 自己还严)。
 */
object ResidenceHook : RegionProtectionHook {

    override val name = "Residence"

    /**
     * 只看插件在不在
     *
     * 反射目标解析失败 (版本不匹配) **不算**"没装": 那种情况要按 fail-closed 拒绝, 见 [check]。
     * 否则换个版本的 Residence 就能让这层保护静默失效。
     */
    override fun isAvailable(): Boolean =
        runCatching { Bukkit.getPluginManager().isPluginEnabled("Residence") }.getOrDefault(false)

    override fun check(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): ProtectionCheck {
        // 没装 / 没启用 Residence 是"没有这层保护", 不是"无法确认"
        if (!isAvailable()) {
            return ProtectionCheck.Allowed
        }
        // Location 的 world 在正常调用路径上不会为 null, 拿不到世界就当这层保护管不到
        val world = corner1.world ?: return ProtectionCheck.Allowed
        val box = EditBox(
            world.name,
            intArrayOf(
                minOf(corner1.blockX, corner2.blockX),
                minOf(corner1.blockY, corner2.blockY),
                minOf(corner1.blockZ, corner2.blockZ)
            ),
            intArrayOf(
                maxOf(corner1.blockX, corner2.blockX),
                maxOf(corner1.blockY, corner2.blockY),
                maxOf(corner1.blockZ, corner2.blockZ)
            )
        )
        return try {
            val targets = Targets.resolve()
                ?: throw IllegalStateException("Residence 已启用, 但反射目标解析失败 (版本不匹配?)")
            for (residence in targets.allResidences()) {
                firstDenied(player, residence, box, needBreak, targets)?.let { return it }
            }
            ProtectionCheck.Allowed
        } catch (throwable: Throwable) {
            // 反射炸了 = 无法确认有没有领地, 按 fail-closed 拒绝 (与 Dominion 钩子同一处理)
            warning("[PlayerWorldEdit] Residence 领地校验失败: $throwable")
            ProtectionCheck.Failed(throwable)
        }
    }

    /**
     * 这个领地 (含其子领地) 里有没有拒绝: 返回第一个拒绝的结果, 全放行返回 null
     *
     * 相交预筛在 [Targets.intersects] 里做, 而且**子领地也要过筛** —— 子领地不一定落在操作框里,
     * 不筛的话会拦下本来碰不到它的操作。
     */
    private fun firstDenied(
        player: Player,
        residence: Any,
        box: EditBox,
        needBreak: Boolean,
        targets: Targets
    ): ProtectionCheck.Denied? {
        if (targets.intersects(residence, box) && !isAllowed(player, residence, needBreak, targets)) {
            return ProtectionCheck.Denied(name, targets.nameOf(residence))
        }
        for (subzone in targets.subzonesOf(residence)) {
            firstDenied(player, subzone, box, needBreak, targets)?.let { return it }
        }
        return null
    }

    /**
     * 与 Residence 自己一致: place / destroy 没设就退回 build 的值, 而 build 没设算 true
     */
    private fun isAllowed(player: Player, residence: Any, needBreak: Boolean, targets: Targets): Boolean {
        val permissions = targets.getPermissions.invoke(residence)
            ?: throw IllegalStateException("ClaimedResidence.getPermissions() 返回 null")
        val buildAllowed = targets.playerHas.invoke(permissions, player, targets.buildFlag, true) as Boolean
        return if (needBreak) {
            targets.playerHas.invoke(permissions, player, targets.destroyFlag, buildAllowed) as Boolean
        } else {
            targets.playerHas.invoke(permissions, player, targets.placeFlag, buildAllowed) as Boolean
        }
    }

    /** 操作框 (方块坐标闭区间) + 世界名 —— 相交预筛与旗查询共用同一份口径 */
    private class EditBox(val worldName: String, val min: IntArray, val max: IntArray)

    /**
     * 反射目标 (类 / 方法 / 枚举常量)
     *
     * 全部 lazy 解析一次并缓存 —— 每次查询都做一遍 `getMethod` 太浪费, 而解析结果在同一个服务端
     * 生命周期里不会变。解析失败缓存成 null, 由 [check] 转成 fail-closed 的 Failed。
     *
     * 目标清单 (依据: 官方 release `Residence6.0.0.1.jar` 的 javap 实测, 见报告):
     * | 目标 | 签名 |
     * |---|---|
     * | `com.bekvon.bukkit.residence.Residence` | `static Residence getInstance()` |
     * | `Residence` | `ResidenceManager getResidenceManager()` |
     * | `ResidenceManager` | `Map<String, ClaimedResidence> getResidences()` |
     * | `ClaimedResidence` | `CuboidArea[] getAreaArray()` / `List<ClaimedResidence> getSubzones()` / `ResidencePermissions getPermissions()` / `String getName()` |
     * | `CuboidArea` | `Location getLowLocation()` / `Location getHighLocation()` / `String getWorldName()` |
     * | `FlagPermissions` (ResidencePermissions 继承) | `boolean playerHas(Player, Flags, boolean)` |
     * | `Flags` 枚举 | 静态常量 `build` / `place` / `destroy` |
     *
     * 注意与任务书的出入 (以 javap 实测为准): 任务书写的是 `ClaimedResidence.getArea()` 与
     * `com.bekvon.bukkit.residence.protection.Area`, 实际 **没有** 无参 `getArea()`, 类名也是
     * `CuboidArea`; 一个领地可以有多块区域, 所以用 `getAreaArray()` 逐块比。
     */
    private class Targets(
        val residenceManager: Any,
        val getResidences: Method,
        val getSubzones: Method,
        val getAreaArray: Method,
        val getLowLocation: Method,
        val getHighLocation: Method,
        val getWorldName: Method,
        val getPermissions: Method,
        val getName: Method,
        val playerHas: Method,
        val buildFlag: Any,
        val placeFlag: Any,
        val destroyFlag: Any
    ) {

        /** 全部主领地 (子领地在父领地的 subzones 里, 由 [subzonesOf] 递归覆盖) */
        fun allResidences(): List<Any> {
            val map = getResidences.invoke(residenceManager) as? Map<*, *>
                ?: throw IllegalStateException("ResidenceManager.getResidences() 返回 null")
            return map.values.filterNotNull()
        }

        /** 子领地 (没有子领地时返回空表) */
        fun subzonesOf(residence: Any): List<Any> {
            val subzones = getSubzones.invoke(residence) as? List<*> ?: return emptyList()
            return subzones.filterNotNull()
        }

        /**
         * 领地与操作框是否相交
         *
         * 逐块 `CuboidArea` 比: 一个领地可以有多块区域 (getAreaArray), 每块带自己的世界名, 跨世界的
         * 同名区域不能参与比较。取不到区域 (返回 null) 时按"相交"处理 —— 宁可多查一次旗, 也不能因为
         * 反射拿不到区域就把保护漏过去。
         */
        fun intersects(residence: Any, box: EditBox): Boolean {
            val areas = getAreaArray.invoke(residence) as? Array<*> ?: return true
            for (area in areas) {
                if (area == null) continue
                if (getWorldName.invoke(area) != box.worldName) continue
                val low = getLowLocation.invoke(area) as? Location ?: return true
                val high = getHighLocation.invoke(area) as? Location ?: return true
                if (overlaps(low, high, box)) return true
            }
            return false
        }

        fun nameOf(residence: Any): String = getName.invoke(residence) as? String ?: "?"

        companion object {

            private val resolved: Targets? by lazy { runCatching { resolveTargets() }.getOrNull() }

            fun resolve(): Targets? = resolved
        }
    }

    /**
     * 两个方块坐标闭区间是否相交 (**贴边也算相交**)
     *
     * 与 Dominion 钩子的 `<=` / `>=` 口径一致: 紧贴领地边界的选区也会被拦, 方向是"更严"而不是漏。
     */
    private fun overlaps(low: Location, high: Location, box: EditBox): Boolean {
        val minX = minOf(low.blockX, high.blockX)
        val minY = minOf(low.blockY, high.blockY)
        val minZ = minOf(low.blockZ, high.blockZ)
        val maxX = maxOf(low.blockX, high.blockX)
        val maxY = maxOf(low.blockY, high.blockY)
        val maxZ = maxOf(low.blockZ, high.blockZ)
        return minX <= box.max[0] && maxX >= box.min[0] &&
            minY <= box.max[1] && maxY >= box.min[1] &&
            minZ <= box.max[2] && maxZ >= box.min[2]
    }

    /**
     * 解析一次反射目标; 任何一环缺失都抛异常 (由 [Targets.resolved] 吞掉变成 null)
     *
     * 用 `getMethod` 而不是 `getDeclaredMethod`: `playerHas` 声明在父类 `FlagPermissions` 上,
     * 但 `getMethod` 一样能找到继承来的 public 方法, 而且以后 Residence 把它挪回子类也不会坏。
     */
    private fun resolveTargets(): Targets {
        val residenceClass = Class.forName("com.bekvon.bukkit.residence.Residence")
        val managerClass = Class.forName("com.bekvon.bukkit.residence.protection.ResidenceManager")
        val claimedClass = Class.forName("com.bekvon.bukkit.residence.protection.ClaimedResidence")
        val areaClass = Class.forName("com.bekvon.bukkit.residence.protection.CuboidArea")
        val permissionsClass = Class.forName("com.bekvon.bukkit.residence.protection.FlagPermissions")
        val flagsClass = Class.forName("com.bekvon.bukkit.residence.containers.Flags")

        val instance = residenceClass.getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("Residence.getInstance() 返回 null")
        val residenceManager = residenceClass.getMethod("getResidenceManager").invoke(instance)
            ?: throw IllegalStateException("Residence.getResidenceManager() 返回 null")
        return Targets(
            residenceManager = residenceManager,
            getResidences = managerClass.getMethod("getResidences"),
            getSubzones = claimedClass.getMethod("getSubzones"),
            getAreaArray = claimedClass.getMethod("getAreaArray"),
            getLowLocation = areaClass.getMethod("getLowLocation"),
            getHighLocation = areaClass.getMethod("getHighLocation"),
            getWorldName = areaClass.getMethod("getWorldName"),
            getPermissions = claimedClass.getMethod("getPermissions"),
            getName = claimedClass.getMethod("getName"),
            playerHas = permissionsClass.getMethod(
                "playerHas",
                Player::class.java,
                flagsClass,
                Boolean::class.javaPrimitiveType
            ),
            buildFlag = flagsClass.getField("build").get(null),
            placeFlag = flagsClass.getField("place").get(null),
            destroyFlag = flagsClass.getField("destroy").get(null)
        )
    }
}

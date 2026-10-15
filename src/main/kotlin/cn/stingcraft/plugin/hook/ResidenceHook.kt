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
 * **父子语义: 按"有效空间范围"判定** (R7)。Residence 自己的解析是"取最具体的那个领地":
 * `ResidenceManager.getByLoc` 的字节码在找到主领地后调 `ClaimedResidence.getSubzoneByLoc` 并**优先
 * 返回子领地**; 旗的继承则由 `FlagPermissions.has(Flags, def, useParent)` 处理 —— 本领地显式设过就用
 * 自己的, 没设才回退 `parent` (子领地的 permissions 链到父领地)。所以正确做法是: 每个领地只对
 * **它独占的那部分空间**负责 (= 自己的各块区域 ∩ 操作框, 再挖掉所有子领地覆盖的部分), 子领地各自对
 * 自己独占的部分负责。原来"父领地相交且拒绝就立刻返回"会把"完全落在许可子领地里"的操作也拒掉
 * (2026-10-14 的 R7), 而只交换父子顺序又会让父领地的裸露部分失去检查。
 *
 * 代价是 O(领地数) 次反射调用 (与 Dominion 的 `allDominions` 同量级), 换来的是零盲区。
 *
 * **语义与 Residence 自己的方块放置/破坏检查逐字一致** —— `ResidenceBlockListener` 的字节码是
 * `playerHas(player, Flags.place, playerHas(player, Flags.build, true))` (破坏那一路换 destroy),
 * 所以这里也这么问; 需要破坏与放置两项时**两项都问** (原来 needBreak=true 时只问 destroy, 覆盖类
 * 操作因此漏了放置权限 —— R5)。`build` 的默认值传 **true** 而不是 false: Residence 的领地大多没有
 * 显式设过 `build` 旗, 传 false 会把本来有权限的玩家拦下来 (比 Residence 自己还严)。
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

    override fun check(player: Player, corner1: Location, corner2: Location, requirement: AreaPermission): ProtectionCheck {
        // 没装 / 没启用 Residence 是"没有这层保护", 不是"无法确认"
        if (!isAvailable()) {
            return ProtectionCheck.Allowed
        }
        // Location 的 world 在正常调用路径上不会为 null, 拿不到世界就当这层保护管不到
        val world = corner1.world ?: return ProtectionCheck.Allowed
        val box = ProtectionBox(
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
                firstDenied(player, residence, box, requirement, targets)?.let { return it }
            }
            ProtectionCheck.Allowed
        } catch (throwable: Throwable) {
            // 反射炸了 = 无法确认有没有领地, 按 fail-closed 拒绝 (与 Dominion 钩子同一处理)
            warning("[PlayerWorldEdit] Residence 领地校验失败: $throwable")
            ProtectionCheck.Failed(throwable)
        }
    }

    /**
     * 这个领地**独占的那部分空间**里有没有拒绝: 返回第一个拒绝的结果, 全放行返回 null
     *
     * 独占 = 自己的各块区域与操作框的交集, 挖掉所有子领地覆盖的部分 (见 [subtract])。父领地只对
     * 裸露的那部分负责 —— 完全落在许可子领地里的操作不会被父领地拒掉; 反过来, 父领地的裸露部分
     * 依然照查 (不是简单交换父子顺序)。
     */
    internal fun firstDenied(
        player: Player,
        residence: Any,
        box: ProtectionBox,
        requirement: AreaPermission,
        targets: Targets
    ): ProtectionCheck.Denied? {
        val children = targets.subzonesOf(residence)
        val own = targets.intersections(residence, box)
        if (own.isNotEmpty()) {
            val covered = children.flatMap { targets.intersections(it, box) }
            val effective = subtractBoxes(own, covered)
            if (effective.isNotEmpty() && !isAllowed(player, residence, requirement, targets)) {
                return ProtectionCheck.Denied(name, targets.nameOf(residence))
            }
        }
        for (subzone in children) {
            firstDenied(player, subzone, box, requirement, targets)?.let { return it }
        }
        return null
    }

    /**
     * 与 Residence 自己一致: place / destroy 没设就退回 build 的值, 而 build 没设算 true
     *
     * [AreaPermission] 的两个维度**分别**问: 需要放置问 place, 需要破坏问 destroy, 两项都要就两项都问
     * (原来 needBreak=true 时只问 destroy —— R5)。
     */
    private fun isAllowed(player: Player, residence: Any, requirement: AreaPermission, targets: Targets): Boolean {
        val permissions = targets.getPermissions.invoke(residence)
            ?: throw IllegalStateException("ClaimedResidence.getPermissions() 返回 null")
        val buildAllowed = targets.playerHas.invoke(permissions, player, targets.buildFlag, true) as Boolean
        if (requirement.needsPlace &&
            !(targets.playerHas.invoke(permissions, player, targets.placeFlag, buildAllowed) as Boolean)
        ) {
            return false
        }
        if (requirement.needsBreak &&
            !(targets.playerHas.invoke(permissions, player, targets.destroyFlag, buildAllowed) as Boolean)
        ) {
            return false
        }
        return true
    }


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
    internal class Targets(
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
            return map.values.map { requireNotNull(it) { "Null Residence entry" } }
        }

        /** 子领地 (没有子领地时返回空表) */
        fun subzonesOf(residence: Any): List<Any> {
            val subzones = getSubzones.invoke(residence) as? List<*>
                ?: throw IllegalStateException("Unreadable Residence subzones")
            return subzones.map { requireNotNull(it) { "Null Residence subzone" } }
        }

        /**
         * 这个领地各块区域与操作框的**交集** (跨世界的区域跳过; 不相交的也不返回)
         *
         * 逐块 `CuboidArea` 比: 一个领地可以有多块区域 (getAreaArray), 每块带自己的世界名, 跨世界的
         * 同名区域不能参与比较。未知几何必须抛错, 不可当成完整覆盖从父区扣除。
         *
         * 返回交集而不是布尔值: 父领地只对"自己独占的那部分"负责, 需要用它算盒减法 (R7)。
         */
        fun intersections(residence: Any, box: ProtectionBox): List<ProtectionBox> {
            val areas = getAreaArray.invoke(residence) as? Array<*>
                ?: throw IllegalStateException("Unreadable Residence geometry")
            require(areas.isNotEmpty()) { "Empty Residence geometry" }
            val result = ArrayList<ProtectionBox>(areas.size)
            for (area in areas) {
                requireNotNull(area) { "Null Residence area" }
                val worldName = getWorldName.invoke(area) as? String
                    ?: throw IllegalStateException("Unreadable Residence world name")
                require(worldName.isNotBlank()) { "Blank Residence world name" }
                if (worldName != box.worldName) continue
                val low = getLowLocation.invoke(area) as? Location
                    ?: throw IllegalStateException("Unreadable Residence low corner")
                val high = getHighLocation.invoke(area) as? Location
                    ?: throw IllegalStateException("Unreadable Residence high corner")
                val areaBox = ProtectionBox(
                    box.worldName,
                    intArrayOf(
                        minOf(low.blockX, high.blockX),
                        minOf(low.blockY, high.blockY),
                        minOf(low.blockZ, high.blockZ)
                    ),
                    intArrayOf(
                        maxOf(low.blockX, high.blockX),
                        maxOf(low.blockY, high.blockY),
                        maxOf(low.blockZ, high.blockZ)
                    )
                )
                areaBox.intersect(box)?.let { result += it }
            }
            return result
        }

        fun nameOf(residence: Any): String = getName.invoke(residence) as? String ?: "?"

        companion object {

            private val resolved: Targets? by lazy { runCatching { resolveTargets() }.getOrNull() }

            fun resolve(): Targets? = resolved
        }
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

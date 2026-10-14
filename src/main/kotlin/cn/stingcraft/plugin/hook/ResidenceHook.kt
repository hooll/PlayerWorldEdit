package cn.stingcraft.plugin.hook

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import java.lang.reflect.Method

/**
 * Residence 的领地保护钩子 (反射实现)
 *
 * Residence 的 API jar 不在公共 Maven 上, 没法像 Dominion 那样 compileOnly, 所以整条调用链走
 * 反射; 反射面很小, 只有 5 个调用点 (见 [Targets]), 而且全部集中在这个文件里。
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
        return try {
            val targets = Targets.resolve()
                ?: throw IllegalStateException("Residence 已启用, 但反射目标解析失败 (版本不匹配?)")
            for (point in protectionProbePoints(corner1, corner2)) {
                // 这一点不在任何领地里 -> 这层保护管不到, 换下一个采样点
                val residence = targets.getByLoc.invoke(targets.residenceManager, point) ?: continue
                val permissions = targets.getPermissions.invoke(residence)
                    ?: throw IllegalStateException("ClaimedResidence.getPermissions() 返回 null")
                // 与 Residence 自己一致: place/destroy 没设就退回 build 的值, 而 build 没设算 true
                val buildAllowed = targets.playerHas.invoke(permissions, player, targets.buildFlag, true) as Boolean
                val allowed = if (needBreak) {
                    targets.playerHas.invoke(permissions, player, targets.destroyFlag, buildAllowed) as Boolean
                } else {
                    targets.playerHas.invoke(permissions, player, targets.placeFlag, buildAllowed) as Boolean
                }
                if (!allowed) {
                    val residenceName = targets.getName.invoke(residence) as? String ?: point.toString()
                    return ProtectionCheck.Denied(name, residenceName)
                }
            }
            ProtectionCheck.Allowed
        } catch (throwable: Throwable) {
            // 反射炸了 = 无法确认有没有领地, 按 fail-closed 拒绝 (与 Dominion 钩子同一处理)
            warning("[PlayerWorldEdit] Residence 领地校验失败: $throwable")
            ProtectionCheck.Failed(throwable)
        }
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
     * | `ResidenceManager` | `ClaimedResidence getByLoc(org.bukkit.Location)` |
     * | `ClaimedResidence` | `ResidencePermissions getPermissions()` / `String getName()` |
     * | `FlagPermissions` (ResidencePermissions 继承) | `boolean playerHas(Player, Flags, boolean)` |
     * | `Flags` 枚举 | 静态常量 `build` / `place` / `destroy` |
     */
    private class Targets(
        val residenceManager: Any,
        val getByLoc: Method,
        val getPermissions: Method,
        val getName: Method,
        val playerHas: Method,
        val buildFlag: Any,
        val placeFlag: Any,
        val destroyFlag: Any
    ) {

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
        val permissionsClass = Class.forName("com.bekvon.bukkit.residence.protection.FlagPermissions")
        val flagsClass = Class.forName("com.bekvon.bukkit.residence.containers.Flags")

        val instance = residenceClass.getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("Residence.getInstance() 返回 null")
        val residenceManager = residenceClass.getMethod("getResidenceManager").invoke(instance)
            ?: throw IllegalStateException("Residence.getResidenceManager() 返回 null")
        return Targets(
            residenceManager = residenceManager,
            getByLoc = managerClass.getMethod("getByLoc", Location::class.java),
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

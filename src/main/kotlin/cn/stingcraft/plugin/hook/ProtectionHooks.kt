package cn.stingcraft.plugin.hook

import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * 领地保护的收口: 装了哪几层就逐层问, 全部放行才放行
 *
 * 顺序固定 (Dominion → Residence → WorldGuard), 不做成配置项: 拒绝提示里已经带上是谁拦的,
 * 玩家看得懂; 再加一个"顺序可配"只会让"到底谁在拦"更难查。
 */
object ProtectionHooks {

    private val hooks: List<RegionProtectionHook> = listOf(DominionHook, ResidenceHook, WorldGuardHook)

    /**
     * 逐个问过去; 第一个拒绝/无法确认的结果直接返回
     *
     * 返回 [ProtectionCheck.Denied] 时 [ProtectionCheck.Denied.source] 就是拦下来的那个插件 ——
     * 提示里带上它, 玩家才知道该去找哪个插件要权限。
     */
    fun checkAreaEdit(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): ProtectionCheck {
        for (hook in hooks) {
            // 没装 = 没有这层保护, 不是"无法确认"
            if (!hook.isAvailable()) continue
            when (val result = hook.check(player, corner1, corner2, needBreak)) {
                is ProtectionCheck.Allowed -> continue
                // 被拒 / 无法确认都到此为止: 后面的钩子再问也不会改变"不放行"的结论
                is ProtectionCheck.Denied -> return result
                is ProtectionCheck.Failed -> return result
            }
        }
        return ProtectionCheck.Allowed
    }
}

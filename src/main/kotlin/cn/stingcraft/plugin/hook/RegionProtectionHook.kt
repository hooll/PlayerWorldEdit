package cn.stingcraft.plugin.hook

import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * 领地保护的统一结果
 *
 * 三态而不是"有权限 / 没权限": 必须能把"确认没有保护"和"查询出错、无法确认"分开 —— 后者要按
 * fail-closed 拒绝, 不能因为某个保护插件内部出错就当成没领地放行 (任务 9C 的教训)。
 */
sealed interface ProtectionCheck {

    /** 确认可以编辑 */
    object Allowed : ProtectionCheck

    /**
     * 被某一层保护拒绝
     *
     * @param source 保护插件名 (提示里要告诉玩家是哪一个插件拦的)
     * @param detail 该插件给的原因 (领地 / 区域名等)
     */
    data class Denied(val source: String, val detail: String) : ProtectionCheck

    /** 查询过程本身出错, 无法确认 -> 调用方必须拒绝 */
    data class Failed(val cause: Throwable) : ProtectionCheck
}

/**
 * 一个领地保护插件的接入点 (Dominion / Residence / WorldGuard)
 *
 * 每个实现只负责"怎么查"这一件事; "全部放行才放行、任何 Failed 都 fail-closed"的收口逻辑在
 * [ProtectionHooks] 里只有一份 —— 三个钩子不许各写一遍。
 */
interface RegionProtectionHook {

    /** 保护插件名 (提示文案与控制台日志用) */
    val name: String

    /**
     * 这层保护现在生效吗 (插件装了并启用了)
     *
     * 必须每次现查, 不能用 lazy: 保护插件比本插件晚启用时, 第一次求值会把结果永久钉死成
     * "没装", 之后这层保护就再也不生效了。
     */
    fun isAvailable(): Boolean

    /**
     * 玩家能不能编辑这个范围内的所有领地
     *
     * @param needBreak 是否需要破坏权限 (替换 / 清空 / 移动这类操作要)
     */
    fun check(player: Player, corner1: Location, corner2: Location, needBreak: Boolean): ProtectionCheck
}

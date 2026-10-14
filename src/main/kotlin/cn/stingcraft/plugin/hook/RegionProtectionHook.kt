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
 * 一次操作需要哪几项权限
 *
 * 收口原来只收一个 `needBreak: Boolean`, 而"覆盖"这种操作既破坏又放置, 用一个布尔表达不出来 ——
 * Residence 钩子在 needBreak=true 时只查 destroy、WorldGuard 只查 BLOCK_BREAK, 两边都没查放置,
 * 于是"只许放置、不许破坏"的玩家反而能清空/覆盖别人的建筑 (2026-10-14 的 R5)。这里把权限拆成
 * 放置与破坏两个独立维度, 每个操作按"真正会做什么"声明, 钩子按维度分别查原生 flag:
 *
 * | 操作 | 需要 | 理由 |
 * |---|---|---|
 * | set 非空气 / replace / wall / cyl / hcyl 非空气 | [PLACE_AND_BREAK] | 会覆盖已有方块 |
 * | set 空气 / 形状清空 (空气) | [BREAK] | 只清不填 |
 * | contract | [BREAK] | 只把被砍掉的层清成空气 |
 * | expand | [PLACE_AND_BREAK] | 复制到新层 = 覆盖新层原有方块 |
 * | move / paste | [PLACE_AND_BREAK] | 清源区 + 覆盖目标区 |
 * | copy | [READ] | 不写方块, 见 [READ] 的说明 |
 *
 * @param place 需要放置权限
 * @param breakBlock 需要破坏权限
 */
enum class AreaPermission(private val place: Boolean, private val breakBlock: Boolean) {

    /**
     * 只读 (copy): 三个保护插件都没有"读取"这一档原生 flag (Dominion 的 PriFlag 里只有 PLACE /
     * BREAK_BLOCK 这类写入 flag, WorldGuard 的 BUILD/PLACE/BREAK 同理), 所以按**放置同档**要求。
     *
     * 为什么不放行: copy 虽然不改世界, 但放行等于"只许放置的玩家能把别人领地里的建筑复制走",
     * 那是放松保护 —— 这一条与改动前一致, 不改。
     */
    READ(true, false),

    /** 只放 (不会覆盖任何已有方块) */
    PLACE(true, false),

    /** 只破坏 (清成空气 / 搬走) */
    BREAK(false, true),

    /** 覆盖: 既破坏原有方块又放上新的 */
    PLACE_AND_BREAK(true, true);

    /** 需要放置权限 */
    val needsPlace: Boolean get() = place

    /** 需要破坏权限 */
    val needsBreak: Boolean get() = breakBlock
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
     * @param requirement 这次操作需要哪几项权限 (见 [AreaPermission])
     */
    fun check(player: Player, corner1: Location, corner2: Location, requirement: AreaPermission): ProtectionCheck
}

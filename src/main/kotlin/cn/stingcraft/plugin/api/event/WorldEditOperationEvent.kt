package cn.stingcraft.plugin.api.event

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * 操作执行**前**触发 (可取消)
 *
 * 在扣料之前、玩家线程上同步触发; 取消 = 整条操作不执行 (零写入、零扣料、不记撤销), 走各操作
 * 自己的"没执行"提示路径。领地保护钩子的校验在事件**之前**做, 所以事件被取消时玩家不会先看到
 * 一条"没有权限"。
 *
 * [affectedRegion] 是这次操作**会改动的包围盒** (两个对角方块坐标), 不是精确到方块的集合:
 * 例如 replace 只会改选区里匹配的那些方块, 但包围盒仍是整个选区。需要精确范围请按 [operation]
 * 自行判断。
 */
class WorldEditOperationEvent(
    val player: Player,
    val operation: OperationType,
    val affectedRegion: Pair<Location, Location>
) : Event(), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {

        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

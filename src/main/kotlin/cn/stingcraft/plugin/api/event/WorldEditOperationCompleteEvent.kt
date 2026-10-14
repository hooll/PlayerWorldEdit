package cn.stingcraft.plugin.api.event

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * 操作成功收尾时触发 (不可取消)
 *
 * 在玩家线程上同步触发, 每次操作只发一次 (操作失败/被取消都不会发)。字段是**实际写入**的统计,
 * 不是预扣的量: 部分区块跑失败时, 这里报的是真写下去的那部分 (与退费口径一致)。
 */
class WorldEditOperationCompleteEvent(
    val player: Player,
    val operation: OperationType,
    /**
     * 实际写下去的方块数
     *
     * 含写空气 —— 清空也是改动 (与撤销记录的 amount 同一口径)。同值重写的方块也算在内。
     */
    val changedBlocks: Int,
    /**
     * 实际写入的方块按材料统计 (含 [Material.AIR]: 清空位置用的空气也是改动)
     */
    val materials: Map<Material, Int>
) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {

        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

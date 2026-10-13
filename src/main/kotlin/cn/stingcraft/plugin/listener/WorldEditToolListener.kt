package cn.stingcraft.plugin.listener

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.inventory.WorldEditOpInventory
import cn.stingcraft.plugin.manager.UndoManager
import cn.stingcraft.plugin.until.isToolItem
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.pluginId
import taboolib.platform.util.isAir
import taboolib.platform.util.sendLang

object WorldEditToolListener {

    @SubscribeEvent
    fun onPlayerInteract(e: PlayerInteractEvent){
        if (e.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val handItem = e.player.inventory.itemInMainHand
        if (handItem.isAir) return
        if (!handItem.isToolItem()) return
        e.isCancelled = true
        if (e.player.isSneaking && (e.action == Action.RIGHT_CLICK_BLOCK || e.action == Action.RIGHT_CLICK_AIR)) {
            WorldEditOpInventory.open(e.player)
            return
        }
        when(e.action){
            Action.LEFT_CLICK_BLOCK -> {
                val loc = e.clickedBlock?.boundingBox?.center?.toLocation(e.player.world) ?:return
                // 另一个点若已选, 必须与当前世界一致
                val pos2 = PlayerWorldEdit.worldEditManager.getPlayerPos2(e.player)
                if (pos2 != null && pos2.world?.name != e.player.world.name) {
                    e.player.sendLang("message-different-world", pluginId to "pluginId")
                    return
                }
                PlayerWorldEdit.worldEditManager.setPlayerPos1(e.player,loc)
                e.player.sendLang("message-region-select-pos1",
                    pluginId to "pluginId",
                    loc.x to "x",
                    loc.y to "y",
                    loc.z to "z",
                    // 区域未选完整时数量记 0, 消息照常发送 (原来会直接不发)
                    (if (PlayerWorldEdit.worldEditManager.hasRegion(e.player))
                        PlayerWorldEdit.worldEditManager.getCountInRegion(e.player) else 0) to "amount"
                )
            }
            Action.RIGHT_CLICK_BLOCK -> {
                val loc = e.clickedBlock?.boundingBox?.center?.toLocation(e.player.world) ?:return
                // 点1未选时允许先选点2; 已选则必须同一世界
                val pos1 = PlayerWorldEdit.worldEditManager.getPlayerPos1(e.player)
                if (pos1 != null && pos1.world?.name != e.player.world.name) {
                    e.player.sendLang("message-different-world", pluginId to "pluginId")
                    return
                }
                PlayerWorldEdit.worldEditManager.setPlayerPos2(e.player,loc)
                e.player.sendLang("message-region-select-pos2",
                    pluginId to "pluginId",
                    loc.x to "x",
                    loc.y to "y",
                    loc.z to "z",
                    // 区域未选完整时数量记 0, 消息照常发送 (原来会直接不发)
                    (if (PlayerWorldEdit.worldEditManager.hasRegion(e.player))
                        PlayerWorldEdit.worldEditManager.getCountInRegion(e.player) else 0) to "amount"
                )
            }
            else -> { return }
        }
    }

    @SubscribeEvent
    fun onPlayerQuit(e: PlayerQuitEvent) {
        PlayerWorldEdit.worldEditManager.clearPlayerPos(e.player)
        // 撤销队列按 UUID 只增不删, 退出时清掉 (记录本身在磁盘上, 下次登录会重建)
        UndoManager.forget(e.player.uniqueId)
    }

}

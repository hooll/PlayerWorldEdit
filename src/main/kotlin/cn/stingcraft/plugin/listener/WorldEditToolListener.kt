package cn.stingcraft.plugin.listener

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import taboolib.common.function.throttle
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.pluginId
import taboolib.module.nms.getItemTag
import taboolib.platform.util.isAir
import taboolib.platform.util.sendLang

object WorldEditToolListener {

    @SubscribeEvent
    fun onPlayerInteract(e: PlayerInteractEvent){
        if (e.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val handItem = e.player.inventory.itemInMainHand
        if (handItem.isAir) return
        if (handItem.getItemTag().getDeep("playerworldedit")?.asString() != "true") return
        when(e.action){
            Action.LEFT_CLICK_BLOCK -> {
                val loc = e.clickedBlock?.boundingBox?.center?.toLocation(e.player.world) ?:return
                PlayerWorldEdit.worldEditManager.setPlayerPos1(e.player,loc)
                e.player.sendLang("message-region-select-pos1",
                    pluginId to "pluginId",
                    loc.x to "x",
                    loc.y to "y",
                    loc.z to "z"
                    )
            }
            Action.RIGHT_CLICK_BLOCK -> {
                val loc = e.clickedBlock?.boundingBox?.center?.toLocation(e.player.world) ?:return
                if (PlayerWorldEdit.worldEditManager.getPlayerPos1(e.player)?.world?.name != e.player.world.name){
                    e.player.sendMessage("两个点不在同一世界！")
                    return
                }
                PlayerWorldEdit.worldEditManager.setPlayerPos2(e.player,loc)
                e.player.sendLang("message-region-select-pos2",
                    pluginId to "pluginId",
                    loc.x to "x",
                    loc.y to "y",
                    loc.z to "z"
                )
            }
            else -> { return }
        }
        e.isCancelled = true
    }

}
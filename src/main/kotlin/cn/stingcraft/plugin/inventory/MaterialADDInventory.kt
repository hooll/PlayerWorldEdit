package cn.stingcraft.plugin.inventory

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.submitAsync
import taboolib.module.chat.colored
import taboolib.module.ui.buildMenu
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.impl.ChestImpl
import taboolib.platform.util.*

object MaterialADDInventory {


    private val packManager = PlayerWorldEdit.packManager

    fun open(player: Player) {
        player.openMenu(build(player))
    }

    private fun build(player: Player):Inventory{
        val list = mutableListOf<MaterialEntry>()
        return buildMenu<ChestImpl>("&f添加材料".colored()) {
            handLocked(false)
            rows(6)
            onClose {
                it.inventory.contents.forEach { itemstack ->
                    if (itemstack.isAir()) return@forEach
                    if (itemstack.hasName() || itemstack.hasLore()) {
                        player.giveItem(itemstack)
                        return@forEach
                    }
                    if (!itemstack.type.isBlock){
                        player.giveItem(itemstack)
                        return@forEach
                    }
                    val material = itemstack.type
                    val amount = itemstack.amount
                    list.add(MaterialEntry(material, amount))

                }
                submitAsync { packManager.addMaterials(player, list) }
                player.sendLang("message-materialpack-add-susses", pluginId to "pluginId")
            }
        }
    }

}
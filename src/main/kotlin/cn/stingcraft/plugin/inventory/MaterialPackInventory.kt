package cn.stingcraft.plugin.inventory

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
import cn.stingcraft.plugin.manager.MaterialPackManager
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import taboolib.library.xseries.XMaterial
import taboolib.module.chat.colored
import taboolib.module.ui.buildMenu
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.impl.PageableChestImpl
import taboolib.platform.util.Slots
import taboolib.platform.util.buildItem
import taboolib.platform.util.giveItem

object MaterialPackInventory {

    private val packManager = PlayerWorldEdit.packManager
    private val settingManager = PlayerWorldEdit.settingManager

    fun open(player: Player) {
        val materialPack = PlayerWorldEdit.packManager.getMaterialPack(player)
        player.openMenu(build(player,materialPack))
    }



    private fun build(player: Player,  materialPack: MaterialPack):Inventory{
        return buildMenu<PageableChestImpl<MaterialEntry>>(
            settingManager.getMaterialPackGuiSettings().getString("title")?.colored() ?:"&f材料背包 &4| 切勿放置其他物品 仅放材料") {
            rows(6)
            slots(Slots.CENTER)
            elements { materialPack.materials.toList() }
            onGenerate { _, element, _, _ ->
                return@onGenerate buildItem(element.material){
                    lore +=  settingManager.getMaterialPackGuiSettings().getString("lore.amount")
                        ?.replace("%amount%",element.amount.toString()) ?: "&7| 数量: &e${element.amount}"
                    lore += settingManager.getMaterialPackGuiSettings().getString("lore.take-1") ?: "&7| 左键 取出 x1"
                    if (element.amount >= 10)
                    lore += settingManager.getMaterialPackGuiSettings().getString("lore.take-10") ?: "&7| 右键 取出 x10"
                    if (element.amount >= 64)
                    lore += settingManager.getMaterialPackGuiSettings().getString("lore.take-64") ?: "&7| shift + 右键 取出 x64"
                    colored()
                }
            }

            onClick { event, element ->
                when(event.clickEvent().click){
                    ClickType.LEFT -> {
                        if (packManager.hasEnoughMaterial(player, element.material, 1)){
                            packManager.removeMaterial(player, element.material, 1)
                            player.giveItem(buildItem(element.material),1)
                            open(player)
                        }
                    }
                    ClickType.RIGHT -> {
                        if (packManager.hasEnoughMaterial(player, element.material, 10)){
                            packManager.removeMaterial(player, element.material, 10)
                            player.giveItem(buildItem(element.material),10)
                            open(player)
                        }
                    }
                    ClickType.SHIFT_RIGHT -> {
                        if (packManager.hasEnoughMaterial(player, element.material, 64)){
                            packManager.removeMaterial(player, element.material, 64)
                            player.giveItem(buildItem(element.material),64)
                            open(player)
                        }
                    }
                    else -> {
                        return@onClick
                    }

                }
            }

            setNextPage(47) { page, hasNextPage ->
                if (hasNextPage) {
                    buildItem(XMaterial.SPECTRAL_ARROW) {
                        name = "§f| 下一页"
                    }
                } else {
                    buildItem(XMaterial.ARROW) {
                        name = "§7| 下一页"
                    }
                }
            }
            setPreviousPage(51) { page, hasPreviousPage ->
                if (hasPreviousPage) {
                    buildItem(XMaterial.SPECTRAL_ARROW) {
                        name = "§f| 上一页"
                    }
                } else {
                    buildItem(XMaterial.ARROW) {
                        name = "§7| 上一页"
                    }
                }
            }

            set(49, buildItem(XMaterial.CHEST){
                name = "&f添加材料"
                colored()
            })

            onClick(49){
                MaterialADDInventory.open(player)
            }

        }
    }

}
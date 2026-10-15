package cn.stingcraft.plugin.inventory

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
import org.bukkit.inventory.ItemStack
import cn.stingcraft.plugin.manager.MaterialPackManager
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import taboolib.common.platform.function.pluginId
import taboolib.library.xseries.XMaterial
import taboolib.module.chat.colored
import taboolib.module.ui.buildMenu
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.impl.PageableChestImpl
import taboolib.platform.util.Slots
import taboolib.platform.util.buildItem
import taboolib.platform.util.giveItem
import taboolib.platform.util.sendLang

object MaterialPackInventory {

    private val packManager = PlayerWorldEdit.packManager
    private val settingManager = PlayerWorldEdit.settingManager

    fun open(player: Player) {
        val materialPack = PlayerWorldEdit.packManager.getMaterialPack(player)
        player.openMenu(build(player,materialPack))
    }



    /** 材料单次同步提交后才发物品。两份存储仍无共同事务, 保留阶段供人工核对。 */
    private fun takeDurably(player: Player, element: MaterialEntry, amount: Int) {
        val item = ItemStack(element.material, amount)
        val journal = runCatching { MaterialTradeJournal.prepare(player.uniqueId, "WITHDRAW", listOf(item)) }
            .getOrElse {
                player.sendLang("message-materialpack-take-failed", pluginId to "pluginId")
                return
            }
        when (packManager.tryConsumeDurably(player, element.material, amount)) {
            MaterialPackManager.TradeResult.OK -> {
                journal.mark("COMMITTED")
                player.giveItem(item)
                journal.mark("DELIVERED")
                open(player)
            }
            MaterialPackManager.TradeResult.NOT_ENOUGH -> journal.mark("NOT_ENOUGH")
            MaterialPackManager.TradeResult.PERSIST_FAILED -> {
                journal.mark("NOT_COMMITTED")
                player.sendLang("message-materialpack-take-failed", pluginId to "pluginId")
            }
            MaterialPackManager.TradeResult.PERSIST_UNKNOWN -> {
                journal.mark("UNKNOWN")
                player.sendLang("message-materialpack-trade-unknown", pluginId to "pluginId")
            }
        }
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
                val amount = when (event.clickEvent().click) {
                    ClickType.LEFT -> 1
                    ClickType.RIGHT -> 10
                    ClickType.SHIFT_RIGHT -> 64
                    else -> return@onClick
                }
                takeDurably(player, element, amount)
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

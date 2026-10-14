package cn.stingcraft.plugin.inventory

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
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



    /**
     * 取物: 扣账**先落库**, 落库成功才发物品
     *
     * 顺序反过来 (先发物品再落库) 崩溃时会变成"物品已经拿到、库里还记着材料" = 重复提现; 现在崩在
     * 中间只会少一件 (方向安全)。落库失败就把这次扣的撤回, 并明确告诉玩家失败 —— 改完缓存就当成功
     * 正是 R10 要修的 (2026-10-14)。
     */
    private fun takeDurably(player: Player, element: MaterialEntry, amount: Int) {
        when (packManager.tryConsumeDurably(player, element.material, amount)) {
            MaterialPackManager.TradeResult.OK -> {
                player.giveItem(buildItem(element.material), amount)
                open(player)
            }
            MaterialPackManager.TradeResult.NOT_ENOUGH -> Unit
            MaterialPackManager.TradeResult.PERSIST_FAILED ->
                player.sendLang("message-materialpack-take-failed", pluginId to "pluginId")
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

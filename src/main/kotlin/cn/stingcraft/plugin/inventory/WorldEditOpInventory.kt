package cn.stingcraft.plugin.inventory

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.until.displayName
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import taboolib.common.platform.function.pluginId
import taboolib.library.xseries.XMaterial
import taboolib.module.chat.colored
import taboolib.module.ui.buildMenu
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.impl.ChestImpl
import taboolib.module.ui.type.impl.PageableChestImpl
import taboolib.platform.util.Slots
import taboolib.platform.util.buildItem
import taboolib.platform.util.sendLang

object WorldEditOpInventory {

    private val worldEditManager = PlayerWorldEdit.worldEditManager

    fun open(player: Player) {
        player.openMenu(build(player))
    }

    private fun build(player: Player): Inventory {
        val hasRegion = worldEditManager.hasRegion(player)
        val size = if (hasRegion) worldEditManager.getCountInRegion(player) else 0
        return buildMenu<ChestImpl>("&f创世神操作 &4| 快捷菜单".colored()) {
            rows(3)
            // 锁定整页点击: 没有注册 onClick 的格子（如信息纸）原本可以把物品拿走
            onClick(true) { }
            set(4, buildItem(XMaterial.PAPER) {
                name = "&f| 选区信息"
                if (hasRegion) {
                    lore += "&7| 已选区域: &e${size} &7个方块"
                } else {
                    lore += "&c| 尚未选择完整区域"
                    lore += "&7| 左键=点1, 右键=点2"
                }
                colored()
            })
            set(10, buildItem(XMaterial.CHEST) {
                name = "&f| 材料背包"
                lore += "&7| 存放与取出填充材料"
                colored()
            }) {
                MaterialPackInventory.open(player)
            }
            set(12, buildItem(XMaterial.GRASS_BLOCK) {
                name = "&f| 设置方块"
                lore += "&7| 用方块填充整个选区"
                colored()
            }) {
                openBlockPicker(player, "&f选择要设置的方块") { material ->
                    player.closeInventory()
                    worldEditManager.trySetArea(player, material)
                }
            }
            set(14, buildItem(XMaterial.ANVIL) {
                name = "&f| 替换方块"
                lore += "&7| 第一步: 选择要替换的原方块"
                colored()
            }) {
                openBlockPicker(player, "&f选择原方块") { from ->
                    openBlockPicker(player, "&f选择新方块") { to ->
                        player.closeInventory()
                        worldEditManager.tryReplaceArea(player, from, to)
                    }
                }
            }
            set(16, buildItem(XMaterial.ARROW) {
                name = "&f| 撤销"
                lore += "&7| 撤销最近一次操作并返还材料"
                colored()
            }) {
                worldEditManager.undo(player, 1) { done ->
                    if (done <= 0) {
                        player.sendLang("message-undo-none", pluginId to "pluginId")
                    } else {
                        player.sendLang("message-undo-susses", pluginId to "pluginId", done to "times")
                    }
                }
            }
        }
    }

    private fun openBlockPicker(player: Player, title: String, callback: (Material) -> Unit) {
        // 空气放第一个, 渲染成屏障物品, 玩家一眼就知道是清空; 其余只列材料背包里已有的方块
        // (没有元素的空格子 TabooLib 不会派发点击, 点了不会有反应)
        val blocks = listOf(Material.AIR) + PlayerWorldEdit.packManager.getMaterialPack(player).materials
            .map { it.material }
            .distinct()
            .filter { it.isBlock && it != Material.AIR }
        player.openMenu(buildMenu<PageableChestImpl<Material>>(title.colored()) {
            rows(6)
            slots(Slots.CENTER)
            elements { blocks }
            onGenerate { _, element, _, _ ->
                if (element == Material.AIR) {
                    return@onGenerate buildItem(XMaterial.BARRIER) {
                        name = "&f| 空气"
                        lore += "&7| 清空选区内的所有方块"
                        colored()
                    }
                }
                return@onGenerate buildItem(element) {
                    name = "&f| ${element.displayName(player)}"
                    colored()
                }
            }
            onClick { _, element ->
                callback(element)
            }
            setNextPage(47) { _, hasNextPage ->
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
            setPreviousPage(51) { _, hasPreviousPage ->
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
        })
    }
}

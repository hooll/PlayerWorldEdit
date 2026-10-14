package cn.stingcraft.plugin.inventory

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
import cn.stingcraft.plugin.manager.MaterialPackManager
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import taboolib.common.platform.function.pluginId
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
                deposit(player, list)
            }
        }
    }

    /**
     * 存入: 同步加进材料包并**立刻落库**, 失败就把物品原样还给玩家
     *
     * 原来是 `submitAsync { addMaterials(...) }` + 无条件报成功: 物品已经从玩家背包里拿走了 (进菜单
     * 时就扣了), 而"加进材料包"是个异步且只改内存的动作 —— 崩在任务执行之前, 物品既不在背包里也不
     * 在库里 (凭空消失); 崩在退出落盘之前同理。现在同步做、做完就落库, 落库失败还能退回去 (R10)。
     *
     * 退回用 [Player.giveItem] 按材料重新发: 这里收的本来就是无名字无 lore 的普通方块 (上面已经
     * 把带名字/非方块的物品原样退回了), 所以重建的物品与原物等价。
     */
    private fun deposit(player: Player, entries: List<MaterialEntry>) {
        if (entries.isEmpty()) return
        when (packManager.addMaterialsDurably(player, entries)) {
            MaterialPackManager.TradeResult.OK ->
                player.sendLang("message-materialpack-add-susses", pluginId to "pluginId")
            MaterialPackManager.TradeResult.PERSIST_FAILED -> {
                entries.forEach { entry ->
                    player.giveItem(buildItem(entry.material) { amount = entry.amount })
                }
                player.sendLang("message-materialpack-add-failed", pluginId to "pluginId")
            }
            // 存入只会成功或落库失败 (NOT_ENOUGH 是取物那条路的结果)
            MaterialPackManager.TradeResult.NOT_ENOUGH -> Unit
        }
    }

}
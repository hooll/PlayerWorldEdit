package cn.stingcraft.plugin.inventory

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.manager.MaterialPackManager
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.concurrent.atomic.AtomicBoolean
import taboolib.common.platform.function.warning
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
        val handled = AtomicBoolean()
        return buildMenu<ChestImpl>("&f添加材料".colored()) {
            handLocked(false)
            rows(6)
            onClose { event ->
                if (!handled.compareAndSet(false, true)) return@onClose
                val originals = event.inventory.contents.filterNotNull().filterNot { it.type.isAir }.map { it.clone() }
                // 完整比较失败也按拒收处理, 不让元数据读取异常吞掉已移出的物品。
                val (accepted, rejected) = originals.partition { item ->
                    runCatching { isPlainMaterial(item) }.getOrDefault(false)
                }
                event.inventory.clear()
                rejected.forEach { player.giveItem(it) }
                deposit(player, accepted)
            }
        }
    }

    /** 与按材质取出的全新物品比较完整等价性, 不以 name/lore 或 hasItemMeta 代替。 */
    internal fun isPlainMaterial(item: ItemStack): Boolean =
        item.type.isBlock && item.isSimilar(ItemStack(item.type))

    private fun deposit(player: Player, originals: List<ItemStack>) {
        if (originals.isEmpty()) return
        val journal = try {
            MaterialTradeJournal.prepare(player.uniqueId, "DEPOSIT", originals)
        } catch (error: Throwable) {
            originals.forEach { player.giveItem(it) }
            player.sendLang("message-materialpack-add-failed", pluginId to "pluginId")
            return
        }
        val entries = originals.map { MaterialEntry(it.type, it.amount) }
        when (packManager.addMaterialsDurably(player, entries)) {
            MaterialPackManager.TradeResult.OK -> {
                journal.mark("COMMITTED")
                player.sendLang("message-materialpack-add-susses", pluginId to "pluginId")
            }
            MaterialPackManager.TradeResult.PERSIST_FAILED, MaterialPackManager.TradeResult.NOT_ENOUGH -> {
                originals.forEach { player.giveItem(it) }
                journal.mark("RETURNED")
                player.sendLang("message-materialpack-add-failed", pluginId to "pluginId")
            }
            MaterialPackManager.TradeResult.PERSIST_UNKNOWN -> {
                journal.mark("UNKNOWN")
                warning("[PlayerWorldEdit] 存入结果不明 (${player.uniqueId}), 原物保留在 material-transactions 记录中, 不自动退物")
                player.sendLang("message-materialpack-trade-unknown", pluginId to "pluginId")
            }
        }
    }
}

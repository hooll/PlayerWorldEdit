package cn.stingcraft.plugin.until

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * 创世神镐的标记 key
 *
 * 用 PDC 而不是 NBT: TabooLib 的 NMSItemTagImpl26 在 Lophine 这类 fork 上调不到
 * CraftItemStack.asCraftMirror, 写标记会抛 NoSuchMethodError
 */
val TOOL_KEY: NamespacedKey = NamespacedKey.fromString("playerworldedit:tool")!!

fun ItemStack.markAsTool() {
    val meta = itemMeta ?: return
    meta.persistentDataContainer.set(TOOL_KEY, PersistentDataType.STRING, "true")
    itemMeta = meta
}

fun ItemStack.isToolItem(): Boolean =
    itemMeta?.persistentDataContainer?.has(TOOL_KEY, PersistentDataType.STRING) == true

/**
 * 物品显示名, 如 DIAMOND_PICKAXE -> Diamond Pickaxe (TNT 这类短词保持大写)
 * 不用 NMS 的 getI18nName: 在部分服务端核心上不可用
 */
fun Material.displayName(): String =
    name.lowercase().split('_').joinToString(" ") { word ->
        if (word.length <= 3) word.uppercase() else word.replaceFirstChar { it.uppercaseChar() }
    }

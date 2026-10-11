package cn.stingcraft.plugin.until

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import taboolib.library.xseries.XMaterial
import taboolib.module.nms.getI18nName

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
 * 解析玩家输入的方块名 (支持 "stone" / "minecraft:stone"), 非法或不是方块时返回 null
 * 不用 XSeries 的 parseToMaterial: 它解析失败会静默退回 STONE
 */
fun parseBlockOrNull(input: String): Material? =
    XMaterial.matchXMaterial(input.lowercase()).orElse(null)?.parseMaterial()?.takeIf { it.isBlock }

/**
 * 物品显示名
 *
 * 优先用 NMS 的 I18nName (原版名称, 如 "Diamond Pickaxe"); 个别服务端核心上 NMS 不可用时
 * 退回本地标题化名称 (DIAMOND_PICKAXE -> Diamond Pickaxe, TNT 这类短词保持大写),
 * 保证任何版本都不会因为拿不到名字而报错
 */
fun Material.displayName(): String =
    runCatching { getI18nName() }.getOrElse {
        name.lowercase().split('_').joinToString(" ") { word ->
            if (word.length <= 3) word.uppercase() else word.replaceFirstChar { it.uppercaseChar() }
        }
    }

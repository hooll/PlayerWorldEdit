package cn.stingcraft.plugin.util

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import taboolib.library.xseries.XMaterial
import taboolib.module.nms.MinecraftLanguage
import taboolib.module.nms.getMinecraftLanguageFile
import java.lang.reflect.Method

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
 * Material 的原版语言键, 如 `block.minecraft.jungle_leaves`
 *
 * 反射取: Translatable 接口是 Paper 1.19.3+ 才给 Material 加的, 编译用的 API 里不一定有
 */
private val translationKeyMethod: Method? by lazy {
    runCatching { Material::class.java.getMethod("getTranslationKey") }.getOrNull()
}

/**
 * 物品显示名, 跟随语言 (不传玩家用默认的 zh_cn, 传了就按该玩家客户端语言)
 *
 * 不用 TabooLib 的 getI18nName: 它拿语言键要经 CraftItemStack/NMS, 在 Lophine 这类 fork 上会抛
 * NoSuchMethodError, 异常被吃掉之后就只剩英文标题化名字了 ("Jungle Leaves")。
 * 这里直接拿 Material 的语言键去查 TabooLib 下好的语言文件, 不碰 NMS。
 */
fun Material.displayName(player: Player? = null): String {
    val key = translationKeyMethod?.let { runCatching { it.invoke(this) as? String }.getOrNull() }
    if (key != null) {
        val file = player?.getMinecraftLanguageFile() ?: MinecraftLanguage.getDefaultLanguageFile()
        val translated = runCatching { file?.get(key) }.getOrNull()
        if (!translated.isNullOrBlank()) {
            return translated
        }
    }
    // 语言文件缺失时的兜底: 用 Material.name (枚举名), DIAMOND_PICKAXE -> Diamond Pickaxe,
    // TNT 这类短词保持大写。这里显式写 this.name —— 上面那个局部变量叫 name 的话,
    // 这一行靠遮蔽才解析对, 以后谁挪一下代码就会悄悄改掉兜底行为。
    return this.name.lowercase().split('_').joinToString(" ") { word ->
        if (word.length <= 3) word.uppercase() else word.replaceFirstChar { it.uppercaseChar() }
    }
}

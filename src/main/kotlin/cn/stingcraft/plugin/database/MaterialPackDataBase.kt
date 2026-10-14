package cn.stingcraft.plugin.database

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.data.MaterialPack
import com.google.gson.Gson
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.LifeCycle
import taboolib.common.io.newFile
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.submitAsync
import taboolib.expansion.getAutoDataContainer
import taboolib.expansion.releasePlayerDataContainer
import taboolib.expansion.setupDataContainer
import taboolib.expansion.setupPlayerDatabase
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object MaterialPackDataBase {

    /** 数据容器 / 数据库里存材料包的那一列 */
    private const val MATERIAL_PACK_KEY = "materialPack"

    @Awake(LifeCycle.ENABLE)
    fun init(){
        setupPlayerDatabase(newFile(getDataFolder(), "data.db"))
    }

    @SubscribeEvent
    private fun setup(e:PlayerJoinEvent){
        submitAsync {
            setupAndLoad(e.player)
        }
    }

    @SubscribeEvent
    private fun onQuit(e: PlayerQuitEvent) {
        // 先把材料包缓存落盘, 再释放数据容器 —— 顺序反了容器就已经没了, 写不进去
        PlayerWorldEdit.packManager.unload(e.player)
        e.player.uniqueId.releasePlayerDataContainer()
    }

    /**
     * 数据容器就绪之后再去加载材料包缓存
     *
     * 加载排在 setupDataContainer() 之后: 容器没就绪就读会拿到一个空包, 之后退出时落盘
     * 就把玩家的真实数据覆盖成空的了。
     */
    private fun setupAndLoad(player: Player) {
        player.setupDataContainer()
        PlayerWorldEdit.packManager.load(player)
    }

    fun getMaterialPack(uuid: UUID): MaterialPack {
        if (!uuid.getAutoDataContainer().keys().contains(MATERIAL_PACK_KEY)) {
            val materialPack = MaterialPack(mutableSetOf())
            saveMaterialPack(uuid, materialPack)
            return materialPack
        }
        return PlayerWorldEdit.gson.fromJson(decompressFromBase64(uuid.getAutoDataContainer()[MATERIAL_PACK_KEY]!!), MaterialPack::class.java)
    }

    /** 改内存里的数据容器 (真正落库要等 `releasePlayerDataContainer()`: 退出 / 关服) */
    fun saveMaterialPack(uuid: UUID, materialPack: MaterialPack) {
        uuid.getAutoDataContainer()[MATERIAL_PACK_KEY] = compressToBase64(PlayerWorldEdit.gson.toJson(materialPack))
    }

    /**
     * **立刻**把材料包写进数据库 (材料交易用)
     *
     * [saveMaterialPack] 只改内存里的数据容器, 真正落库要等 `releasePlayerDataContainer()` (退出 /
     * 关服) —— 交易路径等不起: 崩在那之前, 库里还是旧值, 玩家重登后材料会"回来", 而物品早就给出去了
     * (重复提现); 存入那一路反过来会丢材料 (2026-10-14 的 R10)。这里在改容器的同时直接
     * `Database.set(...)` 写库 (TabooLib 的 `Database.set(user, key, value)` 是直接写, 不是缓存)。
     *
     * 注意这不是"跨存储原子性": 玩家背包那一侧 (giveItem / 把物品拖进菜单) 是 Bukkit 自己管的, 与本
     * 插件的库没有共同事务。能保证的是**顺序** —— 调用方先让这里落库, 再动背包, 所以崩溃窗口只会朝
     * "少给一件物品"的方向偏, 不会凭空多出材料。
     */
    fun saveMaterialPackNow(uuid: UUID, materialPack: MaterialPack) {
        val container = uuid.getAutoDataContainer()
        val value = compressToBase64(PlayerWorldEdit.gson.toJson(materialPack))
        container[MATERIAL_PACK_KEY] = value
        container.database.set(uuid.toString(), MATERIAL_PACK_KEY, value)
    }

    private fun compressToBase64(json: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun decompressFromBase64(base64: String): String {
        val bytes = Base64.getDecoder().decode(base64)
        val input = GZIPInputStream(bytes.inputStream())
        return input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

}

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
        if (!uuid.getAutoDataContainer().keys().contains("materialPack")) {
            val materialPack = MaterialPack(mutableSetOf())
            saveMaterialPack(uuid, materialPack)
            return materialPack
        }
        return PlayerWorldEdit.gson.fromJson(decompressFromBase64(uuid.getAutoDataContainer()["materialPack"]!!), MaterialPack::class.java)
    }
    fun saveMaterialPack(uuid: UUID, materialPack: MaterialPack) {
        uuid.getAutoDataContainer()["materialPack"] = compressToBase64(PlayerWorldEdit.gson.toJson(materialPack))
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

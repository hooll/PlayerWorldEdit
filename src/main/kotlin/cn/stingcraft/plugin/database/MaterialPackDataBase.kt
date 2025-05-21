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
            e.player.setupDataContainer()
        }
    }

    @SubscribeEvent
    private fun onQuit(e: PlayerQuitEvent) {
        e.player.uniqueId.releasePlayerDataContainer()
    }

    fun getMaterialPack(player: Player): MaterialPack {
        if (!player.uniqueId.getAutoDataContainer().keys().contains("materialPack")) {
            val materialPack = MaterialPack(mutableSetOf())
            saveMaterialPack(player, materialPack)
            return materialPack
        }
        return PlayerWorldEdit.gson.fromJson(decompressFromBase64(player.uniqueId.getAutoDataContainer()["materialPack"]!!), MaterialPack::class.java)
    }
    fun saveMaterialPack(player: Player, materialPack: MaterialPack) {
        player.uniqueId.getAutoDataContainer()["materialPack"] = compressToBase64(PlayerWorldEdit.gson.toJson(materialPack))
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
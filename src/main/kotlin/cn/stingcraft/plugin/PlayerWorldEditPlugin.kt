package cn.stingcraft.plugin

import cn.stingcraft.plugin.manager.MaterialPackManager
import cn.stingcraft.plugin.manager.WorldEditManager
import com.github.fierioziy.particlenativeapi.api.ParticleNativeAPI
import com.github.fierioziy.particlenativeapi.core.ParticleNativeCore
import com.google.gson.GsonBuilder
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.platform.BukkitPlugin


object PlayerWorldEdit : Plugin() {

    val gson = GsonBuilder().setPrettyPrinting().create()

    var particleApi: ParticleNativeAPI? = null

    val packManager = MaterialPackManager()

    val worldEditManager = WorldEditManager()

    override fun onEnable() {
        info("Successfully running PlayerWorldEdit!")
        particleApi = ParticleNativeCore.loadAPI(BukkitPlugin.getInstance())
    }
}
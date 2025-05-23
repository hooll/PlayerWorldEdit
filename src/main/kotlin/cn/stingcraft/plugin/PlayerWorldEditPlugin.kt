package cn.stingcraft.plugin

import cn.stingcraft.plugin.manager.SettingManager
import cn.stingcraft.plugin.manager.MaterialPackManager
import cn.stingcraft.plugin.manager.WorldEditManager
import com.google.gson.GsonBuilder
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info


object PlayerWorldEdit : Plugin() {

    val gson = GsonBuilder().setPrettyPrinting().create()

    val packManager = MaterialPackManager()

    val worldEditManager = WorldEditManager()

    val settingManager = SettingManager()

    override fun onEnable() {
        info("Successfully running PlayerWorldEdit!")
    }
}
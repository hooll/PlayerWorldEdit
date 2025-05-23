package cn.stingcraft.plugin.manager

import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

class SettingManager {

    @Config("settings.yml", autoReload = true)
    lateinit var settings: Configuration
        private set

    fun getMaterialPackGuiSettings():ConfigurationSection{
        return settings.getConfigurationSection("material-pack-gui")!!
    }

    fun getWorldEditSettings():ConfigurationSection{
        return settings.getConfigurationSection("world-edit")!!
    }

}
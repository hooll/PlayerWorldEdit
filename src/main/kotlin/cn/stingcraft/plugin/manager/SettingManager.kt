package cn.stingcraft.plugin.manager

import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

object SettingManager {

    @Config("settings.yml", autoReload = true)
    lateinit var settings: Configuration
        private set

    /**
     * 重新读取 settings.yml
     */
    fun reload() {
        settings.reload()
    }

    fun getMaterialPackGuiSettings():ConfigurationSection{
        return settings.getConfigurationSection("material-pack-gui")!!
    }

    fun getWorldEditSettings():ConfigurationSection{
        return settings.getConfigurationSection("world-edit")!!
    }

}

package cn.stingcraft.plugin

import cn.stingcraft.plugin.manager.SettingManager
import cn.stingcraft.plugin.manager.MaterialPackManager
import cn.stingcraft.plugin.manager.WorldEditManager
import com.google.gson.GsonBuilder
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.module.lang.Language


object PlayerWorldEdit : Plugin() {

    val gson = GsonBuilder().setPrettyPrinting().create()

    val settingManager = SettingManager

    val packManager = MaterialPackManager()

    val worldEditManager = WorldEditManager()

    override fun onEnable() {
        info("Successfully running PlayerWorldEdit!")
    }

    override fun onDisable() {
        // 选区显示体是实体, 插件卸载/关服时收掉, 免得留下孤儿实体
        worldEditManager.clearDisplays()
    }

    /**
     * 重载配置文件与语言文件, 并丢弃依赖配置的缓存
     */
    fun reload() {
        settingManager.reload()
        Language.reload()
        worldEditManager.reloadCache()
    }
}

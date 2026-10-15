package cn.stingcraft.plugin

import cn.stingcraft.plugin.manager.SettingManager
import cn.stingcraft.plugin.manager.MaterialPackManager
import cn.stingcraft.plugin.manager.WorldEditManager
import cn.stingcraft.plugin.util.cancelWatchdogs
import cn.stingcraft.plugin.util.EditLifecycle
import cn.stingcraft.plugin.inventory.MaterialTradeJournal
import taboolib.common.platform.function.warning
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
        EditLifecycle.start()
        MaterialTradeJournal.unresolvedPlayers().forEach { uuid ->
            packManager.suspendTrades(uuid)
            warning("[PlayerWorldEdit] $uuid 存在待核对材料交易, 已暂停交易, 请检查 material-transactions")
        }
        info("Successfully running PlayerWorldEdit!")
    }

    override fun onDisable() {
        val drained = runCatching { EditLifecycle.stop() }.getOrDefault(false)
        if (!drained) warning("[PlayerWorldEdit] 停用时仍有操作未排空, 保留锁与在途快照, 不将其声明为完成")
        runCatching { cancelWatchdogs() }.onFailure { warning("[PlayerWorldEdit] 停止计时器失败: $it") }
        // 每份材料包独立保存。显示清理无论成功与否均不能阻断数据保存。
        runCatching { packManager.flushAll() }.onFailure { warning("[PlayerWorldEdit] 材料保存失败: $it") }
        runCatching { worldEditManager.clearDisplays() }.onFailure { warning("[PlayerWorldEdit] 显示清理失败: $it") }
    }

    /**
     * 重载配置文件与语言文件, 并丢弃依赖配置的缓存
     *
     * 顺便收掉所有已经显示出来的选区框: 显示材质 / 是否仅自己可见 / 显示时长这些配置改了之后,
     * 已经画出来的实体不会自己更新。收掉之后玩家重新点一下点, 就会按新配置画出来。
     */
    fun reload() {
        settingManager.reload()
        Language.reload()
        worldEditManager.reloadCache()
        worldEditManager.clearDisplays()
    }
}

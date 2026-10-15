package cn.stingcraft.plugin

import cn.stingcraft.plugin.manager.SettingManager
import cn.stingcraft.plugin.manager.MaterialPackManager
import cn.stingcraft.plugin.manager.WorldEditManager
import cn.stingcraft.plugin.util.cancelWatchdogs
import cn.stingcraft.plugin.util.EditLifecycle
import cn.stingcraft.plugin.inventory.MaterialTradeJournal
import java.util.UUID
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
        if (!EditLifecycle.tryStart()) {
            warning("[PlayerWorldEdit] 拒绝启用: 上一次停用未排空, 可能有玩家账目待核对。" +
                " 请保持本插件停止接单, 完整重启服务端后按日志核对 material-transactions 与 undo 记录")
            return
        }
        MaterialTradeJournal.unresolvedPlayers().forEach { uuid ->
            packManager.suspendTrades(uuid)
            warning("[PlayerWorldEdit] $uuid 存在待核对材料交易, 已暂停交易, 请检查 material-transactions")
        }
        info("Successfully running PlayerWorldEdit!")
    }

    override fun onDisable() {
        val drained = runCatching { EditLifecycle.stop() }.getOrDefault(false)
        // 未排空时把"结算还没跑的玩家"点名; 这些结算之后只会改缓存, 不会再触发保存 (G1)。
        val pending = if (drained) emptySet<UUID>() else EditLifecycle.pendingOwners()
        // 交易日志的阶段标记是异步写的, 停用前把队列清空, 未决记录才可用于人工核对 (G6)。
        runCatching { MaterialTradeJournal.flushPending() }
            .onFailure { warning("[PlayerWorldEdit] 交易日志停用前未写完: $it") }
        runCatching { cancelWatchdogs() }.onFailure { warning("[PlayerWorldEdit] 停止计时器失败: $it") }
        // 每份材料包独立保存。显示清理无论成功与否均不能阻断数据保存。
        runCatching { packManager.flushAll() }.onFailure { warning("[PlayerWorldEdit] 材料保存失败: $it") }
        // 对"停用后才改缓存"的退料/返料再补一次保存; 仍失败的与 pending 一起点名 (G1)。
        val stillUnsaved = runCatching { packManager.flushUnsaved() }.getOrDefault(emptySet())
        val affected = (pending + stillUnsaved)
        if (affected.isNotEmpty()) {
            warning("[PlayerWorldEdit] 停用未完全排空, 以下玩家的退料/返料可能未落库, 请对照材料库与 undo 记录人工核对: $affected")
        }
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

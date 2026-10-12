package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.command.CommandSender
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.warning
import taboolib.platform.util.sendLang

object CommandReload {

    val command = subCommand {
        execute<CommandSender>{ sender: CommandSender, _: CommandContext<CommandSender>, _: String ->
            runCatching {
                PlayerWorldEdit.reload()
            }.onSuccess {
                sender.sendLang("message-reload-susses", pluginId to "pluginId")
            }.onFailure {
                warning("[PlayerWorldEdit] 重载失败: $it")
                sender.sendLang("message-reload-failed", pluginId to "pluginId", it.toString() to "error")
            }
        }
    }

}

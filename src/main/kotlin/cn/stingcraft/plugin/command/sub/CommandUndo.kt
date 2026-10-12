package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.pluginId
import taboolib.platform.util.sendLang

object CommandUndo {

    val command = subCommand {
        execute<Player> { sender: Player, _: CommandContext<Player>, _: String ->
            undo(sender, 1)
        }
        dynamic("times") {
            suggestion(true) { _: Player, _: CommandContext<Player> ->
                return@suggestion listOf("1", "2", "3", "5", "10")
            }
            execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                undo(sender, context["times"].toIntOrNull() ?: 1)
            }
        }
    }

    private fun undo(player: Player, times: Int) {
        // 内部会按区块丢到各自区域线程上执行, 这里不能异步
        val done = PlayerWorldEdit.worldEditManager.undo(player, times.coerceIn(1, 10))
        if (done <= 0) {
            player.sendLang("message-undo-none", pluginId to "pluginId")
        } else {
            player.sendLang("message-undo-susses", pluginId to "pluginId", done to "times")
        }
    }
}

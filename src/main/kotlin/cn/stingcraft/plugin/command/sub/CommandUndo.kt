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
        // undo 改成严格串行后不再同步返回条数: 一条完全结束才轮到下一条, 结果用回调给回来
        PlayerWorldEdit.worldEditManager.undo(player, times.coerceIn(1, 10)) { done, interrupted ->
            when {
                // 中断不等于"没有可撤销的": 那条记录已经放回队列 (超时 / 还原失败 / 记录读不出来),
                // 玩家可以再试一次
                interrupted -> player.sendLang("message-undo-timeout", pluginId to "pluginId", done to "times")
                done <= 0 -> player.sendLang("message-undo-none", pluginId to "pluginId")
                else -> player.sendLang("message-undo-susses", pluginId to "pluginId", done to "times")
            }
        }
    }
}

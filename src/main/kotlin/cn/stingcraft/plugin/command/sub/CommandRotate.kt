package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand

object CommandRotate {

    val command = subCommand {
        dynamic("angle") {
            suggestion(true) { _: Player, _: CommandContext<Player> ->
                return@suggestion PlayerWorldEdit.worldEditManager.rotateAngles().map { it.toString() }
            }
            execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                // 数值解析不了就传 0, 由 manager 统一校验并提示 (同 cyl 的半径)
                PlayerWorldEdit.worldEditManager.rotateClipboard(sender, context["angle"].toIntOrNull() ?: 0)
            }
        }
    }
}

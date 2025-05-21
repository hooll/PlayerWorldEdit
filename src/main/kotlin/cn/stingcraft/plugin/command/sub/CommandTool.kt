package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand
import taboolib.platform.util.giveItem

object CommandTool {

    val command = subCommand {
        execute<Player>{ sender: Player, context: CommandContext<Player>, argument: String ->
            sender.giveItem(PlayerWorldEdit.worldEditManager.getPosTool())
        }
    }

}
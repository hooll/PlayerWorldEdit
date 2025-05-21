package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.inventory.MaterialPackInventory
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand

object CommandPack {

    val command = subCommand {
        execute<Player>{ sender: Player, context: CommandContext<Player>, argument: String ->
            MaterialPackInventory.open(sender)
        }
    }

}
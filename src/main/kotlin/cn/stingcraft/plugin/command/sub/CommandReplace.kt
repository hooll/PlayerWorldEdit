package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.submitAsync
import taboolib.library.xseries.parseToMaterial

object CommandReplace {

    private val worldEditManager = PlayerWorldEdit.worldEditManager

    val command = subCommand {
        dynamic("from"){
            suggestion(true){ _: Player, _: CommandContext<Player> ->
                return@suggestion  Material.values().filter { it.isBlock }.map { it.name }
            }
            dynamic("to"){
                suggestion(true){ _: Player, _: CommandContext<Player> ->
                    return@suggestion  Material.values().filter { it.isBlock }.map { it.name }
                }
                execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                    submitAsync {
                        worldEditManager.tryReplaceArea(
                            sender,
                            context["from"].lowercase().parseToMaterial(),
                            context["to"].lowercase().parseToMaterial()
                        )
                    }
                }
            }
        }
    }

}

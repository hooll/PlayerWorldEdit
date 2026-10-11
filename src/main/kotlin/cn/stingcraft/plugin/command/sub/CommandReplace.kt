package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.until.parseBlockOrNull
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.submitAsync
import taboolib.platform.util.sendLang

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
                    val from = parseBlockOrNull(context["from"]) ?: run {
                        sender.sendLang("message-invalid-material", pluginId to "pluginId", context["from"] to "material")
                        return@execute
                    }
                    val to = parseBlockOrNull(context["to"]) ?: run {
                        sender.sendLang("message-invalid-material", pluginId to "pluginId", context["to"] to "material")
                        return@execute
                    }
                    submitAsync {
                        worldEditManager.tryReplaceArea(sender, from, to)
                    }
                }
            }
        }
    }

}

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

object CommandSet {

    private val worldEditManager = PlayerWorldEdit.worldEditManager

    val command = subCommand {
        dynamic("block"){
            suggestion(true){ sender: Player, context: CommandContext<Player> ->
                return@suggestion  Material.values().filter { it.isBlock }.map { it.name }
            }
            execute<Player> { sender: Player, context: CommandContext<Player>, argument: String ->
                val material = parseBlockOrNull(context["block"]) ?: run {
                    sender.sendLang("message-invalid-material", pluginId to "pluginId", context["block"] to "material")
                    return@execute
                }
                submitAsync {
                    worldEditManager.trySetArea(sender, material)
                }
            }
        }
    }

}

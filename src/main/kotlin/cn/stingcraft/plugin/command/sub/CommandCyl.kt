package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.until.parseBlockOrNull
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.pluginId
import taboolib.platform.util.sendLang

object CommandCyl {

    val command = subCommand {
        dynamic("block") {
            suggestion(true) { _: Player, _: CommandContext<Player> ->
                return@suggestion Material.values().filter { it.isBlock }.map { it.name }
            }
            dynamic("radius") {
                suggestion(true) { _: Player, _: CommandContext<Player> ->
                    return@suggestion listOf("1", "5", "10", "20", "50")
                }
                dynamic("height") {
                    suggestion(true) { _: Player, _: CommandContext<Player> ->
                        return@suggestion listOf("1", "5", "10", "20", "50")
                    }
                    execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                        val material = parseBlockOrNull(context["block"]) ?: run {
                            sender.sendLang("message-invalid-material", pluginId to "pluginId", context["block"] to "material")
                            return@execute
                        }
                        // 数值解析不了就传 0, 由 manager 统一做范围校验并提示 (同 resizeSelection 的写法)
                        PlayerWorldEdit.worldEditManager.tryGenerateCylinder(
                            sender,
                            material,
                            context["radius"].toIntOrNull() ?: 0,
                            context["height"].toIntOrNull() ?: 0
                        )
                    }
                }
            }
        }
    }
}

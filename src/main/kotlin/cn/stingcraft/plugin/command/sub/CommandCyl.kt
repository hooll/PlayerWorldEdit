package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.util.parseBlockOrNull
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
                // 不带高度就是 1 层 (对齐 WE 的 /cyl)
                execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                    cylinder(sender, context, 1)
                }
                dynamic("height") {
                    suggestion(true) { _: Player, _: CommandContext<Player> ->
                        return@suggestion listOf("1", "5", "10", "20", "50")
                    }
                    execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                        cylinder(sender, context, context["height"].toIntOrNull() ?: 0)
                    }
                }
            }
        }
    }

    private fun cylinder(player: Player, context: CommandContext<Player>, height: Int) {
        val material = parseBlockOrNull(context["block"]) ?: run {
            player.sendLang("message-invalid-material", pluginId to "pluginId", context["block"] to "material")
            return
        }
        // 数值解析不了就传 0, 由 manager 统一做范围校验并提示 (同 resizeSelection 的写法)
        PlayerWorldEdit.worldEditManager.tryGenerateCylinder(
            player,
            material,
            context["radius"].toIntOrNull() ?: 0,
            height
        )
    }
}

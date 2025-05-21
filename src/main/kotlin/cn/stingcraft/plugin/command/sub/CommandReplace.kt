package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.pluginId
import taboolib.common.platform.function.submitAsync
import taboolib.library.xseries.parseToMaterial
import taboolib.module.nms.getI18nName
import taboolib.platform.util.sendLang

object CommandReplace {

    private val worldEditManager =PlayerWorldEdit.worldEditManager
    private val packManager = PlayerWorldEdit.packManager

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
                        val from = context["from"].lowercase().parseToMaterial()
                        val to = context["to"].lowercase().parseToMaterial()
                        if (!worldEditManager.hasRegion(sender)) {
                            sender.sendLang("message-no-region", pluginId to "pluginId")
                            return@submitAsync
                        }
                        if (!packManager.hasEnoughMaterial(sender, to, worldEditManager.getCountInRegion(sender))){
                            sender.sendLang("message-materialpack-not-enough",
                                pluginId to "pluginId",
                                to.getI18nName() to "material",
                                worldEditManager.getCountInRegion(sender) to "amount")
                            return@submitAsync
                        }
                        packManager.removeMaterial(sender, to, worldEditManager.getCountInRegion(sender))
                        PlayerWorldEdit.worldEditManager.replaceAreaBlock(sender, from,to)
                        sender.sendLang("message-replace-susses",  pluginId to "pluginId")
                    }
                }
            }
        }
    }

}
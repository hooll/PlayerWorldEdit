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

object CommandSet {

    private val worldEditManager =PlayerWorldEdit.worldEditManager
    private val packManager = PlayerWorldEdit.packManager

    val command = subCommand {
        dynamic("block"){
            suggestion(true){ sender: Player, context: CommandContext<Player> ->
                return@suggestion  Material.values().filter { it.isBlock }.map { it.name }
            }
            execute<Player> { sender: Player, context: CommandContext<Player>, argument: String ->
                submitAsync {
                    val material = context["block"].lowercase().parseToMaterial()
                    if (!worldEditManager.hasRegion(sender)) {
                        sender.sendLang("message-no-region", pluginId to "pluginId")
                        return@submitAsync
                    }
                    if (!packManager.hasEnoughMaterial(sender, material, worldEditManager.getCountInRegion(sender))){
                        sender.sendLang("message-materialpack-not-enough",
                            pluginId to "pluginId",
                            material.getI18nName() to "material",
                            worldEditManager.getCountInRegion(sender) to "amount")
                        return@submitAsync
                    }
                    packManager.removeMaterial(sender, material, worldEditManager.getCountInRegion(sender))
                    worldEditManager.setAreaBlock(sender, material)
                    sender.sendLang("message-set-susses",  pluginId to "pluginId")
                }
            }
        }
    }

}
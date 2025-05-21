package cn.stingcraft.plugin.command.sub

import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.adaptPlayer
import taboolib.common.platform.function.pluginId
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components
import taboolib.module.nms.getI18nName
import taboolib.platform.util.asLangText
import taboolib.platform.util.isAir
import taboolib.platform.util.sendLang

object CommandID {
    val command = subCommand {
        execute<Player>{ sender: Player, context: CommandContext<Player>, argument: String ->
            val itemStack = sender.inventory.itemInMainHand
            if (itemStack.isAir){
                sender.sendLang("message-id-hand-empty",  pluginId to "pluginId")
                return@execute
            }
            val material = itemStack.type
            ComponentText.of(sender.asLangText("message-id-info",  pluginId to "pluginId", material.getI18nName() to "material", material.name to "id"))
                .append(sender.asLangText("message-id-insert"))
                .clickInsertText(material.name)
                .clickCopyToClipboard(material.name)
                .hoverText(material.name)
                .sendTo(sender = adaptPlayer(sender))

        }
    }
}
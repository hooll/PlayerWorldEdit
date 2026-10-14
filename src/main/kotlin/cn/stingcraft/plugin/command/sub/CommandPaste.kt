package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.entity.Player
import taboolib.common.platform.command.subCommand

object CommandPaste {

    val command = subCommand {
        execute<Player> { sender: Player, _, _ ->
            // 内部会按区块丢到各自区域线程上写, 这里不能异步
            PlayerWorldEdit.worldEditManager.tryPaste(sender)
        }
        // 只加一个预览分支: 不带参数仍然是"直接贴", 老手的手感不降
        literal("preview") {
            execute<Player> { sender: Player, _, _ ->
                PlayerWorldEdit.worldEditManager.previewPaste(sender)
            }
        }
    }
}

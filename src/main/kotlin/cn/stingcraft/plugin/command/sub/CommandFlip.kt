package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand

object CommandFlip {

    val command = subCommand {
        // 和 expand/undo 一样分两段: 不带方向就按玩家朝向 (对齐 WE 的 //flip 默认), 带了走 dynamic
        execute<Player> { sender: Player, _: CommandContext<Player>, _: String ->
            flip(sender, "me")
        }
        dynamic("direction") {
            suggestion(true) { _: Player, _: CommandContext<Player> ->
                return@suggestion PlayerWorldEdit.worldEditManager.flipDirections()
            }
            execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                flip(sender, context["direction"])
            }
        }
    }

    private fun flip(player: Player, direction: String) {
        // 认不出的方向由 manager 统一提示 (同 rotate 的角度: 合法值只有一份, 在 manager 里)
        PlayerWorldEdit.worldEditManager.flipClipboard(player, direction)
    }
}

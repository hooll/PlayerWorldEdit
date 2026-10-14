package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand

object CommandMove {

    val command = subCommand {
        // 和 expand/undo 一样分两段: 没有格数就搬 1 格, 有格数走 dynamic
        execute<Player> { sender: Player, _: CommandContext<Player>, _: String ->
            move(sender, 1)
        }
        dynamic("blocks") {
            suggestion(true) { _: Player, _: CommandContext<Player> ->
                return@suggestion listOf("1", "2", "3", "5", "10")
            }
            execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                // 解析不了就当 1, 越界由 manager 统一 clamp 到 1..100 (同 expand 的写法)
                move(sender, context["blocks"].toIntOrNull() ?: 1)
            }
        }
    }

    private fun move(player: Player, blocks: Int) {
        // 方向由 manager 按视线吸附 (与扩缩同一套 snapAxis), 越界是整体拒绝, 不夹取
        PlayerWorldEdit.worldEditManager.moveSelection(player, blocks)
    }
}

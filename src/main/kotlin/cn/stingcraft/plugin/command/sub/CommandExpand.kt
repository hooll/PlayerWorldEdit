package cn.stingcraft.plugin.command.sub

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.subCommand

object CommandExpand {

    val command = subCommand {
        // 和 CommandUndo 一样分成两段: 没有格数就直接扩 1 格, 有格数走 dynamic
        execute<Player> { sender: Player, _: CommandContext<Player>, _: String ->
            expand(sender, 1)
        }
        dynamic("blocks") {
            suggestion(true) { _: Player, _: CommandContext<Player> ->
                return@suggestion listOf("1", "2", "3", "5", "10")
            }
            execute<Player> { sender: Player, context: CommandContext<Player>, _: String ->
                // 解析不了就当 1, 越界由 manager 统一 clamp 到 1..100 (同 CommandUndo 的写法)
                expand(sender, context["blocks"].toIntOrNull() ?: 1)
            }
        }
    }

    private fun expand(player: Player, blocks: Int) {
        // 内部会先做选区校验 (没选齐两点 -> 提示先选点), 再把朝向那一面外扩并把最外层方块复制过去
        PlayerWorldEdit.worldEditManager.resizeSelection(player, blocks, expand = true)
    }
}

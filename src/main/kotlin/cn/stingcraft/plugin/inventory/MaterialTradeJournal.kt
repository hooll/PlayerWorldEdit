package cn.stingcraft.plugin.inventory

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.warning
import java.io.File
import java.util.UUID

/**
 * 记录 GUI 交易的原物与阶段, 供结果不明/重启后人工核对。不是跨玩家存档和材料库的原子事务。
 * 已提交后的日志更新失败只能报警, 不能被解释成交易未提交而再次退物。
 */
internal class MaterialTradeJournal private constructor(
    private val file: File,
    private val data: YamlConfiguration
) {
    fun mark(state: String) {
        runCatching { data.set("state", state); data.save(file) }
            .onFailure { warning("[PlayerWorldEdit] 交易阶段记录失败 (${file.name}), 请人工核对: $it") }
    }

    companion object {
        fun unresolvedPlayers(directory: File = File(getDataFolder(), "material-transactions")): Set<UUID> {
            val files = directory.listFiles() ?: return emptySet()
            return files.filter { it.extension == "yml" }.mapNotNull { file ->
                val data = YamlConfiguration()
                val loaded = runCatching { data.load(file) }.isSuccess
                val state = data.getString("state")
                val known = state in setOf("PREPARED", "UNKNOWN", "COMMITTED", "RETURNED", "DELIVERED", "NOT_ENOUGH", "NOT_COMMITTED")
                val unresolved = !loaded || !known || state == "PREPARED" || state == "UNKNOWN" ||
                    (data.getString("direction") == "WITHDRAW" && state == "COMMITTED")
                if (unresolved) {
                    val owner = data.getString("player") ?: file.name.take(36)
                    runCatching { UUID.fromString(owner) }.getOrNull()
                } else null
            }.toSet()
        }

        fun prepare(
            uuid: UUID, direction: String, items: List<ItemStack>,
            directory: File = File(getDataFolder(), "material-transactions")
        ): MaterialTradeJournal {
            val dir = directory.apply { mkdirs() }
            val file = File(dir, "$uuid-${UUID.randomUUID()}.yml")
            val data = YamlConfiguration()
            data.set("player", uuid.toString())
            data.set("direction", direction)
            data.set("state", "PREPARED")
            data.set("items", items.map { it.clone() })
            data.save(file)
            return MaterialTradeJournal(file, data)
        }
    }
}

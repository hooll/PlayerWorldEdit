package cn.stingcraft.plugin.inventory

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.warning
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 记录 GUI 交易的原物与阶段, 供结果不明/重启后人工核对。不是跨玩家存档和材料库的原子事务。
 * 已提交后的日志更新失败只能报警, 不能被解释成交易未提交而再次退物。
 *
 * 写入策略 (G6): prepare 必须同步落盘 (账动之前先有原物记录), 之后的阶段标记走单线程串行队列,
 * 把磁盘写移出玩家点击路径; 停用前由 [flushPending] 清空队列。串行保证同一文件的阶段顺序。
 * 清理策略 (G5): 到达终态 (DELIVERED / RETURNED / NOT_COMMITTED / NOT_ENOUGH, 或存入已入账的
 * COMMITTED) 后删除文件, 目录只保留未决记录 (PREPARED / UNKNOWN / 未送达的提现 COMMITTED),
 * 启动扫描不随历史交易数量增长。
 */
internal class MaterialTradeJournal private constructor(
    private val file: File,
    private val data: YamlConfiguration
) {
    fun mark(state: String) {
        writer.execute {
            runCatching {
                synchronized(data) {
                    data.set("state", state)
                    data.save(file)
                    if (isTerminal(state)) file.delete()
                }
            }.onFailure { warning("[PlayerWorldEdit] 交易阶段记录失败 (${file.name}), 请人工核对: $it") }
        }
    }

    private fun isTerminal(state: String): Boolean =
        state in TERMINAL_STATES || (state == "COMMITTED" && data.getString("direction") == "DEPOSIT")

    companion object {
        /** 阶段写完即可删除的终态; 未送达的提现 COMMITTED 不在此列, 必须保留供人工核对。 */
        private val TERMINAL_STATES = setOf("DELIVERED", "RETURNED", "NOT_COMMITTED", "NOT_ENOUGH")

        /** 单线程串行队列: 阶段标记与删除按提交顺序落盘, 不占用玩家点击线程。 */
        private val writer: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "PlayerWorldEdit-TradeJournal").apply { isDaemon = true }
        }

        /** 停用前清空阶段标记队列; 超时只告警, 未决文件仍可用 (状态可能在 PREPARED)。 */
        fun flushPending(timeoutMillis: Long = 5000) {
            val done = writer.submit {}
            runCatching { done.get(timeoutMillis, TimeUnit.MILLISECONDS) }
                .onFailure { warning("[PlayerWorldEdit] 交易日志停用前未写完, 未决记录保留供人工核对: $it") }
        }

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

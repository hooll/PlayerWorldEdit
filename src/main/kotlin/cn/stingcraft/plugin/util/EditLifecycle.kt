package cn.stingcraft.plugin.util

import taboolib.common.platform.function.submitAsync
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 停止接单后关闭批次入口, 排空正在执行的写入与已排队的结算, 再允许保存。 */
internal object EditLifecycle {
    enum class State { RUNNING, DRAINING, STOPPED }
    @Volatile var state = State.RUNNING
        private set
    val accepting: Boolean get() = state == State.RUNNING
    private val batches = mutableSetOf<BatchDrain>()
    private val completions = mutableSetOf<Completion>()
    private val failedSettlements = AtomicInteger()

    /** 重新启用: 上一停用未排空时拒绝并给出可读日志, 不把异常抛回启用流程 (G2)。 */
    @Synchronized fun tryStart(): Boolean {
        return try {
            check(batches.isEmpty() && completions.isEmpty() && failedSettlements.get() == 0)
                { "Previous operations have not drained" }
            state = State.RUNNING
            true
        } catch (error: IllegalStateException) {
            java.util.logging.Logger.getLogger("PlayerWorldEdit").warning(
                "拒绝启用: 上一停用未排空 (在途批次=${batches.size}, 待结算=${completions.size}, 失败结算=${failedSettlements.get()})," +
                    " 保持停止接单状态, 请完整重启服务端: $error"
            )
            false
        }
    }

    /** 已登记但尚未执行的结算归属 (结算可能在停用后才运行), 供停用收尾点名受影响玩家 (G1)。 */
    @Synchronized fun pendingOwners(): Set<UUID> = completions.mapNotNull { completion ->
        when (val owner = completion.owner) {
            is UUID -> owner
            is Player -> owner.uniqueId
            else -> null
        }
    }.toSet()

    /** 收尾后 (可能晚于停用) 的退料/返料记录在 MaterialPackManager.unsaved; 这里仅为归属对账预留。 */

    @Synchronized fun register(batch: BatchDrain): Boolean {
        if (!accepting) return false
        batches.add(batch)
        return true
    }

    @Synchronized fun finished(batch: BatchDrain) { batches.remove(batch) }

    /** 即使实体任务被停用静默取消, 停用路径也能执行同一份结算, 且只执行一次。 */
    fun dispatch(schedule: (() -> Unit) -> Unit, action: () -> Unit, owner: Any? = null) {
        val completion = Completion(owner, action)
        val inline = synchronized(this) {
            completions.add(completion)
            !accepting
        }
        if (inline) completion.run()
        else try { schedule { completion.run() } } catch (error: Throwable) {
            if (completion.started) throw error
            completion.run()
        }
    }

    /** 实体退休时, 执行被它的调度器取消的纯数据收尾, 不影响后来登录的实体。 */
    fun retire(owner: Any) {
        val snapshot = synchronized(this) { completions.filter { it.owner === owner } }
        snapshot.forEach { completion ->
            runCatching { completion.run() }.onFailure {
                java.util.logging.Logger.getLogger("PlayerWorldEdit").warning("Retired settlement failed: $it")
            }
        }
    }

    /** 不强杀仍可能写世界的线程; 超过等待上限保留 DRAINING, 调用方必须报告未排空。 */
    fun stop(waitMillis: Long = 5000): Boolean {
        val snapshot = synchronized(this) {
            state = State.DRAINING
            batches.toList() to completions.toList()
        }
        snapshot.first.forEach { runCatching { it.stop() } }
        snapshot.second.forEach { runCatching { it.run() } }
        val deadline = System.nanoTime() + waitMillis * 1_000_000
        while (true) {
            synchronized(this) {
                if (batches.isEmpty() && completions.isEmpty()) {
                    if (failedSettlements.get() != 0) return false
                    state = State.STOPPED
                    return true
                }
            }
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(1)
        }
    }

    private class Completion(val owner: Any?, private val action: () -> Unit) {
        private val claimed = AtomicBoolean()
        val started: Boolean get() = claimed.get()
        fun run() {
            if (!claimed.compareAndSet(false, true)) return
            try { action() } catch (error: Throwable) {
                failedSettlements.incrementAndGet()
                java.util.logging.Logger.getLogger("PlayerWorldEdit").warning("Settlement failed; manual recovery required: $error")
                throw error
            } finally {
                synchronized(EditLifecycle) { completions.remove(this) }
            }
        }
    }
}

/** 已进入操作的后台收尾不能因 scheduler 停用而丢掉。 */
internal fun submitCompletion(action: () -> Unit) =
    EditLifecycle.dispatch({ task -> submitAsync { task() } }, action)

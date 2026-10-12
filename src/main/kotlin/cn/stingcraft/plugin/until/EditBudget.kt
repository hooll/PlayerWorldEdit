package cn.stingcraft.plugin.until

import taboolib.common.platform.function.submit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 全服"每 tick 可改动方块数"的预算。
 *
 * Folia 的区域线程是并行跑的, 只让每个区块任务"隔一拍再执行下一片"并不能限制同一 tick 里
 * 有多少条区域线程在写方块。这里用全局调度器每 tick 补满一次预算, 区块任务先领到预算才动手,
 * 领不到就下一 tick 重试 —— 不管选区多大、跨多少个区域, 全服每 tick 的写方块量都有上限,
 * 相当于 FAWE 的异步批量写入那种节流, 但写入仍然发生在拥有区块的区域线程上。
 */
object EditBudget {

    /** 每 tick 全服可改动的方块总数 */
    @Volatile
    var perTick: Int = DEFAULT_PER_TICK
        private set

    /** 单个任务的方块数: 由预算推导, 保证单个任务不会长时间占住区域线程 */
    val sliceSize: Int
        get() = (perTick / SLICES_PER_TICK).coerceIn(MIN_SLICE, MAX_SLICE)

    private val remaining = AtomicInteger(0)
    private val lastRefill = AtomicLong(0)
    private var started = false

    /**
     * 应用配置 (每次操作前调用一次, 改配置后重载即生效), 并在首次调用时启动补预算的定时器
     */
    fun configure(blocksPerTick: Int) {
        perTick = blocksPerTick.coerceAtLeast(MIN_PER_TICK)
        // 计时器还没跑起来时不要让"定时器失效"的兜底逻辑直接放行
        lastRefill.set(System.currentTimeMillis())
        synchronized(this) {
            if (!started) {
                started = true
                // Folia 走全局区域调度器, 普通服务端走主线程计时器
                submit(now = false, async = false, delay = 1, period = 1) {
                    remaining.set(perTick)
                    lastRefill.set(System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * 领取 [amount] 个方块的预算, 领不到返回 false (调用方应当下一 tick 再试)
     */
    fun tryConsume(amount: Int): Boolean {
        if (amount <= 0) return true
        // 补预算的定时器没在跑 (插件重载等异常情况) 就直接放行, 不能让操作永久卡住
        if (System.currentTimeMillis() - lastRefill.get() > STALL_TIMEOUT) return true
        while (true) {
            val current = remaining.get()
            if (current < amount) return false
            if (remaining.compareAndSet(current, current - amount)) return true
        }
    }

    private const val DEFAULT_PER_TICK = 20000

    /** 每 tick 让几条区域线程并行干活 */
    private const val SLICES_PER_TICK = 4

    private const val MIN_SLICE = 512
    private const val MAX_SLICE = 16384
    private const val MIN_PER_TICK = MIN_SLICE * SLICES_PER_TICK

    /** 超过这个时间没有补过预算, 就认为定时器失效 */
    private const val STALL_TIMEOUT = 3000L
}

package cn.stingcraft.plugin.util

import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import taboolib.platform.Folia
import taboolib.platform.util.runTask
import taboolib.platform.util.submit
import taboolib.common.platform.function.submit as submitTask
import taboolib.common.platform.service.PlatformExecutor
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 区块切片: 一个区块沿 Y 轴切出来的一片, [offset]/[size] 是它在整块快照里的下标区间 */
class RegionSlice(val region: CuboidRegion, val offset: Int, val size: Int)

/**
 * 一批区块任务的执行结果
 *
 * 原来 [runOnChunkThreads] 只回报"是不是超时兜底收的尾", 于是"某个区块抛异常"和"某个区块被超时
 * 截断"都会被调用方当成跑完了 —— 快照读完了就当写成功、撤销只还原了一半照样退料并删记录
 * (2026-10-14 的 R2 / R3)。现在把每个区块的进度与失败一起带出来, 调用方按"实际写到哪"结账。
 *
 * @param timedOut 是不是超时兜底收的尾 (还有区块没跑完)
 * @param failed 抛过异常 / 区块加载失败的区块下标 (这些区块只跑完了一部分)
 * @param slices 与 regions 一一对应的切片布局 (撤销按切片粒度记账要用, 见 [EditPipeline.undoBatches])
 * @param completed 与 regions 一一对应: 每个区块**完整跑完**的切片数
 */
class ChunkBatchResult(
    val timedOut: Boolean,
    val failed: Set<Int>,
    val slices: List<List<RegionSlice>>,
    val completed: IntArray
) {

    /** 这个区块是不是从头到尾跑完了 (没抛异常、也没被超时截断) */
    fun isComplete(index: Int): Boolean = index !in failed && completed[index] == slices[index].size

    /** 整批都跑完了 (所有区块的所有切片) */
    fun isAllComplete(): Boolean = !timedOut && slices.indices.all { isComplete(it) }

    /** 有没有区块真的动过 (至少完整跑完一片) —— 用来判断"这次操作是不是什么都没写成" */
    fun hasProgress(): Boolean = completed.any { it > 0 }

    /** 这个区块实际跑到第几片为止 (含可能写了一半的那一片, 见 [EditPipeline.undoBatches]) */
    fun reachedSlices(index: Int): Int =
        (completed[index] + if (index in failed) 1 else 0).coerceAtMost(slices[index].size)

    companion object {
        /** 空批次 (没有区块) 的结果: 什么都没跑, 也算"全部完成" */
        fun empty(): ChunkBatchResult = ChunkBatchResult(false, emptySet(), emptyList(), IntArray(0))
    }
}

/**
 * 把选区按区块切成若干小块, 顺序固定 (先 X 后 Z)。
 *
 * Folia 里一个区域线程只能改动自己拥有的区块, 而 WorldEdit 的 Folia 支持会把越界修改
 * 静默丢弃 (不报错, 也不计入改动数量), 所以整块选区不能一次提交, 必须拆成单个区块,
 * 每块丢到它所属的区域线程执行。
 */
fun CuboidRegion.splitByChunk(): List<CuboidRegion> {
    val min = minimumPoint
    val max = maximumPoint
    val result = ArrayList<CuboidRegion>()
    for (chunkX in (min.x shr 4)..(max.x shr 4)) {
        for (chunkZ in (min.z shr 4)..(max.z shr 4)) {
            result += CuboidRegion(
                BlockVector3.at(maxOf(min.x, chunkX shl 4), min.y, maxOf(min.z, chunkZ shl 4)),
                BlockVector3.at(minOf(max.x, (chunkX shl 4) + 15), max.y, minOf(max.z, (chunkZ shl 4) + 15))
            )
        }
    }
    return result
}

/**
 * 沿 Y 轴把这个区块切成每片不超过 [maxBlocks] 个方块的若干片。
 *
 * WorldEdit 的区域遍历顺序是 y -> z -> x (x 最快), 所以按 Y 切开后从下往上逐片处理,
 * 顺序和整体遍历完全一致 —— 撤销快照按同样的切片下标存取就不会错位。
 */
fun CuboidRegion.sliceByY(maxBlocks: Int): List<RegionSlice> {
    val min = minimumPoint
    val max = maximumPoint
    val layerBlocks = (max.x - min.x + 1) * (max.z - min.z + 1)
    val layersPerSlice = maxOf(1, maxBlocks / maxOf(1, layerBlocks))
    val result = ArrayList<RegionSlice>()
    var y = min.y
    var offset = 0
    while (y <= max.y) {
        val endY = minOf(max.y, y + layersPerSlice - 1)
        val size = (endY - y + 1) * layerBlocks
        result += RegionSlice(
            CuboidRegion(BlockVector3.at(min.x, y, min.z), BlockVector3.at(max.x, endY, max.z)),
            offset,
            size
        )
        offset += size
        y = endY + 1
    }
    return result
}

/**
 * 在拥有每个区块的线程上分批执行 [work] (参数是区块下标和这一批的切片), 全部结束后调用 [done]。
 *
 * 每个区块被 [EditBudget.sliceSize] 切成若干片, 每片单独排一个任务, 而且执行前要先领全局
 * 每 tick 预算 —— 大选区不会把某条区域线程一次占住几十毫秒, 也不会几百条线程同时写方块。
 * 区块按 [CHUNK_LOAD_WAVE] 分批加载, 避免一口气请求几百个区块。
 *
 * 单个区块抛错不会影响其余区块, 异常直接打到控制台; [done] 在最后一个完成的线程上运行,
 * 所以里面不要再碰玩家数据, 用 [runOnOwnThread] 切回玩家线程。
 *
 * [done] 拿到的 [ChunkBatchResult] 同时给出"是不是超时兜底"、"哪些区块抛了异常 / 加载失败"、
 * "每个区块跑完了几片" —— 调用方必须按它结账: 没跑完的区块不能当成功, 跑了一半的区块要能撤销
 * (见 [ChunkBatchResult])。
 *
 * 线程可见性: 每个区块的切片由同一条区域线程串行执行, 所以 [ChunkBatchResult.completed] 的每个
 * 下标只有一个写者; 收尾线程是通过 `remaining` 的 CAS / `notified` 的 CAS 拿到结果的, 之前的写入
 * 经由这两条原子操作建立 happens-before。看门狗线程那条路径 (超时) 只读一个快照值, 结论由
 * `timedOut` 主导, 调用方本来就按"失败"处理。
 */
fun runOnChunkThreads(
    regions: List<CuboidRegion>,
    world: World,
    work: (index: Int, slice: RegionSlice) -> Unit,
    done: (ChunkBatchResult) -> Unit
) {
    if (regions.isEmpty()) {
        done(ChunkBatchResult.empty())
        return
    }
    val slices = regions.map { it.sliceByY(EditBudget.sliceSize) }
    // 每个区块完整跑完的切片数 / 抛过异常(或区块加载失败)的区块
    val completed = IntArray(regions.size)
    val failed = ConcurrentHashMap.newKeySet<Int>()
    val waves = regions.indices.chunked(minOf(regions.size, CHUNK_LOAD_WAVE))
    var waveIndex = 0
    val notified = AtomicBoolean(false)
    // 超时收尾之后不再往区域线程排新切片: 这一批已经按"失败"结账, 再写下去既不会有撤销记录
    // 也不会退费, 那些改动就成了无主的
    val aborted = AtomicBoolean(false)
    // 每完成一片就刷新它; 计时器只盯"多久没有进展", 不盯"总共跑了多久"
    val lastProgress = AtomicLong(System.currentTimeMillis())
    var watchdog: PlatformExecutor.PlatformTask? = null

    fun complete(timedOut: Boolean) {
        if (!notified.compareAndSet(false, true)) {
            return
        }
        if (timedOut) {
            aborted.set(true)
        }
        watchdog?.let {
            watchdogs.remove(it)
            it.cancel()
        }
        done(ChunkBatchResult(timedOut, failed.toSet(), slices, completed.copyOf()))
    }

    // 真的写完一片才算"有进展": 失败的切片会直接 finish, 不该刷新计时
    val trackedWork: (Int, RegionSlice) -> Unit = { index, slice ->
        work(index, slice)
        completed[index]++
        lastProgress.set(System.currentTimeMillis())
    }

    fun nextWave() {
        // 已经超时收尾了就不用再排下一批 (排出来的任务也会因为 aborted 立刻结束)
        if (aborted.get()) {
            return
        }
        if (waveIndex >= waves.size) {
            complete(false)
            return
        }
        val wave = waves[waveIndex++]
        val remaining = AtomicInteger(wave.size)
        // 区块没跑成也要计数, 否则 done 永远不触发 (材料扣了却没有记录和提示)
        val completeOne = Runnable { if (remaining.decrementAndGet() == 0) nextWave() }
        wave.forEach { index ->
            ChunkTask(world, regions[index], slices[index], index, trackedWork, aborted, failed, completeOne).start()
        }
    }

    // 兜底: 区块加载卡死、区域调度器把任务静默丢掉……任何原因导致 done 迟迟不来时, 只要
    // [STALL_TIMEOUT_MILLIS] 内一片都没完成就强制收尾。盯"无进展"而不是"总时长"是因为插件不限制
    // 操作大小, 一个超大选区正常跑也可能超过 30 秒, 按总时长收尾会把正常的大操作误判成失败。
    watchdog = submitTask(now = false, async = false, delay = WATCHDOG_INTERVAL_TICKS, period = WATCHDOG_INTERVAL_TICKS) {
        if (System.currentTimeMillis() - lastProgress.get() > STALL_TIMEOUT_MILLIS) {
            complete(true)
        }
    }.also { watchdogs.add(it) }

    nextWave()
}

/**
 * 单个区块的切片流水线: 先确认区块已加载, 然后一片一片地排队执行, 每片都要先领全局预算。
 */
private class ChunkTask(
    private val world: World,
    region: CuboidRegion,
    private val slices: List<RegionSlice>,
    private val index: Int,
    private val work: (Int, RegionSlice) -> Unit,
    private val aborted: AtomicBoolean,
    private val failed: MutableSet<Int>,
    private val complete: Runnable
) {

    private val chunkX = region.minimumPoint.x shr 4
    private val chunkZ = region.minimumPoint.z shr 4
    private var position = 0
    private var finished = false

    fun start() {
        schedule(0)
    }

    private fun runCurrent() {
        if (finished) return
        // 整批已经超时收尾: 再写方块就是没人记录的改动, 直接停下
        if (aborted.get()) {
            finish()
            return
        }
        if (position >= slices.size) {
            finish()
            return
        }
        val slice = slices[position]
        // 领不到本 tick 的预算就下一 tick 再来
        if (!EditBudget.tryConsume(slice.size)) {
            schedule(1)
            return
        }
        try {
            work(index, slice)
            position++
        } catch (throwable: Throwable) {
            // 抛异常 = 这一片只做了一半 (读可能成了、写没成): 记下这个区块, 由调用方按"部分完成"结账
            failed.add(index)
            Bukkit.getLogger().warning("[PlayerWorldEdit] 区块操作失败 ${world.name} $chunkX,$chunkZ: $throwable")
            finish()
            return
        }
        if (position >= slices.size) {
            finish()
            return
        }
        schedule(0)
    }

    /**
     * 排下一片
     *
     * 每次都要先确认区块是加载着的: 切片之间区块可能被卸载 (附近没人了), 这时候
     * `world.runTask` 排的任务会被区域调度器一直压着 —— 它不会主动去加载区块, 整批就会
     * 卡到看门狗超时才收尾。start() 当初就是靠这个假设, 这里保持一致。
     *
     * @param delayTicks 0 表示立刻排到该区块所属的区域线程
     */
    private fun schedule(delayTicks: Long) {
        // 区块加载不出来也要标成失败: 否则这个区块"一片没跑"却和其他区块一样算跑完, 调用方会把
        // 没写成的区块当成写成功 (材料不退、撤销记录里还留着它)
        loadChunkThen(world, chunkX, chunkZ, { dispatch(delayTicks) }, { failed.add(index); finish() })
    }

    private fun dispatch(delayTicks: Long) {
        if (delayTicks <= 0) {
            world.runTask(chunkX * 16.0, chunkZ * 16.0, Runnable { runCurrent() })
        } else {
            world.submit(chunkX * 16.0, chunkZ * 16.0, delay = delayTicks) { runCurrent() }
        }
    }

    private fun finish() {
        if (!finished) {
            finished = true
            complete.run()
        }
    }
}

/**
 * 回到实体 (玩家) 所在线程执行
 *
 * 注意 TabooLib 的 runTask 最后一个参数是 useScheduler, 尾随 lambda 会被当成它, 所以这里包一层。
 */
fun Entity.runOnOwnThread(executor: () -> Unit) {
    runTask(Runnable { executor() })
}

/** Paper 的异步区块加载, Folia 上必用; 编译用的 API 里没有这个方法, 只能运行时反射 */
private val chunkLoader: Method? by lazy {
    runCatching {
        World::class.java.getMethod("getChunkAtAsync", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }.getOrNull()
}

/**
 * 区块加载完成后再执行 [then]。
 *
 * Folia 的区域调度器对**没加载的区块会静默不执行** (任务一直排队, 直到区块被加载),
 * 所以先把区块异步加载出来。非 Folia 环境没有这个问题, 直接执行 —— 那条分支不能碰
 * Paper 独有 API。[then] 里只做"排任务", 真正干活仍然发生在区块所属的区域线程上。
 *
 * 加载不了区块时一定要走 [onFailed]: 在 Folia 上直接排任务会被静默丢弃, 任务永远不完成,
 * 调用方的 done 也就永远不触发 (材料扣了、不退费、没有撤销记录, 玩家还卡在 editing)。
 */
private fun loadChunkThen(world: World, chunkX: Int, chunkZ: Int, then: () -> Unit, onFailed: () -> Unit) {
    if (!Folia.isFolia) {
        then()
        return
    }
    val loader = chunkLoader
    if (loader == null) {
        // 编译用的 API 里没有 World.getChunkAtAsync, 只能运行时反射。拿不到就说明这个服务端
        // 没有这个方法 (核心换了实现), 无法保证区块已加载 —— 明确失败, 让调用方退费收尾。
        Bukkit.getLogger().warning(
            "[PlayerWorldEdit] 反射不到 World.getChunkAtAsync, Folia 上无法加载区块, 本次操作按失败处理: ${world.name} $chunkX,$chunkZ"
        )
        onFailed()
        return
    }
    val future = runCatching { loader.invoke(world, chunkX, chunkZ) as? CompletableFuture<*> }.getOrNull()
    if (future == null) {
        // 方法在但调用没成 (参数不匹配/返回值不是 CompletableFuture): 同样是加载不了区块
        Bukkit.getLogger().warning(
            "[PlayerWorldEdit] 调用 World.getChunkAtAsync 失败, 本次操作按失败处理: ${world.name} $chunkX,$chunkZ"
        )
        onFailed()
        return
    }
    future.whenComplete { _, error ->
        if (error == null) {
            then()
        } else {
            Bukkit.getLogger().warning("[PlayerWorldEdit] 区块加载失败 ${world.name} $chunkX,$chunkZ: $error")
            onFailed()
        }
    }
}

/** 一次同时加载的区块数量上限 */
private const val CHUNK_LOAD_WAVE = 64

/** 多久没有任何切片完成就认为这一批卡死了 */
private const val STALL_TIMEOUT_MILLIS = 30_000L

/** 检查"还有没有进展"的间隔 (1 秒) */
private const val WATCHDOG_INTERVAL_TICKS = 20L

/** 正在等超时收尾的批次计时器 (插件卸载时要能停掉) */
private val watchdogs = ConcurrentHashMap.newKeySet<PlatformExecutor.PlatformTask>()

/** 停掉所有超时兜底计时器 (插件 disable 时调用) */
fun cancelWatchdogs() {
    watchdogs.forEach { it.cancel() }
    watchdogs.clear()
}

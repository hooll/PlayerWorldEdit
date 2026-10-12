package cn.stingcraft.plugin.until

import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import taboolib.platform.Folia
import taboolib.platform.util.runTask
import taboolib.platform.util.submit
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** 区块切片: 一个区块沿 Y 轴切出来的一片, [offset]/[size] 是它在整块快照里的下标区间 */
class RegionSlice(val region: CuboidRegion, val offset: Int, val size: Int)

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
 */
fun runOnChunkThreads(
    regions: List<CuboidRegion>,
    world: World,
    work: (index: Int, slice: RegionSlice) -> Unit,
    done: () -> Unit
) {
    if (regions.isEmpty()) {
        done()
        return
    }
    val slices = regions.map { it.sliceByY(EditBudget.sliceSize) }
    val waves = regions.indices.chunked(minOf(regions.size, CHUNK_LOAD_WAVE))
    var waveIndex = 0
    var notified = false
    fun nextWave() {
        if (waveIndex >= waves.size) {
            if (!notified) {
                notified = true
                done()
            }
            return
        }
        val wave = waves[waveIndex++]
        val remaining = AtomicInteger(wave.size)
        // 区块没跑成也要计数, 否则 done 永远不触发 (材料扣了却没有记录和提示)
        val completeOne = Runnable { if (remaining.decrementAndGet() == 0) nextWave() }
        wave.forEach { index ->
            ChunkTask(world, regions[index], slices[index], index, work, completeOne).start()
        }
    }
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
    private val complete: Runnable
) {

    private val chunkX = region.minimumPoint.x shr 4
    private val chunkZ = region.minimumPoint.z shr 4
    private var position = 0
    private var finished = false

    fun start() {
        loadChunkThen(world, chunkX, chunkZ, { schedule(0) }, { finish() })
    }

    private fun runCurrent() {
        if (finished) return
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
     * @param delayTicks 0 表示立刻排到该区块所属的区域线程
     */
    private fun schedule(delayTicks: Long) {
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
 */
private fun loadChunkThen(world: World, chunkX: Int, chunkZ: Int, then: () -> Unit, onFailed: () -> Unit) {
    val loader = if (Folia.isFolia) chunkLoader else null
    if (loader == null) {
        then()
        return
    }
    val future = runCatching { loader.invoke(world, chunkX, chunkZ) as? CompletableFuture<*> }.getOrNull()
    if (future == null) {
        then()
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

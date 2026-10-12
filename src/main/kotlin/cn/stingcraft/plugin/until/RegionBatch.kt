package cn.stingcraft.plugin.until

import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Entity
import taboolib.platform.Folia
import taboolib.platform.util.runTask
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

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
 * 在拥有每个区块的线程上执行 [work] (参数是区块在 [regions] 里的下标), 全部结束后调用 [done]。
 *
 * 单个区块抛错不会影响其余区块, 异常直接打到控制台; [done] 在最后一个完成的线程上运行,
 * 所以里面不要再碰玩家数据, 用 [runOnOwnThread] 切回玩家线程。
 */
fun runOnChunkThreads(regions: List<CuboidRegion>, world: World, work: (Int) -> Unit, done: () -> Unit) {
    if (regions.isEmpty()) {
        done()
        return
    }
    val remaining = AtomicInteger(regions.size)
    // 区块没跑成也要计数, 否则 done 永远不触发 (材料扣了却没有记录和提示)
    val completeOne = Runnable { if (remaining.decrementAndGet() == 0) done() }
    regions.forEachIndexed { index, region ->
        val min = region.minimumPoint
        val task = Runnable {
            try {
                work(index)
            } catch (throwable: Throwable) {
                Bukkit.getLogger().warning("[PlayerWorldEdit] 区块操作失败 ${world.name} ${min.x},${min.z}: $throwable")
            } finally {
                completeOne.run()
            }
        }
        scheduleOnChunkThread(world, min.x shr 4, min.z shr 4, task, completeOne)
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
 * 把任务排到指定区块所属的线程上。
 *
 * Folia 的区域调度器对**没加载的区块会静默不执行** (任务一直排队, 直到区块被加载),
 * 所以先把区块异步加载出来, 加载完成后再排区域任务。
 * 非 Folia 环境没有这个问题, 直接排主线程 —— 那条分支不能碰 Paper 独有 API。
 */
private fun scheduleOnChunkThread(world: World, chunkX: Int, chunkZ: Int, task: Runnable, onFailed: Runnable) {
    val x = chunkX.toDouble() * 16
    val z = chunkZ.toDouble() * 16
    val loader = if (Folia.isFolia) chunkLoader else null
    if (loader == null) {
        world.runTask(x, z, task)
        return
    }
    val future = runCatching { loader.invoke(world, chunkX, chunkZ) as? CompletableFuture<*> }.getOrNull()
    if (future == null) {
        world.runTask(x, z, task)
        return
    }
    future.whenComplete { _, error ->
        if (error == null) {
            world.runTask(x, z, task)
        } else {
            Bukkit.getLogger().warning("[PlayerWorldEdit] 区块加载失败 ${world.name} $chunkX,$chunkZ: $error")
            onFailed.run()
        }
    }
}

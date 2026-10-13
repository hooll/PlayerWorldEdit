package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import com.google.gson.reflect.TypeToken
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.registry.state.Property
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockTypes
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning
import taboolib.expansion.getAutoDataContainer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 一次操作中单个区块的原始方块快照 */
class UndoBatch(val region: CuboidRegion, val states: List<BlockState>)

/**
 * 撤销记录
 *
 * 按区块分开存: 还原时每个区块要在它自己的区域线程上写入, 而且个别区块执行失败
 * 也不会让整条记录错位。
 */
class UndoRecord(
    val worldName: String,
    val batches: List<UndoBatch>,
    val material: Material,
    val amount: Int
)

/**
 * 撤销记录
 *
 * 一条记录一个文件: `plugins/PlayerWorldEdit/undo/<uuid>/<时间戳>.json.gz`。
 *
 * 以前存在玩家数据 (data.db) 里, 于是有两个绕不过去的问题: 几十万方块的操作会把玩家那一行
 * 撑爆, 而且每次读写都要把**整段历史**解析+重新序列化一遍。所以那时只能加"操作过大就不记"
 * 的上限 —— 代价是操作不可逆。改成文件之后只读写当前这一条, 也就没有上限了。
 */
object UndoManager {

    /** 旧版本把记录写在玩家数据的这个键里, 第一次用到时会迁移过来 */
    private const val LEGACY_KEY = "undoRecords"

    // 同一个玩家的读写不能交叉
    private val locks = ConcurrentHashMap<UUID, Any>()
    private val migrated = ConcurrentHashMap.newKeySet<UUID>()

    /** 同一毫秒内写多条时用来区分文件名 */
    private val sequence = AtomicLong()

    /**
     * 待撤销队列 (最新在尾), 只在 [lockOf] 的锁里访问。
     *
     * 队列让**入队**这一步在玩家线程上同步完成, 落盘才丢给异步线程 —— 于是"填充完立刻撤销"
     * 一定能先看到刚入队的这条。原来整个 push 都在线程池里, 玩家立刻 poll 时可能先拿到锁,
     * 读到的是上一条记录, 甚至因为文件还没落盘而什么都读不到。
     */
    private val queues = ConcurrentHashMap<UUID, ArrayDeque<QueuedRecord>>()

    /** 已经被 poll 取走、但写盘可能还没结束的记录, 键是 "uuid/文件名" */
    private val consumed = ConcurrentHashMap.newKeySet<String>()

    /** 队列里的一条记录: 落盘之前带着内存里的快照, 落盘之后只留文件名 */
    private class QueuedRecord(val name: String, @Volatile var record: UndoRecord?)

    /**
     * 记下一条撤销记录: 同步入队 + 异步落盘
     */
    fun pushAsync(player: Player, record: UndoRecord, limit: Int) {
        val uuid = player.uniqueId
        val queued = enqueue(uuid, record, limit)
        submitAsync {
            runCatching { writeToDisk(uuid, queued, limit) }.onFailure {
                warning("[PlayerWorldEdit] 写入撤销记录失败: $it")
            }
        }
    }

    /**
     * 同步版本 —— "世界未加载, 把记录放回去" 用
     */
    fun push(player: Player, record: UndoRecord, limit: Int) {
        val uuid = player.uniqueId
        val queued = enqueue(uuid, record, limit)
        runCatching { writeToDisk(uuid, queued, limit) }.onFailure {
            warning("[PlayerWorldEdit] 写入撤销记录失败: $it")
        }
    }

    /**
     * 取出最近一条撤销记录 (取走即删除), 没有则返回 null
     *
     * 优先从内存队列拿; 队列空了才回落到磁盘 —— 服务端重启后队列是空的, 记录仍然在文件里,
     * 所以这条回落路径就是"从磁盘重建队列"。
     */
    fun poll(player: Player): UndoRecord? {
        val uuid = player.uniqueId
        return synchronized(lockOf(uuid)) {
            migrateLegacy(uuid)
            val queued = queueOf(uuid).removeLastOrNull()
            if (queued != null) {
                return@synchronized takeQueued(uuid, queued)
            }
            // 跳过"已被取走、但文件还没删掉"的记录: 写盘线程在写完到删文件之间会短暂留下文件,
            // 不跳过的话"编辑后立刻 /pwe undo 2"会把刚撤销过的那条又撤销一次
            val newest = listRecords(uuid).lastOrNull { !consumed.contains(consumedKey(uuid, it.name)) }
                ?: return@synchronized null
            val record = readRecord(newest)
            newest.delete()
            record
        }
    }

    /** 从队列里取走一条: 内存里还留着快照就直接给, 已经落盘则从文件读回来 */
    private fun takeQueued(uuid: UUID, queued: QueuedRecord): UndoRecord? {
        val inMemory = queued.record
        if (inMemory != null) {
            // 写盘可能还没结束: 记一笔, 让写盘线程写完自己把文件删掉
            consumed.add(consumedKey(uuid, queued.name))
            queued.record = null
            return inMemory
        }
        val file = File(dirOf(uuid), queued.name)
        val record = readRecord(file)
        file.delete()
        return record
    }

    /** 同步入队, 顺带把超出 [limit] 的旧记录裁掉 */
    private fun enqueue(uuid: UUID, record: UndoRecord, limit: Int): QueuedRecord {
        synchronized(lockOf(uuid)) {
            migrateLegacy(uuid)
            val queue = queueOf(uuid)
            val keep = limit.coerceAtLeast(1)
            while (queue.size >= keep) {
                drop(uuid, queue.removeFirst())
            }
            return QueuedRecord(nextFileName(), record).also { queue.addLast(it) }
        }
    }

    /** 丢掉一条还没被撤销的记录 (连同它已经落盘的文件) */
    private fun drop(uuid: UUID, queued: QueuedRecord) {
        if (queued.record != null) {
            // 还没落盘: 让写盘线程别写了
            consumed.add(consumedKey(uuid, queued.name))
            queued.record = null
        } else {
            File(dirOf(uuid), queued.name).delete()
        }
    }

    /**
     * 落盘并释放内存里的快照
     *
     * 写盘期间这条记录可能已经被撤销取走了, 那样必须把自己写的文件删掉, 否则那些方块状态
     * 在服务端重启后会被当成一条有效的撤销记录, 让玩家把已经还原过的区域再还原一次。
     */
    private fun writeToDisk(uuid: UUID, queued: QueuedRecord, limit: Int) {
        val key = consumedKey(uuid, queued.name)
        val record = queued.record
        if (record == null) {
            // 写盘之前快照就被取走/丢掉了 (takeQueued / drop 会置空 record 并登记 key):
            // 这条不用写了, 但键必须收掉, 否则 consumed 只增不减
            consumed.remove(key)
            return
        }
        // 入队后立刻被撤销取走了: 不用写了
        if (consumed.remove(key)) {
            queued.record = null
            return
        }
        val dir = dirOf(uuid).apply { mkdirs() }
        val file = File(dir, queued.name)
        try {
            GZIPOutputStream(file.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
                // 流式写, 不先拼一个几十 MB 的字符串出来
                PlayerWorldEdit.gson.toJson(record.toStored(), writer)
            }
        } catch (throwable: Throwable) {
            // 写盘失败也要把键收掉 (记录还在队列里, 撤销照旧可用)
            consumed.remove(key)
            throw throwable
        }
        queued.record = null
        if (consumed.contains(key)) {
            // 写盘期间被取走: **先删文件再清键** —— 反过来的话会留下"键已清、文件还在"的窗口,
            // poll 的磁盘回落就会把这条已消费的记录再当成有效记录撤销一次
            file.delete()
            consumed.remove(key)
            return
        }
        trim(dir, limit)
    }

    /**
     * 只保留最近 [limit] 条
     */
    private fun trim(dir: File, limit: Int) {
        val keep = limit.coerceAtLeast(1)
        listFiles(dir).dropLast(keep).forEach { it.delete() }
    }

    private fun listRecords(uuid: UUID): List<File> = listFiles(dirOf(uuid))

    /** 按文件名排序 (文件名以 13 位时间戳开头), 也就是从旧到新 */
    private fun listFiles(dir: File): List<File> {
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".json.gz") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun readRecord(file: File): UndoRecord? {
        return runCatching {
            GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                PlayerWorldEdit.gson.fromJson(reader, StoredUndoRecord::class.java).toRecord()
            }
        }.onFailure {
            warning("[PlayerWorldEdit] 读取撤销记录失败 ${file.name}: $it")
        }.getOrNull()
    }

    private fun nextFileName(): String =
        "%013d-%05d.json.gz".format(System.currentTimeMillis(), sequence.incrementAndGet() % 100000)

    private fun dirOf(uuid: UUID): File = File(File(getDataFolder(), "undo"), uuid.toString())

    private fun lockOf(uuid: UUID): Any = locks.computeIfAbsent(uuid) { Any() }

    private fun queueOf(uuid: UUID): ArrayDeque<QueuedRecord> = queues.computeIfAbsent(uuid) { ArrayDeque() }

    private fun consumedKey(uuid: UUID, name: String): String = "$uuid/$name"

    /**
     * 把旧版本存在玩家数据里的记录搬成文件, 每个玩家只做一次
     */
    private fun migrateLegacy(uuid: UUID) {
        if (!migrated.add(uuid)) {
            return
        }
        runCatching {
            val container = uuid.getAutoDataContainer()
            val raw = container[LEGACY_KEY] ?: return@runCatching
            if (raw.isEmpty()) {
                return@runCatching
            }
            val type = object : TypeToken<List<StoredUndoRecord>>() {}.type
            val legacy: List<StoredUndoRecord> = PlayerWorldEdit.gson.fromJson(raw, type)
            if (legacy.isEmpty()) {
                return@runCatching
            }
            val dir = dirOf(uuid).apply { mkdirs() }
            legacy.forEach { stored ->
                runCatching {
                    val file = File(dir, nextFileName())
                    GZIPOutputStream(file.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
                        PlayerWorldEdit.gson.toJson(stored, writer)
                    }
                }
            }
            container[LEGACY_KEY] = ""
        }.onFailure {
            warning("[PlayerWorldEdit] 迁移旧撤销记录失败: $it")
        }
    }

    private fun UndoRecord.toStored(): StoredUndoRecord {
        return StoredUndoRecord(
            world = worldName,
            material = material.name,
            amount = amount,
            batches = batches.map { batch ->
                StoredUndoBatch(
                    minX = batch.region.minimumPoint.x,
                    minY = batch.region.minimumPoint.y,
                    minZ = batch.region.minimumPoint.z,
                    maxX = batch.region.maximumPoint.x,
                    maxY = batch.region.maximumPoint.y,
                    maxZ = batch.region.maximumPoint.z,
                    states = batch.states.map { it.getAsString() }
                )
            }
        )
    }
}

/**
 * 解析 getAsString() 存下的方块状态字符串, 如 "minecraft:oak_log[axis=y]"
 * 解析失败时抛异常 (与旧版 BlockState.get(String) 行为一致, 由调用方兜底)
 */
@Suppress("UNCHECKED_CAST")
private fun parseBlockState(raw: String): BlockState {
    val type = BlockTypes.get(raw.substringBefore('['))
        ?: throw IllegalArgumentException("Unknown block type: $raw")
    val properties = HashMap<Property<*>, Any>()
    val propsRaw = raw.substringAfter('[', "").substringBefore(']')
    if (propsRaw.isNotBlank()) {
        for (pair in propsRaw.split(',')) {
            val property = type.propertyMap[pair.substringBefore('=').trim()] ?: continue
            val value = (property as Property<Any>).getValueFor(pair.substringAfter('=', "").trim()) ?: continue
            properties[property] = value
        }
    }
    return type.getState(properties)
}

private data class StoredUndoBatch(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    val states: List<String>
) {

    fun toBatch(): UndoBatch {
        return UndoBatch(
            CuboidRegion(BlockVector3.at(minX, minY, minZ), BlockVector3.at(maxX, maxY, maxZ)),
            states.map { parseBlockState(it) }
        )
    }
}

private data class StoredUndoRecord(
    val world: String,
    val material: String,
    val amount: Int,
    val batches: List<StoredUndoBatch>
) {

    fun toRecord(): UndoRecord {
        return UndoRecord(world, batches.map { it.toBatch() }, Material.valueOf(material), amount)
    }
}

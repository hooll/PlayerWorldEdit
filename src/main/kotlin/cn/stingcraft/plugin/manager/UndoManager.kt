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
     * 把撤销记录写下来 —— 序列化 + gzip + 落盘都在异步线程完成
     *
     * 大选区动辄几十万个方块, 在玩家线程上把这些方块全转成字符串再压缩, 玩家所在区域
     * 那一 tick 会被卡住好几秒。
     */
    fun pushAsync(player: Player, record: UndoRecord, limit: Int) {
        val uuid = player.uniqueId
        submitAsync {
            runCatching {
                synchronized(lockOf(uuid)) { push(uuid, record, limit) }
            }.onFailure {
                warning("[PlayerWorldEdit] 写入撤销记录失败: $it")
            }
        }
    }

    fun push(player: Player, record: UndoRecord, limit: Int) {
        val uuid = player.uniqueId
        synchronized(lockOf(uuid)) { push(uuid, record, limit) }
    }

    /**
     * 取出最近一条撤销记录 (取走即删除), 没有则返回 null
     */
    fun poll(player: Player): UndoRecord? {
        val uuid = player.uniqueId
        return synchronized(lockOf(uuid)) {
            migrateLegacy(uuid)
            val newest = listRecords(uuid).lastOrNull() ?: return@synchronized null
            val record = runCatching {
                GZIPInputStream(newest.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                    PlayerWorldEdit.gson.fromJson(reader, StoredUndoRecord::class.java).toRecord()
                }
            }.onFailure {
                warning("[PlayerWorldEdit] 读取撤销记录失败 ${newest.name}: $it")
            }.getOrNull()
            newest.delete()
            record
        }
    }

    private fun push(uuid: UUID, record: UndoRecord, limit: Int) {
        migrateLegacy(uuid)
        val dir = dirOf(uuid).apply { mkdirs() }
        val file = newRecordFile(dir)
        runCatching {
            GZIPOutputStream(file.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
                // 流式写, 不先拼一个几十 MB 的字符串出来
                PlayerWorldEdit.gson.toJson(record.toStored(), writer)
            }
        }.onFailure {
            warning("[PlayerWorldEdit] 写入撤销记录失败: $it")
            file.delete()
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

    private fun newRecordFile(dir: File): File {
        val stamp = "%013d-%05d".format(System.currentTimeMillis(), sequence.incrementAndGet() % 100000)
        return File(dir, "$stamp.json.gz")
    }

    private fun dirOf(uuid: UUID): File = File(File(getDataFolder(), "undo"), uuid.toString())

    private fun lockOf(uuid: UUID): Any = locks.computeIfAbsent(uuid) { Any() }

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
                    val file = newRecordFile(dir)
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

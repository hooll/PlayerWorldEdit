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
import taboolib.expansion.getAutoDataContainer
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

object UndoManager {

    private const val KEY = "undoRecords"

    // 撤销记录的读写都在异步线程上做, 同一玩家的两次操作不能交叉
    private val locks = ConcurrentHashMap<UUID, Any>()

    /**
     * 把撤销记录写进玩家数据 —— 序列化 + gzip + 写库都在异步线程完成
     *
     * 大选区一次几万个方块, 之前是在玩家线程上把这些方块全转成字符串再压缩写库,
     * 玩家所在区域那一 tick 会被卡住一两秒。
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

    private fun push(uuid: UUID, record: UndoRecord, limit: Int) {
        val list = read(uuid).toMutableList()
        list.add(record.toStored())
        while (list.size > limit) {
            list.removeAt(0)
        }
        write(uuid, list)
    }

    fun poll(player: Player): UndoRecord? {
        val uuid = player.uniqueId
        return synchronized(lockOf(uuid)) {
            val list = read(uuid).toMutableList()
            if (list.isEmpty()) {
                return@synchronized null
            }
            val stored = list.removeAt(list.size - 1)
            write(uuid, list)
            runCatching { stored.toRecord() }.getOrNull()
        }
    }

    private fun lockOf(uuid: UUID): Any = locks.computeIfAbsent(uuid) { Any() }

    /**
     * 撤销记录存在玩家数据容器中, 与材料背包共用 data.db
     */
    private fun read(uuid: UUID): List<StoredUndoRecord> {
        val raw = uuid.getAutoDataContainer()[KEY] ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<StoredUndoRecord>>() {}.type
            PlayerWorldEdit.gson.fromJson<List<StoredUndoRecord>>(decompressFromBase64(raw), type)
        }.getOrDefault(emptyList())
    }

    private fun write(uuid: UUID, list: List<StoredUndoRecord>) {
        val container = uuid.getAutoDataContainer()
        if (list.isEmpty()) {
            container[KEY] = ""
        } else {
            container[KEY] = compressToBase64(PlayerWorldEdit.gson.toJson(list))
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

    private fun compressToBase64(json: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun decompressFromBase64(base64: String): String {
        val bytes = Base64.getDecoder().decode(base64)
        return GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
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

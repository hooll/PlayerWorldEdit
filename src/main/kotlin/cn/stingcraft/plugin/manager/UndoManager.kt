package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import com.fastasyncworldedit.core.FaweAPI
import com.google.gson.reflect.TypeToken
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import org.bukkit.Material
import taboolib.common.LifeCycle
import taboolib.common.io.newFile
import taboolib.common.platform.Awake
import taboolib.common.platform.function.getDataFolder
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class UndoRecord(
    val worldName: String,
    val region: CuboidRegion,
    val states: List<BlockState>,
    val material: Material,
    val amount: Int
)

object UndoManager {

    private val records = mutableMapOf<UUID, ArrayDeque<UndoRecord>>()

    private var storage: File? = null

    @Awake(LifeCycle.ENABLE)
    fun init() {
        storage = newFile(getDataFolder(), "undo-data.json.gz")
        load()
    }

    /**
     * 记录区域当前方块快照
     * @return 快照, 失败时返回 null
     */
    fun snapshot(worldName: String, region: CuboidRegion, material: Material): UndoRecord? = runCatching {
        val weWorld = FaweAPI.getWorld(worldName)
        val states = ArrayList<BlockState>(region.size)
        for (vector in region) {
            states.add(weWorld.getBlock(vector))
        }
        UndoRecord(worldName, region, states, material, region.size)
    }.getOrNull()

    fun push(playerId: UUID, record: UndoRecord, limit: Int) {
        val deque = records.getOrPut(playerId) { ArrayDeque() }
        deque.addLast(record)
        while (deque.size > limit) {
            deque.removeFirst()
        }
        save()
    }

    fun poll(playerId: UUID): UndoRecord? {
        val deque = records[playerId] ?: return null
        val record = deque.removeLastOrNull()
        if (deque.isEmpty()) {
            records.remove(playerId)
        }
        return record
    }

    /**
     * 把撤销记录写入磁盘, 服务器重启后仍然可用
     */
    fun save() {
        val file = storage ?: return
        synchronized(this) {
            runCatching {
                val data = records.entries.associate { (id, deque) ->
                    id.toString() to deque.map { record ->
                        StoredUndoRecord(
                            world = record.worldName,
                            minX = record.region.minimumPoint.x,
                            minY = record.region.minimumPoint.y,
                            minZ = record.region.minimumPoint.z,
                            maxX = record.region.maximumPoint.x,
                            maxY = record.region.maximumPoint.y,
                            maxZ = record.region.maximumPoint.z,
                            material = record.material.name,
                            amount = record.amount,
                            states = record.states.map { it.getAsString() }
                        )
                    }
                }
                GZIPOutputStream(file.outputStream()).use { output ->
                    output.write(PlayerWorldEdit.gson.toJson(data).toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun load() {
        val file = storage ?: return
        if (!file.isFile || file.length() == 0L) {
            return
        }
        runCatching {
            val json = GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val type = object : TypeToken<Map<String, List<StoredUndoRecord>>>() {}.type
            val data: Map<String, List<StoredUndoRecord>> = PlayerWorldEdit.gson.fromJson(json, type)
            data.forEach { (id, list) ->
                val playerId = runCatching { UUID.fromString(id) }.getOrNull() ?: return@forEach
                val deque = ArrayDeque<UndoRecord>()
                list.forEach { stored ->
                    runCatching { stored.toRecord() }.getOrNull()?.let { deque.addLast(it) }
                }
                if (deque.isNotEmpty()) {
                    records[playerId] = deque
                }
            }
        }
    }
}

private data class StoredUndoRecord(
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    val material: String,
    val amount: Int,
    val states: List<String>
) {

    fun toRecord(): UndoRecord {
        return UndoRecord(
            world,
            CuboidRegion(BlockVector3.at(minX, minY, minZ), BlockVector3.at(maxX, maxY, maxZ)),
            states.map { BlockState.get(it) },
            Material.valueOf(material),
            amount
        )
    }
}

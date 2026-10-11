package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import com.fastasyncworldedit.core.FaweAPI
import com.google.gson.reflect.TypeToken
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.expansion.getAutoDataContainer
import java.io.ByteArrayOutputStream
import java.util.Base64
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

    private const val KEY = "undoRecords"

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

    fun push(player: Player, record: UndoRecord, limit: Int) {
        val list = read(player).toMutableList()
        list.add(record.toStored())
        while (list.size > limit) {
            list.removeAt(0)
        }
        write(player, list)
    }

    fun poll(player: Player): UndoRecord? {
        val list = read(player).toMutableList()
        if (list.isEmpty()) {
            return null
        }
        val stored = list.removeAt(list.size - 1)
        write(player, list)
        return runCatching { stored.toRecord() }.getOrNull()
    }

    /**
     * 撤销记录存在玩家数据容器中, 与材料背包共用 data.db
     */
    private fun read(player: Player): List<StoredUndoRecord> {
        val raw = player.uniqueId.getAutoDataContainer()[KEY] ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<StoredUndoRecord>>() {}.type
            PlayerWorldEdit.gson.fromJson<List<StoredUndoRecord>>(decompressFromBase64(raw), type)
        }.getOrDefault(emptyList())
    }

    private fun write(player: Player, list: List<StoredUndoRecord>) {
        val container = player.uniqueId.getAutoDataContainer()
        if (list.isEmpty()) {
            container[KEY] = ""
        } else {
            container[KEY] = compressToBase64(PlayerWorldEdit.gson.toJson(list))
        }
    }

    private fun UndoRecord.toStored(): StoredUndoRecord {
        return StoredUndoRecord(
            world = worldName,
            minX = region.minimumPoint.x,
            minY = region.minimumPoint.y,
            minZ = region.minimumPoint.z,
            maxX = region.maximumPoint.x,
            maxY = region.maximumPoint.y,
            maxZ = region.maximumPoint.z,
            material = material.name,
            amount = amount,
            states = states.map { it.getAsString() }
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

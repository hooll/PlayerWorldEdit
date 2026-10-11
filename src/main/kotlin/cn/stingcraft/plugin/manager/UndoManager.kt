package cn.stingcraft.plugin.manager

import com.fastasyncworldedit.core.FaweAPI
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import org.bukkit.Material
import java.util.UUID

class UndoRecord(
    val worldName: String,
    val region: CuboidRegion,
    val states: List<BlockState>,
    val material: Material,
    val amount: Int
)

object UndoManager {

    private val records = mutableMapOf<UUID, ArrayDeque<UndoRecord>>()

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
    }

    fun poll(playerId: UUID): UndoRecord? {
        val deque = records[playerId] ?: return null
        val record = deque.removeLastOrNull()
        if (deque.isEmpty()) {
            records.remove(playerId)
        }
        return record
    }
}

package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
import cn.stingcraft.plugin.database.MaterialPackDataBase
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MaterialPackManager {

    /**
     * 材料包是"读 → 改 → 全量写库"的复合操作, 而它会被三种线程调用:
     * 玩家线程 (取物 / 扣费)、玩家所在区域线程 (退费)、异步线程 (添加材料)。
     * 复合操作非原子, 并发时后写会覆盖先写 —— 材料凭空消失或多出来。
     *
     * 用 per-player 锁把同一个玩家的读改写串成一条线, 不同玩家之间互不阻塞。
     */
    private val locks = ConcurrentHashMap<UUID, Any>()

    private fun lockOf(player: Player): Any = locks.computeIfAbsent(player.uniqueId) { Any() }

    /**
     * 只读快照。
     *
     * 返回的是**深拷贝**: `MaterialPack` 的 `copy()` 是浅拷贝 —— 副本与刚从库里反序列化出来的
     * 对象共享同一个 `MutableSet` 和同一批 `MaterialEntry`, 调用方在锁外改副本就等于改原件,
     * 锁白加。这里逐个复制 entry, 让返回值与内部状态彻底脱钩 (材料包将来改成内存缓存后,
     * 这一点是那把锁能成立的前提)。
     *
     * 真正的读改写一律在锁内直接用 [MaterialPackDataBase] 完成。
     */
    fun getMaterialPack(player: Player): MaterialPack {
        return synchronized(lockOf(player)) {
            val materialPack = MaterialPackDataBase.getMaterialPack(player)
            MaterialPack(materialPack.materials.mapTo(mutableSetOf()) { it.copy() })
        }
    }

    /**
     * 整包写库。
     *
     * 对外保留这个方法 (锁内执行), 但它接受的是调用方传进来的包 —— 传一个过期的包进来
     * 依然会覆盖掉别人的改动, 所以插件内部一律走 [addMaterial] / [removeMaterial] /
     * [tryConsume] 这些"读改写都在锁里"的方法。
     */
    fun saveMaterialPack(player: Player, materialPack: MaterialPack) {
        synchronized(lockOf(player)) {
            MaterialPackDataBase.saveMaterialPack(player, materialPack)
        }
    }

    /**
     * 添加物品列表
     * @param player 玩家
     * @param materials 物品列表
     */
    fun addMaterials(player: Player,materials: List<MaterialEntry>){
        synchronized(lockOf(player)) {
            val materialPack = MaterialPackDataBase.getMaterialPack(player)
            val packMaterials = materialPack.materials
            materials.forEach { newEntry->
                val existing = packMaterials.find { it.material == newEntry.material }
                if (existing != null) {
                    existing.amount += newEntry.amount
                } else {
                    packMaterials.add(MaterialEntry(newEntry.material, newEntry.amount))
                }
            }
            MaterialPackDataBase.saveMaterialPack(player, materialPack)
        }
    }

    /**
     * 添加物品
     * @param player 玩家
     * @param material 物品
     * @param amount 数量
     */
    fun addMaterial(player: Player, material: Material, amount: Int) {
        if (amount <= 0) return
        synchronized(lockOf(player)) {
            val materialPack = MaterialPackDataBase.getMaterialPack(player)
            val materialEntry = materialPack.materials.find { it.material == material } ?:run {
                val newMaterialEntry = MaterialEntry(material, amount)
                materialPack.materials.add(newMaterialEntry)
                MaterialPackDataBase.saveMaterialPack(player, materialPack)
                return
            }
            materialEntry.amount += amount
            MaterialPackDataBase.saveMaterialPack(player, materialPack)
        }
    }

    /**
     * 移除物品
     * @param player 玩家
     * @param material 物品
     * @param amount 数量
     */
    fun removeMaterial(player: Player, material: Material, amount: Int) {
        synchronized(lockOf(player)) {
            val materialPack = MaterialPackDataBase.getMaterialPack(player)
            val iterator = materialPack.materials.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.material == material) {
                    entry.amount -= amount
                    if (entry.amount <= 0) {
                        iterator.remove()
                    }
                    break
                }
            }
            MaterialPackDataBase.saveMaterialPack(player, materialPack)
        }
    }

    /**
     * 原子地"检查并扣除"。
     *
     * 调用方原来是 hasEnoughMaterial(读) 和扣费(读改写) 两次独立调用, 两次之间
     * 别的线程也能通过检查 —— 同一个玩家的两个操作会各扣一次, 材料被扣成负数。
     * 检查与扣除必须在同一把锁里完成。
     *
     * 空气不消耗材料; 余额不足返回 false 且不做任何改动。
     *
     * @return 是否扣成功
     */
    fun tryConsume(player: Player, material: Material, amount: Int): Boolean {
        if (material.isAir || amount <= 0) return true
        synchronized(lockOf(player)) {
            val materialPack = MaterialPackDataBase.getMaterialPack(player)
            val materialEntry = materialPack.materials.firstOrNull { it.material == material } ?: return false
            if (materialEntry.amount < amount) return false
            materialEntry.amount -= amount
            if (materialEntry.amount <= 0) {
                materialPack.materials.remove(materialEntry)
            }
            MaterialPackDataBase.saveMaterialPack(player, materialPack)
            return true
        }
    }

    /**
     * 判断玩家是否有足够的物品
     * @param player 玩家
     * @param material 物品
     * @param amount 数量
     * @return 是否有足够的物品
     */
    fun hasEnoughMaterial(player: Player, material: Material, amount: Int): Boolean {
        val materialPack = getMaterialPack(player)
        if (material.isAir) return true
        val materialEntry = materialPack.materials.firstOrNull { it.material == material } ?: return false
        return materialEntry.amount >= amount
    }




}

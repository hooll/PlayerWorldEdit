package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
import cn.stingcraft.plugin.database.MaterialPackDataBase
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MaterialPackManager {

    /**
     * 材料包是"读 → 改 → 全量写库"的复合操作, 而它会被三种线程调用:
     * 玩家线程 (取物 / 扣费)、玩家所在区域线程 (退费)、异步线程 (添加材料)。
     * 复合操作非原子, 并发时后写会覆盖先写 —— 材料凭空消失或多出来。
     *
     * 用 per-player 锁把同一个玩家的读改写串成一条线, 不同玩家之间互不阻塞。
     * 锁保护的是 [caches] 里的内存包 (以前是每次从库里读出来的那个包)。
     */
    private val locks = ConcurrentHashMap<UUID, Any>()

    /**
     * per-player 材料包缓存 —— 玩家在线期间的**唯一真相源**。
     *
     * 以前每次取物/扣费都要"base64 解码 + gzip 解压 + gson 解析"读一次, 再"gson + gzip + base64"
     * 全量写一次, 一次点击就是两读一写。加了缓存之后, 操作只改内存, 退出 (或关服) 时才落一次盘。
     *
     * 缓存里没有这个玩家时, 读改写会退回数据库直连 (见 [mutate]): 玩家已经退出、或者登录时的
     * 加载还没跑完, 走直连才不会把那次改动丢掉。
     */
    private val caches = ConcurrentHashMap<UUID, MaterialPack>()

    private fun lockOf(uuid: UUID): Any = locks.computeIfAbsent(uuid) { Any() }

    /**
     * 登录后从数据库加载一次 (数据容器就绪之后调用)
     *
     * 已经有缓存就不重复加载 —— 加载必须是幂等的, 否则一次晚到的加载会用库里的旧数据
     * 盖掉内存里还没落盘的改动。
     */
    fun load(player: Player) {
        synchronized(lockOf(player.uniqueId)) {
            // 登录加载是异步的, 跑完时玩家可能已经退出: 那就不再往缓存里塞, 否则会留下一条
            // 永远不会落盘的缓存 (退出事件已经过去, 不会再有 unload 来 flush 它)
            if (!player.isOnline) return
            if (caches.containsKey(player.uniqueId)) return
            caches[player.uniqueId] = MaterialPackDataBase.getMaterialPack(player.uniqueId)
        }
    }

    /**
     * 把缓存写回数据库 (退出 / 关服时用)
     *
     * 没有缓存就什么都不写: 那说明这个玩家的包从没加载过, 写回去只会把库里的数据盖成空包。
     */
    fun flush(uuid: UUID) {
        synchronized(lockOf(uuid)) {
            flushLocked(uuid)
        }
    }

    /**
     * 把缓存写回数据库; 调用方必须已经持有这个玩家的锁
     *
     * 用 [MaterialPackDataBase.saveMaterialPackNow]: 容器 + 数据库一起写。退出/关服这条路径也走它 ——
     * 少写一次库省不了什么, 但"退出前那一小段没落库"的窗口就没了。
     */
    private fun flushLocked(uuid: UUID) {
        val materialPack = caches[uuid] ?: return
        MaterialPackDataBase.saveMaterialPackNow(uuid, materialPack)
    }

    /**
     * 退出: 落盘并丢掉缓存 (下次登录重新从库里加载)
     *
     * 落盘和丢缓存必须在**同一段锁**里完成。分成"flush(锁①) → remove(锁②)"两次加锁的话,
     * 中间能被一次并发的 mutate 插进来: 缓存还在 -> 只改内存不写库 -> 紧接着被 remove 丢掉,
     * 这次改动就既不在缓存里也不在库里了 (触发场景: MaterialADDInventory 的异步添加撞上退出)。
     */
    fun unload(player: Player) {
        synchronized(lockOf(player.uniqueId)) {
            flushLocked(player.uniqueId)
            caches.remove(player.uniqueId)
        }
    }

    /** 关服: 把所有还没落盘的缓存写回去 */
    fun flushAll() {
        caches.keys.toList().forEach { flush(it) }
    }

    /**
     * 材料交易 (与玩家背包打交道的那几条路) 的结果
     *
     * 三态而不是布尔: "材料不够"和"账没落库"要分别告诉玩家, 也要让调用方知道**这次改动的账到底
     * 记上了没有** —— 混成一个 false 就只能猜 (R10)。
     */
    enum class TradeResult {
        /** 成功: 账已经落库 */
        OK,
        /** 材料不够, 什么都没改 */
        NOT_ENOUGH,
        /** 账没落库 (已经回滚), 调用方必须按失败处理: 别发物品 / 把物品还回去 */
        PERSIST_FAILED
    }

    /**
     * 把缓存**立刻落库**; 失败打日志并返回 false
     *
     * 与 [flush] 的区别: [flush] 是退出/关服时的收尾 (失败也无所谓, 反正要下线了), 这里给交易路径用,
     * 调用方要按结果决定"发不发物品 / 收不收物品", 所以必须把失败报出来。
     */
    fun flushNow(player: Player): Boolean {
        return synchronized(lockOf(player.uniqueId)) {
            runCatching { flushLocked(player.uniqueId) }
                .onFailure { warning("[PlayerWorldEdit] 材料包落库失败 (${player.name}): $it") }
                .isSuccess
        }
    }

    /**
     * 取物: 扣缓存 + **立刻落库**, 落库失败就把扣的加回去
     *
     * 顺序是"先扣先落库, 再给物品": 崩在中间只会少一件物品, 不会凭空多出一件 (方向安全)。原来只改
     * 内存、退出时才落盘 —— 崩在退出之前, 库里还是旧值, 重登后材料又回来了, 而物品已经在玩家背包里
     * = 重复提现 (2026-10-14 的 R10)。
     */
    fun tryConsumeDurably(player: Player, material: Material, amount: Int): TradeResult {
        if (!tryConsume(player, material, amount)) return TradeResult.NOT_ENOUGH
        if (flushNow(player)) return TradeResult.OK
        // 落库失败: 把这次扣的加回去 (加回去只改内存, 下一次落库会覆盖掉失败的这次)
        addMaterial(player, material, amount)
        return TradeResult.PERSIST_FAILED
    }

    /**
     * 存入: 加缓存 + **立刻落库**, 落库失败就把加的撤回 (返回 [TradeResult.PERSIST_FAILED], 由调用方
     * 把物品还给玩家)
     *
     * 存入这一路原来是 `submitAsync { addMaterials(...) }` —— 玩家物品已经从背包里拿走了, 而"加进
     * 材料包"是个异步且不落库的动作: 崩在任务执行之前, 物品既不在背包里也不在库里 (凭空消失);
     * 崩在落库之前同理。现在同步做、做完就落库, 失败还能把物品退回去 (R10)。
     */
    fun addMaterialsDurably(player: Player, materials: List<MaterialEntry>): TradeResult {
        addMaterials(player, materials)
        if (flushNow(player)) return TradeResult.OK
        materials.forEach { removeMaterial(player, it.material, it.amount) }
        return TradeResult.PERSIST_FAILED
    }

    /**
     * 只读快照。
     *
     * 返回的是**深拷贝**: `MaterialPack` 的 `copy()` 是浅拷贝 —— 副本与缓存里的对象共享同一个
     * `MutableSet` 和同一批 `MaterialEntry`, 调用方在锁外改副本就等于改原件, 锁白加。
     * 这里逐个复制 entry, 让返回值与缓存彻底脱钩。
     */
    fun getMaterialPack(player: Player): MaterialPack {
        return synchronized(lockOf(player.uniqueId)) {
            val materialPack = caches[player.uniqueId] ?: MaterialPackDataBase.getMaterialPack(player.uniqueId)
            MaterialPack(materialPack.materials.mapTo(mutableSetOf()) { it.copy() })
        }
    }

    /**
     * 添加物品列表
     * @param player 玩家
     * @param materials 物品列表
     */
    fun addMaterials(player: Player,materials: List<MaterialEntry>){
        mutate(player.uniqueId) { materialPack ->
            val packMaterials = materialPack.materials
            materials.forEach { newEntry ->
                val existing = packMaterials.find { it.material == newEntry.material }
                if (existing != null) {
                    existing.amount += newEntry.amount
                } else {
                    packMaterials.add(MaterialEntry(newEntry.material, newEntry.amount))
                }
            }
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
        mutate(player.uniqueId) { materialPack ->
            val materialEntry = materialPack.materials.find { it.material == material }
            if (materialEntry == null) {
                materialPack.materials.add(MaterialEntry(material, amount))
            } else {
                materialEntry.amount += amount
            }
        }
    }

    /**
     * 移除物品
     * @param player 玩家
     * @param material 物品
     * @param amount 数量
     */
    fun removeMaterial(player: Player, material: Material, amount: Int) {
        mutate(player.uniqueId) { materialPack ->
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
        return mutate(player.uniqueId) { materialPack ->
            // 用迭代器删, 和 removeMaterial 保持一致: MaterialEntry 是 data class 且 amount 是 var,
            // 元素进的是 LinkedHashSet, 先把 amount 减到 0 再 remove(entry) 时 hashCode 已经变了,
            // 删不掉, 会留下一条"数量 0"的死记录。
            val iterator = materialPack.materials.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.material == material) {
                    // 余额不足: 一个方块都不动
                    if (entry.amount < amount) return@mutate false
                    entry.amount -= amount
                    if (entry.amount <= 0) {
                        iterator.remove()
                    }
                    return@mutate true
                }
            }
            false
        }
    }

    /**
     * 在锁内对玩家的材料包做一次读改写。
     *
     * 有缓存时改缓存 (常态, 不再每次全量序列化写库); 没有缓存时直接读改写数据库 ——
     * 玩家已经退出、或者登录加载还没跑完时, 那次改动不能就这么丢掉。
     *
     * 走数据库这条路的缺点: 即使 [block] 判定"不用改" (比如材料不够), 也会把原样写回去一次。
     * 这是少见路径上的一次多余写, 换来的是不用为它单独开一条分支。
     */
    private fun <T> mutate(uuid: UUID, block: (MaterialPack) -> T): T {
        synchronized(lockOf(uuid)) {
            val cached = caches[uuid]
            if (cached != null) {
                return block(cached)
            }
            val materialPack = MaterialPackDataBase.getMaterialPack(uuid)
            val result = block(materialPack)
            MaterialPackDataBase.saveMaterialPack(uuid, materialPack)
            return result
        }
    }
}

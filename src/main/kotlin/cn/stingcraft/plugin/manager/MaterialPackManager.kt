package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
import cn.stingcraft.plugin.database.MaterialPackDataBase
import org.bukkit.Material
import org.bukkit.Bukkit
import cn.stingcraft.plugin.util.EditLifecycle
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface MaterialPackStore {
    fun read(uuid: UUID): MaterialPack
    fun write(uuid: UUID, pack: MaterialPack)
}

class MaterialPackManager internal constructor(
    private val store: MaterialPackStore,
    private val log: (String) -> Unit
) {
    constructor() : this(MaterialPackDataBase, { warning(it) })

    /** 无法判明数据库提交结果的账号拒绝后续交易, 等待管理员核对。 */
    private val uncertain = ConcurrentHashMap.newKeySet<UUID>()

    internal fun suspendTrades(uuid: UUID) { uncertain.add(uuid) }


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
     * 在线编辑的 per-player 材料包缓存; GUI 交易在同一锁内同步提交后发布新缓存。
     *
     * 以前每次取物/扣费都要"base64 解码 + gzip 解压 + gson 解析"读一次, 再"gson + gzip + base64"
     * 全量写一次。普通编辑扣退先改缓存, 退出/停用保存; GUI 物品交易必须同步提交。
     *
     * 缓存里没有这个玩家时, 读改写会退回数据库直连 (见 [mutate]): 玩家已经退出、或者登录时的
     * 加载还没跑完, 走直连才不会把那次改动丢掉。
     */
    private val caches = ConcurrentHashMap<UUID, MaterialPack>()

    private fun lockOf(uuid: UUID): Any = locks.computeIfAbsent(uuid) { Any() }

    /**
     * 登录后从数据库加载一次, 借用的 Auto 容器由 store 自己释放
     *
     * 已经有缓存就不重复加载 —— 加载必须是幂等的, 否则一次晚到的加载会用库里的旧数据
     * 盖掉内存里还没落盘的改动。
     */
    fun load(player: Player) {
        synchronized(lockOf(player.uniqueId)) {
            // 登录加载是异步的, 跑完时玩家可能已经退出: 那就不再往缓存里塞, 否则会留下一条
            // 永远不会落盘的缓存 (退出事件已经过去, 不会再有 unload 来 flush 它)
            if (!player.isOnline || Bukkit.getPlayer(player.uniqueId) !== player || !EditLifecycle.accepting) return
            if (caches.containsKey(player.uniqueId)) return
            val loaded = store.read(player.uniqueId)
            if (player.isOnline && Bukkit.getPlayer(player.uniqueId) === player && EditLifecycle.accepting) {
                caches[player.uniqueId] = loaded
            }
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
     * 本依赖的 AutoDataContainer.set 同步写库, 不需要也不能重复调用 Database.set。
     */
    private fun flushLocked(uuid: UUID) {
        if (uuid in uncertain) throw IllegalStateException("Material trade outcome unresolved: $uuid")
        val materialPack = caches[uuid] ?: return
        store.write(uuid, materialPack)
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
            val current = Bukkit.getPlayer(player.uniqueId)
            if (current != null && current !== player) return
            runCatching { flushLocked(player.uniqueId) }
                .onSuccess { caches.remove(player.uniqueId) }
                .onFailure { log("[PlayerWorldEdit] 退出保存失败 (${player.uniqueId}), 保留缓存: $it") }
        }
    }

    /** 关服: 把所有还没落盘的缓存写回去 */
    fun flushAll() {
        caches.keys.toList().forEach { uuid ->
            runCatching { flush(uuid) }.onFailure { log("[PlayerWorldEdit] 材料保存失败 ($uuid), 保留缓存待重试: $it") }
        }
    }

    enum class TradeResult {
        OK, NOT_ENOUGH, PERSIST_FAILED,
        /** 写入抛错且回读也不能判明结果。禁止退物或重提, 原物进入待核对记录。 */
        PERSIST_UNKNOWN
    }

    fun flushNow(player: Player): Boolean = synchronized(lockOf(player.uniqueId)) {
        runCatching { flushLocked(player.uniqueId) }
            .onFailure { log("[PlayerWorldEdit] 材料包落库失败 (${player.uniqueId}): $it") }.isSuccess
    }

    fun tryConsumeDurably(player: Player, material: Material, amount: Int): TradeResult =
        trade(player.uniqueId) { pack -> consume(pack, material, amount) }

    fun addMaterialsDurably(player: Player, materials: List<MaterialEntry>): TradeResult =
        trade(player.uniqueId) { pack ->
            materials.forEach { entry ->
                require(entry.amount > 0) { "Non-positive material deposit" }
                val existing = pack.materials.find { it.material == entry.material }
                if (existing == null) pack.materials.add(entry.copy())
                else existing.amount = Math.addExact(existing.amount, entry.amount)
            }
            true
        }

    /** 整个加载/修改/单次提交/失败回读都在同一 UUID 锁内, 不在原包上做失败回滚。 */
    internal fun trade(uuid: UUID, change: (MaterialPack) -> Boolean): TradeResult = synchronized(lockOf(uuid)) {
        if (!EditLifecycle.accepting) return@synchronized TradeResult.PERSIST_FAILED
        if (uuid in uncertain) return@synchronized TradeResult.PERSIST_UNKNOWN
        val old: MaterialPack
        val candidate: MaterialPack
        try {
            old = caches[uuid] ?: store.read(uuid)
            candidate = copyPack(old)
            if (!change(candidate)) return@synchronized TradeResult.NOT_ENOUGH
        } catch (error: Throwable) {
            log("[PlayerWorldEdit] 材料交易加载或准备失败 ($uuid): $error")
            return@synchronized TradeResult.PERSIST_FAILED
        }
        try {
            store.write(uuid, candidate)
        } catch (writeError: Throwable) {
            // 回读自身也可能抛错, 这种情况必须保留 UNKNOWN, 不可落入未提交分支。
            val outcome = runCatching {
                val committed = store.read(uuid)
                when {
                    equivalent(committed, candidate) -> TradeResult.OK
                    equivalent(committed, old) -> TradeResult.PERSIST_FAILED
                    else -> TradeResult.PERSIST_UNKNOWN
                }
            }.getOrDefault(TradeResult.PERSIST_UNKNOWN)
            if (outcome == TradeResult.PERSIST_UNKNOWN) {
                uncertain.add(uuid)
                log("[PlayerWorldEdit] 材料交易提交结果不明 ($uuid), 已暂停交易, 必须人工核对: $writeError")
            }
            if (outcome != TradeResult.OK) return@synchronized outcome
        }
        // 到这里库已确认提交, 绝不再将后续错误转换成 PERSIST_FAILED 并要求退物。
        if (caches.containsKey(uuid)) caches[uuid] = candidate
        TradeResult.OK
    }

    private fun copyPack(pack: MaterialPack) = MaterialPack(pack.materials.mapTo(mutableSetOf()) { it.copy() })
    private fun equivalent(left: MaterialPack, right: MaterialPack): Boolean =
        left.materials.associate { it.material to it.amount } == right.materials.associate { it.material to it.amount }

    private fun consume(pack: MaterialPack, material: Material, amount: Int): Boolean {
        if (material.isAir || amount <= 0) return true
        val iterator = pack.materials.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.material != material) continue
            if (entry.amount < amount) return false
            entry.amount -= amount
            if (entry.amount == 0) iterator.remove()
            return true
        }
        return false
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
            val materialPack = caches[player.uniqueId] ?: store.read(player.uniqueId)
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
        if (!EditLifecycle.accepting) return false
        return mutate(player.uniqueId) { consume(it, material, amount) }
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
            check(uuid !in uncertain) { "Material trade outcome unresolved: $uuid" }
            val cached = caches[uuid]
            if (cached != null) {
                return block(cached)
            }
            val materialPack = store.read(uuid)
            val result = block(materialPack)
            try {
                store.write(uuid, materialPack)
            } catch (error: Throwable) {
                // 离线返料失败不能丢掉待保存余额, 保留 UUID 缓存供保存重试。
                caches[uuid] = materialPack
                throw error
            }
            return result
        }
    }
}

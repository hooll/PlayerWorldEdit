package cn.stingcraft.plugin.api

import cn.stingcraft.plugin.PlayerWorldEdit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.function.BiConsumer

/**
 * PlayerWorldEdit 的对外 API (Java 友好)
 *
 * 每个方法都是 manager 既有入口的**薄封装** —— 与命令、GUI 走的是完全相同的一条链路
 * (领地保护钩子 → 扣料 → 写入 → 撤销记录 → 两个事件), 所以调用 API 不会绕过任何一道校验,
 * 也不会多出第二份业务逻辑。
 *
 * **线程**: 所有方法都必须在**玩家自己的线程**上调用 (Folia 下是玩家所在区域线程, Bukkit/Paper
 * 下是主线程)。方法内部该切区块线程的部分 (读世界、写方块) 会自己切, 调用方不用管; 但事件监听器
 * 也在这个线程上同步跑, 所以监听器里不要做重活。
 *
 * **签名**: 只用 Bukkit 类型 (`Player` / `Location` / `Material` / `Int`) —— WorldEdit 的内部类型
 * (`CuboidRegion` / `BlockState` 等) 一律不出现在对外签名里。
 *
 * **依赖**: [getSelection] 返回的 `Pair` 是 kotlin-stdlib 的类型, 所以 Java 调用方需要
 * kotlin-stdlib 在 classpath 上 (绝大多数服务端本来就有; 本插件自身也依赖它)。其余方法只用
 * JDK 与 Bukkit 的类型。
 *
 * 写方块的方法都是**异步收尾**的 (大选区要跨很多 tick): 方法返回时操作可能还没写完, 想知道结果
 * 请监听 [cn.stingcraft.plugin.api.event.WorldEditOperationCompleteEvent], 或看 [undo] 的回调。
 */
object PlayerWorldEditApi {

    private val manager = PlayerWorldEdit.worldEditManager

    private val pack = PlayerWorldEdit.packManager

    // ---------------- 选区 (只读) ----------------

    /**
     * 玩家的两个选区点 (`点1 to 点2`); 没选齐返回 null
     *
     * 等价命令: 无 (对应 `/pwe` 菜单里"选区信息"那一格)。只读, 不扣料、不记撤销。
     */
    @JvmStatic
    fun getSelection(player: Player): Pair<Location, Location>? {
        val first = manager.getPlayerPos1(player) ?: return null
        val second = manager.getPlayerPos2(player) ?: return null
        return first to second
    }

    /**
     * 选区里的方块数; 没选齐返回 0
     *
     * 等价命令: 无 (只读)。不扣料、不记撤销。
     */
    @JvmStatic
    fun getSelectionBlockCount(player: Player): Int = manager.getCountInRegion(player)

    // ---------------- 写方块 (扣料 + 记撤销 + 发事件) ----------------

    /**
     * 用 [material] 填满整个选区 (空气 = 清空)
     *
     * 等价命令: `/pwe set <方块>`。按体积扣料、记一条撤销记录、成功后保留选区。
     */
    @JvmStatic
    fun setArea(player: Player, material: Material) = manager.trySetArea(player, material)

    /**
     * 把选区里所有 [from] 换成 [to]
     *
     * 等价命令: `/pwe replace <原方块> <新方块>`。按**实际替换数量**扣料、记撤销、成功后保留选区。
     */
    @JvmStatic
    fun replaceArea(player: Player, from: Material, to: Material) = manager.tryReplaceArea(player, from, to)

    /**
     * 沿选区四周生成四面竖墙 (不含顶和底)
     *
     * 等价命令: `/pwe wall <方块>`。按实际块数扣料、记撤销、成功后保留选区。
     */
    @JvmStatic
    fun generateWall(player: Player, material: Material) = manager.tryGenerateWall(player, material)

    /**
     * 以玩家所在位置为中心生成实心圆柱
     *
     * 等价命令: `/pwe cyl <方块> <半径> [高度]` ([height] 默认 1, 与命令一致)。
     * 不看选区; 半径/高度夹在 1..256, 超出世界高度/世界边界的部分不写也不计费。
     */
    @JvmStatic
    @JvmOverloads
    fun generateCylinder(player: Player, material: Material, radius: Int, height: Int = 1) =
        manager.tryGenerateCylinder(player, material, radius, height)

    /**
     * 生成空心圆柱 (只写半径处那一圈壳, 厚 1 格)
     *
     * 等价命令: `/pwe hcyl <方块> <半径> [高度]` ([height] 默认 1)。其余同 [generateCylinder]。
     */
    @JvmStatic
    @JvmOverloads
    fun generateHollowCylinder(player: Player, material: Material, radius: Int, height: Int = 1) =
        manager.tryGenerateHollowCylinder(player, material, radius, height)

    /**
     * 把选区里的建筑朝玩家视线方向平移 [blocks] 格
     *
     * 等价命令: `/pwe move [格数]` ([blocks] 默认 1)。目标区超出世界高度或世界边界时**整体不动作**
     * (不夹取, 避免方块被搬丢); 按材料扣料、记一条撤销记录; 选区本身不动。
     */
    @JvmStatic
    @JvmOverloads
    fun move(player: Player, blocks: Int = 1) = manager.moveSelection(player, blocks)

    /**
     * 撤销最近 [times] 次操作 (还原方块 + 按记录返还材料)
     *
     * 等价命令: `/pwe undo [次数]`。撤销是链式的、可能跨很多 tick, 全部做完后回调一次:
     * [callback] 的第一个参数是**实际撤销的条数**, 第二个是"这轮是不是因为超时提前收尾"
     * (超时的那一条已经放回队列, 可以重试)。
     *
     * 撤销不触发 [cn.stingcraft.plugin.api.event.WorldEditOperationEvent] / `...CompleteEvent`
     * (撤销记录是异步读出来的, 触发时还不知道会改哪里)。
     */
    @JvmStatic
    @JvmOverloads
    fun undo(player: Player, times: Int = 1, callback: BiConsumer<Int, Boolean>) =
        manager.undo(player, times) { done, timedOut -> callback.accept(done, timedOut) }

    // ---------------- 剪贴板 (不写方块、不扣料、不记撤销) ----------------

    /**
     * 把当前选区复制进玩家的剪贴板
     *
     * 等价命令: `/pwe copy`。不写方块、不扣料、不记撤销; 成功后保留选区。
     */
    @JvmStatic
    fun copy(player: Player) = manager.tryCopy(player)

    /**
     * 绕 Y 轴旋转剪贴板 [degrees] 度 (只接受 90 / 180 / 270)
     *
     * 等价命令: `/pwe rotate <角度>`。纯内存操作, 不写方块、不扣料、不记撤销。
     */
    @JvmStatic
    fun rotate(player: Player, degrees: Int) = manager.rotateClipboard(player, degrees)

    /**
     * 沿某个方向镜像剪贴板
     *
     * 等价命令: `/pwe flip [方向]`。[direction] 取 `me` (按玩家水平朝向) / `n` / `s` / `e` / `w` /
     * `u` / `d`。纯内存操作, 不写方块、不扣料、不记撤销。
     */
    @JvmStatic
    fun flip(player: Player, direction: String) = manager.flipClipboard(player, direction)

    /**
     * 把剪贴板直接贴到玩家所在的位置 (不带预览)
     *
     * 等价命令: `/pwe paste`。空气照写 (会清掉目标位置的方块)、非空气按材料逐种扣、记一条撤销
     * 记录。要先看预览请走 `/pwe paste preview` 或 GUI。
     */
    @JvmStatic
    fun paste(player: Player) = manager.tryPaste(player)

    // ---------------- 材料包 ----------------

    /**
     * 玩家创世神背包里 [material] 的数量 (没有就是 0)
     *
     * 等价命令: `/pwe pack` 里看到的那份材料。只读。
     */
    @JvmStatic
    fun getMaterialCount(player: Player, material: Material): Int =
        pack.getMaterialPack(player).materials.firstOrNull { it.material == material }?.amount ?: 0

    /**
     * 往玩家的创世神背包里放 [amount] 个 [material]
     *
     * 等价命令: 材料背包界面里放东西。不涉及世界、不记撤销。
     */
    @JvmStatic
    fun addMaterial(player: Player, material: Material, amount: Int) = pack.addMaterial(player, material, amount)

    /**
     * 从玩家的创世神背包里取走 [amount] 个 [material]
     *
     * 等价命令: 材料背包界面里取东西。**全有全无**: 不够 [amount] 个就一个都不取并返回 false
     * (与操作内部扣料用的是同一个 `tryConsume`)。
     */
    @JvmStatic
    fun removeMaterial(player: Player, material: Material, amount: Int): Boolean =
        pack.tryConsume(player, material, amount)
}

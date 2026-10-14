package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.api.event.OperationType
import cn.stingcraft.plugin.hook.AreaPermission
import cn.stingcraft.plugin.util.RegionSlice
import cn.stingcraft.plugin.util.WorldEditTool
import cn.stingcraft.plugin.util.runOnChunkThreads
import cn.stingcraft.plugin.util.splitByChunk
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.transform.BlockTransformExtent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.math.transform.Transform
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BaseBlock
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import taboolib.common.platform.function.pluginId
import taboolib.platform.util.sendLang
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.floor

/**
 * 剪贴板 + paste (含预览)
 *
 * 从 WorldEditManager 拆出来的第三块 (第 9 轮重构)。per-UUID 剪贴板与 paste 预览都挂在这里;
 *
 * **方块实体数据 (R8)**: 剪贴板存的是完整方块 ([com.sk89q.worldedit.world.block.BaseBlock]), 不只是
 * 方块状态, 所以箱子里的物品 / 告示牌的文字会跟着复制、粘贴、搬迁:
 * - copy 读 `getFullBlock` (带实体数据), paste 写回完整方块 —— 与 WE 的 `//copy` / `//paste` 一致
 *   (复制一个装满的箱子, 贴出来也是装满的);
 * - move 的第一轮快照同样是完整方块, 目标区写的是源内容的完整副本, 源区清成空气;
 * - rotate / flip 只按属性变换**方块状态** (朝向等, 见 [ClipboardVolume.transformed]), 实体数据本身
 *   原样保留 —— 与 WE 的 `//rotate` / `//flip` 一致 (牌子上的文字不会跟着转);
 * - 实体 (掉落的物品、生物、画框) 不在快照范围内: 撤销与移动都只处理方块。
 * 写方块那一段仍然走 [EditPipeline.writeChunks], 选区与显示相关的判断走 [SelectionManager] ——
 * 子 manager 之间不互相引用, 只依赖这两个。
 */
class ClipboardManager(
    private val pipeline: EditPipeline,
    private val selection: SelectionManager
) {

    /**
     * 每个玩家一份的剪贴板 (copy / rotate / paste 用)
     *
     * 内存态、不持久化、不设上限 (与 WE 一致): 玩家退出时在 [clearPlayerPos] 里清掉, 关服随内存消失。
     * 值是**不可变快照**, 替换时换整份新对象而不是改里面的数组, 所以并发读的一方永远看到一份完整的
     * 剪贴板 (线程安全论证见 [ClipboardVolume])。
     */
    private val clipboards = ConcurrentHashMap<UUID, ClipboardVolume>()

    /**
     * GUI 的 paste 两段式: 玩家刚预览过的目标框 + 过期时间戳
     *
     * 条目只活到"过期"或"再贴一次"为止: 读的时候过期就删 (懒自清), 玩家退出时在 [clearPlayerPos]
     * 里删 —— 与剪贴板/形状尺寸同一套, 不会变成按 UUID 只增不删的表。
     */
    private val pastePreviews = ConcurrentHashMap<UUID, PastePreview>()

    /**
     * 把当前选区复制进玩家自己的剪贴板 (对齐 WE 的 `//copy`)
     *
     * 参考点 = **复制时玩家所在的那一格** (WE 的 clipboard origin 语义), 方块按相对原点的偏移存储。
     * 不写方块、不扣材料、不记撤销, 成功后保留选区。读世界照旧走 [runOnChunkThreads]: 大选区不能在
     * 玩家线程上读。
     */
    fun tryCopy(player: Player) {
        // copy 只读不写: 不触发操作前事件 (与既有行为一致), 但要拿编辑锁串行化同一个玩家的操作
        // 权限按只读声明 (三个插件都没有读取 flag, 所以与放置同档 —— 见 AreaPermission.READ)
        val validated = pipeline.validatedSelection(player, AreaPermission.READ) ?: return
        val world = validated.world
        val region = validated.region
        // copy 只读不写: 不触发操作前事件 (与既有行为一致), 但要拿编辑锁串行化同一个玩家的操作
        val token = pipeline.beginEdit(player) ?: return
        val origin = player.location
        val clipboard = ClipboardVolume.of(region, origin.blockX, origin.blockY, origin.blockZ)
        runOnChunkThreads(
            region.splitByChunk(),
            world,
            work = { _, slice ->
                val weWorld = BukkitAdapter.adapt(world)
                for (vector in slice.region) {
                    clipboard.setBlock(vector.x(), vector.y(), vector.z(), weWorld.getFullBlock(vector))
                }
            },
            done = { result ->
                pipeline.onPlayerThread(player) {
                    pipeline.endEdit(player, token)
                    // 玩家在读取期间退出: 退出路径已经清过表 (clearPlayerPos), 这里再把剪贴板 put 回去
                    // 就成了"按 UUID 只增不删"的泄漏, 而剪贴板不设上限 —— 离线玩家一律不发布
                    if (!player.isOnline) return@onPlayerThread
                    // 超时、区块抛异常、或者某个区块没读完 (数组里留下 null) 都算失败: 半份剪贴板贴出去
                    // 比不贴更糟
                    if (!result.isAllComplete() || !clipboard.isComplete()) {
                        player.sendLang("message-edit-failed", pluginId to "pluginId")
                        return@onPlayerThread
                    }
                    clipboards[player.uniqueId] = clipboard
                    player.sendLang("message-copy-susses", pluginId to "pluginId", clipboard.volume to "amount")
                }
            }
        )
    }

    /**
     * 绕 Y 轴旋转剪贴板 (对齐 WE 的 `//rotate <角度>`)
     *
     * 绕**剪贴板原点**转 (WE 的 `ClipboardHolder.transform` 保持 origin 不动), 所以 rotate 之后再
     * paste, 原点还是落到玩家脚下。纯内存操作: 在玩家线程上做完即可, 不碰世界、不扣料、不记撤销。
     */
    fun rotateClipboard(player: Player, degrees: Int) {
        if (degrees !in ROTATE_ANGLES) {
            player.sendLang(
                "message-rotate-invalid-angle",
                pluginId to "pluginId",
                ROTATE_ANGLES.joinToString("/") to "angles"
            )
            return
        }
        val clipboard = clipboards[player.uniqueId] ?: run {
            player.sendLang("message-clipboard-empty", pluginId to "pluginId")
            return
        }
        val token = pipeline.beginEdit(player) ?: return
        // 换一份新对象进表, 不动已经发布的那一份: 并发读的操作要么看到旋转前、要么看到旋转后
        clipboards[player.uniqueId] = clipboard.rotated(degrees)
        pipeline.endEdit(player, token)
        player.sendLang("message-rotate-susses", pluginId to "pluginId", degrees to "angle")
    }

    /**
     * 沿某个方向镜像剪贴板 (对齐 WE 的 `//flip`)
     *
     * 与 [rotateClipboard] 完全同一条通道 ([ClipboardVolume.transformed]): 只换内存里的那一份,
     * 不写方块、不扣料、不记撤销。方向语义与 WE 一致 —— 镜像平面**垂直于**该方向, 所以
     * e/w 镜像 X、n/s 镜像 Z、u/d 镜像 Y; `me` 取玩家的**水平**朝向 (抬头低头不影响结果, 与 WE
     * 的 `@Direction` 默认方向一致)。
     */
    fun flipClipboard(player: Player, direction: String) {
        val axis = when (direction.lowercase()) {
            "n", "s" -> AXIS_Z
            "e", "w" -> AXIS_X
            "u", "d" -> AXIS_Y
            "me", "" -> {
                // 只看水平分量: 视线朝东西镜像 X、朝南北镜像 Z (平局取 X, 与 snapAxis 同一套取法)
                val look = player.eyeLocation.direction
                if (abs(look.x) >= abs(look.z)) AXIS_X else AXIS_Z
            }
            else -> {
                player.sendLang(
                    "message-flip-invalid-direction",
                    pluginId to "pluginId",
                    FLIP_DIRECTIONS.joinToString("/") to "directions"
                )
                return
            }
        }
        val clipboard = clipboards[player.uniqueId] ?: run {
            player.sendLang("message-clipboard-empty", pluginId to "pluginId")
            return
        }
        val token = pipeline.beginEdit(player) ?: return
        // 与 rotate 一样换一份新对象进表: 并发读的一方要么看到镜像前、要么看到镜像后
        clipboards[player.uniqueId] = clipboard.flipped(axis)
        pipeline.endEdit(player, token)
        // 报轴名而不是回显输入: me 没有可回显的字面量, 而"沿哪一轴镜像"正是玩家想确认的
        player.sendLang("message-flip-susses", pluginId to "pluginId", axisName(axis) to "axis")
    }

    /**
     * 把剪贴板贴到玩家所在的位置 (对齐 WE 的 `//paste`)
     *
     * 锚点: WE 的 `getPlacementPosition` 对玩家就是"玩家脚下那一格", 剪贴板**原点**落到这里 ——
     * 所以"原地 copy → paste"就是原样贴回, 走 5 格再 paste 就是平移 5 格的副本。空气照写 (与 WE
     * 一致: 剪贴板里的空气会把目标位置的方块清掉), 计费只算非空气 (与 `/pwe set air` 一致)。
     *
     * 写方块走 [writeChunks] 这条既有链路 (区域线程 + 每 tick 预算 + 看门狗 + 快照撤销), 玩家线程上
     * 只做纯算术的计费和调度。
     */
    fun tryPaste(player: Player) {
        val clipboard = clipboardOf(player) ?: return
        val target = pasteTarget(player, clipboard, player.world) ?: return
        pasteAt(player, clipboard, target)
    }

    /**
     * 预览粘贴: 只画目标框 + 报预估, 不写方块、不扣料、不记撤销
     *
     * 目标框与用量都从 [pasteTarget] 取 —— 与真贴同一份锚点/夹取数学、同一份材料统计, 所以
     * "预览报的数 = 真贴会扣的数" (9C 口径), 不存在预览算一套、贴的时候算另一套的可能。
     */
    fun previewPaste(player: Player) {
        val clipboard = clipboardOf(player) ?: return
        val target = pasteTarget(player, clipboard, player.world) ?: return
        showPreview(player, target)
    }

    /**
     * GUI 粘贴按钮的两段式入口: 预览还有效就直接贴, 否则先预览
     *
     * "还有效" = [PASTE_PREVIEW_SECONDS] 秒内**且目标框没变**。玩家预览完走开了的话, 直贴会落到
     * 与刚才看到的框不同的地方 —— 那正是预览要防的事, 所以这种情况重新预览一次, 让框和数字跟上。
     *
     * @return true 表示这次点击真的贴了 (调用方可以把菜单关掉), false 表示只显示了预览
     */
    fun pasteOrPreview(player: Player): Boolean {
        val clipboard = clipboardOf(player) ?: return false
        val target = pasteTarget(player, clipboard, player.world) ?: return false
        val preview = pastePreviews[player.uniqueId]
        val confirmed = preview != null &&
            preview.expiresAt >= System.currentTimeMillis() &&
            preview.box.minimumPoint == target.box.minimumPoint &&
            preview.box.maximumPoint == target.box.maximumPoint
        if (confirmed) {
            pasteAt(player, clipboard, target)
            return true
        }
        showPreview(player, target)
        return false
    }

    /**
     * 取玩家的剪贴板, 没有就提示
     *
     * paste 的三条入口 (直贴 / 预览 / GUI 两段式) 都要先过这一关, 所以抽出来一份。
     */
    private fun clipboardOf(player: Player): ClipboardVolume? =
        clipboards[player.uniqueId] ?: run {
            player.sendLang("message-clipboard-empty", pluginId to "pluginId")
            null
        }

    /**
     * paste 的落点: 目标框 + 锚点 + 按材料统计的用量
     *
     * [cells]/[blocks]/[kinds] 就是预览里报的那三个数, 而 [charges] 是真贴要扣的账 —— 同一份对象,
     * 所以预览与真贴不可能对不上。
     */
    private class PasteTarget(
        val world: World,
        val box: CuboidRegion,
        val charges: Map<Material, Int>,
        val anchorX: Int,
        val anchorY: Int,
        val anchorZ: Int
    ) {
        /** 目标框里的格子数 (含会被清成空气的那些) */
        val cells = box.volume.toInt()

        /** 会被写上的非空气方块数 (= 真贴要扣的方块总数) */
        val blocks = charges.values.sum()

        /** 涉及多少种材料 */
        val kinds = charges.size
    }

    /**
     * 算出落点: 剪贴板的偏移框平移到玩家脚下, Y 夹进世界高度, 再统计材料
     *
     * 预览与真贴**共用**这一份计算 (锚点数学、夹取、计费循环都只写一份): 夹取掉的格子不写也不计费,
     * 所以计费恒等于"真正会写的方块"。整份剪贴板落在世界高度之外、或目标框没有编辑权限时提示并返回
     * null —— 领地校验放在这里, 预览也会照做: 在没权限的地方画个框说"将覆盖 N 格"是骗人。
     *
     * [world] 由调用方在**开头**固定下来并一路带下去 (R4): 预览与真贴之间玩家可能传送, 真贴时再用
     * `player.world` 会与预览报的框/数对不上, 校验也会落到另一个世界。
     */
    private fun pasteTarget(player: Player, clipboard: ClipboardVolume, world: World): PasteTarget? {
        val weWorld = BukkitAdapter.adapt(world)
        val anchor = player.location
        val anchorX = anchor.blockX
        val anchorY = anchor.blockY
        val anchorZ = anchor.blockZ
        val minX = anchorX + clipboard.minX
        val maxX = anchorX + clipboard.maxX
        val minZ = anchorZ + clipboard.minZ
        val maxZ = anchorZ + clipboard.maxZ
        val minY = maxOf(anchorY + clipboard.minY, weWorld.minY)
        val maxY = minOf(anchorY + clipboard.maxY, weWorld.maxY)
        if (minY > maxY) {
            // 整份剪贴板都落在世界高度之外
            player.sendLang("message-shape-none", pluginId to "pluginId")
            return null
        }
        // X/Z 世界边界夹取 (REV-06): 下面整段 (领地校验、计费、目标框) 都用夹取后的范围, 所以夹掉的
        // 部分不写也不计费, "预览报的数 = 真贴扣的数"照样成立
        val min = intArrayOf(minX, minY, minZ)
        val max = intArrayOf(maxX, maxY, maxZ)
        if (!clampXZToBorder(world, min, max)) {
            player.sendLang("message-shape-none", pluginId to "pluginId")
            return null
        }
        val box = CuboidRegion(BlockVector3.at(min[0], min[1], min[2]), BlockVector3.at(max[0], max[1], max[2]))
        // 领地校验覆盖整份要写的范围 (含空气会清到的那些格子); 粘贴会覆盖已有建筑, 所以放置与破坏
        // 两项都要 (R5)
        if (!pipeline.checkProtection(player, locationOf(world, min), locationOf(world, max), AreaPermission.PLACE_AND_BREAK)) return null
        // 计费: 目标框里逐格查剪贴板, 非空气按材料累加 —— 纯算术、不读世界, 且与下面写的是同一份内容
        val charges = LinkedHashMap<Material, Int>()
        for (y in min[1]..max[1]) {
            for (z in min[2]..max[2]) {
                for (x in min[0]..max[0]) {
                    val state = clipboard.stateAt(x - anchorX, y - anchorY, z - anchorZ) ?: continue
                    if (!state.blockType.isAir()) charges.add(state.blockType)
                }
            }
        }
        return PasteTarget(world, box, charges, anchorX, anchorY, anchorZ)
    }

    /**
     * 真贴: 扣料 + 走既有写方块管线 (命令直贴与 GUI 的第二段共用)
     *
     * [target] 必须来自 [pasteTarget], 这样"预览报的数"和"这里扣的数"是同一份 charges。
     */
    private fun pasteAt(player: Player, clipboard: ClipboardVolume, target: PasteTarget) {
        val edit = pipeline.beginOperation(player, OperationType.PASTE, target.box, target.world) ?: return
        // 全有全无: 任何一种材料不够就一个方块都不写 (consumeAll 会把已扣的原样加回去)
        if (!pipeline.consumeAll(player, target.charges)) {
            pipeline.endEdit(player, edit.token)
            return
        }
        // 贴过了: 预览到此作废 (再点按钮应该重新预览, 而不是拿旧框直接贴)
        pastePreviews.remove(player.uniqueId)
        pipeline.prepareEdit(target.cells, player)
        val world = target.world
        val actor = pipeline.actorOf(player)
        val regions = target.box.splitByChunk()
        // 每个区块实际写下去多少 (空气也记: 撤销要能还原被清掉的位置), 收尾时当成本表用
        val written = AtomicReferenceArray<HashMap<Material, Int>?>(regions.size)
        pipeline.writeChunks(
            player,
            edit,
            regions,
            "message-paste-susses",
            charged = target.charges,
            costs = { regions.indices.map { written.get(it) ?: emptyMap() } },
            type = OperationType.PASTE
        ) { index, slice, _ ->
            val counts = written.get(index)
                ?: HashMap<Material, Int>().also { written.set(index, it) }
            WorldEditTool.writeBlocks(
                world,
                slice.region,
                actor,
                // 剪贴板就在内存里: 这里的查表是纯算术, 不读世界
                stateAt = { x, y, z -> clipboard.stateAt(x - target.anchorX, y - target.anchorY, z - target.anchorZ) },
                onWritten = { state -> counts.add(state.blockType) }
            )
        }
    }

    private fun showPreview(player: Player, target: PasteTarget) {
        pastePreviews[player.uniqueId] =
            PastePreview(target.box, System.currentTimeMillis() + PASTE_PREVIEW_SECONDS * 1000)
        // 关掉玻璃盒显示 (region-display) 时只报数不画框 (开关与材质都在 SelectionManager 里):
        // 预览的粒子版本不做 —— 粒子边框是按选区的两个点画的, 而预览框是另一个区域, 为它再写一套
        // 粒子逻辑不值当
        selection.showPreviewBox(player, target.box, PASTE_PREVIEW_SECONDS * 20)
        player.sendLang(
            "message-paste-preview",
            pluginId to "pluginId",
            target.cells to "cells",
            target.blocks to "blocks",
            target.kinds to "kinds"
        )
    }

    private class PastePreview(val box: CuboidRegion, val expiresAt: Long)

    /**
     * 剪贴板支持的角度
     *
     * 命令补全与 GUI 的角度按钮都用它, 免得"能补全的角度"和"真能旋转的角度"各写一份对不上。
     */
    fun rotateAngles(): List<Int> = ROTATE_ANGLES

    /**
     * `/pwe flip` 的合法方向
     *
     * 命令补全与"方向非法"的提示共用这一份, 免得补全能给的选项和真能解析的选项对不上。
     */
    fun flipDirections(): List<String> = FLIP_DIRECTIONS

    /**
     * 一次 copy 的结果: 剪贴板原点 + 相对原点的偏移框 + 逐格方块状态
     *
     * 原点 = 复制时玩家所在的那一格 (WE 的 clipboard origin 语义): [rotated] 绕它转, 粘贴时把它
     * 落到玩家所在的那一格 —— 所以"原地 copy → paste"就是原样贴回。
     *
     * 线程安全: [states] 只在 copy 的区块线程阶段被填 (每个区块只碰自己那些下标), 填完才由
     * [clipboards] 的 put 发布 —— ConcurrentHashMap 的 put/get 是 volatile 读写, 读的一方拿到引用
     * 时能看到此前全部写入; 发布之后 [states] 一个格子都不再改, 旋转换的是整份新对象, 所以读的一方
     * 不会看到半份剪贴板。同一玩家身上的 copy/rotate/paste 还被 [beginEdit] 串行化。
     */
    private class ClipboardVolume(
        val originX: Int,
        val originY: Int,
        val originZ: Int,
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
        private val states: Array<BaseBlock?>
    ) {
        /** 剪贴板里的格子数 (提示玩家复制了多少格) */
        val volume = sizeOf(minX, maxX, minY, maxY, minZ, maxZ)

        private val sizeX = maxX - minX + 1
        private val sizeZ = maxZ - minZ + 1

        /** 相对偏移 → 一维下标 (y → z → x 展开, 与 WE 的区域遍历顺序一致) */
        private fun indexOf(dx: Int, dy: Int, dz: Int): Int =
            ((dy - minY) * sizeZ + (dz - minZ)) * sizeX + (dx - minX)

        /** 按**绝对**方块坐标填一格 (copy 阶段用: 区块线程只碰自己那些下标) */
        fun setBlock(x: Int, y: Int, z: Int, state: BaseBlock) {
            put(x - originX, y - originY, z - originZ, state)
        }

        /** 相对偏移处的方块状态; 偏移落在剪贴板框外返回 null (调用方按框内遍历, 正常取不到 null) */
        fun stateAt(dx: Int, dy: Int, dz: Int): BaseBlock? =
            if (dx in minX..maxX && dy in minY..maxY && dz in minZ..maxZ) states[indexOf(dx, dy, dz)] else null

        /** 每格都读到了 (区块跑失败会留下 null) */
        fun isComplete(): Boolean = states.none { it == null }

        /**
         * 把整份剪贴板按 [transform] 变换 (旋转 / 镜像共用这一条通道), 返回一份**新**剪贴板 (原点不动)
         *
         * 方块状态用 WE 自己的 [BlockTransformExtent.transform]: 它会按属性逐个变换 ——
         * facing 这类 [com.sk89q.worldedit.registry.state.DirectionalProperty] 走
         * `Direction.findClosest(transform.apply(direction.toVector()))`, axis / half / shape /
         * orientation / rotation 也各有处理 (楼梯的 `type` left/right 只在 `isHorizontalFlip()`
         * 时互换, 见报告里任务 15 留的字节码证据), 所以楼梯、门、告示牌变换后朝向跟着变。
         * 偏移用**同一个** transform —— 与 WE 的 `ClipboardHolder.transform` 是同一套做法, 双格
         * 方块 (门/床) 的两半因此仍然配对。原点不动, 所以 rotate/flip 之后 paste 的锚点语义不变。
         *
         * 90 的整数倍旋转与 ±1 镜像的系数精确为 0/±1 (实测), 所以整数偏移变换后还是整数。
         */
        fun transformed(transform: Transform): ClipboardVolume {
            // 先变换 8 个角求出新的偏移框: 变换后的框要用新下标, 得先知道边界再逐格搬
            var newMinX = Int.MAX_VALUE
            var newMaxX = Int.MIN_VALUE
            var newMinY = Int.MAX_VALUE
            var newMaxY = Int.MIN_VALUE
            var newMinZ = Int.MAX_VALUE
            var newMaxZ = Int.MIN_VALUE
            for (dy in intArrayOf(minY, maxY)) {
                for (dz in intArrayOf(minZ, maxZ)) {
                    for (dx in intArrayOf(minX, maxX)) {
                        val corner = transformedOffset(transform, dx, dy, dz)
                        newMinX = minOf(newMinX, corner[0])
                        newMaxX = maxOf(newMaxX, corner[0])
                        newMinY = minOf(newMinY, corner[1])
                        newMaxY = maxOf(newMaxY, corner[1])
                        newMinZ = minOf(newMinZ, corner[2])
                        newMaxZ = maxOf(newMaxZ, corner[2])
                    }
                }
            }
            val result = ClipboardVolume(
                originX, originY, originZ,
                newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ,
                arrayOfNulls(sizeOf(newMinX, newMaxX, newMinY, newMaxY, newMinZ, newMaxZ))
            )
            for (dy in minY..maxY) {
                for (dz in minZ..maxZ) {
                    for (dx in minX..maxX) {
                        val state = states[indexOf(dx, dy, dz)] ?: continue
                        val target = transformedOffset(transform, dx, dy, dz)
                        result.put(target[0], target[1], target[2], BlockTransformExtent.transform(state, transform))
                    }
                }
            }
            return result
        }

        /** 绕 Y 轴旋转 [degrees] 度 (只允许 90 的整数倍) */
        fun rotated(degrees: Int): ClipboardVolume =
            transformed(AffineTransform().rotateY(degrees.toDouble()))

        /**
         * 沿 [axis] 镜像 (对齐 WE 的 `//flip`)
         *
         * WE 的 `//flip` 是 `AffineTransform().scale(|方向| * -2 + 1)` (javap 字节码, 见报告):
         * 对 6 个轴向来说就是下面三个缩放向量之一。因为取的是绝对值, 正负方向结果相同, 所以这里
         * 只按轴取 —— 也正因为镜像平面垂直于该轴, 楼梯的 `type` 会在水平镜像下互换 (WE 的行为)。
         */
        fun flipped(axis: Int): ClipboardVolume = transformed(
            when (axis) {
                AXIS_X -> AffineTransform().scale(-1.0, 1.0, 1.0)
                AXIS_Y -> AffineTransform().scale(1.0, -1.0, 1.0)
                else -> AffineTransform().scale(1.0, 1.0, -1.0)
            }
        )

        /** 相对偏移写一格 (copy 与 rotate 共用; 下标一定在框内) */
        private fun put(dx: Int, dy: Int, dz: Int, state: BaseBlock) {
            states[indexOf(dx, dy, dz)] = state
        }

        /**
         * 把一个整数偏移交给 WE 的变换, 再取回整数偏移
         *
         * 取整用 floor: 与 WE 的 `BlockVector3.at(double)` 一致 (90 的整数倍下系数是精确的 0/±1,
         * 取整方式不影响结果, 这里只是跟 WE 保持同一口径)。返回 IntArray 是为了省掉每格的装箱。
         */
        private fun transformedOffset(transform: Transform, dx: Int, dy: Int, dz: Int): IntArray {
            val vector = transform.apply(Vector3.at(dx.toDouble(), dy.toDouble(), dz.toDouble()))
            return intArrayOf(floor(vector.x()).toInt(), floor(vector.y()).toInt(), floor(vector.z()).toInt())
        }

        companion object {

            /** 偏移框的格子数 —— copy 与 rotate 都要按框分配数组, 所以共用一个算式 */
            private fun sizeOf(minX: Int, maxX: Int, minY: Int, maxY: Int, minZ: Int, maxZ: Int): Int =
                (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)

            /** 按选区与剪贴板原点建一份空剪贴板 (方块状态由 copy 的区块线程逐个填) */
            fun of(region: CuboidRegion, originX: Int, originY: Int, originZ: Int): ClipboardVolume {
                val min = region.minimumPoint
                val max = region.maximumPoint
                val minX = min.x() - originX
                val minY = min.y() - originY
                val minZ = min.z() - originZ
                val maxX = max.x() - originX
                val maxY = max.y() - originY
                val maxZ = max.z() - originZ
                return ClipboardVolume(
                    originX, originY, originZ,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    arrayOfNulls(sizeOf(minX, maxX, minY, maxY, minZ, maxZ))
                )
            }
        }
    }

    /**
     * 玩家退出时的状态清理: 剪贴板 + paste 预览
     *
     * 由门面的 [WorldEditManager.clearPlayerPos] 聚合调用 —— 这两张表都按 UUID 存, 不清就是
     * "按 UUID 只增不删"。
     */
    internal fun forget(player: Player) {
        // 剪贴板同样按 UUID 只增不删, 退出时清掉 (不持久化, 下次登录就是空剪贴板)
        clipboards.remove(player.uniqueId)
        // paste 预览标志同理: 盒子和"再点一次即确认"的窗口都不该跨登录留着
        pastePreviews.remove(player.uniqueId)
    }

    private companion object {
        /**
         * 剪贴板允许的旋转角度
         *
         * 只收 90 的整数倍: 方块状态里的朝向属性只能落到四个正交方向上, WE 的 `//rotate` 给别的角度
         * 时也只能把朝向吸附到最接近的那个值, 与其做一件做不到位的事, 不如直接拒绝。
         */
        val ROTATE_ANGLES = listOf(90, 180, 270)

        /**
         * `/pwe flip` 的合法方向
         *
         * `me` = 按玩家水平朝向 (WE 的 `//flip` 不传方向时也是取玩家朝向); n/s 镜像 Z、e/w 镜像 X、
         * u/d 镜像 Y —— 镜像平面垂直于该方向。只收 6 个轴向: WE 的 `//flip` 允许任意方向向量,
         * 但斜向会同时镜像两轴, 那种结果对玩家没有意义。
         */
        val FLIP_DIRECTIONS = listOf("me", "n", "s", "e", "w", "u", "d")

        /**
         * paste 预览存活多久 (秒)
         *
         * 玻璃盒的显示时长与 GUI"再点一次即确认"的窗口共用一个数: 盒子消失了还能直接贴, 玩家会
         * 以为框还在; 反过来窗口比盒子长也一样别扭。
         */
        const val PASTE_PREVIEW_SECONDS = 10L
    }
}

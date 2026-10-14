package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.api.event.OperationType
import cn.stingcraft.plugin.util.EditBudget
import cn.stingcraft.plugin.util.RegionSlice
import cn.stingcraft.plugin.util.WorldEditTool
import cn.stingcraft.plugin.util.displayName
import cn.stingcraft.plugin.util.runOnChunkThreads
import cn.stingcraft.plugin.util.splitByChunk
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockTypes
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
 * 形状生成 + 扩缩 + move
 *
 * 从 WorldEditManager 拆出来的第四块 (第 9 轮重构)。这一摊的共同点是"先算几何、再走写管线":
 * 圆柱 / 墙的判定与计费共用同一个谓词, 扩缩 / move 先算层与框再交给 [EditPipeline] 写。依赖方向与
 * [ClipboardManager] 相同 (EditPipeline + SelectionManager), 两者之间没有引用。
 */
class ShapeManager(
    private val pipeline: EditPipeline,
    private val selection: SelectionManager
) {

    /**
     * 形状尺寸选择器里"待确认"的半径 / 高度 (半径 to 高度)
     *
     * 箱式菜单没有输入框, 玩家点 ± 之后新值要留在某处等下一次点击或确认, 所以按 UUID 存一份;
     * 和剪贴板一样挂在 [clearPlayerPos] 的退出清理路径上 —— 直接按 ESC 走人的玩家没人回收这份
     * 状态, 绝不能变成"按 UUID 只增不删"的表。确认生成时即用即弃 ([forgetShapeSize])。
     */
    private val pendingShapeSizes = ConcurrentHashMap<UUID, Pair<Int, Int>>()

    /**
     * 生成墙: 选区四周的四面竖墙 (等同 WorldEdit 的 //walls, 不含顶和底)
     *
     * @param player 玩家
     * @param material 方块 (空气 = 清空这些位置)
     */
    fun tryGenerateWall(player: Player, material: Material) {
        // 墙保持"选区四周"的口径不变: 选区校验 (含 Dominion fail-closed) 与以前完全一样
        val region = pipeline.validatedRegion(player, needBreak = false) ?: return
        generateShape(player, ShapeKind.WALL, material, region, radius = 0, successKey = "message-wall-susses")
    }

    /**
     * 生成实心圆柱 (轴竖直 Y)
     *
     * 底面在选区最小 Y、水平中心取选区中心、向上高 [height]; 超出选区的部分一律裁掉。
     *
     * @param radius 半径, 1..[SHAPE_MAX_SIZE]
     * @param height 高度, 1..[SHAPE_MAX_SIZE]
     */
    fun tryGenerateCylinder(player: Player, material: Material, radius: Int, height: Int) {
        generateCylinder(player, material, radius, height, hollow = false, successKey = "message-cyl-susses")
    }

    fun tryGenerateHollowCylinder(player: Player, material: Material, radius: Int, height: Int) {
        generateCylinder(player, material, radius, height, hollow = true, successKey = "message-hcyl-susses")
    }

    /**
     * 读取玩家正在调整的形状尺寸 (半径 to 高度)
     *
     * 该玩家还没有进行中的状态时返回 [SHAPE_SIZE_DEFAULT]: 那只是"还没调过"的默认显示, 不写进表里
     * (点过 ± 才有条目), 所以只开一次选择器就按 ESC 的玩家什么都不会留下。
     */
    fun pendingShapeSize(player: Player): Pair<Int, Int> =
        pendingShapeSizes[player.uniqueId] ?: SHAPE_SIZE_DEFAULT

    /**
     * 增减形状尺寸选择器里的数值, 结果 clamp 在 1..[SHAPE_MAX_SIZE] (点到顶/到底就停住, 不报错)
     *
     * 读改写用 compute 而不是 get + put: 同一个玩家的点击本来就串行, 但这样连"两次点击撞在一起"
     * 的可能性都不用去论证。
     */
    fun adjustShapeSize(player: Player, radiusDelta: Int, heightDelta: Int) {
        pendingShapeSizes.compute(player.uniqueId) { _, current ->
            val (radius, height) = current ?: SHAPE_SIZE_DEFAULT
            (radius + radiusDelta).coerceIn(1, SHAPE_MAX_SIZE) to
                (height + heightDelta).coerceIn(1, SHAPE_MAX_SIZE)
        }
    }

    /**
     * 丢掉进行中的形状尺寸 (确认生成后即用即弃; 玩家退出时由 [clearPlayerPos] 一并清掉)
     */
    fun forgetShapeSize(player: Player) {
        pendingShapeSizes.remove(player.uniqueId)
    }

    /**
     * 形状尺寸的上限 (选择器的提示文案要用) —— 与 [SHAPE_MAX_SIZE] 是同一份定义, 界面层不另抄一个数
     */
    fun shapeSizeMax(): Int = SHAPE_MAX_SIZE

    /**
     * 圆柱的几何与校验 —— 和墙的区别在于它**完全不看选区**
     *
     * 中心 = 玩家所在的那一格, 底面 = 玩家脚下方块 (低于世界最低高度时先往上贴到 minY), 向上
     * [height] 层; 顶面夹进世界高度 (被世界高度吃掉的那几层直接丢掉), 夹完一层都不剩 (例如站在
     * 世界顶) 时提示并返回。
     *
     * 没有选区就走不了 [validatedRegion], 但领地校验一个都不能少: 对圆柱的**外接长方体**做同一套
     * fail-closed 校验 —— 要动的方块必须全部有权限, 和 /pwe set 检查选区是同一个道理。
     */
    private fun generateCylinder(
        player: Player,
        material: Material,
        radius: Int,
        height: Int,
        hollow: Boolean,
        successKey: String
    ) {
        if (!validateShapeSize(player, radius, height)) return
        // 走到这里就是真的在生成了: 选择器里那份待确认数值到此用掉 (即用即弃)。命令行调用时表里
        // 本来就没有这个键, 删它是空操作 —— 两个入口共用这一处收尾, 界面层不用自己记着清。
        forgetShapeSize(player)
        val weWorld = BukkitAdapter.adapt(player.world)
        val location = player.location
        val centerX = location.blockX
        val centerZ = location.blockZ
        val baseY = location.blockY.coerceIn(weWorld.minY, weWorld.maxY)
        // 顶面按**夹取后**的底面算 (WE 的语义): 站在世界最低高度以下时底面先往上贴, 高度不会被
        // 凭空吃掉几层。baseY 已经夹在 [minY, maxY] 内、height 又 >= 1 (见 validateShapeSize),
        // 所以 topY 恒 >= baseY —— 原来那条 "baseY > topY 就返回" 的守卫不可达, 第 9 轮删掉
        val topY = (baseY + height - 1).coerceAtMost(weWorld.maxY)
        val world = player.world
        // X/Z 世界边界夹取 (REV-06): 夹掉的部分不写也不计费, 夹取后照常执行 (与 move 的整体拒绝
        // 相反)。形状仍由**未夹取**的外接框定义 —— ShapeVolume 的圆心是从框推出来的, 把框夹小会把
        // 圆柱挪位, 所以"形状定义框"和"实际写入范围"分开传给 generateShape。
        val boxMin = intArrayOf(centerX - radius, baseY, centerZ - radius)
        val boxMax = intArrayOf(centerX + radius, topY, centerZ + radius)
        val writeMin = boxMin.copyOf()
        val writeMax = boxMax.copyOf()
        if (!clampXZToBorder(world, writeMin, writeMax)) {
            // 整个外接框都在世界边界之外: 没有可放的位置
            player.sendLang("message-shape-none", pluginId to "pluginId")
            return
        }
        val box = CuboidRegion(
            BlockVector3.at(boxMin[0], boxMin[1], boxMin[2]),
            BlockVector3.at(boxMax[0], boxMax[1], boxMax[2])
        )
        // 领地校验按**实际会写到的范围** (夹取之后) 查, 而不是形状的外接框
        if (!pipeline.checkProtection(
                player,
                locationOf(player, writeMin),
                locationOf(player, writeMax),
                needBreak = false
            )
        ) {
            return
        }
        generateShape(
            player,
            if (hollow) ShapeKind.HOLLOW_CYLINDER else ShapeKind.CYLINDER,
            material,
            box,
            radius,
            successKey,
            writeBox = CuboidRegion(
                BlockVector3.at(writeMin[0], writeMin[1], writeMin[2]),
                BlockVector3.at(writeMax[0], writeMax[1], writeMax[2])
            )
        )
    }

    /**
     * 半径 / 高度的范围校验
     *
     * 下限 1 是真约束 (0 或负数没有意义); 上限 [SHAPE_MAX_SIZE] 只是 sanity cap —— 形状本来就被
     * 裁剪在选区内, 给得再大也只是"填满选区", 卡个上限是为了挡住手滑敲出的一长串数字。
     */
    private fun validateShapeSize(player: Player, radius: Int, height: Int): Boolean {
        if (radius in 1..SHAPE_MAX_SIZE && height in 1..SHAPE_MAX_SIZE) return true
        player.sendLang("message-shape-invalid-size", pluginId to "pluginId", SHAPE_MAX_SIZE to "max")
        return false
    }

    /**
     * 形状生成的公共流程 —— 和 [trySetArea] 走**同一条写方块链路**:
     * validatedRegion (含 Dominion fail-closed) → beginEdit → 计费 → tryConsume →
     * runOnChunkThreads → finishEdit (撤销记录 + 成功保留选区)。
     *
     * 计费在玩家线程上做, 但只是纯算术数一遍 X/Z 每列 (见 [ShapeVolume.countIn]): 不读世界、
     * 不遍历体积。真正的写入全部丢进 [runOnChunkThreads], 在区块所属区域线程上执行并受
     * [EditBudget] 每 tick 限流, 与 /pwe set 的开销特征一致。
     */
    private fun generateShape(
        player: Player,
        kind: ShapeKind,
        material: Material,
        box: CuboidRegion,
        radius: Int,
        successKey: String,
        // 实际要写的范围 (世界边界夹取之后)。形状仍由 [box] 定义 —— ShapeVolume 的圆心是从 [box]
        // 推出来的, 直接把它夹小会把圆柱挪位, 所以两者分开。墙没有夹取, 用默认值即可。
        writeBox: CuboidRegion = box
    ) {
        val blockType = pipeline.resolveBlockType(material) ?: run {
            player.sendLang("message-invalid-material", pluginId to "pluginId", material.name to "material")
            return
        }
        // 用 BlockVector3.x()/y()/z(): getX()/getY()/getZ() 在 7.4.6 已经标记弃用, 新代码别再添警告
        val min = box.minimumPoint
        val max = box.maximumPoint
        val shape = ShapeVolume(
            kind,
            min.x(), max.x(),
            min.y(), max.y(),
            min.z(), max.z(),
            radius
        )
        val regions = writeBox.splitByChunk()
        // 计费: 每个区块各数自己那一块里的形状方块, 加总就是总量。失败退费按区块退, 口径一致。
        // 这里数的是"列 × 层", 与下面 work 里逐块判定的结果严格相等 (见 ShapeVolume.countIn)
        val costs = regions.map { chunk ->
            mapOf(material to shape.countIn(chunk.minimumPoint.x(), chunk.maximumPoint.x(), chunk.minimumPoint.z(), chunk.maximumPoint.z()))
        }
        val amount = costs.sumOf { it.values.sum() }
        if (amount <= 0) {
            // 形状一点都没落在选区里 (例如 1x1 的选区放半径 1 的空心圆柱): 别白留一条 0 材料的撤销记录
            player.sendLang("message-shape-none", pluginId to "pluginId")
            return
        }
        // 形状种类与事件里的操作类型一一对应 (名字跟命令走: /pwe cyl → CYL, /pwe hcyl → HCYL)
        val operation = when (kind) {
            ShapeKind.WALL -> OperationType.WALL
            ShapeKind.CYLINDER -> OperationType.CYL
            ShapeKind.HOLLOW_CYLINDER -> OperationType.HCYL
        }
        // 事件报的是实际要写的范围 (世界边界夹取之后), 不是形状的外接框
        if (!pipeline.fireOperation(player, operation, writeBox)) return
        if (!pipeline.beginEdit(player)) return
        // 同 trySetArea: 查与扣合成一次原子操作; 先扣, 有区块没跑成再按区块退
        if (!PlayerWorldEdit.packManager.tryConsume(player, material, amount)) {
            player.sendLang("message-materialpack-not-enough",
                pluginId to "pluginId",
                material.displayName(player) to "material",
                amount to "amount")
            pipeline.endEdit(player)
            return
        }
        pipeline.prepareEdit(amount, player)
        val actor = pipeline.actorOf(player)
        val snapshots = AtomicReferenceArray<Array<BlockState?>?>(regions.size)
        // 绑定一次, 别在每个切片里现建函数引用
        val inShape: (Int, Int, Int) -> Boolean = shape::contains
        runOnChunkThreads(regions, player.world,
            work = { index, slice ->
                val chunk = regions[index]
                val states = snapshots.get(index)
                    ?: arrayOfNulls<BlockState>(chunk.volume.toInt()).also { snapshots.set(index, it) }
                // 快照照旧读整块: 撤销要把整块还原回去, 没改过的方块还原回去也没有副作用
                WorldEditTool.readBlocksInto(player.world, slice.region, states, slice.offset)
                WorldEditTool.setBlocksIf(player.world, slice.region, blockType.defaultState, actor, inShape)
            },
            done = { _ ->
                pipeline.onPlayerThread(player) {
                    pipeline.finishEdit(
                        player,
                        regions,
                        regions.indices.map { snapshots[it] },
                        mapOf(material to amount),
                        costs,
                        successKey,
                        operation
                    )
                }
            }
        )
    }

    /** 形状的水平投影种类 */
    private enum class ShapeKind { WALL, CYLINDER, HOLLOW_CYLINDER }

    /**
     * 一次形状生成的几何定义。
     *
     * **写入判定 ([contains]) 与计费 ([countIn]) 共用同一份判定** —— 任务 9C 的教训是"统计口径 ≠
     * 执行口径"就会多扣/少扣材料, 所以这里只留 [columnContains] 一个谓词, 计费只是把它按 X/Z
     * 每列数一遍。全部是纯算术, 一次世界访问都没有。
     *
     * 坐标全部取整到方块坐标 (和 /pwe set 一样按方块算), 不引入小数偏移。圆柱的光栅化与
     * WorldEdit 的 `makeCylinder` 对齐: 半径先 +0.5, 再按**格元**判定 (见 [inCircle])。
     */
    private class ShapeVolume(
        private val kind: ShapeKind,
        private val minX: Int,
        private val maxX: Int,
        private val minY: Int,
        private val maxY: Int,
        private val minZ: Int,
        private val maxZ: Int,
        radius: Int
    ) {
        // 圆心 = 外接框的水平中心。圆柱的外接框是 [cx-r, cx+r], 宽度 2r+1 是奇数, 所以整数除法
        // 算出来正好是玩家所在的那一格 cx; 墙的判定不看圆心, 算出来是什么都无所谓。
        private val centerX = (minX + maxX) / 2
        private val centerZ = (minZ + maxZ) / 2

        // 判定阈值 (2r+1)², 即 (2(r+0.5))²: 半径先 +0.5 是 WE 的格元口径, ×4 是为了把
        // a² + b² ≤ (r+0.5)² 变成纯整数比较, 不引入浮点。r ≤ SHAPE_MAX_SIZE, 不会溢出。
        private val circleLimit = (2 * radius + 1) * (2 * radius + 1)

        /**
         * 这一列 (x, z) 的水平投影在不在形状里 (纯算术, 与 Y 无关)
         *
         * 墙按"贴到选区某一条边"算, 所以 1 格宽的选区整条都算墙 (和 //walls 一致)。
         */
        fun columnContains(x: Int, z: Int): Boolean {
            // 圆柱的两个分支都要"到圆心的格数偏移", 在这里算一次, 不在分支里各抄一遍
            val a = abs(x - centerX)
            val b = abs(z - centerZ)
            return when (kind) {
                ShapeKind.WALL -> x == minX || x == maxX || z == minZ || z == maxZ
                ShapeKind.CYLINDER -> inCircle(a, b)
                // 空心 = 圆内挖掉"往 +X、+Z 各走一格都还在圆内"的格元 (WE 的判据), 剩下的就是径向
                // 厚 1 的壳; 偏移取绝对值, 挖的四个方向对称, 形状天然四象限镜像。
                // r=1 时壳是 3×3 的圈、实心是 3×3 的盘 —— 同一套谓词的自然结果, 与 WE 一致。
                ShapeKind.HOLLOW_CYLINDER ->
                    inCircle(a, b) && !(inCircle(a + 1, b) && inCircle(a, b + 1))
            }
        }

        /** 方块在不在形状里 —— 写入判定用的就是它 */
        fun contains(x: Int, y: Int, z: Int): Boolean = y in minY..maxY && columnContains(x, z)

        /**
         * 选区 (X/Z 取 [x1]..[x2] / [z1]..[z2]) 里有多少方块落在形状里, 纯算术
         *
         * = 落在形状里的列数 × 每列落在形状里的层数。因为 [contains] 是"列判定 AND Y ∈
         * [minY, topY]", 每列的层数是同一个常数, 所以这个乘积与逐块调用 [contains] 数出来的
         * 结果**严格相等** —— 但不用遍历整个体积, 也不读世界。
         */
        fun countIn(x1: Int, x2: Int, z1: Int, z2: Int): Int {
            val layers = maxY - minY + 1
            var columns = 0
            for (x in x1..x2) {
                for (z in z1..z2) {
                    if (columnContains(x, z)) columns++
                }
            }
            return columns * layers
        }

        /**
         * 格元 (a, b) 在不在圆内 —— a / b 是**到圆心的格数偏移的绝对值**
         *
         * 与 WorldEdit `EditSession.makeCylinder` 同一套口径: 半径 +0.5 后按格元判定, 格子的
         * 近角落在半径 r+0.5 的圆内才算入圆, 整数形式 4(a² + b²) ≤ (2r+1)²。
         */
        private fun inCircle(a: Int, b: Int): Boolean = 4 * (a * a + b * b) <= circleLimit
    }

    /**
     * 朝玩家朝向的那一面扩展 / 收缩选区, 并且**改动方块**
     *
     * - 扩展: 把选区最外那一层 (朝视线那一面) 的方块**原样复制**到新增的每一层 (逐块对应)。
     *   只有非空气的方块才写、才计费 —— 源层是空气的位置写空气 = 不写。
     * - 收缩: 把被砍掉的那几层**全部清成空气** (只记账, 不产生材料返还, 与 /pwe set air 一致)。
     *
     * 两者都是写方块的操作, 所以走全 beginEdit → 扣费 → runOnChunkThreads → finishEdit
     * (撤销记录 + 成功保留选区)。领地校验对"老选区 ∪ 新选区"的包围盒做: 扩展动的是新层 (属于
     * 新选区), 收缩清掉的是被砍掉的那几层 (属于老选区), 只查新选区会漏掉后者。
     *
     * 只动视线吸附轴 (|分量|最大的 ±X/±Y/±Z) 上对应的那一面, 另外 5 个面原样保留;
     * pos1/pos2 哪个大哪个小都行, 每次现算。
     *
     * @param player 玩家
     * @param blocks 扩缩的格数, 内部 clamp 到 1..[SELECTION_EDIT_MAX_BLOCKS]
     * @param expand true=扩展, false=收缩
     */
    fun resizeSelection(player: Player, blocks: Int, expand: Boolean) {
        // 和 set/replace 一样: 没选齐两点就提示先选点。这里不走 validatedRegion —— 领地校验要等
        // 移动后的包围盒算出来再做, 见下
        val pos1 = selection.getPlayerPos1(player) ?: run {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        val pos2 = selection.getPlayerPos2(player) ?: run {
            player.sendLang("message-no-region", pluginId to "pluginId")
            return
        }
        val steps = blocks.coerceIn(1, SELECTION_EDIT_MAX_BLOCKS)
        // 选区本来就按方块坐标存 (点的中心 5.5 落到方块 5), 这里同样取整: 全程按方块算, 不引入小数偏移
        val first = intArrayOf(floor(pos1.x).toInt(), floor(pos1.y).toInt(), floor(pos1.z).toInt())
        val second = intArrayOf(floor(pos2.x).toInt(), floor(pos2.y).toInt(), floor(pos2.z).toInt())
        val (axis, positive) = snapAxis(player.eyeLocation.direction)
        val moved = moveSelectionFace(player, first, second, axis, positive, steps, expand) ?: return
        val min = IntArray(3) { minOf(first[it], second[it]) }
        val max = IntArray(3) { maxOf(first[it], second[it]) }
        // 领地校验覆盖**所有会被改动的方块**: 老选区 ∪ 新选区的包围盒。扩展动的是新层 (新选区 ⊇
        // 老选区), 收缩清掉的是被砍掉的层 (老选区 ⊇ 新选区), 取并集两条路都不会漏。fail-closed。
        val checkMin = IntArray(3) { minOf(min[it], moved.first[it], moved.second[it]) }
        val checkMax = IntArray(3) { maxOf(max[it], moved.first[it], moved.second[it]) }
        if (!pipeline.checkProtection(player, locationOf(player, checkMin), locationOf(player, checkMax), needBreak = false)) {
            return
        }
        if (expand) expandSelection(player, moved, axis, positive, min, max)
        else contractSelection(player, moved, axis, positive, min, max)
    }

    /** [moveSelectionFace] 的结果: 移动后的两个点 (保持 pos1/pos2 的角色) */
    private class MovedFace(val first: IntArray, val second: IntArray)

    /**
     * 把选区在 [axis] 轴上朝视线那一面移动 [steps] 格 —— 扩展与收缩共用
     *
     * 只算几何、只做校验: 不写方块, 也不改传进来的数组 (改的是副本)。
     * - 扩展: 往外推。只对 Y 轴做世界高度夹取 (被吃掉的那几格丢掉, 夹取后的结果照旧应用);
     *   一格都推不动时发 limit 提示并返回 null。
     * - 收缩: 往里收。该轴会缩到 1 格以下就整体不动作, 发 limit 提示并返回 null。
     */
    private fun moveSelectionFace(
        player: Player,
        first: IntArray,
        second: IntArray,
        axis: Int,
        positive: Boolean,
        steps: Int,
        expand: Boolean
    ): MovedFace? {
        val newFirst = first.copyOf()
        val newSecond = second.copyOf()
        // 大小关系现算, 不假设 pos1 就是小的一头。pos1IsFirst 必须在这里定下来: lower/upper 是
        // 新数组的别名, 下面会就地改掉内容, 等移动完再比较就只能读到改后的值
        val pos1IsFirst = first[axis] <= second[axis]
        val lower = if (pos1IsFirst) newFirst else newSecond
        val upper = if (pos1IsFirst) newSecond else newFirst
        // 要动的是朝向那一面: 正方向动 upper, 负方向动 lower
        val target = if (positive) upper else lower
        if (!expand) {
            // 收缩的下限: 该轴至少留 1 格。会缩成 0 格或反向就整体不动作 —— 宁可不动, 也不要
            // 出现空选区或反向选区 (反向之后"扩展"会朝反方向长, 玩家看到的框和预期完全相反)
            val extent = upper[axis] - lower[axis] + 1
            if (extent - steps < 1) {
                player.sendLang("message-selection-contract-limit", pluginId to "pluginId", extent to "extent")
                return null
            }
            target[axis] += if (positive) -steps else steps
            return MovedFace(newFirst, newSecond)
        }
        val before = target[axis]
        val after = before + if (positive) steps else -steps
        // 世界边界夹取: Y 用世界高度 (BukkitWorld.getMinY()/getMaxY() 就是 world.getMinHeight() /
        // world.getMaxHeight() - 1), X/Z 用世界边界 (REV-06)。越界的选区点会让框画到世界外, 而且
        // 之后复制是按层数算的 —— 界外那一层根本放不上, 等于白算。被吃掉的那几格就丢掉, 但夹取后
        // 的结果照旧应用 (不整体放弃), 只额外提示一句, 免得玩家以为命令没生效。
        val clamped = clampAxis(player.world, axis, after)
        // 夹完必须真的朝那一面移动了才算扩展: 顶在边界上, 或者选区本来就在界外 (夹取结果反向) 都算
        // "到边界了" —— 只提示, 不写方块也不动选区
        if (if (positive) clamped <= before else clamped >= before) {
            sendExpandLimit(player, axis, clamped)
            return null
        }
        target[axis] = clamped
        if (clamped != after) sendExpandLimit(player, axis, clamped)
        return MovedFace(newFirst, newSecond)
    }

    /**
     * 扩展被世界边界挡住时的提示 (推不动 / 只推动了一部分, 两处共用)
     *
     * 文案要中性: 挡住的可能是上下, 也可能是东西南北 —— REV-06 之前只做 Y 夹取, 文案里写死了
     * "世界高度…这一层", X/Z 夹取上线后就对不上了 (LW-R3-01)。
     */
    private fun sendExpandLimit(player: Player, axis: Int, pos: Int) {
        player.sendLang(
            "message-selection-expand-limit",
            pluginId to "pluginId",
            axisName(axis) to "axis",
            pos to "pos"
        )
    }

    /**
     * 扩展: 把选区最外那一层 (朝视线那一面) 原样复制到新增的每一层
     *
     * 分两轮 (和 [tryReplaceArea] 同一个套路):
     * 1. 在区块线程上**只读**源层, 统计每种方块各需要多少个 —— 玩家线程一行方块都不读;
     * 2. 逐种扣材料: 任何一种不够就把已经扣掉的原样退回并整体放弃, 一个方块都不写; 全部扣成功
     *    才进第二轮写入。所以"要么全够要么全不写"成立, 不依赖"扣费期间别人不会同时扣"这种假设。
     */
    private fun expandSelection(
        player: Player,
        moved: MovedFace,
        axis: Int,
        positive: Boolean,
        min: IntArray,
        max: IntArray
    ) {
        val world = player.world
        // 源层 = 老选区在朝向那一面最外的一层; 目标面 = 移动后的那一面 (被世界高度夹过, 可能少几层)
        val sourceCoord = if (positive) max[axis] else min[axis]
        val targetCoord =
            if (positive) maxOf(moved.first[axis], moved.second[axis]) else minOf(moved.first[axis], moved.second[axis])
        val layers = abs(targetCoord - sourceCoord)
        // 要写的窗口 = 老选区的平面范围 ∩ 世界边界 (X/Z, REV-06)。界外的格子写不了, 所以不读也不
        // 计费 —— 计费按"源层这个窗口里的材料 × 层数"算, 天然就是夹取后的实际交集 (9C 红线:
        // 计费 = 真正会写的方块)。选区本身不动: 夹取只影响这次读什么、写什么、扣什么。
        val writeMin = intArrayOf(min[0], min[1], min[2])
        val writeMax = intArrayOf(max[0], max[1], max[2])
        if (!clampXZToBorder(world, writeMin, writeMax)) {
            // 整块都在世界边界之外: 没有可写的位置, 一个方块都不读不写
            player.sendLang("message-shape-none", pluginId to "pluginId")
            return
        }
        // 实际会写下去的那几层 —— 操作前事件报的就是它
        val targetSlab = slabRegion(axis, sourceCoord + if (positive) 1 else -1, targetCoord, writeMin, writeMax)
        if (!pipeline.fireOperation(player, OperationType.EXPAND, targetSlab)) return
        if (!pipeline.beginEdit(player)) return
        EditBudget.configure(pipeline.blocksPerTick())
        // 源层沿 [axis] 平移复制, 平面内两个坐标原样对应, 所以把源层压成一维数组存起来最省事
        val plane = PlaneIndex(axis, writeMin, writeMax)
        val source = arrayOfNulls<BlockState>(plane.size)
        runOnChunkThreads(
            slabRegion(axis, sourceCoord, sourceCoord, writeMin, writeMax).splitByChunk(),
            world,
            work = { _, slice ->
                val weWorld = BukkitAdapter.adapt(world)
                for (vector in slice.region) {
                    source[plane.indexOf(vector.x(), vector.y(), vector.z())] = weWorld.getBlock(vector)
                }
            },
            done = { timedOut ->
                // 统计是纯内存操作, 顺手在区块线程上做, 玩家线程只拿结果
                val perLayer = if (timedOut) emptyMap() else countMaterials(source)
                pipeline.onPlayerThread(player) {
                    if (timedOut) {
                        // 源层没读完: 什么都还没扣、也没写, 直接收尾
                        pipeline.endEdit(player)
                        player.sendLang("message-edit-failed", pluginId to "pluginId")
                        return@onPlayerThread
                    }
                    if (perLayer.isEmpty()) {
                        // 源层全是空气: 没有可复制的, 一个方块都不写, 选区也不动
                        pipeline.endEdit(player)
                        player.sendLang("message-selection-expand-empty", pluginId to "pluginId")
                        return@onPlayerThread
                    }
                    // 每一层都照抄源层, 所以每种材料的用量 = 源层里该材料的个数 × 层数
                    val needed = perLayer.mapValues { it.value * layers }
                    if (!pipeline.consumeAll(player, needed)) {
                        pipeline.endEdit(player)
                        return@onPlayerThread
                    }
                    selection.updateSelection(player, moved.first, moved.second)
                    pipeline.prepareEdit(needed.values.sum(), player)
                    val actor = pipeline.actorOf(player)
                    val targetChunks = targetSlab.splitByChunk()
                    // 每个区块实际写下去多少 (按材料记), 收尾时当成本表用
                    val written = AtomicReferenceArray<HashMap<Material, Int>?>(targetChunks.size)
                    pipeline.writeChunks(
                        player,
                        targetChunks,
                        "message-selection-expand-susses",
                        charged = needed,
                        costs = { targetChunks.indices.map { written.get(it) ?: emptyMap() } },
                        operation = OperationType.EXPAND
                    ) { index, slice, _ ->
                        val counts = written.get(index)
                            ?: HashMap<Material, Int>().also { written.set(index, it) }
                        WorldEditTool.writeBlocks(
                            world,
                            slice.region,
                            actor,
                            stateAt = { x, y, z -> source[plane.indexOf(x, y, z)]?.takeUnless { it.blockType.isAir() } },
                            onWritten = { state -> counts.add(state.blockType) }
                        )
                    }
                }
            }
        )
    }

    /**
     * 收缩: 把被砍掉的那几层全部清成空气
     *
     * 只跑一轮: 空气不计材料, 没有"先扣费再写"的需求, 所以快照 (撤销用) 与清空气在同一个
     * [runOnChunkThreads] 里完成。被清掉的非空气方块数从快照里数出来记进撤销记录 —— 只记账,
     * 不产生材料返还 (与 /pwe set air 一致)。一层空气都没清到时连撤销记录都不写。
     */
    private fun contractSelection(
        player: Player,
        moved: MovedFace,
        axis: Int,
        positive: Boolean,
        min: IntArray,
        max: IntArray
    ) {
        val newCoord =
            if (positive) maxOf(moved.first[axis], moved.second[axis]) else minOf(moved.first[axis], moved.second[axis])
        // 被砍掉的层 = 老选区里、新选区之外的那几层
        val from = if (positive) newCoord + 1 else min[axis]
        val to = if (positive) max[axis] else newCoord - 1
        val clearedLayers = slabRegion(axis, from, to, min, max)
        // 操作前事件必须在**任何状态改动之前**: 下面的 updateSelection 会真的动选区
        if (!pipeline.fireOperation(player, OperationType.CONTRACT, clearedLayers)) return
        if (!pipeline.beginEdit(player)) return
        selection.updateSelection(player, moved.first, moved.second)
        val regions = clearedLayers.splitByChunk()
        // 每个区块被清掉多少非空气方块 —— 同一区块的切片在同一线程上顺序执行, 普通 IntArray 就够
        val cleared = IntArray(regions.size)
        // 处理提示用上界 (这一层里所有方块数), 真正扣的是 0 (空气不计材料)
        pipeline.prepareEdit(regions.sumOf { it.volume.toInt() }, player)
        val actor = pipeline.actorOf(player)
        // AIR 一定存在 (哪个版本都有), WorldEdit 把这些常量标成可空只是因为老版本可能没有新方块
        val air = BlockTypes.AIR!!.defaultState
        pipeline.writeChunks(
            player,
            regions,
            "message-selection-contract-susses",
            charged = emptyMap(),
            costs = { regions.indices.map { index -> if (cleared[index] == 0) emptyMap() else mapOf(Material.AIR to cleared[index]) } },
            operation = OperationType.CONTRACT
        ) { index, slice, states ->
            for (position in slice.offset until slice.offset + slice.size) {
                val state = states[position] ?: continue
                if (!state.blockType.isAir()) cleared[index]++
            }
            WorldEditTool.setBlocksIf(player.world, slice.region, air, actor) { _, _, _ -> true }
        }
    }

    /**
     * 把选区里的建筑沿视线方向平移 [blocks] 格 (对齐 WE 的 `//move`)
     *
     * 目标区 = 源区 (选区) 沿方向平移; 写入目标区 (源内容)、源区清成空气; **选区本身不动**
     * (WE 的 `//move` 默认也不动, `-s` 之类的 flag 不做)。
     *
     * 两轮管线 (重叠安全的关键): 第一轮在区块线程上**只读**源区, 压成一维快照; 玩家线程统计材料 →
     * [consumeAll]; 第二轮写入只查第一轮的快照 ([WorldEditTool.writeBlocks] 的 `stateAt` 是纯查表),
     * **写入阶段一次世界读都没有** —— 源区与目标区可能重叠 (steps < 选区尺寸), 而各区块任务的执行
     * 顺序不可依赖, 只有"一切以第一轮快照为准"才能保证搬出去的内容和搬之前一模一样。
     *
     * 越界 = **整体不动作**: 目标框只要出去一格, 那部分内容就永远丢了 (源区已经清掉、目标区又写不
     * 进去), 所以宁可不动也不丢 —— 与 expand/paste 的"夹取后照常执行"是有意的两套策略。
     *
     * @param blocks 平移格数, 内部 clamp 到 1..[SELECTION_EDIT_MAX_BLOCKS]
     */
    fun moveSelection(player: Player, blocks: Int) {
        val region = selection.selectionRegion(player) ?: return
        val steps = blocks.coerceIn(1, SELECTION_EDIT_MAX_BLOCKS)
        val (axis, positive) = snapAxis(player.eyeLocation.direction)
        val min = intArrayOf(region.minimumPoint.x(), region.minimumPoint.y(), region.minimumPoint.z())
        val max = intArrayOf(region.maximumPoint.x(), region.maximumPoint.y(), region.maximumPoint.z())
        // 位移只在吸附到的那一轴上
        val delta = intArrayOf(0, 0, 0)
        delta[axis] = if (positive) steps else -steps
        val targetMin = intArrayOf(min[0] + delta[0], min[1] + delta[1], min[2] + delta[2])
        val targetMax = intArrayOf(max[0] + delta[0], max[1] + delta[1], max[2] + delta[2])
        if (!moveTargetInWorld(player, targetMin, targetMax)) {
            player.sendLang("message-move-out-of-bounds", pluginId to "pluginId")
            return
        }
        // 领地校验覆盖**会被改动的全部方块**: 源区 ∪ 目标区的包围盒 (清源区 + 覆盖目标区, 所以按
        // 替换操作要求破坏权限, 与 replace/paste 同类)。fail-closed。
        val checkMin = intArrayOf(minOf(min[0], targetMin[0]), minOf(min[1], targetMin[1]), minOf(min[2], targetMin[2]))
        val checkMax = intArrayOf(maxOf(max[0], targetMax[0]), maxOf(max[1], targetMax[1]), maxOf(max[2], targetMax[2]))
        if (!pipeline.checkProtection(player, locationOf(player, checkMin), locationOf(player, checkMax), needBreak = true)) return
        // 源 ∪ 目标的并集: 既是领地校验的框, 也是操作前事件报的范围、第二轮要写的范围 —— 一处算好
        val union = CuboidRegion(
            BlockVector3.at(checkMin[0], checkMin[1], checkMin[2]),
            BlockVector3.at(checkMax[0], checkMax[1], checkMax[2])
        )
        if (!pipeline.fireOperation(player, OperationType.MOVE, union)) return
        if (!pipeline.beginEdit(player)) return
        EditBudget.configure(pipeline.blocksPerTick())
        val world = player.world
        // 第一轮: 只读源区。写入阶段全靠这份快照, 所以它必须完整
        val source = arrayOfNulls<BlockState>(region.volume.toInt())
        val index = BoxIndex(min, max)
        runOnChunkThreads(
            region.splitByChunk(),
            world,
            work = { _, slice ->
                val weWorld = BukkitAdapter.adapt(world)
                for (vector in slice.region) {
                    source[index.indexOf(vector.x(), vector.y(), vector.z())] = weWorld.getBlock(vector)
                }
            },
            done = { timedOut ->
                // 统计是纯内存操作, 顺手在区块线程上做, 玩家线程只拿结果
                val moved = if (timedOut) emptyMap() else countMaterials(source)
                pipeline.onPlayerThread(player) {
                    if (timedOut) {
                        // 源区没读完: 什么都还没扣、也没写, 直接收尾
                        pipeline.endEdit(player)
                        player.sendLang("message-edit-failed", pluginId to "pluginId")
                        return@onPlayerThread
                    }
                    if (moved.isEmpty()) {
                        // 源区全是空气: 没有可搬的。照字面执行会把目标区清空, 但那不是玩家要的"搬
                        // 东西", 所以直接不动 (与 expand 的"没有可复制的"同一处理)
                        pipeline.endEdit(player)
                        player.sendLang("message-move-empty", pluginId to "pluginId")
                        return@onPlayerThread
                    }
                    // 目标区写下去的就是源区这份内容, 所以用量 = 源区的统计 (与 expand 同口径:
                    // 搬出来的方块算"新放上的", 要按材料扣; 源区清空气不退料, 同 contract)
                    if (!pipeline.consumeAll(player, moved)) {
                        pipeline.endEdit(player)
                        return@onPlayerThread
                    }
                    val actor = pipeline.actorOf(player)
                    // 并集按区块切分: 同一区块只出现一次、只取一份快照 —— 源区与目标区重叠时若分成
                    // 两批处理, 后一批的快照会拍到前一批刚写下去的内容, 撤销就还原不回去
                    pipeline.prepareEdit(union.volume.toInt(), player)
                    val regions = union.splitByChunk()
                    // 每个区块实际写下去多少 (空气也记: 撤销要能还原被清掉的源区), 收尾时当成本表
                    val written = AtomicReferenceArray<HashMap<Material, Int>?>(regions.size)
                    val air = BlockTypes.AIR!!.defaultState
                    pipeline.writeChunks(
                        player,
                        regions,
                        "message-move-susses",
                        charged = moved,
                        costs = { regions.indices.map { written.get(it) ?: emptyMap() } },
                        operation = OperationType.MOVE
                    ) { chunkIndex, slice, _ ->
                        val counts = written.get(chunkIndex)
                            ?: HashMap<Material, Int>().also { written.set(chunkIndex, it) }
                        WorldEditTool.writeBlocks(
                            world,
                            slice.region,
                            actor,
                            // 只查第一轮的源快照, 一次世界读都没有: 源/目标重叠也不会读到刚写下去的内容
                            stateAt = { x, y, z ->
                                when {
                                    // 目标区: 取源快照里 (减去位移) 的那一格
                                    x in targetMin[0]..targetMax[0] &&
                                        y in targetMin[1]..targetMax[1] &&
                                        z in targetMin[2]..targetMax[2] ->
                                        source[index.indexOf(x - delta[0], y - delta[1], z - delta[2])]
                                    // 源区里、目标区之外的部分: 清成空气
                                    x in min[0]..max[0] && y in min[1]..max[1] && z in min[2]..max[2] -> air
                                    // 并集里既不属于源也不属于目标的格子: 不动
                                    else -> null
                                }
                            },
                            onWritten = { state -> counts.add(state.blockType) }
                        )
                    }
                }
            }
        )
    }

    /**
     * 目标框是不是完全在世界内 (世界高度 + X/Z 世界边界)
     *
     * 只有 move 用它做"整体拒绝": 出去一格就意味着有方块搬不过去 (源区清了、目标区写不上),
     * 所以这里不像 expand/paste 那样夹取。世界边界读不到时只按高度判断 (退回改动前的行为)。
     */
    private fun moveTargetInWorld(player: Player, min: IntArray, max: IntArray): Boolean {
        val weWorld = BukkitAdapter.adapt(player.world)
        if (min[AXIS_Y] < weWorld.minY || max[AXIS_Y] > weWorld.maxY) return false
        val border = worldBorderBounds(player.world) ?: return true
        return min[AXIS_X] >= border[0] && max[AXIS_X] <= border[1] &&
            min[AXIS_Z] >= border[2] && max[AXIS_Z] <= border[3]
    }

    /**
     * 长方体里"方块坐标 → 一维下标"的换算 (y → z → x, 与 WE 的区域遍历顺序一致)
     *
     * move 的第一轮快照与第二轮查表必须按同一个顺序寻址, 所以共用一个下标器。
     * [ClipboardVolume] 里那份是"相对剪贴板原点"的偏移下标, 这里要的是绝对坐标, 不通用。
     */
    private class BoxIndex(private val min: IntArray, private val max: IntArray) {

        private val sizeX = max[AXIS_X] - min[AXIS_X] + 1
        private val sizeZ = max[AXIS_Z] - min[AXIS_Z] + 1

        fun indexOf(x: Int, y: Int, z: Int): Int =
            ((y - min[AXIS_Y]) * sizeZ + (z - min[AXIS_Z])) * sizeX + (x - min[AXIS_X])
    }

    /**
     * 建一个沿 [axis] 从 [from] 到 [to] 层、另外两轴取 [min]..[max] 的长方体
     *
     * 扩缩用到的"源层 / 新层 / 被砍掉的层"都是这种薄片, 所以抽出来共用 (from/to 会自己摆正)。
     */
    private fun slabRegion(axis: Int, from: Int, to: Int, min: IntArray, max: IntArray): CuboidRegion {
        val low = intArrayOf(min[0], min[1], min[2])
        val high = intArrayOf(max[0], max[1], max[2])
        low[axis] = minOf(from, to)
        high[axis] = maxOf(from, to)
        return CuboidRegion(BlockVector3.at(low[0], low[1], low[2]), BlockVector3.at(high[0], high[1], high[2]))
    }

    /**
     * 与 [axis] 垂直的那一层平面的下标器
     *
     * 复制时"源层 → 新层"只沿 [axis] 平移, 平面内的两个坐标原样对应, 所以把位置压成一个连续
     * 下标 (u * sizeV + v) 就能用数组存源层、再按同样的下标写新层 —— 不用按坐标查哈希表。
     */
    private class PlaneIndex(axis: Int, private val min: IntArray, private val max: IntArray) {

        private val axisU: Int
        private val axisV: Int
        private val minU: Int
        private val minV: Int
        private val sizeV: Int

        init {
            val (u, v) = when (axis) {
                AXIS_X -> AXIS_Y to AXIS_Z
                AXIS_Y -> AXIS_X to AXIS_Z
                else -> AXIS_X to AXIS_Y
            }
            axisU = u
            axisV = v
            minU = min[u]
            minV = min[v]
            sizeV = max[v] - min[v] + 1
        }

        /** 平面里一共有多少格 (源层数组的长度) */
        val size: Int get() = (max[axisU] - minU + 1) * sizeV

        fun indexOf(x: Int, y: Int, z: Int): Int {
            val u = when (axisU) {
                AXIS_X -> x
                AXIS_Y -> y
                else -> z
            }
            val v = when (axisV) {
                AXIS_X -> x
                AXIS_Y -> y
                else -> z
            }
            return (u - minU) * sizeV + (v - minV)
        }
    }

    private companion object {
        /**
         * 一次扩 / 缩的格数上限
         *
         * 扩 / 缩现在会真的复制 / 清掉方块, 格数直接决定要改动的方块数; 设上限是为了防止手滑
         * (例如把 1000 敲成 10000) 一下子把选区撑到几个区块以外 —— 那样一次操作要跑很久。
         */
        const val SELECTION_EDIT_MAX_BLOCKS = 100

        /**
         * 圆柱半径 / 高度的上限
         *
         * 只是 sanity cap: 形状本来就被裁剪在选区内, 给得再大也只是"填满选区"; 卡个上限是为了
         * 挡住手滑敲出来的一长串数字。
         */
        const val SHAPE_MAX_SIZE = 256

        /**
         * 形状尺寸选择器的初值 (半径 to 高度)
         *
         * 没有进行中的状态时用它起一份; 取 5 是为了和旧的 3x3 预设里最小的一档一致 —— 界面一打开
         * 就跳到一个玩家没见过的大数字, 比多按几次 ± 更让人意外。
         */
        val SHAPE_SIZE_DEFAULT = 5 to 5
    }
}

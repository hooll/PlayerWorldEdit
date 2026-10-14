package cn.stingcraft.plugin.inventory

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.until.displayName
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import taboolib.common.platform.function.pluginId
import taboolib.library.xseries.XMaterial
import taboolib.module.chat.colored
import taboolib.module.ui.buildMenu
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.impl.ChestImpl
import taboolib.module.ui.type.impl.PageableChestImpl
import taboolib.platform.util.Slots
import taboolib.platform.util.buildItem
import taboolib.platform.util.sendLang

object WorldEditOpInventory {

    /**
     * 步进选择器一次点击动多少格 (每排的按钮是 ±1 与 ±[SHAPE_STEP] 两档)
     *
     * 只活在界面层: 数值的上下限与初值都在 manager (那里才做校验), 这里纯粹是"点一下动多少"的手感。
     */
    private const val SHAPE_STEP = 10

    private val worldEditManager = PlayerWorldEdit.worldEditManager

    fun open(player: Player) {
        player.openMenu(build(player))
    }

    private fun build(player: Player): Inventory {
        val hasRegion = worldEditManager.hasRegion(player)
        val size = if (hasRegion) worldEditManager.getCountInRegion(player) else 0
        return buildMenu<ChestImpl>("&f创世神操作 &4| 快捷菜单".colored()) {
            rows(5)
            // 锁定整页点击: 没有注册 onClick 的格子（如信息纸）原本可以把物品拿走
            onClick(true) { }
            set(4, buildItem(XMaterial.PAPER) {
                name = "&f| 选区信息"
                if (hasRegion) {
                    lore += "&7| 已选区域: &e${size} &7个方块"
                } else {
                    lore += "&c| 尚未选择完整区域"
                    lore += "&7| 左键=点1, 右键=点2"
                }
                colored()
            })
            set(10, buildItem(XMaterial.CHEST) {
                name = "&f| 材料背包"
                lore += "&7| 存放与取出填充材料"
                colored()
            }) {
                MaterialPackInventory.open(player)
            }
            set(12, buildItem(XMaterial.GRASS_BLOCK) {
                name = "&f| 设置方块"
                lore += "&7| 用方块填充整个选区"
                colored()
            }) {
                openBlockPicker(player, "&f选择要设置的方块") { material ->
                    player.closeInventory()
                    worldEditManager.trySetArea(player, material)
                }
            }
            set(14, buildItem(XMaterial.ANVIL) {
                name = "&f| 替换方块"
                lore += "&7| 第一步: 选择要替换的原方块"
                colored()
            }) {
                openBlockPicker(player, "&f选择原方块") { from ->
                    openBlockPicker(player, "&f选择新方块") { to ->
                        player.closeInventory()
                        worldEditManager.tryReplaceArea(player, from, to)
                    }
                }
            }
            set(16, buildItem(XMaterial.ARROW) {
                name = "&f| 撤销"
                lore += "&7| 撤销最近一次操作并返还材料"
                colored()
            }) {
                worldEditManager.undo(player, 1) { done, timedOut ->
                    when {
                        // 超时不等于"没有可撤销的": 那条记录已经放回队列, 玩家可以重试
                        timedOut -> player.sendLang("message-undo-timeout", pluginId to "pluginId", done to "times")
                        done <= 0 -> player.sendLang("message-undo-none", pluginId to "pluginId")
                        else -> player.sendLang("message-undo-susses", pluginId to "pluginId", done to "times")
                    }
                }
            }
            // 扩展 / 收缩: 每次点击 1 格 (要更多格数用命令的 [格数] 参数)。
            // 菜单扩到 5 行, 这两个按钮排在第 4 行的对称位置; 第 3 行与第 5 行留空给下一轮的墙/圆柱按钮。
            set(29, buildItem(XMaterial.LIME_STAINED_GLASS_PANE) {
                name = "&f| 扩展选区"
                lore += "&7| 朝你视线的那一面外扩 1 格"
                lore += "&7| 把最外层方块复制出去 (消耗材料)"
                colored()
            }) {
                worldEditManager.resizeSelection(player, 1, expand = true)
                // 第 4 槽的方块数是 build() 那一刻算的, 不重开菜单就会一直显示旧值
                // (MaterialPackInventory 每次取物后同样 open(player) 刷新)
                open(player)
            }
            set(33, buildItem(XMaterial.RED_STAINED_GLASS_PANE) {
                name = "&f| 收缩选区"
                lore += "&7| 朝你视线的那一面内缩 1 格"
                lore += "&7| 被移除的层清成空气"
                colored()
            }) {
                worldEditManager.resizeSelection(player, 1, expand = false)
                // 同上: 重开菜单让第 4 槽的方块数跟上新的选区
                open(player)
            }
            // 形状生成。墙一步到底; 两个圆柱再点一次尺寸预设 —— 和上面"替换方块"一样是
            // 连着选两次, 不是二级菜单。
            set(20, buildItem(XMaterial.BRICKS) {
                name = "&f| 生成墙"
                lore += "&7| 沿选区四周生成四面竖墙"
                lore += "&7| 不含顶和底"
                colored()
            }) {
                openBlockPicker(player, "&f选择墙的方块") { material ->
                    player.closeInventory()
                    worldEditManager.tryGenerateWall(player, material)
                }
            }
            set(22, buildItem(XMaterial.CAULDRON) {
                name = "&f| 实心圆柱"
                lore += "&7| 以你的位置为中心生成实心圆柱"
                lore += "&7| 下一步选半径与高度"
                lore += "&7| 半径和高度可以自己增减"
                colored()
            }) {
                openBlockPicker(player, "&f选择实心圆柱的方块") { material ->
                    openNumberStepper(player, "&f选择圆柱尺寸", "半径", "高度") { radius, height ->
                        player.closeInventory()
                        worldEditManager.tryGenerateCylinder(player, material, radius, height)
                    }
                }
            }
            set(24, buildItem(XMaterial.FLOWER_POT) {
                name = "&f| 空心圆柱"
                lore += "&7| 以你的位置为中心"
                lore += "&7| 只生成半径处那一圈壳 (厚 1 格)"
                lore += "&7| 内部方块不动"
                lore += "&7| 下一步选半径与高度, 可以自己增减"
                colored()
            }) {
                openBlockPicker(player, "&f选择空心圆柱的方块") { material ->
                    openNumberStepper(player, "&f选择圆柱尺寸", "半径", "高度") { radius, height ->
                        player.closeInventory()
                        worldEditManager.tryGenerateHollowCylinder(player, material, radius, height)
                    }
                }
            }
            // 剪贴板: 复制 / 旋转 / 粘贴排在第 5 行的 38/40/42。旋转再点一次角度 —— 和圆柱尺寸
            // 一样是连着选两次, 不算二级菜单。
            set(38, buildItem(XMaterial.WRITABLE_BOOK) {
                name = "&f| 复制选区"
                lore += "&7| 把整个选区存进你的剪贴板"
                lore += "&7| 不消耗材料, 不改变方块"
                colored()
            }) {
                player.closeInventory()
                worldEditManager.tryCopy(player)
            }
            set(40, buildItem(XMaterial.COMPASS) {
                name = "&f| 旋转剪贴板"
                lore += "&7| 绕复制时的位置水平旋转"
                lore += "&7| 方块朝向会一起转"
                lore += "&7| 下一步选角度"
                colored()
            }) {
                openAnglePicker(player, "&f选择旋转角度") { degrees ->
                    player.closeInventory()
                    worldEditManager.rotateClipboard(player, degrees)
                }
            }
            set(42, buildItem(XMaterial.STRUCTURE_BLOCK) {
                name = "&f| 粘贴剪贴板"
                lore += "&7| 剪贴板原点贴到你脚下"
                lore += "&7| 空气会清掉目标位置的方块"
                lore += "&7| 按材料消耗, 可撤销"
                colored()
            }) {
                player.closeInventory()
                worldEditManager.tryPaste(player)
            }
            // 镜像: 与旋转同一类"只改剪贴板"的变换, 所以排在粘贴右边 (38/40/42 是复制/旋转/粘贴,
            // 不动)。这里只给一个按钮 = 按玩家朝向镜像 (与 WE 的 //flip 默认一致); 上下镜像等
            // 需要指定方向的情况走命令, 不为一个按钮再加一层方向选择界面。
            set(44, buildItem(XMaterial.SHIELD) {
                name = "&f| 镜像剪贴板"
                lore += "&7| 沿你面朝的方向镜像 (左右或前后)"
                lore += "&7| 方块朝向会一起镜像"
                lore += "&7| 要上下镜像用命令: &f/pwe flip u"
                colored()
            }) {
                player.closeInventory()
                worldEditManager.flipClipboard(player, "me")
            }
        }
    }

    /**
     * 剪贴板旋转角度预设 (90 / 180 / 270)
     *
     * 与圆柱尺寸同一套做法: 箱子界面没有输入框, 做"等玩家打字"的输入流程又要多一份 per-player
     * 待输入状态, 所以给三个按钮; 角度列表取自 manager, 不在这里另抄一份。
     */
    private fun openAnglePicker(player: Player, title: String, callback: (degrees: Int) -> Unit) {
        val angles = worldEditManager.rotateAngles()
        player.openMenu(buildMenu<ChestImpl>(title.colored()) {
            rows(3)
            // 和主菜单一样锁住整页: 没注册 onClick 的格子原本可以把物品拿走
            onClick(true) { }
            set(4, buildItem(XMaterial.PAPER) {
                name = "&f| 旋转角度"
                lore += "&7| 绕 Y 轴 (水平) 旋转剪贴板"
                colored()
            })
            angles.forEachIndexed { index, degrees ->
                set(11 + index * 2, buildItem(XMaterial.COMPASS) {
                    name = "&f| 旋转 $degrees°"
                    colored()
                }) {
                    callback(degrees)
                }
            }
        })
    }

    /**
     * 形状尺寸的数字步进选择器 (半径 / 高度各一排)
     *
     * 箱子界面没有输入框, 而"等玩家打字"的输入流程要多一份 per-player 待输入状态 (前几轮反复踩过
     * 按 UUID 只增不删的坑), 所以改成点按钮增减: 每排 `-10 -1 [当前值] +1 +10`, 点完重开本菜单刷新
     * 显示, 底部确认生成。待确认的数值本身存在 manager 里 (挂 clearPlayerPos 的退出清理路径),
     * 这里只负责显示与派发。
     *
     * 实心圆柱与空心圆柱共用这一个组件: 只有标题、两排的标签和确认回调不同, 不为两个形状各写一份。
     */
    private fun openNumberStepper(
        player: Player,
        title: String,
        firstLabel: String,
        secondLabel: String,
        onConfirm: (first: Int, second: Int) -> Unit
    ) {
        val (first, second) = worldEditManager.pendingShapeSize(player)
        // 点完增减原地刷新: 重开同一个菜单即可 (扩缩按钮的既有写法)
        val refresh = { openNumberStepper(player, title, firstLabel, secondLabel, onConfirm) }
        player.openMenu(buildMenu<ChestImpl>(title.colored()) {
            rows(4)
            // 和主菜单一样锁住整页: 没注册 onClick 的格子原本可以把物品拿走
            onClick(true) { }
            set(4, buildItem(XMaterial.PAPER) {
                name = "&f| $firstLabel / $secondLabel"
                lore += "&7| 点两边的按钮增减, 到底/到顶就停住"
                lore += "&7| 范围 1 - ${worldEditManager.shapeSizeMax()}"
                lore += "&7| 点底部的按钮生成"
                colored()
            })
            // 一排: -10 -1 [当前值] +1 +10 —— 半径与高度两排共用这段布局
            fun row(startSlot: Int, label: String, value: Int, step: (Int) -> Unit) {
                set(startSlot, buildItem(XMaterial.RED_STAINED_GLASS_PANE) {
                    name = "&f| $label -$SHAPE_STEP"
                    colored()
                }) { step(-SHAPE_STEP) }
                set(startSlot + 1, buildItem(XMaterial.RED_STAINED_GLASS_PANE) {
                    name = "&f| $label -1"
                    colored()
                }) { step(-1) }
                set(startSlot + 2, buildItem(XMaterial.PAPER) {
                    name = "&f| $label: &e$value"
                    colored()
                })
                set(startSlot + 3, buildItem(XMaterial.LIME_STAINED_GLASS_PANE) {
                    name = "&f| $label +1"
                    colored()
                }) { step(1) }
                set(startSlot + 4, buildItem(XMaterial.LIME_STAINED_GLASS_PANE) {
                    name = "&f| $label +$SHAPE_STEP"
                    colored()
                }) { step(SHAPE_STEP) }
            }
            row(11, firstLabel, first) { delta ->
                worldEditManager.adjustShapeSize(player, delta, 0)
                refresh()
            }
            row(20, secondLabel, second) { delta ->
                worldEditManager.adjustShapeSize(player, 0, delta)
                refresh()
            }
            set(31, buildItem(XMaterial.EMERALD_BLOCK) {
                name = "&f| 确认生成"
                lore += "&7| 用当前的 $firstLabel / $secondLabel 生成"
                colored()
            }) {
                onConfirm(first, second)
            }
        })
    }

    private fun openBlockPicker(player: Player, title: String, callback: (Material) -> Unit) {
        // 空气放第一个, 渲染成屏障物品, 玩家一眼就知道是清空; 其余只列材料背包里已有的方块
        // (没有元素的空格子 TabooLib 不会派发点击, 点了不会有反应)
        val blocks = listOf(Material.AIR) + PlayerWorldEdit.packManager.getMaterialPack(player).materials
            .map { it.material }
            .distinct()
            .filter { it.isBlock && it != Material.AIR }
        player.openMenu(buildMenu<PageableChestImpl<Material>>(title.colored()) {
            rows(6)
            slots(Slots.CENTER)
            elements { blocks }
            onGenerate { _, element, _, _ ->
                if (element == Material.AIR) {
                    return@onGenerate buildItem(XMaterial.BARRIER) {
                        name = "&f| 空气"
                        lore += "&7| 清空选区内的所有方块"
                        colored()
                    }
                }
                return@onGenerate buildItem(element) {
                    name = "&f| ${element.displayName(player)}"
                    colored()
                }
            }
            onClick { _, element ->
                callback(element)
            }
            setNextPage(47) { _, hasNextPage ->
                if (hasNextPage) {
                    buildItem(XMaterial.SPECTRAL_ARROW) {
                        name = "§f| 下一页"
                    }
                } else {
                    buildItem(XMaterial.ARROW) {
                        name = "§7| 下一页"
                    }
                }
            }
            setPreviousPage(51) { _, hasPreviousPage ->
                if (hasPreviousPage) {
                    buildItem(XMaterial.SPECTRAL_ARROW) {
                        name = "§f| 上一页"
                    }
                } else {
                    buildItem(XMaterial.ARROW) {
                        name = "§7| 上一页"
                    }
                }
            }
        })
    }
}

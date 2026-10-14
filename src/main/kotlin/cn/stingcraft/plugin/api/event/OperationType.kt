package cn.stingcraft.plugin.api.event

/**
 * 会改动世界的操作类型 (两个事件与 API 共用)
 *
 * 只收"写方块"的操作。copy / rotate / flip 只动内存里的剪贴板, undo 的撤销记录是异步读出来的
 * (触发事件时还不知道会改哪里), 所以这三类不发这两个事件 —— 要观察剪贴板操作请直接用
 * [cn.stingcraft.plugin.api.PlayerWorldEditApi]。
 */
enum class OperationType {

    /** 等价命令: `/pwe set <方块>` */
    SET,

    /** 等价命令: `/pwe replace <原方块> <新方块>` */
    REPLACE,

    /** 等价命令: `/pwe expand [格数]` */
    EXPAND,

    /** 等价命令: `/pwe contract [格数]` */
    CONTRACT,

    /** 等价命令: `/pwe wall <方块>` */
    WALL,

    /** 等价命令: `/pwe cyl <方块> <半径> [高度]` */
    CYL,

    /** 等价命令: `/pwe hcyl <方块> <半径> [高度]` */
    HCYL,

    /** 等价命令: `/pwe move [格数]` */
    MOVE,

    /** 等价命令: `/pwe paste` (预览不写方块, 不发事件) */
    PASTE
}

package cn.stingcraft.plugin.util

import com.sk89q.jnbt.CompoundTag
import com.sk89q.jnbt.LinBusConverter
import com.sk89q.jnbt.NBTInputStream
import com.sk89q.jnbt.NBTOutputStream
import com.sk89q.worldedit.world.block.BaseBlock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 方块实体数据 (箱子里的物品 / 告示牌文字 / ……, 即方块的 NBT) 的读写
 *
 * 撤销快照与剪贴板都存 [BaseBlock] 而不是 [com.sk89q.worldedit.world.block.BlockState]:
 * `BlockState` 只有方块状态 (类型 + 属性), 方块实体数据不在里面 —— 撤一个装满了东西的箱子只会还原出
 * 空箱子, 复制/粘贴同理 (2026-10-14 的 R8)。
 *
 * **为什么用二进制 NBT 而不是 SNBT 文本**: WE 7.4.6 里带的 lin-bus SNBT 读侧解析不了空容器
 * (`{}` 与 `[]` 都会抛 NbtParseException, 实测见报告), 而空容器在方块实体数据里很常见 (空箱子的
 * `Items: []`) —— 用 SNBT 就会导致"编辑一个空箱子附近"直接失败。二进制 NBT 有显式的类型与长度,
 * 空容器没有任何问题。
 *
 * 序列化路径 (全部是 WE 自带的公开 API, 见报告的证据清单):
 * - 写: `BaseBlock.getNbtReference()` (lin-bus 的 `LinCompoundTag`) → `LinBusConverter.toJnbtTag`
 *   (WE 自带的 JNBT 桥) → `NBTOutputStream.writeNamedTag` (二进制) → Base64 文本;
 * - 读: Base64 → `NBTInputStream.readNamedTag` → `BlockStateHolder.toBaseBlock(CompoundTag)`
 *   (WE 自带的 JNBT → 方块 桥)。
 *
 * 已知限制 (报告里如实列出):
 * - **轮换/镜像不转方块实体数据本身**: `BlockTransformExtent.transform` 只按属性变换方块状态
 *   (朝向等), 牌子上的文字、箱子里的物品原样保留 —— 与 WE 的 `//rotate` / `//flip` 一致;
 * - **实体 (掉落的物品、生物、画框等) 不在快照范围内**: 撤销只还原方块, 不还原实体;
 * - **改动前写下的记录没有实体数据**: 反序列化后 `nbt` 为 null, 还原成"只有方块状态的方块"
 *   (改动前就是这个行为), 存量记录照常可用。
 */

/** NBT 二进制里的根名 (NBT 二进制格式要求有个根名, 这里只是个占位, 读回时不看它) */
private const val NBT_ROOT_NAME = "pwe"

/** 方块实体数据 → Base64 文本; 仅真正没有实体数据时返回 null; 读取/编码失败抛异常 */
internal fun nbtOf(block: BaseBlock): String? {
    val reference = block.nbtReference ?: return null
    val tag = checkNotNull(reference.value) { "NBT reference returned null" }
    return run {
        val bytes = ByteArrayOutputStream()
        NBTOutputStream(bytes).use { it.writeNamedTag(NBT_ROOT_NAME, LinBusConverter.toJnbtTag(tag)) }
        Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}

/** Base64 文本 → 方块实体数据 (JNBT 的 CompoundTag, 直接喂给 `BlockStateHolder.toBaseBlock`); 解析失败抛异常 */
internal fun nbtFrom(raw: String): CompoundTag {
    val bytes = Base64.getDecoder().decode(raw)
    val named = NBTInputStream(ByteArrayInputStream(bytes)).use { it.readNamedTag() }
    return named.tag as? CompoundTag
        ?: throw IllegalArgumentException("方块实体数据的根不是复合标签: ${named.name}")
}

/**
 * 方块实体数据能不能被完整保存 (读得到 + 序列化得了 + 读得回来)
 *
 * 写入前用它做校验: 保存不了就**拒绝这次操作**, 而不是先把世界改了、事后才发现撤销记录里丢了数据
 * (R8 的"不静默丢数据")。没有实体数据的方块直接算通过, 不花序列化的钱。
 */
internal fun canCaptureNbt(block: BaseBlock): Boolean {
    val encoded = nbtOf(block) ?: return true
    nbtFrom(encoded)
    return true
}

/** 强制读取并验证后固定 NBT, 避免稍后的编码/懒读取失败发生在破坏源区之后。 */
internal fun captureBlock(block: BaseBlock): BaseBlock {
    val encoded = nbtOf(block) ?: return block
    return block.toImmutableState().toBaseBlock(nbtFrom(encoded))
}

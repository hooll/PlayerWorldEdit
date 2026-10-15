package cn.stingcraft.plugin.database

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.data.MaterialPack
import cn.stingcraft.plugin.manager.MaterialPackStore
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.LifeCycle
import taboolib.common.io.newFile
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning
import taboolib.expansion.getAutoDataContainer
import taboolib.expansion.releaseAutoDataContainer
import taboolib.expansion.AutoDataContainer
import taboolib.expansion.setupPlayerDatabase
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object MaterialPackDataBase : MaterialPackStore {

    /** 数据容器 / 数据库里存材料包的那一列 */
    private const val MATERIAL_PACK_KEY = "materialPack"

    @Awake(LifeCycle.ENABLE)
    fun init(){
        setupPlayerDatabase(newFile(getDataFolder(), "data.db"))
    }

    @SubscribeEvent
    private fun setup(e:PlayerJoinEvent){
        val player = e.player
        submitAsync {
            runCatching { PlayerWorldEdit.packManager.load(player) }
                .onFailure { warning("[PlayerWorldEdit] 材料包登录加载失败 (${player.uniqueId}): $it") }
        }
    }

    @SubscribeEvent
    private fun onQuit(e: PlayerQuitEvent) {
        // 缓存保存与移除由同一 UUID 锁保护; 借用的 Auto 容器在每次访问结束即释放。
        PlayerWorldEdit.packManager.unload(e.player)
    }

    /** 仅借用 Auto 容器, 每次访问都释放正确的那张表; 离线收尾也不会留下刷新任务。 */
    internal fun <T> withContainer(uuid: UUID, action: (AutoDataContainer) -> T): T {
        try {
            return action(uuid.getAutoDataContainer())
        } finally {
            uuid.releaseAutoDataContainer()
        }
    }

    fun getMaterialPack(uuid: UUID): MaterialPack = read(uuid)

    override fun read(uuid: UUID): MaterialPack = withContainer(uuid) { container ->
        // 读取当前数据库而不是可能被周期刷新交错的 source。缺失键不隐式写入空包。
        val raw = container.database.get(uuid.toString(), MATERIAL_PACK_KEY)
        if (raw.isNullOrEmpty()) MaterialPack(mutableSetOf())
        else PlayerWorldEdit.gson.fromJson(decompressFromBase64(raw), MaterialPack::class.java)
    }

    /** AutoDataContainer.set 在本依赖中同步写库, 成功后更新 source; 只能调用一次。 */
    fun saveMaterialPack(uuid: UUID, materialPack: MaterialPack) = write(uuid, materialPack)
    fun saveMaterialPackNow(uuid: UUID, materialPack: MaterialPack) = write(uuid, materialPack)

    override fun write(uuid: UUID, pack: MaterialPack) {
        val value = compressToBase64(PlayerWorldEdit.gson.toJson(pack))
        withContainer(uuid) { container -> container[MATERIAL_PACK_KEY] = value }
    }

    private fun compressToBase64(json: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun decompressFromBase64(base64: String): String {
        val bytes = Base64.getDecoder().decode(base64)
        val input = GZIPInputStream(bytes.inputStream())
        return input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

}

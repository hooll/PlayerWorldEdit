package cn.stingcraft.plugin.manager

import cn.stingcraft.plugin.data.MaterialEntry
import cn.stingcraft.plugin.data.MaterialPack
import cn.stingcraft.plugin.database.MaterialPackDataBase
import org.bukkit.Material
import org.bukkit.entity.Player

class MaterialPackManager {


    fun getMaterialPack(player: Player): MaterialPack {
        val materialPack = MaterialPackDataBase.getMaterialPack(player)
        return materialPack
    }

    fun saveMaterialPack(player: Player, materialPack: MaterialPack) {
        MaterialPackDataBase.saveMaterialPack(player, materialPack)
    }

    /**
     * 添加物品列表
     * @param player 玩家
     * @param materials 物品列表
     */
    fun addMaterials(player: Player,materials: List<MaterialEntry>){
        val materialPack = getMaterialPack(player)
        val packMaterials = materialPack.materials
        materials.forEach { newEntry->
            val existing = packMaterials.find { it.material == newEntry.material }
            if (existing != null) {
                existing.amount += newEntry.amount
            } else {
                packMaterials.add(MaterialEntry(newEntry.material, newEntry.amount))
            }
        }
        saveMaterialPack(player, materialPack)
    }

    /**
     * 添加物品
     * @param player 玩家
     * @param material 物品
     * @param amount 数量
     */
    fun addMaterial(player: Player, material: Material, amount: Int) {
        if (amount <= 0) return
        val materialPack = getMaterialPack(player)
        val materialEntry = materialPack.materials.find { it.material == material } ?:run {
            val newMaterialEntry = MaterialEntry(material, amount)
            materialPack.materials.add(newMaterialEntry)
            saveMaterialPack(player, materialPack)
            return
        }
        materialEntry.amount += amount
        saveMaterialPack(player, materialPack)
    }

    /**
     * 移除物品
     * @param player 玩家
     * @param material 物品
     * @param amount 数量
     */
    fun removeMaterial(player: Player, material: Material, amount: Int) {
        val materialPack = getMaterialPack(player)
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
        saveMaterialPack(player, materialPack)
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
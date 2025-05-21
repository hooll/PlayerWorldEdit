package cn.stingcraft.plugin.command

import cn.stingcraft.plugin.PlayerWorldEdit
import cn.stingcraft.plugin.command.sub.*
import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.common.platform.command.*
import taboolib.common.platform.function.adaptLocation
import taboolib.common.util.Location
import taboolib.expansion.createHelper
import taboolib.library.xseries.XMaterial
import taboolib.library.xseries.XMaterialUtil
import taboolib.library.xseries.parseToMaterial
import taboolib.module.effect.createCube
import taboolib.module.effect.createLine
import taboolib.platform.util.toProxyLocation
import top.maplex.arim.tools.commandhelper.createTabooLegacyStyleCommandHelper


@CommandHeader("PlayerWorldEdit", aliases = ["pwe"], permissionDefault = PermissionDefault.TRUE)
object MainCommand {

    @CommandBody(permissionDefault = PermissionDefault.TRUE)
    val main = mainCommand {
        createTabooLegacyStyleCommandHelper()
    }

    @CommandBody(permissionDefault = PermissionDefault.TRUE)
    val pack  = CommandPack.command

    @CommandBody(permission = "PlayerWorldEdit.Admin",permissionDefault = PermissionDefault.OP)
    val tool = CommandTool.command

    @CommandBody(permissionDefault = PermissionDefault.TRUE)
    val set = CommandSet.command

    @CommandBody(permissionDefault = PermissionDefault.TRUE)
    val replace = CommandReplace.command

    @CommandBody(permissionDefault = PermissionDefault.TRUE)
    val id = CommandID.command
}
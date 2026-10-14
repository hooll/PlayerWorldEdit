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

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val main = mainCommand {
        createTabooLegacyStyleCommandHelper()
    }

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val pack  = CommandPack.command

    @CommandBody(permission = "PlayerWorldEdit.Admin",permissionDefault = PermissionDefault.OP)
    val tool = CommandTool.command

    @CommandBody(permission = "PlayerWorldEdit.Admin",permissionDefault = PermissionDefault.OP)
    val reload = CommandReload.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val set = CommandSet.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val replace = CommandReplace.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val undo = CommandUndo.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val id = CommandID.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val expand = CommandExpand.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val contract = CommandContract.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val wall = CommandWall.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val cyl = CommandCyl.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val hcyl = CommandHcyl.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val copy = CommandCopy.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val rotate = CommandRotate.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val flip = CommandFlip.command

    @CommandBody(permission = "PlayerWorldEdit.Use")
    val paste = CommandPaste.command
}

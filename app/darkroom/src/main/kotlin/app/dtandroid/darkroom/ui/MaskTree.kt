// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.MaskSheet
import app.dtandroid.darkroom.Masks
import app.dtandroid.darkroom.MasksState
import app.dtandroid.darkroom.ModuleMask
import app.dtandroid.darkroom.ParamElement
import app.dtandroid.darkroom.MaskTreeRow
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * P3d stage 2a: the module's mask as a tree (darktable manual, masking & blending, masks, groups and operators):
 * the module's own group with its operator, invert and opacity, then its members top first; a nested group's members
 * follow its row one step in, shown when the group is opened (closed by default). Every row: move up / move down
 * (order matters for difference), invert, more (wrap in group, group together…, remove from this group). The
 * selected row shows its opacity.
 */

private fun groupFlag(ms: MasksState, id: Int, k: String) =
    (ms.forms.firstOrNull { it.id == id }?.geometry?.get(k) as? JsonPrimitive)?.booleanOrNull ?: false

private fun groupOpacity(ms: MasksState, id: Int) =
    (ms.forms.firstOrNull { it.id == id }?.geometry?.get("opacity") as? JsonPrimitive)?.doubleOrNull ?: 1.0

private fun groupOperator(ms: MasksState, id: Int) =
    (ms.forms.firstOrNull { it.id == id }?.geometry?.get("operator") as? JsonPrimitive)?.content

@Composable
internal fun MaskTree(controller: DarkroomController, ms: MasksState, m: ModuleMask, ro: Boolean) {
    val masks = controller.masks
    val root = m.rootId
    if (root != null && m.members.isNotEmpty()) {
        ListControl("group operator", Masks.operatorLabel(m.operator), enabled = !ro) {
            masks.openSheet(MaskSheet.Operator(root))
        }
        ToggleControl("invert group", groupFlag(ms, root, "invert"), enabled = !ro) {
            controller.launch { masks.setGroupInvert(root, !groupFlag(ms, root, "invert")) }
        }
        MaskSlider(maskSlider(2, "group opacity", 0.0, 100.0, 100.0, 1.0, 0, " %"), groupOpacity(ms, root) * 100.0, !ro, live = false) {
            controller.launch { masks.setGroupOpacity(root, it / 100.0) }
        }
    }
    val pst by controller.parametric.state.collectAsState()
    val st by controller.state.collectAsState()
    for (row in Masks.treeRows(ms.forms, root, ms.expanded)) TreeRowView(controller, st, ms, m, row, ro, pst.all)
}

@Composable
private fun TreeRowView(controller: DarkroomController, s: DarkroomState, ms: MasksState, m: ModuleMask, row: MaskTreeRow, ro: Boolean, params: List<ParamElement>) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val masks = controller.masks
    val id = row.member.id
    val f = row.form
    val name = f?.let { Masks.shapeLabel(it) } ?: "form $id"
    val group = row.isGroup
    val inverse = if (group) groupFlag(ms, id, "invert") else row.member.inverse
    val selected = ms.selected == id
    val open = id in ms.expanded
    var indent: Dp = sp.none
    repeat(row.depth) { indent += sp.l }
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.touch).background(if (selected) c.selected else c.panel)
            // P3d 2c: a group row chooses where new shapes go; a shape row opens its edit; a parametric element row opens its controls
            .clickable(role = Role.Button) {
                controller.launch { if (group) masks.chooseGroup(id) else if (masks.editable(f)) masks.startEdit(id) else masks.selectShape(id) }
            }
            .semantics { contentDescription = "member $name"; stateDescription = if (selected) "selected" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(indent))
        if (group) {
            DtIconButton(DtIcon.ChevronDown, if (open) "close group $name" else "open group $name", c.textMuted,
                onClick = { masks.toggleExpanded(id) },
                // as the expanders of the module panel: pointing up while open
                modifier = Modifier.rotate(if (open) OPEN_TURN else CLOSED_TURN).semantics { stateDescription = if (open) "expanded" else "collapsed" })
        } else {
            DtIconImage(shapeIcon(f?.type ?: ""), null, c.text, DtTheme.sizes.icon, Modifier.padding(horizontal = sp.s))
        }
        Column(Modifier.weight(1f)) {
            DtText(name, DtTheme.type.label, c.textStrong, maxLines = 1)
            if (group) {
                val here = Masks.target(ms) == id
                DtText(Masks.operatorLabel(groupOperator(ms, id)) + " · ${f?.members?.size ?: 0}" + if (here) " · new shapes go here" else "", DtTheme.type.small, c.accent, maxLines = 1,
                    modifier = Modifier.clickable(enabled = !ro, role = Role.Button) { masks.openSheet(MaskSheet.Operator(id)) }
                        .semantics { contentDescription = "operator of $name" })
            }
        }
        DtIconButton(DtIcon.MoveUp, "move $name up", c.text, enabled = !ro && row.index < row.count - 1,
            onClick = { controller.launch { masks.moveMember(row.group, id, up = true) } })
        DtIconButton(DtIcon.MoveDown, "move $name down", c.text, enabled = !ro && row.index > 0,
            onClick = { controller.launch { masks.moveMember(row.group, id, up = false) } })
        DtIconButton(DtIcon.Invert, "invert $name", if (inverse) c.textBright else c.textMuted, enabled = !ro,
            onClick = { controller.launch { masks.setMemberInvertIn(row.group, id, !inverse) } },
            modifier = Modifier.semantics { stateDescription = if (inverse) "on" else "off" })
        DtIconButton(DtIcon.More, "more, $name", c.text,
            onClick = { masks.openSheet(MaskSheet.More(id, if (row.group == m.rootId) m.key else null, row.group)) })
    }
    if (selected && f?.type == "parametric") {
        val el = params.firstOrNull { it.id == id }
        if (el != null) Row(Modifier.fillMaxWidth().padding(start = indent)) { Column(Modifier.weight(1f)) { ChannelRow(controller, s, el, ro) } }
    }
    if (selected) {
        val value = if (group) groupOpacity(ms, id) else row.member.opacity
        Row(Modifier.fillMaxWidth().padding(start = indent)) {
            MaskSlider(maskSlider(3, "opacity", 0.0, 100.0, 100.0, 1.0, 0, " %"), value * 100.0, !ro, live = false) {
                controller.launch { masks.setMemberOpacityIn(row.group, id, it / 100.0) }
            }
        }
    }
}

/** The tree's own sheets: a group's operator, and "group together…". */
@Composable
internal fun BoxScope.MaskTreeSheets(controller: DarkroomController, ms: MasksState, sheet: MaskSheet, maxW: Dp?, ro: Boolean) {
    val masks = controller.masks
    val close = { masks.openSheet(null) }
    when (sheet) {
        is MaskSheet.Operator -> {
            val cur = groupOperator(ms, sheet.group) ?: "maximum"
            Sheet("combine with", close, maxW) {
                for ((label, op) in Masks.OPERATORS) SheetItem(label, !ro, selected = op == cur) {
                    controller.launch { masks.setGroupOperator(sheet.group, op) }
                }
            }
        }
        is MaskSheet.Together -> {
            val g = ms.forms.firstOrNull { it.id == sheet.group }
            var picked by remember(sheet) { mutableStateOf(setOf(sheet.first)) }
            Sheet("group together", close, maxW) {
                SheetSection("members of ${g?.let { Masks.shapeLabel(it) } ?: "this group"}, top first")
                for (mem in (g?.members ?: emptyList()).reversed()) {
                    val f = ms.forms.firstOrNull { it.id == mem.id }
                    val on = mem.id in picked
                    SheetItem((f?.let { Masks.shapeLabel(it) } ?: "form ${mem.id}") + if (on) " · chosen" else "", !ro && mem.id != sheet.first, selected = on) {
                        picked = if (on) picked - mem.id else picked + mem.id
                    }
                }
                SheetItem("group ${picked.size} together", !ro && picked.size >= 2) {
                    val order = g?.members?.map { it.id }?.filter { it in picked } ?: emptyList()
                    controller.launch { masks.groupTogether(sheet.group, order) }
                }
            }
        }
        else -> {}
    }
}

private const val OPEN_TURN = 180f
private const val CLOSED_TURN = 0f

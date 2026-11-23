// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.l1

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.GroupTab
import app.dtandroid.darkroom.HostAction
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.ui.DtIconButton
import app.dtandroid.darkroom.ui.DtText
import app.dtandroid.darkroom.ui.groupIcon
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

// ----------------------------------------------------------------------- top bar and pill

/** Back · series pill (opens and closes the filmstrip) · undo · compare · export · more, over the photo on a soft shade. */
@Composable
internal fun L1TopBar(controller: DarkroomController, s: DarkroomState, fade: Float, onBack: () -> Unit, export: HostAction?, modifier: Modifier) {
    val c = DtTheme.colors
    Row(
        modifier.fillMaxWidth().alpha(fade).background(DtTheme.l1.shade).padding(horizontal = DtTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xxs),
    ) {
        DtIconButton(DtIcon.Back, "Back", c.textBright, onBack, iconSize = DtTheme.sizes.iconLarge)
        val pill = L1Model.seriesPill(s)
        if (pill.isNotEmpty()) {
            Box(
                Modifier.height(DtTheme.sizes.touch).clickable(role = Role.Button) { controller.toggleFilmstrip() }
                    .semantics {
                        contentDescription = (if (s.filmstrip) "Hide" else "Show") + " filmstrip for ${s.series.name}"
                        stateDescription = if (s.filmstrip) "expanded" else "collapsed"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.height(DtTheme.l1.pillHeight).clip(CircleShape)
                        .background(if (s.filmstrip) c.accent else DtTheme.l1.pill).padding(horizontal = DtTheme.space.l),
                    contentAlignment = Alignment.Center,
                ) { DtText(pill, DtTheme.type.value, if (s.filmstrip) c.bg else c.textBright) }
            }
        }
        Spacer(Modifier.weight(1f))
        DtIconButton(DtIcon.Undo, "undo", c.textBright, enabled = s.undo.canUndo, onClick = { controller.launch { undo() } })
        // the existing before/after
        DtIconButton(DtIcon.Compare, "compare", if (s.before) c.accent else c.textBright, onClick = { controller.launch { setBefore(!s.before) } },
            keepTint = true, modifier = Modifier.semantics { stateDescription = if (s.before) "on" else "off" })
        if (export != null) DtIconButton(DtIcon.Export, export.label, c.textBright, enabled = export.enabled, onClick = export.run)
        DtIconButton(DtIcon.More, "more", c.textBright, { controller.setOverlay(Overlay.HostMenu) }, iconSize = DtTheme.sizes.iconLarge)
    }
}

/** `module · control · value` at the top while a slider is dragged. */
@Composable
internal fun L1Pill(text: String, modifier: Modifier) {
    Box(
        modifier.padding(top = DtTheme.space.m).height(DtTheme.l1.pillHeight).clip(CircleShape).background(DtTheme.l1.pill)
            .padding(horizontal = DtTheme.space.xl).semantics { contentDescription = "dragging $text" },
        contentAlignment = Alignment.Center,
    ) { DtText(text, DtTheme.type.value, DtTheme.colors.textBright) }
}

// ----------------------------------------------------------------------- sections

/**
 * `quick`, the layout's groups, `active`, all on one row (or column, sideways): never scrolled sideways. With more
 * sections than the token `count.l1SectionsFull` (and always in the column) each shows its icon and a short label.
 */
@Composable
internal fun L1Sections(controller: DarkroomController, s: DarkroomState, vertical: Boolean, fade: Float) {
    val sections = L1Model.sections(s)
    val chosen = L1Model.selectedSection(s)
    val compact = vertical || sections.size > DtTheme.l1.sectionsFull
    val cell: @Composable (L1Section, Modifier) -> Unit = { sec, m ->
        SectionCell(sec, sec.tab == chosen, compact, m) { L1Model.selectSection(controller, controller.state.value, sec.tab) }
    }
    val bg = DtTheme.colors.panel.let { it.copy(alpha = it.alpha * DtTheme.l1.panelAlpha) }
    if (vertical) {
        Column(Modifier.width(DtTheme.l1.sideColumn).fillMaxHeight().alpha(fade).background(bg)) {
            // brief PUI3b: cells share the height but are never under the touch minimum; when they do not fit, the column scrolls
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val each = maxOf(maxHeight / sections.size, DtTheme.l1.sectionCell)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    for (sec in sections) cell(sec, Modifier.height(each).fillMaxWidth())
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().height(DtTheme.l1.sectionsRow).alpha(fade).background(bg)) {
            for (sec in sections) cell(sec, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun SectionCell(sec: L1Section, selected: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = DtTheme.colors
    val col = if (selected) c.accent else c.textDim
    val description = when (sec.tab) {
        GroupTab.Quick -> "quick access panel"
        GroupTab.Active -> "active modules"
        else -> "group ${sec.label}"
    } + if (sec.marked) ", has changes" else ""
    Column(
        modifier.clickable(role = Role.Tab, onClick = onClick).semantics { contentDescription = description; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (compact) {
            val icon = when (sec.tab) {
                GroupTab.Quick -> DtIcon.QuickAccess
                GroupTab.Active -> DtIcon.Power
                else -> groupIcon(sec.icon ?: "")
            }
            DtIconImage(icon, null, col, DtTheme.sizes.icon)
            DtText(sec.label, DtTheme.type.tiny, col, Modifier.padding(horizontal = DtTheme.space.xxs))
        } else {
            DtText(sec.label, DtTheme.type.label, col, Modifier.padding(horizontal = DtTheme.space.xxs))
        }
        // the short mark under a group with a changed module
        val markModifier = Modifier.padding(top = DtTheme.space.xxs).size(DtTheme.l1.sectionMarkWidth, DtTheme.l1.sectionMark)
        if (sec.marked) Box(markModifier.clip(CircleShape).background(c.accent)) else Spacer(markModifier)
    }
}

// ----------------------------------------------------------------------- tools

/** `styles` · `modules` · `masks` · `more`; the chosen one filled with the accent. */
@Composable
internal fun L1Tools(controller: DarkroomController, s: DarkroomState, tool: L1Tool, vertical: Boolean, fade: Float) {
    val bg = DtTheme.colors.panel
    if (vertical) {
        Column(Modifier.width(DtTheme.l1.sideColumn).fillMaxHeight().alpha(fade).background(bg).padding(DtTheme.space.xs),
            verticalArrangement = Arrangement.spacedBy(DtTheme.space.xs, Alignment.CenterVertically)) {
            for (t in L1Tool.entries) ToolCell(t, t == tool, Modifier.fillMaxWidth().height(DtTheme.l1.toolsRow)) { L1Model.selectTool(controller, controller.state.value, t) }
        }
    } else {
        Row(Modifier.fillMaxWidth().height(DtTheme.l1.toolsRow).alpha(fade).background(bg).padding(DtTheme.space.xs),
            horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xs)) {
            for (t in L1Tool.entries) ToolCell(t, t == tool, Modifier.weight(1f).fillMaxHeight()) { L1Model.selectTool(controller, controller.state.value, t) }
        }
    }
}

private fun L1Tool.icon(): DtIcon = when (this) {
    L1Tool.STYLES -> DtIcon.Styles
    L1Tool.MODULES -> DtIcon.Modules
    L1Tool.MASKS -> DtIcon.Mask
    L1Tool.MORE -> DtIcon.More
}

@Composable
private fun ToolCell(t: L1Tool, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = DtTheme.colors
    val col = if (selected) c.bg else c.text
    Column(
        modifier.clip(RoundedCornerShape(DtTheme.sizes.radiusControl)).background(if (selected) c.accent else c.panel)
            .clickable(role = Role.Tab, onClick = onClick).semantics { contentDescription = "tool ${t.label}"; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DtIconImage(t.icon(), null, col, DtTheme.sizes.icon)
        DtText(t.label, DtTheme.type.tiny, col)
    }
}

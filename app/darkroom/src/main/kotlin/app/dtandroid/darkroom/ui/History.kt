// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.theme.DtTheme

/**
 * History rows: newest on top, "original" at the bottom, the current step
 * selected and the steps above it dimmed; tap goes to a step (ui-spec.md 4).
 */
@Composable
fun HistoryRows(controller: DarkroomController, s: DarkroomState) {
    val c = DtTheme.colors
    val names = s.modules.associate { (it.op to it.priority) to it.name }
    val items = s.history.items
    for (n in items.size downTo 0) {
        val item = items.getOrNull(n - 1)
        val sel = n == s.history.end
        val name = if (item == null) "original" else {
            val base = names[item.op to item.priority] ?: item.name
            if (item.instanceName.isEmpty()) base else "$base ${item.instanceName}"
        }
        Row(
            Modifier.fillMaxWidth().height(DtTheme.sizes.historyRow)
                .background(if (sel) c.selected else c.bg)
                .alpha(if (n > s.history.end) DtTheme.alpha.dimmed else 1f)
                .clickable(role = Role.Button) { controller.launch { historyGoto(n) } }
                .semantics { selected = sel }
                .padding(horizontal = DtTheme.space.panel),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val col = if (sel) c.textBright else c.text
            DtText("$n", DtTheme.type.number, col, Modifier.width(DtTheme.sizes.historyNumber))
            DtText(name, DtTheme.type.body, col, Modifier.weight(1f).padding(start = DtTheme.space.l))
            if (item != null && !item.enabled) DtText("off", DtTheme.type.small, col)
        }
    }
}

/** "compress history stack", and on phones undo and redo next to it. */
@Composable
fun HistoryActions(controller: DarkroomController, s: DarkroomState, withUndo: Boolean) {
    val c = DtTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m),
    ) {
        if (withUndo) {
            DtIconButton(DtIcon.Undo, "undo", c.textStrong, enabled = s.undo.canUndo, onClick = { controller.launch { undo() } })
            DtIconButton(DtIcon.Redo, "redo", c.textStrong, enabled = s.undo.canRedo, onClick = { controller.launch { redo() } })
        }
        DtTextButton("compress history stack", { controller.launch { historyCompress() } }, Modifier.weight(1f), enabled = !s.readOnly)
    }
}

/**
 * Brief P3h, mockup B3-Snapshots: the snapshots section as darktable's snapshots module (it sits above the history on
 * desktop's left panel): heading, "take snapshot", one row per snapshot (tap: show it in the split view, tap again:
 * end the split; delete on the right), and, while one is shown, the split view's "rotate" and "switch sides".
 */
@Composable
fun SnapshotsSection(controller: DarkroomController, s: DarkroomState) {
    val c = DtTheme.colors
    val snaps = s.snapshots
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "snapshots" }) {
        PanelHeading("snapshots")
        Column(
            Modifier.fillMaxWidth().background(c.panel)
                .padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m),
            verticalArrangement = Arrangement.spacedBy(DtTheme.space.xxs),
        ) {
            DtTextButton("take snapshot", { controller.launch { takeSnapshot() } }, Modifier.fillMaxWidth(),
                enabled = s.info != null && !snaps.full)
            if (snaps.list.isEmpty()) DtText("No snapshots yet.", DtTheme.type.body, c.textMuted, Modifier.padding(vertical = DtTheme.space.s))
            for (sn in snaps.list) {
                val sel = sn.id == snaps.selected
                Row(
                    Modifier.fillMaxWidth().background(if (sel) c.selected else c.panel),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).height(DtTheme.sizes.touch)
                            .clickable(role = Role.Button) { controller.selectSnapshot(sn.id) }
                            .semantics { selected = sel; stateDescription = if (sel) "shown" else "not shown" }
                            .padding(horizontal = DtTheme.space.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DtText(sn.name, DtTheme.type.body, if (sel) c.textBright else c.text)
                    }
                    DtIconButton(DtIcon.Close, "delete snapshot ${sn.name}", if (sel) c.textBright else c.text, { controller.deleteSnapshot(sn.id) })
                }
            }
            if (snaps.selected != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = DtTheme.space.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m),
                ) {
                    DtTextButton(if (snaps.split.vertical) "divider: vertical" else "divider: horizontal", { controller.rotateSplit() }, Modifier.weight(1f))
                    DtTextButton("switch sides", { controller.switchSplitSides() }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Phone: the history tab (brief P3h: the snapshots above it, as on desktop's left panel). */
@Composable
fun HistoryView(controller: DarkroomController, s: DarkroomState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = DtTheme.space.xxs)) {
        SnapshotsSection(controller, s)
        PanelHeading("history")
        HistoryRows(controller, s)
        HistoryActions(controller, s, withUndo = true)
    }
}

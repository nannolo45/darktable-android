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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/** Phone: the history tab. */
@Composable
fun HistoryView(controller: DarkroomController, s: DarkroomState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = DtTheme.space.xxs)) {
        HistoryRows(controller, s)
        HistoryActions(controller, s, withUndo = true)
    }
}

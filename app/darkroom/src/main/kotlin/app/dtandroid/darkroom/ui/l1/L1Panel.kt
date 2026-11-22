// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.l1

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.MasksState
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.ui.DrawPanel
import app.dtandroid.darkroom.ui.HistoryView
import app.dtandroid.darkroom.ui.ImportFiles
import app.dtandroid.darkroom.ui.MasksTab
import app.dtandroid.darkroom.ui.ModuleList
import app.dtandroid.darkroom.ui.SearchLine
import app.dtandroid.darkroom.ui.SheetItem
import app.dtandroid.darkroom.ui.StylesPanel
import app.dtandroid.ui.theme.DtTheme

/**
 * The l1 panel: the panel colour at the token opacity (fading while a slider is dragged), scrolling vertically inside.
 * Its content is the darkroom's existing content for the chosen tool; a shape being drawn replaces it with the
 * drawing tool's panel, as on the other phone layout.
 */
@Composable
internal fun L1Panel(
    controller: DarkroomController,
    s: DarkroomState,
    live: Map<Pair<InstanceKey, Int>, Double>,
    ms: MasksState,
    tool: L1Tool,
    importFiles: ImportFiles?,
    fade: Float,
    modifier: Modifier,
) {
    val c = DtTheme.colors
    val bg = c.panel.copy(alpha = c.panel.alpha * DtTheme.l1.panelAlpha * fade)
    Box(modifier.background(bg)) {
        val draw = ms.draw
        when {
            draw != null -> DrawPanel(controller, s, draw, Modifier.fillMaxSize())
            tool == L1Tool.STYLES -> StylesPanel(controller, s, importFiles, Modifier.fillMaxSize())
            tool == L1Tool.MASKS -> MasksTab(controller, s, ms, Modifier.fillMaxWidth())
            tool == L1Tool.MORE -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // only what the app has: the module layouts menu (the other layouts have it beside the group tabs),
                // then the history with undo, redo and "compress history stack"
                SheetItem("module layouts · ${s.layoutName}…", enabled = true) { controller.setOverlay(Overlay.Layouts) }
                HistoryView(controller, s)
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (s.layout.showSearch) Box(Modifier.alpha(fade)) { Column { SearchLine(controller, s) } }
                ModuleList(controller, s, live, tablet = false, modifier = Modifier.weight(1f), l1 = true)
            }
        }
    }
}

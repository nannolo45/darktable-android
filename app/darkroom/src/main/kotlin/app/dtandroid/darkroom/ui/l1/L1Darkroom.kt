// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.l1

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.HostAction
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.ui.CropRotationRow
import app.dtandroid.darkroom.ui.Filmstrip
import app.dtandroid.darkroom.ui.ImportFiles
import app.dtandroid.darkroom.ui.InfoLine
import app.dtandroid.darkroom.ui.LastEditLine
import app.dtandroid.darkroom.ui.LocalSliderDrag
import app.dtandroid.darkroom.ui.Notices
import app.dtandroid.darkroom.ui.PhotoView
import app.dtandroid.darkroom.ui.SliderDragWatch
import app.dtandroid.darkroom.ui.dragFade
import app.dtandroid.ui.theme.DtTheme

/**
 * Brief PUI3a: the darkroom's phone layout in the `l1` skin. Upright: the photo on top with a top bar floating over
 * it, then one panel, then the sections row (while `modules` is chosen) and the tools row. Sideways: the photo on
 * the left, then the panel, the sections column and the tools column. Everything inside is the darkroom's existing
 * content (module rows and controls, quick access panel, styles, masks tab, history), driven by the same controller.
 * While a slider is dragged everything but that slider fades and a pill names it.
 */
@Composable
fun L1Darkroom(
    controller: DarkroomController,
    s: DarkroomState,
    live: Map<Pair<InstanceKey, Int>, Double>,
    onBack: () -> Unit,
    actions: List<HostAction>,
    importFiles: ImportFiles?,
    sideways: Boolean,
) {
    val watch = remember { SliderDragWatch() }
    val ms by controller.masks.state.collectAsState()
    val draw = ms.draw
    CompositionLocalProvider(LocalSliderDrag provides watch) {
        val chrome = L1Model.chrome(s, draw != null, watch.drag)
        val fade = dragFade() ?: 1f
        val tool = L1Model.tool(s)
        // the app's export, when the host offers one, gets the top bar's export button
        val export = actions.firstOrNull { it.label.startsWith("export") }
        val panel: @Composable (Modifier) -> Unit = { m -> L1Panel(controller, s, live, ms, tool, importFiles, fade, m) }
        if (sideways) {
            Row(Modifier.fillMaxSize().background(DtTheme.colors.photoBackground)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    L1PhotoArea(controller, s, chrome, fade, onBack, export, Modifier.weight(1f).fillMaxWidth())
                    UnderPhoto(controller, s)
                }
                panel(Modifier.width(DtTheme.l1.panelWidth).fillMaxHeight())
                if (chrome.rows) {
                    if (tool == L1Tool.MODULES) L1Sections(controller, s, vertical = true, fade = fade)
                    L1Tools(controller, s, tool, vertical = true, fade = fade)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(DtTheme.colors.photoBackground)) {
                L1PhotoArea(controller, s, chrome, fade, onBack, export, Modifier.weight(1f).fillMaxWidth())
                UnderPhoto(controller, s)
                panel(Modifier.fillMaxWidth().heightIn(max = DtTheme.l1.panelMaxHeight))
                if (chrome.rows) {
                    if (tool == L1Tool.MODULES) L1Sections(controller, s, vertical = false, fade = fade)
                    L1Tools(controller, s, tool, vertical = false, fade = fade)
                }
            }
        }
    }
}

/** What the other phone layout shows under the photo: the filmstrip, notices, the last edit, crop's rotation, the picked values. */
@Composable
private fun UnderPhoto(controller: DarkroomController, s: DarkroomState) {
    if (s.filmstrip) Filmstrip(controller, s, DtTheme.sizes.filmstripPhone, DtTheme.sizes.thumbPhoneWidth, DtTheme.sizes.thumbPhoneHeight, centered = false)
    Notices(s)
    LastEditLine(s)
    // brief P3a deliverable 4: rotation and straighten while the crop tool is open
    CropRotationRow(controller, s)
    // the crop size or the picked values while those tools are open
    if (s.cropTool != null || s.picker != null) InfoLine(s)
}

/** The photo, fitted on black, the top bar over it, and the pill while a slider is dragged. */
@Composable
private fun L1PhotoArea(controller: DarkroomController, s: DarkroomState, chrome: L1Chrome, fade: Float, onBack: () -> Unit, export: HostAction?,
                        modifier: Modifier) {
    Box(modifier) {
        PhotoView(controller, s, DtTheme.space.none, twoFingerUndo = true, Modifier.fillMaxSize())
        L1TopBar(controller, s, fade, onBack, export, Modifier.align(Alignment.TopCenter))
        chrome.pill?.let { L1Pill(it, Modifier.align(Alignment.TopCenter)) }
    }
}

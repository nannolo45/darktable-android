// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.ParamSheet
import app.dtandroid.darkroom.ParametricMasks
import app.dtandroid.engine.api.ImageClass
import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P3d stage 2b: the refinement of a mask (manual: masks, refinement controls), for every mode but off and
 * uniform. Collapsed by default. Limits are the engine's (engine-api.md 6.6), defaults desktop's.
 */

private fun slider(id: Int, label: String, min: Double, max: Double, factor: Double, digits: Int, unit: String) =
    Control.Slider(
        id = id, label = label, tooltip = null, hiddenByDefault = false, binding = Binding.Param("mask.$label"),
        softMin = min, softMax = max, hardMin = min, hardMax = max, default = 0.0, step = 1.0 / factor,
        digits = digits, factor = factor, offset = 0.0, format = unit, textDefault = null, integer = false,
    )

@Composable
private fun RefineSlider(sl: Control.Slider, value: Double, enabled: Boolean, onSet: (Double) -> Unit) {
    var moving by remember(sl.id) { mutableStateOf<Double?>(null) }
    SliderControl(sl.label ?: "", sl, moving ?: value, enabled,
        onDrag = { moving = it }, onDragEnd = { moving?.let(onSet); moving = null }, onReset = { onSet(sl.default) }, onExact = {})
}

@Composable
fun RefinementSection(controller: DarkroomController, s: DarkroomState, ro: Boolean) {
    val c = DtTheme.colors
    val pm = controller.parametric
    val p by pm.state.collectAsState()
    val b = p.blend
    val raw = s.info?.imageClass.let { it != null && it != ImageClass.NONRAW }
    SheetSection("refinement")
    ToggleControl("refine the mask", p.refineOpen, enabled = true) { pm.toggleRefine() }
    if (p.refineOpen && b != null) {
        Column(Modifier.fillMaxWidth()) {
            RefineSlider(slider(11, "details threshold", -1.0, 1.0, 100.0, 0, " %"), b.details ?: 0.0, !ro && raw) { controller.launch { pm.setRefine("details", it) } }
            if (!raw) DtText("details threshold needs a RAW photo", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = DtTheme.space.xxs), maxLines = 2)
            val guide = ParametricMasks.GUIDES.firstOrNull { it.first == p.guide }?.second ?: p.guide
            ListControl("feathering guide", guide, enabled = !ro) { pm.openSheet(ParamSheet.Guide) }
            RefineSlider(slider(12, "feathering radius", 0.0, 250.0, 1.0, 1, " px"), b.featheringRadius ?: 0.0, !ro) { controller.launch { pm.setRefine("feathering_radius", it) } }
            RefineSlider(slider(13, "blurring radius", 0.0, 100.0, 1.0, 1, " px"), b.blurRadius ?: 0.0, !ro) { controller.launch { pm.setRefine("blur_radius", it) } }
            RefineSlider(slider(14, "mask brightness", -1.0, 1.0, 100.0, 0, " %"), b.brightness ?: 0.0, !ro) { controller.launch { pm.setRefine("brightness", it) } }
            RefineSlider(slider(15, "mask contrast", -1.0, 1.0, 100.0, 0, " %"), b.contrast ?: 0.0, !ro) { controller.launch { pm.setRefine("contrast", it) } }
            DtTextButton("reset refinement", { controller.launch { pm.resetRefine() } }, Modifier.fillMaxWidth(), enabled = !ro)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.ChannelSpec
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.ParamChannels
import app.dtandroid.darkroom.ParamSheet
import app.dtandroid.darkroom.ParametricMasks
import app.dtandroid.darkroom.RangeMath
import app.dtandroid.engine.api.BlendChannel
import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P3d stage 2b: the parametric section of the masks tab (manual: masks, parametric). Channel buttons per blend
 * colour space; a row per active channel with the input range (four markers), the output range behind a toggle,
 * invert, boost factor (scene channels), a picker on the photo and remove. No early return or `continue` in the
 * composables below (stage 1 crash: a skipped group handed a remembered lambda to the wrong call site).
 */

@Composable
fun ParametricSection(controller: DarkroomController, s: DarkroomState, ro: Boolean) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val pm = controller.parametric
    val p by pm.state.collectAsState()
    val b = p.blend
    SheetSection("parametric")
    if (b != null) {
        ListControl("colour space", ParamChannels.spaceLabel(p.space) + if (b.colorspace == "default") " (module's)" else "", enabled = !ro) {
            pm.openSheet(ParamSheet.Space)
        }
        val specs = ParamChannels.of(p.space)
        if (specs.isEmpty()) {
            DtText("parametric masks are not offered in this colour space", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.s), maxLines = 3)
        }
        for (rowSpecs in specs.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                for (spec in rowSpecs) {
                    val on = b.parametric[spec.inName]?.active == true || b.parametric[spec.outName]?.active == true
                    ChannelButton(spec.label, on, !ro, Modifier.weight(1f)) { controller.launch { pm.addChannel(spec) } }
                }
                for (i in rowSpecs.size until 4) Spacer(Modifier.weight(1f))
            }
        }
        for (spec in specs) {
            key(spec.base) {
                val on = b.parametric[spec.inName]?.active == true || b.parametric[spec.outName]?.active == true
                if (on) ChannelRow(controller, s, spec, ro)
            }
        }
    }
    p.note?.let { DtText(it, DtTheme.type.small, c.textStrong, Modifier.padding(vertical = sp.xs), maxLines = 3) }
}

@Composable
private fun ChannelButton(label: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = DtTheme.colors
    val shape = RoundedCornerShape(DtTheme.sizes.radiusControl)
    var m = modifier.height(DtTheme.sizes.touch).clip(shape).background(if (on) c.selected else c.raised)
    if (on) m = m.border(DtTheme.sizes.border, c.accent, shape)
    Box(
        m.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "channel $label"; stateDescription = if (on) "in use" else "not in use" },
        contentAlignment = Alignment.Center,
    ) {
        DtText(label, DtTheme.type.label, if (!enabled) c.textDisabled else if (on) c.textBright else c.textStrong)
    }
}

@Composable
private fun ChannelRow(controller: DarkroomController, s: DarkroomState, spec: ChannelSpec, ro: Boolean) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val pm = controller.parametric
    val p by pm.state.collectAsState()
    val b = p.blend
    val chIn = b?.parametric?.get(spec.inName)
    val chOut = b?.parametric?.get(spec.outName)
    val outShown = spec.base in p.outputShown
    val whole = (chIn == null || RangeMath.isFull(chIn.range)) && (chOut == null || RangeMath.isFull(chOut.range))
    Column(Modifier.fillMaxWidth().padding(top = sp.s)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DtText(spec.label, DtTheme.type.label, c.textStrong, Modifier.weight(1f))
            if (whole) {
                Box(Modifier.semantics { contentDescription = "${spec.label}: the whole range, this restricts nothing" }, contentAlignment = Alignment.Center) {
                    DtIconImage(DtIcon.Warning, null, c.textMuted, DtTheme.sizes.icon)
                }
            }
            DtTextButton("output range", { pm.toggleOutput(spec.base) }, selected = outShown, compact = true)
            DtIconButton(DtIcon.Remove, "remove channel ${spec.label}", c.text, enabled = !ro,
                onClick = { controller.launch { pm.removeChannel(spec) } })
        }
        if (chIn != null) SliderLine(controller, s, spec.inName, "${spec.label} input range", chIn, ro)
        if (outShown && chOut != null) SliderLine(controller, s, spec.outName, "${spec.label} output range", chOut, ro)
        if (!outShown && chOut != null && !RangeMath.isFull(chOut.range)) {
            DtText("an output range is set and still applies", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.xxs), maxLines = 2)
        }
        if (spec.boost && chIn != null) {
            val d = ParamChannels.defaultBoost(p.space, spec.base)
            BoostSlider(spec, chIn.boost, d, !ro) { controller.launch { pm.setBoost(spec.inName, it); if (chOut != null) pm.setBoost(spec.outName, it) } }
        }
    }
}

@Composable
private fun SliderLine(controller: DarkroomController, s: DarkroomState, name: String, description: String, ch: BlendChannel, ro: Boolean) {
    val c = DtTheme.colors
    val pm = controller.parametric
    val keyOf = pm.state.value.key
    val id = pm.pickerId(name)
    val armed = keyOf != null && controller.pickerArmed(s, keyOf, id)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RangeSlider4(ch.range, !ro, description, { r -> controller.launch { pm.setRange(name, r) } }, Modifier.weight(1f))
        DtIconButton(DtIcon.ColorPicker, "pick the range of $description from the photo", if (armed) c.accent else c.text, enabled = !ro && keyOf != null,
            onClick = { if (keyOf != null) controller.launch { controller.togglePicker(keyOf, id) } })
        DtIconButton(DtIcon.Invert, "invert $description", if (ch.invert) c.textBright else c.textMuted, enabled = !ro,
            onClick = { controller.launch { pm.setInvert(name, !ch.invert) } })
    }
}

@Composable
private fun BoostSlider(spec: ChannelSpec, value: Double, default: Double, enabled: Boolean, onSet: (Double) -> Unit) {
    val slider = Control.Slider(
        id = 2, label = "boost factor ${spec.label}", tooltip = null, hiddenByDefault = false, binding = Binding.Param("mask.boost"),
        softMin = -18.0, softMax = 18.0, hardMin = -18.0, hardMax = 18.0, default = default, step = 0.1,
        digits = 1, factor = 1.0, offset = 0.0, format = " EV", textDefault = null, integer = false,
    )
    var moving by androidx.compose.runtime.remember(spec.base) { androidx.compose.runtime.mutableStateOf<Double?>(null) }
    SliderControl(slider.label ?: "", slider, moving ?: value, enabled,
        onDrag = { moving = it }, onDragEnd = { moving?.let(onSet); moving = null }, onReset = { onSet(default) }, onExact = {})
}

/** The sheets of the parametric section: colour space, its confirmation, feathering guide. */
@Composable
fun BoxScope.ParametricOverlays(controller: DarkroomController, s: DarkroomState, tablet: Boolean) {
    val pm = controller.parametric
    val p by pm.state.collectAsState()
    val sheet = p.sheet
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    val close = { pm.openSheet(null) }
    val ro = s.readOnly
    when (sheet) {
        ParamSheet.Space -> Sheet("blend colour space", close, maxW) {
            for (cs in listOf("default", "lab", "rgb_display", "rgb_scene")) {
                SheetItem(if (cs == "default") "module's default" else ParamChannels.spaceLabel(cs), !ro, selected = cs == p.blend?.colorspace) {
                    pm.askSpace(cs)
                }
            }
        }
        is ParamSheet.ConfirmSpace -> Sheet("change the colour space? the parametric channels are reset", close, maxW) {
            SheetItem("change", !ro) { controller.launch { pm.confirmSpace() } }
            SheetItem("cancel", true, onClick = close)
        }
        ParamSheet.Guide -> Sheet("feathering guide", close, maxW) {
            for ((k, label) in ParametricMasks.GUIDES) SheetItem(label, !ro, selected = k == p.guide) { controller.launch { pm.setGuide(k) } }
        }
        null -> {}
    }
}

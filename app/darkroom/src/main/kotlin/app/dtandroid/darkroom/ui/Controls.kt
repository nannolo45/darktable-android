// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.ui.graph.BandEditor
import app.dtandroid.darkroom.ui.graph.BandHost
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.paramAt
import app.dtandroid.schema.Binding
import app.dtandroid.schema.ComputedList
import app.dtandroid.schema.ComputedToggle
import app.dtandroid.darkroom.ui.graph.CurveEditorHost
import app.dtandroid.schema.Control
import app.dtandroid.schema.ModuleSchema
import app.dtandroid.schema.ParamRuleInputs
import app.dtandroid.schema.Rules
import app.dtandroid.schema.ValueFormat
import app.dtandroid.schema.settingBool
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import app.dtandroid.ui.theme.SliderGradient

/** What the generated controls need from the screen. */
class PanelContext(
    val controller: DarkroomController,
    val state: DarkroomState,
    val live: Map<Pair<InstanceKey, Int>, Double>,
    val module: ModuleSchema,
    val instance: ModuleInstance,
    val readOnly: Boolean,
) {
    val key = InstanceKey(instance.op, instance.priority)
    val inputs = ParamRuleInputs(module, instance.params)
}

/**
 * The body of an expanded module, generated from the control schema
 * (ui-spec.md sections 2 and 3). Modules without generated controls show a
 * one-line note.
 */
@Composable
fun ModulePanel(ctx: PanelContext, modifier: Modifier = Modifier) {
    val sp = DtTheme.space
    // brief PUI3a: in the l1 layout the panel's colour fades while a slider is dragged
    val fade = dragFade()
    val bg = DtTheme.colors.panel.let { if (fade == null) it else it.copy(alpha = it.alpha * fade) }
    Column(
        modifier.fillMaxWidth().background(bg)
            .padding(start = sp.panel, end = sp.panel, top = sp.xs, bottom = sp.m),
    ) {
        if (ctx.module.op == app.dtandroid.darkroom.RETOUCH_OP) {
            // brief P3m: retouch's and the colour look up table's editors are hand-built, inside the module as desktop
            RetouchPanel(ctx)
        } else if (ctx.module.op == app.dtandroid.darkroom.LUT_OP) {
            LutPanel(ctx)
        } else if (ctx.module.op == app.dtandroid.darkroom.LUT3D_OP) {
            // brief P6b: the file row (desktop's file button and list) is hand-built over the phone's LUT folder;
            // the schema's row (control 0: button and file list) is left out, the other controls stay generated
            Lut3dFileRow(ctx)
            Controls(ctx.module.controls.filter { it.id != 0 }, ctx)
        } else if (ctx.module.op == app.dtandroid.darkroom.ASHIFT_OP && ctx.module.editableCount > 0) {
            // brief P3n: structure and fit first (approved mockup), then the module's generated controls
            if (!ctx.readOnly) PerspectivePanel(ctx)
            Controls(ctx.module.controls, ctx)
        } else if (ctx.module.editableCount == 0) {
            DtText(
                "The editor for this module is not available yet. It still renders and can be switched.",
                DtTheme.type.label, DtTheme.colors.textMuted, Modifier.padding(vertical = sp.s), maxLines = 3,
            )
        } else {
            Controls(ctx.module.controls, ctx)
        }
    }
}

@Composable
private fun Controls(controls: List<Control>, ctx: PanelContext) {
    for (c in controls) {
        // brief P3e-b: what the band editor replaces (its notebook, colour zones' own lists) is not built here
        if (!Rules.isVisible(ctx.module, c, ctx.inputs) || BandHost.hides(ctx.module, c)) continue
        ControlView(c, ctx)
        // brief P2j: a control that waits for a later tool says so in one line
        ctx.module.notes[c.id]?.let { ControlNote(it) }
    }
}

@Composable
private fun ControlView(c: Control, ctx: PanelContext) {
    // brief P6d: lens correction's camera, lens and value rows are hand-built (desktop's menus and editable lists)
    if (ctx.module.op == app.dtandroid.darkroom.LENS_OP && app.dtandroid.darkroom.LensNodes.handles(c.id)) {
        LensRows(c.id, ctx)
        return
    }
    when (c) {
        // containers: their children fade one by one (the dragged slider may be one of them)
        is Control.Stack, is Control.Page, is Control.Group, is Control.Collapsible, is Control.Tabs -> ControlBody(c, ctx)
        else -> Fading(ctx, c.id) { ControlBody(c, ctx) }
    }
}

/** Brief PUI3a: in the l1 layout every control but the dragged slider fades while a slider is dragged. */
@Composable
private fun Fading(ctx: PanelContext, controlId: Int, content: @Composable () -> Unit) {
    val f = dragFade { it.key == ctx.key && it.controlId == controlId }
    if (f == null) content() else Box(Modifier.alpha(f)) { content() }
}

@Composable
private fun ControlBody(c: Control, ctx: PanelContext) {
    val sensitive = Rules.isSensitive(ctx.module, c, ctx.inputs)
    when (c) {
        is Control.Slider -> GeneratedSlider(c, ctx, sensitive)
        is Control.ComboBox -> GeneratedList(c, ctx, sensitive)
        is Control.Toggle -> GeneratedToggle(c, ctx, sensitive)
        is Control.Section -> SectionLabel(c.label ?: "")
        is Control.Collapsible -> Collapsible(c, ctx)
        is Control.Tabs -> TabsBlock(c, ctx)
        is Control.Stack -> if (BandHost.pagesFollowTabs(ctx.module.op)) {
            // brief P3e-b: colour equalizer's sliders of the tab the graph shows
            val page = c.pages.getOrNull(ctx.controller.tabPage(ctx.state, ctx.key, BandHost.tabsId(ctx.module) ?: -1))
            if (page != null) for (child in page.children) ControlView(child, ctx)
        } else Controls(c.pages, ctx)
        is Control.Page -> Controls(c.children, ctx)
        is Control.Group -> Controls(c.children, ctx)
        is Control.Label -> if (!c.label.isNullOrBlank()) {
            DtText(c.label!!.trim(), DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(vertical = DtTheme.space.xs), maxLines = 3)
        }
        is Control.ButtonRow -> ButtonRowView(c, ctx)
        // brief P3c: a picker button that stands alone (white balance's "from image area", levels' black, gray, white)
        is Control.Unsupported -> when {
            c.id in ctx.module.pickerMarks -> PickerButtonRow(c, ctx)
            // brief P6a: a desktop button the engine does (rgb levels' "auto", AgX's buttons)
            c.id in ctx.module.actionButtons -> ActionButtonRow(c, ctx)
            // brief P3e-b: the band editor stands in for desktop's graph of contrast equalizer, colour zones and colour equalizer
            c.id == BandHost.graphId(ctx.module) -> BandEditor(ctx)
            // brief P3e: the graph of the curve modules, levels and the tone equalizer; other buttons and graphs: later packages
            c.kind == "graph" -> CurveEditorHost(c, ctx)
            else -> {}
        }
    }
}

/**
 * One slider, list or switch on its own, as the quick access panel shows it: with
 * darktable's widget name as label; [disabled] greys it out (several instances, read-only).
 */
@Composable
fun SingleControl(c: Control, ctx: PanelContext, label: String, disabled: Boolean) {
    val sensitive = !disabled && Rules.isSensitive(ctx.module, c, ctx.inputs)
    Fading(ctx, c.id) {
        when (c) {
            is Control.Slider -> GeneratedSlider(c, ctx, sensitive, label)
            is Control.ComboBox -> GeneratedList(c, ctx, sensitive, label)
            is Control.Toggle -> GeneratedToggle(c, ctx, sensitive, label)
            else -> UnavailableControl(label, null)
        }
    }
}

/** One short line under a control that cannot be used yet, saying what it waits for (brief P2j). */
@Composable
fun ControlNote(text: String) {
    DtText(text, DtTheme.type.tiny, DtTheme.colors.textDisabled, Modifier.padding(bottom = DtTheme.space.xs), maxLines = 2)
}

/** A quick access entry the app cannot use yet: its name, greyed out, and why. */
@Composable
fun UnavailableControl(label: String, reason: String?) {
    Column(Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.sliderRow).padding(vertical = DtTheme.space.xs), verticalArrangement = Arrangement.Center) {
        DtText(label, DtTheme.type.label, DtTheme.colors.textDisabled)
        if (reason != null) DtText(reason, DtTheme.type.tiny, DtTheme.colors.textDisabled, maxLines = 2)
    }
}

// ----------------------------------------------------------------------- slider

/** A generated slider bound to the schema's parameter (or action). */
@Composable
private fun GeneratedSlider(c0: Control.Slider, ctx: PanelContext, sensitive: Boolean, label: String? = null) {
    // brief P6a: limits and default the engine gives per state (white balance's fine tuning: the preset's tunings)
    val c = ctx.controller.withActionRange(ctx.state, ctx.key, c0)
    val value = ctx.controller.sliderValue(ctx.state, ctx.key, c, ctx.live) ?: c.default
    // brief P3c deliverable 5: a value the engine gives only in some states (white balance's fine tuning)
    val present = (c.binding as? Binding.Action)?.needs?.let { n ->
        ctx.state.actionValues[ctx.key]?.get(n).let { it != null && it !is kotlinx.serialization.json.JsonNull }
    } ?: true
    // brief P6a: desktop hides such a slider while it has no value (white balance's finetune)
    if (!present && (c.binding as? Binding.Action)?.hide == true) return
    val enabled = sensitive && !ctx.readOnly && c.binding !is Binding.None && present
    // brief P3c: a slider with desktop's picker quad gets the picker button at its right, as on desktop
    if (c.id in ctx.module.pickerMarks && label == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GeneratedSliderBody(c, ctx, enabled, value, label) }
            PickerButton(ctx, c.id, sensitive)
        }
        return
    }
    GeneratedSliderBody(c, ctx, enabled, value, label)
}

@Composable
private fun GeneratedSliderBody(c: Control.Slider, ctx: PanelContext, enabled: Boolean, value: Double, label: String?) {
    // brief PUI3a: the l1 layout watches drags (null elsewhere)
    val watch = LocalSliderDrag.current
    SliderControl(
        label = label ?: c.label ?: "",
        slider = c,
        value = value,
        enabled = enabled,
        onDrag = { v ->
            ctx.controller.drag(ctx.key, c.id, c.binding, v, c.integer)
            watch?.moved(SliderDrag(ctx.key, c.id, fullName(ctx.instance), label ?: c.label ?: "", ValueFormat.text(c, v)))
        },
        onDragEnd = {
            ctx.controller.endDrag(ctx.key, c.id, c.binding, c.integer)
            watch?.ended()
        },
        onReset = { ctx.controller.launch { setSlider(ctx.key, c.binding, c.default, c.integer, c.id) } },
        onExact = { ctx.controller.setOverlay(Overlay.NumberEntry(ctx.key, c.id)) },
    )
}

/**
 * darktable's bauhaus slider for touch: label left, value right, a track with
 * a lighter fill from the left and a small marker under the value. The whole
 * row is the touch target. A horizontal drag moves the value through the soft
 * range; a long press opens exact entry; a double tap on the label or on the
 * bar (the track with its marker) resets to the default, as a double click
 * does on desktop (brief P3a); a double tap anywhere else on the row (the
 * value) opens exact entry.
 */
@Composable
fun SliderControl(
    label: String,
    slider: Control.Slider,
    value: Double,
    enabled: Boolean,
    onDrag: (Double) -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    onExact: () -> Unit,
) {
    val c = DtTheme.colors
    val z = DtTheme.sizes
    var width by remember { mutableIntStateOf(1) }
    val current by rememberUpdatedState(value)
    var dragStart by remember { mutableFloatStateOf(0f) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    val fraction = ValueFormat.fraction(slider, value)
    val text = ValueFormat.text(slider, value)
    val textColor = if (enabled) c.text else c.textDisabled
    var rowModifier = Modifier.fillMaxWidth().height(z.sliderRow).onSizeChanged { width = it.width.coerceAtLeast(1) }
        .semantics { contentDescription = label; stateDescription = text }
    if (enabled) {
        rowModifier = rowModifier
            .pointerInput(slider.id) {
                detectHorizontalDragGestures(
                    onDragStart = { dragStart = ValueFormat.fraction(slider, current); dragDx = 0f },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, dx ->
                    change.consume()
                    dragDx += dx
                    onDrag(ValueFormat.valueAt(slider, dragStart + dragDx / width))
                }
            }
            .pointerInput(slider.id) {
                detectTapGestures(onDoubleTap = { onExact() }, onLongPress = { onExact() })
            }
    }
    Column(rowModifier, verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            var labelModifier = Modifier.weight(1f)
            if (enabled) labelModifier = labelModifier.pointerInput(slider.id) {
                detectTapGestures(onDoubleTap = { onReset() }, onLongPress = { onExact() })
            }
            DtText(label, DtTheme.type.label, textColor, labelModifier)
            DtText(text, DtTheme.type.value, textColor, Modifier.padding(start = DtTheme.space.m))
        }
        // brief P3a deliverable 5: the bar resets on a double tap (desktop: double click on the slider); its long
        // press stays exact entry. Its touch area is the bar and the gap above it, from under the label row.
        var barModifier = Modifier.fillMaxWidth()
        if (enabled) barModifier = barModifier.pointerInput(slider.id) {
            detectTapGestures(onDoubleTap = { onReset() }, onLongPress = { onExact() })
        }
        Column(barModifier.semantics { contentDescription = "$label bar" }) {
        Spacer(Modifier.height(DtTheme.space.s))
        val round = DtTheme.shapes.sliderThumbRound
        val thumb = z.sliderThumbRound
        // brief PUI2a: `round` draws a circle in the accent colour on the track; the bar's height grows to hold it
        val barHeight = if (round) maxOf(thumb, z.sliderTrack) else z.sliderTrack + z.sliderMarkerHeight
        val trackY = if (round) (barHeight - z.sliderTrack) / 2 else DtTheme.space.none
        BoxWithConstraints(Modifier.fillMaxWidth().height(barHeight)) {
            // P1c: a slider with colour stops shows them in its track, as desktop's
            // _draw_baseline (bauhaus.c) does: the gradient over the track's colour, the fill only
            // for sliders with desktop's fill feedback, brightening ("screen") so the colours stay
            val gradientAlpha = DtTheme.alpha.sliderGradient
            val gradient = remember(slider, width, gradientAlpha) {
                SliderGradient.brush(
                    slider.gradient.map { SliderGradient.Stop(it.position, it.red, it.green, it.blue) },
                    slider.softMin, slider.softMax, slider.hardMin, slider.hardMax, width.toFloat(),
                    gradientAlpha,
                )
            }
            Box(Modifier.offset(y = trackY).fillMaxWidth().height(z.sliderTrack).background(c.sliderTrack)) {
                if (gradient != null) {
                    Box(Modifier.fillMaxWidth().height(z.sliderTrack).background(gradient))
                    if (slider.fillFeedback) {
                        Box(
                            Modifier.fillMaxWidth(fraction).height(z.sliderTrack).drawBehind {
                                drawRect(if (enabled) c.sliderFill else c.header, blendMode = BlendMode.Screen)
                            },
                        )
                    }
                } else {
                    Box(Modifier.fillMaxWidth(fraction).height(z.sliderTrack).background(if (enabled) c.sliderFill else c.header))
                }
            }
            if (round) {
                Box(
                    Modifier.offset(x = maxWidth * fraction - thumb / 2).size(thumb)
                        .background(if (enabled) c.accent else c.header, CircleShape),
                )
            } else DtIconImage(
                DtIcon.SliderMarker, null, textColor, z.sliderMarkerWidth,
                Modifier.offset(x = maxWidth * fraction - z.sliderMarkerWidth / 2, y = z.sliderTrack)
                    .size(z.sliderMarkerWidth, z.sliderMarkerHeight),
            )
        }
        }
    }
}

// ----------------------------------------------------------------------- list and switch

@Composable
private fun GeneratedList(c: Control.ComboBox, ctx: PanelContext, sensitive: Boolean, label: String? = null) {
    // brief P2f: crop's aspect list (override): the entry from ratio_d / ratio_n, and desktop's flip button
    val aspect = ctx.module.aspect?.takeIf { c.binding == Binding.Aspect && it.controlId == c.id }
    if (aspect != null) {
        val p = ctx.instance.params
        fun int(n: String) = (p.paramAt(n) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: -1
        // brief P3a: with the crop tool open, the ratio chosen in the tool (written when the tool is left)
        val (rd, rn) = ctx.controller.cropRatioShown(ctx.state, ctx.key, int(aspect.ratioD), int(aspect.ratioN))
        val text = app.dtandroid.schema.CropAspect.label(aspect, rd, rn)
        val enabled = sensitive && !ctx.readOnly
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ListControl(label ?: c.label ?: "", text, enabled) { ctx.controller.setOverlay(Overlay.ListEntries(ctx.key, c.id)) }
            }
            DtIcon.byKey(aspect.flipIcon)?.let { icon ->
                DtIconButton(icon, aspect.flipLabel, DtTheme.colors.text, enabled = enabled, onClick = { ctx.controller.launch { flipAspect(ctx.key) } })
            }
        }
        return
    }
    // brief P2j: a list whose entries and choice are computed from the parameters (override)
    val computed = ctx.module.computed[c.id] as? ComputedList
    if (computed != null && c.binding == Binding.Computed) {
        val p = ctx.instance.params
        val cur = computed.current(p)
        val text = cur?.let { computed.entries(p).getOrNull(it)?.label } ?: computed.fallbackText(p) ?: ""
        val enabled = sensitive && !ctx.readOnly && computed.enabled(p)
        ListControl(label ?: c.label ?: "", text, enabled) { ctx.controller.setOverlay(Overlay.ListEntries(ctx.key, c.id)) }
        return
    }
    // brief P3c deliverable 5: a list the engine gives for this photo (input profiles, denoise profiles)
    if (c.binding == Binding.ActionList) {
        val model = ctx.module.actionLists[c.id]
        val entries = model?.entries(ctx.state.actionLists[ctx.key to c.id]) ?: emptyList()
        val cur = model?.currentIndex(entries) ?: -1
        val text = entries.getOrNull(cur)?.let { model?.label(it) } ?: ""
        ListControl(label ?: c.label ?: "", text, sensitive && !ctx.readOnly && entries.isNotEmpty()) {
            ctx.controller.setOverlay(Overlay.ListEntries(ctx.key, c.id))
        }
        return
    }
    // brief P3c: a picker setting shows the app's own value
    (c.binding as? Binding.Setting)?.let { sb ->
        val setting = ctx.module.pickerSettings[c.id]
        val i = setting?.entryOf(ctx.controller.settingValue(ctx.state, ctx.key.op, sb.key)) ?: -1
        val text = setting?.entries?.getOrNull(i)?.first ?: ""
        ListControl(label ?: c.label ?: "", text, sensitive && !ctx.readOnly) { ctx.controller.setOverlay(Overlay.ListEntries(ctx.key, c.id)) }
        return
    }
    val current = when (val b = c.binding) {
        is Binding.Param -> ctx.inputs.param(b.path)
        else -> c.entries.getOrNull(c.currentPos)?.value
    }
    val entry = c.entries.firstOrNull { it.value == current }?.label ?: current?.toString() ?: ""
    val enabled = sensitive && !ctx.readOnly && c.binding !is Binding.None && c.entries.isNotEmpty()
    // brief P3c: a list with desktop's picker quad (filmic's auto tune levels) gets the picker button at its right;
    // one without entries (only there for its picker) shows its name and the button
    if (c.id in ctx.module.pickerMarks && label == null && c.entries.isEmpty()) {
        val usable = ctx.module.pickers[c.id] != null && !ctx.readOnly
        Row(Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically) {
            DtText(c.label ?: "", DtTheme.type.label, if (usable) DtTheme.colors.text else DtTheme.colors.textDisabled, Modifier.weight(1f))
            PickerButton(ctx, c.id, sensitive)
        }
        return
    }
    if (c.id in ctx.module.pickerMarks && label == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ListControl(c.label ?: "", entry, enabled) { ctx.controller.setOverlay(Overlay.ListEntries(ctx.key, c.id)) } }
            PickerButton(ctx, c.id, sensitive)
        }
        return
    }
    ListControl(label ?: c.label ?: "", entry, enabled) { ctx.controller.setOverlay(Overlay.ListEntries(ctx.key, c.id)) }
}

/** Label left, current entry right; opens a sheet with the entries. */
@Composable
fun ListControl(label: String, entry: String, enabled: Boolean, onOpen: () -> Unit) {
    val col = if (enabled) DtTheme.colors.text else DtTheme.colors.textDisabled
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow).clickable(enabled = enabled, role = Role.DropdownList, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DtText(label, DtTheme.type.label, col, Modifier.weight(1f))
        Box(Modifier.weight(1f).padding(start = DtTheme.space.m), contentAlignment = Alignment.CenterEnd) {
            DtText(entry, DtTheme.type.label, col)
        }
        DtIconImage(DtIcon.ChevronDown, null, col, DtTheme.sizes.icon, Modifier.padding(start = DtTheme.space.xs))
    }
}

@Composable
private fun GeneratedToggle(c: Control.Toggle, ctx: PanelContext, sensitive: Boolean, label: String? = null) {
    val b = c.binding
    val computed = ctx.module.computed[c.id] as? ComputedToggle
    if (computed != null && b == Binding.Computed) {
        val on = computed.on(ctx.instance.params)
        ToggleControl(label ?: c.label ?: "", on, sensitive && !ctx.readOnly) {
            ctx.controller.launch { toggleComputed(ctx.key, c.id, !on) }
        }
        return
    }
    // brief P3c: a picker setting ("take channel mixing into account"): the app's own value
    if (b is Binding.Setting) {
        val on = ctx.controller.settingValue(ctx.state, ctx.key.op, b.key)?.settingBool() ?: c.default
        ToggleControl(label ?: c.label ?: "", on, sensitive && !ctx.readOnly) {
            ctx.controller.launch { setSetting(ctx.key, c.id, JsonPrimitive(!on)) }
        }
        return
    }
    val on = when (b) {
        is Binding.Param -> (ctx.instance.params.paramAt(b.path) as? JsonPrimitive)?.booleanOrNull ?: c.default
        else -> c.default
    }
    val enabled = sensitive && !ctx.readOnly && b is Binding.Param
    ToggleControl(label ?: c.label ?: "", on, enabled) {
        if (b is Binding.Param) ctx.controller.launch { setParam(ctx.key, b.path, JsonPrimitive(!on)) }
    }
}

/** Label and darktable's check-style box. */
@Composable
fun ToggleControl(label: String, on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val c = DtTheme.colors
    val col = if (enabled) c.text else c.textDisabled
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .semantics { stateDescription = if (on) "on" else "off" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DtText(label, DtTheme.type.label, col, Modifier.weight(1f), maxLines = 2)
        Box(
            Modifier.padding(start = DtTheme.space.m).size(DtTheme.sizes.checkbox)
                .border(DtTheme.sizes.border, col).background(if (on) c.header else c.sliderTrack),
            contentAlignment = Alignment.Center,
        ) {
            if (on) DtIconImage(DtIcon.Check, null, if (enabled) c.textStrong else c.textDisabled, DtTheme.sizes.checkbox)
        }
    }
}

// ----------------------------------------------------------------------- containers

@Composable
private fun SectionLabel(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = DtTheme.space.m)) {
        DtText(text, DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(bottom = DtTheme.space.xxs))
        Box(Modifier.fillMaxWidth().height(DtTheme.sizes.border).background(DtTheme.colors.textDisabled))
    }
}

@Composable
private fun Collapsible(c: Control.Collapsible, ctx: PanelContext) {
    var open by rememberSaveable(ctx.instance.op, ctx.instance.priority, c.id) { mutableStateOf(c.expanded) }
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.touch).clickable(role = Role.Button) { open = !open },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DtText(c.label ?: "", DtTheme.type.label, DtTheme.colors.textStrong, Modifier.weight(1f))
        DtIconImage(DtIcon.ChevronDown, null, DtTheme.colors.textMuted, DtTheme.sizes.icon,
            Modifier.rotate(if (open) HALF_TURN else NO_TURN))
    }
    if (open) Column(Modifier.fillMaxWidth().padding(start = DtTheme.space.m)) { Controls(c.children, ctx) }
}

private const val HALF_TURN = 180f
private const val NO_TURN = 0f

@Composable
private fun TabsBlock(c: Control.Tabs, ctx: PanelContext) {
    val pages = c.pages.filter { Rules.isVisible(ctx.module, it, ctx.inputs) }
    if (pages.isEmpty()) return
    // brief P3c: the chosen page is the controller's (a picker takes its channel from it)
    val selected = ctx.controller.tabPage(ctx.state, ctx.key, c.id)
    val page = pages.getOrElse(selected) { pages[0] }
    Column(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs)) {
        DtTabRow(pages, page, { ctx.controller.chooseTab(ctx.key, c.id, pages.indexOf(it)) }, DtTheme.sizes.touch) { p, sel ->
            DtText(p.label ?: "", DtTheme.type.label, if (sel) DtTheme.colors.textBright else DtTheme.colors.textDim)
        }
        Controls(page.children, ctx)
    }
}

@Composable
private fun ButtonRowView(c: Control.ButtonRow, ctx: PanelContext) {
    val enabled = !ctx.readOnly
    Row(Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically) {
        DtText(c.label ?: "", DtTheme.type.label, if (enabled) DtTheme.colors.text else DtTheme.colors.textDisabled, Modifier.weight(1f))
        for (b in c.buttons) {
            val icon = DtIcon.byKey(b.icon) ?: continue
            DtIconButton(icon, b.label, DtTheme.colors.text, enabled = enabled, onClick = {
                val cur = (ctx.instance.params.paramAt(b.param) as? JsonPrimitive)?.content
                val next = cur?.let { b.map[it] } ?: return@DtIconButton
                ctx.controller.launch { setParam(ctx.key, b.param, JsonPrimitive(next)) }
            })
        }
    }
}

/** Number shown in the exact-entry field. */
fun exactText(c: Control.Slider, v: Double) = ValueFormat.numberText(c, v)

// ----------------------------------------------------------------------- brief P3c: colour pickers

/**
 * Desktop's picker button on control [controlId]: arms the picker for that control (the photo shows its selection),
 * shown on while armed; tapping it again disarms it. A picker the app has not built yet is shown greyed out.
 */
@Composable
fun PickerButton(ctx: PanelContext, controlId: Int, sensitive: Boolean = true) {
    val spec = ctx.module.pickers[controlId]
    val armed = ctx.controller.pickerArmed(ctx.state, ctx.key, controlId)
    val enabled = spec != null && sensitive && !ctx.readOnly
    val c = DtTheme.colors
    val what = spec?.label?.takeIf { it.isNotBlank() }
    val description = when {
        spec == null -> "colour picker (not available yet)"
        what != null -> "colour picker: $what"
        else -> "colour picker"
    }
    DtIconButton(
        DtIcon.ColorPicker, description, if (armed) c.textBright else c.text,
        onClick = { ctx.controller.launch { togglePicker(ctx.key, controlId) } },
        modifier = (if (armed) Modifier.background(c.selected) else Modifier).semantics { stateDescription = if (armed) "on" else "off" },
        enabled = enabled,
    )
}

/** Brief P6a: a desktop button done by a module action: its text (or, for a menu, one button per entry). */
@Composable
private fun ActionButtonRow(c: Control.Unsupported, ctx: PanelContext) {
    val b = ctx.module.actionButtons[c.id] ?: return
    Row(Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xs)) {
        if (b.choices.size > 1) DtText(b.label, DtTheme.type.label, if (ctx.readOnly) DtTheme.colors.textDisabled else DtTheme.colors.text, Modifier.weight(1f), maxLines = 2)
        for ((i, ch) in b.choices.withIndex()) {
            DtTextButton(ch.label, onClick = { ctx.controller.launch { pressActionButton(ctx.key, c.id, i) } },
                modifier = if (b.choices.size == 1) Modifier.weight(1f) else Modifier, enabled = !ctx.readOnly, compact = b.choices.size > 1)
        }
    }
}

/** A picker button that stands alone on desktop (an icon in a row): its meaning in words, the button at the right. */
@Composable
private fun PickerButtonRow(c: Control.Unsupported, ctx: PanelContext) {
    val spec = ctx.module.pickers[c.id]
    val text = spec?.label ?: c.tooltip?.lineSequence()?.firstOrNull() ?: "colour picker"
    val enabled = spec != null && !ctx.readOnly
    Row(Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically) {
        DtText(text, DtTheme.type.label, if (enabled) DtTheme.colors.text else DtTheme.colors.textDisabled, Modifier.weight(1f), maxLines = 2)
        PickerButton(ctx, c.id)
    }
}

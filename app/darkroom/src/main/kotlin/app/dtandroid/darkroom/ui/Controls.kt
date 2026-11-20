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
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.paramAt
import app.dtandroid.schema.Binding
import app.dtandroid.schema.ComputedList
import app.dtandroid.schema.ComputedToggle
import app.dtandroid.schema.Control
import app.dtandroid.schema.ModuleSchema
import app.dtandroid.schema.ParamRuleInputs
import app.dtandroid.schema.Rules
import app.dtandroid.schema.ValueFormat
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
    Column(
        modifier.fillMaxWidth().background(DtTheme.colors.panel)
            .padding(start = sp.panel, end = sp.panel, top = sp.xs, bottom = sp.m),
    ) {
        if (ctx.module.editableCount == 0) {
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
        if (!Rules.isVisible(ctx.module, c, ctx.inputs)) continue
        ControlView(c, ctx)
        // brief P2j: a control that waits for a later tool says so in one line
        ctx.module.notes[c.id]?.let { ControlNote(it) }
    }
}

@Composable
private fun ControlView(c: Control, ctx: PanelContext) {
    val sensitive = Rules.isSensitive(ctx.module, c, ctx.inputs)
    when (c) {
        is Control.Slider -> GeneratedSlider(c, ctx, sensitive)
        is Control.ComboBox -> GeneratedList(c, ctx, sensitive)
        is Control.Toggle -> GeneratedToggle(c, ctx, sensitive)
        is Control.Section -> SectionLabel(c.label ?: "")
        is Control.Collapsible -> Collapsible(c, ctx)
        is Control.Tabs -> TabsBlock(c, ctx)
        is Control.Stack -> Controls(c.pages, ctx)
        is Control.Page -> Controls(c.children, ctx)
        is Control.Group -> Controls(c.children, ctx)
        is Control.Label -> if (!c.label.isNullOrBlank()) {
            DtText(c.label!!.trim(), DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(vertical = DtTheme.space.xs), maxLines = 3)
        }
        is Control.ButtonRow -> ButtonRowView(c, ctx)
        is Control.Unsupported -> Unit // buttons, pickers and graphs: Tier 2 and 3, later work packages
    }
}

/**
 * One slider, list or switch on its own, as the quick access panel shows it: with
 * darktable's widget name as label; [disabled] greys it out (several instances, read-only).
 */
@Composable
fun SingleControl(c: Control, ctx: PanelContext, label: String, disabled: Boolean) {
    val sensitive = !disabled && Rules.isSensitive(ctx.module, c, ctx.inputs)
    when (c) {
        is Control.Slider -> GeneratedSlider(c, ctx, sensitive, label)
        is Control.ComboBox -> GeneratedList(c, ctx, sensitive, label)
        is Control.Toggle -> GeneratedToggle(c, ctx, sensitive, label)
        else -> UnavailableControl(label, null)
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
private fun GeneratedSlider(c: Control.Slider, ctx: PanelContext, sensitive: Boolean, label: String? = null) {
    val value = ctx.controller.sliderValue(ctx.state, ctx.key, c, ctx.live) ?: c.default
    val enabled = sensitive && !ctx.readOnly && c.binding !is Binding.None
    SliderControl(
        label = label ?: c.label ?: "",
        slider = c,
        value = value,
        enabled = enabled,
        onDrag = { v -> ctx.controller.drag(ctx.key, c.id, c.binding, v, c.integer) },
        onDragEnd = { ctx.controller.endDrag(ctx.key, c.id, c.binding, c.integer) },
        onReset = { ctx.controller.launch { setSlider(ctx.key, c.binding, c.default, c.integer, c.id) } },
        onExact = { ctx.controller.setOverlay(Overlay.NumberEntry(ctx.key, c.id)) },
    )
}

/**
 * darktable's bauhaus slider for touch: label left, value right, a track with
 * a lighter fill from the left and a small marker under the value. The whole
 * row is the touch target. A horizontal drag moves the value through the soft
 * range; a long press or double tap opens exact entry; a double tap on the
 * label resets to the default.
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
            DtText(text, DtTheme.type.label, textColor, Modifier.padding(start = DtTheme.space.m))
        }
        Spacer(Modifier.height(DtTheme.space.s))
        BoxWithConstraints(Modifier.fillMaxWidth().height(z.sliderTrack + z.sliderMarkerHeight)) {
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
            Box(Modifier.fillMaxWidth().height(z.sliderTrack).background(c.sliderTrack)) {
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
            DtIconImage(
                DtIcon.SliderMarker, null, textColor, z.sliderMarkerWidth,
                Modifier.offset(x = maxWidth * fraction - z.sliderMarkerWidth / 2, y = z.sliderTrack)
                    .size(z.sliderMarkerWidth, z.sliderMarkerHeight),
            )
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
        val text = app.dtandroid.schema.CropAspect.label(aspect, int(aspect.ratioD), int(aspect.ratioN))
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
    val current = when (val b = c.binding) {
        is Binding.Param -> ctx.inputs.param(b.path)
        else -> c.entries.getOrNull(c.currentPos)?.value
    }
    val entry = c.entries.firstOrNull { it.value == current }?.label ?: current?.toString() ?: ""
    val enabled = sensitive && !ctx.readOnly && c.binding !is Binding.None && c.entries.isNotEmpty()
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
    var selected by rememberSaveable(ctx.instance.op, ctx.instance.priority, c.id) { mutableIntStateOf(0) }
    val page = pages.getOrElse(selected) { pages[0] }
    Column(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs)) {
        DtTabRow(pages, page, { selected = pages.indexOf(it) }, DtTheme.sizes.touch) { p, sel ->
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

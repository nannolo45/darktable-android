// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.dtandroid.darkroom.ColorMath
import app.dtandroid.darkroom.Hsv
import app.dtandroid.darkroom.Rgb
import app.dtandroid.ui.theme.DataDraw
import app.dtandroid.ui.theme.DtTheme
import kotlin.math.roundToInt

/*
 * Brief P6e: the shared colour row and colour dialog (desktop's GtkColorButton and its chooser), used by framing and
 * watermark and meant for later packages. The colour is display sRGB 0..1 as the module's float parameters hold it
 * (ColorMath.kt); the dialog keeps the colour it was opened with exactly until the user changes it.
 */

private fun color(c: Rgb) = DataDraw.rgb(c.r, c.g, c.b)

/** A swatch of [rgb] (a placeholder outline while unknown); tapping it calls [onClick]. */
@Composable
fun ColorSwatch(rgb: Rgb?, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = DtTheme.colors
    var m = modifier.size(DtTheme.sizes.fillSwatch)
    if (rgb != null) m = m.background(color(rgb))
    m = m.border(DtTheme.sizes.border, if (enabled) c.border else c.textDisabled)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; if (rgb != null) stateDescription = ColorMath.hex(rgb) }
    Box(m)
}

/**
 * A colour row as desktop's: the label, the swatch with the current colour (a tap opens the colour dialog), and
 * [trailing] (desktop's picker button) at the right.
 */
@Composable
fun ColorRow(label: String, rgb: Rgb?, enabled: Boolean, onOpen: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val c = DtTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically) {
        DtText(label, DtTheme.type.label, if (enabled) c.text else c.textDisabled, Modifier.weight(1f))
        if (rgb != null) DtText(ColorMath.hex(rgb), DtTheme.type.small, c.textMuted, Modifier.padding(end = DtTheme.space.s))
        ColorSwatch(rgb, label, enabled, onOpen)
        Spacer(Modifier.width(DtTheme.space.s))
        trailing()
    }
}

/**
 * The colour dialog as a bottom sheet: saturation/value field and hue strip, red, green and blue 0..255, a hex field,
 * the recent colours, Cancel and OK. Nothing is written while it is open; [onOk] gets the colour (the one it was
 * opened with, unchanged, when nothing was touched).
 */
@Composable
fun BoxScope.ColorDialogSheet(
    title: String,
    initial: Rgb,
    recent: List<String>,
    enabled: Boolean,
    maxWidth: androidx.compose.ui.unit.Dp?,
    onOk: (Rgb) -> Unit,
    onCancel: () -> Unit,
) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    var rgb by remember(initial) { mutableStateOf(initial) }
    var hue by remember(initial) { mutableDoubleStateOf(ColorMath.toHsv(initial).h) }
    var hexText by remember(initial) { mutableStateOf(ColorMath.hex(initial)) }
    var hexError by remember(initial) { mutableStateOf(false) }
    fun set(n: Rgb, keepHue: Boolean = false, typed: Boolean = false) {
        rgb = n
        if (!typed) hexText = ColorMath.hex(n)
        hexError = false
        if (!keepHue) ColorMath.toHsv(n).let { if (it.s > 0.0) hue = it.h }
    }
    val hsv = ColorMath.toHsv(rgb)
    Sheet(title, onCancel, maxWidth) {
        // before and after, side by side (the "after" follows every drag)
        Row(Modifier.fillMaxWidth().padding(vertical = sp.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.s)) {
            Box(Modifier.size(DtTheme.sizes.touch).background(color(initial)).border(DtTheme.sizes.border, c.border).semantics { contentDescription = "colour before" })
            Box(
                Modifier.size(DtTheme.sizes.touch).background(color(rgb)).border(DtTheme.sizes.border, c.border)
                    .semantics { contentDescription = "colour after"; stateDescription = ColorMath.hex(rgb) },
            )
            DtText(ColorMath.hex(rgb), DtTheme.type.value, c.textStrong)
        }
        SvField(hue, hsv, enabled) { s, v -> set(ColorMath.fromHsvBytes(Hsv(hue, s, v)), keepHue = true) }
        Spacer(Modifier.height(sp.s))
        HueStrip(hue, enabled) { h ->
            hue = h
            if (hsv.s > 0.0) set(ColorMath.fromHsvBytes(Hsv(h, hsv.s, hsv.v)), keepHue = true)
        }
        Spacer(Modifier.height(sp.xs))
        val names = listOf("red", "green", "blue")
        for (ch in 0..2) ChannelSlider(names[ch], ch, rgb, enabled) { set(ColorMath.withByte(rgb, ch, it)) }
        // hex field: "#" optional, 3 or 6 digits; a valid entry applies at once
        Row(Modifier.fillMaxWidth().padding(vertical = sp.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.s)) {
            DtText("hex", DtTheme.type.label, c.text)
            Box(
                Modifier.weight(1f).height(DtTheme.sizes.sliderRow).background(c.sliderTrack).padding(horizontal = sp.l),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = hexText,
                    onValueChange = { t ->
                        hexText = t.take(7)
                        ColorMath.parseHex(hexText)?.let { set(it, typed = true) }
                    },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = DtTheme.type.body.copy(color = c.textBright),
                    cursorBrush = SolidColor(c.textBright),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { hexError = ColorMath.parseHex(hexText) == null }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "hex colour" },
                )
            }
        }
        if (hexError) DtText("Type 3 or 6 hex digits, for example #FF8800.", DtTheme.type.small, c.textStrong)
        if (recent.isNotEmpty()) {
            SheetSection("recent colours")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                for (h in recent) {
                    val r = ColorMath.parseHex(h) ?: continue
                    ColorSwatch(r, "recent colour $h", enabled, { set(r) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = sp.m), horizontalArrangement = Arrangement.spacedBy(sp.s)) {
            DtTextButton("cancel", onCancel, Modifier.weight(1f).semantics { contentDescription = "cancel colour" })
            DtTextButton("OK", { onOk(rgb) }, Modifier.weight(1f).semantics { contentDescription = "OK colour" }, enabled = enabled)
        }
    }
}

/** Saturation (left to right) and value (bottom to top) at hue [hue]; the cursor marks [hsv]. */
@Composable
private fun SvField(hue: Double, hsv: Hsv, enabled: Boolean, onPick: (Double, Double) -> Unit) {
    val c = DtTheme.colors
    var w by remember { mutableIntStateOf(1) }
    var h by remember { mutableIntStateOf(1) }
    val pick by rememberUpdatedState(onPick)
    val white = color(ColorMath.fromHsv(Hsv(hue, 0.0, 1.0)))
    val pure = color(ColorMath.fromHsv(Hsv(hue, 1.0, 1.0)))
    val black = color(ColorMath.fromHsv(Hsv(hue, 0.0, 0.0)))
    fun at(o: Offset) = pick((o.x / w).toDouble().coerceIn(0.0, 1.0), (1.0 - o.y / h).coerceIn(0.0, 1.0))
    var m = Modifier.fillMaxWidth().height(DtTheme.sizes.colorField).onSizeChanged { w = it.width.coerceAtLeast(1); h = it.height.coerceAtLeast(1) }
        .drawBehind {
            drawRect(Brush.horizontalGradient(listOf(white, pure)))
            drawRect(Brush.verticalGradient(listOf(black.copy(alpha = 0f), black)))
        }
        .border(DtTheme.sizes.border, c.border)
        .semantics { contentDescription = "saturation and value"; stateDescription = "${(hsv.s * 100).roundToInt()}% ${(hsv.v * 100).roundToInt()}%" }
    if (enabled) m = m.pointerInput(Unit) { detectTapGestures { at(it) } }.pointerInput(Unit) { detectDragGestures(onDragStart = { at(it) }) { ch, _ -> ch.consume(); at(ch.position) } }
    Box(m) { Cursor(hsv.s.toFloat(), (1.0 - hsv.v).toFloat(), w, h) }
}

/** The hue strip: red, yellow, green, cyan, blue, magenta, red; the cursor marks [hue]. */
@Composable
private fun HueStrip(hue: Double, enabled: Boolean, onHue: (Double) -> Unit) {
    val c = DtTheme.colors
    var w by remember { mutableIntStateOf(1) }
    var h by remember { mutableIntStateOf(1) }
    val set by rememberUpdatedState(onHue)
    val stops = (0..6).map { color(ColorMath.fromHsv(Hsv(it * 60.0, 1.0, 1.0))) }
    fun at(o: Offset) = set(((o.x / w).toDouble().coerceIn(0.0, 1.0) * 360.0).coerceAtMost(359.999))
    var m = Modifier.fillMaxWidth().height(DtTheme.sizes.colorHue).onSizeChanged { w = it.width.coerceAtLeast(1); h = it.height.coerceAtLeast(1) }
        .drawBehind { drawRect(Brush.horizontalGradient(stops)) }
        .border(DtTheme.sizes.border, c.border)
        .semantics { contentDescription = "hue"; stateDescription = "${hue.roundToInt()}°" }
    if (enabled) m = m.pointerInput(Unit) { detectTapGestures { at(it) } }.pointerInput(Unit) { detectDragGestures(onDragStart = { at(it) }) { ch, _ -> ch.consume(); at(ch.position) } }
    Box(m) { Cursor((hue / 360.0).toFloat(), 0.5f, w, h) }
}

/** One channel 0..255 over a strip from its 0 to its 255 (the other two as they are), the number at the right. */
@Composable
private fun ChannelSlider(label: String, channel: Int, rgb: Rgb, enabled: Boolean, onValue: (Int) -> Unit) {
    val c = DtTheme.colors
    var w by remember { mutableIntStateOf(1) }
    var h by remember { mutableIntStateOf(1) }
    val set by rememberUpdatedState(onValue)
    val v = rgb.bytes[channel]
    val lo = color(ColorMath.withByte(rgb, channel, 0))
    val hi = color(ColorMath.withByte(rgb, channel, 255))
    fun at(o: Offset) = set(((o.x / w).coerceIn(0f, 1f) * 255f).roundToInt())
    Row(Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
        DtText(label, DtTheme.type.label, if (enabled) c.text else c.textDisabled, Modifier.width(DtTheme.sizes.touch))
        var m = Modifier.weight(1f).height(DtTheme.sizes.colorHue).onSizeChanged { w = it.width.coerceAtLeast(1); h = it.height.coerceAtLeast(1) }
            .drawBehind { drawRect(Brush.horizontalGradient(listOf(lo, hi))) }
            .border(DtTheme.sizes.border, c.border)
            .semantics { contentDescription = label; stateDescription = "$v" }
        if (enabled) m = m.pointerInput(Unit) { detectTapGestures { at(it) } }.pointerInput(Unit) { detectDragGestures(onDragStart = { at(it) }) { ch, _ -> ch.consume(); at(ch.position) } }
        Box(m) { Cursor(v / 255f, 0.5f, w, h) }
        DtText("$v", DtTheme.type.value, if (enabled) c.textStrong else c.textDisabled, Modifier.width(DtTheme.sizes.touch))
    }
}

/** A small outlined square centred at ([fx], [fy]) of a [w] x [h] pixel area. */
@Composable
private fun Cursor(fx: Float, fy: Float, w: Int, h: Int) {
    val d = LocalDensity.current
    val size = DtTheme.sizes.colorCursor
    val half = with(d) { size.toPx() / 2f }
    val x = with(d) { (fx.coerceIn(0f, 1f) * w - half).toDp() }
    val y = with(d) { (fy.coerceIn(0f, 1f) * h - half).toDp() }
    Box(
        Modifier.offset(x, y).size(size).border(DtTheme.sizes.border, DtTheme.colors.textBright)
            .padding(DtTheme.sizes.border).border(DtTheme.sizes.border, DtTheme.colors.panel),
    )
}

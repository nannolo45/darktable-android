// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import app.dtandroid.darkroom.Lut
import app.dtandroid.darkroom.LutPatches
import app.dtandroid.ui.theme.DataDraw
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P3m: the colour look up table's panel, as desktop's (colorchecker.c gui_init; the manual's "color look up
 * table" page): the patch grid (the source colours; an outline on altered patches; the selected one marked), the
 * picker, the patch list, and the selected patch's lightness, green-red, blue-yellow and saturation offsets. Touch
 * equivalents of the mouse: a tap selects, a long press opens "replace source with the picked colour" (shift+click),
 * "reset" (double-click) and "delete" (right-click); "add a patch from the picked colour" (shift+click on empty
 * space) is a button beside the grid. Desktop's "target color: relative / absolute" display switch is left out (the
 * owner's approved mockup); the sliders show the relative offsets, desktop's default.
 */

private const val SL_L = 9201
private const val SL_A = 9202
private const val SL_B = 9203
private const val SL_C = 9204

// colorchecker.c gui_init: lightness -100 .. 200, a and b -256 .. 256, saturation -128 .. 128, two digits
private val lSlider = maskSlider(SL_L, "lightness", -100.0, 200.0, 0.0, 1.0, 2, "")
private val aSlider = maskSlider(SL_A, "green-red", -256.0, 256.0, 0.0, 1.0, 2, "")
private val bSlider = maskSlider(SL_B, "blue-yellow", -256.0, 256.0, 0.0, 1.0, 2, "")
private val cSlider = maskSlider(SL_C, "saturation", -128.0, 128.0, 0.0, 1.0, 2, "")

@Composable
fun LutPanel(ctx: PanelContext) {
    val lut = ctx.controller.lut
    LaunchedEffect(ctx.key) { lut.open(ctx.key) }
    DisposableEffect(ctx.key) { onDispose { lut.close() } }
    val u by lut.state.collectAsState()
    val c = DtTheme.colors
    val sp = DtTheme.space
    val ro = ctx.readOnly
    val p = LutPatches.of(ctx.instance.params)
    if (p == null) {
        DtText("this module's parameters could not be read", DtTheme.type.label, c.textMuted, Modifier.padding(vertical = sp.s))
        return
    }
    val sel = u.patch.coerceIn(0, (p.n - 1).coerceAtLeast(0))
    PatchGrid(lut, p, sel, ro)
    Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
        DtTextButton(if (u.picking) "picking…" else "pick", { lut.togglePicking() }, Modifier.semantics { contentDescription = "pick a colour on the photo" },
            selected = u.picking, enabled = !ro, compact = true)
        u.picked?.let { lab ->
            Box(Modifier.size(DtTheme.sizes.fillSwatch).background(DataDraw.lab(lab.first, lab.second, lab.third))
                .border(DtTheme.sizes.border, c.border).semantics { contentDescription = "picked colour" })
        }
        Spacer(Modifier.weight(1f))
        DtTextButton("add patch from picked colour", { ctx.controller.launch { lut.addPicked() } },
            enabled = !ro && u.picked != null && p.n < p.max, compact = true)
    }
    if (p.n > 0) {
        // the patch combo box: "patch #k"
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DtText("patch", DtTheme.type.label, c.text, Modifier.weight(1f))
            DtTextButton("previous", { lut.select(sel - 1) }, enabled = sel > 0, compact = true)
            DtText("patch #$sel", DtTheme.type.label, c.textStrong, Modifier.padding(horizontal = sp.s).semantics { contentDescription = "patch #$sel" })
            DtTextButton("next", { lut.select(sel + 1) }, enabled = sel < p.n - 1, compact = true)
        }
        val o = p.offsets(sel)
        MaskSlider(lSlider, o.lightness, !ro, live = false) { v -> ctx.controller.launch { lut.setOffset("lightness", v) } }
        MaskSlider(aSlider, o.greenRed, !ro, live = false) { v -> ctx.controller.launch { lut.setOffset("green-red", v) } }
        MaskSlider(bSlider, o.blueYellow, !ro, live = false) { v -> ctx.controller.launch { lut.setOffset("blue-yellow", v) } }
        MaskSlider(cSlider, o.saturation, !ro, live = false) { v -> ctx.controller.launch { lut.setOffset("saturation", v) } }
    } else {
        DtText("no patches: pick a colour and add one, or choose a preset", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.s), maxLines = 2)
    }
    u.menu?.takeIf { it < p.n }?.let { i -> PatchMenu(ctx, lut, i, u.picked != null, ro) }
    u.note?.let { DtText(it, DtTheme.type.small, c.textStrong, Modifier.padding(vertical = sp.xs).semantics { contentDescription = "look up table note: $it" }, maxLines = 3) }
}

@Composable
private fun PatchGrid(lut: Lut, p: LutPatches, sel: Int, ro: Boolean) {
    val c = DtTheme.colors
    val z = DtTheme.sizes
    var size by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    val cols = p.columns
    val rows = p.rows
    fun patchAt(o: Offset): Int {
        if (size <= 0 || height <= 0) return -1
        val i = (o.x / size * cols).toInt().coerceIn(0, cols - 1)
        val j = (o.y / height * rows).toInt().coerceIn(0, rows - 1)
        return i + cols * j
    }
    // desktop: 2:3 (height:width) up to 24 patches, square above
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(if (p.n <= 24) 1.5f else 1f)
            .onSizeChanged { s: IntSize -> size = s.width; height = s.height }
            .pointerInput(p.n, ro) {
                detectTapGestures(
                    onTap = { o -> val k = patchAt(o); if (k in 0 until p.n) lut.select(k) else if (p.n > 0) lut.select(p.n - 1) },
                    onLongPress = { o -> val k = patchAt(o); if (!ro && k in 0 until p.n) { lut.select(k); lut.openMenu(k) } },
                )
            }
            .semantics { contentDescription = "patches"; stateDescription = "${p.n} patches, patch #$sel selected" },
    ) {
        drawRect(c.patchBg)
        val cw = this.size.width / cols
        val ch = this.size.height / rows
        val gap = z.patchGap.toPx()
        val ol = z.patchOutline.toPx()
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = i + j * cols
            if (k >= p.n) continue
            val (l, a, b) = p.source(k)
            val x = cw * i
            val y = ch * j
            drawRect(DataDraw.lab(l, a, b), Offset(x, y), Size(cw - gap, ch - gap))
            if (p.altered(k)) {
                drawRect(c.patchAltered, Offset(x + gap, y + gap), Size(cw - 3 * gap, ch - 3 * gap), style = Stroke(ol))
                drawRect(c.patchAlteredInner, Offset(x + 2 * gap, y + 2 * gap), Size(cw - 5 * gap, ch - 5 * gap), style = Stroke(gap))
            }
        }
        if (sel in 0 until p.n) {
            val i = sel % cols
            val j = sel / cols
            val inset = z.patchMarkInset.toPx()
            // desktop: black on light patches (L > 80), white on the others
            val mark = if (p.sourceL[sel] > 80) c.patchMarkDark else c.patchMarkLight
            drawRect(mark, Offset(cw * i + inset, ch * j + inset), Size(cw - 2 * inset - gap, ch - 2 * inset - gap), style = Stroke(ol))
        }
    }
}

/** A patch's long-press menu: desktop's shift+click, double-click and right-click. */
@Composable
private fun PatchMenu(ctx: PanelContext, lut: Lut, i: Int, picked: Boolean, ro: Boolean) {
    val sp = DtTheme.space
    DtText("patch #$i", DtTheme.type.label, DtTheme.colors.textStrong, Modifier.padding(top = sp.xs))
    Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        DtTextButton("replace source with picked colour", { ctx.controller.launch { lut.replaceSource(i) } }, Modifier.weight(1f), enabled = !ro && picked, compact = true)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        DtTextButton("reset", { ctx.controller.launch { lut.reset(i) } }, Modifier.weight(1f), enabled = !ro, compact = true)
        DtTextButton("delete", { ctx.controller.launch { lut.delete(i) } }, Modifier.weight(1f), enabled = !ro, compact = true)
        DtTextButton("close", { lut.openMenu(null) }, Modifier.weight(1f), compact = true)
    }
}

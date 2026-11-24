// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.Retouch
import app.dtandroid.darkroom.RetouchOutline
import app.dtandroid.darkroom.RetouchPart
import app.dtandroid.darkroom.RetouchTool
import app.dtandroid.darkroom.RetouchUi
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DataDraw
import app.dtandroid.ui.theme.DtTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.min

/*
 * Brief P3m: retouch's panel, as desktop's (retouch.c gui_init; the manual's "retouch" page): "retouch tools" with
 * the shape count, the shape buttons and "show and edit shapes", the algorithm buttons, "display masks" and
 * "temporarily switch off shapes"; the wavelet decompose line (editing it is not in this package); "shapes" with
 * the selected shape and the controls that apply to it. Touch equivalents: a long press on a shape button is
 * desktop's ctrl+click (several shapes), a long press on an algorithm button is ctrl+click (the selected shape's
 * algorithm).
 */

private const val SL_SIZE = 9101
private const val SL_FEATHER = 9102
private const val SL_FLAT = 9103
private const val SL_ROT = 9104
private const val SL_HARD = 9105
private const val SL_BLUR = 9106
private const val SL_BRIGHT = 9107
private const val SL_OPACITY = 9108
private const val SL_R = 9109
private const val SL_G = 9110
private const val SL_B = 9111

private val sizeSlider = maskSlider(SL_SIZE, "size", 0.005, 0.5, 0.03, 100.0, 1, "%")
private val featherSlider = maskSlider(SL_FEATHER, "feather", 0.0, 0.5, 0.01, 100.0, 1, "%")
private val flatSlider = maskSlider(SL_FLAT, "flatness", 0.1, 1.0, 0.6, 100.0, 0, "%")
private val rotSlider = maskSlider(SL_ROT, "rotation", -180.0, 180.0, 0.0, 1.0, 0, "°")
private val hardSlider = maskSlider(SL_HARD, "hardness", 0.0, 1.0, 0.66, 100.0, 0, "%")
// retouch.c: blur radius 0.1 .. 200 px (default 10), brightness -1 .. 1 shown as % with 4 digits, mask opacity 0 .. 1 as %
private val blurSlider = maskSlider(SL_BLUR, "blur radius", 0.1, 200.0, 10.0, 1.0, 1, " px")
private val brightSlider = maskSlider(SL_BRIGHT, "brightness", -1.0, 1.0, 0.0, 100.0, 2, "%")
private val opacitySlider = maskSlider(SL_OPACITY, "mask opacity", 0.0, 1.0, 1.0, 100.0, 1, "%")
private val redSlider = maskSlider(SL_R, "red", 0.0, 1.0, 0.0, 100.0, 1, "%")
private val greenSlider = maskSlider(SL_G, "green", 0.0, 1.0, 0.0, 100.0, 1, "%")
private val blueSlider = maskSlider(SL_B, "blue", 0.0, 1.0, 0.0, 100.0, 1, "%")

@Composable
fun RetouchPanel(ctx: PanelContext) {
    val rt = ctx.controller.retouch
    LaunchedEffect(ctx.key) { rt.open(ctx.key) }
    DisposableEffect(ctx.key) { onDispose { rt.close() } }
    val u by rt.state.collectAsState()
    val c = DtTheme.colors
    val sp = DtTheme.space
    val ro = ctx.readOnly
    val st = u.st
    val scope = ctx.controller

    Section("retouch tools")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DtText("shapes: ${st?.count ?: 0}", DtTheme.type.label, c.text, Modifier.weight(1f).semantics { contentDescription = "shapes: ${st?.count ?: 0}" })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        for (t in RetouchTool.entries) {
            val on = u.tool == t
            Column(
                Modifier.weight(1f).height(DtTheme.sizes.shapeButton).background(if (on) c.selected else c.raised)
                    .then(if (on && u.continuous) Modifier.border(DtTheme.sizes.border, c.accent) else Modifier)
                    .pointerInput(t, ro) {
                        if (!ro) detectTapGestures(onTap = { rt.setTool(t) }, onLongPress = { rt.setTool(t, continuous = true) })
                    }
                    .semantics { role = Role.Button; contentDescription = "add ${t.key}"; stateDescription = if (on) (if (u.continuous) "on, several" else "on") else "off" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                DtIconImage(shapeIcon(t.key), null, if (ro) c.textDisabled else if (on) c.textBright else c.textStrong, DtTheme.sizes.icon)
                DtText(t.key, DtTheme.type.small, if (ro) c.textDisabled else c.text)
            }
        }
    }
    ToggleControl("show and edit shapes", u.showShapes, true) { rt.toggleShowShapes() }
    DtText("algorithms", DtTheme.type.label, c.text, Modifier.padding(top = sp.xs))
    Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        // desktop's order (packed from the end): heal, clone, fill, blur
        for (a in listOf("heal", "clone", "fill", "blur")) {
            AlgoButton(a, st?.algorithm == a, !ro && st != null, Modifier.weight(1f),
                onTap = { scope.launch { rt.setAlgorithm(a) } },
                onLong = { scope.launch { rt.applyAlgorithmToSelected(a) } })
        }
    }
    if (u.selected != null) DtText("long-press an algorithm to change the selected shape's (clone and heal, blur and fill)",
        DtTheme.type.small, c.textMuted, Modifier.padding(bottom = sp.xxs), maxLines = 2)
    ToggleControl("display masks", u.displayMasks, true) { rt.toggleDisplayMasks() }
    ToggleControl("temporarily switch off shapes", u.suppress, true) { rt.toggleSuppress() }

    // the tool that is on: how to use it, and the new shape's size
    u.tool?.let { t -> ToolSection(rt, u, t, ro) }

    // brief P3m item 3: wavelet decompose is edited on desktop; the engine renders it and its settings stay
    if (st != null && st.numScales > 0) {
        Section("wavelet decompose")
        DtText("wavelet decompose: ${st.numScales} scales · edit on desktop", DtTheme.type.label, c.textStrong, maxLines = 2)
        DtText("shapes of scale ${st.currScale} are shown and edited", DtTheme.type.small, c.textMuted, maxLines = 2)
    }

    Section("shapes")
    val sel = u.selectedShape
    Row(Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically) {
        DtText("shape selected: ${sel?.let { "${it.name} (${it.type}, ${it.algorithm})" } ?: "none"}", DtTheme.type.label, c.text, Modifier.weight(1f), maxLines = 2)
        if (sel != null) DtTextButton("remove", { scope.launch { rt.removeSelected() } }, enabled = !ro, compact = true)
    }
    if (st != null) {
        // desktop shows the blur or fill controls of the algorithm the module has (rt_show_hide_controls); the
        // selected shape's own values when it is one of that kind (selecting copies them to the module)
        when (st.algorithm) {
            "blur" -> {
                Choice("blur type", listOf("gaussian", "bilateral"), st.blurType, !ro) { v -> scope.launch { rt.setSetting("blur_type", JsonPrimitive(v)) } }
                MaskSlider(blurSlider, st.blurRadius, !ro, live = false) { v -> scope.launch { rt.setSetting("blur_radius", JsonPrimitive(v)) } }
            }
            "fill" -> {
                Choice("fill mode", listOf("erase", "color"), st.fillMode, !ro) { v -> scope.launch { rt.setSetting("fill_mode", JsonPrimitive(v)) } }
                if (st.fillMode == "color") FillColor(rt, st.fillColor, u.picking, ro)
                MaskSlider(brightSlider, st.fillBrightness, !ro, live = false) { v -> scope.launch { rt.setSetting("fill_brightness", JsonPrimitive(v)) } }
            }
        }
    }
    if (sel != null) {
        MaskSlider(opacitySlider, sel.opacity, !ro, live = false) { v -> scope.launch { rt.setOpacity(v) } }
        // desktop resizes with the mouse wheel on the photo; here with sliders as the masks tools
        val g = sel.geometry
        when (sel.type) {
            "circle" -> {
                MaskSlider(sizeSlider, (g["radius"] as? JsonPrimitive)?.doubleOrNull ?: 0.03, !ro, live = false) { v -> scope.launch { rt.setShapeValue("size", v) } }
                MaskSlider(featherSlider, (g["border"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, !ro, live = false) { v -> scope.launch { rt.setShapeValue("feather", v) } }
            }
            "ellipse" -> {
                val r = (g["radius"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: listOf(0.03, 0.02)
                val a = r.getOrElse(0) { 0.03 }
                MaskSlider(sizeSlider, a, !ro, live = false) { v -> scope.launch { rt.setShapeValue("size", v) } }
                MaskSlider(flatSlider, (r.getOrElse(1) { a } / a).coerceIn(0.1, 1.0), !ro, live = false) { v -> scope.launch { rt.setShapeValue("flatness", v) } }
                MaskSlider(rotSlider, (g["rotation"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, !ro, live = false) { v -> scope.launch { rt.setShapeValue("rotation", v) } }
                MaskSlider(featherSlider, (g["border"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, !ro, live = false) { v -> scope.launch { rt.setShapeValue("feather", v) } }
            }
        }
    } else if (st != null && st.count > 0 && u.tool == null) {
        DtText("tap a shape on the photo to select it; drag its target or source to move it", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.xs), maxLines = 2)
    }
    u.note?.let { DtText(it, DtTheme.type.small, c.textStrong, Modifier.padding(vertical = sp.xs).semantics { contentDescription = "retouch note: $it" }, maxLines = 3) }
}

@Composable
private fun Section(text: String) {
    DtText(text, DtTheme.type.label, DtTheme.colors.textStrong, Modifier.padding(top = DtTheme.space.s, bottom = DtTheme.space.xxs))
}

@Composable
private fun ToolSection(rt: Retouch, u: RetouchUi, t: RetouchTool, ro: Boolean) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    val hint = when (t) {
        RetouchTool.CIRCLE, RetouchTool.ELLIPSE -> "tap the spot to fix: the source goes up and to the right; or press the spot and drag to the source"
        RetouchTool.PATH -> "tap the path's points; tap the first point or done to close it"
        RetouchTool.BRUSH -> "paint the spot with one finger"
    }
    DtText(hint + if (u.continuous) " · several shapes: tap the button again to stop" else "", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.xs), maxLines = 3)
    when (t) {
        RetouchTool.CIRCLE -> {
            MaskSlider(sizeSlider, u.size, !ro, live = true) { rt.setNewValue("size", it) }
            MaskSlider(featherSlider, u.feather, !ro, live = true) { rt.setNewValue("feather", it) }
        }
        RetouchTool.ELLIPSE -> {
            MaskSlider(sizeSlider, u.size, !ro, live = true) { rt.setNewValue("size", it) }
            MaskSlider(flatSlider, u.flatness, !ro, live = true) { rt.setNewValue("flatness", it) }
            MaskSlider(rotSlider, u.rotation, !ro, live = true) { rt.setNewValue("rotation", it) }
            MaskSlider(featherSlider, u.feather, !ro, live = true) { rt.setNewValue("feather", it) }
        }
        RetouchTool.BRUSH -> {
            MaskSlider(sizeSlider, u.size, !ro, live = true) { rt.setNewValue("size", it) }
            MaskSlider(hardSlider, u.hardness, !ro, live = true) { rt.setNewValue("hardness", it) }
        }
        RetouchTool.PATH -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
            DtText("${u.pathPoints.size} points", DtTheme.type.label, c.text, Modifier.weight(1f).padding(top = sp.s))
            DtTextButton("cancel", { rt.cancelPath() }, enabled = u.pathPoints.isNotEmpty(), compact = true)
            DtTextButton("done", { rtLaunch(rt) }, enabled = u.pathPoints.size >= 3 && !ro, compact = true)
        }
    }
}

private fun rtLaunch(rt: Retouch) = rt.finishPathLater()

/** An algorithm button: a tap chooses the algorithm of new shapes, a long press changes the selected shape's. */
@Composable
private fun AlgoButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onTap: () -> Unit, onLong: () -> Unit) {
    val c = DtTheme.colors
    val shape = RoundedCornerShape(DtTheme.sizes.radiusControl)
    var m = modifier.height(DtTheme.sizes.touch).clip(shape).background(if (selected) c.selected else c.raised)
    if (selected) m = m.border(DtTheme.sizes.border, c.accent, shape)
    Box(
        m.pointerInput(label, enabled) { if (enabled) detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() }) }
            .semantics { role = Role.Button; contentDescription = "algorithm $label"; stateDescription = if (selected) "selected" else "not selected" },
        contentAlignment = Alignment.Center,
    ) {
        DtText(label, DtTheme.type.label, when {
            !enabled -> c.textDisabled
            selected -> c.textBright
            else -> c.textStrong
        })
    }
}

/** A choice of a few values as a row of buttons (desktop: a combo box). */
@Composable
private fun Choice(label: String, values: List<String>, current: String, enabled: Boolean, onChoose: (String) -> Unit) {
    val sp = DtTheme.space
    Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        DtText(label, DtTheme.type.label, DtTheme.colors.text, Modifier.weight(1f))
        for (v in values) DtTextButton(v, { if (v != current) onChoose(v) }, Modifier.semantics { contentDescription = "$label $v" }, selected = v == current, enabled = enabled, compact = true)
    }
}

/** Fill colour: the swatch (a tap opens red, green, blue as desktop's colour chooser), the picker from the image. */
@Composable
private fun FillColor(rt: Retouch, rgb: List<Double>, picking: Boolean, ro: Boolean) {
    val c = DtTheme.colors
    val sp = DtTheme.space
    var open by remember { mutableStateOf(false) }
    val scope = rt
    Row(Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically) {
        DtText("fill color", DtTheme.type.label, c.text, Modifier.weight(1f))
        Box(
            Modifier.size(DtTheme.sizes.fillSwatch).background(DataDraw.rgb(rgb[0], rgb[1], rgb[2])).border(DtTheme.sizes.border, c.border)
                .pointerInput(ro) { if (!ro) detectTapGestures(onTap = { open = !open }) }
                .semantics { role = Role.Button; contentDescription = "fill color"; stateDescription = if (open) "open" else "closed" },
        )
        Spacer(Modifier.size(sp.s))
        DtTextButton(if (picking) "picking…" else "pick", { scope.togglePicking() }, Modifier.semantics { contentDescription = "pick fill color from image" },
            selected = picking, enabled = !ro, compact = true)
    }
    if (open) {
        val sliders = listOf(redSlider, greenSlider, blueSlider)
        for (i in 0..2) MaskSlider(sliders[i], rgb[i], !ro, live = false) { v ->
            val n = rgb.toMutableList().also { it[i] = v.coerceIn(0.0, 1.0) }
            scope.setFillColorLater(n)
        }
    }
}

// ----------------------------------------------------------------------- on the photo

/**
 * Retouch's shapes over the picture drawn at [left], [top], [w] x [h]: each target outline (light), its source
 * (in the source colour) and a line between them; the selected shape's points as handles; with "display masks" the
 * targets filled in the mask colour; what a finger is placing or moving now.
 */
fun DrawScope.drawRetouch(u: RetouchUi, left: Float, top: Float, w: Float, h: Float, look: MaskLook, sourceColor: androidx.compose.ui.graphics.Color) {
    fun at(p: Pair<Double, Double>) = Offset(left + p.first.toFloat() * w, top + p.second.toFloat() * h)
    val side = min(w, h)
    clipRect(left, top, left + w, top + h) {
        if (u.displayMasks) for (o in u.outlines) {
            if (o.closed) DataDraw.fillPolygon(this, o.target.map { at(it) }, look.fill, look.fillAlpha)
            else {
                val r = (o.halfWidth * w).toFloat().coerceAtLeast(look.lineWidth.toPx())
                val pts = o.target.map { at(it) }
                for (i in 1 until pts.size) segment(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, 2 * r, look.fill, look.fillAlpha)
            }
        }
        if (u.showShapes) for (o in u.outlines) {
            val d = u.drag?.takeIf { it.id == o.id && it.moved }
            val tdx = if (d?.part == RetouchPart.TARGET) d.dx else 0.0
            val tdy = if (d?.part == RetouchPart.TARGET) d.dy else 0.0
            val sdx = if (d?.part == RetouchPart.SOURCE) d.dx else 0.0
            val sdy = if (d?.part == RetouchPart.SOURCE) d.dy else 0.0
            drawOutline(o, tdx, tdy, sdx, sdy, o.id == u.selected, ::at, look, sourceColor)
        }
        // placing: target where the finger went down, the source where it is (circle, ellipse)
        val press = u.press
        if (press != null && u.tool != null) {
            val rx = (u.size * side).toFloat()
            val ry = if (u.tool == RetouchTool.ELLIPSE) (u.size * u.flatness * side).toFloat() else rx
            val turn = if (u.tool == RetouchTool.ELLIPSE) u.rotation.toFloat() else 0f
            val p = at(press)
            rotate(turn, p) { drawOval(look.line, Offset(p.x - rx, p.y - ry), Size(2 * rx, 2 * ry), style = Stroke(look.lineWidth.toPx())) }
            if (u.pressMoved) u.pressNow?.let { q ->
                val s = at(q)
                rotate(turn, s) { drawOval(sourceColor, Offset(s.x - rx, s.y - ry), Size(2 * rx, 2 * ry), style = Stroke(look.lineWidth.toPx())) }
                segment(p.x, p.y, s.x, s.y, look.lineWidth.toPx(), sourceColor)
            }
        }
        if (u.pathPoints.isNotEmpty()) {
            val pts = u.pathPoints.map { at(it) }
            line(pts, false, look)
            for (p in pts) handle(p, look)
        }
        u.stroke?.let { s ->
            val pts = s.map { at(it) }
            val r = (u.size / 2 * side).toFloat()
            for (i in 1 until pts.size) segment(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, 2 * r, look.fill, look.fillAlpha)
        }
    }
}

private fun DrawScope.drawOutline(o: RetouchOutline, tdx: Double, tdy: Double, sdx: Double, sdy: Double, selected: Boolean,
                                  at: (Pair<Double, Double>) -> Offset, look: MaskLook, sourceColor: androidx.compose.ui.graphics.Color) {
    val target = o.target.map { at(it.first + tdx to it.second + tdy) }
    line(target, o.closed, look)
    val ta = at(o.targetAt.first + tdx to o.targetAt.second + tdy)
    val src = o.source
    val sa = o.sourceAt
    if (src != null && sa != null) {
        val spts = src.map { at(it.first + sdx to it.second + sdy) }
        val sp = at(sa.first + sdx to sa.second + sdy)
        val w = look.lineWidth.toPx()
        val all = if (o.closed && spts.size > 2) spts + spts.first() else spts
        for (i in 1 until all.size) segment(all[i - 1].x, all[i - 1].y, all[i].x, all[i].y, w * 3, look.shadow, 0.6f)
        for (i in 1 until all.size) segment(all[i - 1].x, all[i - 1].y, all[i].x, all[i].y, w, sourceColor)
        segment(ta.x, ta.y, sp.x, sp.y, w, sourceColor, 0.7f)
        if (selected) handle(sp, look)
    }
    if (selected) handle(ta, look)
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import app.dtandroid.darkroom.FitVariant
import app.dtandroid.darkroom.PerspectiveUi
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P3n: rotate and perspective's structure and fit, inside the module as desktop (ashift.c gui_init, section
 * "perspective": structure rectangle / auto / lines, fit vertical / horizontal / both), placed first as in the approved
 * mockup; the module's generated controls (rotation, lens shift, shear, automatic cropping, lens model) follow.
 * Desktop's ctrl and shift clicks: a long press on "find lines" offers its enhancements (ctrl: edges, shift: detail,
 * both), a long press on a fit button its variants (ctrl: rotation only, shift: lens shift only, ctrl+shift on "both":
 * without shear).
 */

@Composable
fun PerspectivePanel(ctx: PanelContext) {
    val ps = ctx.controller.perspective
    // the panel may leave the composition when the list scrolls: the module keeps the focus while expanded
    LaunchedEffect(ctx.key, ctx.state.info) { ps.open(ctx.key) }
    val u by ps.state.collectAsState()
    val c = DtTheme.colors
    val sp = DtTheme.space
    val ro = ctx.readOnly
    val st = u.st
    val scope = ctx.controller
    var menu by remember { mutableStateOf<String?>(null) }

    DtText("structure", DtTheme.type.label, c.textStrong, Modifier.padding(top = sp.s, bottom = sp.xxs))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        StructureButton("rectangle", DtIcon.PerspectiveRectangle, st?.rectangleOn == true, !ro && st != null, Modifier.weight(1f),
            onTap = { menu = null; scope.launch { perspective.structure("rectangle") } }, onLong = null)
        StructureButton("find lines", DtIcon.PerspectiveFindLines, st?.linesFoundOn == true, !ro && st != null, Modifier.weight(1f),
            onTap = { menu = null; scope.launch { perspective.structure("lines_found") } }, onLong = { menu = if (menu == "find") null else "find" })
        StructureButton("draw lines", DtIcon.PerspectiveDrawLines, st?.drawnOn == true, !ro && st != null, Modifier.weight(1f),
            onTap = { menu = null; scope.launch { perspective.structure("drawn") } }, onLong = null)
    }
    if (menu == "find") Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        for ((label, e) in listOf("edges" to "edges", "detail" to "detail", "edges + detail" to "both")) {
            DtTextButton(label, { menu = null; scope.launch { perspective.structure("lines_found", e) } },
                Modifier.weight(1f).semantics { contentDescription = "find lines, enhance $label" }, enabled = !ro, compact = true)
        }
    }
    val fitOn = !ro && st?.fitEnabled == true
    DtText("fit", DtTheme.type.label, c.textStrong, Modifier.padding(top = sp.s, bottom = sp.xxs))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        for (axis in listOf("vertical", "horizontal", "both")) {
            FitButton(axis, fitOn, Modifier.weight(1f), onTap = { menu = null; scope.launch { perspective.fit(axis) } },
                onLong = { menu = if (menu == axis) null else axis })
        }
    }
    menu?.takeIf { it != "find" }?.let { axis ->
        val variants = if (axis == "both") listOf(FitVariant.ROTATION, FitVariant.SHIFT, FitVariant.NO_SHEAR) else listOf(FitVariant.ROTATION, FitVariant.SHIFT)
        Row(Modifier.fillMaxWidth().padding(vertical = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
            for (v in variants) DtTextButton(v.label, { menu = null; scope.launch { perspective.fit(axis, v) } },
                Modifier.weight(1f).semantics { contentDescription = "fit $axis, ${v.label}" }, enabled = fitOn, compact = true)
        }
    }
    if (st != null && st.method != "none") {
        val hint = when (st.method) {
            "rectangle" -> "drag the four corners onto something that is a rectangle in reality, then tap a fit button"
            "drawn" -> "drag along an edge to draw a line; drag an end to move it; long-press a line to delete it"
            else -> "tap a line to use it, long-press to leave it out; ${st.vertical} vertical and ${st.horizontal} horizontal lines used"
        }
        DtText(hint, DtTheme.type.small, c.textMuted, Modifier.padding(vertical = sp.xs).semantics { contentDescription = "structure: $hint" }, maxLines = 3)
        if (st.method == "lines_found" || st.method == "drawn")
            ToggleControl("select by area (drag a box; long-press first to leave lines out)", u.areaMode, !ro) { ps.toggleAreaMode() }
    }
    ToggleControl("show guides", u.showGuides, true) { ps.toggleGuides() }
    (u.note ?: st?.message)?.let {
        DtText(it, DtTheme.type.small, c.textStrong, Modifier.padding(vertical = sp.xs).semantics { contentDescription = "perspective note: $it" }, maxLines = 3)
    }
    DtText("set by fit, or by hand", DtTheme.type.label, c.textStrong, Modifier.padding(top = sp.s, bottom = sp.xxs))
}

@Composable
private fun StructureButton(label: String, icon: DtIcon, on: Boolean, enabled: Boolean, modifier: Modifier, onTap: () -> Unit, onLong: (() -> Unit)?) {
    val c = DtTheme.colors
    Column(
        modifier.height(DtTheme.sizes.shapeButton).background(if (on) c.selected else c.raised)
            .then(if (on) Modifier.border(DtTheme.sizes.border, c.accent) else Modifier)
            .pointerInput(label, enabled, onLong != null) {
                if (enabled) detectTapGestures(onTap = { onTap() }, onLongPress = onLong?.let { l -> { _: Offset -> l() } })
            }
            .semantics { role = Role.Button; contentDescription = label; stateDescription = if (on) "on" else "off" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        DtIconImage(icon, null, if (!enabled) c.textDisabled else if (on) c.textBright else c.textStrong, DtTheme.sizes.icon)
        DtText(label, DtTheme.type.small, if (enabled) c.text else c.textDisabled)
    }
}

@Composable
private fun FitButton(axis: String, enabled: Boolean, modifier: Modifier, onTap: () -> Unit, onLong: () -> Unit) {
    val c = DtTheme.colors
    val shape = RoundedCornerShape(DtTheme.sizes.radiusControl)
    Box(
        modifier.height(DtTheme.sizes.touch).clip(shape).background(c.raised)
            .pointerInput(axis, enabled) { if (enabled) detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() }) }
            .semantics { role = Role.Button; contentDescription = "fit $axis" },
        contentAlignment = Alignment.Center,
    ) {
        DtText(axis, DtTheme.type.label, if (enabled) c.textStrong else c.textDisabled)
    }
}

/** What the structure is drawn with (tokens). */
class GuideLook(
    val verticalUsed: Color, val verticalOutlier: Color, val horizontalUsed: Color, val horizontalOutlier: Color, val other: Color,
    val area: Color, val handle: Color, val dim: Color, val frame: Color, val grid: Color,
    val line: Dp, val lineNear: Dp, val handleR: Dp, val handleNear: Dp, val frameLine: Dp, val gridLine: Dp,
)

@Composable
fun guideLook(): GuideLook {
    val c = DtTheme.colors
    val s = DtTheme.sizes
    return GuideLook(c.guideVerticalUsed, c.guideVerticalOutlier, c.guideHorizontalUsed, c.guideHorizontalOutlier, c.guideOther,
        c.guideArea, c.cropHandle, c.cropDim, c.cropFrame, c.cropGrid, s.guideLine, s.guideLineNear, s.guideHandle, s.guideHandleNear,
        s.cropFrameLine, s.cropGridLine)
}

/**
 * Desktop's gui_post_expose: outside the automatic crop dimmed and its outline; the lines in desktop's colours (the
 * one near the finger thicker; removed drawn lines are not sent); the corners of a rectangle and the ends of drawn
 * lines; the box of a selection by area; composition guides (thirds) in the crop when "show guides" is on.
 */
fun DrawScope.drawPerspective(u: PerspectiveUi, left: Float, top: Float, w: Float, h: Float, look: GuideLook) {
    val st = u.st ?: return
    fun at(x: Double, y: Double) = Offset(left + x.toFloat() * w, top + y.toFloat() * h)
    st.clip?.let { cl ->
        // the crop's corners (top-left, bottom-left, bottom-right, top-right); outside its box dimmed as four bands
        val pts = cl.map { at(it.first, it.second) }
        val x0 = pts.minOf { it.x }.coerceIn(left, left + w)
        val x1 = pts.maxOf { it.x }.coerceIn(left, left + w)
        val y0 = pts.minOf { it.y }.coerceIn(top, top + h)
        val y1 = pts.maxOf { it.y }.coerceIn(top, top + h)
        drawRect(look.dim, Offset(left, top), Size(w, y0 - top))
        drawRect(look.dim, Offset(left, y1), Size(w, top + h - y1))
        drawRect(look.dim, Offset(left, y0), Size(x0 - left, y1 - y0))
        drawRect(look.dim, Offset(x1, y0), Size(left + w - x1, y1 - y0))
        val fl = look.frameLine.toPx()
        for (i in pts.indices) { val q = pts[(i + 1) % pts.size]; segment(pts[i].x, pts[i].y, q.x, q.y, fl, look.frame) }
        if (u.showGuides) {
            // thirds between the corners
            fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            val gl = look.gridLine.toPx()
            for (t in listOf(1f / 3, 2f / 3)) {
                val a1 = lerp(pts[0], pts[3], t); val b1 = lerp(pts[1], pts[2], t)
                val a2 = lerp(pts[0], pts[1], t); val b2 = lerp(pts[3], pts[2], t)
                segment(a1.x, a1.y, b1.x, b1.y, gl, look.grid)
                segment(a2.x, a2.y, b2.x, b2.y, gl, look.grid)
            }
        }
    }
    for (l in st.lines) {
        val col = when (l.color) {
            "green" -> look.verticalUsed
            "red" -> look.verticalOutlier
            "blue" -> look.horizontalUsed
            "yellow" -> look.horizontalOutlier
            else -> look.other
        }
        val a = at(l.x1, l.y1)
        val b = at(l.x2, l.y2)
        segment(a.x, a.y, b.x, b.y, (if (l.near) look.lineNear else look.line).toPx(), col)
    }
    for (hd in st.handles) {
        val r = (if (hd.near) look.handleNear else look.handleR).toPx()
        val p = at(hd.x, hd.y)
        drawOval(look.handle, Offset(p.x - r, p.y - r), Size(2 * r, 2 * r), style = Stroke((if (hd.near) look.lineNear else look.line).toPx()))
    }
    val from = st.areaFrom
    val to = u.finger
    if (from != null && to != null) {
        val a = at(from.first, from.second)
        val b = at(to.first, to.second)
        drawRect(look.area, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
            style = Stroke(look.line.toPx()))
    }
}

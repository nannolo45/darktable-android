// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import app.dtandroid.darkroom.ui.ControlNote
import app.dtandroid.darkroom.ui.DtText
import app.dtandroid.darkroom.ui.PanelContext
import app.dtandroid.engine.api.Histogram
import app.dtandroid.engine.api.paramAt
import app.dtandroid.schema.Control
import app.dtandroid.schema.Rules
import app.dtandroid.ui.theme.DtTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Brief P3e: the graph editors of tone curve, rgb curve, rgb levels and tone equalizer, drawn where the schema has
 * a `graph` control (docs/design/ui-spec.md, "graph editors: curves"). Touch edits only: a node, a handle or a band
 * moves with the finger; every finger that lifts is one history item. The logic is in [CurveEdit], [LevelsEdit] and
 * [ToneEqMath]; the engine is reached through the controller's `curveDrag`, `curveEnd` and `curveCommit` only.
 */
@Composable
fun CurveEditorHost(control: Control.Unsupported, ctx: PanelContext) {
    when (ctx.instance.op) {
        "tonecurve", "rgbcurve" -> CurveEditor(ctx)
        "rgblevels" -> LevelsEditor(ctx)
        "toneequal" -> ToneEqEditor(ctx)
        else -> Unit
    }
}

// ------------------------------------------------------------------------------------------------ shared pieces

/** The colours and sizes of a graph, from the tokens. */
private class GraphLook(
    val bg: Color, val grid: Color, val line: Color, val node: Color, val nodeFill: Color, val histogram: Color,
    val gridLine: Dp, val curveLine: Dp, val nodeSize: Dp,
)

@Composable
private fun graphLook() = GraphLook(
    DtTheme.colors.curveBg, DtTheme.colors.curveGrid, DtTheme.colors.curveLine, DtTheme.colors.curveNode, DtTheme.colors.curveNodeFill,
    DtTheme.colors.curveHistogram, DtTheme.sizes.curveGridLine, DtTheme.sizes.curveLine, DtTheme.sizes.curveNode,
)

/** The side of a square graph: the panel's width, at most the token's height; in the l1 layout it also leaves room for the tabs. */
@Composable
private fun graphSide(): Dp =
    if (DtTheme.l1.layout) minOf(DtTheme.sizes.curveMaxHeight, DtTheme.l1.panelMaxHeight - DtTheme.sizes.touch * 2) else DtTheme.sizes.curveMaxHeight

/** The page of the channel tabs the graph belongs to; the first channel while the tabs are hidden (desktop goes back to it). */
private fun channelOf(ctx: PanelContext): Int {
    val tabs = ctx.module.allControls().filterIsInstance<Control.Tabs>().firstOrNull { it.pages.size == 3 } ?: return 0
    val shown = ctx.module.pathTo(tabs.id).all { Rules.isVisible(ctx.module, it, ctx.inputs) }
    return if (shown) ctx.controller.tabPage(ctx.state, ctx.key, tabs.id).coerceIn(0, 2) else 0
}

/** The histogram of the picture, read again whenever the module's parameters change (the engine's, behind the module: engine-api.md 6.4). */
@Composable
private fun rememberHistogram(ctx: PanelContext): Histogram? {
    var h by remember { mutableStateOf(ctx.controller.histogramCache) }
    LaunchedEffect(ctx.key, ctx.instance.params) {
        val got = ctx.controller.histogramNow()
        if (got != null) h = got
    }
    return h
}

/** The counts to draw: one channel's, or the three added up for a grey histogram. */
private fun counts(h: Histogram?, channel: Int?): List<Long>? {
    if (h == null || h.counts.isEmpty()) return null
    return if (channel != null && channel in h.counts.indices) h.counts[channel]
    else List(h.bins) { i -> h.counts.sumOf { it.getOrElse(i) { 0L } } }
}

/** A straight piece from (x0, y0) to (x1, y1) as a turned rectangle: the screen rules keep lines to rectangles. */
private fun DrawScope.segment(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Color) {
    val dx = x1 - x0
    val dy = y1 - y0
    val length = sqrt(dx * dx + dy * dy)
    if (length <= 0f) return
    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    rotate(angle, Offset(x0, y0)) { drawRect(color, Offset(x0, y0 - width / 2), Size(length, width)) }
}

/** The histogram as bars, in the log scale of desktop's default (`log(1 + count) / log(1 + max)`). */
private fun DrawScope.histogramBars(c: List<Long>?, left: Float, top: Float, w: Float, h: Float, color: Color) {
    if (c == null || c.isEmpty()) return
    val peak = c.max().coerceAtLeast(1L)
    val barW = w / c.size
    val norm = ln(1.0 + peak)
    c.forEachIndexed { i, v ->
        val bh = (ln(1.0 + v) / norm).toFloat() * h
        if (bh > 0f) drawRect(color, Offset(left + i * barW, top + h - bh), Size(barW + 0.5f, bh))
    }
}

/** A node: a diamond with a dark fill and a light edge; filled light while it is held. */
private fun DrawScope.nodeMark(cx: Float, cy: Float, look: GraphLook, held: Boolean) {
    val s = look.nodeSize.toPx()
    val line = look.curveLine.toPx()
    rotate(45f, Offset(cx, cy)) {
        drawRect(if (held) look.node else look.nodeFill, Offset(cx - s / 2, cy - s / 2), Size(s, s))
        drawRect(look.node, Offset(cx - s / 2, cy - s / 2), Size(s, s), style = Stroke(line))
    }
}

// ------------------------------------------------------------------------------------------------ tone curve and rgb curve

@Composable
private fun CurveEditor(ctx: PanelContext) {
    val layout = CurveLayout.of(ctx.instance.op)
    if (layout != null) CurveEditorBody(layout, ctx)
}

@Composable
private fun CurveEditorBody(layout: CurveLayout, ctx: PanelContext) {
    val ch = channelOf(ctx)
    val params = ctx.instance.params
    val rgb = layout.op == "rgbcurve"
    // desktop edits a and b only with independent channels (tonecurve.c: autoscale_ab MANUAL); the rgb curve's tabs only exist then
    val independent = if (rgb) (params.paramAt("curve_autoscale") as? JsonPrimitive)?.content.let { it == "DT_S_SCALE_MANUAL_RGB" || it == "1" }
    else (params.paramAt("tonecurve_autoscale_ab") as? JsonPrimitive)?.content.let { it == "DT_S_SCALE_MANUAL" || it == "0" }
    val editable = !ctx.readOnly && (ch == 0 || independent)
    val stored = remember(params, ch) { layout.read(params, ch) }
    val type = layout.readType(params, ch)
    // the nodes while a finger moves them (the gesture writes these; the engine's own come back when the finger lifts)
    var local by remember { mutableStateOf<List<CurveNode>?>(null) }
    var held by remember { mutableIntStateOf(-1) }
    LaunchedEffect(params, ch) { local = null; held = -1 }
    val nodes = local ?: stored
    val look = graphLook()
    val tint = when {
        rgb && independent -> listOf(DtTheme.colors.curveRed, DtTheme.colors.curveGreen, DtTheme.colors.curveBlue)[ch]
        else -> look.line
    }
    val hist = counts(rememberHistogram(ctx), if (rgb && independent) ch else null)
    val vc = LocalViewConfiguration.current
    val insetDp = DtTheme.space.l
    val touchDp = DtTheme.sizes.touch
    val nowNodes by rememberUpdatedState(nodes)
    val nowParams by rememberUpdatedState(params)
    val lastTap = remember { mutableLongStateOf(0L) }
    val mayEdit = editable
    val note = if (!mayEdit && !ctx.readOnly) "this curve has no effect with linked channels; double tap the graph to edit it" else null

    Box(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = graphSide()).fillMaxWidth().aspectRatio(1f).background(look.bg)
                .semantics { contentDescription = "curve graph"; stateDescription = "${nodes.size} nodes" }
                .pointerInput(ch, mayEdit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val inset = insetDp.toPx()
                        val pw = (w - 2 * inset).coerceAtLeast(1f)
                        val ph = (h - 2 * inset).coerceAtLeast(1f)
                        val p0 = down.position
                        val base = nowNodes
                        val previous = layout.countOf(nowParams, ch)
                        // a second tap soon after the first: desktop's double click, the channel's curve back to its default
                        if (lastTap.longValue > 0L && down.uptimeMillis - lastTap.longValue <= vc.doubleTapTimeoutMillis) {
                            lastTap.longValue = 0L
                            down.consume()
                            if (mayEdit) {
                                val defaults = layout.defaults(ch)
                                ctx.controller.curveCommit(ctx.key, layout.writes(ch, defaults, previous) + (layout.typePath(ch) to JsonPrimitive(CurveLayout.DEFAULT_TYPE.toLong())))
                            } else if (!ctx.readOnly && !rgb && ch > 0) {
                                ctx.controller.launch { setParam(ctx.key, "tonecurve_autoscale_ab", JsonPrimitive(0L)) }
                            }
                            do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        if (!mayEdit) {
                            var up = down.uptimeMillis
                            do { val e = awaitPointerEvent(); up = e.changes.maxOf { it.uptimeMillis } } while (e.changes.any { it.pressed })
                            lastTap.longValue = up
                            return@awaitEachGesture
                        }
                        down.consume()
                        fun fx(p: Offset) = ((p.x - inset) / pw).coerceIn(0f, 1f).toDouble()
                        fun fy(p: Offset) = (1f - ((p.y - inset) / ph).coerceIn(0f, 1f)).toDouble()
                        val reach = touchDp.toPx() / 2
                        var sel = CurveEdit.hit(base, fx(p0), fy(p0), (reach / pw).toDouble(), (reach / ph).toDouble())
                        var cur = base
                        var added = false
                        var moved = false
                        var up = down.uptimeMillis
                        if (sel >= 0) {
                            // on a node: a long press removes it, a drag moves it, a tap does nothing
                            var upAt = down.uptimeMillis
                            val verdict: Int? = withTimeoutOrNull(vc.longPressTimeoutMillis) {
                                var res = 0
                                while (res == 0) {
                                    val e = awaitPointerEvent()
                                    upAt = e.changes.maxOf { it.uptimeMillis }
                                    val c = e.changes.firstOrNull { it.id == down.id }
                                    res = if (c == null || !c.pressed) 1 else if ((c.position - p0).getDistance() > vc.touchSlop) 2 else 0
                                }
                                res
                            }
                            if (verdict == null) {
                                val next = CurveEdit.remove(base, sel)
                                ctx.controller.curveCommit(ctx.key, layout.writes(ch, next, previous))
                                do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                                return@awaitEachGesture
                            }
                            if (verdict == 1) {
                                lastTap.longValue = upAt
                                return@awaitEachGesture
                            }
                            moved = true
                        } else {
                            // empty space: a node is added there (and follows the finger)
                            val add = CurveEdit.add(base, fx(p0), fy(p0), layout.maxNodes)
                            if (add == null) {
                                do { val e = awaitPointerEvent(); up = e.changes.maxOf { it.uptimeMillis } } while (e.changes.any { it.pressed })
                                lastTap.longValue = up
                                return@awaitEachGesture
                            }
                            cur = add.first
                            sel = add.second
                            added = true
                            local = cur
                            ctx.controller.curveDrag(ctx.key, layout.writes(ch, cur, previous))
                        }
                        held = sel
                        var off = false
                        do {
                            val e = awaitPointerEvent()
                            up = e.changes.maxOf { it.uptimeMillis }
                            val c = e.changes.firstOrNull { it.id == down.id }
                            if (c != null && c.pressed) {
                                if ((c.position - p0).getDistance() > vc.touchSlop) moved = true
                                // a node dragged off the graph is dropped there
                                off = c.position.x < -inset || c.position.x > w + inset || c.position.y < -inset || c.position.y > h + inset
                                if (!off && moved) {
                                    cur = CurveEdit.move(cur, sel, fx(c.position), fy(c.position))
                                    local = cur
                                    ctx.controller.curveDrag(ctx.key, layout.writes(ch, cur, previous))
                                }
                                c.consume()
                            }
                        } while (e.changes.any { it.pressed })
                        if (off) {
                            val final = if (added) base else CurveEdit.remove(cur, sel)
                            local = final
                            ctx.controller.curveDrag(ctx.key, layout.writes(ch, final, previous))
                        }
                        ctx.controller.curveEnd(ctx.key)
                        held = -1
                        lastTap.longValue = if (moved || off) 0L else up
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = insetDp.toPx()
                val pw = size.width - 2 * inset
                val ph = size.height - 2 * inset
                val dim = if (mayEdit) 1f else 0.4f
                histogramBars(hist, inset, inset, pw, ph, look.histogram)
                val gl = look.gridLine.toPx()
                for (i in 0..4) {
                    val gx = inset + pw * i / 4
                    val gy = inset + ph * i / 4
                    drawRect(look.grid, Offset(gx - gl / 2, inset), Size(gl, ph))
                    drawRect(look.grid, Offset(inset, gy - gl / 2), Size(pw, gl))
                }
                // the identity, as desktop's diagonal
                segment(inset, inset + ph, inset + pw, inset, gl, look.grid)
                val steps = 96
                var px = inset
                var py = inset + ph - eval(nodes, type, 0.0).toFloat() * ph
                val lw = look.curveLine.toPx()
                for (s in 1..steps) {
                    val x = s.toDouble() / steps
                    val qx = inset + pw * x.toFloat()
                    val qy = inset + ph - eval(nodes, type, x).toFloat() * ph
                    segment(px, py, qx, qy, lw, tint.copy(alpha = dim))
                    px = qx; py = qy
                }
                nodes.forEachIndexed { i, n -> nodeMark(inset + pw * n.x.toFloat(), inset + ph - ph * n.y.toFloat(), look, held == i) }
            }
        }
    }
    if (note != null) ControlNote(note)
}

private fun eval(nodes: List<CurveNode>, type: Int, x: Double) = CurveEdit.eval(nodes, type, x)

// ------------------------------------------------------------------------------------------------ rgb levels

@Composable
private fun LevelsEditor(ctx: PanelContext) {
    val ch = channelOf(ctx)
    val params = ctx.instance.params
    val stored = remember(params, ch) { LevelsEdit.read(params, ch) }
    var local by remember { mutableStateOf<DoubleArray?>(null) }
    var held by remember { mutableIntStateOf(-1) }
    LaunchedEffect(params, ch) { local = null; held = -1 }
    val levels = local ?: stored
    val independent = (params.paramAt("autoscale") as? JsonPrimitive)?.content.let { it == "DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS" || it == "1" }
    val hist = counts(rememberHistogram(ctx), if (independent) ch else null)
    val look = graphLook()
    val tint = if (independent) listOf(DtTheme.colors.curveRed, DtTheme.colors.curveGreen, DtTheme.colors.curveBlue)[ch] else look.line
    val vc = LocalViewConfiguration.current
    val insetDp = DtTheme.space.l
    val touchDp = DtTheme.sizes.touch
    val nowLevels by rememberUpdatedState(levels)
    val lastTap = remember { mutableLongStateOf(0L) }
    val editable = !ctx.readOnly
    val darkFill = look.nodeFill
    val greyFill = DtTheme.colors.sliderFill

    Box(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs)) {
        Box(
            Modifier.fillMaxWidth().height(touchDp * 3).background(look.bg)
                .semantics { contentDescription = "levels graph"; stateDescription = "black ${fmt(levels[0])}, grey ${fmt(levels[1])}, white ${fmt(levels[2])}" }
                .pointerInput(ch, editable) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val inset = insetDp.toPx()
                        val pw = (size.width - 2 * inset).coerceAtLeast(1f)
                        val p0 = down.position
                        if (!editable) {
                            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        down.consume()
                        if (lastTap.longValue > 0L && down.uptimeMillis - lastTap.longValue <= vc.doubleTapTimeoutMillis) {
                            lastTap.longValue = 0L
                            ctx.controller.curveCommit(ctx.key, LevelsEdit.writes(ch, doubleArrayOf(0.0, 0.5, 1.0)))
                            do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        val start = nowLevels
                        val pct = LevelsEdit.pct(start)
                        val handle = LevelsEdit.nearest(start, ((p0.x - inset) / pw).toDouble())
                        held = handle
                        var moved = false
                        var up = down.uptimeMillis
                        do {
                            val e = awaitPointerEvent()
                            up = e.changes.maxOf { it.uptimeMillis }
                            val c = e.changes.firstOrNull { it.id == down.id }
                            if (c != null && c.pressed) {
                                if ((c.position - p0).getDistance() > vc.touchSlop) moved = true
                                if (moved) {
                                    val next = LevelsEdit.move(start, handle, start[handle] + ((c.position.x - p0.x) / pw).toDouble(), pct)
                                    local = next
                                    ctx.controller.curveDrag(ctx.key, LevelsEdit.writes(ch, next))
                                }
                                c.consume()
                            }
                        } while (e.changes.any { it.pressed })
                        ctx.controller.curveEnd(ctx.key)
                        held = -1
                        lastTap.longValue = if (moved) 0L else up
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = insetDp.toPx()
                val pw = size.width - 2 * inset
                val rowH = touchDp.toPx()
                val ph = size.height - rowH - inset
                histogramBars(hist, inset, inset, pw, ph, look.histogram)
                val lw = look.curveLine.toPx()
                for (k in 0..2) {
                    val x = inset + pw * levels[k].toFloat()
                    drawRect(if (k == 1) look.grid else tint, Offset(x - lw / 2, inset), Size(lw, ph))
                    nodeMarkFilled(x, inset + ph + rowH / 2, look, if (k == 0) darkFill else if (k == 1) greyFill else look.node, held == k)
                }
            }
        }
    }
}

private fun fmt(v: Double) = "%.2f".format(v)

/** A handle: a diamond filled with [fill] (black, grey or white), edged light. */
private fun DrawScope.nodeMarkFilled(cx: Float, cy: Float, look: GraphLook, fill: Color, held: Boolean) {
    val s = look.nodeSize.toPx() * 1.5f
    val line = look.curveLine.toPx()
    rotate(45f, Offset(cx, cy)) {
        drawRect(fill, Offset(cx - s / 2, cy - s / 2), Size(s, s))
        drawRect(if (held) look.line else look.node, Offset(cx - s / 2, cy - s / 2), Size(s, s), style = Stroke(line))
    }
}

// ------------------------------------------------------------------------------------------------ tone equalizer

@Composable
private fun ToneEqEditor(ctx: PanelContext) {
    val params = ctx.instance.params
    val stored = remember(params) { ToneEqMath.read(params) }
    var local by remember { mutableStateOf<DoubleArray?>(null) }
    var active by remember { mutableIntStateOf(-1) }
    LaunchedEffect(params) { local = null }
    val gains = local ?: stored
    val sigma = ToneEqMath.sigmaOf(params)
    val look = graphLook()
    val vc = LocalViewConfiguration.current
    val insetDp = DtTheme.space.l
    val nowGains by rememberUpdatedState(gains)
    val lastTap = remember { mutableLongStateOf(0L) }
    val editable = !ctx.readOnly
    val curve = remember(gains.toList(), sigma) { ToneEqMath.curve(gains, sigma, 64) }

    Box(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(EQ_ASPECT).background(look.bg)
                .semantics { contentDescription = "tone equalizer graph" }
                .pointerInput(editable, sigma) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val inset = insetDp.toPx()
                        val ph = (size.height - 2 * inset).coerceAtLeast(1f)
                        val p0 = down.position
                        if (!editable) {
                            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        down.consume()
                        if (lastTap.longValue > 0L && down.uptimeMillis - lastTap.longValue <= vc.doubleTapTimeoutMillis) {
                            lastTap.longValue = 0L
                            ctx.controller.curveCommit(ctx.key, ToneEqMath.writes(DoubleArray(ToneEqMath.CHANNELS)))
                            do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        // the band under the finger: the exposure the drag works on (desktop uses the finger's own x)
                        val band = (p0.x / size.width * ToneEqMath.CHANNELS).toInt().coerceIn(0, ToneEqMath.CHANNELS - 1)
                        active = band
                        var g = nowGains
                        var lastY = p0.y
                        var moved = false
                        var up = down.uptimeMillis
                        do {
                            val e = awaitPointerEvent()
                            up = e.changes.maxOf { it.uptimeMillis }
                            val c = e.changes.firstOrNull { it.id == down.id }
                            if (c != null && c.pressed) {
                                if (!moved && (c.position - p0).getDistance() > vc.touchSlop) moved = true
                                if (moved) {
                                    val offset = -((c.position.y - lastY) / ph).toDouble() * ToneEqMath.SPAN_EV
                                    lastY = c.position.y
                                    val next = ToneEqMath.interactive(g, (band - 8).toDouble(), offset, sigma)
                                    if (next != null) {
                                        g = next
                                        local = next
                                        ctx.controller.curveDrag(ctx.key, ToneEqMath.writes(next))
                                    }
                                }
                                c.consume()
                            }
                        } while (e.changes.any { it.pressed })
                        ctx.controller.curveEnd(ctx.key)
                        lastTap.longValue = if (moved) 0L else up
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = insetDp.toPx()
                val ph = size.height - 2 * inset
                val colW = size.width / ToneEqMath.CHANNELS
                val gl = look.gridLine.toPx()
                fun yOf(ev: Double) = inset + ph / 2 - (ev / ToneEqMath.SPAN_EV * ph).toFloat()
                for (i in -2..2) drawRect(if (i == 0) look.histogram else look.grid, Offset(0f, yOf(i.toDouble()) - gl / 2), Size(size.width, gl))
                for (i in 0 until ToneEqMath.CHANNELS) drawRect(look.grid, Offset(colW * (i + 0.5f) - gl / 2, inset), Size(gl, ph))
                // the curve between the first and the last band, flat out to the edges
                val lw = look.curveLine.toPx()
                val x0 = colW * 0.5f
                val x1 = colW * (ToneEqMath.CHANNELS - 0.5f)
                segment(0f, yOf(curve.first().toDouble()), x0, yOf(curve.first().toDouble()), lw, look.line)
                for (s in 1 until curve.size) {
                    val ax = x0 + (x1 - x0) * (s - 1) / (curve.size - 1)
                    val bx = x0 + (x1 - x0) * s / (curve.size - 1)
                    segment(ax, yOf(curve[s - 1]), bx, yOf(curve[s]), lw, look.line)
                }
                segment(x1, yOf(curve.last()), size.width, yOf(curve.last()), lw, look.line)
                for (i in 0 until ToneEqMath.CHANNELS) nodeMark(colW * (i + 0.5f), yOf(gains[i]), look, active == i)
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        for (i in 0 until ToneEqMath.CHANNELS) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                DtText(if (i == ToneEqMath.CHANNELS - 1) "0" else "${i - 8}", DtTheme.type.tiny, if (i == active) DtTheme.colors.textBright else DtTheme.colors.textMuted)
            }
        }
    }
    val shown = if (active >= 0) "${if (active == 8) "0" else (active - 8).toString()} EV: ${"%+.2f".format(gains[active])} EV" else "drag a point up or down; +2 EV at the top, -2 EV at the bottom"
    DtText(shown, DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(vertical = DtTheme.space.xs), maxLines = 2)
}

private const val EQ_ASPECT = 2.1f

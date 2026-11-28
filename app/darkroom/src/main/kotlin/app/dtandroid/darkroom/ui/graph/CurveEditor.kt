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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        "tonecurve", "rgbcurve", "basecurve", "satcurvergb" -> CurveEditor(ctx)
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
private fun channelOf(ctx: PanelContext, channels: Int = 3): Int {
    if (channels <= 1) return 0
    val tabs = ctx.module.allControls().filterIsInstance<Control.Tabs>().firstOrNull { it.pages.size == channels } ?: return 0
    val shown = ctx.module.pathTo(tabs.id).all { Rules.isVisible(ctx.module, it, ctx.inputs) }
    return if (shown) ctx.controller.tabPage(ctx.state, ctx.key, tabs.id).coerceIn(0, channels - 1) else 0
}

/** The histogram of the picture, read again whenever the module's parameters change (the engine's, behind the module: engine-api.md 6.4). */
@Composable
private fun rememberHistogram(ctx: PanelContext): Histogram? {
    var h by remember { mutableStateOf(ctx.controller.histogramCache) }
    LaunchedEffect(ctx.key, ctx.instance.params) {
        // on its own clock: the controller waits for a quiet moment, whatever drives the composition's time
        val got = withContext(Dispatchers.Default) { ctx.controller.histogramNow() }
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

// ------------------------------------------------------------------------------------------------ tone curve, rgb curve, base curve, saturation curve

@Composable
private fun CurveEditor(ctx: PanelContext) {
    val layout = CurveLayout.of(ctx.instance.op)
    if (layout != null) CurveEditorBody(layout, ctx)
}

/** Brief P6f: desktop's "scale for graph" slider of tone curve and base curve: display only, nothing is written. */
@Composable
fun GraphScaleSlider(c: Control.Slider, ctx: PanelContext) {
    val v = GraphViews.logBase[ctx.key] ?: c.default
    app.dtandroid.darkroom.ui.SliderControl(
        label = c.label ?: "", slider = c, value = v, enabled = true,
        onDrag = { GraphViews.logBase[ctx.key] = it.coerceIn(CurveScale.LOG_MIN, CurveScale.LOG_MAX) },
        onDragEnd = {},
        onReset = { GraphViews.logBase[ctx.key] = c.default },
        onExact = {},
    )
}

@Composable
private fun CurveEditorBody(layout: CurveLayout, ctx: PanelContext) {
    val ch = channelOf(ctx, layout.channels)
    val params = ctx.instance.params
    val op = layout.op
    val rgb = op == "rgbcurve"
    val lab = op == "tonecurve"
    // desktop edits a and b only with independent channels (tonecurve.c: autoscale_ab MANUAL); the rgb curve's tabs only exist then
    val independent = when {
        rgb -> (params.paramAt("curve_autoscale") as? JsonPrimitive)?.content.let { it == "DT_S_SCALE_MANUAL_RGB" || it == "1" }
        lab -> (params.paramAt("tonecurve_autoscale_ab") as? JsonPrimitive)?.content.let { it == "DT_S_SCALE_MANUAL" || it == "0" }
        else -> true
    }
    val editable = !ctx.readOnly && (ch == 0 || independent)
    val stored = remember(params, ch) { layout.read(params, ch) }
    val type = layout.readType(params, ch)
    // the nodes while a finger moves them (the gesture writes these; the engine's own come back when the finger lifts)
    var local by remember { mutableStateOf<List<CurveNode>?>(null) }
    var held by remember { mutableIntStateOf(-1) }
    LaunchedEffect(params, ch) { local = null; held = -1 }
    val nodes = local ?: stored
    // brief P6f: the display scale ("scale for graph", rgb curve's pinch zoom)
    val view = GraphViews.view(ctx.key, ch)
    // saturation curve: desktop draws both curves, the other one fainter (satcurvergb.c `area_draw`)
    val others = if (op == "satcurvergb") (0 until layout.channels).filter { it != ch }.map { o -> layout.read(params, o) to layout.readType(params, o) } else emptyList()
    val look = graphLook()
    val satColours = listOf(look.line, DtTheme.colors.curveSecond)
    val tint = when {
        rgb && independent -> listOf(DtTheme.colors.curveRed, DtTheme.colors.curveGreen, DtTheme.colors.curveBlue)[ch]
        op == "satcurvergb" -> satColours[ch.coerceIn(0, 1)]
        else -> look.line
    }
    val otherTint = if (op == "satcurvergb") satColours[(1 - ch).coerceIn(0, 1)] else look.line
    // tone curve and rgb curve draw the picture's histogram behind (base curve and saturation curve do not: see the P6f report)
    val hist = if (lab || rgb) counts(rememberHistogram(ctx), if (rgb && independent) ch else null) else null
    // the picker armed on this module: what it took, on the graph (desktop: band, line at the mean, "in → out")
    val pickState = ctx.state.picker?.takeIf { it.key == ctx.key }
    val pick = remember(pickState?.moduleInput, pickState?.moduleOutput, pickState?.saturation, params, ch, view) {
        pickState?.let {
            // brief P6f: saturation curve, the picked saturation (satcurvergb.c _draw_sat_picker: band min..max, line at the mean, no text)
            val sat = it.saturation
            if (op == "satcurvergb") sat?.let { v -> CurvePickMark(v[1].coerceIn(0.0, 1.0), v[0].coerceIn(0.0, 1.0), v[2].coerceIn(0.0, 1.0), "") }
            else CurvePick.mark(op, if (rgb && !independent) 0 else ch, params, it.moduleInput, it.moduleOutput, view)
        }
    }
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val pickTextStyle = DtTheme.type.small.copy(color = look.line)
    val pickBand = DtTheme.colors.curvePickBand
    val pickLine = DtTheme.colors.curvePickLine
    val satLow = DtTheme.colors.curveSatLow
    val satHigh = DtTheme.colors.curveSatHigh
    val vc = LocalViewConfiguration.current
    val insetDp = DtTheme.space.l
    val touchDp = DtTheme.sizes.touch
    val stripDp = DtTheme.space.s
    val nowNodes by rememberUpdatedState(nodes)
    val nowParams by rememberUpdatedState(params)
    val nowView by rememberUpdatedState(view)
    val nowType by rememberUpdatedState(type)
    val lastTap = remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val mayEdit = editable
    val note = if (!mayEdit && !ctx.readOnly) "this curve has no effect with linked channels; double tap the graph to edit it" else null
    // saturation curve: the gradient strip under the graph (desktop: white at no saturation, purple at full)
    val strip = op == "satcurvergb"

    Box(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = graphSide()).fillMaxWidth().aspectRatio(1f).background(look.bg)
                .semantics { contentDescription = "curve graph"; stateDescription = "${nodes.size} nodes" + if (view.zoom > 1.0) ", zoom ${"%.1f".format(view.zoom)}" else "" }
                .pointerInput(ch, mayEdit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val inset = insetDp.toPx()
                        val pw = (w - 2 * inset).coerceAtLeast(1f)
                        val ph = (h - 2 * inset - (if (strip) stripDp.toPx() * 2 else 0f)).coerceAtLeast(1f)
                        val p0 = down.position
                        val base = nowNodes
                        val v = nowView
                        val previous = layout.countOf(nowParams, ch)
                        // a second tap soon after the first: desktop's double click, the channel's curve back to its default
                        if (lastTap.longValue > 0L && down.uptimeMillis - lastTap.longValue <= vc.doubleTapTimeoutMillis) {
                            lastTap.longValue = 0L
                            down.consume()
                            if (mayEdit) {
                                ctx.controller.curveCommit(ctx.key, layout.resetWrites(ch, previous))
                            } else if (!ctx.readOnly && lab && ch > 0) {
                                ctx.controller.launch { setParam(ctx.key, "tonecurve_autoscale_ab", JsonPrimitive("DT_S_SCALE_MANUAL")) }
                            }
                            do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        if (!mayEdit && !rgb) {
                            var up = down.uptimeMillis
                            do { val e = awaitPointerEvent(); up = e.changes.maxOf { it.uptimeMillis } } while (e.changes.any { it.pressed })
                            lastTap.longValue = up
                            return@awaitEachGesture
                        }
                        down.consume()
                        // screen fractions (y up) and back to the curve's values through the display scale
                        fun sx(p: Offset) = ((p.x - inset) / pw).toDouble()
                        fun sy(p: Offset) = (1f - (p.y - inset) / ph).toDouble()
                        fun fx(p: Offset) = v.valueX(sx(p).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
                        fun fy(p: Offset) = v.valueY(sy(p).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
                        val reach = touchDp.toPx() / 2
                        val onScreen = base.map { CurveNode(v.screenX(it.x), v.screenY(it.y)) }
                        var sel = if (mayEdit) CurveEdit.hit(onScreen, sx(p0), sy(p0), (reach / pw).toDouble(), (reach / ph).toDouble()) else -1
                        var cur = base
                        var added = false
                        var moved = false
                        var pinched = false
                        var up = down.uptimeMillis
                        var longPress: Job? = null
                        var longPressed = false
                        // empty space: a node will be added there when the finger moves or lifts (a second finger first: a pinch)
                        val add = if (sel < 0 && mayEdit) layout.add(base, fx(p0), fy(p0), nowType) else null
                        if (sel >= 0) {
                            // on a node: a long press removes it (a timer, so that it fires with no event), a drag moves it
                            val hitNode = sel
                            longPress = scope.launch {
                                delay(vc.longPressTimeoutMillis)
                                longPressed = true
                                ctx.controller.curveCommit(ctx.key, layout.writes(ch, layout.remove(base, hitNode), previous))
                            }
                            held = sel
                        }
                        var off = false
                        var pinchFrom: Pair<Offset, Float>? = null
                        do {
                            val e = awaitPointerEvent()
                            up = e.changes.maxOf { it.uptimeMillis }
                            val pressed = e.changes.filter { it.pressed }
                            // brief P6f: rgb curve, two fingers before any edit: desktop's pan and zoom of the graph
                            if (rgb && !moved && !added && !longPressed && pressed.size >= 2) {
                                longPress?.cancel()
                                pinched = true
                                held = -1
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val centre = (a + b) / 2f
                                val span = (a - b).getDistance().coerceAtLeast(1f)
                                val last = pinchFrom
                                if (last != null) {
                                    val nv = CurveScale.pinch(GraphViews.view(ctx.key, ch), sx(last.first), sy(last.first), sx(centre), sy(centre), (span / last.second).toDouble())
                                    GraphViews.zoom[ctx.key] = nv
                                }
                                pinchFrom = centre to span
                                e.changes.forEach { it.consume() }
                                continue
                            }
                            if (pinched) { e.changes.forEach { it.consume() }; continue }
                            val c = e.changes.firstOrNull { it.id == down.id }
                            if (c != null && c.pressed && !longPressed && mayEdit) {
                                if (!moved && (c.position - p0).getDistance() > vc.touchSlop) {
                                    moved = true
                                    longPress?.cancel()
                                    if (sel < 0 && add != null) {
                                        cur = add.first; sel = add.second; added = true; held = sel
                                    }
                                }
                                // a node dragged off the graph is dropped there
                                off = c.position.x < -inset || c.position.x > w + inset || c.position.y < -inset || c.position.y > h + inset
                                if (!off && moved && sel >= 0) {
                                    cur = layout.move(cur, sel, fx(c.position), fy(c.position))
                                    local = cur
                                    ctx.controller.curveDrag(ctx.key, layout.writes(ch, cur, previous))
                                }
                                c.consume()
                            } else if (c != null && c.pressed) c.consume()
                        } while (e.changes.any { it.pressed })
                        longPress?.cancel()
                        held = -1
                        if (pinched) { lastTap.longValue = 0L; return@awaitEachGesture }
                        if (longPressed) return@awaitEachGesture // the node was removed by the long press
                        if (!mayEdit) { lastTap.longValue = up; return@awaitEachGesture }
                        if (!moved && sel < 0 && add != null) {
                            // a tap on empty space: the node is added there (one history item)
                            local = add.first
                            ctx.controller.curveCommit(ctx.key, layout.writes(ch, add.first, previous))
                            lastTap.longValue = up
                            return@awaitEachGesture
                        }
                        if (off && moved) {
                            val final = if (added) base else layout.remove(cur, sel)
                            local = final
                            ctx.controller.curveDrag(ctx.key, layout.writes(ch, final, previous))
                        }
                        ctx.controller.curveEnd(ctx.key)
                        lastTap.longValue = if (moved || off) 0L else up
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = insetDp.toPx()
                val pw = size.width - 2 * inset
                val stripH = if (strip) stripDp.toPx() else 0f
                val ph = size.height - 2 * inset - stripH * 2
                val dim = if (mayEdit) 1f else 0.4f
                fun px(s: Double) = inset + pw * s.toFloat()
                fun py(s: Double) = inset + ph - ph * s.toFloat()
                clipRect(inset, inset, inset + pw, inset + ph) {
                    if (view.zoom == 1.0) histogramBars(hist, inset, inset, pw, ph, look.histogram)
                    val gl = look.gridLine.toPx()
                    // the grid: quarters of the values, through the display scale (gui/draw.h dt_draw_loglog_grid, dt_draw_grid_zoomed)
                    for (i in 0..4) {
                        val gx = px(view.screenX(i / 4.0))
                        val gy = py(view.screenY(i / 4.0))
                        drawRect(look.grid, Offset(gx - gl / 2, inset), Size(gl, ph))
                        drawRect(look.grid, Offset(inset, gy - gl / 2), Size(pw, gl))
                    }
                    // the identity, as desktop's diagonal (a satcurve's neutral line is the middle)
                    if (op == "satcurvergb") drawRect(look.grid, Offset(inset, py(0.5) - gl / 2), Size(pw, gl))
                    else segment(px(view.screenX(0.0)), py(view.screenY(0.0)), px(view.screenX(1.0)), py(view.screenY(1.0)), gl, look.grid)
                    val lw = look.curveLine.toPx()
                    fun curveLine(ns: List<CurveNode>, t: Int, color: Color, width: Float) {
                        val steps = 128
                        var qx0 = 0f
                        var qy0 = 0f
                        for (k in 0..steps) {
                            val s = k.toDouble() / steps
                            val qx = px(s)
                            val qy = py(view.screenY(eval(ns, t, view.valueX(s).coerceIn(0.0, 1.0))))
                            if (k > 0) segment(qx0, qy0, qx, qy, width, color)
                            qx0 = qx; qy0 = qy
                        }
                    }
                    for ((ns, t) in others) curveLine(ns, t, otherTint.copy(alpha = 0.5f), lw * 0.6f)
                    curveLine(nodes, type, tint.copy(alpha = dim), lw)
                    if (pick != null) {
                        val x0 = px(pick.min)
                        val x1 = px(pick.max)
                        if (x1 > x0) drawRect(pickBand, Offset(x0, inset), Size(x1 - x0, ph))
                        val xm = px(pick.mean)
                        drawRect(pickLine, Offset(xm - lw / 2, inset), Size(lw, ph))
                        // the picked value as a point on the curve
                        val ym = py(view.screenY(eval(nodes, type, view.valueX(pick.mean).coerceIn(0.0, 1.0))).coerceIn(0.0, 1.0))
                        if (op != "satcurvergb") nodeMark(xm, ym, look, true)
                        if (pick.text.isNotEmpty()) drawText(textMeasurer, pick.text, Offset(inset + pw * 0.02f, inset + ph * 0.02f), pickTextStyle)
                    }
                    nodes.forEachIndexed { i, n -> nodeMark(px(view.screenX(n.x)), py(view.screenY(n.y)), look, held == i) }
                    // base curve: the held node's "x / y ( ±difference)" in percent (basecurve.c dt_iop_basecurve_draw)
                    val hn = nodes.getOrNull(held)
                    if (op == "basecurve" && hn != null) {
                        val t = "%.2f / %.2f ( %+.2f)".format(hn.x * 100, hn.y * 100, (hn.y - hn.x) * 100)
                        val m = textMeasurer.measure(t, pickTextStyle)
                        drawText(m, topLeft = Offset(inset + pw * 0.98f - m.size.width, inset + ph * 0.98f - m.size.height))
                    }
                }
                if (strip) {
                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(satLow, satHigh), inset, inset + pw),
                        Offset(inset, inset + ph + stripH), Size(pw, stripH))
                }
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

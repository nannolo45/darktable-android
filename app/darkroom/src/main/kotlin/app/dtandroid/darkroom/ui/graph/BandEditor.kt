// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.ui.DtTabRow
import app.dtandroid.darkroom.ui.DtText
import app.dtandroid.darkroom.ui.PanelContext
import app.dtandroid.darkroom.ui.SliderControl
import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.schema.ModuleSchema
import app.dtandroid.ui.theme.BandBrushCircle
import app.dtandroid.ui.theme.BandDraw
import app.dtandroid.ui.theme.BandFrame
import app.dtandroid.ui.theme.BandLine
import app.dtandroid.ui.theme.BandMark
import app.dtandroid.ui.theme.BandNodes
import app.dtandroid.ui.theme.DtBand
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.HueBand
import kotlinx.serialization.json.JsonPrimitive

/**
 * Brief P3e-b: where the band editor takes the place of desktop's graph, and which generic controls it replaces. The
 * module's own notebook (its tabs) and colour zones' "select by", "edit by area" and "interpolation method" belong
 * to the editor; the sliders, the process mode and the mix stay where the schema puts them.
 */
object BandHost {
    private val ZONES_OWN = setOf("select by", "edit by area", "interpolation method")

    fun isBandModule(op: String) = op in BandModels.OPS

    /** The graph control the editor stands in for (the first graph of the panel), or null */
    fun graphId(m: ModuleSchema): Int? =
        if (!isBandModule(m.op)) null else m.allControls().firstOrNull { it is Control.Unsupported && it.kind == "graph" }?.id

    /** The notebook that holds the module's curve tabs (its page is the controller's, as the pickers read it) */
    fun tabsId(m: ModuleSchema): Int? = m.allControls().firstOrNull { it is Control.Tabs }?.id

    /** Brief P6f: denoise (profiled) has two notebooks, all/R/G/B and luma/chroma; the colour mode in [params] says which one shows. */
    fun tabsId(m: ModuleSchema, params: kotlinx.serialization.json.JsonObject): Int? {
        val tabs = m.allControls().filterIsInstance<Control.Tabs>()
        return if (m.op == "denoiseprofile" && BandModels.denoiseY0U0V0(params)) tabs.getOrNull(1)?.id else tabs.firstOrNull()?.id
    }

    /** True for a control the editor replaces: it is not built as a generic control. */
    fun hides(m: ModuleSchema, c: Control): Boolean =
        isBandModule(m.op) && (c is Control.Tabs || (m.op == "colorzones" && c.label?.trim() in ZONES_OWN))

    /** Colour equalizer's slider pages follow the editor's tab (the schema's visibility rules show the first page only). */
    fun pagesFollowTabs(op: String) = op == "colorequal"
}

/** The words of colour zones' interpolation list (dt_bauhaus_combobox_new_interpolation). */
private val INTERPOLATION = listOf("cubic spline", "centripetal spline", "monotonic spline")
private val SELECT_BY = listOf("lightness", "chroma", "hue")

/** Where a touch is on the graph, and back: the graph sits inside a margin, with the strip under it. */
internal class BandGeometry(val width: Float, val height: Float, val inset: Float, val strip: Float) {
    val gw get() = (width - 2 * inset).coerceAtLeast(1f)
    val gh get() = (height - 2 * inset - strip).coerceAtLeast(1f)
    fun x(px: Float) = ((px - inset) / gw).toDouble().coerceIn(0.0, 1.0)
    fun y(py: Float) = (1.0 - (py - inset) / gh).coerceIn(0.0, 1.0)
    fun inStrip(py: Float) = strip > 0f && py - inset > gh
}

/** The editor's state that the touch handler reads: set again each time the editor is composed. */
private class BandLive {
    var view: BandView? = null
    var tabIndex = 0
    var tab: BandTab? = null
    var radius = 0.1
    var brush = true
    var hitPx = 1f
    var inset = 0f
    var stripPx = 0f
    var lastTapAt = 0L
}

/**
 * The band editor: tabs for the module's curves, the graph, the strip under it, the brush radius, and (colour zones)
 * its three small lists. A drag is one history item; a double tap resets the tab's curve.
 */
@Composable
fun BandEditor(ctx: PanelContext) {
    val op = ctx.module.op
    val params = ctx.instance.params
    val view = remember(op, params) { BandModels.build(op, params) }
    if (view == null) {
        DtText("The editor for this module's curves could not read its parameters.", DtTheme.type.label, DtTheme.colors.textMuted, Modifier.padding(vertical = DtTheme.space.s), maxLines = 3)
    } else {
        BandEditorBody(ctx, view)
    }
}

@Composable
private fun BandEditorBody(ctx: PanelContext, view: BandView) {
    val op = ctx.module.op
    val params = ctx.instance.params
    val tabsId = BandHost.tabsId(ctx.module, params) ?: -1
    val tabIndex = ctx.controller.tabPage(ctx.state, ctx.key, tabsId).coerceIn(0, view.tabs.size - 1)
    // the tab as the finger has it, until the engine's parameters say the same
    var live by remember(ctx.key) { mutableStateOf<Pair<Int, BandTab>?>(null) }
    LaunchedEffect(params) { live = null }
    val tab = live?.takeIf { it.first == tabIndex }?.second ?: view.tabs[tabIndex]
    var radius by remember(ctx.key) { mutableStateOf(view.defaultRadius) }
    var byArea by remember(ctx.key) { mutableStateOf(false) }
    var selected by remember(ctx.key) { mutableStateOf(-1 to -1) } // curve (-2: the strip), node
    var brushAt by remember(ctx.key) { mutableStateOf<Pair<Double, Double>?>(null) }
    val holder = remember(ctx.key) { BandLive() }
    val band = DtTheme.band
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = with(density) { (band.node * 2f).toPx() }
    val stripPx = with(density) { (if (view.xStrip) band.strip else band.axis).toPx() }
    val graphStrip = if (view.xStrip) stripPx else 0f
    val hitPx = with(density) { band.nodeHit.toPx() }
    val brushOn = view.brushAlways || byArea
    holder.view = view; holder.tabIndex = tabIndex; holder.tab = tab; holder.radius = radius; holder.brush = brushOn
    holder.hitPx = hitPx; holder.inset = inset; holder.stripPx = graphStrip

    Column(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs)) {
        DtTabRow(view.tabs, view.tabs[tabIndex], { t -> ctx.controller.chooseTab(ctx.key, tabsId, view.tabs.indexOf(t)); live = null }, DtTheme.sizes.touch) { t, sel ->
            DtText(t.label, DtTheme.type.label, if (sel) DtTheme.colors.textBright else DtTheme.colors.textDim)
        }
        val marks = pickMarks(ctx, view)
        val tints = listOf(null, DtTheme.colors.curveRed, DtTheme.colors.curveGreen, DtTheme.colors.curveBlue, DtTheme.colors.curveSecond)
        val frame = frameOf(view, tab, selected, if (brushOn) brushAt else null, radius, marks, tints)
        Box(
            Modifier.fillMaxWidth().height(if (view.xStrip) band.height + band.strip else band.height)
                .semantics { contentDescription = "band graph: ${tab.label}" }
                .drawBehind { BandDraw.draw(this, band, frame, inset, graphStrip) }
                .then(if (!ctx.readOnly) Modifier.pointerInput(ctx.key, view.op) {
                    awaitEachGesture {
                        bandGesture(holder, ctx.key, ctx,
                            onLive = { t -> live = holder.tabIndex to t },
                            onSelect = { c, n -> selected = c to n },
                            onBrush = { b -> brushAt = b })
                    }
                } else Modifier),
        ) {}
        // the strip under a hue, lightness or chroma axis: the colour of the position (data, from ui-theme)
        if (!view.xStrip && view.axis != BandAxis.FREQUENCY) AxisStrip(ctx, view, tab, inset)
        AxisWords(view)
        Column(Modifier.padding(top = DtTheme.space.xs)) {
            val slider = remember(view.defaultRadius, view.radiusMin) { radiusSlider(view.defaultRadius, view.radiusMin) }
            SliderControl("brush radius", slider, radius, !ctx.readOnly, onDrag = { radius = it }, onDragEnd = {}, onReset = { radius = view.defaultRadius }, onExact = {})
        }
        if (op == "colorzones") ZonesOptions(ctx, tab, tabIndex, byArea) { byArea = it }
    }
}

private fun radiusSlider(default: Double, min: Double = BandModels.RADIUS_MIN) = Control.Slider(
    id = -1, label = "brush radius", tooltip = "how far a moved node pulls its neighbours: the soft brush", hiddenByDefault = false, binding = Binding.None,
    softMin = min, softMax = BandModels.RADIUS_MAX, hardMin = min, hardMax = BandModels.RADIUS_MAX, default = default,
    step = 0.01, digits = 0, factor = 100.0, offset = 0.0, format = "%", textDefault = null, integer = false,
)

/** colour zones' own lists: "edit by area", "select by" and the interpolation of the shown curve */
@Composable
private fun ZonesOptions(ctx: PanelContext, tab: BandTab, tabIndex: Int, byArea: Boolean, onByArea: (Boolean) -> Unit) {
    val c = DtTheme.colors
    val channel = BandModels.zonesChannel(ctx.instance.params)
    val enabled = !ctx.readOnly
    Row(Modifier.fillMaxWidth().height(DtTheme.sizes.touch), verticalAlignment = Alignment.CenterVertically) {
        DtText("edit by area", DtTheme.type.label, if (enabled) c.text else c.textDisabled, Modifier.weight(1f))
        DtTabRow(listOf(false, true), byArea, { if (enabled) onByArea(it) }, DtTheme.sizes.touch, Modifier.weight(1f)) { v, sel ->
            DtText(if (v) "on" else "off", DtTheme.type.label, if (sel) c.textBright else c.textDim)
        }
    }
    Row(Modifier.fillMaxWidth().height(DtTheme.sizes.touch), verticalAlignment = Alignment.CenterVertically) {
        DtText("select by", DtTheme.type.label, if (enabled) c.text else c.textDisabled, Modifier.weight(1f))
        // desktop: changing it puts every curve, the mix and the process mode back to their defaults
        DtTabRow(listOf(0, 1, 2), channel, { v ->
            if (enabled && v != channel) ctx.controller.launch { bandApply(ctx.key, BandModels.zonesSelectWrites(v, ctx.instance.params)) }
        }, DtTheme.sizes.touch, Modifier.weight(2f)) { v, sel ->
            DtText(SELECT_BY[v], DtTheme.type.label, if (sel) c.textBright else c.textDim)
        }
    }
    val curve = tab.curves[0]
    Row(Modifier.fillMaxWidth().height(DtTheme.sizes.touch), verticalAlignment = Alignment.CenterVertically) {
        DtText("interpolation method", DtTheme.type.label, if (enabled) c.text else c.textDisabled, Modifier.weight(1f), maxLines = 2)
        DtTabRow(listOf(0, 1, 2), curve.type, { v ->
            if (enabled && v != curve.type) ctx.controller.launch { bandApply(ctx.key, listOf("curve_type[$tabIndex]" to JsonPrimitive(v.toLong()))) }
        }, DtTheme.sizes.touch, Modifier.weight(2f)) { v, sel ->
            DtText(INTERPOLATION[v].substringBefore(' '), DtTheme.type.label, if (sel) c.textBright else c.textDim)
        }
    }
}

@Composable
private fun AxisStrip(ctx: PanelContext, view: BandView, tab: BandTab, inset: Float) {
    val band = DtTheme.band
    Box(
        Modifier.fillMaxWidth().height(band.axis)
            .drawBehind {
                val w = size.width - 2 * inset
                val brush = when (view.axis) {
                    BandAxis.HUE -> HueBand.hueBrush(w, band.axisAlpha)
                    BandAxis.HUE_NODES -> equalizerBand(ctx, tab, w, band)
                    else -> HueBand.rampBrush(band, w)
                }
                if (brush != null) BandDraw.drawAxis(this, brush, inset, size.height)
            },
    ) {}
}

/** Colour equalizer's strip: the colours the schema's saturation sliders end in, at the nodes' positions. */
private fun equalizerBand(ctx: PanelContext, tab: BandTab, widthPx: Float, band: DtBand): androidx.compose.ui.graphics.Brush? {
    val xs = tab.curves[0].xs
    val colours = BandModels.EQUAL_NODE_NAMES.mapIndexed { k, name ->
        val s = ctx.module.allControls().firstOrNull { it is Control.Slider && (it.binding as? Binding.Param)?.path == "sat_$name" } as? Control.Slider
        val stop = s?.gradient?.maxByOrNull { it.position }
        stop?.let { doubleArrayOf(it.red, it.green, it.blue) } ?: return null
    }
    return HueBand.stopsBrush(xs, colours, widthPx, band.axisAlpha)
}

@Composable
private fun AxisWords(view: BandView) {
    // brief P6f: the y axis's words where desktop writes them in the graph (lowlight: night / day vision; denoise: noisy / smooth)
    view.yWords?.let { (bottom, top) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            DtText("$bottom (bottom) to $top (top)", DtTheme.type.tiny, DtTheme.colors.textMuted)
        }
    }
    // raw denoise's graph has no words on desktop
    if (view.op == "rawdenoise") return
    val words = view.xWords ?: when (view.axis) {
        BandAxis.FREQUENCY -> "coarse" to "fine"
        BandAxis.LIGHTNESS -> "dark" to "light"
        BandAxis.CHROMA -> "dull" to "bold"
        else -> null
    } ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DtText(words.first, DtTheme.type.tiny, DtTheme.colors.textMuted)
        DtText(words.second, DtTheme.type.tiny, DtTheme.colors.textMuted)
    }
}

/** What the graph draws for [tab]: its lines and nodes, the brush while a finger is down, the picked values. */
private fun frameOf(
    view: BandView, tab: BandTab, selected: Pair<Int, Int>, brushAt: Pair<Double, Double>?, radius: Double, marks: List<BandMark>,
    tints: List<androidx.compose.ui.graphics.Color?> = emptyList(),
): BandFrame {
    val own = tints.getOrNull(tab.tint)
    // brief P6f: the other channels' curves faint behind (raw denoise, denoise (profiled))
    val ghosts = tab.ghosts.map { (c, t) -> BandLine(BandInterp.line(c.xs, c.ys, c.type, c.periodic), color = tints.getOrNull(t), faded = true) }
    val lines = ghosts + tab.curves.map { BandLine(BandInterp.line(it.xs, it.ys, it.type, it.periodic), it.second, color = if (it.second) null else own) }
    val nodes = tab.curves.mapIndexed { k, c ->
        BandNodes(FloatArray(c.count) { c.xs[it].toFloat() }, FloatArray(c.count) { c.ys[it].toFloat() }, c.second, if (selected.first == k) selected.second else -1,
            bars = view.op == "colorequal", color = if (c.second) null else own)
    }
    val strip = if (view.xStrip) {
        val c = tab.curves[0]
        FloatArray(c.count - 2) { c.xs[it + 1].toFloat() }
    } else null
    return BandFrame(
        lines = lines, nodes = nodes, gridX = view.gridX, gridY = 4, baseY = 0.5f,
        brush = brushAt?.let { BandBrushCircle(it.first.toFloat(), it.second.toFloat(), radius.toFloat()) },
        marks = marks, stripXs = strip, stripSelected = if (strip != null && selected.first == -2) selected.second - 1 else -1,
    )
}

/** Colour zones: the colour the picker took, marked on the axis of "select by" (colorzones.c `_draw_color_picker`). */
private fun pickMarks(ctx: PanelContext, view: BandView): List<BandMark> {
    if (view.op != "colorzones") return emptyList()
    val p = ctx.state.picker?.takeIf { it.key == ctx.key }?.moduleInput ?: return emptyList()
    return BandModels.pickMarks(view.axis, p).map { BandMark(it.x.toFloat(), it.from?.toFloat(), it.to?.toFloat()) }
}

// ---------------------------------------------------------------------- the touch handler

private enum class Phase { UP, MOVE, LONG, CANCEL }

/**
 * One gesture on the graph. A move past the touch slop starts the edit (the brush, a node, a new node, an x
 * position); an edit is one history item, written when the finger lifts. A touch that does not move is a tap: two
 * taps in a row reset the tab's curve; a long press on a node of colour zones takes the node away (desktop: the
 * right click).
 */
private suspend fun AwaitPointerEventScope.bandGesture(
    h: BandLive, key: InstanceKey, ctx: PanelContext,
    onLive: (BandTab) -> Unit, onSelect: (Int, Int) -> Unit, onBrush: (Pair<Double, Double>?) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    val view = h.view
    val tab = h.tab
    if (view == null || tab == null) return
    val geo = BandGeometry(size.width.toFloat(), size.height.toFloat(), h.inset, h.stripPx)
    try {
        val x0 = geo.x(down.position.x)
        val y0 = geo.y(down.position.y)
        val opts = BandOptions(h.radius, h.brush, h.hitPx / geo.gw.toDouble(), h.hitPx / geo.gh.toDouble())
        val drag = BandEdit.begin(tab, x0, y0, opts, geo.inStrip(down.position.y), view.xStrip)
        if (drag != null && drag.selected >= 0) onSelect(if (drag.mode == BandMode.STRIP) -2 else 0, drag.selected)
        val slop = viewConfiguration.touchSlop
        var upAt = down.uptimeMillis
        val phase = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result = Phase.CANCEL
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch == null) { result = Phase.CANCEL; break }
                if (ch.changedToUp()) { upAt = ch.uptimeMillis; result = Phase.UP; break }
                if ((ch.position - down.position).getDistance() > slop) { result = Phase.MOVE; break }
            }
            result
        } ?: Phase.LONG
        when (phase) {
            Phase.UP -> {
                if (h.lastTapAt != 0L && upAt - h.lastTapAt <= viewConfiguration.doubleTapTimeoutMillis) {
                    h.lastTapAt = 0L
                    // a double tap: this tab's curves go back to their defaults, one history item
                    val reset = tab.reset()
                    onLive(reset)
                    ctx.controller.launch { bandApply(key, reset.writesFrom(tab, clearRemoved = false)) }
                } else h.lastTapAt = upAt
            }
            Phase.LONG -> {
                // colour zones: a node held down is taken away (desktop's right click); anything else waits for the lift
                val c = tab.curves[0]
                val hit = if (view.op == "colorzones" && !h.brush) BandEdit.nearestNode(c, x0, y0, opts.tolX, opts.tolY) else -1
                if (hit >= 0) {
                    val deleted = tab.copy(curves = listOf(BandEdit.deleteNode(c, hit)))
                    onLive(deleted)
                    ctx.controller.launch { bandApply(key, deleted.writesFrom(tab)) }
                }
                waitForUpOrCancellation()
            }
            Phase.MOVE -> if (drag == null) {
                waitForUpOrCancellation()
            } else {
                var current: BandTab = tab
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch: PointerInputChange = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (ch.changedToUp()) {
                        ch.consume()
                        ctx.controller.bandEnd(key, current.writesFrom(tab))
                        break
                    }
                    if (ch.positionChange() != Offset.Zero) {
                        ch.consume()
                        current = BandEdit.update(drag, geo.x(ch.position.x), geo.y(ch.position.y))
                        onLive(current)
                        if (drag.mode == BandMode.BRUSH) {
                            val c = current.curves[drag.curveIndex]
                            onBrush(drag.centre to BandInterp.fn(c.xs, c.ys, c.type, c.periodic)(drag.centre))
                        }
                        if (drag.selected >= 0) onSelect(if (drag.mode == BandMode.STRIP) -2 else 0, drag.selected)
                        ctx.controller.bandDrag(key, current.writesFrom(tab))
                    }
                }
            }
            Phase.CANCEL -> {}
        }
    } finally {
        onSelect(-1, -1)
        onBrush(null)
    }
}

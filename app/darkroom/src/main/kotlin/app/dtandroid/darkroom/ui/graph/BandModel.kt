// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import app.dtandroid.engine.api.paramAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Brief P3e-b: the band editor's model, with no screen code in it (JVM tests run it on the stand-in engine).
 *
 * One editor serves three modules. A tab holds one or two curves; a curve is a row of nodes whose x runs 0..1 along
 * the axis and whose y runs 0..1 up the graph. Each y is one engine parameter: `value = yMin + y * (yMax - yMin)`.
 * The x of a node is a parameter too where desktop lets it move (contrast equalizer's inner nodes, colour zones'
 * free nodes), else fixed (colour equalizer's hue positions).
 *
 * The behaviour follows desktop's source: contrast equalizer `atrous.c` (area_motion_notify: `get_params`, the x
 * strip, `area_button_press` double-click), colour zones `colorzones.c` (`_move_point_internal`, `_add_node`,
 * `_delete_node`, `dt_iop_colorzones_get_params`, `_reset_parameters`), colour equalizer `colorequal.c`
 * (`_area_set_value`: the graph's height spans the parameter's range, the neutral value in the middle).
 */
typealias BandWrite = Pair<String, JsonPrimitive>

enum class BandAxis { FREQUENCY, LIGHTNESS, CHROMA, HUE, HUE_NODES }

data class BandCurve(
    val name: String,
    val xs: List<Double>,
    val ys: List<Double>,
    /** one parameter path per node slot; null: the x positions are fixed */
    val xPaths: List<String>?,
    /** one parameter path per node slot (the slots are the most nodes the curve can have) */
    val yPaths: List<String>,
    /** the parameter holding the number of nodes, for a curve whose number of nodes changes */
    val countPath: String? = null,
    val yMin: Double = 0.0,
    val yMax: Double = 1.0,
    /** what a double tap sets */
    val resetXs: List<Double>,
    val resetYs: List<Double>,
    /** interpolation: [BandInterp.CUBIC], [BandInterp.CATMULL], [BandInterp.MONOTONE] */
    val type: Int = BandInterp.CATMULL,
    val typePath: String? = null,
    val resetType: Int = BandInterp.CATMULL,
    /** the axis wraps round (hue) */
    val periodic: Boolean = false,
    /** the second curve of a tab (denoise) */
    val second: Boolean = false,
    /** nodes keep at least this distance in x (colour zones: 0.0025; contrast equalizer: 0.001) */
    val minGap: Double = 0.001,
    /** the first and last node keep their x (contrast equalizer; colour zones' first splines version) */
    val endsFixed: Boolean = true,
    /** the last node's y follows the first's (colour zones' hue axis, first splines version) */
    val linkEndsY: Boolean = false,
    /** nodes can be added and taken away (colour zones) */
    val canAdd: Boolean = false,
) {
    val count get() = xs.size
    val maxNodes get() = yPaths.size

    fun value(y: Double) = yMin + y * (yMax - yMin)

    /** The engine writes that turn [base] into this curve; slots past the new node count are zeroed when [clearRemoved]. */
    fun writesFrom(base: BandCurve, clearRemoved: Boolean = true): List<BandWrite> {
        val out = ArrayList<BandWrite>()
        if (countPath != null && count != base.count) out += countPath to JsonPrimitive(count.toLong())
        if (typePath != null && type != base.type) out += typePath to JsonPrimitive(type.toLong())
        val slots = min(max(count, base.count), yPaths.size)
        for (i in 0 until slots) {
            val removed = i >= count
            if (removed && !clearRemoved) continue
            val y = ys.getOrNull(i)
            val by = base.ys.getOrNull(i)
            if (y != by) out += yPaths[i] to JsonPrimitive(if (y == null) 0.0 else value(y))
            val xp = xPaths ?: continue
            val x = xs.getOrNull(i)
            val bx = base.xs.getOrNull(i)
            if (x != bx) out += xp[i] to JsonPrimitive(x ?: 0.0)
        }
        return out
    }

    fun reset(): BandCurve = copy(xs = resetXs, ys = resetYs, type = resetType)
}

data class BandTab(
    val label: String,
    val curves: List<BandCurve>,
    val sharedX: Boolean = false,
    /** brief P6f: the channel colour of the tab's curve (0 neutral, 1 red, 2 green, 3 blue, 4 the second colour: raw denoise, denoise (profiled)) */
    val tint: Int = 0,
    /** brief P6f: the other tabs' curves, drawn faint behind (desktop draws every channel, the selected one last) */
    val ghosts: List<Pair<BandCurve, Int>> = emptyList(),
) {
    fun writesFrom(base: BandTab, clearRemoved: Boolean = true): List<BandWrite> =
        curves.indices.flatMap { curves[it].writesFrom(base.curves[it], clearRemoved) }

    fun reset(): BandTab = copy(curves = curves.map { it.reset() })
}

class BandView(
    val op: String,
    val axis: BandAxis,
    val tabs: List<BandTab>,
    /** the soft brush's radius where the editor starts, as a fraction of the axis */
    val defaultRadius: Double,
    /** the brush always works (contrast equalizer, colour equalizer); colour zones: its "edit by area" */
    val brushAlways: Boolean,
    /** nodes of the first curve can be taken at the strip under the graph (contrast equalizer) */
    val xStrip: Boolean = false,
    val gridX: Int = 8,
    /** brief P6f: the smallest brush radius (desktop's scroll limit: 0.2 / bands for lowlight and the denoise modules) */
    val radiusMin: Double = BandModels.RADIUS_MIN,
    /** brief P6f: words at the ends of the x axis and at the bottom and top of the graph, where desktop writes them */
    val xWords: Pair<String, String>? = null,
    val yWords: Pair<String, String>? = null,
)

object BandModels {
    val OPS = setOf("atrous", "colorzones", "colorequal", "lowlight", "rawdenoise", "denoiseprofile")

    const val RADIUS_MIN = 0.02
    const val RADIUS_MAX = 1.0

    private fun JsonObject.num(path: String): Double? = (paramAt(path) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.text(path: String): String? = (paramAt(path) as? JsonPrimitive)?.content

    /** The model of [op]'s curves in [p], or null where the parameters are not those of the module. */
    fun build(op: String, p: JsonObject): BandView? = when (op) {
        "atrous" -> atrous(p)
        "colorzones" -> zones(p)
        "colorequal" -> equalizer(p)
        "lowlight" -> lowlight(p)
        "rawdenoise" -> rawdenoise(p)
        "denoiseprofile" -> denoiseprofile(p)
        else -> null
    }

    // ------------------------------------------------------------------ brief P6f: lowlight vision, raw denoise, denoise (profiled)

    /**
     * One curve of [bands] nodes with x in `x[k]` and y in `y[k]` (paths from [xPath], [yPath]), as lowlight.c,
     * rawdenoise.c and denoiseprofile.c keep them: `init` puts x at k / (bands - 1), y defaults to 0.5, Catmull-Rom
     * (`dt_draw_curve_new(0, 1, CATMULL_ROM)`), the ends' x fixed.
     */
    private fun fixedBands(p: JsonObject, bands: Int, name: String, xPath: (Int) -> String, yPath: (Int) -> String): BandCurve? {
        val xs = (0 until bands).map { p.num(xPath(it)) ?: return null }
        val ys = (0 until bands).map { p.num(yPath(it)) ?: return null }
        val defaultXs = (0 until bands).map { it / (bands - 1.0) }
        val ordered = xs.zipWithNext().all { (a, b) -> a < b }
        return BandCurve(
            name = name, xs = if (ordered) xs else defaultXs, ys = ys,
            xPaths = (0 until bands).map(xPath), yPaths = (0 until bands).map(yPath),
            resetXs = defaultXs, resetYs = List(bands) { 0.5 },
            type = BandInterp.CATMULL, resetType = BandInterp.CATMULL, minGap = 0.001, endsFixed = true,
        )
    }

    const val LOWLIGHT_BANDS = 6

    /**
     * lowlight.c: the transition curve, x the luminance (dark to bright), y night to day vision; the soft brush
     * (`dt_iop_lowlight_get_params`, radius 1/6, scroll 0.2/6..1) and the inner nodes' x moved at the strip under the
     * graph (`lowlight_motion_notify`, x_move, 0.001 from the neighbours); double click resets x and y.
     */
    private fun lowlight(p: JsonObject): BandView? {
        val c = fixedBands(p, LOWLIGHT_BANDS, "lowlight", { "transition_x[$it]" }, { "transition_y[$it]" }) ?: return null
        return BandView("lowlight", BandAxis.LIGHTNESS, listOf(BandTab("lowlight", listOf(c))),
            defaultRadius = 1.0 / LOWLIGHT_BANDS, brushAlways = true, xStrip = true, gridX = 8, radiusMin = 0.2 / LOWLIGHT_BANDS,
            xWords = "dark" to "bright", yWords = "night vision" to "day vision")
    }

    const val RAWDENOISE_BANDS = 5
    private val RGB_TABS = listOf("all", "R", "G", "B")

    /**
     * rawdenoise.c: four curves (all, R, G, B) of five bands, y only (`rawdenoise_motion_notify` never moves an x),
     * the soft brush (radius 1/10, scroll 0.2/5..1), every channel drawn, the selected one last.
     */
    private fun rawdenoise(p: JsonObject): BandView? {
        val curves = RGB_TABS.indices.map { ch -> fixedBands(p, RAWDENOISE_BANDS, RGB_TABS[ch], { "x[$ch][$it]" }, { "y[$ch][$it]" }) ?: return null }
        val tabs = RGB_TABS.indices.map { ch -> BandTab(RGB_TABS[ch], listOf(curves[ch]), tint = ch, ghosts = curves.indices.filter { it != ch }.map { curves[it] to it }) }
        return BandView("rawdenoise", BandAxis.FREQUENCY, tabs,
            defaultRadius = 1.0 / (RAWDENOISE_BANDS * 2), brushAlways = true, gridX = 8, radiusMin = 0.2 / RAWDENOISE_BANDS)
    }

    const val DENOISE_BANDS = 7

    /** denoise (profiled)'s colour mode: true for Y0U0V0 (the enum's constant name or number) */
    fun denoiseY0U0V0(p: JsonObject): Boolean {
        val t = p.text("wavelet_color_mode") ?: return true
        t.toDoubleOrNull()?.let { return it.toInt() == 1 }
        return t == "MODE_Y0U0V0"
    }

    /**
     * denoiseprofile.c, wavelets mode: six curves of seven bands, y only; the tabs are all, R, G, B with the RGB colour
     * mode (channels 0..3) and luma, chroma with Y0U0V0 (channels 4, 5: `denoiseprofile_tab_switch`); the soft brush
     * (radius 1/14, scroll 0.2/7..1); "coarse" to "fine", "noisy" (bottom) to "smooth" (top).
     */
    private fun denoiseprofile(p: JsonObject): BandView? {
        val y0 = denoiseY0U0V0(p)
        val chans = if (y0) listOf(4, 5) else listOf(0, 1, 2, 3)
        val labels = if (y0) listOf("luma", "chroma") else RGB_TABS
        val curves = chans.mapIndexed { i, ch -> fixedBands(p, DENOISE_BANDS, labels[i], { "x[$ch][$it]" }, { "y[$ch][$it]" }) ?: return null }
        // desktop draws the mode's other curves faint behind the selected one: all grey, R, G, B; luma grey, chroma orange
        val tints = if (y0) listOf(0, 4) else listOf(0, 1, 2, 3)
        val tabs = chans.indices.map { i ->
            BandTab(labels[i], listOf(curves[i]), tint = tints[i], ghosts = curves.indices.filter { it != i }.map { curves[it] to tints[it] })
        }
        return BandView("denoiseprofile", BandAxis.FREQUENCY, tabs,
            defaultRadius = 1.0 / (DENOISE_BANDS * 2), brushAlways = true, gridX = 8, radiusMin = 0.2 / DENOISE_BANDS,
            xWords = "coarse" to "fine", yWords = "noisy" to "smooth")
    }

    // ------------------------------------------------------------------ contrast equalizer

    const val ATROUS_BANDS = 6

    private fun atrousCurve(p: JsonObject, c: Int, name: String, second: Boolean): BandCurve? {
        val xs = (0 until ATROUS_BANDS).map { p.num("x[$c][$it]") ?: return null }
        val ys = (0 until ATROUS_BANDS).map { p.num("y[$c][$it]") ?: return null }
        val defaultXs = (0 until ATROUS_BANDS).map { it / (ATROUS_BANDS - 1.0) }
        // desktop keeps the x positions in order; positions that are not (the stand-in engine's zeros) are the defaults
        val ordered = xs.zipWithNext().all { (a, b) -> a < b }
        return BandCurve(
            name = name, xs = if (ordered) xs else defaultXs, ys = ys,
            xPaths = (0 until ATROUS_BANDS).map { "x[$c][$it]" }, yPaths = (0 until ATROUS_BANDS).map { "y[$c][$it]" },
            resetXs = defaultXs, resetYs = List(ATROUS_BANDS) { if (second) 0.0 else 0.5 },
            type = BandInterp.CATMULL, resetType = BandInterp.CATMULL, second = second, minGap = 0.001, endsFixed = true,
        )
    }

    private fun atrous(p: JsonObject): BandView? {
        // channels of atrous.c: 0 luma, 1 chroma, 2 edges (s), 3 luma denoise (Lt), 4 chroma denoise (ct)
        val luma = atrousCurve(p, 0, "luma", false) ?: return null
        val chroma = atrousCurve(p, 1, "chroma", false) ?: return null
        val edges = atrousCurve(p, 2, "edges", false) ?: return null
        val lumaT = atrousCurve(p, 3, "luma denoise", true) ?: return null
        val chromaT = atrousCurve(p, 4, "chroma denoise", true) ?: return null
        return BandView(
            "atrous", BandAxis.FREQUENCY,
            listOf(BandTab("luma", listOf(luma, lumaT), sharedX = true), BandTab("chroma", listOf(chroma, chromaT), sharedX = true), BandTab("edges", listOf(edges))),
            defaultRadius = 1.0 / ATROUS_BANDS, brushAlways = true, xStrip = true, gridX = ATROUS_BANDS - 1,
        )
    }

    // ------------------------------------------------------------------ color zones

    const val ZONES_MAX_NODES = 20
    private val ZONES_TABS = listOf("lightness", "chroma", "hue")

    /** colour zones' "select by": 0 lightness, 1 chroma, 2 hue (the enum's constant name or number) */
    fun zonesChannel(p: JsonObject): Int {
        val t = p.text("channel") ?: return 2
        t.toDoubleOrNull()?.let { return it.toInt().coerceIn(0, 2) }
        return when {
            t.endsWith("_L") -> 0
            t.endsWith("_C") -> 1
            else -> 2
        }
    }

    /** Where a fresh curve's two nodes sit (colorzones.c `_reset_nodes`): at the edges, or half a step in for a hue axis. */
    fun zonesResetXs(touchEdges: Boolean, n: Int = 2) = (0 until n).map { if (touchEdges) it / (n - 1.0) else (it + 0.5) / n }

    private fun zones(p: JsonObject): BandView? {
        val channel = zonesChannel(p)
        val v2 = (p.num("splines_version") ?: 1.0).toInt() >= 1
        val hue = channel == 2
        val tabs = ZONES_TABS.mapIndexed { ch, label ->
            val stored = (p.num("curve_num_nodes[$ch]") ?: return null).toInt()
            val touch = !hue || !v2
            val n = if (stored < 1 || stored > ZONES_MAX_NODES) 2 else stored
            val xs: List<Double>
            val ys: List<Double>
            if (stored < 1 || stored > ZONES_MAX_NODES) {
                // no nodes stored (the stand-in engine's zeros): desktop's two nodes
                xs = zonesResetXs(touch)
                ys = listOf(0.5, 0.5)
            } else {
                xs = (0 until n).map { p.num("curve[$ch][$it].x") ?: return null }
                ys = (0 until n).map { p.num("curve[$ch][$it].y") ?: return null }
            }
            val type = (p.num("curve_type[$ch]") ?: 1.0).toInt().coerceIn(0, 2)
            BandTab(
                label,
                listOf(BandCurve(
                    name = label, xs = xs, ys = ys,
                    xPaths = (0 until ZONES_MAX_NODES).map { "curve[$ch][$it].x" }, yPaths = (0 until ZONES_MAX_NODES).map { "curve[$ch][$it].y" },
                    countPath = "curve_num_nodes[$ch]", resetXs = zonesResetXs(touch), resetYs = listOf(0.5, 0.5),
                    type = type, typePath = "curve_type[$ch]", resetType = BandInterp.CATMULL,
                    periodic = hue && v2, minGap = 0.0025, endsFixed = !v2, linkEndsY = hue && !v2, canAdd = true,
                )),
            )
        }
        val axis = when (channel) { 0 -> BandAxis.LIGHTNESS; 1 -> BandAxis.CHROMA; else -> BandAxis.HUE }
        return BandView("colorzones", axis, tabs, defaultRadius = 0.1, brushAlways = false, gridX = 8)
    }

    /**
     * What colour zones' "select by" writes (`gui_changed`, `_reset_parameters`): the new channel, and every curve,
     * the mix and the process mode back to their defaults.
     */
    fun zonesSelectWrites(channel: Int, p: JsonObject): List<BandWrite> {
        val v2 = (p.num("splines_version") ?: 1.0).toInt() >= 1
        val touch = channel != 2 || !v2
        val out = ArrayList<BandWrite>()
        val const = listOf("DT_IOP_COLORZONES_L", "DT_IOP_COLORZONES_C", "DT_IOP_COLORZONES_h")[channel.coerceIn(0, 2)]
        out += "channel" to JsonPrimitive(const)
        val xs = zonesResetXs(touch)
        for (ch in 0..2) {
            out += "curve_num_nodes[$ch]" to JsonPrimitive(2L)
            out += "curve_type[$ch]" to JsonPrimitive(BandInterp.CATMULL.toLong())
            for (i in 0..1) {
                out += "curve[$ch][$i].x" to JsonPrimitive(xs[i])
                out += "curve[$ch][$i].y" to JsonPrimitive(0.5)
            }
        }
        out += "strength" to JsonPrimitive(0.0)
        out += "mode" to JsonPrimitive("DT_IOP_COLORZONES_MODE_SMOOTH")
        return out
    }

    /**
     * Where a colour the picker took lies on colour zones' axis (colorzones.c `_draw_color_picker`: lightness / 100,
     * chroma / (128 sqrt 2), hue as it is in 0..1). [stats] is the module's input in LCh; an area marks its range
     * (min to max) and the mean, a point only the point.
     */
    fun pickMarks(axis: BandAxis, stats: app.dtandroid.engine.api.PickStats): List<BandPick> {
        if (stats.colorspace != "lch") return emptyList()
        val i = when (axis) { BandAxis.LIGHTNESS -> 0; BandAxis.CHROMA -> 1; BandAxis.HUE -> 2; else -> return emptyList() }
        fun pos(v: Double?): Double? = v?.takeIf { it.isFinite() }?.let {
            when (axis) {
                BandAxis.LIGHTNESS -> it / 100.0
                BandAxis.CHROMA -> it / (128.0 * Math.sqrt(2.0))
                else -> if (it > 1.0001) (it / 360.0) % 1.0 else it
            }.coerceIn(0.0, 1.0)
        }
        val mean = pos(stats.mean.getOrNull(i)) ?: return emptyList()
        val lo = pos(stats.min.getOrNull(i))
        val hi = pos(stats.max.getOrNull(i))
        return listOf(BandPick(mean, if (lo != null && hi != null && lo != hi) lo else null, if (lo != null && hi != null && lo != hi) hi else null))
    }

    // ------------------------------------------------------------------ color equalizer

    const val EQUAL_NODES = 8
    private val EQUAL_COLOURS = listOf("red", "orange", "yellow", "green", "cyan", "blue", "lavender", "magenta")
    val EQUAL_NODE_NAMES: List<String> get() = EQUAL_COLOURS

    private fun equalCurve(p: JsonObject, prefix: String, name: String, lo: Double, hi: Double): BandCurve? {
        val shift = (p.num("hue_shift") ?: 0.0) / 360.0
        val paths = EQUAL_COLOURS.map { "${prefix}_$it" }
        val vals = paths.map { p.num(it) ?: return null }
        val neutral = List(EQUAL_NODES) { 0.5 }
        return BandCurve(
            name = name, xs = (0 until EQUAL_NODES).map { it / EQUAL_NODES.toDouble() + shift },
            ys = vals.map { ((it - lo) / (hi - lo)).coerceIn(0.0, 1.0) }, xPaths = null, yPaths = paths,
            yMin = lo, yMax = hi, resetXs = (0 until EQUAL_NODES).map { it / EQUAL_NODES.toDouble() + shift }, resetYs = neutral,
            type = BandInterp.CATMULL, periodic = true, endsFixed = false,
        )
    }

    private fun equalizer(p: JsonObject): BandView? {
        // colorequal.c `_get_param_ptr`: hue -180..180 degrees (neutral 0), saturation and brightness 0..2 (neutral 1)
        val hue = equalCurve(p, "hue", "hue", -180.0, 180.0) ?: return null
        val sat = equalCurve(p, "sat", "saturation", 0.0, 2.0) ?: return null
        val bri = equalCurve(p, "bright", "brightness", 0.0, 2.0) ?: return null
        return BandView("colorequal", BandAxis.HUE_NODES, listOf(BandTab("hue", listOf(hue)), BandTab("saturation", listOf(sat)), BandTab("brightness", listOf(bri))),
            defaultRadius = 0.1375, brushAlways = true, gridX = EQUAL_NODES)
    }
}

/** A picked value on the axis: a position, with a range when the pick was an area. */
data class BandPick(val x: Double, val from: Double? = null, val to: Double? = null)

/** What a finger does to a curve: the brush, one node, a new node, or an x position at the strip. */
enum class BandMode { BRUSH, NODE, ADD, STRIP, NONE }

/** The editor's settings that decide how a touch is understood. */
data class BandOptions(
    /** the soft brush's radius, as a fraction of the axis */
    val radius: Double,
    /** the brush is on (else nodes are taken one by one) */
    val brush: Boolean,
    /** how near a node a finger takes it, in graph units on each axis */
    val tolX: Double,
    val tolY: Double,
)

/** One finger's edit of one tab, from the touch down to the lift. */
class BandDrag internal constructor(
    val mode: BandMode,
    /** the tab as it was when the finger came down: every writes list is made against it */
    val base: BandTab,
    internal val curve: Int,
    internal var node: Int,
    internal val cx: Double,
    internal val pick: Double,
    internal val grabDx: Double,
    internal val grabDy: Double,
    internal val radius: Double,
    internal var started: BandCurve? = null,
) {
    /** the node being moved, or -1 */
    val selected get() = node
    /** the brush's centre on the axis */
    val centre get() = cx
    /** which curve of the tab is edited */
    val curveIndex get() = curve
}

object BandEdit {
    /** The nearest node of [c] within the finger's reach ([tolX], [tolY] as the half-axes of an ellipse), or -1. */
    fun nearestNode(c: BandCurve, x: Double, y: Double, tolX: Double, tolY: Double): Int {
        var best = -1
        var bestD = 1.0
        for (i in c.xs.indices) {
            var dx = abs(c.xs[i] - x)
            if (c.periodic) dx = min(dx, 1.0 - dx)
            val d = (dx / tolX) * (dx / tolX) + ((c.ys[i] - y) / tolY) * ((c.ys[i] - y) / tolY)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    /** The soft brush (atrous.c `get_params`): every node's y is drawn towards [target] with a Gaussian of [radius] round [cx]. */
    fun brush(c: BandCurve, cx: Double, target: Double, radius: Double): List<Double> {
        val r2 = radius * radius
        return c.ys.indices.map { k ->
            var d = abs(cx - c.xs[k])
            if (c.periodic) d = min(d, 1.0 - d)
            val f = exp(-d * d / r2)
            ((1.0 - f) * c.ys[k] + f * target).coerceIn(0.0, 1.0)
        }
    }

    /** Node [i] moved to ([nx], [ny]) as colorzones.c `_move_point_internal` allows: in order, a gap apart, the ends as the splines version asks. */
    fun moveNode(c: BandCurve, i: Int, nx: Double, ny: Double): BandCurve {
        val n = c.count
        val y = ny.coerceIn(0.0, 1.0)
        val xp = c.xPaths
        var x = if (xp == null || (c.endsFixed && (i == 0 || i == n - 1))) c.xs[i] else nx.coerceIn(0.0, 1.0)
        var lo = if (i > 0) c.xs[i - 1] + c.minGap else 0.0
        var hi = if (i < n - 1) c.xs[i + 1] - c.minGap else 1.0
        if (c.periodic && n > 1) {
            if (i == 0) lo = max(lo, c.xs[n - 1] + c.minGap - 1.0)
            if (i == n - 1) hi = min(hi, c.xs[0] + 1.0 - c.minGap)
            if (i == 0) lo = min(lo, hi)
        }
        if (lo <= hi) x = x.coerceIn(lo, hi) else x = c.xs[i]
        val nxs = c.xs.toMutableList().also { it[i] = x }
        val nys = c.ys.toMutableList().also {
            it[i] = y
            if (c.linkEndsY && n > 1 && (i == 0 || i == n - 1)) { it[0] = y; it[n - 1] = y }
        }
        return c.copy(xs = nxs, ys = nys)
    }

    /** A node added at ([x], [y]) (colorzones.c `_add_node`); null where the curve is full or the node would be too near another. */
    fun addNode(c: BandCurve, x: Double, y: Double): Pair<BandCurve, Int>? {
        if (!c.canAdd || c.count >= c.maxNodes) return null
        val at = c.xs.indexOfFirst { it > x }.let { if (it < 0) c.count else it }
        if ((at > 0 && x - c.xs[at - 1] <= c.minGap) || (at < c.count && c.xs[at] - x <= c.minGap)) return null
        val nxs = c.xs.toMutableList().also { it.add(at, x.coerceIn(0.0, 1.0)) }
        val nys = c.ys.toMutableList().also { it.add(at, y.coerceIn(0.0, 1.0)) }
        return c.copy(xs = nxs, ys = nys) to at
    }

    /**
     * Node [i] taken away (colorzones.c `_delete_node`, the right click): the curve keeps at least one node; with the
     * first splines version the end nodes are put back on their edge instead.
     */
    fun deleteNode(c: BandCurve, i: Int): BandCurve {
        val n = c.count
        if (!c.canAdd) return c
        if (c.endsFixed && (i == 0 || i == n - 1)) {
            val x = if (i == 0) 0.0 else 1.0
            return c.copy(xs = c.xs.toMutableList().also { it[i] = x }, ys = c.ys.toMutableList().also {
                if (c.linkEndsY) { it[0] = 0.5; it[n - 1] = 0.5 } else it[i] = 0.5
            })
        }
        if (n > 1) return c.copy(xs = c.xs.toMutableList().also { it.removeAt(i) }, ys = c.ys.toMutableList().also { it.removeAt(i) })
        return c.copy(xs = listOf(0.5), ys = listOf(0.5))
    }

    private fun withCurve(t: BandTab, k: Int, c: BandCurve) = t.copy(curves = t.curves.toMutableList().also { it[k] = c })

    /**
     * A finger comes down at graph position ([x], [y]). [inStrip]: it landed in the strip under the graph.
     * Null when it takes nothing (the editor then lets the gesture go).
     */
    fun begin(tab: BandTab, x: Double, y: Double, o: BandOptions, inStrip: Boolean, xStrip: Boolean): BandDrag? {
        if (inStrip) {
            if (!xStrip) return null
            val c = tab.curves[0]
            var node = 0
            var dist = abs(c.xs[0] - x)
            for (k in 1 until c.count) { val d = abs(c.xs[k] - x); if (d < dist) { node = k; dist = d } }
            if (node <= 0 || node >= c.count - 1) return null // desktop moves the inner nodes only
            return BandDrag(BandMode.STRIP, tab, 0, node, x, 0.0, 0.0, 0.0, o.radius)
        }
        if (o.brush) {
            // the curve whose node nearest in x is nearest in y (desktop chooses the bottom or the top curve)
            var ci = 0
            if (tab.curves.size > 1) {
                var bestDist = Double.MAX_VALUE
                for ((k, c) in tab.curves.withIndex()) {
                    val n = c.xs.indices.minByOrNull { abs(c.xs[it] - x) } ?: continue
                    val d = abs(c.ys[n] - y)
                    if (d < bestDist) { bestDist = d; ci = k }
                }
            }
            val c = tab.curves[ci]
            // a finger on a node takes the node: the brush is centred on it, so it follows the finger
            val hit = nearestNode(c, x, y, o.tolX, o.tolY)
            val cx = if (hit >= 0) c.xs[hit] else x
            val at = BandInterp.fn(c.xs, c.ys, c.type, c.periodic)(cx)
            return BandDrag(BandMode.BRUSH, tab, ci, hit, cx, at - y, 0.0, 0.0, o.radius)
        }
        val c = tab.curves[0]
        val hit = nearestNode(c, x, y, o.tolX, o.tolY)
        if (hit >= 0) return BandDrag(BandMode.NODE, tab, 0, hit, x, 0.0, c.xs[hit] - x, c.ys[hit] - y, o.radius)
        if (c.canAdd && c.count < c.maxNodes) return BandDrag(BandMode.ADD, tab, 0, -1, x, 0.0, 0.0, 0.0, o.radius)
        return null
    }

    /** The finger is at ([x], [y]): the tab as it now looks (made from the tab at the touch down, not from the last step). */
    fun update(d: BandDrag, x: Double, y: Double): BandTab {
        val t = d.base
        val c = t.curves[d.curve]
        return when (d.mode) {
            BandMode.BRUSH -> withCurve(t, d.curve, c.copy(ys = brush(c, d.cx, y + d.pick, d.radius)))
            BandMode.NODE -> withCurve(t, d.curve, moveNode(c, d.node, x + d.grabDx, y + d.grabDy))
            BandMode.ADD -> {
                val st = d.started ?: addNode(c, x, y)?.let { (nc, at) -> d.node = at; d.started = nc; nc } ?: return t
                withCurve(t, d.curve, moveNode(st, d.node, x, y))
            }
            BandMode.STRIP -> {
                val k = d.node
                val nx = x.coerceIn(c.xs[k - 1] + c.minGap, c.xs[k + 1] - c.minGap)
                // both curves of a tab share their x positions (atrous.c sets p->x[ch2][k] = p->x[channel][k])
                if (t.sharedX) t.copy(curves = t.curves.map { cc -> cc.copy(xs = cc.xs.toMutableList().also { it[k] = nx }) })
                else withCurve(t, d.curve, c.copy(xs = c.xs.toMutableList().also { it[k] = nx }))
            }
            BandMode.NONE -> t
        }
    }
}

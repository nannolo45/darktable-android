// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import app.dtandroid.engine.api.paramAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * Brief P3e: the logic of the curve editor, without any drawing (docs/design/ui-spec.md "graph editors: curves").
 * Nodes are (x, y) in 0..1 as the module stores them. Desktop's references: tonecurve.c and rgbcurve.c
 * (`_add_node`, `_move_point_internal`, the button press handlers) and rgblevels.c (`_rgblevels_move_handle`).
 */
data class CurveNode(val x: Double, val y: Double)

/** The names of one curve module's node arrays (the schema's field names) and its limits. */
class CurveLayout(
    val op: String,
    val channels: Int,
    val maxNodes: Int,
    private val node: (Int, Int, Char) -> String,
    private val count: (Int) -> String,
    private val type: (Int) -> String,
) {
    fun nodePath(ch: Int, k: Int, axis: Char) = node(ch, k, axis)
    fun countPath(ch: Int) = count(ch)
    fun typePath(ch: Int) = type(ch)

    /** The channel's nodes as the module holds them; the identity curve when the parameters hold no usable curve. */
    fun read(params: JsonObject, ch: Int): List<CurveNode> {
        val n = (params.paramAt(count(ch)) as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        val out = ArrayList<CurveNode>()
        for (k in 0 until n.coerceIn(0, maxNodes)) {
            val x = (params.paramAt(node(ch, k, 'x')) as? JsonPrimitive)?.doubleOrNull ?: break
            val y = (params.paramAt(node(ch, k, 'y')) as? JsonPrimitive)?.doubleOrNull ?: break
            out += CurveNode(x, y)
        }
        if (CurveEdit.valid(out)) return out
        // the engine's "create curve" can leave two nodes at one x (the default end node and the picked maximum at 1.0): the later one is dropped
        val dedup = ArrayList<CurveNode>()
        for (nd in out) if (dedup.isEmpty() || nd.x > dedup.last().x) dedup += nd
        return if (dedup.size >= 2 && CurveEdit.valid(dedup)) dedup else defaults(ch)
    }

    /** darktable's default nodes of the channel (`init`): a straight line; the tone curve's a and b have a middle node. */
    fun defaults(ch: Int): List<CurveNode> =
        if (op == "tonecurve" && ch > 0) listOf(CurveNode(0.0, 0.0), CurveNode(0.5, 0.5), CurveNode(1.0, 1.0)) else CurveEdit.IDENTITY

    /** The interpolation method of the channel (0 cubic spline, 1 centripetal spline, 2 monotonic spline). */
    fun readType(params: JsonObject, ch: Int): Int = (params.paramAt(type(ch)) as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0

    /**
     * What to write for [nodes] on channel [ch]: the count and every node. [previous] is the count the module held;
     * the slots a shorter curve leaves behind are zeroed, as desktop does.
     */
    fun writes(ch: Int, nodes: List<CurveNode>, previous: Int = nodes.size): Map<String, JsonPrimitive> {
        val out = LinkedHashMap<String, JsonPrimitive>()
        out[count(ch)] = JsonPrimitive(nodes.size.toLong())
        nodes.forEachIndexed { k, nd ->
            out[node(ch, k, 'x')] = JsonPrimitive(nd.x)
            out[node(ch, k, 'y')] = JsonPrimitive(nd.y)
        }
        for (k in nodes.size until min(previous, maxNodes)) {
            out[node(ch, k, 'x')] = JsonPrimitive(0.0)
            out[node(ch, k, 'y')] = JsonPrimitive(0.0)
        }
        return out
    }

    fun countOf(params: JsonObject, ch: Int): Int = (params.paramAt(count(ch)) as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0

    companion object {
        /** the default interpolation method (monotonic spline) */
        const val DEFAULT_TYPE = 2

        /** tone curve: tonecurve[ch][k].x, tonecurve_nodes[ch], tonecurve_type[ch]; DT_IOP_TONECURVE_MAXNODES 20 */
        val TONECURVE = CurveLayout("tonecurve", 3, 20, { c, k, a -> "tonecurve[$c][$k].$a" }, { "tonecurve_nodes[$it]" }, { "tonecurve_type[$it]" })

        /** rgb curve: curve_nodes[ch][k].x, curve_num_nodes[ch], curve_type[ch]; MAX_ANCHORS 20 */
        val RGBCURVE = CurveLayout("rgbcurve", 3, 20, { c, k, a -> "curve_nodes[$c][$k].$a" }, { "curve_num_nodes[$it]" }, { "curve_type[$it]" })

        fun of(op: String): CurveLayout? = when (op) { "tonecurve" -> TONECURVE; "rgbcurve" -> RGBCURVE; else -> null }
    }
}

/** Editing a list of nodes: always in order (x strictly increasing), inside 0..1, at most [CurveLayout.maxNodes]. */
object CurveEdit {
    val IDENTITY = listOf(CurveNode(0.0, 0.0), CurveNode(1.0, 1.0))

    /** Smallest gap in x between two nodes while one is dragged (desktop deletes a node dragged past its neighbour; the app stops it). */
    const val MIN_GAP = 0.001

    /** Desktop adds a node by ctrl-click only when it lies more than this from both neighbours in x (`0.025`). */
    const val ADD_GAP = 0.025

    /** Desktop selects a node within 0.04 of the touch (graph fractions): the tap radius is the larger of this and the touch target. */
    const val SELECT_RADIUS = 0.04

    fun valid(nodes: List<CurveNode>): Boolean =
        nodes.size >= 2 && nodes.zipWithNext().all { (a, b) -> a.x < b.x } && nodes.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 }

    /** Node [i] to (x, y), clamped to the graph and to its neighbours. */
    fun move(nodes: List<CurveNode>, i: Int, x: Double, y: Double): List<CurveNode> {
        if (i !in nodes.indices) return nodes
        val lo = if (i > 0) nodes[i - 1].x + MIN_GAP else 0.0
        val hi = if (i < nodes.lastIndex) nodes[i + 1].x - MIN_GAP else 1.0
        val nx = min(max(x, lo), max(lo, hi)).coerceIn(0.0, 1.0)
        return nodes.toMutableList().also { it[i] = CurveNode(nx, y.coerceIn(0.0, 1.0)) }
    }

    /** A node at (x, y) in its place; null when the curve is full, or x is within [ADD_GAP] of a neighbour. The new node's index is in `second`. */
    fun add(nodes: List<CurveNode>, x: Double, y: Double, maxNodes: Int): Pair<List<CurveNode>, Int>? {
        if (nodes.size >= maxNodes) return null
        val at = nodes.indexOfFirst { it.x > x }.let { if (it < 0) nodes.size else it }
        if (at > 0 && x - nodes[at - 1].x <= ADD_GAP) return null
        if (at < nodes.size && nodes[at].x - x <= ADD_GAP) return null
        return nodes.toMutableList().also { it.add(at, CurveNode(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))) } to at
    }

    /** Remove node [i]; the two end nodes cannot be removed: desktop puts them back at 0 and 1 (tonecurve.c, right click). */
    fun remove(nodes: List<CurveNode>, i: Int): List<CurveNode> {
        if (i !in nodes.indices) return nodes
        if (i == 0) return nodes.toMutableList().also { it[0] = CurveNode(0.0, 0.0) }
        if (i == nodes.lastIndex) return nodes.toMutableList().also { it[i] = CurveNode(1.0, 1.0) }
        return nodes.toMutableList().also { it.removeAt(i) }
    }

    /** The node nearest to (x, y) within [radiusX] and [radiusY] (graph fractions), or -1. */
    fun hit(nodes: List<CurveNode>, x: Double, y: Double, radiusX: Double, radiusY: Double): Int {
        var best = -1
        var bestD = 1.0
        nodes.forEachIndexed { k, n ->
            val dx = (n.x - x) / radiusX
            val dy = (n.y - y) / radiusY
            val d = dx * dx + dy * dy
            if (d <= 1.0 && d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** The curve through [nodes] at [x], for drawing: [type] 0 cubic spline, 1 centripetal (Catmull-Rom), 2 monotonic; clamped to 0..1. */
    fun eval(nodes: List<CurveNode>, type: Int, x: Double): Double {
        val n = nodes.size
        if (n == 0) return x
        if (n == 1) return nodes[0].y
        if (x <= nodes[0].x) return nodes[0].y
        if (x >= nodes[n - 1].x) return nodes[n - 1].y
        var i = 0
        while (i < n - 2 && x > nodes[i + 1].x) i++
        val m = tangents(nodes, type)
        val h = nodes[i + 1].x - nodes[i].x
        val t = (x - nodes[i].x) / h
        val t2 = t * t
        val t3 = t2 * t
        val y = (2 * t3 - 3 * t2 + 1) * nodes[i].y + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * nodes[i + 1].y + (t3 - t2) * h * m[i + 1]
        return y.coerceIn(0.0, 1.0)
    }

    private fun tangents(nodes: List<CurveNode>, type: Int): DoubleArray {
        val n = nodes.size
        val d = DoubleArray(n - 1) { (nodes[it + 1].y - nodes[it].y) / (nodes[it + 1].x - nodes[it].x) }
        val m = DoubleArray(n)
        when (type) {
            0 -> { // natural cubic spline: second derivative 0 at both ends
                if (n == 2) { m[0] = d[0]; m[1] = d[0]; return m }
                val h = DoubleArray(n - 1) { nodes[it + 1].x - nodes[it].x }
                val a = DoubleArray(n); val b = DoubleArray(n); val c = DoubleArray(n); val r = DoubleArray(n)
                b[0] = 2 * h[0]; c[0] = h[0]; r[0] = 3 * d[0]
                for (k in 1 until n - 1) { a[k] = h[k]; b[k] = 2 * (h[k - 1] + h[k]); c[k] = h[k - 1]; r[k] = 3 * (h[k] * d[k - 1] + h[k - 1] * d[k]) }
                a[n - 1] = h[n - 2]; b[n - 1] = 2 * h[n - 2]; r[n - 1] = 3 * d[n - 2]
                // Thomas algorithm
                for (k in 1 until n) { val w = a[k] / b[k - 1]; b[k] -= w * c[k - 1]; r[k] -= w * r[k - 1] }
                m[n - 1] = r[n - 1] / b[n - 1]
                for (k in n - 2 downTo 0) m[k] = (r[k] - c[k] * m[k + 1]) / b[k]
            }
            1 -> { // Catmull-Rom: the slope across the neighbours
                m[0] = d[0]; m[n - 1] = d[n - 2]
                for (k in 1 until n - 1) m[k] = (nodes[k + 1].y - nodes[k - 1].y) / (nodes[k + 1].x - nodes[k - 1].x)
            }
            else -> { // monotonic (Fritsch and Carlson)
                m[0] = d[0]; m[n - 1] = d[n - 2]
                for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0.0) 0.0 else (d[k - 1] + d[k]) / 2
                for (k in 0 until n - 1) {
                    if (d[k] == 0.0) { m[k] = 0.0; m[k + 1] = 0.0; continue }
                    val a = m[k] / d[k]; val b = m[k + 1] / d[k]
                    val s = a * a + b * b
                    if (s > 9.0) { val tau = 3.0 / kotlin.math.sqrt(s); m[k] = tau * a * d[k]; m[k + 1] = tau * b * d[k] }
                }
            }
        }
        return m
    }
}

/** rgb levels: three handles per channel, `levels[ch][0..2]` = black, grey, white (rgblevels.c). */
object LevelsEdit {
    /** Least distance between two handles (`0.05` in `_rgblevels_move_handle`). */
    const val MIN_GAP = 0.05

    fun path(ch: Int, k: Int) = "levels[$ch][$k]"

    fun read(params: JsonObject, ch: Int): DoubleArray = DoubleArray(3) { k ->
        (params.paramAt(path(ch, k)) as? JsonPrimitive)?.doubleOrNull ?: doubleArrayOf(0.0, 0.5, 1.0)[k]
    }.let { if (it[0] < it[1] && it[1] < it[2]) it else doubleArrayOf(0.0, 0.5, 1.0) }

    /**
     * Handle [handle] to [pos], as desktop moves it: black and white keep the grey handle at the same relative place
     * ([startPct], measured when the drag began); grey moves between the other two.
     */
    fun move(levels: DoubleArray, handle: Int, pos: Double, startPct: Double): DoubleArray {
        val pct = startPct.coerceIn(0.001, 0.999)
        var lo = 0.0
        var hi = 1.0
        when (handle) {
            0 -> {
                hi = min(levels[2] - MIN_GAP / pct, 1.0)
                hi = min((levels[2] * (1 - pct) - MIN_GAP) / (1 - pct), hi)
            }
            1 -> { lo = levels[0] + MIN_GAP; hi = levels[2] - MIN_GAP }
            2 -> {
                lo = max(MIN_GAP / pct + levels[0], 0.0)
                lo = max((levels[0] * (1 - pct) + MIN_GAP) / (1 - pct), lo)
            }
        }
        val out = levels.copyOf()
        out[handle] = min(hi, max(lo, pos))
        if (handle != 1) out[1] = out[0] + pct * (out[2] - out[0])
        return out
    }

    fun pct(levels: DoubleArray): Double = (levels[1] - levels[0]) / (levels[2] - levels[0])

    fun writes(ch: Int, levels: DoubleArray): Map<String, JsonPrimitive> = (0..2).associate { path(ch, it) to JsonPrimitive(levels[it]) }

    /** The handle nearest to [x] (0 black, 1 grey, 2 white), as desktop picks it on a press. */
    fun nearest(levels: DoubleArray, x: Double): Int = (0..2).minByOrNull { kotlin.math.abs(levels[it] - x) } ?: 0
}

/** Where what the colour picker took lies on a curve's graph (x from 0 to 1), and the "in → out" line desktop writes. */
data class CurvePickMark(val min: Double, val mean: Double, val max: Double, val text: String)

/**
 * The picker on tone curve and rgb curve's graph, as desktop draws it (tonecurve.c `dt_iop_tonecurve_draw` with
 * `picker_scale`; rgbcurve.c `rgbcurve_draw` with its `picker_scale`): a band from the picked minimum to maximum, a
 * line at the mean, and "mean in → mean out". The numbers are the module's own input and output in its picker space
 * (tone curve: Lab; rgb curve: the working RGB).
 */
/**
 * Brief P4c: how a curve's graph is shown, as desktop's: rgb curve's zoom and offset (rgbcurve.c `_curve_to_mouse`,
 * set with ctrl+scroll on desktop) and tone curve's "scale for graph" (tonecurve.c `to_log`, `loglogscale` and
 * `semilog`). The app's graphs have neither control, so they are shown with [IDENTITY]; the mark follows whatever view
 * the graph is drawn with.
 */
data class CurveView(val zoom: Double = 1.0, val offsetX: Double = 0.0, val logBase: Double = 0.0, val semilog: Int = 0) {
    companion object { val IDENTITY = CurveView() }
}

object CurvePick {
    fun mark(op: String, ch: Int, params: kotlinx.serialization.json.JsonObject, input: app.dtandroid.engine.api.PickStats?,
             output: app.dtandroid.engine.api.PickStats?, view: CurveView = CurveView.IDENTITY): CurvePickMark? {
        input ?: return null
        val c = ch.coerceIn(0, 2)
        return when (op) {
            "tonecurve" -> {
                fun sc(v: Double) = if (c == 0) (v / 100.0).coerceIn(0.0, 1.0) else ((v + 128.0) / 256.0).coerceIn(0.0, 1.0)
                fun x(v: Double) = toLog(sc(v), view.logBase, c, view.semilog)
                val mean = input.mean.getOrNull(c) ?: return null
                val lo = input.min.getOrNull(c) ?: mean
                val hi = input.max.getOrNull(c) ?: mean
                val out = output?.mean?.getOrNull(c)
                CurvePickMark(x(lo), x(mean), x(hi), if (out != null) "%.1f → %.1f".format(mean, out) else "%.1f".format(mean))
            }
            "rgbcurve" -> {
                val manual = paramString(params, "curve_autoscale").let { it == "DT_S_SCALE_MANUAL_RGB" || it == "1" }
                val grey = paramString(params, "compensate_middle_grey").let { it == "true" || it == "1" }
                // k: 0 mean, 1 min, 2 max (the order of PickWork's values)
                fun scale(st: app.dtandroid.engine.api.PickStats, k: Int): Double? {
                    val v = when (k) { 0 -> st.mean; 1 -> st.min; else -> st.max }
                    val w = st.work
                    val x = if (w != null) {
                        // darktable's own picker_scale values in the pipe's working profile (any tone curve)
                        if (manual) (if (grey) w.grey.getOrNull(k)?.getOrNull(c) else v.getOrNull(c)) ?: return null
                        else (if (grey) w.greyLuminance.getOrNull(k) else w.luminance.getOrNull(k)) ?: return null
                    } else {
                        val rgb = (0..2).map { v.getOrNull(it) ?: return null }
                        pickerScale(rgb, REC2020_Y, manual, grey)[if (manual) c else 0]
                    }
                    return x.coerceIn(0.0, 1.0)
                }
                val mean = scale(input, 0) ?: return null
                val lo = scale(input, 1) ?: mean
                val hi = scale(input, 2) ?: mean
                val out = output?.let { scale(it, 0) }
                fun x(v: Double) = curveToMouse(v, view.zoom, view.offsetX)
                CurvePickMark(x(lo), x(mean), x(hi), if (out != null) "%.1f → %.1f".format(mean * 255, out * 255) else "%.1f".format(mean * 255))
            }
            else -> null
        }
    }

    /**
     * rgbcurve.c `picker_scale` for a linear working profile whose matrix_in luminance row is [coeffs]: independent
     * channels (manual) each channel, linked (automatic) the luminance in channel 0; "compensate middle grey" through
     * `dt_ioppr_compensate_middle_grey`; clipped to 0..1. The engine's own values (PickStats.work) are used when given.
     */
    fun pickerScale(rgb: List<Double>, coeffs: List<Double>, manual: Boolean, grey: Boolean): List<Double> {
        val white = coeffs.sum()
        val out = if (manual) rgb.map { if (grey) middleGrey(it, white) else it }
        else {
            val y = coeffs[0] * rgb[0] + coeffs[1] * rgb[1] + coeffs[2] * rgb[2]
            listOf(if (grey) middleGrey(y, white) else y, 0.0, 0.0)
        }
        return out.map { it.coerceIn(0.0, 1.0) }
    }

    /**
     * dt_ioppr_compensate_middle_grey for a linear profile: the grey (x, x, x) to XYZ through matrix_in (Y = x times the
     * luminance row's sum, [white], 1 for a profile whose white is D50's), then Lab lightness / 100 (dt_XYZ_to_Lab).
     */
    fun middleGrey(x: Double, white: Double = 1.0): Double {
        val y = x * white
        return if (y > 216.0 / 24389.0) 1.16 * Math.cbrt(y) - 0.16 else y * 24389.0 / 27.0 / 100.0
    }

    /** rgbcurve.c `_curve_to_mouse`. */
    fun curveToMouse(x: Double, zoom: Double, offset: Double): Double = (x - offset) * zoom

    /** tonecurve.c `to_log` for the abscissa (L only; semilog -1 keeps the abscissa linear). */
    fun toLog(x: Double, base: Double, ch: Int, semilog: Int): Double =
        if (base > 0.0 && ch == 0 && semilog != -1) Math.log(x * base + 1.0) / Math.log(base + 1.0) else x

    /**
     * The luminance row of darktable's linear Rec 2020 working profile, the profile the pipe uses when the
     * photo's own is unknown (brief P5c; src/common/iop_profile.c dt_ioppr_set_pipe_work_profile_info falls back to
     * DT_COLORSPACE_LIN_REC2020). It is the Y row of that profile's matrix_in, which darktable reads from the profile
     * `_colorspaces_create_linear_rec2020_rgb_profile` (src/common/colorspaces.c: Rec2020_Primaries at D65, adapted to
     * D50 by lcms), and not the rounded ITU coefficients (0.2627, 0.6780, 0.0593) of Rec 2020 at D65.
     */
    internal val REC2020_Y = listOf(0.2790405, 0.6753473, 0.0456121)

    private fun paramString(params: kotlinx.serialization.json.JsonObject, name: String): String? =
        (params.paramAt(name) as? kotlinx.serialization.json.JsonPrimitive)?.content
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Brief P2j: the arithmetic of desktop callbacks that write parameters from an interface-only slider, in
 * single precision as upstream computes it. Each is named in an override file (`computed`, `formula`).
 */
object Formulas {
    /** The formula names an override may use, with the roles (one per slider) each formula has. */
    val roles: Map<String, Set<String>> = mapOf(
        "illuminant_hue_chroma" to setOf("hue", "chroma"),
        "protect_center_width" to setOf("center", "width"),
        "sqrt2_log" to setOf("value"),
    )

    /** The model of one slider: [formula] with [role], parameters by the formula's own names, partner control ids. */
    fun slider(controlId: Int, formula: String, role: String, params: Map<String, String>, partners: Map<String, Int>): ComputedSlider =
        when (formula) {
            "illuminant_hue_chroma" -> IlluminantHueChroma(controlId, role, params.getValue("x"), params.getValue("y"),
                params.getValue("temperature"), partners["hue"], params["temperature_min"]?.toFloat() ?: 1667f,
                params["temperature_max"]?.toFloat() ?: 25000f)
            "protect_center_width" -> ProtectCenterWidth(controlId, role, params.getValue("from"), params.getValue("to"))
            "sqrt2_log" -> Sqrt2Log(controlId, params.getValue("value"))
            else -> throw IllegalArgumentException("unknown formula $formula")
        }

    // ------------------------------------------------------------------ color calibration (channelmixerrgb.c)

    /** D50 white in u'v' (`dt_xyY_to_Luv`, common/colorspaces_inline_conversions.h). */
    private const val D50_U = 0.20915914598542354f
    private const val D50_V = 0.488075320769787f
    private const val TWO_PI = 6.2831855f // DT_2PI_F

    private fun cbf(x: Float) = x * x * x

    /** `dt_xyY_to_Lch` for Y = 1: [L, chroma, hue in radians 0..2π]. */
    fun xyToLch(x: Float, y: Float): FloatArray {
        val den = -2f * x + 12f * y + 3f
        val u = 4f * x / den
        val v = 9f * y / den
        val yy = 1f
        val threshold = cbf(6f / 29f)
        val l = if (yy <= threshold) cbf(29f / 3f) * yy else 116f * cbrt(yy) - 16f
        val us = 13f * l * (u - D50_U)
        val vs = 13f * l * (v - D50_V)
        val c = hypot(vs, us)
        var h = atan2(vs, us)
        if (h < 0f) h += TWO_PI
        return floatArrayOf(l, c, h)
    }

    /** `dt_Lch_to_xyY`: chromaticity x, y of L, chroma, hue (radians). */
    fun lchToXy(l: Float, c: Float, h: Float): FloatArray {
        val us = c * cos(h)
        val vs = c * sin(h)
        val threshold = 8f
        @Suppress("UNUSED_VARIABLE") val yy = if (l <= threshold) l * cbf(3f / 29f) else cbf((l + 16f) / 116f)
        val u = us / (l * 13f) + D50_U
        val v = vs / (l * 13f) + D50_V
        val den = 6f * u - 16f * v + 12f
        return floatArrayOf(9f * u / den, 4f * v / den)
    }

    /** `xy_to_CCT` (common/illuminants.h), McCamy-type fit valid from 3000 K. */
    fun xyToCct(x: Float, y: Float): Float {
        val n = (x - 0.3366f) / (y - 0.1735f)
        return (-949.86315f + 6253.80338f * exp(-n / 0.92159f) + 28.70599f * exp(-n / 0.20039f) + 0.00004f * exp(-n / 0.07125f))
    }

    private fun cctToXyDaylight(t: Float): FloatArray {
        var x = 0f
        if (t >= 4000f && t <= 7000f) x = ((-4.6070e9f / t + 2.9678e6f) / t + 0.09911e3f) / t + 0.244063f
        else if (t > 7000f && t <= 25000f) x = ((-2.0064e9f / t + 1.9018e6f) / t + 0.24748e3f) / t + 0.237040f
        val y = (-3f * x + 2.87f) * x - 0.275f
        return floatArrayOf(x, y)
    }

    private fun cctToXyBlackbody(t: Float): FloatArray {
        var x = 0f
        var y = 0f
        if (t >= 1667f && t <= 4000f) x = ((-0.2661239e9f / t - 0.2343589e6f) / t + 0.8776956e3f) / t + 0.179910f
        else if (t > 4000f && t <= 25000f) x = ((-3.0258469e9f / t + 2.1070379e6f) / t + 0.2226347e3f) / t + 0.240390f
        if (t >= 1667f && t <= 2222f) y = ((-1.1063814f * x - 1.34811020f) * x + 2.18555832f) * x - 0.20219683f
        else if (t > 2222f && t <= 4000f) y = ((-0.9549476f * x - 1.37418593f) * x + 2.09137015f) * x - 0.16748867f
        else if (t > 4000f && t <= 25000f) y = ((3.0817580f * x - 5.87338670f) * x + 3.75112997f) * x - 0.37001483f
        return floatArrayOf(x, y)
    }

    /** `CCT_reverse_lookup` (common/illuminants.h): the nearest temperature on the daylight/black body locus. */
    fun cctReverseLookup(x: Float, y: Float): Float {
        val tMin = 1667f
        val tMax = 25000f
        val range = tMax - tMin
        val samples = 1 shl 16
        var best = Float.MAX_VALUE
        var bestT = 0f
        for (i in 0 until samples) {
            val step = (i.toFloat() / (samples - 1).toFloat()).pow(4f)
            val t = tMin + step * range
            val xy = if (t >= 4000f) cctToXyDaylight(t) else cctToXyBlackbody(t)
            val dx = xy[0] - x
            val dy = xy[1] - y
            val r = sqrt(dx * dx + dy * dy)
            if (r < best) { best = r; bestT = t }
        }
        return bestT
    }

    /**
     * Color calibration's "hue" and "chroma" of a custom illuminant (`channelmixerrgb.c`: `_illum_xy_callback`
     * writes x, y and temperature; `gui_changed` shows them, the hue only where chroma is above 0).
     */
    class IlluminantHueChroma(
        override val controlId: Int,
        val role: String,
        val x: String,
        val y: String,
        val temperature: String,
        /** the hue slider's id, for the chroma slider to keep the hue the user set at chroma 0 */
        val hueControl: Int?,
        val tMin: Float,
        val tMax: Float,
    ) : ComputedSlider {
        private fun lch(p: JsonObject): FloatArray? {
            val px = p.num(x)?.toFloat() ?: return null
            val py = p.num(y)?.toFloat() ?: return null
            return xyToLch(px, py)
        }

        override fun value(p: JsonObject, recall: (Int) -> Double?): Double? {
            val l = lch(p) ?: return null
            return if (role == "hue") (if (l[1] > 0f) Math.toDegrees(l[2].toDouble()).toFloat().toDouble() else null) else l[1].toDouble()
        }

        override fun write(v: Double, p: JsonObject, recall: (Int) -> Double?): Map<String, JsonPrimitive> {
            val l = lch(p) ?: floatArrayOf(100f, 0f, 0f)
            val hueDeg = if (role == "hue") v.toFloat()
                else if (l[1] > 0f) Math.toDegrees(l[2].toDouble()).toFloat()
                else (hueControl?.let(recall) ?: 0.0).toFloat()
            val chroma = if (role == "chroma") v.toFloat() else l[1]
            val xy = lchToXy(100f, chroma, Math.toRadians(hueDeg.toDouble()).toFloat())
            var t = xyToCct(xy[0], xy[1])
            if (t < 3000f) t = cctReverseLookup(xy[0], xy[1])
            // desktop stores t unclamped; the bridge refuses a value outside the field's limits, and a custom
            // illuminant renders from x and y only (illuminant_to_xy leaves them as they are)
            if (!t.isFinite()) t = tMax
            t = t.coerceIn(tMin, tMax)
            return linkedMapOf(x to f32(xy[0]), y to f32(xy[1]), temperature to f32(t))
        }
    }

    // ------------------------------------------------------------------ saturation curve (satcurvergb.c)

    /**
     * "protect near-neutrals" (centre) and "protection transition width": `protect_from_to_to_center_width`
     * shows them, `_protect_center_width_changed` writes `gf_protect_from` and `gf_protect_to`.
     */
    class ProtectCenterWidth(override val controlId: Int, val role: String, val from: String, val to: String) : ComputedSlider {
        private fun cw(p: JsonObject): Pair<Float, Float>? {
            val f = p.num(from)?.toFloat() ?: return null
            val t = p.num(to)?.toFloat() ?: return null
            return 0.5f * (f + t) to max(0f, t - f)
        }

        override fun value(p: JsonObject, recall: (Int) -> Double?): Double? {
            val (c, w) = cw(p) ?: return null
            return (if (role == "center") c else w).toDouble()
        }

        override fun write(v: Double, p: JsonObject, recall: (Int) -> Double?): Map<String, JsonPrimitive> {
            val (c0, w0) = cw(p) ?: (0f to 0f)
            val center = if (role == "center") v.toFloat() else c0
            val width = if (role == "width") v.toFloat() else w0
            val half = max(width, 0f) * 0.5f
            val f = (center - half).coerceIn(0f, 0.5f)
            var t = (center + half).coerceIn(0f, 0.5f)
            if (t < f) t = f
            return linkedMapOf(from to f32(f), to to f32(t))
        }
    }

    // ------------------------------------------------------------------ tone equalizer (toneequal.c)

    private const val SQRT2 = 1.4142135f // M_SQRT2_F

    /** "curve smoothing": `smoothing_callback` writes sqrt(2)^(1 + v); `gui_update` shows log(s)/log(sqrt(2)) - 1. */
    class Sqrt2Log(override val controlId: Int, val param: String) : ComputedSlider {
        override fun value(p: JsonObject, recall: (Int) -> Double?): Double? {
            val s = p.num(param)?.toFloat() ?: return null
            return (ln(s) / ln(SQRT2) - 1f).toDouble()
        }

        override fun write(v: Double, p: JsonObject, recall: (Int) -> Double?) =
            linkedMapOf(param to f32(SQRT2.pow(1f + v.toFloat())))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import app.dtandroid.engine.api.paramAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Brief P3e: the tone equalizer's curve and its interactive edit, ported from darktable's toneequal.c so that the
 * graph and the drag on the photo move the nine band values as desktop does (`set_new_params_interactive`,
 * `pixel_correction`, `build_interpolation_matrix`, `pseudo_solve` in choleski.h). Gains are in EV (the parameters),
 * factors are linear (2^gain).
 */
object ToneEqMath {
    /** The nine band parameters, from -8 EV (`noise`, "blacks" on the simple tab) to 0 EV (`speculars`). */
    val BANDS = listOf("noise", "ultra_deep_blacks", "deep_blacks", "blacks", "shadows", "midtones", "highlights", "whites", "speculars")
    const val CHANNELS = 9
    private const val PIXEL_CHAN = 8
    private const val MIN_EV = -8.0

    /** The graph spans 4 EV vertically (+2 at the top, -2 at the bottom). */
    const val SPAN_EV = 4.0

    private val centersOps = DoubleArray(PIXEL_CHAN) { (it - 7) * 8.0 / 7.0 }
    private val centersParams = DoubleArray(CHANNELS) { it - 8.0 }

    private fun gaussDenom(sigma: Double) = 2.0 * sigma * sigma
    private fun gauss(radius: Double, denom: Double) = exp(-radius * radius / denom)

    fun read(params: JsonObject): DoubleArray = DoubleArray(CHANNELS) { (params.paramAt(BANDS[it]) as? JsonPrimitive)?.doubleOrNull ?: 0.0 }

    fun sigmaOf(params: JsonObject): Double = ((params.paramAt("smoothing") as? JsonPrimitive)?.doubleOrNull ?: sqrt(2.0)).takeIf { it > 0.0 } ?: sqrt(2.0)

    /** `pixel_correction`: the linear correction at [ev] for the eight weights [factors], clamped to 2^-2..2^2. */
    fun correction(ev: Double, factors: DoubleArray, sigma: Double): Double {
        val d = gaussDenom(sigma)
        val e = ev.coerceIn(MIN_EV, 0.0)
        var r = 0.0
        for (i in 0 until PIXEL_CHAN) r += gauss(e - centersOps[i], d) * factors[i]
        return r.coerceIn(0.25, 4.0)
    }

    /** `pseudo_solve` on the (9 x 8) matrix of `build_interpolation_matrix`: the weights whose curve fits [gainsFactors]; null when singular. */
    fun weights(gainsFactors: DoubleArray, sigma: Double): DoubleArray? {
        val d = gaussDenom(sigma)
        val a = Array(CHANNELS) { i -> DoubleArray(PIXEL_CHAN) { j -> gauss(centersParams[i] - centersOps[j], d) } }
        // normal equations (A' A) x = A' y, solved with partial pivoting
        val m = Array(PIXEL_CHAN) { DoubleArray(PIXEL_CHAN + 1) }
        for (r in 0 until PIXEL_CHAN) {
            for (c in 0 until PIXEL_CHAN) { var s = 0.0; for (i in 0 until CHANNELS) s += a[i][r] * a[i][c]; m[r][c] = s }
            var s = 0.0; for (i in 0 until CHANNELS) s += a[i][r] * gainsFactors[i]; m[r][PIXEL_CHAN] = s
        }
        for (col in 0 until PIXEL_CHAN) {
            var piv = col
            for (r in col + 1 until PIXEL_CHAN) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in col + 1 until PIXEL_CHAN) {
                val f = m[r][col] / m[col][col]
                for (c in col until PIXEL_CHAN + 1) m[r][c] -= f * m[col][c]
            }
        }
        val x = DoubleArray(PIXEL_CHAN)
        for (r in PIXEL_CHAN - 1 downTo 0) {
            var s = m[r][PIXEL_CHAN]
            for (c in r + 1 until PIXEL_CHAN) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return if (x.all { it.isFinite() }) x else null
    }

    /** The curve the module applies, in EV, at [ev] (-8..0), for the nine band values [gains]; the graph draws this. */
    fun curveEv(gains: DoubleArray, sigma: Double, ev: Double): Double {
        val w = weights(DoubleArray(CHANNELS) { 2.0.pow(gains[it]) }, sigma) ?: return 0.0
        return ln(correction(ev, w, sigma)) / ln(2.0)
    }

    /** The curve sampled at [samples] points from -8 to 0 EV: the weights are solved once. */
    fun curve(gains: DoubleArray, sigma: Double, samples: Int): DoubleArray {
        val w = weights(DoubleArray(CHANNELS) { 2.0.pow(gains[it]) }, sigma) ?: return DoubleArray(samples)
        return DoubleArray(samples) { k -> ln(correction(MIN_EV + 8.0 * k / (samples - 1), w, sigma)) / ln(2.0) }
    }

    /**
     * `set_new_params_interactive`: raise the band values by [offsetEv] around [controlEv], weighted by a gaussian
     * of the distance (width [blendSigma], desktop passes `smoothing^2 / 2`); then fit the curve again and read the
     * nine values back from it. Null when the fit is not possible (desktop: "the interpolation is unstable").
     */
    fun interactive(gains: DoubleArray, controlEv: Double, offsetEv: Double, sigma: Double, blendSigma: Double = sigma * sigma / 2.0): DoubleArray? {
        val std = gaussDenom(blendSigma)
        val factors = DoubleArray(CHANNELS) { i -> 2.0.pow(gains[i]) * 2.0.pow(gauss(centersParams[i] - controlEv, std) * offsetEv) }
        val w = weights(factors, sigma) ?: return null
        return DoubleArray(CHANNELS) { i -> ln(correction(centersParams[i], w, sigma)) / ln(2.0) }
    }

    /** The values to write for [gains] (the nine band parameters). */
    fun writes(gains: DoubleArray): Map<String, JsonPrimitive> = BANDS.indices.associate { BANDS[it] to JsonPrimitive(gains[it]) }

    /**
     * The luminance of one pixel as the module's mask estimator gives it (`luminance_mask.h`), with the exposure and contrast
     * boosts the module applies; [method] is the `method` parameter (enum name or number), [details] the `details` parameter.
     */
    fun luminance(rgb: List<Double?>, params: JsonObject): Double {
        val r = rgb.getOrNull(0) ?: 0.0
        val g = rgb.getOrNull(1) ?: 0.0
        val b = rgb.getOrNull(2) ?: 0.0
        val method = enumIndex(params, "method", METHODS, 4)
        val details = enumIndex(params, "details", DETAILS, 4)
        val exposureBoost = 2.0.pow((params.paramAt("exposure_boost") as? JsonPrimitive)?.doubleOrNull ?: 0.0)
        val boost = details == 2 || details == 4
        val fulcrum = if (boost) 2.0.pow(-4.0) else 0.0
        val contrast = if (boost) 2.0.pow((params.paramAt("contrast_boost") as? JsonPrimitive)?.doubleOrNull ?: 0.0) else 1.0
        val raw = when (method) {
            0 -> (r + g + b) / 3.0
            1 -> (max(max(r, g), b) + min(min(r, g), b)) / 2.0
            2 -> max(max(r, g), b)
            3 -> abs(r) + abs(g) + abs(b)
            4 -> sqrt(r * r + g * g + b * b)
            5 -> { val den = r * r + g * g + b * b; if (den == 0.0) 0.0 else (abs(r).pow(3) + abs(g).pow(3) + abs(b).pow(3)) / den }
            else -> (abs(r) * abs(g) * abs(b)).pow(1.0 / 3.0)
        }
        return max((exposureBoost * raw - fulcrum) * contrast + fulcrum, 2.0.pow(-16.0))
    }

    /** The exposure (EV, -8..0) of a pixel, as the band it belongs to. */
    fun exposureOf(rgb: List<Double?>, params: JsonObject): Double = (ln(luminance(rgb, params)) / ln(2.0)).coerceIn(MIN_EV, 0.0)

    private val METHODS = listOf("DT_TONEEQ_MEAN", "DT_TONEEQ_LIGHTNESS", "DT_TONEEQ_VALUE", "DT_TONEEQ_NORM_1", "DT_TONEEQ_NORM_2", "DT_TONEEQ_NORM_POWER", "DT_TONEEQ_GEOMEAN")
    private val DETAILS = listOf("DT_TONEEQ_NONE", "DT_TONEEQ_AVG_GUIDED", "DT_TONEEQ_GUIDED", "DT_TONEEQ_AVG_EIGF", "DT_TONEEQ_EIGF")

    private fun enumIndex(params: JsonObject, name: String, names: List<String>, default: Int): Int {
        val p = params.paramAt(name) as? JsonPrimitive ?: return default
        val s = p.contentOrNull ?: return default
        val i = names.indexOf(s)
        return if (i >= 0) i else (s.toDoubleOrNull()?.toInt() ?: default)
    }
}

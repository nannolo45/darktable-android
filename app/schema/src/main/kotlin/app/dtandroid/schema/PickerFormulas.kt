// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.engine.api.PickStats
import app.dtandroid.engine.api.paramAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Brief P3c deliverable 3: pickers that only set sliders from the picked values (override `picker` with
 * `set_slider`), without a module action. Each formula repeats one branch of a module's `color_picker_apply` on the
 * statistics `color_pick` gives for the module's input, in the module's picker colour space (its working RGB for the
 * modules here), and on the instance's parameters; [roles] names the parameters a formula reads and writes.
 *
 * Covered: filmic rgb's four pickers (src/iop/filmicrgb.c `apply_auto_grey`, `apply_auto_black`,
 * `apply_auto_white_point_source`, `apply_autotune`, with `get_pixel_norm` and `_compute_output_power`), and color
 * balance rgb's six (src/iop/colorbalancergb.c `color_picker_apply` with `pipe_RGB_to_Ych`: the hue of offset, lift,
 * power and gain with its chroma, white fulcrum, contrast gray fulcrum). tools/p3c-picker-reference.py compiles
 * those functions verbatim; P3cPickerTest compares.
 *
 * Working profile: the picked numbers are the module's input in the pipe's working RGB; the formulas that need its
 * matrix take linear Rec2020's ([REC2020], darktable's default working profile). With another working profile
 * (input colour profile's "working profile") those pickers give slightly different values than desktop.
 */
object PickerFormulas {
    private val filmicRoles = setOf("grey", "black", "white", "power", "security", "target", "norm", "custom_grey")

    val roles: Map<String, Set<String>> = mapOf(
        "filmic_grey" to filmicRoles,
        "filmic_black" to filmicRoles,
        "filmic_white" to filmicRoles,
        "filmic_autotune" to filmicRoles,
        "colorbalance_hue" to setOf("hue", "chroma"),
        "colorbalance_white_fulcrum" to setOf("white_fulcrum"),
        "colorbalance_grey_fulcrum" to setOf("grey_fulcrum"),
    )

    /**
     * The parameters [spec]'s formula writes for the picked [stats] and the instance's [params], by parameter name
     * (only the ones that change); empty when the pick gives nothing. [limits]: a parameter's (min, max).
     */
    fun apply(spec: PickerSlider, stats: PickStats?, params: JsonObject, limits: (String) -> Pair<Double, Double>?): Map<String, JsonPrimitive> {
        stats ?: return emptyMap()
        val mean = stats.mean.take(3).filterNotNull()
        val mn = stats.min.take(3).filterNotNull()
        val mx = stats.max.take(3).filterNotNull()
        if (mean.size < 3 || mn.size < 3 || mx.size < 3) return emptyMap()
        // desktop: "if(self->picked_color_max[0] < self->picked_color_min[0]) return;" (no sample)
        if (mx[0] < mn[0]) return emptyMap()
        return when (spec.formula) {
            "filmic_grey", "filmic_black", "filmic_white", "filmic_autotune" -> filmic(spec, params, limits, mean, mn, mx)
            "colorbalance_hue", "colorbalance_white_fulcrum", "colorbalance_grey_fulcrum" -> colorBalance(spec, limits, mean, mx)
            else -> emptyMap()
        }
    }

    // ------------------------------------------------------------------ filmic rgb

    /** filmicrgb.c dt_iop_filmicrgb_methods_type_t, by constant name */
    private const val MAX_RGB = "DT_FILMIC_METHOD_MAX_RGB"

    /**
     * filmicrgb.c `get_pixel_norm`. "luminance Y" (and the default branch) weighs R, G, B by the working profile's
     * luminance row; the app takes linear Rec2020's (darktable's default working profile, D50): with another working
     * profile that norm differs slightly from desktop's.
     */
    fun filmicNorm(px: List<Double>, method: String): Double = when (method) {
        MAX_RGB -> maxOf(px[0], px[1], px[2])
        "DT_FILMIC_METHOD_POWER_NORM" -> {
            // pixel_rgb_norm_power: (R^3 + G^3 + B^3) / (R^2 + G^2 + B^2) of the absolute values
            var num = 0.0
            var den = 0.0
            for (c in 0..2) {
                val v = abs(px[c])
                num += v * v * v
                den += v * v
            }
            num / max(den, 1e-12)
        }
        "DT_FILMIC_METHOD_EUCLIDEAN_NORM_V1" -> sqrt(px[0] * px[0] + px[1] * px[1] + px[2] * px[2])
        "DT_FILMIC_METHOD_EUCLIDEAN_NORM_V2" -> sqrt(px[0] * px[0] + px[1] * px[1] + px[2] * px[2]) * 0.5773502691896258
        else -> REC2020_Y[0] * px[0] + REC2020_Y[1] * px[1] + REC2020_Y[2] * px[2]
    }

    /**
     * linear Rec2020 to XYZ (D50), row-major: Rec2020's primaries and darktable's D65 white adapted by Bradford to
     * D50, as LittleCMS builds darktable's "linear Rec2020 RGB" profile (colorspaces.c), quantised to the profile's
     * 16.16 numbers as darktable reads them back
     */
    val REC2020 = doubleArrayOf(
        0.6734771729, 0.1656799316, 0.1250457764,
        0.2790374756, 0.6753540039, 0.0456085205,
        -0.0019378662, 0.0299835205, 0.7968444824,
    )

    /** linear Rec2020's luminance row (D50) */
    val REC2020_Y = doubleArrayOf(REC2020[3], REC2020[4], REC2020[5])

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun filmic(spec: PickerSlider, p: JsonObject, limits: (String) -> Pair<Double, Double>?,
                       mean: List<Double>, mn: List<Double>, mx: List<Double>): Map<String, JsonPrimitive> {
        val w = spec.writes
        fun num(role: String) = (p.paramAt(w.getValue(role)) as? JsonPrimitive)?.doubleOrNull
        fun text(role: String) = (p.paramAt(w.getValue(role)) as? JsonPrimitive)?.content
        var grey = num("grey") ?: return emptyMap()
        var black = num("black") ?: return emptyMap()
        var white = num("white") ?: return emptyMap()
        val security = num("security") ?: 0.0
        val target = num("target") ?: 18.45
        val norm = text("norm") ?: "DT_FILMIC_METHOD_POWER_NORM"
        val customGrey = (p.paramAt(w.getValue("custom_grey")) as? JsonPrimitive)?.booleanOrNull ?: false
        fun autoGrey(shift: Boolean) {
            val g = filmicNorm(mean, norm) / 2.0
            val prev = grey
            grey = (100.0 * g).coerceIn(0.001, 100.0)
            if (shift) {
                val v = log2(prev / grey)
                black -= v
                white += v
            }
        }
        fun autoBlack() {
            val b = filmicNorm(mn, MAX_RGB)
            var ev = log2(b / (grey / 100.0)).coerceIn(-16.0, -1.0)
            ev *= 1.0 + security / 100.0
            black = max(ev, -16.0)
        }
        fun autoWhite() {
            val wv = filmicNorm(mx, MAX_RGB)
            var ev = log2(wv / (grey / 100.0)).coerceIn(1.0, 16.0)
            ev *= 1.0 + security / 100.0
            white = ev
        }
        when (spec.formula) {
            "filmic_grey" -> autoGrey(shift = true)
            "filmic_black" -> autoBlack()
            "filmic_white" -> autoWhite()
            "filmic_autotune" -> { if (customGrey) autoGrey(shift = false); autoWhite(); autoBlack() }
        }
        // _compute_output_power: the power is clamped to its field's limits
        val (pmin, pmax) = limits(w.getValue("power")) ?: (1.0 to 10.0)
        val power = (ln(target / 100.0) / ln(-black / (white - black))).let { if (it.isNaN()) pmin else it.coerceIn(pmin, pmax) }
        val out = LinkedHashMap<String, JsonPrimitive>()
        fun put(role: String, v: Double) { if (num(role) != v) out[w.getValue(role)] = JsonPrimitive(v) }
        put("grey", grey); put("black", black); put("white", white); put("power", power)
        return out
    }

    // ------------------------------------------------------------------ color balance rgb

    /** common/chromatic_adaptation.h XYZ_D50_to_D65_CAT16_trans */
    private val XYZ_D50_TO_D65_CAT16_T = arrayOf(
        doubleArrayOf(9.89466254e-01, -5.40518733e-03, -4.03920992e-04),
        doubleArrayOf(-4.00304626e-02, 1.00666069e+00, 1.50768030e-02),
        doubleArrayOf(4.40530317e-02, -1.75551955e-03, 1.30210211e+00),
    )

    /** common/colorspaces_inline_conversions.h XYZ_D65_to_LMS_2006_D65_trans */
    private val XYZ_D65_TO_LMS_2006_T = arrayOf(
        doubleArrayOf(0.257085, -0.394427, 0.064856),
        doubleArrayOf(0.859943, 1.175800, -0.076250),
        doubleArrayOf(-0.031061, 0.106423, 0.559067),
    )

    /** common/colorspaces_inline_conversions.h LMS_D65_to_filmlightRGB_D65_trans */
    private val LMS_TO_FILMLIGHT_T = arrayOf(
        doubleArrayOf(1.08771930, -0.0877193, 0.0),
        doubleArrayOf(-0.66666667, 1.66666667, 0.0),
        doubleArrayOf(0.02061856, -0.05154639, 1.03092784),
    )

    /** dt_apply_transposed_color_matrix: out[r] = m[0][r] in[0] + m[1][r] in[1] + m[2][r] in[2] */
    private fun applyT(v: DoubleArray, m: Array<DoubleArray>) = DoubleArray(3) { r -> m[0][r] * v[0] + m[1][r] * v[1] + m[2][r] * v[2] }

    /**
     * colorbalancergb.c `pipe_RGB_to_Ych` with the working profile linear Rec2020: XYZ (D50), CAT16 to D65, CIE 2006
     * LMS, Filmlight Yrg (`LMS_to_Yrg`), polar (`Yrg_to_Ych`): Y, c, cos h, sin h.
     */
    fun rgbToYch(rgb: List<Double>): DoubleArray {
        val xyz50 = DoubleArray(3) { r -> REC2020[3 * r] * rgb[0] + REC2020[3 * r + 1] * rgb[1] + REC2020[3 * r + 2] * rgb[2] }
        val xyz65 = applyT(xyz50, XYZ_D50_TO_D65_CAT16_T)
        val lms = applyT(xyz65, XYZ_D65_TO_LMS_2006_T)
        val y = 0.68990272 * lms[0] + 0.34832189 * lms[1]
        val a = lms[0] + lms[1] + lms[2]
        val n = DoubleArray(3) { if (a == 0.0) 0.0 else lms[it] / a }
        val f = applyT(n, LMS_TO_FILMLIGHT_T)
        val r = f[0] - 0.21902143
        val g = f[1] - 0.54371398
        val c = kotlin.math.hypot(g, r)
        return doubleArrayOf(y, c, if (c != 0.0) r / c else 1.0, if (c != 0.0) g / c else 0.0)
    }

    private fun colorBalance(spec: PickerSlider, limits: (String) -> Pair<Double, Double>?, mean: List<Double>, mx: List<Double>): Map<String, JsonPrimitive> {
        val w = spec.writes
        fun limited(param: String, v: Double) = limits(param)?.let { (lo, hi) -> v.coerceIn(lo, hi) } ?: v
        return when (spec.formula) {
            "colorbalance_hue" -> {
                val ych = rgbToYch(mean)
                // YRG_RAD_TO_CONVENTIONAL_DEG(h) + 180: the opponent colour (desktop takes the colour itself with ctrl)
                val hue = Math.toDegrees(kotlin.math.atan2(ych[3], ych[2])) + 30.0 + 180.0
                // desktop writes the angle as it comes (up to 390) and the chroma unbounded; the bridge checks the
                // fields' limits, so the angle is written within 0..360 (the same hue) and the chroma within its limits
                val wrapped = ((hue % 360.0) + 360.0) % 360.0
                mapOf(w.getValue("hue") to JsonPrimitive(wrapped), w.getValue("chroma") to JsonPrimitive(limited(w.getValue("chroma"), ych[1] * ych[0])))
            }
            "colorbalance_white_fulcrum" -> mapOf(w.getValue("white_fulcrum") to JsonPrimitive(limited(w.getValue("white_fulcrum"), log2(rgbToYch(mx)[0]))))
            "colorbalance_grey_fulcrum" -> mapOf(w.getValue("grey_fulcrum") to JsonPrimitive(limited(w.getValue("grey_fulcrum"), rgbToYch(mean)[0])))
            else -> emptyMap()
        }
    }
}

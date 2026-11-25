// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import app.dtandroid.engine.api.PickResult
import app.dtandroid.engine.api.PickStats
import app.dtandroid.engine.api.PickWork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

/**
 * Brief P4c item 2: the picker's mark on rgb curve uses the photo's working profile (desktop's picker_scale with
 * dt_ioppr_get_pipe_work_profile_info), and the mark follows rgb curve's zoom/offset (`_curve_to_mouse`) and tone
 * curve's "scale for graph" (`to_log`). Expected numbers are computed here from upstream's formulas at the pinned
 * commit (rgbcurve.c picker_scale, iop_profile.h dt_ioppr_get_rgb_matrix_luminance and
 * dt_ioppr_compensate_middle_grey, colorspaces_inline_conversions.h dt_XYZ_to_Lab, tonecurve.c to_log).
 */
class P4cCurvePickTest {
    private fun rgbParams(independent: Boolean, grey: Boolean = false): JsonObject = buildJsonObject {
        put("curve_autoscale", JsonPrimitive(if (independent) 1L else 0L))
        put("compensate_middle_grey", JsonPrimitive(if (grey) 1L else 0L))
    }

    /** linear Rec 709 (D50 adapted): darktable's matrix_in luminance row for that working profile */
    private val rec709 = listOf(0.2225045, 0.7168786, 0.0606169)

    /** dt_XYZ_to_Lab's L / 100 of Y (white Y = 1) */
    private fun labL(y: Double): Double {
        val eps = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        val f = if (y > eps) Math.cbrt(y) else (kappa * y + 16.0) / 116.0
        return (116.0 * f - 16.0) / 100.0
    }

    private fun work(lum: List<Double>, greyLum: List<Double> = lum.map { labL(it) }, grey: List<List<Double>> = List(3) { listOf(0.0, 0.0, 0.0) }) =
        PickWork(0, "", false, rec709, lum, greyLum, grey)

    private fun stats(mean: List<Double>, min: List<Double> = mean, max: List<Double> = mean, w: PickWork? = null) =
        PickStats("rgb", mean, min, max, listOf(0, 0, 1, 1), w)

    @Test fun linkedChannelsUseTheWorkingProfilesLuminanceFormula() {
        // rgb (0.2, 0.5, 0.1) in linear Rec 709: Y = 0.2225045*0.2 + 0.7168786*0.5 + 0.0606169*0.1
        val y = 0.2225045 * 0.2 + 0.7168786 * 0.5 + 0.0606169 * 0.1
        assertEquals(0.40900189, y, 1e-8)
        assertEquals(y, CurvePick.pickerScale(listOf(0.2, 0.5, 0.1), rec709, manual = false, grey = false)[0], 1e-12)
        // with "compensate middle grey": L/100 of that luminance (0.70105936)
        assertEquals(0.7010593642926753, CurvePick.pickerScale(listOf(0.2, 0.5, 0.1), rec709, manual = false, grey = true)[0], 1e-9)
        // below the Lab threshold: kappa * Y / 100
        assertEquals(0.03613185185185184, CurvePick.middleGrey(0.004), 1e-12)
        // independent channels with the grey: each channel through it; clipped to 0..1
        val each = CurvePick.pickerScale(listOf(0.2, 0.5, 1.5), rec709, manual = true, grey = true)
        listOf(labL(0.2), labL(0.5), 1.0).forEachIndexed { k, v -> assertEquals(v, each[k], 1e-12) }
        // and Rec 2020 gives another number than Rec 709 (the fixed coefficients before P4c)
        val r2020 = CurvePick.pickerScale(listOf(0.2, 0.5, 0.1), listOf(0.2627, 0.6780, 0.0593), manual = false, grey = false)[0]
        assertEquals(0.2627 * 0.2 + 0.6780 * 0.5 + 0.0593 * 0.1, r2020, 1e-12)
        assertEquals(0.39747, r2020, 1e-5)
    }

    @Test fun theMarkUsesTheEnginesWorkingProfileValues() {
        // the engine sent the luminance of mean, min and max in the photo's working profile (not Rec 2020)
        val i = stats(listOf(0.2, 0.5, 0.1), listOf(0.1, 0.2, 0.0), listOf(0.4, 0.8, 0.3), work(listOf(0.409, 0.16, 0.68)))
        val o = stats(listOf(0.3, 0.6, 0.2), w = work(listOf(0.5, 0.5, 0.5)))
        val m = CurvePick.mark("rgbcurve", 0, rgbParams(independent = false), i, o)!!
        assertEquals(0.409, m.mean, 1e-12); assertEquals(0.16, m.min, 1e-12); assertEquals(0.68, m.max, 1e-12)
        assertEquals("%.1f → %.1f".format(0.409 * 255, 0.5 * 255), m.text)
        // compensate middle grey: the engine's grey_luminance
        val g = CurvePick.mark("rgbcurve", 0, rgbParams(independent = false, grey = true), i, null)!!
        assertEquals(labL(0.409), g.mean, 1e-12)
        // independent channels with the grey: the engine's per-channel values; without it the channel itself
        val grey = listOf(listOf(0.11, 0.22, 0.33), listOf(0.01, 0.02, 0.03), listOf(0.5, 0.6, 0.7))
        val ig = stats(listOf(0.2, 0.5, 0.1), w = work(listOf(0.4, 0.4, 0.4), grey = grey))
        val c = CurvePick.mark("rgbcurve", 2, rgbParams(independent = true, grey = true), ig, null)!!
        assertEquals(0.33, c.mean, 1e-12); assertEquals(0.03, c.min, 1e-12); assertEquals(0.7, c.max, 1e-12)
        assertEquals(0.1, CurvePick.mark("rgbcurve", 2, rgbParams(independent = true), ig, null)!!.mean, 1e-12)
    }

    @Test fun withoutTheEnginesValuesTheDefaultWorkingProfileIsUsed() {
        val m = CurvePick.mark("rgbcurve", 0, rgbParams(independent = false), stats(listOf(0.2, 0.5, 0.1)), null)!!
        assertEquals(0.2627 * 0.2 + 0.6780 * 0.5 + 0.0593 * 0.1, m.mean, 1e-12)
    }

    @Test fun rgbCurvesZoomAndOffsetMoveTheMark() {
        // _curve_to_mouse: (x - offset) * zoom
        assertEquals(0.5, CurvePick.curveToMouse(0.5, 2.0, 0.25), 1e-12)
        val i = stats(listOf(0.5, 0.5, 0.5), listOf(0.3, 0.3, 0.3), listOf(0.6, 0.6, 0.6), work(listOf(0.5, 0.3, 0.6)))
        val m = CurvePick.mark("rgbcurve", 0, rgbParams(independent = false), i, null, CurveView(zoom = 2.0, offsetX = 0.25))!!
        assertEquals(0.5, m.mean, 1e-12); assertEquals(0.1, m.min, 1e-12); assertEquals(0.7, m.max, 1e-12)
        // the identity view (the app's graphs) leaves it where it was
        assertEquals(0.5, CurvePick.mark("rgbcurve", 0, rgbParams(independent = false), i, null)!!.mean, 1e-12)
    }

    @Test fun toneCurvesScaleForGraphLogEncodesLightnessOnly() {
        // to_log(x, base) = ln(x * base + 1) / ln(base + 1); L = 50 -> 0.5, base 10: ln 6 / ln 11
        val lab = PickStats("Lab", listOf(50.0, 0.0, 0.0), listOf(50.0, 0.0, 0.0), listOf(50.0, 0.0, 0.0), listOf(0, 0, 1, 1))
        assertEquals(ln(6.0) / ln(11.0), CurvePick.mark("tonecurve", 0, JsonObject(emptyMap()), lab, null, CurveView(logBase = 10.0))!!.mean, 1e-12)
        assertEquals(0.7472217363092141, CurvePick.toLog(0.5, 10.0, 0, 0), 1e-12)
        // a and b are never log-encoded; semilog -1 keeps the abscissa linear
        assertEquals(0.5, CurvePick.mark("tonecurve", 1, JsonObject(emptyMap()), lab, null, CurveView(logBase = 10.0))!!.mean, 1e-12)
        assertEquals(0.5, CurvePick.toLog(0.5, 10.0, 0, -1), 1e-12)
        assertEquals(0.5, CurvePick.toLog(0.5, 0.0, 0, 0), 1e-12)
    }

    @Test fun theEnginesJsonIsRead() {
        val r = PickResult.fromJson(Json.parseToJsonElement("""
            {"pipe":"preview","input":{"colorspace":"rgb","mean":[0.2,0.5,0.1,1],"min":[0.1,0.2,0,1],"max":[0.4,0.8,0.3,1],"box":[0,0,1,1]},
             "output":null,"work":{"type":5,"filename":"","nonlinear":false,"luminance_coeffs":[0.2225045,0.7168786,0.0606169],
             "input":{"luminance":[0.409,0.16,0.68],"grey_luminance":[0.701,0.47,0.86],"grey":[[0.1,0.2,0.3],[0.4,0.5,0.6],[0.7,0.8,0.9]]},
             "output":null}}"""))
        val w = r.input!!.work!!
        assertEquals(rec709, w.coeffs)
        assertEquals(listOf(0.409, 0.16, 0.68), w.luminance)
        assertEquals(0.6, w.grey[1][2], 0.0)
        assertNull(r.output)
        // written back as the engine writes it
        assertEquals(r, PickResult.fromJson(r.toJson()))
    }
}

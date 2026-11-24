// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import app.dtandroid.engine.api.PickStats
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The picker's mark on the tone curve and rgb curve graphs, against darktable's `picker_scale` at the pinned upstream:
 * tonecurve.c (L/100, a and b as (v+128)/256, each clamped to 0..1) and rgbcurve.c (independent channels: the channel;
 * linked: the working-profile luminance; "compensate middle grey": the Lab lightness / 100 of that grey; every result
 * clipped to 0..1).
 */
class CurvePickTest {
    private fun stats(space: String, mean: List<Double?>, min: List<Double?> = mean, max: List<Double?> = mean) =
        PickStats(space, mean, min, max, listOf(0, 0, 1, 1))

    private fun rgbParams(independent: Boolean, grey: Boolean = false): JsonObject = buildJsonObject {
        put("curve_autoscale", JsonPrimitive(if (independent) 1L else 0L))
        put("compensate_middle_grey", JsonPrimitive(if (grey) 1L else 0L))
    }

    private val none = JsonObject(emptyMap())

    // ------------------------------------------------------------------ tone curve

    @Test fun toneCurveLightnessIsLOver100() {
        val m = CurvePick.mark("tonecurve", 0, none, stats("Lab", listOf(50.0, 0.0, 0.0), listOf(20.0, 0.0, 0.0), listOf(80.0, 0.0, 0.0)),
            stats("Lab", listOf(60.0, 0.0, 0.0)))!!
        assertEquals(0.2, m.min, 1e-12); assertEquals(0.5, m.mean, 1e-12); assertEquals(0.8, m.max, 1e-12)
        assertEquals("50.0 → 60.0", m.text)
    }

    @Test fun toneCurveAAndBAreShiftedBy128AndDividedBy256() {
        val i = stats("Lab", listOf(50.0, 0.0, -128.0), listOf(50.0, -128.0, -128.0), listOf(50.0, 64.0, 127.0))
        val a = CurvePick.mark("tonecurve", 1, none, i, null)!!
        assertEquals(0.0, a.min, 1e-12); assertEquals(0.5, a.mean, 1e-12); assertEquals((64.0 + 128) / 256, a.max, 1e-12)
        val b = CurvePick.mark("tonecurve", 2, none, i, null)!!
        assertEquals(0.0, b.mean, 1e-12); assertEquals(255.0 / 256, b.max, 1e-12)
        assertEquals("no output: the input alone", "-128.0", b.text)
    }

    @Test fun toneCurveValuesOutsideTheRangeAreClamped() {
        val m = CurvePick.mark("tonecurve", 0, none, stats("Lab", listOf(120.0, 0.0, 0.0), listOf(-5.0, 0.0, 0.0), listOf(120.0, 0.0, 0.0)), null)!!
        assertEquals(0.0, m.min, 0.0); assertEquals(1.0, m.mean, 0.0); assertEquals(1.0, m.max, 0.0)
        val ab = CurvePick.mark("tonecurve", 1, none, stats("Lab", listOf(0.0, 200.0, 0.0), listOf(0.0, -200.0, 0.0), listOf(0.0, 200.0, 0.0)), null)!!
        assertEquals(0.0, ab.min, 0.0); assertEquals(1.0, ab.max, 0.0)
    }

    @Test fun noInputNoMarkAndAnUnknownModuleNone() {
        assertNull(CurvePick.mark("tonecurve", 0, none, null, null))
        assertNull(CurvePick.mark("exposure", 0, none, stats("Lab", listOf(1.0, 1.0, 1.0)), null))
    }

    // ------------------------------------------------------------------ rgb curve

    @Test fun rgbCurveIndependentUsesTheChannelItself() {
        val i = stats("RGB", listOf(0.2, 0.4, 0.6), listOf(0.1, 0.3, 0.5), listOf(0.3, 0.5, 0.9))
        val o = stats("RGB", listOf(0.25, 0.45, 0.65))
        for (c in 0..2) {
            val m = CurvePick.mark("rgbcurve", c, rgbParams(independent = true), i, o)!!
            assertEquals(i.min[c]!!, m.min, 1e-12); assertEquals(i.mean[c]!!, m.mean, 1e-12); assertEquals(i.max[c]!!, m.max, 1e-12)
        }
        assertEquals("%.1f → %.1f".format(0.4 * 255, 0.45 * 255), CurvePick.mark("rgbcurve", 1, rgbParams(true), i, o)!!.text)
    }

    @Test fun rgbCurveLinkedUsesTheRec2020Luminance() {
        val i = stats("RGB", listOf(0.5, 0.25, 0.1))
        val m = CurvePick.mark("rgbcurve", 0, rgbParams(independent = false), i, null)!!
        assertEquals(0.2627 * 0.5 + 0.6780 * 0.25 + 0.0593 * 0.1, m.mean, 1e-12)
        // the channel asked for makes no difference in linked mode (desktop writes the luminance into channel 0 only)
        assertEquals(m.mean, CurvePick.mark("rgbcurve", 2, rgbParams(false), i, null)!!.mean, 1e-12)
    }

    @Test fun rgbCurveCompensateMiddleGreyIsTheLabLightnessOver100() {
        // 18.4 % grey is L* = 49.5 ... the formula at both of its branches
        val i = stats("RGB", listOf(0.18, 0.18, 0.18), listOf(0.005, 0.005, 0.005), listOf(1.0, 1.0, 1.0))
        val lin = CurvePick.mark("rgbcurve", 0, rgbParams(false, grey = true), i, null)!!
        val expectedMean = (116.0 * Math.cbrt(0.18) - 16.0) / 100.0
        assertEquals(expectedMean, lin.mean, 1e-6)
        assertEquals("below the cut: the linear branch, 903.3 * Y / 100", 903.2963 * 0.005 / 100.0, lin.min, 1e-4)
        assertEquals(1.0, lin.max, 1e-9)
        // independent channels each get it
        val ind = CurvePick.mark("rgbcurve", 1, rgbParams(true, grey = true), stats("RGB", listOf(0.05, 0.18, 0.9)), null)!!
        assertEquals(expectedMean, ind.mean, 1e-6)
        // off: the value as it is
        assertEquals(0.18, CurvePick.mark("rgbcurve", 1, rgbParams(true, grey = false), i, null)!!.mean, 1e-12)
    }

    @Test fun rgbCurveResultsAreClippedToTheGraph() {
        val m = CurvePick.mark("rgbcurve", 0, rgbParams(true), stats("RGB", listOf(1.7, 0.0, 0.0), listOf(-0.3, 0.0, 0.0), listOf(1.7, 0.0, 0.0)), null)!!
        assertEquals(0.0, m.min, 0.0); assertEquals(1.0, m.mean, 0.0); assertEquals(1.0, m.max, 0.0)
    }

    @Test fun rgbCurveParamsAcceptTheEnumNameToo() {
        val p = buildJsonObject { put("curve_autoscale", JsonPrimitive("DT_S_SCALE_MANUAL_RGB")); put("compensate_middle_grey", JsonPrimitive(true)) }
        val m = CurvePick.mark("rgbcurve", 2, p, stats("RGB", listOf(0.0, 0.0, 0.18)), null)!!
        assertEquals((116.0 * Math.cbrt(0.18) - 16.0) / 100.0, m.mean, 1e-6)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brief P3e (JVM): the curve editor's logic without a screen: node order, adding, removing and limits, how a curve
 * is written (count, nodes, vacated slots), levels as desktop moves them, and the tone equalizer's fit and drag.
 */
class CurveModelTest {
    private fun n(x: Double, y: Double) = CurveNode(x, y)
    private val three = listOf(n(0.0, 0.0), n(0.5, 0.5), n(1.0, 1.0))

    // ------------------------------------------------------------------ order, limits

    @Test fun movingANodeKeepsTheOrderAndTheGraph() {
        val a = CurveEdit.move(three, 1, 1.5, 0.2)
        assertTrue(CurveEdit.valid(a))
        assertEquals(1.0 - CurveEdit.MIN_GAP, a[1].x, 1e-12) // stopped at its right neighbour
        assertEquals(0.2, a[1].y, 1e-12)
        val b = CurveEdit.move(three, 1, -3.0, 5.0)
        assertTrue(CurveEdit.valid(b))
        assertEquals(CurveEdit.MIN_GAP, b[1].x, 1e-12)
        assertEquals(1.0, b[1].y, 1e-12)
        // the end nodes move freely inside the graph
        val c = CurveEdit.move(three, 0, 0.3, 0.1)
        assertEquals(0.3, c[0].x, 1e-12)
        val d = CurveEdit.move(three, 2, 2.0, -1.0)
        assertEquals(1.0, d[2].x, 1e-12)
        assertEquals(0.0, d[2].y, 1e-12)
    }

    @Test fun addingPutsTheNodeInItsPlaceAndRespectsTheLimits() {
        val (a, at) = CurveEdit.add(three, 0.25, 0.4, 20)!!
        assertEquals(1, at)
        assertEquals(listOf(0.0, 0.25, 0.5, 1.0), a.map { it.x })
        assertTrue(CurveEdit.valid(a))
        // too close to a neighbour in x (desktop's 0.025)
        assertNull(CurveEdit.add(three, 0.51, 0.5, 20))
        assertNull(CurveEdit.add(three, 0.02, 0.5, 20))
        // a full curve takes no more
        val full = List(20) { n(it / 19.0, it / 19.0) }
        assertNull(CurveEdit.add(full, 0.001, 0.5, 20))
        val almost = List(19) { n(it / 20.0, it / 20.0) }
        assertNotNull(CurveEdit.add(almost, 0.99, 0.5, 20))
    }

    @Test fun removingTakesAnInteriorNodeAndResetsAnEndNode() {
        assertEquals(listOf(0.0, 1.0), CurveEdit.remove(three, 1).map { it.x })
        val e = CurveEdit.remove(listOf(n(0.2, 0.3), n(0.5, 0.5), n(0.9, 0.8)), 0)
        assertEquals(3, e.size)
        assertEquals(n(0.0, 0.0), e[0])
        val f = CurveEdit.remove(listOf(n(0.0, 0.0), n(0.5, 0.5), n(0.9, 0.8)), 2)
        assertEquals(n(1.0, 1.0), f[2])
        // a curve is never shorter than two nodes
        assertEquals(2, CurveEdit.remove(CurveEdit.remove(three, 1), 0).size)
    }

    @Test fun hitFindsTheNearestNodeInsideTheRadius() {
        assertEquals(1, CurveEdit.hit(three, 0.52, 0.49, 0.05, 0.05))
        assertEquals(-1, CurveEdit.hit(three, 0.3, 0.7, 0.05, 0.05))
        assertEquals(0, CurveEdit.hit(three, 0.0, 0.0, 0.05, 0.05))
    }

    @Test fun anInvalidCurveReadsAsTheDefault() {
        val zero = buildJsonObject {
            put("tonecurve_nodes", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(0))))
        }
        assertEquals(CurveEdit.IDENTITY, CurveLayout.TONECURVE.read(zero, 0))
        assertEquals(3, CurveLayout.TONECURVE.read(zero, 1).size)
        assertEquals(0.5, CurveLayout.TONECURVE.defaults(2)[1].x, 0.0)
        assertEquals(2, CurveLayout.RGBCURVE.defaults(1).size)
    }

    // ------------------------------------------------------------------ interpolation (drawing)

    @Test fun theCurvePassesThroughItsNodesForEveryMethod() {
        val nodes = listOf(n(0.0, 0.0), n(0.25, 0.4), n(0.6, 0.55), n(1.0, 1.0))
        for (type in 0..2) {
            for (p in nodes) assertEquals("type $type at ${p.x}", p.y, CurveEdit.eval(nodes, type, p.x), 1e-9)
            for (i in 0..50) assertTrue(CurveEdit.eval(nodes, type, i / 50.0) in 0.0..1.0)
        }
        // the straight line stays straight, and the monotonic curve does not go back down
        for (type in 0..2) assertEquals(0.3, CurveEdit.eval(CurveEdit.IDENTITY, type, 0.3), 1e-9)
        var last = -1.0
        for (i in 0..100) { val y = CurveEdit.eval(nodes, 2, i / 100.0); assertTrue(y >= last - 1e-12); last = y }
    }

    // ------------------------------------------------------------------ what is written

    @Test fun aCurveIsWrittenAsItsCountAndEveryNode() {
        val w = CurveLayout.TONECURVE.writes(1, three)
        assertEquals(JsonPrimitive(3L), w["tonecurve_nodes[1]"])
        assertEquals(JsonPrimitive(0.5), w["tonecurve[1][1].x"])
        assertEquals(JsonPrimitive(1.0), w["tonecurve[1][2].y"])
        assertEquals(7, w.size)
        val r = CurveLayout.RGBCURVE.writes(0, three)
        assertEquals(JsonPrimitive(3L), r["curve_num_nodes[0]"])
        assertTrue("curve_nodes[0][2].x" in r)
        assertEquals("curve_type[2]", CurveLayout.RGBCURVE.typePath(2))
    }

    @Test fun aShorterCurveZeroesTheSlotsItLeaves() {
        val w = CurveLayout.TONECURVE.writes(0, CurveEdit.remove(three, 1), previous = 3)
        assertEquals(JsonPrimitive(2L), w["tonecurve_nodes[0]"])
        assertEquals(JsonPrimitive(0.0), w["tonecurve[0][2].x"])
        assertEquals(JsonPrimitive(0.0), w["tonecurve[0][2].y"])
        assertFalse("tonecurve[0][3].x" in w)
    }

    @Test fun nodesReadBackFromParameters() {
        val p = params(three)
        assertEquals(three, CurveLayout.TONECURVE.read(p, 0))
        assertEquals(3, CurveLayout.TONECURVE.countOf(p, 0))
    }

    private fun params(nodes: List<CurveNode>): JsonObject {
        // tonecurve[3][20] of {x, y}, tonecurve_nodes[3]
        val slot = { k: Int, ch: Int -> if (ch == 0 && k < nodes.size) buildJsonObject { put("x", nodes[k].x); put("y", nodes[k].y) } else buildJsonObject { put("x", 0.0); put("y", 0.0) } }
        return buildJsonObject {
            put("tonecurve", kotlinx.serialization.json.JsonArray(List(3) { ch -> kotlinx.serialization.json.JsonArray(List(20) { k -> slot(k, ch) }) }))
            put("tonecurve_nodes", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(nodes.size), JsonPrimitive(0), JsonPrimitive(0))))
        }
    }

    // ------------------------------------------------------------------ rgb levels (rgblevels.c `_rgblevels_move_handle`)

    @Test fun levelsKeepTheGreyHandleWhereItWasRelativelyAndTheGapsOpen() {
        val l = doubleArrayOf(0.1, 0.4, 0.9)
        val pct = LevelsEdit.pct(l) // 0.375
        val b = LevelsEdit.move(l, 0, 0.3, pct)
        assertEquals(0.3, b[0], 1e-12)
        assertEquals(0.3 + pct * (0.9 - 0.3), b[1], 1e-12)
        // black cannot pass the white handle's gap
        val c = LevelsEdit.move(l, 0, 5.0, pct)
        assertTrue(c[0] < c[1] && c[1] < c[2])
        assertTrue(c[2] - c[0] >= LevelsEdit.MIN_GAP - 1e-12)
        // grey stays between the others, 0.05 from each
        val g = LevelsEdit.move(l, 1, 5.0, pct)
        assertEquals(0.9 - LevelsEdit.MIN_GAP, g[1], 1e-12)
        val g2 = LevelsEdit.move(l, 1, -5.0, pct)
        assertEquals(0.1 + LevelsEdit.MIN_GAP, g2[1], 1e-12)
        // white
        val w = LevelsEdit.move(l, 2, 0.7, pct)
        assertEquals(0.7, w[2], 1e-12)
        assertEquals(0.1 + pct * 0.6, w[1], 1e-12)
        assertEquals(2, LevelsEdit.nearest(l, 0.8))
        assertEquals(1, LevelsEdit.nearest(l, 0.45))
    }

    @Test fun levelsWriteTheirThreeValues() {
        val w = LevelsEdit.writes(2, doubleArrayOf(0.1, 0.5, 0.9))
        assertEquals(setOf("levels[2][0]", "levels[2][1]", "levels[2][2]"), w.keys)
    }

    // ------------------------------------------------------------------ tone equalizer

    @Test fun aFlatToneEqualizerIsAFlatCurve() {
        val c = ToneEqMath.curve(DoubleArray(9), 1.4142135, 33)
        for (v in c) assertEquals(0.0, v, 0.05) // the fit of eight gaussians is not exact, as desktop
    }

    @Test fun theCurveFollowsTheBandValues() {
        val gains = doubleArrayOf(0.6, 0.5, 0.3, 0.1, 0.0, 0.0, -0.2, -0.4, -0.5)
        val sigma = 1.4142135
        for (i in 0 until 9) assertEquals("band $i", gains[i], ToneEqMath.curveEv(gains, sigma, i - 8.0), 0.2)
        // the curve is the fit desktop draws, bounded by +-2 EV
        for (v in ToneEqMath.curve(DoubleArray(9) { 2.0 }, sigma, 40)) assertTrue(v <= 2.0 + 1e-9)
    }

    @Test fun draggingUpRaisesTheBandsAroundTheControlExposure() {
        val sigma = 1.4142135
        val g = ToneEqMath.interactive(DoubleArray(9), -4.0, 0.5, sigma)!!
        assertTrue("the control band goes up: ${g.toList()}", g[4] > 0.3)
        assertTrue("its neighbours follow, less", g[3] in 0.0..g[4] && g[5] in 0.0..g[4])
        assertTrue("the far bands hardly move", abs(g[0]) < 0.1 && abs(g[8]) < 0.1)
        val d = ToneEqMath.interactive(DoubleArray(9), -4.0, -0.5, sigma)!!
        assertTrue(d[4] < -0.3)
        // the values stay within +-2 EV
        var x = DoubleArray(9)
        repeat(40) { x = ToneEqMath.interactive(x, -2.0, 0.5, sigma) ?: x }
        assertTrue(x.all { it <= 2.0 + 1e-9 })
    }

    private fun abs(v: Double) = kotlin.math.abs(v)

    @Test fun theBandsAreWrittenByTheirParameterNames() {
        val w = ToneEqMath.writes(DoubleArray(9) { it * 0.1 })
        assertEquals(listOf("noise", "ultra_deep_blacks", "deep_blacks", "blacks", "shadows", "midtones", "highlights", "whites", "speculars"), w.keys.toList())
        assertEquals(JsonPrimitive(0.8), w["speculars"])
    }

    @Test fun exposureOfAPixelFollowsTheEstimator() {
        val p = buildJsonObject { put("method", "DT_TONEEQ_NORM_2"); put("details", "DT_TONEEQ_NONE"); put("exposure_boost", 0.0); put("contrast_boost", 0.0) }
        // the euclidean norm of (0.25, 0.25, 0.25) is 0.433: about -1.2 EV
        assertEquals(Math.log(Math.sqrt(3 * 0.0625)) / Math.log(2.0), ToneEqMath.exposureOf(listOf(0.25, 0.25, 0.25, 0.0), p), 1e-9)
        // black is the bottom band, and a bright pixel the top one
        assertEquals(-8.0, ToneEqMath.exposureOf(listOf(0.0, 0.0, 0.0, 0.0), p), 0.0)
        assertEquals(0.0, ToneEqMath.exposureOf(listOf(4.0, 4.0, 4.0, 0.0), p), 0.0)
        // the mean estimator
        val m = buildJsonObject { put("method", "DT_TONEEQ_MEAN"); put("details", "DT_TONEEQ_NONE") }
        assertEquals(-2.0, ToneEqMath.exposureOf(listOf(0.25, 0.25, 0.25, 0.0), m), 1e-9)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.ui.graph.BandAxis
import app.dtandroid.darkroom.ui.graph.BandEdit
import app.dtandroid.darkroom.ui.graph.BandHost
import app.dtandroid.darkroom.ui.graph.BandInterp
import app.dtandroid.darkroom.ui.graph.BandMode
import app.dtandroid.darkroom.ui.graph.BandModels
import app.dtandroid.darkroom.ui.graph.BandOptions
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.PickStats
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.engine.api.paramAt
import app.dtandroid.schema.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3e-b (JVM): the band editor's model (node limits, the soft brush, the writes, reset, adding and taking away
 * nodes, "select by", the picked marks) and, on the stand-in engine, its calls into the controller: one history item
 * per drag, one for a reset, the tab the controller keeps, and where the editor stands in for desktop's graph.
 */
class P3eBandsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun nums(vararg v: Double) = JsonArray(v.map { JsonPrimitive(it) })

    private fun atrousParams(luma: List<Double> = List(6) { 0.5 }): JsonObject = buildJsonObject {
        put("octaves", JsonPrimitive(3))
        val xs = List(6) { it / 5.0 }
        put("x", JsonArray(List(5) { nums(*xs.toDoubleArray()) }))
        put("y", JsonArray(listOf(nums(*luma.toDoubleArray()), nums(*DoubleArray(6) { 0.5 }), nums(*DoubleArray(6) { 0.5 }), nums(*DoubleArray(6)), nums(*DoubleArray(6)))))
        put("mix", JsonPrimitive(1.0))
    }

    private fun zonesParams(channel: String = "DT_IOP_COLORZONES_h", version: Int = 1, nodes: List<Pair<Double, Double>> = listOf(0.25 to 0.5, 0.75 to 0.5)): JsonObject = buildJsonObject {
        put("channel", JsonPrimitive(channel))
        fun row(): JsonArray = JsonArray(List(20) { i ->
            val n = nodes.getOrNull(i)
            buildJsonObject { put("x", JsonPrimitive(n?.first ?: 0.0)); put("y", JsonPrimitive(n?.second ?: 0.0)) }
        })
        put("curve", JsonArray(List(3) { row() }))
        put("curve_num_nodes", JsonArray(List(3) { JsonPrimitive(nodes.size) }))
        put("curve_type", JsonArray(List(3) { JsonPrimitive(1) }))
        put("strength", JsonPrimitive(0.0))
        put("mode", JsonPrimitive("DT_IOP_COLORZONES_MODE_SMOOTH"))
        put("splines_version", JsonPrimitive(version))
    }

    private fun equalizerParams(hueRed: Double = 0.0, hueShift: Double = 0.0): JsonObject = buildJsonObject {
        for (n in BandModels.EQUAL_NODE_NAMES) {
            put("hue_$n", JsonPrimitive(if (n == "red") hueRed else 0.0))
            put("sat_$n", JsonPrimitive(1.0))
            put("bright_$n", JsonPrimitive(1.0))
        }
        put("hue_shift", JsonPrimitive(hueShift))
    }

    private val opts = BandOptions(radius = 0.1, brush = true, tolX = 0.05, tolY = 0.05)

    // ------------------------------------------------------------------ the model

    @Test fun contrastEqualizerReadsSixNodesPerCurveAndTheDenoiseCurveWithTheLumaTab() {
        val v = BandModels.build("atrous", atrousParams())!!
        assertEquals(listOf("luma", "chroma", "edges"), v.tabs.map { it.label })
        assertEquals(2, v.tabs[0].curves.size); assertEquals(2, v.tabs[1].curves.size); assertEquals(1, v.tabs[2].curves.size)
        assertEquals(6, v.tabs[0].curves[0].count)
        assertEquals(BandAxis.FREQUENCY, v.axis)
        // the x positions the stand-in engine does not set (zeros) are desktop's defaults
        val zeros = buildJsonObject { for ((k, e) in atrousParams()) put(k, e); put("x", JsonArray(List(5) { nums(*DoubleArray(6)) })) }
        assertEquals(listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0), BandModels.build("atrous", zeros)!!.tabs[0].curves[0].xs)
    }

    @Test fun writesMapEachNodeToItsParameter() {
        val v = BandModels.build("atrous", atrousParams())!!
        val t = v.tabs[0]
        val d = BandEdit.begin(t, 0.4, 0.5, opts.copy(radius = 0.02), inStrip = false, xStrip = true)!!
        val moved = BandEdit.update(d, 0.4, 0.9)
        val w = moved.writesFrom(t).toMap()
        assertEquals(0.9, (w["y[0][2]"] as JsonPrimitive).doubleOrNull!!, 1e-9)
        assertTrue("only the nodes that changed are written", w.keys.all { it.startsWith("y[0][") })

        // colour equalizer: the graph spans the parameter's range, the neutral value in the middle
        val e = BandModels.build("colorequal", equalizerParams())!!
        assertEquals(0.5, e.tabs[0].curves[0].ys[0], 1e-9)
        assertEquals(0.5, e.tabs[1].curves[0].ys[3], 1e-9)
        val hue = e.tabs[0]
        val d2 = BandEdit.begin(hue, 0.0, 0.5, opts.copy(radius = 0.02), false, false)!!
        val w2 = BandEdit.update(d2, 0.0, 0.75).writesFrom(hue).toMap()
        assertEquals(90.0, (w2["hue_red"] as JsonPrimitive).doubleOrNull!!, 1e-6)
        val sat = e.tabs[1]
        val d3 = BandEdit.begin(sat, 0.0, 0.5, opts.copy(radius = 0.02), false, false)!!
        assertEquals(1.5, (BandEdit.update(d3, 0.0, 0.75).writesFrom(sat).toMap()["sat_red"] as JsonPrimitive).doubleOrNull!!, 1e-6)
    }

    @Test fun brushRadiusDecidesHowFarTheNeighboursFollow() {
        val c = BandModels.build("atrous", atrousParams())!!.tabs[0].curves[0]
        val small = BandEdit.brush(c, 0.4, 1.0, 0.02)
        val wide = BandEdit.brush(c, 0.4, 1.0, 0.3)
        assertEquals(1.0, small[2], 1e-9)                   // the node under the brush goes all the way
        assertEquals(0.5, small[1], 1e-6); assertEquals(0.5, small[3], 1e-6) // a narrow brush leaves the neighbours
        assertTrue("a wide brush moves them", wide[1] > 0.6 && wide[3] > 0.6)
        assertTrue("less the further away", wide[1] > wide[0] && wide[3] > wide[4])
        assertTrue(wide.all { it in 0.0..1.0 })
        // a wider brush never moves a neighbour less
        var last = 0.0
        for (r in listOf(0.02, 0.05, 0.1, 0.2, 0.4)) { val y = BandEdit.brush(c, 0.4, 1.0, r)[1]; assertTrue(y >= last); last = y }
        // on a hue axis the brush wraps round: the node at 0.0 feels a brush at 0.98
        val e = BandModels.build("colorequal", equalizerParams())!!.tabs[1].curves[0]
        assertTrue(BandEdit.brush(e, 0.98, 1.0, 0.05)[0] > 0.9)
    }

    @Test fun aDragIsMadeFromTheTouchDownNotFromTheLastStep() {
        val t = BandModels.build("atrous", atrousParams())!!.tabs[0]
        val d = BandEdit.begin(t, 0.4, 0.5, opts, false, true)!!
        assertEquals(BandMode.BRUSH, d.mode)
        val up = BandEdit.update(d, 0.4, 0.9)
        val back = BandEdit.update(d, 0.4, 0.5)
        for (i in t.curves[0].ys.indices) assertEquals(t.curves[0].ys[i], back.curves[0].ys[i], 1e-9)
        assertTrue(up.curves[0].ys[2] > 0.85)
        // the finger on a node takes the node: it follows the finger exactly even though the brush is wide
        val on = BandEdit.begin(t, 0.4, 0.5, opts.copy(radius = 0.3), false, true)!!
        assertEquals(2, on.selected)
        assertEquals(0.8, BandEdit.update(on, 0.43, 0.8).curves[0].ys[2], 1e-9)
    }

    @Test fun theDenoiseCurveIsTheOneNearestTheFinger() {
        val t = BandModels.build("atrous", atrousParams())!!.tabs[0]
        val low = BandEdit.begin(t, 0.4, 0.05, opts.copy(tolY = 0.01), false, true)!!   // near the denoise curve (y 0)
        assertEquals(1, low.curveIndex)
        val mid = BandEdit.begin(t, 0.4, 0.55, opts.copy(tolY = 0.01), false, true)!!
        assertEquals(0, mid.curveIndex)
    }

    // ------------------------------------------------------------------ node limits

    @Test fun xStripMovesInnerNodesBetweenTheirNeighboursAndBothCurvesTogether() {
        val t = BandModels.build("atrous", atrousParams())!!.tabs[0]
        assertNull("the end nodes stay", BandEdit.begin(t, 0.0, 0.0, opts, inStrip = true, xStrip = true))
        assertNull("the end nodes stay", BandEdit.begin(t, 1.0, 0.0, opts, inStrip = true, xStrip = true))
        val d = BandEdit.begin(t, 0.41, 0.0, opts, inStrip = true, xStrip = true)!!
        assertEquals(BandMode.STRIP, d.mode)
        val far = BandEdit.update(d, 0.95, 0.0)
        assertTrue(far.curves[0].xs[2] < far.curves[0].xs[3] && far.curves[0].xs[2] > 0.59)
        val near = BandEdit.update(d, 0.3, 0.0)
        assertEquals(0.3, near.curves[0].xs[2], 1e-9)
        assertEquals("the denoise curve shares the x positions", near.curves[0].xs, near.curves[1].xs)
        val w = near.writesFrom(t).toMap()
        assertEquals(0.3, (w["x[0][2]"] as JsonPrimitive).doubleOrNull!!, 1e-9)
        assertEquals(0.3, (w["x[3][2]"] as JsonPrimitive).doubleOrNull!!, 1e-9)
        // the edges tab has one curve only
        val edges = BandModels.build("atrous", atrousParams())!!.tabs[2]
        assertEquals(1, BandEdit.update(BandEdit.begin(edges, 0.4, 0.0, opts, true, true)!!, 0.3, 0.0).curves.size)
    }

    @Test fun colourEqualizerNodesNeverLeaveTheirHuePosition() {
        val e = BandModels.build("colorequal", equalizerParams(hueShift = 12.0))!!
        val c = e.tabs[0].curves[0]
        assertEquals(12.0 / 360.0, c.xs[0], 1e-9)
        assertEquals(1.0 / 8 + 12.0 / 360.0, c.xs[1], 1e-9)
        val moved = BandEdit.moveNode(c, 1, 0.9, 0.9)
        assertEquals(c.xs, moved.xs)
        assertEquals(0.9, moved.ys[1], 1e-9)
        assertTrue("x is not a parameter here", moved.writesFrom(c).none { it.first.contains("shift") })
        assertEquals(1.0, BandEdit.moveNode(c, 1, 0.0, 3.0).ys[1], 0.0)   // the top of the graph is the limit
        assertEquals(0.0, BandEdit.moveNode(c, 1, 0.0, -3.0).ys[1], 0.0)
    }

    @Test fun zonesNodesStayInOrderAGapApartAndInsideTheGraph() {
        val c = BandModels.build("colorzones", zonesParams(nodes = listOf(0.1 to 0.5, 0.4 to 0.5, 0.8 to 0.5)))!!.tabs[2].curves[0]
        val m = BandEdit.moveNode(c, 1, 0.95, 0.7)
        assertTrue("stopped at its right neighbour", m.xs[1] <= 0.8 - 0.0025 + 1e-12 && m.xs[1] > 0.79)
        assertEquals(0.7, m.ys[1], 1e-9)
        assertEquals(0.1 + 0.0025, BandEdit.moveNode(c, 1, -1.0, 0.5).xs[1], 1e-9)
        for (n in listOf(0, 1, 2)) {
            val r = BandEdit.moveNode(c, n, 5.0, 5.0)
            assertTrue(r.xs.zipWithNext().all { (a, b) -> b - a >= 0.0025 - 1e-12 })
            assertTrue(r.xs.all { it in 0.0..1.0 } && r.ys.all { it in 0.0..1.0 })
        }
    }

    @Test fun zonesHueAxisWrapsRoundWithTheSecondSplinesVersionAndKeepsItsEndsWithTheFirst() {
        val v2 = BandModels.build("colorzones", zonesParams(version = 1, nodes = listOf(0.1 to 0.5, 0.5 to 0.5, 0.9 to 0.5)))!!.tabs[2].curves[0]
        assertTrue(v2.periodic)
        // the first node keeps its distance to the last node across the seam
        assertTrue(BandEdit.moveNode(v2, 0, -0.5, 0.5).xs[0] >= 0.0 && BandEdit.moveNode(v2, 0, 0.95, 0.5).xs[0] <= 0.5 - 0.0025 + 1e-12)
        val v1 = BandModels.build("colorzones", zonesParams(version = 0, nodes = listOf(0.0 to 0.5, 0.5 to 0.5, 1.0 to 0.5)))!!.tabs[2].curves[0]
        assertFalse(v1.periodic)
        val e = BandEdit.moveNode(v1, 0, 0.3, 0.8)
        assertEquals("end nodes keep their x", 0.0, e.xs[0], 0.0)
        assertEquals("the hue's last node follows the first one's y", 0.8, e.ys[2], 0.0)
        val l = BandModels.build("colorzones", zonesParams("DT_IOP_COLORZONES_L", 0, listOf(0.0 to 0.5, 1.0 to 0.5)))!!.tabs[0].curves[0]
        assertEquals(0.0, BandEdit.moveNode(l, 0, 0.4, 0.2).xs[0], 0.0)
        assertEquals(0.5, BandEdit.moveNode(l, 0, 0.4, 0.2).ys[1], 0.0)
    }

    @Test fun zonesNodesCanBeAddedAndTakenAway() {
        val t = BandModels.build("colorzones", zonesParams())!!.tabs[2]
        val c = t.curves[0]
        val (added, at) = BandEdit.addNode(c, 0.5, 0.8)!!
        assertEquals(1, at); assertEquals(3, added.count)
        assertEquals(listOf(0.25, 0.5, 0.75), added.xs)
        val w = added.writesFrom(c).toMap()
        assertEquals(3L, (w["curve_num_nodes[2]"] as JsonPrimitive).content.toLong())
        assertEquals(0.5, (w["curve[2][1].x"] as JsonPrimitive).doubleOrNull!!, 1e-9)
        assertEquals(0.8, (w["curve[2][1].y"] as JsonPrimitive).doubleOrNull!!, 1e-9)
        assertEquals("the node that moved up a slot is written there", 0.75, (w["curve[2][2].x"] as JsonPrimitive).doubleOrNull!!, 1e-9)
        assertNull("too near a node", BandEdit.addNode(c, 0.251, 0.5))
        val back = BandEdit.deleteNode(added, 1)
        assertEquals(c.xs, back.xs)
        val wd = back.writesFrom(added).toMap()
        assertEquals(2L, (wd["curve_num_nodes[2]"] as JsonPrimitive).content.toLong())
        assertEquals("the slot that fell out is zeroed, as desktop does", 0.0, (wd["curve[2][2].x"] as JsonPrimitive).doubleOrNull!!, 0.0)
        assertEquals(1, BandEdit.deleteNode(c.copy(xs = listOf(0.5), ys = listOf(0.7)), 0).count)
        // no more than the 20 nodes the module has
        var full = c
        for (i in 0 until 40) full = BandEdit.addNode(full, 0.26 + i * 0.012, 0.5)?.first ?: full
        assertEquals(20, full.count)
        assertNull(BandEdit.addNode(full, 0.9995, 0.5))
        // a new node by a drag: the drag adds it on the first move, then it follows the finger
        val d = BandEdit.begin(t, 0.5, 0.2, opts.copy(brush = false), false, false)!!
        assertEquals(BandMode.ADD, d.mode)
        val moved = BandEdit.update(d, 0.5, 0.3)
        assertEquals(3, moved.curves[0].count)
        assertEquals(0.3, moved.curves[0].ys[1], 1e-9)
        assertEquals(3, BandEdit.update(d, 0.55, 0.35).curves[0].count)
    }

    @Test fun aDragOnANodeTakesTheNodeAndLeavesTheOthers() {
        val t = BandModels.build("colorzones", zonesParams(nodes = listOf(0.1 to 0.5, 0.4 to 0.5, 0.8 to 0.5)))!!.tabs[2]
        val d = BandEdit.begin(t, 0.41, 0.52, opts.copy(brush = false), false, false)!!
        assertEquals(BandMode.NODE, d.mode); assertEquals(1, d.selected)
        val m = BandEdit.update(d, 0.5, 0.8).curves[0]
        assertEquals(listOf(0.1, 0.8), listOf(m.xs[0], m.xs[2]))
        assertEquals(0.49, m.xs[1], 1e-9)   // the finger keeps its grip on the node: no jump
        assertEquals(0.78, m.ys[1], 1e-9)
        // "edit by area" on: the brush instead
        assertEquals(BandMode.BRUSH, BandEdit.begin(t, 0.41, 0.52, opts, false, false)!!.mode)
    }

    // ------------------------------------------------------------------ reset, select by, the picker

    @Test fun resetPutsTheTabsCurvesBackToDesktopsDefaults() {
        val t = BandModels.build("atrous", atrousParams(luma = listOf(0.9, 0.8, 0.7, 0.6, 0.5, 0.4)))!!.tabs[0]
        val w = t.reset().writesFrom(t, clearRemoved = false).toMap()
        assertEquals("the four nodes that are not at 0.5 (the denoise curve is already flat)", 5, w.size)
        assertFalse(w.containsKey("y[0][4]"))
        assertTrue(w.keys.all { it.startsWith("y[0][") })
        assertEquals(0.5, (w["y[0][0]"] as JsonPrimitive).doubleOrNull!!, 0.0)
        // zones: two nodes, the interpolation back to centripetal; a hue axis starts a quarter in
        val z = BandModels.build("colorzones", zonesParams(nodes = listOf(0.1 to 0.9, 0.3 to 0.2, 0.5 to 0.7, 0.9 to 0.5)))!!.tabs[2]
        val zw = z.reset().writesFrom(z, clearRemoved = false).toMap()
        assertEquals(2L, (zw["curve_num_nodes[2]"] as JsonPrimitive).content.toLong())
        assertEquals(0.25, (zw["curve[2][0].x"] as JsonPrimitive).doubleOrNull!!, 0.0)
        assertEquals(0.75, (zw["curve[2][1].x"] as JsonPrimitive).doubleOrNull!!, 0.0)
        assertEquals(0.5, (zw["curve[2][0].y"] as JsonPrimitive).doubleOrNull!!, 0.0)
        // colour equalizer: every node neutral
        val e = BandModels.build("colorequal", equalizerParams(hueRed = 100.0))!!.tabs[0]
        assertEquals(0.0, (e.reset().writesFrom(e).toMap()["hue_red"] as JsonPrimitive).doubleOrNull!!, 1e-9)
    }

    @Test fun selectByPutsEverythingBackAsDesktopsCallbackDoes() {
        val w = BandModels.zonesSelectWrites(0, zonesParams()).toMap()
        assertEquals("DT_IOP_COLORZONES_L", (w["channel"] as JsonPrimitive).content)
        assertEquals(0.0, (w["curve[0][0].x"] as JsonPrimitive).doubleOrNull!!, 0.0)   // lightness: the nodes at the edges
        assertEquals(1.0, (w["curve[1][1].x"] as JsonPrimitive).doubleOrNull!!, 0.0)
        for (ch in 0..2) assertEquals(2L, (w["curve_num_nodes[$ch]"] as JsonPrimitive).content.toLong())
        assertEquals(0.0, (w["strength"] as JsonPrimitive).doubleOrNull!!, 0.0)
        assertEquals("DT_IOP_COLORZONES_MODE_SMOOTH", (w["mode"] as JsonPrimitive).content)
        val h = BandModels.zonesSelectWrites(2, zonesParams()).toMap()
        assertEquals(0.25, (h["curve[0][0].x"] as JsonPrimitive).doubleOrNull!!, 0.0)
        // the axis of the editor follows "select by"
        assertEquals(BandAxis.LIGHTNESS, BandModels.build("colorzones", zonesParams("DT_IOP_COLORZONES_L"))!!.axis)
        assertEquals(BandAxis.CHROMA, BandModels.build("colorzones", zonesParams("DT_IOP_COLORZONES_C"))!!.axis)
        assertEquals(BandAxis.HUE, BandModels.build("colorzones", zonesParams())!!.axis)
    }

    @Test fun theMarkOfAPickedColourLiesWhereDesktopDrawsIt() {
        fun lch(l: Double, c: Double, h: Double) = PickStats("lch", listOf(l, c, h, 0.0), listOf(l - 10, c - 5, h - 0.1, 0.0), listOf(l + 10, c + 5, h + 0.1, 0.0), listOf(0, 0, 1, 1))
        val s = lch(50.0, 36.0, 0.5)
        assertEquals(0.5, BandModels.pickMarks(BandAxis.LIGHTNESS, s).single().x, 1e-9)
        assertEquals(0.4, BandModels.pickMarks(BandAxis.LIGHTNESS, s).single().from!!, 1e-9)
        assertEquals(36.0 / (128 * Math.sqrt(2.0)), BandModels.pickMarks(BandAxis.CHROMA, s).single().x, 1e-9)
        assertEquals(0.5, BandModels.pickMarks(BandAxis.HUE, s).single().x, 1e-9)
        assertTrue(BandModels.pickMarks(BandAxis.FREQUENCY, s).isEmpty())
        assertTrue(BandModels.pickMarks(BandAxis.HUE, s.copy(colorspace = "rgb")).isEmpty())
        // a point has no range
        assertNull(BandModels.pickMarks(BandAxis.HUE, s.copy(min = s.mean, max = s.mean)).single().from)
    }

    @Test fun theInterpolatedLineGoesThroughTheNodes() {
        val xs = listOf(0.0, 0.3, 0.7, 1.0)
        val ys = listOf(0.5, 0.8, 0.2, 0.5)
        for (type in listOf(BandInterp.CUBIC, BandInterp.CATMULL, BandInterp.MONOTONE)) {
            val f = BandInterp.fn(xs, ys, type, false)
            for (i in xs.indices) assertEquals("type $type node $i", ys[i], f(xs[i]), 1e-9)
        }
        val periodic = BandInterp.fn(listOf(0.0, 0.25, 0.5, 0.75), listOf(0.5, 0.9, 0.5, 0.1), BandInterp.CATMULL, true)
        assertEquals(0.9, periodic(0.25), 1e-9)
        assertEquals("the seam joins up", periodic(0.0), periodic(1.0 - 1e-9), 1e-3)
        val mono = BandInterp.fn(xs, listOf(0.1, 0.2, 0.9, 1.0), BandInterp.MONOTONE, false)
        assertTrue((0..100).map { mono(it / 100.0) }.zipWithNext().all { (a, b) -> b >= a - 1e-9 })
    }

    // ------------------------------------------------------------------ on the stand-in engine

    private fun controller(): DarkroomController {
        val dir = tmp.root
        val engine: DarktableEngine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 600)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = false) }
        return c
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun params(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }.params
    private fun num(c: DarkroomController, key: InstanceKey, path: String) = (params(c, key).paramAt(path) as JsonPrimitive).doubleOrNull!!

    private fun waitFor(what: String, cond: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!cond()) delay(5) }
    }

    private fun dragNode(c: DarkroomController, key: InstanceKey, tab: Int, node: Int, to: Double) {
        val t = BandModels.build(key.op, params(c, key))!!.tabs[tab]
        val curve = t.curves[0]
        // colour zones without "edit by area": the node itself; the others: the soft brush
        val d = BandEdit.begin(t, curve.xs[node], curve.ys[node], opts.copy(brush = key.op != "colorzones"), false, true)!!
        var last = t
        for (i in 1..8) {
            last = BandEdit.update(d, curve.xs[node], curve.ys[node] + (to - curve.ys[node]) * i / 8)
            c.bandDrag(key, last.writesFrom(t))
        }
        c.bandEnd(key, last.writesFrom(t))
    }

    @Test fun oneHistoryItemPerDragInEachModule() {
        val c = controller()
        for ((op, tab, path) in listOf(Triple("atrous", 0, "y[0][2]"), Triple("colorzones", 2, "curve[2][0].y"), Triple("colorequal", 1, "sat_green"))) {
            val key = InstanceKey(op, 0)
            val before = items(c)
            val v0 = num(c, key, path)
            dragNode(c, key, tab, if (op == "colorequal") 3 else if (op == "atrous") 2 else 0, 0.9)
            waitFor("$op: the drag written") { items(c) > before && num(c, key, path) != v0 }
            Thread.sleep(150)
            assertEquals("$op: one history item for the whole drag", before + 1, items(c))
            assertTrue("$op: the module is switched on, as moving a control does", c.state.value.modules.first { it.key == key }.enabled)
        }
    }

    @Test fun aSecondDragIsAnotherItemAndUndoTakesItBack() {
        val c = controller()
        val key = InstanceKey("atrous", 0)
        val b0 = items(c)
        dragNode(c, key, 0, 2, 0.9)
        waitFor("first") { items(c) == b0 + 1 }
        val after1 = num(c, key, "y[0][2]")
        // another module in between seals the group
        runBlocking { c.setParam(InstanceKey("exposure", 0), "exposure", JsonPrimitive(0.5)) }
        dragNode(c, key, 0, 2, 0.2)
        waitFor("second") { items(c) == b0 + 3 && num(c, key, "y[0][2]") != after1 }
        runBlocking { c.undo() }
        waitFor("undo") { num(c, key, "y[0][2]") == after1 }
    }

    @Test fun resetOfATabIsOneHistoryItem() {
        val c = controller()
        val key = InstanceKey("colorequal", 0)
        dragNode(c, key, 0, 2, 0.9)
        waitFor("moved") { num(c, key, "hue_yellow") != 0.0 }
        val before = items(c)
        val t = BandModels.build(key.op, params(c, key))!!.tabs[0]
        runBlocking { c.bandApply(key, t.reset().writesFrom(t)) }
        assertEquals(before + 1, items(c))
        assertEquals(0.0, num(c, key, "hue_yellow"), 1e-9)
        // another tab's curve is not touched by this tab's reset
        dragNode(c, key, 1, 4, 0.2)
        waitFor("sat moved") { num(c, key, "sat_cyan") != 1.0 }
        val sat = num(c, key, "sat_cyan")
        runBlocking { c.bandApply(key, BandModels.build(key.op, params(c, key))!!.tabs[0].let { it.reset().writesFrom(it) }) }
        assertEquals(sat, num(c, key, "sat_cyan"), 0.0)
    }

    @Test fun nodesAddedAndTakenAwayAreOneItemEachAndTheCountFollows() {
        val c = controller()
        val key = InstanceKey("colorzones", 0)
        val t = BandModels.build(key.op, params(c, key))!!.tabs[2]
        val before = items(c)
        val (added, _) = BandEdit.addNode(t.curves[0], 0.5, 0.7)!!
        runBlocking { c.bandApply(key, t.copy(curves = listOf(added)).writesFrom(t)) }
        assertEquals(before + 1, items(c))
        assertEquals(3.0, num(c, key, "curve_num_nodes[2]"), 0.0)
        assertEquals(3, BandModels.build(key.op, params(c, key))!!.tabs[2].curves[0].count)
    }

    @Test fun selectByIsOneItemAndMovesTheAxis() {
        val c = controller()
        val key = InstanceKey("colorzones", 0)
        val before = items(c)
        runBlocking { c.bandApply(key, BandModels.zonesSelectWrites(0, params(c, key))) }
        assertEquals(before + 1, items(c))
        assertEquals(BandAxis.LIGHTNESS, BandModels.build(key.op, params(c, key))!!.axis)
    }

    @Test fun tabSwitchingIsTheControllersAndKeepsTheOtherTabsAsTheyWere() {
        val c = controller()
        val m = TestSetup.schema.modules
        for (op in BandModels.OPS) {
            val ms = m[op]!!
            val tabs = BandHost.tabsId(ms)
            assertNotNull("$op has its notebook", tabs)
            val key = InstanceKey(op, 0)
            assertEquals(0, c.tabPage(c.state.value, key, tabs!!))
            c.chooseTab(key, tabs, 2)
            assertEquals(2, c.tabPage(c.state.value, key, tabs))
        }
        val key = InstanceKey("colorequal", 0)
        dragNode(c, key, 0, 1, 0.8)
        waitFor("hue moved") { num(c, key, "hue_orange") > 1.0 }
        assertEquals(1.0, num(c, key, "sat_orange"), 0.0)
        assertEquals(1.0, num(c, key, "bright_orange"), 0.0)
    }

    @Test fun theEditorStandsInForDesktopsGraphAndNotebook() {
        val m = TestSetup.schema.modules
        assertEquals(4, BandHost.graphId(m["atrous"]!!))
        assertEquals(8, BandHost.graphId(m["colorzones"]!!))
        assertEquals(4, BandHost.graphId(m["colorequal"]!!))
        assertNull(BandHost.graphId(m["exposure"]!!))
        for (op in BandModels.OPS) assertTrue(m[op]!!.allControls().filterIsInstance<Control.Tabs>().all { BandHost.hides(m[op]!!, it) })
        val zones = m["colorzones"]!!
        val hidden = zones.allControls().filter { BandHost.hides(zones, it) && it !is Control.Tabs }.mapNotNull { it.label?.trim() }
        assertEquals(setOf("select by", "edit by area", "interpolation method"), hidden.toSet())
        // the process mode and the mix stay generic controls
        assertFalse(zones.allControls().any { it.label?.trim() == "process mode" && BandHost.hides(zones, it) })
        assertFalse(zones.allControls().any { it.label?.trim() == "mix" && BandHost.hides(zones, it) })
        assertFalse(BandHost.hides(m["colorequal"]!!, m["colorequal"]!!.allControls().first { it.label?.trim() == "node placement" }))
        assertTrue(BandHost.pagesFollowTabs("colorequal"))
        assertFalse(BandHost.pagesFollowTabs("atrous"))
    }
}

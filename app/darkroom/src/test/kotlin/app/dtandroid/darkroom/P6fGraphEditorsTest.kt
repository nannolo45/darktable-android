// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.ui.graph.BandEdit
import app.dtandroid.darkroom.ui.graph.BandHost
import app.dtandroid.darkroom.ui.graph.BandMode
import app.dtandroid.darkroom.ui.graph.BandModels
import app.dtandroid.darkroom.ui.graph.BandOptions
import app.dtandroid.darkroom.ui.graph.CurveEdit
import app.dtandroid.darkroom.ui.graph.CurveLayout
import app.dtandroid.darkroom.ui.graph.CurveNode
import app.dtandroid.darkroom.ui.graph.CurveScale
import app.dtandroid.darkroom.ui.graph.CurveView
import app.dtandroid.darkroom.ui.graph.GraphViews
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.paramAt
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Brief P6f (JVM): the graph editors of base curve, saturation curve, lowlight vision, raw denoise and denoise
 * (profiled), and the display scale of the curve graphs. The gesture to parameter mapping (add, move, take away a node,
 * the limits, reset, tabs) against desktop's handlers, and on the stand-in engine: one history item per finished
 * gesture, undo, the parameters each tab writes. The zoom maths against tonecurve.c / rgbcurve.c's formulas.
 */
class P6fGraphEditorsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = InstanceKey("basecurve", 0)
    private val sat = InstanceKey("satcurvergb", 0)
    private val low = InstanceKey("lowlight", 0)
    private val raw = InstanceKey("rawdenoise", 0)
    private val dn = InstanceKey("denoiseprofile", 0)
    private val baseL = CurveLayout.BASECURVE
    private val satL = CurveLayout.SATCURVE
    private fun n(x: Double, y: Double) = CurveNode(x, y)

    private fun controller(): DarkroomController {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 600)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = false) }
        return c
    }

    private fun params(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }.params
    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun num(p: JsonObject, path: String) = (p.paramAt(path) as? JsonPrimitive)?.doubleOrNull

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) {
            throw AssertionError("timed out: $what")
        }

    // ------------------------------------------------------------------ base curve

    @Test fun baseCurveLayoutFollowsBasecurveC() {
        assertEquals("basecurve[0][3].x", baseL.nodePath(0, 3, 'x'))
        assertEquals("basecurve_nodes[0]", baseL.countPath(0))
        assertEquals("basecurve_type[0]", baseL.typePath(0))
        assertEquals(1, baseL.channels)
        assertEquals(20, baseL.maxNodes)
        // init: (0, 0), (1, 1)
        assertEquals(CurveEdit.IDENTITY, baseL.defaults(0))
        // add (at the finger, 0.025 from both neighbours, as dt_iop_basecurve_button_press), up to MAXNODES
        val (two, at) = baseL.add(CurveEdit.IDENTITY, 0.3, 0.45, 2)!!
        assertEquals(1, at)
        assertEquals(n(0.3, 0.45), two[1])
        assertNull("too near a neighbour", baseL.add(two, 0.31, 0.5, 2))
        var full = CurveEdit.IDENTITY
        var x = 0.04
        while (full.size < 20) { full = baseL.add(full, x, x, 2)!!.first; x += 0.04 }
        assertNull("MAXNODES 20", baseL.add(full, 0.99 - 0.001, 0.5, 2))
        // the right click: an end node back on its corner, an inner node removed
        val three = listOf(n(0.1, 0.2), n(0.4, 0.6), n(0.9, 0.8))
        assertEquals(n(0.0, 0.0), baseL.remove(three, 0)[0])
        assertEquals(n(1.0, 1.0), baseL.remove(three, 2)[2])
        assertEquals(2, baseL.remove(three, 1).size)
        // a move stays inside the graph and between the neighbours
        val m = baseL.move(three, 1, 2.0, -1.0)
        assertTrue(m[1].x < 0.9 && m[1].y == 0.0)
        // the double click: the default nodes and the monotonic interpolation; the slots a longer curve used are zeroed
        val w = baseL.resetWrites(0, previous = 3)
        assertEquals(JsonPrimitive(2L), w["basecurve_nodes[0]"])
        assertEquals(JsonPrimitive(1.0), w["basecurve[0][1].x"])
        assertEquals(JsonPrimitive(0.0), w["basecurve[0][2].x"])
        assertEquals(JsonPrimitive(2L), w["basecurve_type[0]"])
    }

    @Test fun baseCurveDragIsOneItemAndUndoes() = runBlocking {
        val c = controller()
        val items0 = items(c)
        assertEquals("the stand-in's zeros read as desktop's default curve", CurveEdit.IDENTITY, baseL.read(params(c, base), 0))
        val added = baseL.add(CurveEdit.IDENTITY, 0.3, 0.3, 2)!!.first
        for (y in listOf(0.35, 0.4, 0.45)) c.curveDrag(base, baseL.writes(0, baseL.move(added, 1, 0.3, y), previous = 0))
        c.curveEnd(base)
        waitFor("one item") { items(c) == items0 + 1 }
        waitFor("the curve") { baseL.read(params(c, base), 0) == listOf(n(0.0, 0.0), n(0.3, 0.45), n(1.0, 1.0)) }
        c.undo()
        waitFor("undone") { items(c) == items0 || baseL.read(params(c, base), 0) == CurveEdit.IDENTITY }
        assertEquals(CurveEdit.IDENTITY, baseL.read(params(c, base), 0))
    }

    // ------------------------------------------------------------------ saturation curve

    @Test fun saturationCurveFollowsSatcurvergbC() {
        assertEquals("channel[1].curve[4].y", satL.nodePath(1, 4, 'y'))
        assertEquals("channel[0].curve_num_nodes", satL.countPath(0))
        assertEquals("channel[1].curve_type", satL.typePath(1))
        // reset_channel_curve: (0, .5), (1, .5)
        val flat = listOf(n(0.0, 0.5), n(1.0, 0.5))
        assertEquals(flat, satL.defaults(0))
        assertEquals(flat, satL.defaults(1))
        // a node is added on the curve, not at the finger (area_button_press), 0.0025 from the neighbours
        val (three, at) = satL.add(flat, 0.5, 0.9, 2)!!
        assertEquals(1, at)
        assertEquals(0.5, three[1].y, 1e-9)
        assertNotNull(satL.add(three, 0.503, 0.5, 2))
        assertNull(satL.add(three, 0.502, 0.5, 2))
        // the ends keep x 0 and 1 (area_motion_notify); an inner node stays 0.0025 inside its neighbours
        val bent = listOf(n(0.0, 0.5), n(0.5, 0.8), n(1.0, 0.5))
        assertEquals(0.0, satL.move(bent, 0, 0.3, 0.6)[0].x, 0.0)
        assertEquals(0.6, satL.move(bent, 0, 0.3, 0.6)[0].y, 1e-12)
        assertEquals(1.0, satL.move(bent, 2, 0.3, 0.6)[2].x, 0.0)
        assertEquals(1.0 - 0.0025, satL.move(bent, 1, 1.0, 0.6)[1].x, 1e-12)
        // the right click: with two nodes the y goes back to 0.5, else the node goes (an end node too)
        assertEquals(listOf(n(0.0, 0.5), n(1.0, 0.5)), satL.remove(listOf(n(0.0, 0.9), n(1.0, 0.5)), 0))
        assertEquals(listOf(n(0.5, 0.8), n(1.0, 0.5)), satL.remove(bent, 0))
    }

    @Test fun saturationAndBrillianceAreSeparateChannels() = runBlocking {
        val c = controller()
        val items0 = items(c)
        val bent = listOf(n(0.0, 0.5), n(0.5, 0.8), n(1.0, 0.5))
        c.curveCommit(sat, satL.writes(1, bent, previous = 0))
        waitFor("one item") { items(c) == items0 + 1 }
        waitFor("brilliance written") { satL.read(params(c, sat), 1) == bent }
        assertEquals("saturation untouched", satL.defaults(0), satL.read(params(c, sat), 0))
        assertEquals(3.0, num(params(c, sat), "channel[1].curve_num_nodes"))
        // the double tap resets the shown channel only
        c.curveCommit(sat, satL.resetWrites(1, previous = 3))
        waitFor("reset") { items(c) == items0 + 2 && satL.read(params(c, sat), 1) == satL.defaults(1) }
    }

    // ------------------------------------------------------------------ lowlight vision

    private fun lowlightParams(c: DarkroomController) = params(c, low)

    @Test fun lowlightIsOneCurveOfSixBandsWithTheBrushAndTheStrip() = runBlocking {
        val c = controller()
        // the stand-in lists the x positions as zeros (lowlight.c sets them in init): write desktop's first
        c.bandApply(low, (0 until 6).map { "transition_x[$it]" to JsonPrimitive(it / 5.0) })
        waitFor("x positions") { num(params(c, low), "transition_x[5]") == 1.0 }
        val v = BandModels.build("lowlight", lowlightParams(c))!!
        assertEquals(1, v.tabs.size)
        val curve = v.tabs[0].curves[0]
        assertEquals(6, curve.count)
        assertEquals(listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0), curve.xs.map { Math.round(it * 10) / 10.0 })
        assertTrue(v.brushAlways && v.xStrip)
        assertEquals(1.0 / 6, v.defaultRadius, 1e-12)
        assertEquals(0.2 / 6, v.radiusMin, 1e-12)
        // the brush (dt_iop_lowlight_get_params): the node under the finger follows it, the far ones hardly move
        val o = BandOptions(radius = v.defaultRadius, brush = true, tolX = 0.05, tolY = 0.05)
        val d = BandEdit.begin(v.tabs[0], 0.0, 0.5, o, inStrip = false, xStrip = true)!!
        assertEquals(BandMode.BRUSH, d.mode)
        val t = BandEdit.update(d, 0.0, 1.0)
        assertEquals(1.0, t.curves[0].ys[0], 1e-6)
        assertTrue(t.curves[0].ys[5] < 0.51)
        val w = t.writesFrom(v.tabs[0])
        assertTrue(w.all { it.first.startsWith("transition_y[") })
        // the strip moves an inner x between its neighbours (0.001 apart), never an end
        val s = BandEdit.begin(v.tabs[0], 0.41, 0.0, o, inStrip = true, xStrip = true)!!
        assertEquals(BandMode.STRIP, s.mode)
        val moved = BandEdit.update(s, 0.9, 0.0)
        assertEquals(0.6 - 0.001, moved.curves[0].xs[2], 1e-9)
        assertTrue(moved.writesFrom(v.tabs[0]).any { it.first == "transition_x[2]" })
        assertNull(BandEdit.begin(v.tabs[0], 0.0, 0.0, o, inStrip = true, xStrip = true))
        // one history item per lifted finger, undo, and the double tap's reset (x and y)
        val items0 = items(c)
        c.bandEnd(low, moved.writesFrom(v.tabs[0]))
        waitFor("one item") { items(c) == items0 + 1 }
        waitFor("x written") { num(params(c, low), "transition_x[2]")?.let { Math.abs(it - 0.599) < 1e-6 } == true }
        val now = BandModels.build("lowlight", params(c, low))!!.tabs[0]
        c.bandApply(low, now.reset().writesFrom(now))
        waitFor("reset") { items(c) == items0 + 2 }
        assertEquals(0.6, num(params(c, low), "transition_x[2]")!!, 1e-6)
        c.undo()
        waitFor("undo") { num(params(c, low), "transition_x[2]")?.let { Math.abs(it - 0.599) < 1e-6 } == true }
    }

    // ------------------------------------------------------------------ raw denoise

    @Test fun rawDenoiseHasFourTabsOfFiveBandsAndWritesOnlyTheShownOne() = runBlocking {
        val c = controller()
        val v = BandModels.build("rawdenoise", params(c, raw))!!
        assertEquals(listOf("all", "R", "G", "B"), v.tabs.map { it.label })
        assertTrue(v.tabs.all { it.curves[0].count == 5 })
        assertFalse("no x strip (rawdenoise_motion_notify never moves an x)", v.xStrip)
        assertEquals(0.1, v.defaultRadius, 1e-12)
        assertEquals(0.04, v.radiusMin, 1e-12)
        // the other three channels are drawn faint behind, each in its colour
        assertEquals(listOf(0, 2, 3), v.tabs[1].ghosts.map { it.second })
        assertEquals(1, v.tabs[1].tint)
        val o = BandOptions(radius = v.defaultRadius, brush = true, tolX = 0.05, tolY = 0.05)
        val d = BandEdit.begin(v.tabs[2], 0.5, 0.5, o, inStrip = false, xStrip = false)!!
        val t = BandEdit.update(d, 0.5, 0.9)
        val w = t.writesFrom(v.tabs[2])
        assertTrue(w.isNotEmpty() && w.all { it.first.startsWith("y[2][") })
        val items0 = items(c)
        c.bandEnd(raw, w)
        waitFor("one item") { items(c) == items0 + 1 }
        waitFor("G written") { num(params(c, raw), "y[2][2]")?.let { it > 0.85 } == true }
        assertEquals("R untouched", 0.5, num(params(c, raw), "y[1][2]")!!, 1e-9)
        // the tab is the controller's page of the module's notebook
        val m = TestSetup.schema.modules.getValue("rawdenoise")
        val tabs = BandHost.tabsId(m, params(c, raw))!!
        c.chooseTab(raw, tabs, 3)
        assertEquals(3, c.tabPage(c.state.value, raw, tabs))
        assertEquals(BandHost.graphId(m), m.allControls().first { it is Control.Unsupported && it.kind == "graph" }.id)
    }

    // ------------------------------------------------------------------ denoise (profiled)

    @Test fun denoiseWaveletTabsFollowTheColourMode() = runBlocking {
        val c = controller()
        val m = TestSetup.schema.modules.getValue("denoiseprofile")
        val tabsIds = m.allControls().filterIsInstance<Control.Tabs>().map { it.id }.toList()
        // the default colour mode is Y0U0V0: luma and chroma, channels 4 and 5
        val p0 = params(c, dn)
        assertTrue(BandModels.denoiseY0U0V0(p0))
        val v = BandModels.build("denoiseprofile", p0)!!
        assertEquals(listOf("luma", "chroma"), v.tabs.map { it.label })
        assertEquals(tabsIds[1], BandHost.tabsId(m, p0))
        assertEquals(7, v.tabs[0].curves[0].count)
        assertEquals(1.0 / 14, v.defaultRadius, 1e-12)
        val o = BandOptions(radius = v.defaultRadius, brush = true, tolX = 0.05, tolY = 0.05)
        val t = BandEdit.update(BandEdit.begin(v.tabs[1], 0.0, 0.5, o, inStrip = false, xStrip = false)!!, 0.0, 0.9)
        val w = t.writesFrom(v.tabs[1])
        assertTrue(w.all { it.first.startsWith("y[5][") })
        val items0 = items(c)
        c.bandEnd(dn, w)
        waitFor("one item") { items(c) == items0 + 1 }
        waitFor("chroma written") { num(params(c, dn), "y[5][0]")?.let { it > 0.85 } == true }
        // RGB: all, R, G, B on channels 0..3, the first notebook
        c.bandApply(dn, listOf("wavelet_color_mode" to JsonPrimitive("MODE_RGB")))
        waitFor("RGB") { !BandModels.denoiseY0U0V0(params(c, dn)) }
        val rgb = BandModels.build("denoiseprofile", params(c, dn))!!
        assertEquals(listOf("all", "R", "G", "B"), rgb.tabs.map { it.label })
        assertEquals(tabsIds[0], BandHost.tabsId(m, params(c, dn)))
        val tr = BandEdit.update(BandEdit.begin(rgb.tabs[3], 1.0, 0.5, o, inStrip = false, xStrip = false)!!, 1.0, 0.1)
        assertTrue(tr.writesFrom(rgb.tabs[3]).all { it.first.startsWith("y[3][") })
        // the band editor hides both notebooks (it draws the tabs itself)
        assertTrue(m.allControls().filterIsInstance<Control.Tabs>().all { BandHost.hides(m, it) })
    }

    // ------------------------------------------------------------------ the display scale of the curve graphs

    @Test fun logScaleIsTonecurveCsToLogAndItsInverse() {
        for (base in listOf(0.0, 0.5, 4.0, 40.0)) for (k in 0..20) {
            val x = k / 20.0
            val l = CurveScale.toLog(x, base)
            val expect = if (base > 0) Math.log(x * base + 1) / Math.log(base + 1) else x
            assertEquals(expect, l, 1e-12)
            assertEquals(x, CurveScale.toLin(l, base), 1e-12)
        }
        // the grid of dt_draw_loglog_grid: the quarters through to_log
        assertEquals(listOf(0.25, 0.5, 0.75), CurveScale.grid(4, 0.0))
        assertEquals(Math.log(0.25 * 10 + 1) / Math.log(11.0), CurveScale.grid(4, 10.0)[0], 1e-12)
        // a view maps values to the screen and back
        val v = CurveView(logBase = 20.0)
        assertEquals(0.3, v.valueX(v.screenX(0.3)), 1e-12)
        assertEquals(0.7, v.valueY(v.screenY(0.7)), 1e-12)
        assertTrue("the shadows are spread", v.screenX(0.1) > 0.3)
    }

    @Test fun rgbCurvePinchKeepsThePointUnderTheFingersAndClamps() {
        // rgbcurve.c _scrolled: linx = x / zoom + offset; zoom *= f, at least 1; offset = linx - x / zoom, in 0..(zoom - 1) / zoom
        val one = CurveView.IDENTITY
        val z = CurveScale.pinch(one, 0.5, 0.5, 0.5, 0.5, 2.0)
        assertEquals(2.0, z.zoom, 0.0)
        assertEquals(0.25, z.offsetX, 1e-12)
        assertEquals(0.25, z.offsetY, 1e-12)
        // the value under the centre stays there
        assertEquals(0.5, z.screenX(0.5), 1e-12)
        // at a corner the offset clamps to the graph
        val c = CurveScale.pinch(one, 0.0, 1.0, 0.0, 1.0, 4.0)
        assertEquals(0.0, c.offsetX, 0.0)
        assertEquals(0.75, c.offsetY, 1e-12)
        // zooming out stops at 1 (offsets back to 0), and in at the app's upper limit
        val back = CurveScale.pinch(z, 0.3, 0.3, 0.3, 0.3, 0.1)
        assertEquals(1.0, back.zoom, 0.0)
        assertEquals(0.0, back.offsetX, 0.0)
        assertEquals(CurveScale.ZOOM_MAX, CurveScale.pinch(one, 0.5, 0.5, 0.5, 0.5, 1e6).zoom, 0.0)
        // moving the centre pans: the curve point under the old centre comes under the new one
        val p = CurveScale.pinch(z, 0.5, 0.5, 0.4, 0.5, 1.0)
        assertEquals(0.4, p.screenX(0.5), 1e-12)
        // the picker mark follows the view (CurvePick uses _curve_to_mouse)
        assertEquals(z.screenX(0.6), app.dtandroid.darkroom.ui.graph.CurvePick.curveToMouse(0.6, z.zoom, z.offsetX), 1e-12)
    }

    @Test fun scaleSlidersAreTheAppsAndWriteNothing() = runBlocking {
        val c = controller()
        for ((op, node) in listOf("tonecurve" to 10, "basecurve" to 5)) {
            val m = TestSetup.schema.modules.getValue(op)
            val s = m.allControls().first { it.id == node }
            assertTrue("$op node $node is desktop's scale for graph", GraphViews.isScaleSlider(op, s))
            s as Control.Slider
            assertEquals(0.0, s.softMin, 0.0)
            assertEquals(40.0, s.softMax, 0.0)
        }
        assertFalse(GraphViews.isScaleSlider("rgbcurve", TestSetup.schema.modules.getValue("tonecurve").allControls().first { it.id == 10 }))
        val items0 = items(c)
        val tone = InstanceKey("tonecurve", 0)
        GraphViews.logBase[tone] = 20.0
        GraphViews.logBase[base] = 10.0
        // tone curve: L only (to_log leaves a and b linear); base curve: its one curve
        assertEquals(20.0, GraphViews.view(tone, 0).logBase, 0.0)
        assertEquals(0.0, GraphViews.view(tone, 1).logBase, 0.0)
        assertEquals(10.0, GraphViews.view(base, 0).logBase, 0.0)
        delay(100)
        assertEquals("a display scale writes no history item", items0, items(c))
        GraphViews.logBase.clear()
    }
}

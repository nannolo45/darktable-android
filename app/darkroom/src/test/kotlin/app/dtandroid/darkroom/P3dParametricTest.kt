// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.PickStats
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3d stage 2b (JVM): parametric masks and the refinement on the stand-in engine: the mode, the channels, the
 * marker order, the reset by the whole range, one history item per drag, the colour space question, the refinement
 * values, and the range a picked area gives (desktop's scaling).
 */
class P3dParametricTest {
    @get:Rule val tmp = TemporaryFolder()

    private val exposure = InstanceKey("exposure", 0)

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

    private suspend fun onExposure(c: DarkroomController) {
        c.setView(BottomView.MASKS)
        c.masks.select(exposure)
        withTimeout(10_000) { while (c.parametric.state.value.blend == null) delay(5) }
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun ch(c: DarkroomController, n: String) = c.parametric.state.value.blend!!.parametric[n]!!

    @Test fun markersKeepTheirOrder() {
        val r = listOf(0.2, 0.4, 0.6, 0.8)
        assertEquals(0.4, RangeMath.move(r, 2, 0.1)[2], 0.0)      // stops at the marker to its left, touching
        assertEquals(0.6, RangeMath.move(r, 1, 0.9)[1], 0.0)      // stops at the marker to its right
        assertEquals(0.0, RangeMath.move(r, 0, -3.0)[0], 0.0)
        assertEquals(1.0, RangeMath.move(r, 3, 3.0)[3], 0.0)
        val m = RangeMath.move(RangeMath.move(r, 1, 0.55), 2, 0.5)
        assertTrue(m.zipWithNext().all { (a, b) -> a <= b })
        // markers that touch: both are candidates, the whole range's markers too
        assertEquals(listOf(0, 1), RangeMath.candidates(RangeMath.FULL, 0.0, 0.05))
        assertTrue(RangeMath.candidates(r, 0.5, 0.01).isEmpty())
        assertEquals(listOf(1), RangeMath.candidates(r, 0.41, 0.05))
        assertTrue(RangeMath.isFull(RangeMath.FULL))
        assertFalse(RangeMath.isFull(r))
    }

    @Test fun parametricModeChannelsAndOneItemPerDrag() = runBlocking {
        val c = controller()
        onExposure(c)
        assertTrue(MaskMode.PARAMETRIC.available)
        val n0 = items(c)
        c.masks.setMode(MaskMode.PARAMETRIC)
        assertEquals(MaskMode.PARAMETRIC, c.masks.state.value.module!!.mode)
        assertEquals(n0 + 1, items(c))
        val spec = ParamChannels.of(c.parametric.state.value.space).first { it.base == "g" }
        c.parametric.addChannel(spec)
        assertTrue(ch(c, "g_in").active)
        assertTrue(RangeMath.isFull(ch(c, "g_in").range)) // a whole-range row: the screen shows the warning
        val n1 = items(c)
        c.parametric.setRange("g_in", listOf(0.2, 0.3, 0.7, 0.8))
        assertEquals(listOf(0.2, 0.3, 0.7, 0.8), ch(c, "g_in").range)
        assertEquals(n1 + 1, items(c))
        c.parametric.setRange("g_in", listOf(0.25, 0.3, 0.7, 0.8))
        assertEquals(n1 + 2, items(c))
        // a range out of order is put in order, never refused
        c.parametric.setRange("g_in", listOf(0.5, 0.4, 0.9, 0.8))
        val r = ch(c, "g_in").range
        assertTrue(r.zipWithNext().all { (a, b) -> a <= b })
        // double tap: the whole range
        c.parametric.setRange("g_in", RangeMath.FULL)
        assertTrue(RangeMath.isFull(ch(c, "g_in").range))
        c.parametric.setInvert("g_in", true)
        assertTrue(ch(c, "g_in").invert)
        c.parametric.setRange("g_out", listOf(0.1, 0.2, 0.3, 0.4))
        c.parametric.removeChannel(spec)
        assertFalse(ch(c, "g_in").active)
        assertFalse(ch(c, "g_in").invert)
        assertTrue(RangeMath.isFull(ch(c, "g_out").range))
        c.undo()
        assertTrue(ch(c, "g_out").range == listOf(0.1, 0.2, 0.3, 0.4))
    }

    @Test fun colourSpaceAsksFirstAndResets() = runBlocking {
        val c = controller()
        onExposure(c)
        c.masks.setMode(MaskMode.PARAMETRIC)
        val g = ParamChannels.of(c.parametric.state.value.space).first { it.base == "g" }
        c.parametric.addChannel(g)
        c.parametric.setRange("g_in", listOf(0.2, 0.3, 0.7, 0.8))
        val n = items(c)
        c.parametric.askSpace("lab")
        assertEquals(ParamSheet.ConfirmSpace("lab"), c.parametric.state.value.sheet)
        assertEquals(n, items(c))
        assertEquals(listOf(0.2, 0.3, 0.7, 0.8), ch(c, "g_in").range)
        c.parametric.openSheet(null) // cancel
        assertEquals("rgb_scene", c.parametric.state.value.space)
        c.parametric.askSpace("lab")
        c.parametric.confirmSpace()
        assertNull(c.parametric.state.value.sheet)
        assertEquals("lab", c.parametric.state.value.space)
        assertEquals(n + 1, items(c))
        assertTrue(c.parametric.state.value.blend!!.parametric.values.none { it.active })
        assertTrue(c.parametric.state.value.blend!!.parametric.containsKey("L_in"))
    }

    @Test fun refinementValuesAndReset() = runBlocking {
        val c = controller()
        onExposure(c)
        c.masks.setMode(MaskMode.PARAMETRIC)
        val n = items(c)
        c.parametric.setRefine("feathering_radius", 12.5)
        c.parametric.setRefine("blur_radius", 3.0)
        c.parametric.setRefine("brightness", -0.2)
        c.parametric.setRefine("contrast", 0.4)
        c.parametric.setRefine("details", 0.5)
        c.parametric.setGuide("output_before_blur")
        val b = c.parametric.state.value.blend!!
        assertEquals(12.5, b.featheringRadius!!, 1e-9)
        assertEquals(3.0, b.blurRadius!!, 1e-9)
        assertEquals(-0.2, b.brightness!!, 1e-9)
        assertEquals(0.4, b.contrast!!, 1e-9)
        assertEquals(0.5, b.details!!, 1e-9)
        assertEquals("output_before_blur", c.parametric.state.value.guide)
        assertEquals(n + 6, items(c))
        // out of the engine's limits: a note, no change
        c.parametric.setRefine("feathering_radius", 900.0)
        assertEquals(12.5, c.parametric.state.value.blend!!.featheringRadius!!, 1e-9)
        assertTrue(c.state.value.message != null) // the engine refuses it (RANGE); the message line says so
        c.parametric.resetRefine()
        val d = c.parametric.state.value.blend!!
        assertEquals(0.0, d.featheringRadius!!, 0.0); assertEquals(0.0, d.blurRadius!!, 0.0)
        assertEquals(0.0, d.brightness!!, 0.0); assertEquals(0.0, d.contrast!!, 0.0); assertEquals(0.0, d.details!!, 0.0)
        assertEquals(ParametricMasks.DEFAULT_GUIDE, c.parametric.state.value.guide)
    }

    @Test fun rangeFromAPickedArea() {
        // scene RGB, channel g from an RGB pick (module space "none" is the buffer's own = RGB here)
        val s = PickStats("none", listOf(0.2, 0.2, 0.2, 0.0), listOf(0.1, 0.1, 0.1, 0.0), listOf(0.3, 0.3, 0.3, 0.0), listOf(0, 0, 1, 1))
        val r = PickRange.fromStats("rgb_scene", "g", s, 0.0)!!
        assertEquals(4, r.size)
        assertTrue(r.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(0.09, r[0], 1e-9); assertEquals(0.11, r[1], 1e-9); assertEquals(0.29, r[2], 1e-9); assertEquals(0.31, r[3], 1e-9)
        // a boost of +1 EV halves the scaled values
        val half = PickRange.fromStats("rgb_scene", "R", s, 1.0)!!
        assertEquals(0.04, half[0], 1e-9)
        // Lab L: 0..100 to 0..1
        val lab = PickStats("lab", listOf(50.0, 0.0, 0.0, 0.0), listOf(40.0, -5.0, -5.0, 0.0), listOf(60.0, 5.0, 5.0, 0.0), listOf(0, 0, 1, 1))
        val l = PickRange.fromStats("lab", "L", lab, 0.0)!!
        assertEquals(0.39, l[0], 1e-9); assertEquals(0.61, l[3], 1e-9)
        // a point or a thin area: the inner markers stay in order and inside 0..1
        val p = PickRange.markers(0.0, 0.0)
        assertEquals(4, p.size)
        assertTrue(p.zipWithNext().all { (a, b) -> a <= b } && p.all { it in 0.0..1.0 })
        // a channel the pick cannot give: no range
        assertNull(PickRange.fromStats("rgb_scene", "Jz", s, 0.0))
        assertNull(PickRange.fromStats("lab", "h", lab, 0.0))
    }

    @Test fun pickerIdsAreBelowTheSchemasControls() = runBlocking {
        val c = controller()
        onExposure(c)
        c.masks.setMode(MaskMode.PARAMETRIC)
        val id = c.parametric.pickerId("R_in")
        assertTrue(id <= ParametricMasks.PICK_BASE)
        assertTrue(c.pickerSpec(exposure, id)!!.area)
    }
}

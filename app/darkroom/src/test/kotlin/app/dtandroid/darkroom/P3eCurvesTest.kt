// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.ui.graph.CurveEdit
import app.dtandroid.darkroom.ui.graph.CurveLayout
import app.dtandroid.darkroom.ui.graph.CurveNode
import app.dtandroid.darkroom.ui.graph.LevelsEdit
import app.dtandroid.darkroom.ui.graph.ToneEqMath
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.paramAt
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3e (JVM): the graph editors' writes on the stand-in engine. A drag is one history item however many steps it
 * has; adding, removing and resetting are one step each; nodes come back in order with the count; channels are
 * independent; levels, the tone equalizer's drag on the photo and the histogram.
 */
class P3eCurvesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tone = InstanceKey("tonecurve", 0)
    private val rgb = InstanceKey("rgbcurve", 0)
    private val levels = InstanceKey("rgblevels", 0)
    private val eq = InstanceKey("toneequal", 0)
    private val toneL = CurveLayout.TONECURVE
    private val rgbL = CurveLayout.RGBCURVE
    private fun n(x: Double, y: Double) = CurveNode(x, y)
    private val three = listOf(n(0.0, 0.0), n(0.5, 0.5), n(1.0, 1.0))

    private fun controller(): DarkroomController {
        val dir = tmp.root
        val engine = app.dtandroid.darkroom.InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 600)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = false) }
        return c
    }

    private fun params(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }.params
    private fun items(c: DarkroomController) = c.state.value.history.items.size

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) {
            throw AssertionError("timed out: $what")
        }

    @Test fun aDragIsOneHistoryItemAndTheNodesComeBackInOrder() = runBlocking {
        val c = controller()
        val items0 = items(c)
        for (y in listOf(0.55, 0.6, 0.65, 0.7)) c.curveDrag(tone, toneL.writes(0, CurveEdit.move(three, 1, 0.5, y), previous = 2))
        c.curveEnd(tone)
        waitFor("the last step is written") { toneL.read(params(c, tone), 0).getOrNull(1)?.y == 0.7 }
        waitFor("one item") { items(c) == items0 + 1 }
        assertEquals(3, toneL.countOf(params(c, tone), 0))
        assertTrue(CurveEdit.valid(toneL.read(params(c, tone), 0)))
        assertTrue("a write switches the module on", c.state.value.modules.first { it.key == tone }.enabled)
        // a second drag is a second item
        c.curveDrag(tone, toneL.writes(0, CurveEdit.move(three, 1, 0.4, 0.5), previous = 3))
        c.curveEnd(tone)
        waitFor("second item") { items(c) == items0 + 2 }
    }

    @Test fun addingAndRemovingAreOneStepEach() = runBlocking {
        val c = controller()
        val items0 = items(c)
        val (added, at) = CurveEdit.add(three, 0.25, 0.4, toneL.maxNodes)!!
        c.curveSet(tone, toneL.writes(0, added, previous = 2))
        assertEquals(items0 + 1, items(c))
        assertEquals(3 + 1, toneL.countOf(params(c, tone), 0))
        assertEquals(0.25, toneL.read(params(c, tone), 0)[at].x, 1e-12)
        val removed = CurveEdit.remove(added, at)
        c.curveSet(tone, toneL.writes(0, removed, previous = 4))
        assertEquals(items0 + 2, items(c))
        assertEquals(3, toneL.countOf(params(c, tone), 0))
        assertEquals("the vacated slot is zero", 0.0, (params(c, tone).paramAt("tonecurve[0][3].x") as JsonPrimitive).doubleOrNull!!, 0.0)
        assertEquals(removed, toneL.read(params(c, tone), 0))
    }

    @Test fun channelsAreIndependent() = runBlocking {
        val c = controller()
        val ab = listOf(n(0.0, 0.0), n(0.4, 0.2), n(1.0, 1.0))
        c.curveSet(tone, toneL.writes(1, ab, previous = 0))
        assertEquals(ab, toneL.read(params(c, tone), 1))
        assertEquals("L untouched: the default", CurveEdit.IDENTITY, toneL.read(params(c, tone), 0))
        c.curveSet(tone, toneL.writes(2, three, previous = 0))
        assertEquals(ab, toneL.read(params(c, tone), 1))
        assertEquals(three, toneL.read(params(c, tone), 2))
    }

    @Test fun resetWritesTheDefaultCurveAndMethodInOneStep() = runBlocking {
        val c = controller()
        c.curveSet(tone, toneL.writes(0, three, previous = 0) + (toneL.typePath(0) to JsonPrimitive(0L)))
        val items0 = items(c)
        c.curveSet(tone, toneL.writes(0, toneL.defaults(0), previous = 3) + (toneL.typePath(0) to JsonPrimitive(CurveLayout.DEFAULT_TYPE.toLong())))
        assertEquals(items0 + 1, items(c))
        assertEquals(CurveEdit.IDENTITY, toneL.read(params(c, tone), 0))
        assertEquals(CurveLayout.DEFAULT_TYPE, toneL.readType(params(c, tone), 0))
    }

    @Test fun anRgbCurveKeepsItsOwnNamesAndChannels() = runBlocking {
        val c = controller()
        val g = listOf(n(0.0, 0.1), n(0.3, 0.4), n(0.7, 0.6), n(1.0, 0.9))
        c.curveSet(rgb, rgbL.writes(1, g, previous = 2))
        assertEquals(4, rgbL.countOf(params(c, rgb), 1))
        assertEquals(g, rgbL.read(params(c, rgb), 1))
        assertEquals(CurveEdit.IDENTITY, rgbL.read(params(c, rgb), 0))
        assertEquals(0.3, (params(c, rgb).paramAt("curve_nodes[1][1].x") as JsonPrimitive).doubleOrNull!!, 0.0)
    }

    @Test fun levelsAreWrittenPerChannel() = runBlocking {
        val c = controller()
        val items0 = items(c)
        val l = LevelsEdit.move(doubleArrayOf(0.0, 0.5, 1.0), 0, 0.2, 0.5)
        c.curveDrag(levels, LevelsEdit.writes(1, l))
        c.curveEnd(levels)
        waitFor("written") { LevelsEdit.read(params(c, levels), 1)[0] == 0.2 }
        waitFor("one item") { items(c) == items0 + 1 }
        assertEquals(0.6, LevelsEdit.read(params(c, levels), 1)[1], 1e-12)
        assertEquals("channel 0 untouched", 0.0, LevelsEdit.read(params(c, levels), 0)[0], 0.0)
    }

    @Test fun aVerticalDragOnThePhotoRaisesTheBandsAndIsOneItem() = runBlocking {
        val c = controller()
        c.toggleExpanded(eq)
        assertEquals("the editor of the open module", eq, c.toneEqWanted(c.state.value))
        val items0 = items(c)
        c.toneEqStart(eq, 0.5f, 0.5f)
        for (i in 1..10) c.toneEqMove(-0.02 * i) // up
        waitFor("the drag has its exposure") { c.toneEqDragging }
        c.toneEqEnd() // the modules are read again when the finger lifts
        waitFor("a band went up") { ToneEqMath.read(params(c, eq)).max() > 0.05 }
        waitFor("sealed, one item") { items(c) == items0 + 1 }
        assertTrue("the module is on", c.state.value.modules.first { it.key == eq }.enabled)
        assertTrue(ToneEqMath.read(params(c, eq)).all { it in -2.0..2.0 })
        // down lowers
        c.toneEqStart(eq, 0.5f, 0.5f)
        for (i in 1..10) c.toneEqMove(0.04 * i)
        waitFor("the second drag has its exposure") { c.toneEqDragging }
        c.toneEqEnd()
        waitFor("lowered") { ToneEqMath.read(params(c, eq)).min() < -0.05 }
        waitFor("second item") { items(c) == items0 + 2 }
    }

    @Test fun theHistogramIsRead() = runBlocking {
        val c = controller()
        val h = c.histogramNow()
        assertNotNull(h)
        assertEquals(256, h!!.bins)
        assertEquals(3, h.counts.size)
        assertTrue(c.histogramCache === h)
    }
}

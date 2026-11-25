// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brief P4b deliverables 4 and 5: while a slider is dragged, steps that come while a drag picture renders are merged
 * (the newest value wins, PLAN.md 3.9, since P2b); the last value is never dropped, the full picture follows when the
 * finger lifts. Measured here on the fake engine with the delay gate G0 measured for one drag picture: how many renders
 * a quick drag costs. JVM numbers, not phone numbers; written to build/p4b-coalescing.txt.
 */
class P4bCoalescingTest {
    @get:Rule val tmp = TemporaryFolder()

    /** counts the renders and makes each drag picture take [previewMs] */
    private class Counting(val base: FakeEngine, val previewMs: Long) : DarktableEngine by base {
        val previews = AtomicInteger()
        val regions = AtomicInteger()
        override suspend fun renderPreview(maxSize: Int): RenderedImage = renderPreview(maxSize, RenderOptions.NONE)
        override suspend fun renderPreview(maxSize: Int, options: RenderOptions): RenderedImage {
            previews.incrementAndGet(); delay(previewMs); return base.renderPreview(maxSize, options)
        }
        override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double): RenderedImage = renderRegion(x, y, w, h, scale, RenderOptions.NONE)
        override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double, options: RenderOptions): RenderedImage {
            regions.incrementAndGet(); return base.renderRegion(x, y, w, h, scale, options)
        }
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) = withTimeout(10_000) { while (!cond()) delay(5) }.also { assertTrue(what, cond()) }

    @Test
    fun aQuickDragRendersLessThanItsStepsAndKeepsTheLastValue() = runBlocking<Unit> {
        val dir = tmp.root
        val base = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        base.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path))
        val e = Counting(base, previewMs = 90)
        val c = DarkroomController(e, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir), CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900)
        c.start(InterfacePreview.series(File(dir, "series")), 0, false, false)
        val key = InstanceKey("exposure", 0)
        val slider = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        waitFor("first picture") { c.state.value.preview != null }
        delay(300)
        val n0 = c.state.value.history.items.size
        val p0 = e.previews.get()

        // a finger moving for about a second: 60 steps, one every 16 ms (one per screen frame)
        val steps = 60
        val t0 = System.nanoTime()
        var last = 0.0
        for (i in 1..steps) {
            last = 0.5 + i * 0.025
            c.drag(key, slider.id, slider.binding, last, false)
            delay(16)
        }
        val dragRenders = e.previews.get() - p0
        c.endDrag(key, slider.id, slider.binding, false)
        waitFor("drag applied") { c.liveValues.value.isEmpty() }
        waitFor("the full picture after the finger lifts") { e.previews.get() > p0 + dragRenders && c.state.value.preview != null }
        val ms = (System.nanoTime() - t0) / 1_000_000

        val exposure = (c.state.value.modules.first { it.key == key }.params["exposure"] as JsonPrimitive).doubleOrNull!!
        assertEquals("the last value is never dropped", last, exposure, 1e-9)
        assertEquals("one history item for the drag", n0 + 1, c.state.value.history.items.size)
        assertTrue("steps were merged: $dragRenders drag pictures for $steps steps", dragRenders in 1 until steps)
        File("build").mkdirs()
        File("build/p4b-coalescing.txt").writeText(
            "P4b coalescing (JVM, fake engine, 90 ms per drag picture as gate G0 measured; not phone numbers): $steps drag steps 16 ms apart " +
                "-> $dragRenders drag pictures, then the full picture on release; ${ms} ms from first step to the full picture; exposure ends at $exposure\n")
    }
}

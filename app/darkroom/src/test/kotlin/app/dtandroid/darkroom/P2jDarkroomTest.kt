// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Binding
import app.dtandroid.schema.ComputedList
import app.dtandroid.schema.ComputedSlider
import app.dtandroid.schema.Control
import app.dtandroid.schema.SpektrafilmStocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Brief P2j: computed controls through the darkroom on the stand-in engine (writes, history steps, the XMP). */
class P2jDarkroomTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun controller(): DarkroomController {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false) }
        return c
    }

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    private fun DarkroomController.params(op: String) = state.value.modules.first { it.op == op }.params
    private fun DarkroomController.num(op: String, n: String) = (params(op)[n] as JsonPrimitive).content.toDouble()

    @Test fun colorCalibrationHueDragIsOneHistoryStepAndShowsWhatWasSet() = runBlocking {
        val c = controller()
        waitFor("preview") { c.state.value.preview != null }
        val key = InstanceKey("channelmixerrgb", 0)
        c.setParam(key, "illuminant", JsonPrimitive("DT_ILLUMINANT_CUSTOM"))
        val ms = TestSetup.schema.modules.getValue("channelmixerrgb")
        val hue = ms.control(11) as Control.Slider
        val chroma = ms.control(12) as Control.Slider
        assertEquals(Binding.Computed, hue.binding)
        c.setSlider(key, chroma.binding, 30.0, false, chroma.id)
        val before = c.state.value.history.items.size
        for (h in listOf(100.0, 120.0, 140.0)) c.drag(key, hue.id, hue.binding, h, false)
        c.endDrag(key, hue.id, hue.binding, false)
        waitFor("drag written") { c.liveValues.value.isEmpty() && c.state.value.history.items.size > before }
        delay(200)
        assertEquals("the drag is one history step", before + 1, c.state.value.history.items.size)
        assertEquals(140.0, c.sliderValue(c.state.value, key, hue, emptyMap())!!, 0.05)
        assertEquals(30.0, c.sliderValue(c.state.value, key, chroma, emptyMap())!!, 0.01)
        // the parameters are what the formula writes
        val w = (ms.computed.getValue(11) as ComputedSlider).write(140.0, c.params("channelmixerrgb")) { null }
        assertEquals(w.getValue("x").content.toDouble(), c.num("channelmixerrgb", "x"), 1e-6)
    }

    @Test fun hueIsKeptWhileChromaIsZero() = runBlocking {
        val c = controller()
        waitFor("preview") { c.state.value.preview != null }
        val key = InstanceKey("channelmixerrgb", 0)
        c.setParam(key, "illuminant", JsonPrimitive("DT_ILLUMINANT_CUSTOM"))
        val ms = TestSetup.schema.modules.getValue("channelmixerrgb")
        val hue = ms.control(11) as Control.Slider
        val chroma = ms.control(12) as Control.Slider
        c.setSlider(key, chroma.binding, 0.0, false, chroma.id)
        c.setSlider(key, hue.binding, 250.0, false, hue.id)
        assertEquals("desktop keeps the hue on screen at chroma 0", 250.0, c.sliderValue(c.state.value, key, hue, emptyMap())!!, 1e-9)
        c.setSlider(key, chroma.binding, 20.0, false, chroma.id)
        assertEquals("and uses it when chroma comes back", 250.0, c.sliderValue(c.state.value, key, hue, emptyMap())!!, 0.05)
    }

    @Test fun spektrafilmFilmStockIsChosenAndSurvivesTheXmp() = runBlocking {
        val c = controller()
        waitFor("preview") { c.state.value.preview != null }
        val key = InstanceKey("spektrafilm", 0)
        val film = TestSetup.schema.modules.getValue("spektrafilm").computed.getValue(2) as ComputedList
        val before = c.state.value.history.items.size
        c.chooseComputed(key, 2, film.entries(c.params("spektrafilm")).indexOfFirst { it.label == "Kodak Ektar 100" })
        assertEquals("one history step", before + 1, c.state.value.history.items.size)
        assertEquals(SpektrafilmStocks.nameHash("kodak_ektar_100").toDouble(), c.num("spektrafilm", "film_hash"), 0.0)
        assertTrue("switched on", c.state.value.modules.first { it.key == key }.enabled)
        c.save()
        c.closePhoto()
        c.openPhoto(0)
        waitFor("reopened") { c.state.value.info != null }
        val p = c.params("spektrafilm")
        assertEquals("Kodak Ektar 100", film.entries(p)[film.current(p)!!].label)
    }

    @Test fun framingListWritesItsTable() = runBlocking {
        val c = controller()
        waitFor("preview") { c.state.value.preview != null }
        val key = InstanceKey("borders", 0)
        val aspect = TestSetup.schema.modules.getValue("borders").computed.getValue(2) as ComputedList
        c.chooseComputed(key, 2, aspect.entries(c.params("borders")).indexOfFirst { it.label == "4:3" })
        assertEquals((4f / 3f).toDouble(), c.num("borders", "aspect"), 0.0)
        assertEquals("4:3", aspect.entries(c.params("borders"))[aspect.current(c.params("borders"))!!].label)
        // "custom..." writes nothing
        val h = c.state.value.history.items.size
        c.chooseComputed(key, 2, aspect.entries(c.params("borders")).indexOfFirst { it.label == "custom..." })
        assertEquals(h, c.state.value.history.items.size)
    }
}

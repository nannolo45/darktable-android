// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
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
 * Brief P4c item 1 (JVM, stand-in engine): while retouch's panel is open the module has the engine's focus (the whole,
 * uncropped picture, crop's frame as a guide), it leaves when the panel closes, it is paused around renders for
 * LightPhotoLibrary, no history item comes from it, the uncropped preview is not the filmstrip's picture, and a heal
 * spot placed outside the crop's frame is kept in the edit where it was placed.
 */
class P4cRetouchFocusTest {
    @get:Rule val tmp = TemporaryFolder()

    private val retouch = InstanceKey(RETOUCH_OP, 0)
    private lateinit var engine: FakeEngine

    private fun controller(): DarkroomController {
        val dir = tmp.root
        engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 600)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = false) }
        return c
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) {
            throw AssertionError("timed out: $what")
        }

    private fun items(c: DarkroomController) = c.state.value.history.items.size

    /** the photo open with a crop of the middle (as a user's earlier edit) */
    private suspend fun cropped(c: DarkroomController) {
        waitFor("photo open") { c.state.value.modules.any { it.key == retouch } && !c.state.value.loading }
        c.withEngine { e ->
            e.paramsSet("crop", 0, mapOf("cx" to JsonPrimitive(0.25), "cy" to JsonPrimitive(0.2), "cw" to JsonPrimitive(0.75), "ch" to JsonPrimitive(0.8)))
            e.setEnabled("crop", 0, true)
            e.historySeal()
        }
    }

    private suspend fun openRetouch(c: DarkroomController) {
        c.toggleExpanded(retouch)
        c.retouch.open(retouch)
        waitFor("retouch focused") { c.retouch.state.value.focus?.focused == true && c.retouch.state.value.st != null }
    }

    @Test fun theOpenPanelHasTheFocusAndTheFrame() = runBlocking {
        val c = controller()
        cropped(c)
        val h0 = items(c)
        openRetouch(c)
        val f = c.retouch.state.value.focus!!
        assertTrue("crop is left out", f.leftOut.any { it.op == "crop" })
        val frame = assertNotNull(f.frame).let { f.frame!! }
        assertEquals(listOf(0.25 to 0.2, 0.75 to 0.2, 0.75 to 0.8, 0.25 to 0.8), frame)
        assertTrue("the view is uncropped", c.retouch.uncropped())
        assertEquals("the focus adds no history item", h0, items(c))
        assertEquals(listOf("begin"), engine.retouchFocusCalls.filter { it != "state" })
        // the panel closes: the focus leaves, the edit's own picture again
        c.toggleExpanded(retouch)
        waitFor("focus ended") { "end" in engine.retouchFocusCalls }
        assertNull(c.retouch.state.value.focus)
        val st = c.withEngine { e -> e.retouchFocus(RETOUCH_OP, 0, "state") }
        assertFalse("the engine is not focused", st.focused)
        assertFalse(c.retouch.uncropped())
        assertEquals("no history item", h0, items(c))
    }

    @Test fun anotherModuleTakesTheFocusAway() = runBlocking {
        val c = controller()
        cropped(c)
        openRetouch(c)
        c.toggleExpanded(InstanceKey("exposure", 0))
        waitFor("focus ended") { "end" in engine.retouchFocusCalls }
        assertFalse(c.withEngine { e -> e.retouchFocus(RETOUCH_OP, 0, "state") }.focused)
    }

    @Test fun lightPhotoLibraryRendersSeeTheCroppedEdit() = runBlocking {
        val c = controller()
        cropped(c)
        openRetouch(c)
        val cur = c.state.value.current!!
        val h0 = items(c)
        val inside = c.withPhotoSession(cur.rawPath, cur.xmpPath) { e -> e.retouchFocus(RETOUCH_OP, 0, "state") }!!
        assertTrue("focused", inside.focused)
        assertTrue("paused inside the session", inside.paused)
        assertTrue("nothing left out while paused", inside.leftOut.isEmpty())
        val after = c.withEngine { e -> e.retouchFocus(RETOUCH_OP, 0, "state") }
        assertTrue(after.focused)
        assertFalse("resumed after it", after.paused)
        assertEquals("no history item", h0, items(c))
    }

    @Test fun theUncroppedPreviewIsNotTheFilmstripsPicture() = runBlocking {
        val c = controller()
        cropped(c)
        c.renderNow()
        val before = c.state.value.thumbnails[c.state.value.index]
        assertNotNull(before)
        openRetouch(c)
        c.renderNow()
        assertTrue("the same thumbnail as before the focus", c.state.value.thumbnails[c.state.value.index] === before)
    }

    @Test fun aHealSpotOutsideTheFrameIsKeptWhereItWasPlaced() = runBlocking {
        val c = controller()
        cropped(c)
        openRetouch(c)
        val h0 = items(c)
        // left of and above the frame (0.25, 0.2): outside the edit's picture, inside the whole one
        c.retouch.setTool(RetouchTool.CIRCLE)
        assertTrue(c.retouch.touchDown(0.1, 0.1, 0.01, 0.01))
        c.retouch.touchUp(0.1, 0.1, 0.01, 0.01)
        waitFor("shape placed") { (c.retouch.state.value.st?.shapes?.size ?: 0) == 1 }
        assertEquals("one history item", h0 + 1, items(c))
        val s = c.retouch.state.value.st!!.shapes.single()
        assertEquals("heal", s.algorithm)
        val center = (s.geometry["center"] as JsonArray).map { (it as JsonPrimitive).doubleOrNull!! }
        assertEquals(0.1, center[0], 1e-6)
        assertEquals(0.1, center[1], 1e-6)
        val o = c.retouch.state.value.outlines.single()
        assertTrue("its outline is drawn outside the frame", o.targetAt.first < 0.25 && o.targetAt.second < 0.2)
        // still there after the panel closed and opened again
        c.toggleExpanded(retouch)
        waitFor("focus ended") { "end" in engine.retouchFocusCalls }
        openRetouch(c)
        assertEquals(1, c.retouch.state.value.st!!.shapes.size)
        assertTrue((c.retouch.state.value.st!!.shapes.single().geometry["center"] as JsonArray).let { (it[0] as JsonPrimitive).doubleOrNull == 0.1 })
        assertTrue(c.retouch.state.value.outlines.single().let { it.targetAt.first < 0.25 } && c.retouch.state.value.focus!!.frame != null)
        Unit
    }
}

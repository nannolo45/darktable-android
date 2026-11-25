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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
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
 * Brief P3n (JVM, stand-in engine): the panel takes and gives back the module's focus; structure buttons (toggle as
 * desktop's, enhancements); the finger as desktop's mouse (tap = left button, long press = right button, the area
 * switch = shift, a drag after a long press, two fingers cancel); a corner drag and a drawn line are one history item
 * when the finger lifts; fit buttons and variants; a slider of the focused module runs gui_changed; undo; another
 * photo resets. The engine's own behaviour (darktable's code) is tested by the bridge test C33.
 */
class P3nPerspectiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ashift = InstanceKey(ASHIFT_OP, 0)

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

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) {
            throw AssertionError("timed out: $what")
        }

    private suspend fun open(c: DarkroomController) {
        waitFor("photo open") { c.state.value.modules.any { it.key == ashift } && !c.state.value.loading }
        c.toggleExpanded(ashift)
        c.perspective.open(ashift)
        waitFor("focused") { c.perspective.state.value.st?.focused == true }
    }

    private fun st(c: DarkroomController) = c.perspective.state.value.st!!
    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun param(c: DarkroomController, k: String) =
        (c.state.value.modules.first { it.key == ashift }.params[k] as JsonPrimitive).content.toDouble()

    /** one finger: down, moves, up, with times in ms */
    private fun finger(c: DarkroomController, pts: List<Pair<Double, Double>>, t0: Long = 1000, holdMs: Long = 0, r: Double = 0.02) {
        assertTrue("the module takes the finger", c.perspective.touchDown(pts[0].first, pts[0].second, t0, r))
        var t = t0 + holdMs
        for (p in pts.drop(1)) { t += 16; c.perspective.touchMove(p.first, p.second, t, 0.005) }
        c.perspective.touchUp(pts.last().first, pts.last().second, t + 16)
    }

    @Test fun thePanelTakesTheFocusAndGivesItBack() = runBlocking {
        val c = controller()
        open(c)
        assertTrue(c.perspective.active(c.state.value))
        assertEquals("none", st(c).method)
        assertFalse("fit buttons off without structure (desktop)", st(c).fitEnabled)
        assertFalse("no structure: the finger pans", c.perspective.takesFinger())
        c.toggleExpanded(ashift) // collapsed: the focus leaves
        waitFor("closed") { c.perspective.state.value.key == null }
        assertFalse(c.perspective.active(c.state.value))
    }

    @Test fun structureButtonsToggleAsDesktop() = runBlocking {
        val c = controller()
        open(c)
        val h0 = items(c)
        c.perspective.structure("rectangle")
        waitFor("rectangle") { st(c).method == "rectangle" }
        assertEquals(4, st(c).handles.size)
        assertTrue(st(c).fitEnabled)
        assertTrue("the module is switched on: one item", items(c) > h0)
        c.perspective.structure("rectangle")
        waitFor("off again") { st(c).method == "none" }
        c.perspective.structure("lines_found", "edges")
        waitFor("lines") { st(c).method == "lines_found" }
        assertTrue(st(c).lines.any { it.kind == "vertical_used" } && st(c).lines.any { it.kind == "horizontal_outlier" })
        assertEquals(st(c).lines.count { it.kind == "vertical_used" }, st(c).vertical)
    }

    @Test fun aTapSelectsALongPressDeselects() = runBlocking {
        val c = controller()
        open(c)
        c.perspective.structure("lines_found")
        waitFor("lines") { st(c).method == "lines_found" }
        val l = st(c).lines.first { it.kind == "vertical_used" }
        val mid = (l.x1 + l.x2) / 2 to (l.y1 + l.y2) / 2
        val v0 = st(c).vertical
        finger(c, listOf(mid), holdMs = Perspective.LONG_PRESS_MS + 50, r = 0.005)
        waitFor("deselected") { st(c).vertical == v0 - 1 }
        finger(c, listOf(mid), r = 0.005)
        waitFor("selected again") { st(c).vertical == v0 }
    }

    @Test fun theAreaSwitchIsShift() = runBlocking {
        val c = controller()
        open(c)
        c.perspective.structure("lines_found")
        waitFor("lines") { st(c).method == "lines_found" }
        c.perspective.toggleAreaMode()
        finger(c, listOf(0.0 to 0.0, 0.5 to 0.5, 1.0 to 1.0), holdMs = Perspective.LONG_PRESS_MS + 50)
        waitFor("all out") { st(c).vertical == 0 && st(c).horizontal == 0 }
        c.perspective.fit("both")
        waitFor("message") { c.perspective.state.value.note != null }
        assertEquals(0.0, param(c, "rotation"), 0.0)
        finger(c, listOf(0.0 to 0.0, 0.5 to 0.5, 1.0 to 1.0))
        waitFor("all in") { st(c).vertical >= 4 }
    }

    @Test fun aCornerDragIsOneItemWhenTheFingerLifts() = runBlocking {
        val c = controller()
        open(c)
        c.perspective.structure("rectangle")
        waitFor("rectangle") { st(c).method == "rectangle" }
        val h0 = items(c)
        assertTrue(c.perspective.touchDown(0.2, 0.2, 1000, 0.02))
        c.perspective.touchMove(0.23, 0.21, 1020, 0.005)
        c.perspective.touchMove(0.25, 0.22, 1040, 0.005)
        waitFor("moving") { st(c).handles.any { kotlin.math.abs(it.x - 0.25) < 1e-9 } }
        assertEquals("nothing written while the finger is down", h0, items(c))
        c.perspective.touchUp(0.25, 0.22, 1060)
        waitFor("written") { items(c) == h0 + 1 }
        assertEquals(0.25, st(c).handles[0].x, 1e-9)
    }

    @Test fun drawnLinesAndLongPressDelete() = runBlocking {
        val c = controller()
        open(c)
        c.perspective.structure("drawn")
        waitFor("drawn") { st(c).method == "drawn" }
        assertTrue("draw mode takes the finger without lines", c.perspective.takesFinger())
        finger(c, listOf(0.3 to 0.2, 0.3 to 0.5, 0.31 to 0.8))
        waitFor("one line") { st(c).lines.size == 1 }
        assertEquals("vertical_used", st(c).lines[0].kind)
        finger(c, listOf(0.1 to 0.4, 0.5 to 0.41, 0.9 to 0.42))
        waitFor("two lines") { st(c).lines.size == 2 && st(c).horizontal == 1 }
        val h0 = items(c)
        finger(c, listOf(0.6 to 0.415), holdMs = Perspective.LONG_PRESS_MS + 50, r = 0.01)
        waitFor("deleted") { st(c).lines.size == 1 && items(c) == h0 + 1 }
    }

    @Test fun twoFingersEndWhatTheFingerBegan() = runBlocking {
        val c = controller()
        open(c)
        c.perspective.structure("drawn")
        waitFor("drawn") { st(c).method == "drawn" }
        val h0 = items(c)
        assertTrue(c.perspective.touchDown(0.4, 0.2, 1000, 0.02))
        c.perspective.touchMove(0.4, 0.6, 1020, 0.005)
        c.perspective.touchCancel()
        waitFor("ended where it was") { st(c).lines.size == 1 && items(c) == h0 + 1 }
        assertNull(c.perspective.state.value.finger)
    }

    @Test fun fitButtonsAndVariants() = runBlocking {
        val c = controller()
        open(c)
        c.perspective.structure("lines_found")
        waitFor("lines") { st(c).method == "lines_found" }
        val h0 = items(c)
        c.perspective.fit("vertical", FitVariant.ROTATION)
        waitFor("fit") { items(c) == h0 + 1 }
        assertTrue(param(c, "rotation") != 0.0)
        assertEquals("rotation only", 0.0, param(c, "lensshift_v"), 0.0)
        c.perspective.fit("both", FitVariant.SHIFT)
        waitFor("fit 2") { items(c) == h0 + 2 }
        assertTrue(param(c, "lensshift_v") != 0.0 && param(c, "lensshift_h") != 0.0)
        c.perspective.fit("both")
        waitFor("fit 3") { items(c) == h0 + 3 }
        assertTrue("plain both fits shear too (desktop)", param(c, "shear") != 0.0)
        c.undo()
        waitFor("undone") { items(c) == h0 + 2 || c.state.value.history.end == h0 + 2 }
        waitFor("state read again after undo") { c.perspective.state.value.st != null }
    }

    @Test fun cropModeOriginalFormatIsDraggable() = runBlocking {
        val c = controller()
        open(c)
        c.setParam(ashift, "cropmode", JsonPrimitive("ASHIFT_CROP_ASPECT"))
        waitFor("clip") { st(c).cropmode == 2 && st(c).clip != null }
        assertTrue("a crop to drag: the finger goes to the module", c.perspective.takesFinger())
        val l0 = st(c).crop[0]
        val h0 = items(c)
        finger(c, listOf(0.5 to 0.5, 0.48 to 0.5, 0.45 to 0.5))
        waitFor("moved") { items(c) == h0 + 1 }
        assertTrue(st(c).crop[0] < l0)
    }

    @Test fun anotherPhotoResets() = runBlocking {
        val c = controller()
        open(c)
        c.perspective.structure("rectangle")
        waitFor("rectangle") { st(c).method == "rectangle" }
        c.perspective.reset()
        assertNull(c.perspective.state.value.key)
        assertNotNull(c.state.value.info)
    }

    @Test fun lightPhotoLibraryRendersSeeTheCroppedEdit() = runBlocking {
        val c = controller()
        open(c)
        c.perspective.structure("rectangle")
        waitFor("rectangle") { st(c).method == "rectangle" }
        val cur = c.state.value.current!!
        val h0 = items(c)
        // a session on the open photo (LightPhotoLibrary's preview): the focus is paused inside it
        val inside = c.withPhotoSession(cur.rawPath, cur.xmpPath) { e -> e.ashift(ASHIFT_OP, 0, kotlinx.serialization.json.buildJsonObject { put("action", "state") }).focused }
        assertEquals("paused inside the session", false, inside)
        val after = c.withEngine { e -> e.ashift(ASHIFT_OP, 0, kotlinx.serialization.json.buildJsonObject { put("action", "state") }) }
        assertTrue("focused again after it", after.focused)
        assertEquals("the rectangle is kept", "rectangle", after.method)
        assertEquals("no history item", h0, items(c))
    }

    @Test fun linesAreKeptWhenTheEngineSendsNone() {
        val full = app.dtandroid.engine.api.PerspectiveState.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"focused":true,"enabled":true,"method":"lines_found","structure":{"lines_found":true,"rectangle":false,"drawn":false},
               "fit_enabled":true,"vertical":1,"horizontal":0,"flipped":false,"handled":false,"size":[10,10],"rev":7,
               "lines":[{"p":[0.1,0.1,0.1,0.9],"kind":"vertical_used","color":"green"}],"handles":[],"near_lines":[],"near_handles":[],
               "area":null,"clip":null,"crop":[0,1,0,1],"params":{"rotation":0,"lensshift_v":0,"lensshift_h":0,"shear":0,"cropmode":1,"saved_lines":0},"message":null}"""))
        val next = app.dtandroid.engine.api.PerspectiveState.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"focused":true,"enabled":true,"method":"lines_found","structure":{"lines_found":true,"rectangle":false,"drawn":false},
               "fit_enabled":true,"vertical":1,"horizontal":0,"flipped":false,"handled":false,"size":[10,10],"rev":7,
               "lines":null,"handles":null,"near_lines":[0],"near_handles":[],
               "area":null,"clip":null,"crop":[0,1,0,1],"params":{"rotation":0,"lensshift_v":0,"lensshift_h":0,"shear":0,"cropmode":1,"saved_lines":0},"message":null}"""), full)
        assertEquals(1, next.lines.size)
        assertTrue("near marks come with every answer", next.lines[0].near)
        assertFalse(full.lines[0].near)
    }
}

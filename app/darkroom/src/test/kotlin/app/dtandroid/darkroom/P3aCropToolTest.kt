// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.History
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.schema.CropTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3a deliverables 1 to 3 on the stand-in engine: the tool opens with crop's panel, the frame is dragged
 * and moved without touching the edit, leaving writes one history item (none without a change), and none of the
 * cases of deliverable 3 lets a temporary state reach the XMP or the history: a sync's writeIfOpen while the tool
 * is open and while its uncropped picture renders, "done", the app killed with the tool open (nothing written),
 * "before", history navigation, undo and redo, the read-only case, and a render that fails.
 * Brief P1b part 2 deliverable 2: the uncropped picture is a render that leaves crop out (`bypass`), so the
 * engine's edit, undo and redo steps and the XMP are never touched by opening the tool; the tests that pinned
 * P3a's switch-off, render and undo bracket (its redo step hidden, a failed undo stopping every write) now pin
 * that, and the sharp region when zoomed in is rendered while the tool is open.
 * (The stand-in engine does not crop its picture; the emulator test checks the picture on the real engine.)
 */
class P3aCropToolTest {
    @get:Rule val tmp = TemporaryFolder()

    private val crop = InstanceKey("crop", 0)
    private val spec by lazy { TestSetup.schema.modules.getValue("crop").aspect!! }

    /** The stand-in engine, with hooks to watch the renders that leave crop out, and to break them. */
    private class Hooked(val e: DarktableEngine) : DarktableEngine by e {
        val bypassStarted = CompletableDeferred<Unit>()
        @Volatile var failBypass = false
        @Volatile var undos = 0
        @Volatile var switches = 0
        val bypassPreviews = java.util.concurrent.atomic.AtomicInteger(0)
        val bypassRegions = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun setEnabled(op: String, priority: Int, enabled: Boolean) {
            switches++
            e.setEnabled(op, priority, enabled)
        }
        override suspend fun undo() {
            undos++
            e.undo()
        }
        override suspend fun renderPreview(maxSize: Int, options: RenderOptions): RenderedImage {
            if (options.bypass.isNotEmpty()) {
                bypassPreviews.incrementAndGet()
                bypassStarted.complete(Unit)
                if (failBypass) throw EngineException(EngineError.ENGINE, "render broken for the test")
            }
            return e.renderPreview(maxSize, options)
        }
        override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double, options: RenderOptions): RenderedImage {
            if (options.bypass.isNotEmpty()) bypassRegions.incrementAndGet()
            return e.renderRegion(x, y, w, h, scale, options)
        }
    }

    private fun controller(timing: FakeEngine.Timing = FakeEngine.Timing.NONE, index: Int = 0): Pair<DarkroomController, Hooked> {
        val dir = tmp.root
        val engine = Hooked(InterfacePreview.engine(TestSetup.schemaText, timing))
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), index, filmstrip = false, thumbnails = false) }
        return c to engine
    }

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    private fun DarkroomController.cropParams() = state.value.modules.first { it.key == crop }.let { m ->
        listOf("cx", "cy", "cw", "ch").map { (m.params[it] as JsonPrimitive).content.toDouble() } to m.enabled
    }

    private fun DarkroomController.xmp() = File(state.value.current!!.xmpPath)

    /** Crop on with a 3:2 frame (as a photo cropped earlier), its XMP written: the state the tool starts from. */
    private suspend fun cropped(c: DarkroomController) {
        c.chooseAspect(crop, spec.entries.indexOfFirst { it.label.startsWith("3:2") })
        c.setSlider(crop, Binding.Param("cx"), 0.1, false)
        assertTrue(c.cropParams().second)
        c.save()
    }

    private suspend fun openTool(c: DarkroomController) {
        if (c.state.value.expanded != crop) c.toggleExpanded(crop)
        c.syncCropTool()
        waitFor("tool open") { c.state.value.cropTool?.picture != null }
    }

    private suspend fun closeTool(c: DarkroomController) {
        if (c.state.value.expanded == crop) c.toggleExpanded(crop)
        c.syncCropTool()
        waitFor("tool closed") { c.state.value.cropTool == null }
    }

    /** A finger on the frame's bottom right corner, pulled up and left by [d]. */
    private fun dragCorner(c: DarkroomController, d: Float = 0.1f) {
        val g = c.state.value.cropTool!!.clip
        val grab = c.cropHit(g.right - 0.01f, g.bottom - 0.01f, 0.05f, 0.05f)
        assertEquals(CropTool.GRAB_BOTTOM_RIGHT, grab)
        c.cropDragStart(grab, g.right - 0.01f, g.bottom - 0.01f)
        c.cropDragMove(g.right - 0.01f - d / 2, g.bottom - 0.01f - d / 2)
        c.cropDragMove(g.right - 0.01f - d, g.bottom - 0.01f - d)
        c.cropDragEnd()
    }

    /** A finger inside the frame, moving it by [dx], [dy]. */
    private fun moveFrame(c: DarkroomController, dx: Float, dy: Float) {
        val g = c.state.value.cropTool!!.clip
        val x = g.x + g.w / 2
        val y = g.y + g.h / 2
        val grab = c.cropHit(x, y, 0.05f, 0.05f)
        assertEquals(CropTool.GRAB_CENTER, grab)
        c.cropDragStart(grab, x, y)
        c.cropDragMove(x + dx, y + dy)
        c.cropDragEnd()
    }

    // ------------------------------------------------------------------ deliverables 1 and 2

    @Test fun theToolOpensOnTheUncroppedPictureWithoutChangingTheEdit() = runBlocking {
        val (c, e) = controller()
        cropped(c)
        val h0 = c.state.value.history
        val m0 = c.state.value.modules
        val undo0 = e.undoState()
        openTool(c)
        val t = c.state.value.cropTool!!
        // the frame from the parameters; the size of crop's input
        val (p, on) = c.cropParams()
        assertTrue(on)
        assertEquals(p[0], t.clip.x.toDouble(), 1e-6)
        assertEquals(p[2], t.clip.right.toDouble(), 1e-6)
        assertEquals(c.state.value.info!!.width, t.inW) // the stand-in's picture is not cropped
        // the edit, the history and the undo steps are as before: crop was left out of one render, nothing else
        assertEquals(h0, c.state.value.history)
        assertEquals(h0, e.history())
        assertEquals(m0, c.state.value.modules)
        assertEquals("no switch-off, no undo (P1b part 2)", 0, e.undos)
        assertEquals(0, e.switches)
        assertEquals(1, e.bypassPreviews.get())
        assertEquals(undo0, e.undoState())
        assertEquals(undo0, c.state.value.undo)
    }

    @Test fun dragAndMoveThenLeaveWriteOneHistoryItemAndUndoBringsTheFrameBack() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        val before = c.cropParams()
        val items0 = c.state.value.history.items.size
        openTool(c)
        dragCorner(c)
        moveFrame(c, -0.05f, -0.05f)
        val t = c.state.value.cropTool!!
        assertTrue(t.touched)
        // nothing reached the engine yet
        assertEquals(before, c.cropParams())
        assertEquals(items0, c.state.value.history.items.size)
        val want = t.committed()
        closeTool(c)
        assertEquals("one history item for the session", items0 + 1, c.state.value.history.items.size)
        val (p, on) = c.cropParams()
        assertTrue(on)
        assertEquals(want.left, p[0], 1e-6); assertEquals(want.top, p[1], 1e-6)
        assertEquals(want.right, p[2], 1e-6); assertEquals(want.bottom, p[3], 1e-6)
        assertNotEquals(before.first, p)
        // the ratio was kept (3:2 in pixels of crop's input)
        val info = c.state.value.info!!
        assertEquals(1.5, (p[2] - p[0]) * info.width / ((p[3] - p[1]) * info.height), 2e-3)
        c.undo()
        assertEquals(before, c.cropParams())
        assertEquals(items0, c.state.value.history.end)
    }

    @Test fun noChangeNoHistoryItem() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        val h0 = c.state.value.history
        openTool(c)
        // a drag that ends where it started, and a reopening
        val g = c.state.value.cropTool!!.clip
        c.cropDragStart(CropTool.GRAB_CENTER, g.x + 0.1f, g.y + 0.1f)
        c.cropDragMove(g.x + 0.1f, g.y + 0.1f)
        c.cropDragEnd()
        closeTool(c)
        openTool(c)
        closeTool(c)
        assertEquals(h0, c.state.value.history)
    }

    @Test fun openingAndClosingTheToolWithoutAChangeLeavesTheXmpByteEqual() = runBlocking {
        // P3a's limit 1 (P1b part 2 deliverable 2): before, the bracket's switch-off and undo changed the XMP's
        // metadata; now nothing in the engine changes
        val (c, e) = controller()
        cropped(c)
        val xmp0 = c.xmp().readBytes()
        openTool(c)
        closeTool(c)
        c.save()
        assertTrue("XMP byte-equal", xmp0.contentEquals(c.xmp().readBytes()))
        assertEquals(0, e.undos)
    }

    @Test fun cropSwitchedOffStartsFromTheWholePictureWithoutABracket() = runBlocking {
        val (c, e) = controller()
        assertFalse(c.state.value.modules.first { it.key == crop }.enabled)
        val h0 = c.state.value.history
        openTool(c)
        assertEquals(CropTool.Clip.FULL, c.state.value.cropTool!!.clip)
        assertEquals("no bracket when crop is off", 0, e.undos)
        closeTool(c)
        assertEquals("untouched: nothing written", h0, c.state.value.history)
        openTool(c)
        dragCorner(c, 0.2f)
        closeTool(c)
        assertEquals(h0.items.size + 1, c.state.value.history.items.size)
        assertTrue("written frame switches crop on", c.cropParams().second)
    }

    @Test fun marginSlidersAndTheAspectListWorkOnTheFrameInTheTool() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        val before = c.cropParams()
        openTool(c)
        val ms = TestSetup.schema.modules.getValue("crop")
        val left = ms.allControls().filterIsInstance<Control.Slider>().first { (it.binding as? Binding.Param)?.path == "cx" }
        c.drag(crop, left.id, left.binding, 0.2, false)
        assertEquals(0.2, c.state.value.cropTool!!.clip.x.toDouble(), 1e-6)
        assertEquals("the slider shows the frame", 0.2, c.sliderValue(c.state.value, crop, left, c.liveValues.value)!!, 1e-6)
        c.endDrag(crop, left.id, left.binding, false)
        assertEquals("nothing written while the tool is open", before, c.cropParams())
        // the aspect list: square on the frame in the tool, shown in the list, written on leaving
        c.chooseAspect(crop, spec.entries.indexOfFirst { it.label.startsWith("square") })
        val t = c.state.value.cropTool!!
        assertEquals(1 to 1, t.ratioD to t.ratioN)
        assertEquals(1 to 1, c.cropRatioShown(c.state.value, crop, 3, 2))
        assertEquals(1.0, t.clip.w * t.inW.toDouble() / (t.clip.h * t.inH), 1e-4)
        assertEquals(before, c.cropParams())
        val items = c.state.value.history.items.size
        closeTool(c)
        assertEquals(items + 1, c.state.value.history.items.size)
        val m = c.state.value.modules.first { it.key == crop }
        assertEquals(1, (m.params["ratio_d"] as JsonPrimitive).content.toInt())
    }

    // ------------------------------------------------------------------ deliverable 3: no temporary state anywhere

    @Test fun aSyncWhileTheToolIsOpenWritesTheEditFromBeforeTheTool() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        val xmp0 = c.xmp().readBytes()
        openTool(c)
        dragCorner(c)
        // LightPhotoLibrary's writeIfOpen during a sync job
        c.saveIfOpen(c.state.value.current!!.rawPath)
        assertTrue("the XMP holds the frame from before the tool", xmp0.contentEquals(c.xmp().readBytes()))
        assertNotNull("the tool stays open", c.state.value.cropTool)
        assertTrue(c.state.value.cropTool!!.touched)
    }

    @Test fun aSyncDuringTheBracketWaitsAndWritesTheEdit() = runBlocking {
        // a slow preview, so the sync's write arrives while the uncropped picture renders (P3a: while crop was
        // switched off for it; P1b part 2: crop is only left out of that render)
        val (c, e) = controller(FakeEngine.Timing(previewFixedMs = 400, regionFixedMs = 100, msPerMegapixel = 0))
        cropped(c)
        val xmp0 = c.xmp().readBytes()
        c.toggleExpanded(crop)
        val tool = async(Dispatchers.Default) { c.syncCropTool() }
        withTimeout(10_000) { e.bypassStarted.await() }
        c.saveIfOpen(c.state.value.current!!.rawPath)
        tool.await()
        waitFor("tool open") { c.state.value.cropTool?.picture != null }
        assertTrue("written with crop on", xmp0.contentEquals(c.xmp().readBytes()))
        assertEquals(0, e.undos)
    }

    @Test fun doneWritesTheFrameKilledWritesNothing() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        val xmpFile = c.xmp()
        val xmp0 = xmpFile.readBytes()
        val stamp = xmpFile.lastModified()
        openTool(c)
        dragCorner(c)
        moveFrame(c, 0.02f, 0.0f)
        val want = c.state.value.cropTool!!.committed()
        // killed here: nothing writes the XMP while the tool is open
        delay(200)
        assertTrue(xmp0.contentEquals(xmpFile.readBytes()))
        assertEquals(stamp, xmpFile.lastModified())
        // "done": the tool is left, the frame written, then the XMP
        c.closePhoto()
        assertFalse(xmp0.contentEquals(xmpFile.readBytes()))
        c.openPhoto(0, saveCurrent = false)
        val (p, on) = c.cropParams()
        assertTrue(on)
        assertEquals(want.left, p[0], 1e-6); assertEquals(want.bottom, p[3], 1e-6)
    }

    @Test fun beforeWhileTheToolIsOpenChangesNothing() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        openTool(c)
        dragCorner(c)
        val t = c.state.value.cropTool!!
        val h = c.state.value.history
        c.setBefore(true)
        waitFor("before picture") { c.state.value.beforePreview != null }
        c.setBefore(false)
        assertEquals(h, c.state.value.history)
        assertEquals(t.clip, c.state.value.cropTool!!.clip)
        val xmp0 = c.xmp().readBytes()
        c.saveIfOpen(c.state.value.current!!.rawPath)
        assertTrue(xmp0.contentEquals(c.xmp().readBytes()))
    }

    @Test fun historyNavigationLeavesTheToolFirst() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        val n0 = c.state.value.history.items.size
        openTool(c)
        dragCorner(c)
        // tap on an earlier history step: the frame is written first (one item), then the history goes there
        c.historyGoto(n0 - 1)
        val h: History = c.state.value.history
        assertEquals(n0 + 1, h.items.size)
        assertEquals("crop", h.items.last().op)
        assertEquals(n0 - 1, h.end)
        // the tool is open again on that state, untouched
        waitFor("tool again") { c.state.value.cropTool?.picture != null }
        assertFalse(c.state.value.cropTool!!.touched)
        c.historyGoto(h.items.size)
        assertEquals(h.items.size, c.state.value.history.end)
    }

    @Test fun undoAndRedoWhileTheToolIsOpen() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        val before = c.cropParams()
        openTool(c)
        dragCorner(c)
        val want = c.state.value.cropTool!!.committed()
        c.undo() // the frame is written, then undone: the frame from before the tool
        assertEquals(before, c.cropParams())
        assertNotNull(c.state.value.cropTool)
        assertEquals(before.first[2], c.state.value.cropTool!!.clip.right.toDouble(), 1e-6)
        // the frame's step can be redone (only crop changed: the uncropped picture was kept, no new bracket)
        assertTrue(c.state.value.undo.canRedo)
        c.redo()
        assertEquals(want.right, c.cropParams().first[2], 1e-6)
    }

    @Test fun theBracketsRedoStepIsNeverOffered() = runBlocking {
        // P3a's limit 2, P1b part 2 deliverable 2: the user's redo steps survive opening the tool (P3a's bracket
        // emptied the redo queue and hid its own step; now the tool's render touches neither)
        val (c, e) = controller()
        cropped(c)
        c.setSlider(InstanceKey("exposure", 0), Binding.Param("exposure"), 0.7, false)
        val m1 = c.state.value.modules
        c.undo()
        assertTrue(c.state.value.undo.canRedo)
        openTool(c)
        assertTrue("redo still offered with the tool open", c.state.value.undo.canRedo)
        assertTrue(e.undoState().canRedo)
        closeTool(c)
        assertTrue("and after it", c.state.value.undo.canRedo)
        c.redo()
        assertEquals(m1, c.state.value.modules)
        assertFalse(c.state.value.undo.canRedo)
    }

    @Test fun readOnlyPhotoHasNoTool() = runBlocking {
        val (c, e) = controller(index = InterfacePreview.READ_ONLY_INDEX)
        assertTrue(c.state.value.readOnly)
        val h0 = c.state.value.history
        c.toggleExpanded(crop)
        c.syncCropTool()
        delay(100)
        assertNull(c.state.value.cropTool)
        assertNull(c.cropWanted(c.state.value))
        assertEquals(0, e.undos)
        assertEquals(h0, c.state.value.history)
    }

    @Test fun anUndoThatFailsStopsEveryWrite() = runBlocking {
        // P1b part 2: there is no undo in opening the tool any more; a render of the uncropped picture that fails
        // leaves the tool closed and the edit as it was, and writes go on as before
        val (c, e) = controller()
        cropped(c)
        val xmp0 = c.xmp().readBytes()
        val h0 = c.state.value.history
        e.failBypass = true
        c.toggleExpanded(crop)
        c.syncCropTool()
        waitFor("message") { c.state.value.message?.contains("render broken for the test") == true }
        assertNull(c.state.value.cropTool)
        assertEquals(h0, c.state.value.history)
        assertEquals(0, e.undos)
        c.saveIfOpen(c.state.value.current!!.rawPath)
        c.save()
        assertTrue(xmp0.contentEquals(c.xmp().readBytes()))
        val out = File(tmp.root, "copy.xmp")
        c.writeXmp(out.path)
        assertTrue(out.isFile)
        val r = c.withPhotoSession(c.state.value.current!!.rawPath, c.state.value.current!!.xmpPath) { "rendered" }
        assertEquals("rendered", r)
        // the render works again: the tool opens
        e.failBypass = false
        c.syncCropTool()
        waitFor("tool open") { c.state.value.cropTool?.picture != null }
    }

    @Test fun zoomedInTheToolShowsItsSharpRegion() = runBlocking {
        // P3a's limit 3 (P1b part 2 deliverable 2): zoomed in with the tool open, the region of the uncropped picture
        val (c, e) = controller()
        cropped(c)
        openTool(c)
        val t = c.state.value.cropTool!!
        assertEquals(RenderOptions.bypass("crop", 0), t.options)
        c.setViewport(Viewport(400, 300, 3f, 0.4f, 0.6f))
        waitFor("region") { c.state.value.region != null }
        assertTrue(c.state.value.regionOfCropTool)
        assertTrue(e.bypassRegions.get() >= 1)
        assertEquals(c.state.value.history, e.history())
        // the tool closed: the photo's own picture again, the tool's region is not shown
        c.setViewport(Viewport(400, 300))
        closeTool(c)
        assertEquals(0, e.undos)
    }

    @Test fun anotherModulesChangeWhileTheToolIsOpenWritesTheFrameFirst() = runBlocking {
        val (c, _) = controller()
        cropped(c)
        val n0 = c.state.value.history.items.size
        openTool(c)
        dragCorner(c)
        val want = c.state.value.cropTool!!.committed()
        // a power button in the list while crop's panel is open
        c.setEnabled(InstanceKey("exposure", 0), !c.state.value.modules.first { it.op == "exposure" }.enabled)
        val h = c.state.value.history
        assertEquals(n0 + 2, h.items.size)
        assertEquals("crop", h.items[n0].op)
        assertEquals("exposure", h.items[n0 + 1].op)
        assertEquals(want.right, c.cropParams().first[2], 1e-6)
        waitFor("tool again") { c.state.value.cropTool?.picture != null && !c.state.value.cropTool!!.touched }
    }

    // ------------------------------------------------------------------ deliverable 4: rotation and straighten

    private fun DarkroomController.rotation() = rotationValue(state.value)

    @Test fun theRotationSliderTurnsThePictureThenWritesOneItem() = runBlocking {
        val (c, e) = controller()
        cropped(c)
        openTool(c)
        dragCorner(c)
        val n0 = c.state.value.history.items.size
        val undos = e.undos
        assertNotNull(c.rotationSlider())
        // the finger moves: only the screen changes
        c.cropRotationDrag(0.5); c.cropRotationDrag(1.0); c.cropRotationDrag(1.5)
        assertEquals(1.5, c.state.value.cropTool!!.rotationLive!!, 0.0)
        assertEquals(0.0, c.rotation(), 0.0)
        assertEquals(n0, c.state.value.history.items.size)
        // the finger lifts: the frame first, then the rotation, one item each; the tool opens again on a new picture
        c.cropRotate(1.5)
        val h = c.state.value.history
        assertEquals(n0 + 2, h.items.size)
        assertEquals("crop", h.items[n0].op)
        assertEquals("ashift", h.items[n0 + 1].op)
        assertEquals(1.5, c.rotation(), 1e-6)
        assertTrue(c.state.value.modules.first { it.op == "ashift" }.enabled)
        waitFor("tool again") { c.state.value.cropTool?.picture != null }
        assertNull(c.state.value.cropTool!!.rotationLive)
        assertEquals("the uncropped picture rendered again", undos + 1, e.undos)
    }

    @Test fun straightenSetsTheRotationAsDesktop() = runBlocking {
        val (c, _) = controller()
        openTool(c)
        val r0 = c.rotation()
        c.cropStraighten(true)
        assertTrue(c.state.value.cropTool!!.straighten)
        // a horizon falling 2° to the right, drawn on a picture shown 1000 x 667 pixels
        val wd = 1000f
        val ht = 667f
        val dy = (0.6f * wd * Math.tan(Math.toRadians(2.0)).toFloat()) / ht
        c.cropLine(0.2f, 0.5f, 0.8f, 0.5f + dy)
        val a = c.cropLineEnd(wd, ht, 44f)
        assertEquals(-2f, a, 1e-3f)
        assertEquals(r0 - 2.0, c.rotation(), 1e-3)
        assertFalse(c.state.value.cropTool?.straighten ?: false)
        // a line shorter than a touch target is a tap: nothing changes
        openTool(c)
        c.cropStraighten(true)
        c.cropLine(0.5f, 0.5f, 0.51f, 0.5f)
        assertEquals(0f, c.cropLineEnd(wd, ht, 44f), 0f)
        assertEquals(r0 - 2.0, c.rotation(), 1e-3)
    }

    @Test fun aPinchThatStartsOnTheFrameGivesTheFrameBack() = runBlocking {
        val (c, _) = controller()
        openTool(c)
        val g0 = c.state.value.cropTool!!
        c.cropDragStart(CropTool.GRAB_CENTER, 0.5f, 0.5f)
        c.cropDragCancel()
        assertEquals(g0.clip, c.state.value.cropTool!!.clip)
        assertEquals(g0.touched, c.state.value.cropTool!!.touched)
    }
}

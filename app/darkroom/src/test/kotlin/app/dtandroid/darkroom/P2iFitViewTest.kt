// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.ImageInfo
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brief P2i deliverables 1 to 3 and 7 (first point) on the stand-in engine: at fit the darkroom shows the
 * preview pipe's picture at the frame's size and never starts the full pipe; zoomed in, the preview scaled up
 * at once and then the sharp region; back at fit, a region render in flight is stopped; a photo the user opens
 * is always tried, even when remembered as having no picture.
 */
class P2iFitViewTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The stand-in engine, recording the preview sizes asked and the region renders. */
    private class Recording(val e: FakeEngine) : DarktableEngine by e {
        val previewAsks: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val regions = AtomicInteger()
        override suspend fun renderPreview(maxSize: Int): RenderedImage { previewAsks += maxSize; return e.renderPreview(maxSize) }
        override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double): RenderedImage {
            regions.incrementAndGet(); return e.renderRegion(x, y, w, h, scale)
        }
    }

    private val exposureKey = InstanceKey("exposure", 0)
    private val slider by lazy {
        TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
    }

    /** A phone's darkroom frame: 1080 px wide, a portrait screen. */
    private val phone = Viewport(1080, 1500)

    /** The app's sizes (preview 1024 px, fit preview at most [DarkroomController.FIT_PREVIEW_MAX]). */
    private fun controller(timing: FakeEngine.Timing, vp: Viewport = phone): Pair<DarkroomController, Recording> {
        val dir = tmp.root
        val engine = Recording(InterfacePreview.engine(TestSetup.schemaText, timing))
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default))
        c.setViewport(vp)
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = false) }
        return c to engine
    }

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    private fun expectedSize(c: DarkroomController, vp: Viewport): Int {
        val info = c.state.value.info!!
        return DarkroomController.previewSizeFor(vp.widthPx, vp.heightPx, info.width, info.height, 1024, DarkroomController.FIT_PREVIEW_MAX)
    }

    /** Deliverable 1: the rule for the preview's size. */
    @Test fun previewSizeFollowsTheFrameWithinItsBounds() {
        val f = DarkroomController::previewSizeFor
        // a phone in portrait, a 3:2 landscape photo: the frame is 1080 px wide
        assertEquals(1080, f(1080, 1500, 6000, 4000, 1024, 1152))
        // a portrait photo there: the frame is 1500 px high, more than the time target allows
        assertEquals(1152, f(1080, 1500, 4000, 6000, 1024, 1152))
        // a small view never gets less than before P2i
        assertEquals(1024, f(400, 300, 6000, 4000, 1024, 1152))
        // a tablet frame: the bound
        assertEquals(1152, f(2560, 1500, 6000, 4000, 1024, 1152))
        // the view's size not known: as before P2i
        assertEquals(1024, f(0, 0, 6000, 4000, 1024, 1152))
        // a lower bound above the upper one wins (tests that ask for a fixed preview size)
        assertEquals(900, f(400, 300, 6000, 4000, 900, 600))
    }

    /** Deliverables 1 and 2: at fit, a change renders the preview at the frame's size, and no region. */
    @Test fun atFitAChangeRendersThePreviewOnly() = runBlocking {
        val (c, e) = controller(FakeEngine.Timing.NONE)
        waitFor("first preview") { c.state.value.preview != null }
        val want = expectedSize(c, phone)
        assertTrue("the frame's size, $want px, is more than the old 1024 px", want > 1024)
        waitFor("preview at the frame's size") { e.previewAsks.lastOrNull() == want && c.state.value.preview!!.image.let { maxOf(it.width, it.height) } == want }
        c.setSlider(exposureKey, slider.binding, 1.0, false)
        waitFor("preview after the change") { c.renderTimes.value.previewPx == want }
        delay(300)
        assertEquals("no full pipe at fit", 0, e.regions.get())
        assertEquals(0, c.regionRenders.get())
        assertNull(c.state.value.region)
        assertFalse(c.state.value.regionPending)
        assertNull("nothing is said at fit", c.state.value.working)
        assertTrue("every preview at the frame's size once the frame was known: ${e.previewAsks}", e.previewAsks.drop(1).all { it == want })
    }

    /** Deliverable 4 on the stand-in: a change at fit waits for no region render (the region costs 3 s here). */
    @Test fun aChangeAtFitIsFollowedByThePreviewInThePreviewsTime() = runBlocking {
        val (c, e) = controller(FakeEngine.Timing(previewFixedMs = 40, regionFixedMs = 3000, msPerMegapixel = 30))
        waitFor("first preview") { c.state.value.preview != null }
        delay(200)
        for (i in 1..3) {
            Timeline.clear()
            val t0 = System.nanoTime()
            c.setSlider(exposureKey, slider.binding, 0.5 * i, false)
            waitFor("preview after change $i") { Timeline.first("darkroom: preview shown") != null }
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("change $i at fit: preview shown after $ms ms (preview render ${c.renderTimes.value.previewMs} ms)")
            assertTrue("change $i: $ms ms, want well under the region's 3000 ms", ms < 1500)
        }
        delay(300)
        assertEquals(0, e.regions.get())
    }

    /** Deliverable 2: zoomed in, the preview stays (scaled up) at once, then the region; "rendering…" meanwhile. */
    @Test fun zoomInShowsThePreviewAtOnceThenTheRegion() = runBlocking {
        val (c, e) = controller(FakeEngine.Timing(previewFixedMs = 10, regionFixedMs = 400, msPerMegapixel = 10))
        waitFor("first preview") { c.state.value.preview != null && c.renderTimes.value.previewPx == expectedSize(c, phone) }
        val preview = c.state.value.preview
        c.setViewport(phone.copy(zoom = 3f, centerX = 0.4f, centerY = 0.6f))
        assertSame("the preview is kept, scaled up", preview, c.state.value.preview)
        waitFor("rendering… while the region renders") { c.state.value.regionPending }
        waitFor("region, and nothing pending") { c.state.value.region != null && !c.state.value.regionPending }
        assertSame("no new preview for a zoom", preview, c.state.value.preview)
        assertEquals(1, e.regions.get())
        // a change while zoomed in: preview, then the region again
        c.setSlider(exposureKey, slider.binding, 1.0, false)
        waitFor("region after the change") { e.regions.get() == 2 && c.state.value.region != null }
    }

    /** Deliverable 2: back to fit while the region renders: the render is stopped, the region dropped. */
    @Test fun backToFitStopsTheRegionInFlight() = runBlocking {
        val (c, e) = controller(FakeEngine.Timing(previewFixedMs = 10, regionFixedMs = 3000, msPerMegapixel = 10))
        waitFor("first preview") { c.state.value.preview != null }
        Timeline.clear()
        c.setViewport(phone.copy(zoom = 3f))
        waitFor("region render started") { Timeline.events().any { it.name.startsWith("darkroom: region render start") } }
        val t0 = System.nanoTime()
        c.setViewport(phone)
        waitFor("region render cancelled", 2000) { Timeline.first("darkroom: region render cancelled") != null }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("stopped after $ms ms (the region takes 3000 ms)", ms < 1000)
        waitFor("nothing pending") { !c.state.value.regionPending }
        assertNull(c.state.value.region)
        assertNotNull(c.state.value.preview)
        // a change at fit right after: the preview, no region
        val n = e.regions.get()
        val t1 = System.nanoTime()
        c.setSlider(exposureKey, slider.binding, 1.0, false)
        Timeline.clear()
        waitFor("preview") { Timeline.first("darkroom: preview shown") != null }
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 1500)
        delay(300)
        assertEquals("no region at fit", n, e.regions.get())
        assertNull(c.state.value.region)
    }

    /** Deliverable 1: a frame of another size (a rotation) gets a preview of its size. */
    @Test fun aNewFrameSizeGetsItsPreview() = runBlocking {
        val (c, e) = controller(FakeEngine.Timing.NONE, Viewport(400, 300))
        waitFor("first preview") { c.state.value.preview != null }
        assertEquals(1024, e.previewAsks.last())
        c.setViewport(phone)
        val want = expectedSize(c, phone)
        waitFor("preview at the new frame's size") { e.previewAsks.last() == want && c.renderTimes.value.previewPx == want }
        assertEquals(0, e.regions.get())
    }

    // ------------------------------------------------------------------ deliverable 7, first point

    /** The stand-in engine whose `image_info` fails for [path] the first [failures] times (as for want of memory). */
    private class FailsFirst(val e: FakeEngine, val path: String, var failures: Int) : DarktableEngine by e {
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var current: String? = null
        override suspend fun open(rawPath: String, xmpPath: String?) {
            opens.getOrPut(rawPath) { AtomicInteger() }.incrementAndGet()
            e.open(rawPath, xmpPath); current = rawPath
        }
        override suspend fun close() { e.close(); current = null }
        override suspend fun imageInfo(): ImageInfo {
            if (current == path && failures > 0) { failures--; throw EngineException(EngineError.ENGINE, "image could not be loaded (mipmap 6)") }
            return e.imageInfo()
        }
    }

    /** A photo the user opens is tried even when remembered as having no picture; the memory is for background passes. */
    @Test fun aPhotoTheUserOpensIsAlwaysTried() = runBlocking {
        val dir = tmp.root
        val names = InterfacePreview.samples.keys.sorted()
        val photos = File(dir, "photos").apply { mkdirs() }
        val items = names.take(2).map { n ->
            val f = File(photos, n).apply { writeBytes(ByteArray(2000) { it.toByte() }) }
            SeriesItem(n, f.path, f.path + ".xmp")
        }
        val engine = FailsFirst(InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE), items[1].rawPath, 1)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default))
        c.setViewport(phone)
        c.start(Series("p2i", items), 0, filmstrip = false, thumbnails = false)
        assertNotNull(c.state.value.info)
        // first open: image_info fails
        c.openPhoto(1)
        assertNull(c.state.value.info)
        assertEquals("cannot open ${items[1].fileName}: ${DarkroomController.NO_PICTURE}", c.state.value.message)
        assertTrue(c.knownUnreadable(items[1].rawPath))
        // a background pass does not open it (the memory is for background passes)
        c.closePhoto()
        c.renderThumbnails(items) { it == items[1] }
        assertEquals(1, engine.opens[items[1].rawPath]!!.get())
        // the user opens the unchanged file again: tried, and it opens
        c.openPhoto(1)
        assertNotNull("opened on the second try", c.state.value.info)
        assertNull(c.state.value.message)
        assertEquals(2, engine.opens[items[1].rawPath]!!.get())
        assertFalse("no longer remembered", c.knownUnreadable(items[1].rawPath))
        waitFor("its preview") { c.state.value.preview != null }
    }
}

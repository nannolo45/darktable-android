// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Brief P2f deliverables 2, 3, 4 and 8 on the stand-in engine. */
class P2fDarkroomTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The stand-in engine, counting modules() calls (deliverable 4: how often the app reads them). */
    private class Counting(val e: FakeEngine) : DarktableEngine by e {
        val modulesCalls = AtomicInteger()
        override suspend fun modules(): List<ModuleInstance> { modulesCalls.incrementAndGet(); return e.modules() }
    }

    private class MemStore : ThumbnailStore {
        val thumbs = HashMap<String, RenderedImage>()
        val pictures = HashMap<String, RenderedImage>()
        override fun load(item: SeriesItem) = thumbs[item.fileName]
        override fun store(item: SeriesItem, image: RenderedImage) { thumbs[item.fileName] = image }
        override fun loadPicture(item: SeriesItem) = pictures[item.fileName]
        override fun storePicture(item: SeriesItem, image: RenderedImage) { pictures[item.fileName] = image }
    }

    private fun controller(timing: FakeEngine.Timing, store: ThumbnailStore? = MemStore()): Pair<DarkroomController, Counting> {
        val dir = tmp.root
        val engine = Counting(InterfacePreview.engine(TestSetup.schemaText, timing))
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900, thumbnailStore = store)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false) }
        return c to engine
    }

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    /** Deliverable 2: a background render is cancelled by interactive work, which goes first; it runs again later. */
    @Test fun interactiveWorkCancelsBackgroundWorkWhichRunsAgainLater() = runBlocking {
        // slow renders: about 4 s for a 1920 px picture on the stand-in
        val (c, _) = controller(FakeEngine.Timing(previewFixedMs = 50, regionFixedMs = 100, msPerMegapixel = 1500))
        waitFor("first preview") { c.state.value.preview != null }
        val starts = AtomicInteger()
        val bg = async(Dispatchers.Default) {
            c.bg("test background render") { e ->
                starts.incrementAndGet()
                // as the LightPhotoLibrary preview: the whole photo at 1920 px
                val full = e.renderSize(1.0)
                val sc = 1920.0 / maxOf(full.w, full.h)
                val s = e.renderSize(sc)
                e.renderRegion(0, 0, s.w, s.h, sc)
            }
        }
        waitFor("background render started") { starts.get() == 1 }
        Timeline.clear(); Timeline.mark("bg started")
        delay(100)
        c.openPhoto(1)
        waitFor("preview of the photo opened meanwhile") { c.state.value.preview != null && c.state.value.index == 1 }
        println(Timeline.text())
        // the open starts once the background render has stopped at its next module, not after the whole render
        val asked = Timeline.first("darkroom: open SAMPLE_0002.RAF asked")!!.nanos
        val opened = Timeline.first("darkroom: engine open start", asked)!!.nanos
        val waited = (opened - asked) / 1_000_000
        assertTrue("the open waited $waited ms for the engine (the background render alone takes about 3.7 s)", waited < 1200)
        assertNotNull(Timeline.first("background: test background render put back for interactive work"))
        val img = withTimeout(30_000) { bg.await() }
        assertTrue("the background render ran again from the start", starts.get() >= 2)
        assertTrue(img.width > 0)
    }

    /** Deliverable 2: background work waits while a finger drags. */
    @Test fun backgroundWorkWaitsWhileDragging() = runBlocking {
        val (c, _) = controller(FakeEngine.Timing.NONE)
        waitFor("preview") { c.state.value.preview != null }
        val slider = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        val key = InstanceKey("exposure", 0)
        c.drag(key, slider.id, slider.binding, 0.5, false)
        var ranAt = 0L
        val bg = async(Dispatchers.Default) { c.bg("test") { ranAt = System.nanoTime() } }
        delay(400)
        assertEquals("not while the finger is down", 0L, ranAt)
        val up = System.nanoTime()
        c.endDrag(key, slider.id, slider.binding, false)
        withTimeout(10_000) { bg.await() }
        assertTrue(ranAt > up)
    }

    /** Deliverable 3: the photo's last picture shows at once while it opens again; then the real preview. */
    @Test fun aPhotoOpensWithItsLastPictureNotBlack() = runBlocking {
        val store = MemStore()
        val (c, _) = controller(FakeEngine.Timing(previewFixedMs = 400, regionFixedMs = 100, msPerMegapixel = 60), store)
        waitFor("preview") { c.state.value.preview != null }
        c.closePhoto()
        val name = InterfacePreview.fileName(0)
        assertNotNull("the last picture was kept when the photo closed", store.pictures[name])
        val j = async(Dispatchers.Default) { c.openPhoto(0) }
        waitFor("a picture while it opens") { c.state.value.placeholder != null }
        assertNull(c.state.value.preview)
        assertTrue(c.state.value.working!!, c.state.value.working!!.startsWith("opening") || c.state.value.working == "rendering…")
        j.await()
        waitFor("real preview") { c.state.value.preview != null }
        assertNull("the stored picture goes once the preview is there", c.state.value.placeholder)
        assertNull(c.state.value.working)
    }

    /** Deliverable 4: modules() is not read during a drag, and once when the finger lifts. */
    @Test fun modulesAreReadOnceAfterADragAndNeverDuringIt() = runBlocking {
        val (c, e) = controller(FakeEngine.Timing.NONE)
        waitFor("preview") { c.state.value.preview != null }
        val slider = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        val key = InstanceKey("exposure", 0)
        val before = e.modulesCalls.get()
        for (i in 1..10) { c.drag(key, slider.id, slider.binding, 0.1 * i, false); delay(20) }
        assertEquals("no modules() during the drag", before, e.modulesCalls.get())
        c.endDrag(key, slider.id, slider.binding, false)
        waitFor("drag ended") { c.liveValues.value.isEmpty() }
        delay(200)
        assertEquals("one modules() when the finger lifts", before + 1, e.modulesCalls.get())
    }

    /**
     * Deliverable 8: where spektrafilm appears. As desktop (src/libs/modulegroups.c, `_preset_from_string` /
     * `_init_presets`): the default workflow is "scene-referred (sigmoid)", whose "workflow: scene-referred"
     * layout has no spektrafilm in any group; the search line finds it (name and aliases); "modules: all" has
     * it in "tone"; with the workflow "scene-referred (spektrafilm)" it is in "base".
     */
    @Test fun spektrafilmIsFoundAndSitsWhereDesktopPutsIt() = runBlocking {
        val (c, _) = controller(FakeEngine.Timing.NONE)
        c.setSearch("spektra")
        assertTrue(c.visibleModules(c.state.value).any { it.op == "spektrafilm" })
        c.setSearch("film simulation")
        assertTrue("found by its aliases too", c.visibleModules(c.state.value).any { it.op == "spektrafilm" })
        c.setSearch("")
        val b = TestSetup.builtins
        fun groupOf(workflow: String, preset: String): String? {
            val text = b.presets(workflow).first { it.name == preset }.params
            return app.dtandroid.schema.layout.LayoutCodec.parse(text).groups.firstOrNull { "spektrafilm" in it.modules }?.name
        }
        assertEquals("scene-referred (sigmoid)", b.defaultWorkflow)
        assertEquals(null, groupOf(b.defaultWorkflow, "workflow: scene-referred"))
        assertEquals("tone", groupOf(b.defaultWorkflow, "modules: all"))
        assertEquals("base", groupOf("scene-referred (spektrafilm)", "workflow: scene-referred"))
        // the app's default layout is desktop's: no group lists it, as on desktop
        assertTrue(c.state.value.layout.groups.none { "spektrafilm" in it.modules })
    }

    /** Deliverable 10: crop's aspect list chosen in the app survives writing and reopening the XMP. */
    @Test fun cropAspectIsChosenAndSurvivesTheXmp() = runBlocking {
        val (c, _) = controller(FakeEngine.Timing.NONE)
        waitFor("preview") { c.state.value.preview != null }
        val key = InstanceKey("crop", 0)
        val spec = TestSetup.schema.modules.getValue("crop").aspect!!
        val sq = spec.entries.indexOfFirst { it.label.startsWith("square") }
        c.chooseAspect(key, sq)
        fun crop() = c.state.value.modules.first { it.key == key }
        fun p(n: String) = (crop().params[n] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
        assertTrue("crop switched on", crop().enabled)
        assertEquals(1.0, p("ratio_d"), 0.0); assertEquals(1.0, p("ratio_n"), 0.0)
        // the frame is square in pixels of the crop's input (the stand-in does not crop its picture; the emulator test does)
        val info = c.state.value.info!!
        assertEquals(1.0, (p("cw") - p("cx")) * info.width / ((p("ch") - p("cy")) * info.height), 1e-6)
        val hist = c.state.value.history.items.size
        c.flipAspect(key)
        assertEquals(-1.0, p("ratio_d"), 0.0)
        assertEquals("one history step each", hist + 1, c.state.value.history.items.size)
        c.chooseAspect(key, spec.entries.indexOfFirst { it.label.startsWith("3:2") })
        val item = c.state.value.current!!
        c.save()
        c.closePhoto()
        c.openPhoto(0)
        waitFor("reopened") { c.state.value.info != null }
        assertEquals(3.0, p("ratio_d"), 0.0); assertEquals(2.0, p("ratio_n"), 0.0)
        assertEquals("3:2, 4x6, 35mm  1.50", app.dtandroid.schema.CropAspect.label(spec, p("ratio_d").toInt(), p("ratio_n").toInt()))
        assertTrue(File(item.xmpPath).isFile)
    }
}

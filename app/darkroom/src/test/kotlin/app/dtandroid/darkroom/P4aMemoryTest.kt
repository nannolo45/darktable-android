// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.robust.MemoryTrim
import app.dtandroid.engine.api.EnginePaths
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Brief P4a deliverables 3 and 5: Android's memory levels give back the engine's caches and far thumbnails and keep the
 * open edit; a render that runs out of memory gives memory back and tries once more; if it fails again the darkroom
 * says so, keeps the edit and goes on working.
 */
class P4aMemoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val exposureKey = InstanceKey("exposure", 0)
    private val exposureSlider by lazy {
        TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
    }

    private class MemStore : ThumbnailStore {
        val m = ConcurrentHashMap<String, RenderedImage>()
        override fun load(item: SeriesItem) = m[item.rawPath]
        override fun store(item: SeriesItem, image: RenderedImage) { m[item.rawPath] = image }
    }

    private fun controller(store: ThumbnailStore? = null): Pair<DarkroomController, FakeEngine> {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            previewMaxSize = 900, thumbnailStore = store)
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 5, false, true) }
        return c to engine
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) = withTimeout(5000) {
        while (!cond()) delay(10)
    }.also { assertTrue(what, cond()) }

    private fun exposure(c: DarkroomController) = (c.state.value.modules.first { it.key == exposureKey }.params["exposure"] as JsonPrimitive).doubleOrNull!!

    private suspend fun setExposure(c: DarkroomController, v: Double) {
        c.drag(exposureKey, exposureSlider.id, exposureSlider.binding, v, false)
        c.endDrag(exposureKey, exposureSlider.id, exposureSlider.binding, false)
        waitFor("exposure $v applied") { c.liveValues.value.isEmpty() && kotlin.math.abs(exposure(c) - v) < 1e-9 }
    }

    @Test fun thePlanForEachAndroidLevel() {
        assertEquals(MemoryTrim.Plan(0, null), MemoryTrim.plan(0))
        assertEquals(MemoryTrim.Plan(1, null), MemoryTrim.plan(MemoryTrim.RUNNING_MODERATE))
        assertEquals(MemoryTrim.Plan(1, 20), MemoryTrim.plan(MemoryTrim.RUNNING_LOW))
        assertEquals(MemoryTrim.Plan(2, 5), MemoryTrim.plan(MemoryTrim.RUNNING_CRITICAL))
        assertEquals(MemoryTrim.Plan(1, 10), MemoryTrim.plan(MemoryTrim.UI_HIDDEN))
        for (l in listOf(MemoryTrim.BACKGROUND, MemoryTrim.MODERATE, MemoryTrim.COMPLETE, MemoryTrim.LOW_MEMORY))
            assertEquals(MemoryTrim.Plan(2, 1), MemoryTrim.plan(l))
        val img = RenderedImage(1, 1, ByteArray(4))
        val all = (0 until 12).associateWith { img }
        assertEquals(setOf(5, 4, 6), MemoryTrim.keepNear(all, 5, 3).keys)
        assertEquals(setOf(0, 1), MemoryTrim.keepNear(all, 0, 2).keys)
        assertEquals(all, MemoryTrim.keepNear(all, 5, 20))
    }

    @Test fun trimLevelsDropCachesAndThumbnailsAndKeepTheEdit() = runBlocking<Unit> {
        val store = MemStore()
        val (c, e) = controller(store)
        waitFor("preview") { c.state.value.preview != null }
        setExposure(c, 1.3)
        val history = c.state.value.history
        val undo = c.state.value.undo
        // every thumbnail is on disk (the store), so a trim can drop them from memory
        for ((i, item) in c.state.value.series.items.withIndex()) store.store(item, c.state.value.thumbnails[i] ?: RenderedImage(2, 2, ByteArray(16)))
        c.restoreThumbnails()
        assertEquals(InterfacePreview.COUNT, c.state.value.thumbnails.size)

        c.trimMemory(MemoryTrim.plan(MemoryTrim.UI_HIDDEN))
        assertEquals(listOf(1), e.memoryTrims.toList())
        assertEquals(10, c.state.value.thumbnails.size)
        assertTrue("the open photo's thumbnail stays", 5 in c.state.value.thumbnails)

        c.trimMemory(MemoryTrim.plan(MemoryTrim.RUNNING_CRITICAL))
        c.trimMemory(MemoryTrim.plan(MemoryTrim.COMPLETE))
        assertEquals(listOf(1, 2, 2), e.memoryTrims.toList())
        assertEquals(setOf(5), c.state.value.thumbnails.keys)

        // the edit is as it was: history, undo, the parameter
        assertEquals(history, c.state.value.history)
        assertEquals(undo, c.state.value.undo)
        assertEquals(1.3, exposure(c), 1e-9)
        // and the darkroom goes on: another change renders, undo works
        setExposure(c, 0.4)
        waitFor("preview after the trims") { c.state.value.preview != null }
        c.undo()
        waitFor("undo") { kotlin.math.abs(exposure(c) - 1.3) < 1e-9 }

        // back in front: the thumbnails come back from storage
        c.restoreThumbnails()
        assertEquals(InterfacePreview.COUNT, c.state.value.thumbnails.size)
    }

    @Test fun aRenderOutOfMemoryGivesMemoryBackAndTriesOnce() = runBlocking<Unit> {
        val (c, e) = controller()
        waitFor("preview") { c.state.value.preview != null }
        e.outOfMemoryRenders = 1
        val before = c.state.value.preview
        setExposure(c, 0.9)
        waitFor("a new preview") { c.state.value.preview !== before && c.state.value.working == null }
        assertTrue("memory was given back", 2 in e.memoryTrims)
        assertNull(c.state.value.message)
    }

    @Test fun aRenderThatStaysOutOfMemoryFailsWithAMessageAndTheEditStays() = runBlocking<Unit> {
        val (c, e) = controller()
        waitFor("preview") { c.state.value.preview != null }
        val logged = ArrayList<String>()
        c.onOutOfMemory = { logged += it }
        e.trimFreesEnough = false
        e.outOfMemoryRenders = 1_000
        c.drag(exposureKey, exposureSlider.id, exposureSlider.binding, 1.7, false)
        c.endDrag(exposureKey, exposureSlider.id, exposureSlider.binding, false)
        waitFor("the message") { c.state.value.message?.contains(DarkroomController.OUT_OF_MEMORY_TEXT) == true }
        waitFor("the drag ended") { c.liveValues.value.isEmpty() }
        // the engine holds the change (the history has it), whatever the screen could show
        val inEngine = e.modules().first { it.op == "exposure" && it.priority == 0 }.params["exposure"] as JsonPrimitive
        assertEquals("the edit is kept", 1.7, inEngine.doubleOrNull!!, 1e-6)
        assertEquals("and the panel shows it", 1.7, exposure(c), 1e-6)
        assertTrue(logged.isNotEmpty() && logged.all { "out of memory" in it })
        // memory is back: the darkroom renders again
        e.outOfMemoryRenders = 0
        val before = c.state.value.preview
        setExposure(c, 0.2)
        waitFor("rendering again") { c.state.value.preview !== before }
    }
}

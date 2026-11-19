// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Brief P2b: the picture while dragging, the full picture after it, and the kept "before" picture. */
class DragAndBeforeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val exposureKey = InstanceKey("exposure", 0)
    private val slider by lazy {
        TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) {
        withTimeout(5000) { while (!cond()) delay(5) }
        assertTrue(what, cond())
    }

    private fun controller(drag: DragPicture, store: ThumbnailStore? = null): DarkroomController {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900, dragPicture = drag, thumbnailStore = store)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = store != null) }
        return c
    }

    @Test
    fun dragShowsTheSmallPictureThenTheFullOneAndReadsModulesOnlyAtTheEnd() = runBlocking<Unit> {
        val c = controller(DragPicture(minSize = 240, maxSize = 240))
        waitFor("full preview") { (c.state.value.preview?.image?.width ?: 0) > 240 && c.state.value.region != null }
        val n0 = c.state.value.history.items.size
        c.drag(exposureKey, slider.id, slider.binding, 1.0, false)
        waitFor("drag picture") { c.state.value.preview?.image?.let { maxOf(it.width, it.height) } == 240 }
        assertNull("the sharp region is hidden while the finger moves", c.state.value.region)
        assertEquals("the module list is not re-read during the drag", n0, c.state.value.history.items.size)
        assertTrue("exposure shows as on", c.state.value.modules.first { it.key == exposureKey }.enabled)
        c.drag(exposureKey, slider.id, slider.binding, 1.5, false)
        c.endDrag(exposureKey, slider.id, slider.binding, false)
        waitFor("drag ended") { c.liveValues.value.isEmpty() }
        assertEquals(n0 + 1, c.state.value.history.items.size)
        waitFor("full preview and region again") { (c.state.value.preview?.image?.width ?: 0) > 240 && c.state.value.region != null }
        assertTrue("drag renders were measured", c.dragPicture.dragRenders >= 1)
        // the full preview has the same framing as the drag picture: both cover the whole photo
        val p = c.state.value.preview!!
        assertEquals(0, p.x); assertEquals(0, p.y)
    }

    @Test
    fun beforePictureIsKeptForTheSession() = runBlocking<Unit> {
        val c = controller(DragPicture())
        waitFor("preview") { c.state.value.preview != null }
        c.setBefore(true)
        val before = c.state.value.beforePreview
        assertNotNull(before)
        c.setBefore(false)
        c.setSlider(exposureKey, slider.binding, 2.0, false)
        c.setBefore(true)
        assertSame("history position 0 does not change while the photo is open", before, c.state.value.beforePreview)
        c.openPhoto(1)
        assertNull("another photo has its own", c.state.value.beforePreview?.takeIf { it === before })
    }

    @Test
    fun thumbnailsComeFromTheStoreAndAreSmall() = runBlocking<Unit> {
        val stored = HashMap<String, app.dtandroid.engine.api.RenderedImage>()
        val store = object : ThumbnailStore {
            override fun load(item: SeriesItem) = stored[item.fileName]
            override fun store(item: SeriesItem, image: app.dtandroid.engine.api.RenderedImage) { stored[item.fileName] = image }
        }
        val c = controller(DragPicture(), store)
        waitFor("preview") { c.state.value.preview != null }
        assertEquals(InterfacePreview.COUNT, stored.size)
        waitFor("thumbnail of the open photo replaced by its preview's") { stored[InterfacePreview.fileName(0)]!!.width <= 128 && c.state.value.thumbnails[0]!!.width <= 128 }
        assertTrue(c.state.value.thumbnails.values.all { maxOf(it.width, it.height) <= 128 })
    }
}

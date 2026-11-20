// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.ImageInfo
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brief P2h deliverables 2 to 4 on the stand-in engine: a file darktable found no picture in, whether
 * `image_info` answers 0 x 0 or an engine error, gives the darkroom's sentence and a closed session, is never
 * rendered, and is not opened again by the thumbnail pass or the preview session until the file changes.
 */
class P2hUnreadableTest {
    @get:Rule val tmp = TemporaryFolder()

    /** How `image_info` answers for a file without a picture. */
    enum class Answer { ZERO, ERROR, CANCEL_ONCE }

    /**
     * The stand-in engine, answering `image_info` for the files in [bad] as the real engine may for a file
     * it cannot decode, and counting opens per file, the session's state and renders of such sessions.
     */
    private class NoPictureEngine(val e: FakeEngine) : DarktableEngine by e {
        val bad = ConcurrentHashMap<String, Answer>()
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        val rendersWithoutPicture = AtomicInteger()
        @Volatile var current: String? = null

        fun opensOf(path: String) = opens[path]?.get() ?: 0

        /** whether the open session is one of the files in [bad] (ConcurrentHashMap takes no null key) */
        private fun onBadFile() = current?.let { bad.containsKey(it) } == true

        override suspend fun open(rawPath: String, xmpPath: String?) {
            opens.getOrPut(rawPath) { AtomicInteger() }.incrementAndGet()
            if (current != null) throw EngineException(EngineError.STATE, "an image is already open")
            e.open(rawPath, xmpPath)
            current = rawPath
        }

        override suspend fun close() { e.close(); current = null }

        override suspend fun imageInfo(): ImageInfo {
            val i = e.imageInfo()
            val p = current ?: return i
            return when (bad[p]) {
                Answer.ZERO -> i.copy(width = 0, height = 0, rawWidth = 0, rawHeight = 0)
                // the bridge's words when the full image could not be loaded (engine/src/render.c, _pipe_ensure)
                Answer.ERROR -> throw EngineException(EngineError.ENGINE, "image could not be loaded (mipmap 6)")
                Answer.CANCEL_ONCE -> { bad.remove(p); throw EngineException(EngineError.CANCELLED, "cancelled") }
                null -> i
            }
        }

        override suspend fun renderPreview(maxSize: Int): RenderedImage {
            if (onBadFile()) rendersWithoutPicture.incrementAndGet()
            return e.renderPreview(maxSize)
        }

        override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double): RenderedImage {
            if (onBadFile()) rendersWithoutPicture.incrementAndGet()
            return e.renderRegion(x, y, w, h, scale)
        }
    }

    private class MemStore : ThumbnailStore {
        val thumbs = ConcurrentHashMap<String, RenderedImage>()
        override fun load(item: SeriesItem) = thumbs[item.fileName]
        override fun store(item: SeriesItem, image: RenderedImage) { thumbs[item.fileName] = image }
        override fun loadPicture(item: SeriesItem): RenderedImage? = null
        override fun storePicture(item: SeriesItem, image: RenderedImage) {}
    }

    private lateinit var engine: NoPictureEngine
    private lateinit var store: MemStore
    private lateinit var items: List<SeriesItem>

    /** Three photos on disk: two the stand-in reads, and in the middle one answered with [answer]. */
    private fun controller(answer: Answer): DarkroomController {
        val dir = tmp.root
        engine = NoPictureEngine(InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE))
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        store = MemStore()
        val names = InterfacePreview.samples.keys.sorted()
        val photos = File(dir, "photos").apply { mkdirs() }
        items = listOf(names[0], "BROKEN_0001.RAF", names[1]).map { n ->
            val f = File(photos, n).apply { writeBytes(ByteArray(2000) { it.toByte() }) }
            SeriesItem(n, f.path, f.path + ".xmp")
        }
        engine.bad[items[1].rawPath] = answer
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900, thumbnailStore = store)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(Series("p2h", items), 0, filmstrip = false) }
        return c
    }

    private val bad get() = items[1]

    private suspend fun assertNoPictureFromSession(c: DarkroomController) {
        try {
            c.withPhotoSession(bad.rawPath, bad.xmpPath) { e -> e.renderPreview(64) }
            fail("a session on a file without a picture ran its work")
        } catch (e: EngineException) {
            assertTrue(e.message, DarkroomController.isNoPicture(e))
        }
    }

    /** Deliverables 2 and 4: the darkroom meets the file first. */
    private fun darkroomOpensTheFile(answer: Answer) = runBlocking {
        val c = controller(answer)
        assertNotNull(c.state.value.info)
        c.openPhoto(1)
        val st = c.state.value
        assertNull("no photo open", st.info)
        assertEquals("cannot open ${bad.fileName}: ${DarkroomController.NO_PICTURE}", st.message)
        assertNull("the session is closed at once", engine.current)
        assertEquals(1, engine.opensOf(bad.rawPath))
        assertTrue(c.knownUnreadable(bad.rawPath))

        // background work that needs its own session works at once (before P2h: a state error until the next photo)
        val good = items[2]
        assertEquals(true, c.withPhotoSession(good.rawPath, good.xmpPath) { e -> e.renderPreview(64); true })
        assertNull(engine.current)

        // the thumbnail pass, twice, and the preview session: none opens the file again
        assertEquals("thumbnails of the two readable photos", 2, c.renderThumbnails(items))
        assertEquals(0, c.renderThumbnails(items))
        assertNoPictureFromSession(c)
        assertEquals("opened once, by the darkroom", 1, engine.opensOf(bad.rawPath))
        assertNull(store.load(bad))

        // the darkroom again: the same sentence, without opening it
        c.openPhoto(0)
        assertNotNull(c.state.value.info)
        c.openPhoto(1)
        assertEquals("cannot open ${bad.fileName}: ${DarkroomController.NO_PICTURE}", c.state.value.message)
        assertEquals(1, engine.opensOf(bad.rawPath))
        assertNull(engine.current)
        assertEquals("never a render of a session without a picture", 0, engine.rendersWithoutPicture.get())
    }

    @Test fun pictureOf0x0InTheDarkroom() = darkroomOpensTheFile(Answer.ZERO)

    @Test fun errorFromImageInfoInTheDarkroom() = darkroomOpensTheFile(Answer.ERROR)

    /** Deliverables 2 and 4: the thumbnail pass meets the file first (no photo open). */
    private fun thumbnailPassOpensTheFile(answer: Answer) = runBlocking {
        val c = controller(answer)
        c.closePhoto()
        assertEquals(2, c.renderThumbnails(items))
        assertEquals("opened once to find there is no picture", 1, engine.opensOf(bad.rawPath))
        assertNull("and closed", engine.current)
        store.thumbs.clear()
        assertEquals(2, c.renderThumbnails(items))
        assertEquals("the second pass does not open it", 1, engine.opensOf(bad.rawPath))
        assertNoPictureFromSession(c)
        c.openPhoto(1)
        assertNull(c.state.value.info)
        assertEquals("cannot open ${bad.fileName}: ${DarkroomController.NO_PICTURE}", c.state.value.message)
        assertEquals(1, engine.opensOf(bad.rawPath))
        assertEquals(0, engine.rendersWithoutPicture.get())
    }

    @Test fun pictureOf0x0InTheThumbnailPass() = thumbnailPassOpensTheFile(Answer.ZERO)

    @Test fun errorFromImageInfoInTheThumbnailPass() = thumbnailPassOpensTheFile(Answer.ERROR)

    /** Deliverables 2 and 4: the LightPhotoLibrary preview session meets the file first. */
    @Test fun previewSessionMeetsTheFileFirst() = runBlocking {
        for (answer in listOf(Answer.ZERO, Answer.ERROR)) {
            val c = controller(answer)
            c.closePhoto()
            assertNoPictureFromSession(c)
            assertEquals(1, engine.opensOf(bad.rawPath))
            assertNull("closed before the work could render it", engine.current)
            assertNoPictureFromSession(c)
            assertEquals(2, c.renderThumbnails(items))
            assertEquals("$answer: not opened again", 1, engine.opensOf(bad.rawPath))
            assertEquals(0, engine.rendersWithoutPicture.get())
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /** Deliverable 3: a file that changed (size, or only its modification time) is tried again. */
    @Test fun aRepairedFileIsTriedAgain() = runBlocking {
        val c = controller(Answer.ERROR)
        c.closePhoto()
        assertEquals(2, c.renderThumbnails(items))
        assertTrue(c.knownUnreadable(bad.rawPath))
        // the download is repaired: new bytes, so a new size
        engine.bad.remove(bad.rawPath)
        File(bad.rawPath).appendBytes(ByteArray(100))
        assertFalse(c.knownUnreadable(bad.rawPath))
        assertEquals("the repaired file gets its thumbnail", 1, c.renderThumbnails(items))
        assertEquals(2, engine.opensOf(bad.rawPath))
        c.openPhoto(1)
        assertNotNull("and opens in the darkroom", c.state.value.info)
        assertNull(c.state.value.message)
        c.closePhoto()

        // broken again, then only its modification time changes (same size): tried again too
        engine.bad[bad.rawPath] = Answer.ZERO
        store.thumbs.clear()
        c.renderThumbnails(items)
        assertTrue(c.knownUnreadable(bad.rawPath))
        val opens = engine.opensOf(bad.rawPath)
        engine.bad.remove(bad.rawPath)
        val f = File(bad.rawPath)
        assertTrue(f.setLastModified(f.lastModified() + 10_000))
        assertEquals(1, c.renderThumbnails(items))
        assertEquals(opens + 1, engine.opensOf(bad.rawPath))
        assertEquals(0, engine.rendersWithoutPicture.get())
    }

    /** CANCELLED from image_info is not "no picture": the work runs again later and the file is not remembered. */
    @Test fun aCancelledImageInfoIsNotNoPicture() = runBlocking {
        val c = controller(Answer.CANCEL_ONCE)
        c.closePhoto()
        assertEquals(true, c.withPhotoSession(bad.rawPath, bad.xmpPath) { e -> e.renderPreview(64); true })
        assertEquals("opened again after the cancel", 2, engine.opensOf(bad.rawPath))
        assertFalse(c.knownUnreadable(bad.rawPath))
        assertNull(engine.current)
    }
}

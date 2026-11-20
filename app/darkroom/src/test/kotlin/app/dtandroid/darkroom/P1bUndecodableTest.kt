// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EnginePaths
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
 * Brief P1b (E1) on the stand-in engine: `open` itself refuses a file without a decodable picture
 * (engine-api.md 5.1, [EngineException.isUndecodableFile]; the fake answers so for an empty file). The darkroom
 * handles it as P2h/P2i handle "no picture" from image_info: the NO_PICTURE sentence, nothing left open,
 * remembered for background passes, and a photo the user opens again is tried again. Another IO answer of
 * `open` keeps its own message and is not remembered.
 */
class P1bUndecodableTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The fake, counting opens per file; [otherIo] files answer an IO error that is not the undecodable case. */
    private class Counting(val e: FakeEngine) : DarktableEngine by e {
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        val otherIo = ConcurrentHashMap.newKeySet<String>()
        @Volatile var current: String? = null
        fun opensOf(path: String) = opens[path]?.get() ?: 0

        override suspend fun open(rawPath: String, xmpPath: String?) {
            opens.getOrPut(rawPath) { AtomicInteger() }.incrementAndGet()
            if (rawPath in otherIo) throw EngineException(EngineError.IO, "cannot read $rawPath")
            e.open(rawPath, xmpPath)
            current = rawPath
        }

        override suspend fun close() { e.close(); current = null }
    }

    private class MemStore : ThumbnailStore {
        val thumbs = ConcurrentHashMap<String, RenderedImage>()
        override fun load(item: SeriesItem) = thumbs[item.fileName]
        override fun store(item: SeriesItem, image: RenderedImage) { thumbs[item.fileName] = image }
        override fun loadPicture(item: SeriesItem): RenderedImage? = null
        override fun storePicture(item: SeriesItem, image: RenderedImage) {}
    }

    private lateinit var engine: Counting
    private lateinit var items: List<SeriesItem>

    /** Three photos: two the stand-in reads, and in the middle an empty file (undecodable). */
    private fun controller(): DarkroomController {
        val dir = tmp.root
        engine = Counting(InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE))
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val names = InterfacePreview.samples.keys.sorted()
        val photos = File(dir, "photos").apply { mkdirs() }
        items = listOf(names[0], "EMPTY_0001.CR2", names[1]).map { n ->
            val f = File(photos, n).apply { writeBytes(if (n.startsWith("EMPTY")) ByteArray(0) else ByteArray(2000) { it.toByte() }) }
            SeriesItem(n, f.path, f.path + ".xmp")
        }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900, thumbnailStore = MemStore())
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(Series("p1b", items), 0, filmstrip = false) }
        return c
    }

    private val bad get() = items[1]

    @Test fun anUndecodableFileFromOpenIsNoPicture() = runBlocking {
        val c = controller()
        assertNotNull(c.state.value.info)
        c.openPhoto(1)
        val st = c.state.value
        assertNull("no photo open", st.info)
        assertEquals("cannot open ${bad.fileName}: ${DarkroomController.NO_PICTURE}", st.message)
        assertNull("nothing left open", engine.current)
        assertEquals(1, engine.opensOf(bad.rawPath))
        assertTrue("remembered for background passes", c.knownUnreadable(bad.rawPath))

        // background passes do not open it again
        assertEquals("thumbnails of the two readable photos", 2, c.renderThumbnails(items))
        try {
            c.withPhotoSession(bad.rawPath, bad.xmpPath) { e -> e.renderPreview(64) }
            fail("a session on an undecodable file ran its work")
        } catch (e: EngineException) {
            assertTrue(e.message, DarkroomController.isNoPicture(e))
        }
        assertEquals(1, engine.opensOf(bad.rawPath))

        // the user opens it again: tried again (P2i), same sentence, still remembered
        c.openPhoto(0)
        assertNotNull(c.state.value.info)
        c.openPhoto(1)
        assertEquals("cannot open ${bad.fileName}: ${DarkroomController.NO_PICTURE}", c.state.value.message)
        assertEquals(2, engine.opensOf(bad.rawPath))
        assertNull(engine.current)
        assertTrue(c.knownUnreadable(bad.rawPath))

        // the good photo opens afterwards
        c.openPhoto(2)
        assertNotNull(c.state.value.info)
    }

    @Test fun theBackgroundPassMeetsTheFileFirst() = runBlocking {
        val c = controller()
        c.closePhoto()
        assertEquals(2, c.renderThumbnails(items))
        assertEquals(1, engine.opensOf(bad.rawPath))
        assertTrue(c.knownUnreadable(bad.rawPath))
        assertEquals(0, c.renderThumbnails(items))
        assertEquals("not opened again", 1, engine.opensOf(bad.rawPath))
        assertNull(engine.current)
    }

    @Test fun anotherIoErrorFromOpenKeepsItsMessageAndIsNotRemembered() = runBlocking {
        val c = controller()
        engine.otherIo += items[2].rawPath
        c.openPhoto(2)
        val st = c.state.value
        assertNull(st.info)
        assertEquals("cannot open ${items[2].fileName}: IO: cannot read ${items[2].rawPath}", st.message)
        assertFalse(c.knownUnreadable(items[2].rawPath))
        assertNull(engine.current)
    }
}

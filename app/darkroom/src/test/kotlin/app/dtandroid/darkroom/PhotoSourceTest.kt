// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.ExportFormat
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Brief P2e: photos that are not on the phone yet are fetched through a [PhotoSource] before they open. */
class PhotoSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A source that "downloads" by creating the file, after [gate] opens. */
    private class Source(val gate: CompletableDeferred<Unit> = CompletableDeferred(Unit)) : PhotoSource {
        val prepared = ArrayList<String>()
        val opened = ArrayList<String>()
        val left = ArrayList<String>()
        var nextSeen: String? = null
        override suspend fun prepare(item: SeriesItem, progress: (String) -> Unit): SeriesItem {
            prepared += item.fileName
            progress("downloading ${item.fileName}… 50 %")
            gate.await()
            if (item.fileName.startsWith("GONE")) throw PhotoSourceException("${item.fileName}: this photo is gone from the library")
            val f = File(File(item.rawPath).parentFile, item.fileName.removePrefix("photo ").ifEmpty { "x" })
            f.parentFile.mkdirs(); f.writeText("raw")
            return SeriesItem(f.name, f.path, f.path + ".xmp")
        }
        override fun opened(item: SeriesItem, next: SeriesItem?) { opened += item.fileName; nextSeen = next?.fileName }
        override fun left(item: SeriesItem) { left += item.fileName }
    }

    private fun setup(source: Source, items: List<SeriesItem>): DarkroomController {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        return DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 300).also { it.source = source }
    }

    private fun local(name: String): SeriesItem {
        val f = File(tmp.root, "local/$name").apply { parentFile.mkdirs(); writeText("raw") }
        return SeriesItem(name, f.path, f.path + ".xmp")
    }

    /** A photo not on the phone: a name and a path that does not exist (as the app makes them). */
    private fun remote(name: String) = File(tmp.root, "remote/${name.hashCode()}/.not-downloaded").let { SeriesItem(name, it.path, it.path + ".xmp") }

    private suspend fun until(what: String, cond: () -> Boolean) =
        try { withTimeout(20_000) { while (!cond()) delay(10) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    @Test fun missingPhotosAreFetchedBeforeTheyOpenAndHaveNoThumbnail() = runBlocking {
        val src = Source()
        val names = InterfacePreview.samples.keys.sorted()
        val c = setup(src, emptyList())
        val items = listOf(local(names[0]), remote(names[1]), remote(names[2]))
        c.start(Series("s", items), 0, filmstrip = true)
        assertEquals("only the local photo has a thumbnail", setOf(0), c.state.value.thumbnails.keys)
        assertEquals(emptyList<String>(), src.prepared)
        assertEquals(listOf(names[0]), src.opened)
        assertEquals(names[1], src.nextSeen)
        c.openPhoto(1)
        assertEquals(listOf(names[1]), src.prepared)
        assertEquals(listOf(names[0]), src.left)
        assertTrue(c.state.value.info != null)
        assertTrue(File(c.state.value.current!!.rawPath).isFile)
        assertEquals(names[1], c.state.value.series.items[1].fileName)
        c.closePhoto()
        assertEquals(listOf(names[0], names[1]), src.left)
    }

    @Test fun aFailedFetchShowsItsReasonAndOpensNothing() = runBlocking {
        val src = Source()
        val names = InterfacePreview.samples.keys.sorted()
        val c = setup(src, emptyList())
        c.start(Series("s", listOf(local(names[0]), remote("GONE_1.RAF"))), 0, filmstrip = true)
        c.openPhoto(1)
        assertNull(c.state.value.info)
        assertEquals("GONE_1.RAF: this photo is gone from the library", c.state.value.message)
    }

    @Test fun leavingWhileAPhotoIsFetchedOpensNothingLater() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val src = Source(gate)
        val names = InterfacePreview.samples.keys.sorted()
        val c = setup(src, emptyList())
        c.start(Series("s", listOf(local(names[0]), remote(names[1]))), 0, filmstrip = true)
        val j = CoroutineScope(Dispatchers.Default).launch { c.openPhoto(1) }
        until("fetch started") { src.prepared.isNotEmpty() }
        assertEquals("downloading ${names[1]}… 50 %", c.state.value.message)
        // while the photo is fetched no session is open: a preview of another photo can be rendered
        val other = local(names[2])
        val done = c.withPhotoSession(other.rawPath, other.xmpPath) { e ->
            e.export(File(tmp.root, "p.png").path, ExportOptions(format = ExportFormat.PNG, maxWidth = 64, maxHeight = 64)); true
        }
        assertEquals(null, done) // refused: the darkroom is about to open its photo
        c.closePhoto()
        gate.complete(Unit)
        j.join()
        assertNull("nothing opened after the darkroom was left", c.state.value.info)
        // with no photo open, a session of its own is used for the preview, and closed again
        val ok = c.withPhotoSession(other.rawPath, other.xmpPath) { e ->
            e.export(File(tmp.root, "p.png").path, ExportOptions(format = ExportFormat.PNG, maxWidth = 64, maxHeight = 64)); true
        }
        assertEquals(true, ok)
        assertNull(c.state.value.info)
        assertTrue(File(tmp.root, "p.png").length() > 0)
    }

    @Test fun thePhotoOpenInTheDarkroomIsUsedForItsOwnPreviewAndAnotherIsRefused() = runBlocking {
        val src = Source()
        val names = InterfacePreview.samples.keys.sorted()
        val c = setup(src, emptyList())
        val a = local(names[0]); val b = local(names[1])
        c.start(Series("s", listOf(a, b)), 0, filmstrip = false)
        assertEquals(a.rawPath, c.openRawPath())
        assertEquals(true, c.withPhotoSession(a.rawPath, a.xmpPath) { e ->
            e.export(File(tmp.root, "a.png").path, ExportOptions(format = ExportFormat.PNG, maxWidth = 64, maxHeight = 64)); true
        })
        assertEquals(null, c.withPhotoSession(b.rawPath, b.xmpPath) { true })
        assertEquals("the darkroom's photo is still open", a.rawPath, c.openRawPath())
        c.saveIfOpen(a.rawPath)
        assertTrue(File(a.xmpPath).isFile)
    }
}

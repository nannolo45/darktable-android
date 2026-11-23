// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.ExportDelivery
import app.dtandroid.darkroom.ExportJob
import app.dtandroid.darkroom.ExportPhase
import app.dtandroid.darkroom.ExportScope
import app.dtandroid.darkroom.ExportSettings
import app.dtandroid.darkroom.FolderExportDelivery
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Collections

/**
 * Brief P3g on the emulator, with the real engine and the public test RAW, through the export screen's runner
 * (the same code the screen's button starts). Results go to files/p2b-test/p3g-results.txt.
 *
 * s1: one photo to "this device" (Pictures/darktable through MediaStore): the progress observed (steps in order,
 * percent never back, the engine's "module n of N"), done at 100 %, the picture found in MediaStore.
 * s2: one photo cancelled mid-way (at module 3 or later): cancelled, nothing delivered, no file left.
 * s3: a two-photo series: "photo n of 2", 2 finished exactly, two files, back on the first photo.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3gScenarioTest {
    private val res = "p3g-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null
    private var appDelivery: ExportDelivery? = null

    @get:org.junit.Rule val guard = HangGuard.rule(15)

    @After fun tearDown() {
        val c = AppHost.controller
        c.exports.cancel()
        runCatching { waitFor("export stopped", 120_000) { c.exports.job.value?.running != true } }
        c.exports.dismiss()
        appDelivery?.let { c.exports.delivery = it }
        c.exports.set { ExportSettings() }
        runBlocking { runCatching { c.closePhoto() } }
        runCatching { c.setOverlay(null) }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 300_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(5)
        }
    }

    private fun open(name: String, photos: Int): DarkroomController = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3g-$name").apply { deleteRecursively(); mkdirs() }
        val items = (1..photos).map { k ->
            val raw = File(dir, "p3g_${name}_$k." + TestFiles.raw.extension).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
            val xmp = File(dir, raw.name + ".xmp").also { TestFiles.xmp.copyTo(it) }
            SeriesItem(raw.name, raw.path, xmp.path)
        }
        if (scenario == null) {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        }
        val c = AppHost.controller
        appDelivery = c.exports.delivery
        c.start(Series("p3g $name", items), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c.exports.dismiss()
        c
    }

    /** every job state seen, polled every 2 ms on a thread of its own, until [stop] */
    private class Watch(val c: DarkroomController) {
        val seen: MutableList<ExportJob> = Collections.synchronizedList(ArrayList())
        @Volatile var stop = false
        val t = Thread {
            var last: ExportJob? = null
            while (!stop) {
                val j = c.exports.job.value
                if (j != null && j != last) { seen += j; last = j }
                Thread.sleep(2)
            }
        }.apply { start() }
        fun end(): List<ExportJob> { stop = true; t.join(); return synchronized(seen) { seen.toList() } }
    }

    private fun mediaStoreHas(name: String): Boolean {
        val cr = TestFiles.ctx.contentResolver
        cr.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { return it.count > 0 }
        return false
    }

    @Test
    fun s1_onePhotoWithProgress() {
        val c = open("one", 1)
        val w = Watch(c)
        val t0 = System.nanoTime()
        assertTrue(c.exports.start())
        waitFor("export done") { c.exports.job.value?.running == false }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val seen = w.end()
        val end = c.exports.job.value!!
        val steps = seen.filter { it.running }.map { it.step }.distinct()
        TestFiles.record(res, "s1_steps", steps.joinToString(" | "))
        TestFiles.record(res, "s1_percents", seen.map { it.percent }.distinct().joinToString(" "))
        TestFiles.record(res, "s1_end", "${end.phase} ${end.percent} % finished ${end.finished} in $ms ms (emulator, x86_64); ${end.message}")
        assertEquals(end.message, ExportPhase.DONE, end.phase)
        assertEquals(100, end.percent)
        for (k in 1 until seen.size) assertTrue("percent went back", seen[k].overall >= seen[k - 1].overall)
        assertTrue("no module step: $steps", steps.any { it.startsWith("processing · module ") })
        assertTrue("no writing step: $steps", steps.contains(ExportJob.STEP_WRITING))
        val name = c.state.value.current!!.fileName.substringBeforeLast('.') + ".jpg"
        val found = mediaStoreHas(name)
        TestFiles.record(res, "s1_mediastore", "$name ${if (found) "found" else "MISSING"} in MediaStore")
        assertTrue("$name not in MediaStore", found)
        TestFiles.record(res, "s1", "passed")
    }

    @Test
    fun s2_cancelMidway() {
        val c = open("cancel", 1)
        val out = File(TestFiles.ctx.cacheDir, "p3g-delivered").apply { deleteRecursively() }
        val scratch = File(TestFiles.ctx.cacheDir, "p3g-scratch").apply { deleteRecursively() }
        c.exports.delivery = FolderExportDelivery(scratch, out)
        assertTrue(c.exports.start())
        waitFor("module 3") {
            val s = c.exports.job.value?.step ?: ""
            Regex("module (\\d+) of").find(s)?.groupValues?.get(1)?.toInt()?.let { it >= 3 } == true
        }
        val at = c.exports.job.value!!.step
        val t0 = System.nanoTime()
        c.exports.cancel()
        waitFor("export stopped", 120_000) { c.exports.job.value?.running == false }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val end = c.exports.job.value!!
        val leftScratch = scratch.listFiles()?.size ?: 0
        val delivered = out.listFiles()?.size ?: 0
        TestFiles.record(res, "s2_cancel", "cancelled at '$at': ${end.phase} after $ms ms (emulator); delivered $delivered, engine files left $leftScratch")
        assertEquals(ExportPhase.CANCELLED, end.phase)
        assertEquals(0, end.finished)
        assertEquals(0, delivered)
        assertEquals(0, leftScratch)
        TestFiles.record(res, "s2", "passed")
    }

    @Test
    fun s3_twoPhotoSeries() {
        val c = open("series", 2)
        val out = File(TestFiles.ctx.cacheDir, "p3g-series").apply { deleteRecursively() }
        val scratch = File(TestFiles.ctx.cacheDir, "p3g-scratch").apply { deleteRecursively() }
        c.exports.delivery = FolderExportDelivery(scratch, out)
        c.exports.set { it.copy(scope = ExportScope.SERIES) }
        val w = Watch(c)
        assertTrue(c.exports.start())
        waitFor("series done", 600_000) { c.exports.job.value?.running == false }
        val seen = w.end()
        val end = c.exports.job.value!!
        val labels = seen.filter { it.running }.map { it.label }.distinct()
        val files = out.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        TestFiles.record(res, "s3_labels", labels.joinToString(" | "))
        TestFiles.record(res, "s3_end", "${end.phase} finished ${end.finished} of ${end.total}, failed ${end.failed}; files $files; ${end.message}")
        assertEquals(end.message, ExportPhase.DONE, end.phase)
        assertEquals(2, end.finished)
        assertEquals(listOf("photo 1 of 2", "photo 2 of 2"), labels)
        assertEquals(2, files.size)
        for (k in 1 until seen.size) assertTrue("finished went back", seen[k].finished >= seen[k - 1].finished)
        waitFor("back on the first photo", 120_000) { c.state.value.index == 0 && c.state.value.info != null }
        TestFiles.record(res, "s3", "passed")
    }
}

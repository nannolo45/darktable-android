// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.ExportFormat
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.ExportProgress
import app.dtandroid.engine.api.ExportStep
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * Brief P3g on the stand-in engine: the export's progress state (steps in order, percent never back, ends at
 * 100 %), cancel mid-way (nothing delivered, no file left), and a series' counter of finished photos (exact,
 * also when the series is cancelled), the export screen's defaults (darktable's) and their engine options.
 */
class P3gExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Setup(val c: DarkroomController, val engine: FakeEngine, val out: File, val scratch: File, val delivery: FolderExportDelivery)

    private fun setup(msPerModule: Long, photos: Int = 3, index: Int = 0): Setup {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        engine.exportMsPerModule = msPerModule
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val full = InterfacePreview.series(File(dir, "series"))
        runBlocking { c.start(full.copy(items = full.items.take(photos)), index, filmstrip = false, thumbnails = false) }
        val out = File(dir, "delivered")
        val scratch = File(dir, "scratch")
        val d = FolderExportDelivery(scratch, out)
        c.exports.delivery = d
        // the stand-in engine writes PNG only; a smaller picture keeps the tests quick
        c.exports.set { it.withFormat(ExportFormat.PNG).copy(size = ExportSize.E2048) }
        return Setup(c, engine, out, scratch, d)
    }

    private suspend fun waitFor(what: String, ms: Long = 20_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!cond()) delay(2) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    /** every job state seen, in order (StateFlow may skip some; none is reordered) */
    private fun record(s: Setup, scope: CoroutineScope): MutableList<ExportJob> {
        val seen = Collections.synchronizedList(ArrayList<ExportJob>())
        scope.launch(Dispatchers.Unconfined) { s.c.exports.job.collect { j -> if (j != null) seen += j } }
        return seen
    }

    private fun files(d: File) = d.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    @Test fun onePhotoStepsInOrderAndEndsAt100() = runBlocking<Unit> {
        val s = setup(msPerModule = 15)
        val rec = CoroutineScope(Dispatchers.Default)
        val seen = record(s, rec)
        assertTrue(s.c.exports.start())
        waitFor("the export ends") { s.c.exports.job.value?.running == false }
        rec.cancel()
        val end = s.c.exports.job.value!!
        assertEquals(ExportPhase.DONE, end.phase)
        assertEquals(1, end.finished)
        assertEquals(1f, end.overall)
        assertEquals(100, end.percent)
        val snap = synchronized(seen) { seen.toList() }
        for (k in 1 until snap.size) assertTrue("percent went back: ${snap[k - 1].percent} -> ${snap[k].percent}", snap[k].overall >= snap[k - 1].overall)
        val steps = snap.filter { it.running }.map { it.step }
        val order = listOf(ExportJob.STEP_PREPARING, "processing", ExportJob.STEP_WRITING, ExportTarget.DEVICE.delivering)
        var at = 0
        for (st in steps) {
            val k = order.indexOfFirst { st.startsWith(it) }
            assertTrue("unexpected step '$st'", k >= 0)
            assertTrue("step '$st' after '${order[at]}'", k >= at)
            at = k
        }
        assertTrue("no module step seen: $steps", steps.any { it.startsWith("processing · module ") })
        assertEquals(listOf(InterfacePreview.series(File(tmp.root, "series")).items[0].fileName.substringBeforeLast('.') + ".png"), files(s.out))
        assertEquals("the engine's file is removed after delivery", emptyList<String>(), files(s.scratch))
        assertEquals(1, s.delivery.finishedWith.size)
    }

    @Test fun cancelMidwayDeliversNothingAndLeavesNoFile() = runBlocking<Unit> {
        val s = setup(msPerModule = 60)
        assertTrue(s.c.exports.start())
        waitFor("module 2") { s.c.exports.job.value?.step?.startsWith("processing · module 2 ") == true }
        s.c.exports.cancel()
        waitFor("the export stops", 5_000) { s.c.exports.job.value?.running == false }
        val end = s.c.exports.job.value!!
        assertEquals(ExportPhase.CANCELLED, end.phase)
        assertEquals(0, end.finished)
        assertEquals("cancelled · nothing written", end.label)
        assertEquals(emptyList<String>(), files(s.out))
        assertEquals(emptyList<String>(), files(s.scratch))
        assertTrue(s.delivery.finishedWith.isEmpty())
        // the next export is not cancelled by the earlier cancel
        s.engine.exportMsPerModule = 0
        assertTrue(s.c.exports.start())
        waitFor("the second export ends") { s.c.exports.job.value?.running == false }
        assertEquals(ExportPhase.DONE, s.c.exports.job.value!!.phase)
        assertEquals(1, files(s.out).size)
    }

    @Test fun seriesCountsFinishedPhotosExactlyAndReturnsToThePhoto() = runBlocking<Unit> {
        val s = setup(msPerModule = 5, photos = 3, index = 1)
        s.c.exports.set { it.copy(scope = ExportScope.SERIES) }
        val rec = CoroutineScope(Dispatchers.Default)
        val seen = record(s, rec)
        assertTrue(s.c.exports.start())
        assertEquals(3, s.c.exports.job.value!!.total)
        waitFor("the series ends", 60_000) { s.c.exports.job.value?.running == false }
        rec.cancel()
        val end = s.c.exports.job.value!!
        assertEquals(ExportPhase.DONE, end.phase)
        assertEquals(3, end.finished)
        assertEquals(0, end.failed)
        assertEquals(3, files(s.out).size)
        val snap = synchronized(seen) { seen.toList() }
        for (k in 1 until snap.size) {
            assertTrue("finished went back", snap[k].finished >= snap[k - 1].finished)
            assertTrue("overall went back", snap[k].overall >= snap[k - 1].overall)
        }
        for (j in snap.filter { it.running }) {
            assertTrue("finished ${j.finished} while photo ${j.number} runs", j.finished <= j.number - 1 || j.step == ExportTarget.DEVICE.delivering && j.finished <= j.number)
            assertEquals("photo ${j.number} of 3", j.label)
        }
        waitFor("back on the photo the export started from") { s.c.state.value.index == 1 && s.c.state.value.info != null }
    }

    @Test fun seriesCancelledOnTheSecondPhotoCountsOne() = runBlocking<Unit> {
        val s = setup(msPerModule = 40, photos = 3)
        s.c.exports.set { it.copy(scope = ExportScope.SERIES) }
        assertTrue(s.c.exports.start())
        waitFor("photo 2 processing") { s.c.exports.job.value?.let { it.number == 2 && it.step.startsWith("processing · module 2 ") } == true }
        s.c.exports.cancel()
        waitFor("the series stops", 10_000) { s.c.exports.job.value?.running == false }
        val end = s.c.exports.job.value!!
        assertEquals(ExportPhase.CANCELLED, end.phase)
        assertEquals(1, end.finished)
        assertEquals("cancelled · 1 of 3 exported", end.label)
        assertEquals(1, files(s.out).size)
        assertEquals(emptyList<String>(), files(s.scratch))
    }

    @Test fun noSecondExportWhileOneRuns() = runBlocking<Unit> {
        val s = setup(msPerModule = 30)
        assertTrue(s.c.exports.start())
        assertFalse(s.c.exports.start())
        s.c.exports.cancel()
        waitFor("stopped") { s.c.exports.job.value?.running == false }
    }

    @Test fun defaultsAreDarktablesAndMapToTheEngineOptions() {
        val d = ExportSettings()
        assertEquals(ExportFormat.JPEG, d.format)
        assertEquals(95, d.quality) // manual: "The default quality (95)"
        assertEquals(8, d.bitDepth)
        assertEquals(ExportSize.ORIGINAL, d.size) // max size 0 x 0: no limit
        assertFalse(d.highQuality) // darktableconfig: high_quality_processing false
        assertEquals(ExportProfile.IMAGE, d.profile) // icctype -1: image settings
        val o = d.copy(size = ExportSize.E2048).options()
        assertEquals(2048, o.maxWidth)
        assertEquals(2048, o.maxHeight)
        assertFalse(o.upscale)
        assertEquals(8, ExportSettings().copy(format = ExportFormat.TIFF, bitDepth = 32).withFormat(ExportFormat.PNG).bitDepth)
        assertEquals(16, ExportSettings().copy(format = ExportFormat.TIFF, bitDepth = 16).withFormat(ExportFormat.PNG).bitDepth)
        assertEquals("tif", d.withFormat(ExportFormat.TIFF).extension)
    }

    @Test fun stepsInPlainWords() {
        assertEquals("preparing", ExportJob.words(ExportProgress(ExportStep.PREPARING, 0, 0, 0.0)))
        assertEquals("processing · module 7 of 23", ExportJob.words(ExportProgress(ExportStep.PROCESSING, 7, 23, 0.3)))
        assertEquals("writing the file", ExportJob.words(ExportProgress(ExportStep.WRITING, 23, 23, 0.9)))
        val j = ExportJob(ExportPhase.RUNNING, ExportTarget.DEVICE, total = 12, finished = 4, number = 5, fileName = "a.raf",
            step = "processing · module 3 of 20", photoFraction = 0.5)
        assertEquals("photo 5 of 12", j.label)
        assertEquals(38, j.percent) // (4 + 0.5) / 12
        assertEquals("4 finished · 38 %", j.stepLine)
        assertEquals("processing · module 3 of 20 · 50 %", j.photoLine)
        assertEquals("export 5/12 · 38 %", j.short)
    }
}

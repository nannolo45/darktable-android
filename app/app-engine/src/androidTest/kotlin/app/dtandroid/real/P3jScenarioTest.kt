// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.engine.api.PasteMode
import app.dtandroid.schema.Control
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Brief P3j on the emulator, with the real engine and the public test RAW, through the darkroom's controller (the
 * code the menus call). Results go to files/p2b-test/p3j-results.txt.
 *
 * Photo A (exposure +1.5 EV on the test XMP) is copied; a selective paste of exposure alone onto photo B (a copy of
 * the RAW without an XMP) makes B as bright as A's exposure says, B's XMP reopens with the same history, and a paste
 * onto two photos of the series (C and D, not open) writes both XMP files with A's exposure.
 */
@RunWith(AndroidJUnit4::class)
class P3jScenarioTest {
    private val res = "p3j-results.txt"
    private val exposure = InstanceKey("exposure", 0)
    private var scenario: ActivityScenario<MainActivity>? = null
    @get:org.junit.Rule val guard = HangGuard.rule(15)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 240_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(10) }
    }

    private fun ev(c: DarkroomController) =
        (c.state.value.modules.first { it.op == "exposure" && it.priority == 0 }.params["exposure"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()

    @Test fun copyFromAPasteOntoBAndOntoTwoPhotos() = runBlocking<Unit> {
        val engine = TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3j").apply { deleteRecursively(); mkdirs() }
        fun copyRaw(name: String) = File(dir, name + "." + TestFiles.raw.extension).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val a = copyRaw("a")
        val ax = File(dir, "a.xmp").also { TestFiles.xmp.copyTo(it) }
        val items = listOf(SeriesItem(a.name, a.path, ax.path)) + listOf("b", "c", "d").map { n ->
            val r = copyRaw(n)
            SeriesItem(r.name, r.path, File(dir, "$n.xmp").path)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c: DarkroomController = AppHost.controller
        c.start(Series("p3j", items), 0, filmstrip = false, thumbnails = false)
        waitFor("preview of A") { c.state.value.preview != null }
        val e = AppHost.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        val evA = ev(c) + 1.5
        c.setSlider(exposure, e.binding, evA, false)
        waitFor("A's change") { kotlin.math.abs(ev(c) - evA) < 1e-4 }
        c.copyPaste.copy()
        assertNotNull("copied", c.copyPaste.state.value.clipboard)

        // B: selective paste of exposure alone
        c.openPhoto(1)
        waitFor("preview of B") { c.state.value.info != null && c.state.value.index == 1 && c.state.value.preview != null }
        val evB0 = ev(c)
        c.copyPaste.openPasteSheet()
        c.copyPaste.selectAll(false)
        val expLine = c.copyPaste.state.value.sheet!!.lines.last { it.item.op == "exposure" && it.item.priority == 0 }
        c.copyPaste.toggle(expLine.item.num)
        val n0 = c.state.value.history.items.size
        c.copyPaste.paste(PasteMode.APPEND, fromSheet = true)
        waitFor("B's paste") { kotlin.math.abs(ev(c) - evA) < 1e-4 }
        val n1 = c.state.value.history.items.size
        c.renderNow()
        val pic = c.state.value.preview
        TestFiles.record(res, "b_paste", "exposure %.3f -> %.3f (A %.3f), history %d -> %d items, preview %s"
            .format(java.util.Locale.ROOT, evB0, ev(c), evA, n0, n1, pic?.let { "${it.image.width}x${it.image.height}" }))
        assertEquals("one module pasted", n0 + 1, n1)
        assertNotNull("B renders", pic)
        val histB = engine.history()
        c.openPhoto(2) // B is saved to b.xmp
        waitFor("C open") { c.state.value.info != null && c.state.value.index == 2 }
        c.closePhoto()
        engine.open(items[1].rawPath, items[1].xmpPath)
        val histB2 = engine.history()
        engine.close()
        TestFiles.record(res, "b_reopen", "same history: ${histB == histB2} (${histB2.items.size} items)")
        assertEquals("B's XMP reopens with the same history", histB, histB2)

        // C and D from the series, not open: the clipboard's selection (exposure) in append
        c.start(Series("p3j", items), 0, filmstrip = false, thumbnails = false)
        waitFor("A again") { c.state.value.info != null && c.state.value.index == 0 }
        assertTrue(c.copyPaste.pasteOnto(listOf(items[2], items[3]), PasteMode.APPEND))
        waitFor("paste onto two photos") { c.copyPaste.state.value.job?.running == false }
        val job = c.copyPaste.state.value.job!!
        TestFiles.record(res, "two_photos", "${job.done} pasted, ${job.failed} failed: ${job.message}")
        assertEquals(2, job.done)
        waitFor("A open again") { c.state.value.info != null && c.state.value.index == 0 }
        c.closePhoto()
        for (it in listOf(items[2], items[3])) {
            engine.open(it.rawPath, it.xmpPath)
            val v = (engine.modules().first { m -> m.op == "exposure" && m.priority == 0 }.params["exposure"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
            engine.close()
            TestFiles.record(res, "exposure_${it.fileName}", "%.4f (A %.4f)".format(java.util.Locale.ROOT, v, evA))
            assertEquals(evA, v, 1e-4)
        }
        TestFiles.record(res, "result", "passed")
    }
}

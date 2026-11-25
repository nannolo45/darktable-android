// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.ASHIFT_OP
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Perspective
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.RenderedImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.abs

/**
 * Brief P3n on the emulator, with the real engine (darktable's ashift.c) and the public test RAW, through the
 * controller's calls (the same calls the panel and the touch handler make):
 *  n1 the panel opened (the whole picture), "rectangle", its top-left corner dragged by one finger (one history item
 *     when it lifts), "fit both": the parameters change and the picture changes; the panel closed; XMP kept as
 *     p3n-rect.xmp / .jpg for darktable-cli;
 *  n2 "find lines" with edge enhancement, a line deselected by a long press, "fit vertical" rotation only; p3n-lines.
 * The workflow renders each XMP with darktable-cli and compares it with the app's export.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3nScenarioTest {
    private val res = "p3n-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null
    private val key = InstanceKey(ASHIFT_OP, 0)

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!runCatching(cond).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; message ${AppHost.controller.state.value.message}; note ${AppHost.controller.perspective.state.value.note}")
            Thread.sleep(10)
        }
    }

    private fun open(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3n-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p3n $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c to item
    }

    private suspend fun preview(c: DarkroomController): RenderedImage = c.withEngine { e -> e.renderPreview(800) }

    private fun diff(a: RenderedImage, b: RenderedImage): Double {
        if (a.width != b.width || a.height != b.height) return 255.0
        var sum = 0L
        for (i in a.rgba.indices) if (i % 4 != 3) sum += abs((a.rgba[i].toInt() and 255) - (b.rgba[i].toInt() and 255))
        return sum.toDouble() / (a.width * a.height * 3)
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun st(c: DarkroomController) = c.perspective.state.value.st!!

    private fun openPanel(c: DarkroomController) {
        c.goToModule(key)
        runBlocking { c.perspective.open(key) }
        waitFor("focused") { c.perspective.state.value.st?.focused == true }
    }

    private fun closePanel(c: DarkroomController) {
        c.toggleExpanded(key)
        waitFor("focus left") { c.perspective.state.value.key == null }
        Thread.sleep(500)
        runBlocking { c.withEngine { } }
    }

    private fun export(c: DarkroomController, item: SeriesItem, file: String): File = runBlocking {
        c.save()
        val xmp = File(TestFiles.out, "$file.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        c.closePhoto()
        val jpg = File(TestFiles.out, "$file.jpg").apply { delete() }
        c.withEngine { e ->
            runCatching { e.close() }
            e.open(item.rawPath, xmp.path)
            try { e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
        }
        TestFiles.record(res, "${file}_export", "${jpg.length()} bytes")
        jpg
    }

    @Test fun n1_rectangleCornerDragAndFitBoth() {
        val (c, item) = open("rect")
        val plain = runBlocking { preview(c) }
        openPanel(c)
        val focused = runBlocking { preview(c) }
        TestFiles.record(res, "n1_focus", "picture ${plain.width}x${plain.height}, focused ${focused.width}x${focused.height}")
        runBlocking { c.perspective.structure("rectangle") }
        waitFor("rectangle") { st(c).method == "rectangle" && st(c).handles.size == 4 }
        val h0 = st(c).handles[0]
        val n0 = items(c)
        assertTrue(c.perspective.touchDown(h0.x, h0.y, 1000, 0.03))
        c.perspective.touchMove(h0.x + 0.03, h0.y, 1030, 0.005)
        c.perspective.touchMove(h0.x + 0.06, h0.y + 0.01, 1060, 0.005)
        c.perspective.touchUp(h0.x + 0.06, h0.y + 0.01, 1090)
        waitFor("corner written") { items(c) == n0 + 1 }
        val h1 = st(c).handles[0]
        TestFiles.record(res, "n1_corner", "%.4f %.4f -> %.4f %.4f, items $n0 -> ${items(c)}".format(h0.x, h0.y, h1.x, h1.y))
        assertEquals(h0.x + 0.06, h1.x, 0.003)
        val n1 = items(c)
        runBlocking { c.perspective.fit("both") }
        waitFor("fitted") { items(c) == n1 + 1 }
        val s = st(c)
        TestFiles.record(res, "n1_fit", "rotation %.4f lensshift_v %.4f lensshift_h %.4f shear %.4f crop %s message %s".format(
            s.rotation, s.lensshiftV, s.lensshiftH, s.shear, s.crop, s.message))
        assertTrue("the fit changes the parameters", s.rotation != 0.0 || s.lensshiftV != 0.0 || s.lensshiftH != 0.0)
        closePanel(c)
        val fitted = runBlocking { preview(c) }
        val d = diff(plain, fitted)
        TestFiles.record(res, "n1_render", "preview diff %.4f (%dx%d -> %dx%d)".format(d, plain.width, plain.height, fitted.width, fitted.height))
        assertTrue("the fit changes the picture", d > 0.0)
        export(c, item, "p3n-rect")
    }

    @Test fun n2_linesFoundLongPressAndRotationFit() {
        val (c, item) = open("lines")
        openPanel(c)
        runBlocking { c.perspective.structure("lines_found", "edges") }
        waitFor("lines") { st(c).method == "lines_found" }
        val s0 = st(c)
        TestFiles.record(res, "n2_lines", "${s0.lines.size} lines, vertical ${s0.vertical}, horizontal ${s0.horizontal}")
        assertTrue("structure found", s0.lines.isNotEmpty())
        val l = s0.lines.firstOrNull { it.kind == "vertical_used" }
        if (l != null) {
            val mx = (l.x1 + l.x2) / 2
            val my = (l.y1 + l.y2) / 2
            assertTrue(c.perspective.touchDown(mx, my, 1000, 0.003))
            c.perspective.touchUp(mx, my, 1000 + Perspective.LONG_PRESS_MS + 100)
            waitFor("deselected") { st(c).vertical == s0.vertical - 1 || st(c).vertical < s0.vertical }
            TestFiles.record(res, "n2_long_press", "vertical ${s0.vertical} -> ${st(c).vertical}")
        }
        val n0 = items(c)
        runBlocking { c.perspective.fit("vertical", app.dtandroid.darkroom.FitVariant.ROTATION) }
        waitFor("fitted") { items(c) == n0 + 1 }
        val s = st(c)
        TestFiles.record(res, "n2_fit", "rotation %.4f lensshift_v %.4f message %s".format(s.rotation, s.lensshiftV, s.message))
        assertEquals("rotation only", 0.0, s.lensshiftV, 0.0)
        closePanel(c)
        export(c, item, "p3n-lines")
    }
}

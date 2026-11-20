// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Brief P3a on the emulator, with the real engine and the public test RAW. Results go to
 * files/p2b-test/p3a-results.txt; the XMP and the app's export as p3a-crop.xmp / p3a-crop.jpg, which the
 * workflow renders with darktable-cli and compares (size and PSNR).
 *
 * c1: crop on (square, from the aspect list, as a photo cropped earlier), crop's panel opened: the tool shows the
 * uncropped picture and the history is unchanged; a corner and then the whole frame dragged with touch events
 * injected into the system (UiAutomation); a sync's writeIfOpen meanwhile writes the edit from before the tool;
 * the panel closed: one history item, the picture the size the tool showed; XMP written and exported.
 * c2: the tool opened and closed without a change leaves the history and the XMP's edit as they were.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3aScenarioTest {
    private val res = "p3a-results.txt"
    private val crop = InstanceKey("crop", 0)
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(10)
        }
    }

    /** The darkroom on the screen with the test RAW (its own folder and sidecar). */
    private fun open(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3a-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p3a $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c to item
    }

    /** Crop on with a square frame, as a photo cropped earlier, and its XMP written. */
    private fun squareCrop(c: DarkroomController) = runBlocking {
        val spec = AppHost.schema.modules.getValue("crop").aspect!!
        c.chooseAspect(crop, spec.entries.indexOfFirst { it.label.startsWith("square") })
        c.save()
    }

    private fun params(c: DarkroomController) = c.state.value.modules.first { it.op == crop.op && it.priority == crop.priority }.params.let { p ->
        listOf("cx", "cy", "cw", "ch").map { (p[it] as JsonPrimitive).content.toDouble() }
    }

    /**
     * The XMP's edit: without its time stamps and without the history's basic and auto hashes (P3a: the bridge's
     * undo lost those two, and the crop tool's bracket used an undo). Brief P1b part 2: the tool's picture is a
     * render that leaves crop out and the bridge's undo keeps the hashes, so c1 and c2 now also require the XMP
     * to be byte-equal; [edit] stays for the message that says what differs.
     */
    private fun edit(xmp: String) = xmp
        .replace(Regex("""\s+[A-Za-z]+:[A-Za-z_]*(?:timestamp|Date)="[^"]*""""), "")
        .replace(Regex("""\s+darktable:history_(?:basic|auto)_hash="[^"]*""""), "")

    /** The attributes that differ between two XMPs, by name (for the results file). */
    private fun differences(a: String, b: String): String {
        val rx = Regex("""([A-Za-z]+:[A-Za-z_]+)="([^"]*)"""")
        val ma = rx.findAll(a).associate { it.groupValues[1] to it.groupValues[2] }
        val mb = rx.findAll(b).associate { it.groupValues[1] to it.groupValues[2] }
        return (ma.keys + mb.keys).filter { ma[it] != mb[it] }.sorted().joinToString(", ") { "$it: ${ma[it] ?: "(none)"} -> ${mb[it] ?: "(none)"}" }
    }

    private fun openTool(c: DarkroomController) {
        c.toggleExpanded(crop)
        waitFor("crop tool") { c.state.value.cropTool?.picture != null }
        // the screen lays out the uncropped picture (the view goes back to fit)
        waitFor("photo on the screen") { c.photoArea != null && c.currentViewport != null }
        Thread.sleep(800)
    }

    /** The screen position (pixels) of a point of the uncropped picture given as fractions. */
    private fun onScreen(c: DarkroomController, px: Float, py: Float): Pair<Float, Float> {
        val a = c.photoArea!!
        val t = c.state.value.cropTool!!
        val win = IntArray(2)
        scenario!!.onActivity { it.window.decorView.getLocationOnScreen(win) }
        val fit = min(a.width / t.inW, a.height / t.inH)
        val left = a.left + a.width / 2f - 0.5f * t.inW * fit
        val top = a.top + a.height / 2f - 0.5f * t.inH * fit
        return (win[0] + left + px * t.inW * fit) to (win[1] + top + py * t.inH * fit)
    }

    /** One finger from [from] to [to] (screen pixels), injected into the system as a touch screen would. */
    private fun swipe(from: Pair<Float, Float>, to: Pair<Float, Float>, steps: Int = 12) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("event injected", ui.injectInputEvent(e, true))
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from.first, from.second)
        for (i in 1..steps) {
            Thread.sleep(16)
            val f = i.toFloat() / steps
            send(MotionEvent.ACTION_MOVE, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f)
        }
        send(MotionEvent.ACTION_UP, to.first, to.second)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(200)
    }

    @Test
    fun c1_dragCornerAndFrameLeaveWriteExport() = runBlocking<Unit> {
        val (c, item) = open("c1")
        squareCrop(c)
        val xmp0 = File(item.xmpPath).readText()
        val h0 = c.state.value.history
        val p0 = params(c)
        val cropped0 = c.state.value.info!!
        openTool(c)
        val t0 = c.state.value.cropTool!!
        TestFiles.record(res, "c1_tool", "uncropped ${t0.inW}x${t0.inH}, cropped ${cropped0.width}x${cropped0.height}, frame ${t0.clip}")
        assertTrue("the tool shows the uncropped picture", t0.inW.toLong() * t0.inH > cropped0.width.toLong() * cropped0.height + 1000)
        assertEquals("opening the tool left the history as it was", h0, c.state.value.history)
        assertEquals(p0, params(c))
        // a corner, then the whole frame, by touch
        val g0 = t0.clip
        swipe(onScreen(c, g0.right - 0.01f, g0.bottom - 0.01f), onScreen(c, g0.right - 0.21f, g0.bottom - 0.21f))
        val g1 = c.state.value.cropTool!!.clip
        TestFiles.record(res, "c1_corner", "$g0 -> $g1")
        assertTrue("corner dragged: $g0 -> $g1", g1.w < g0.w - 0.1f)
        assertEquals("square kept", 1.0, g1.w.toDouble() * t0.inW / (g1.h.toDouble() * t0.inH), 2e-3)
        swipe(onScreen(c, g1.x + g1.w / 2, g1.y + g1.h / 2), onScreen(c, g1.x + g1.w / 2 + 0.08f, g1.y + g1.h / 2 + 0.04f))
        val g2 = c.state.value.cropTool!!.clip
        TestFiles.record(res, "c1_move", "$g1 -> $g2")
        assertTrue("frame moved: $g1 -> $g2", g2.x > g1.x + 0.04f && abs(g2.w - g1.w) < 1e-4f)
        assertEquals("nothing written while the tool is open", h0, c.state.value.history)
        assertEquals(p0, params(c))
        // LightPhotoLibrary's writeIfOpen during a sync job: the edit from before the tool
        c.saveIfOpen(item.rawPath)
        val xmpDuring = File(item.xmpPath).readText()
        TestFiles.record(res, "c1_sync_during_tool_differences", differences(xmp0, xmpDuring).ifEmpty { "none" })
        assertEquals(edit(xmp0), edit(xmpDuring))
        // brief P1b part 2: nothing of the tool reaches the engine, so the XMP is byte-equal
        assertEquals("XMP during the tool byte-equal (P1b part 2)", xmp0, xmpDuring)
        val shown = c.state.value.cropTool!!.outputSize()
        // crop's panel closed: the frame applied, one history item, the picture as large as the tool said
        c.toggleExpanded(crop)
        waitFor("tool left") { c.state.value.cropTool == null && c.state.value.history.items.size == h0.items.size + 1 }
        waitFor("picture") { c.state.value.preview != null }
        val info = c.state.value.info!!
        TestFiles.record(res, "c1_left", "history ${h0.items.size} -> ${c.state.value.history.items.size}, shown ${shown.first}x${shown.second}, picture ${info.width}x${info.height}, params ${params(c)}")
        assertTrue("size ${info.width}x${info.height} vs ${shown.first}x${shown.second}", abs(info.width - shown.first) <= 1 && abs(info.height - shown.second) <= 1)
        c.save()
        val xmp = File(TestFiles.out, "p3a-crop.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        c.closePhoto()
        // the app's export of that XMP at full size: the workflow compares it with darktable-cli's
        val jpg = File(TestFiles.out, "p3a-crop.jpg").apply { delete() }
        c.withEngine { e ->
            runCatching { e.close() }
            e.open(item.rawPath, xmp.path)
            try { e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpg.path, o)
        TestFiles.record(res, "c1_export", "${o.outWidth}x${o.outHeight}")
        assertEquals(info.width, o.outWidth)
        assertEquals(info.height, o.outHeight)
        TestFiles.record(res, "c1", "passed")
    }

    /**
     * c3 (deliverable 4): "straighten" and a line drawn by touch along a line falling to the right: rotate and
     * perspective's rotation changes by ashift.c's `_calculate_straightening` angle for the line the app received,
     * one history item; the tool opens again on the turned picture (bracket clean: history unchanged by it).
     */
    @Test
    fun c3_straightenByTouch() = runBlocking<Unit> {
        val (c, _) = open("c3")
        squareCrop(c)
        openTool(c)
        val r0 = c.rotationValue(c.state.value)
        val n0 = c.state.value.history.items.size
        c.cropStraighten(true)
        Thread.sleep(300)
        swipe(onScreen(c, 0.15f, 0.5f), onScreen(c, 0.85f, 0.55f), steps = 16)
        waitFor("rotation written") { c.state.value.history.items.size == n0 + 1 }
        waitFor("tool again") { c.state.value.cropTool?.picture != null }
        val r1 = c.rotationValue(c.state.value)
        val t = c.state.value.cropTool!!
        // the angle desktop's function gives for that line on the uncropped picture (before the turn)
        val want = app.dtandroid.schema.CropTool.straighten(0.85f, 0.55f, 0.15f, 0.5f, 1000f, 1000f * t.inH / t.inW, 1f)
        TestFiles.record(res, "c3", "rotation $r0 -> $r1 (line angle ${"%.3f".format(java.util.Locale.ROOT, want)}), history $n0 -> ${c.state.value.history.items.size}, last ${c.state.value.history.items.last().op}")
        assertEquals("ashift", c.state.value.history.items.last().op)
        assertEquals(r0 + want, r1, 0.15)
        assertTrue("turned to level a line falling to the right", r1 < r0)
        TestFiles.record(res, "c3_passed", "true")
    }

    @Test
    fun c2_noChangeLeavesTheEditAsItWas() = runBlocking<Unit> {
        val (c, item) = open("c2")
        squareCrop(c)
        val xmp0 = File(item.xmpPath).readText()
        val h0 = c.state.value.history
        openTool(c)
        c.toggleExpanded(crop)
        waitFor("tool left") { c.state.value.cropTool == null }
        Thread.sleep(500)
        assertEquals(h0, c.state.value.history)
        c.save()
        val xmp1 = File(item.xmpPath).readText()
        val same = xmp0 == xmp1
        TestFiles.record(res, "c2_xmp_identical", "$same${if (same) "" else " (differing attributes: ${differences(xmp0, xmp1)})"}")
        assertEquals(edit(xmp0), edit(xmp1))
        // brief P1b part 2 (P3a's limit 1): opening and closing the tool without a change leaves the XMP byte-equal
        assertTrue("XMP byte-equal after the tool opened and closed (P1b part 2): ${differences(xmp0, xmp1)}", same)
        TestFiles.record(res, "c2", "passed")
    }
}

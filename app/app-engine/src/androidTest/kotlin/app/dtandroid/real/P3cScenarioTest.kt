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
import app.dtandroid.darkroom.key
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.PickRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Brief P3c on the emulator, with the real engine and the public test RAW (without a sidecar: darktable's default
 * edit). Results go to files/p2b-test/p3c-results.txt; each scenario's XMP and the app's export of it as
 * p3c-<name>.xmp / .jpg, which the workflow renders with darktable-cli and compares (size and PSNR).
 *
 * p1: white balance's "from image area": armed, its first area made smaller from its top left corner and then moved
 * with touch events injected into the system (UiAutomation); one history item per pick, the module in "from image
 * area" mode, the coefficients those of the picked area; XMP written and exported.
 * p2: exposure's spot mapping (the exposure slider's picker), "correction" to a 40 % target, by touch as p1;
 * the exposure changed, then "measure" on the same area reads back the target.
 * p3: the values picked for fixed areas (white balance's input, the global picker's output) written to
 * p3c-picks.txt; the workflow compares them with the Linux engine test's picks of the same areas (dtb_pickref).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3cScenarioTest {
    private val res = "p3c-results.txt"
    private val wb = InstanceKey("temperature", 0)
    private val exposure = InstanceKey("exposure", 0)
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!runCatching(cond).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; picker ${AppHost.controller.state.value.picker}, message ${AppHost.controller.state.value.message}")
            Thread.sleep(10)
        }
    }

    private fun open(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3c-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p3c $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c to item
    }

    /** The screen position (pixels) of a point of the picture given as fractions, at fit. */
    private fun onScreen(c: DarkroomController, px: Float, py: Float): Pair<Float, Float> {
        val a = c.photoArea!!
        val info = c.state.value.info!!
        val win = IntArray(2)
        scenario!!.onActivity { it.window.decorView.getLocationOnScreen(win) }
        val fit = min(a.width / info.width, a.height / info.height)
        val left = a.left + a.width / 2f - 0.5f * info.width * fit
        val top = a.top + a.height / 2f - 0.5f * info.height * fit
        return (win[0] + left + px * info.width * fit) to (win[1] + top + py * info.height * fit)
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

    /** Arm the picker of [key]'s control [id] with its module's panel open, and wait for its first pick. */
    private fun arm(c: DarkroomController, key: InstanceKey, id: Int) = runBlocking {
        if (c.state.value.expanded != key) c.toggleExpanded(key)
        waitFor("photo on the screen") { c.photoArea != null && c.currentViewport != null }
        c.togglePicker(key, id)
        waitFor("armed and picked") { c.state.value.picker?.readout?.isNotEmpty() == true }
        Thread.sleep(500)
    }

    /** The armed area made smaller from its top left corner to ([x0], [y0]), then moved by ([dx], [dy]), by touch. */
    private fun areaByTouch(c: DarkroomController, x0: Float, y0: Float, dx: Float, dy: Float): List<Double> {
        val a0 = c.state.value.picker!!.selection!!.area!!
        val picks0 = c.state.value.history.items.size
        val n0 = c.state.value.picker!!.picks
        swipe(onScreen(c, a0[0].toFloat() + 0.004f, a0[1].toFloat() + 0.004f), onScreen(c, x0, y0))
        waitFor("corner dragged") { c.state.value.picker!!.selection!!.area!![0] > a0[0] + 0.1 && !c.state.value.picker!!.dragging }
        // the pick of the corner drag has landed (it runs after the finger lifts; the next drag must not start before)
        waitFor("corner picked") { c.state.value.picker!!.picks > n0 }
        val n1 = c.state.value.picker!!.picks
        val a1 = c.state.value.picker!!.selection!!.area!!
        TestFiles.record(res, "corner", "$a0 -> $a1")
        val mx = ((a1[0] + a1[2]) / 2).toFloat()
        val my = ((a1[1] + a1[3]) / 2).toFloat()
        swipe(onScreen(c, mx, my), onScreen(c, mx + dx, my + dy))
        waitFor("area moved") { abs(c.state.value.picker!!.selection!!.area!![0] - (a1[0] + dx)) < 0.02 }
        val a2 = c.state.value.picker!!.selection!!.area!!
        TestFiles.record(res, "moved", "$a1 -> $a2, history items $picks0 -> ${c.state.value.history.items.size}")
        assertEquals("moved, same width", a1[2] - a1[0], a2[2] - a2[0], 1e-3)
        // the pick after the move has been made (its readout is there and no finger is down)
        // the pick after the move has landed, with its history item: a readout alone may be the one before
        waitFor("picked") { !c.state.value.picker!!.dragging && c.state.value.picker!!.picks > n1 && c.state.value.picker!!.readout.size == 3 }
        return a2
    }

    private fun exportAndKeep(c: DarkroomController, item: SeriesItem, name: String): Pair<Int, Int> = runBlocking {
        c.save()
        val xmp = File(TestFiles.out, "p3c-$name.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        val info = c.state.value.info!!
        c.closePhoto()
        val jpg = File(TestFiles.out, "p3c-$name.jpg").apply { delete() }
        c.withEngine { e ->
            runCatching { e.close() }
            e.open(item.rawPath, xmp.path)
            try { e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpg.path, o)
        TestFiles.record(res, "${name}_export", "${o.outWidth}x${o.outHeight} (picture ${info.width}x${info.height})")
        o.outWidth to o.outHeight
    }

    private fun num(c: DarkroomController, key: InstanceKey, p: String) =
        (c.state.value.modules.first { it.key == key }.params[p] as JsonPrimitive).content.toDouble()

    @Test
    fun p1_whiteBalanceFromAnAreaByTouch() = runBlocking<Unit> {
        val (c, item) = open("p1")
        val red0 = num(c, wb, "red")
        arm(c, wb, 4)
        val area = areaByTouch(c, 0.40f, 0.42f, -0.15f, -0.08f)
        val last = c.state.value.history.items.last()
        val red = num(c, wb, "red")
        val blue = num(c, wb, "blue")
        // the module's own picker on the same area: the coefficients are desktop's from its input mean
        val pick = c.withEngine { it.colorPick("temperature", 0, PickRequest(area = area)) }
        val m = pick.input!!.mean.map { it!! }
        val g = if (m[1] > 0.001) 1.0 / m[1] else 1.0
        val wantRed = ((if (m[0] > 0.001) 1.0 / m[0] else 1.0) / g).coerceIn(0.0, 8.0)
        val wantBlue = ((if (m[2] > 0.001) 1.0 / m[2] else 1.0) / g).coerceIn(0.0, 8.0)
        TestFiles.record(res, "p1", String.format(Locale.ROOT, "area %s, red %.5f -> %.5f, blue %.5f, from the picked mean %.5f %.5f; last item %s; readout %s",
            area, red0, red, blue, wantRed, wantBlue, last.op, c.state.value.picker!!.readout))
        assertEquals("temperature", last.op)
        assertEquals(wantRed, red, 1e-4 * wantRed)
        assertEquals(wantBlue, blue, 1e-4 * wantBlue)
        assertNotEquals(red0, red)
        val wbInfo = c.withEngine { it.moduleAction("temperature", 0, "get_wb") }
        TestFiles.record(res, "p1_get_wb", wbInfo)
        assertTrue("mode from image area: $wbInfo", wbInfo.toString().contains("from image area"))
        val (w, h) = exportAndKeep(c, item, "wb")
        assertTrue(w > 0 && h > 0)
        TestFiles.record(res, "p1_passed", "true")
    }

    @Test
    fun p2_exposureSpotByTouch() = runBlocking<Unit> {
        val (c, item) = open("p2")
        val ev0 = num(c, exposure, "exposure")
        c.toggleExpanded(exposure)
        // the target: 40 % (desktop's lightness slider in the area exposure mapping section)
        c.setSlider(exposure, app.dtandroid.schema.Binding.Setting("exposure/lightness"), 40.0, false, 22)
        arm(c, exposure, 5)
        val area = areaByTouch(c, 0.45f, 0.40f, -0.20f, -0.10f)
        val ev = num(c, exposure, "exposure")
        TestFiles.record(res, "p2", String.format(Locale.ROOT, "area %s, exposure %.4f -> %.4f EV, history last %s", area, ev0, ev,
            c.state.value.history.items.last().op))
        assertEquals("exposure", c.state.value.history.items.last().op)
        assertTrue("exposure changed", abs(ev - ev0) > 1e-3)
        // "measure" on the same area: the lightness after the correction is the target
        c.chooseSetting(exposure, 14, 1)
        c.pickerApply()
        waitFor("measured") { (c.settingValue(c.state.value, "exposure", "exposure/lightness")?.content?.toDouble() ?: 40.0) != 40.0 }
        val measured = c.settingValue(c.state.value, "exposure", "exposure/lightness")!!.content.toDouble()
        TestFiles.record(res, "p2_measure", String.format(Locale.ROOT, "%.4f %% (target 40)", measured))
        assertEquals(40.0, measured, 0.05)
        assertEquals("measure changes nothing", ev, num(c, exposure, "exposure"), 0.0)
        c.chooseSetting(exposure, 14, 0)
        val (w, h) = exportAndKeep(c, item, "exposure")
        assertTrue(w > 0 && h > 0)
        TestFiles.record(res, "p2_passed", "true")
    }

    /** Fixed areas and a point, picked on the default edit: compared by the workflow with the Linux engine test. */
    @Test
    fun p3_pickedValuesForTheEngineTest() = runBlocking<Unit> {
        val (c, _) = open("p3")
        val lines = ArrayList<String>()
        for ((name, req) in listOf(
            "area" to PickRequest.area(0.40, 0.35, 0.62, 0.55),
            "corner" to PickRequest.area(0.05, 0.05, 0.25, 0.30),
            "point" to PickRequest.point(0.5, 0.5),
        )) {
            for (op in listOf("temperature", "exposure", null)) {
                val r = c.withEngine { it.colorPick(op, 0, req) }
                val side = if (op == null) r.output else r.input
                assertNotNull("$name $op", side)
                fun v(l: List<Double?>) = l.take(3).joinToString(" ") { String.format(Locale.ROOT, "%.6f", it ?: Double.NaN) }
                lines += "PICK $name ${op ?: "global"} ${side!!.colorspace} mean ${v(side.mean)} min ${v(side.min)} max ${v(side.max)}"
            }
        }
        File(TestFiles.out, "p3c-picks.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        TestFiles.record(res, "p3_passed", "true")
    }
}

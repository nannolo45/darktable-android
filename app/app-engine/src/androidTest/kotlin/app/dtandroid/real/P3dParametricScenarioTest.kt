// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.BottomView
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.MaskMode
import app.dtandroid.darkroom.ParamChannels
import app.dtandroid.darkroom.RangeMath
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.InstanceRef
import app.dtandroid.engine.api.PickRequest
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.RenderedImage
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

/**
 * Brief P3d stage 2b on the emulator, real engine, the public test RAW (exposure raised by 1.5 EV). Results go to
 * files/p2b-test/p3d-param-results.txt; the XMP and the app's export of the first scenario as p3d-parametric.xmp /
 * .jpg, which the workflow renders with darktable-cli and compares.
 *
 * e1: a luminance (g) range on exposure's input, set below the picture's mean: the picture changes where the mask
 * is on (yellow in "show mask") and stays as it was where it is off; the XMP reopens with the same range.
 * e2: the range set from a picked area through the shared picker tool.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3dParametricScenarioTest {
    private val res = "p3d-param-results.txt"
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
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; parametric ${AppHost.controller.parametric.state.value}, message ${AppHost.controller.state.value.message}")
            Thread.sleep(10)
        }
    }

    private fun open(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3dp-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p3dp $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c.setEnabled(exposure, true)
        c.setParam(exposure, "exposure", JsonPrimitive(1.5))
        c.setView(BottomView.MASKS)
        c.masks.select(exposure)
        waitFor("exposure in the masks tab") { c.masks.state.value.module?.key == exposure && c.parametric.state.value.blend != null }
        c to item
    }

    private suspend fun preview(c: DarkroomController, options: RenderOptions = RenderOptions.NONE): RenderedImage =
        c.withEngine { e -> if (options.isNone) e.renderPreview(800) else e.renderPreview(800, options) }

    private fun px(a: RenderedImage, p: Int, k: Int) = a.rgba[p * 4 + k].toInt() and 255

    private fun g(c: DarkroomController, name: String) = c.parametric.state.value.blend!!.parametric[name]!!

    @Test
    fun e1_luminanceRangeOnExposure() {
        val (c, item) = open("range")
        val unmasked = runBlocking { preview(c) }
        // the picture's mean luminance at the module's input: the range selects what is darker than that
        val mean = runBlocking { c.withEngine { e -> e.colorPick("exposure", 0, PickRequest(area = listOf(0.0, 0.0, 1.0, 1.0))).input!!.mean } }
        val lum = 0.3 * mean[0]!! + 0.59 * mean[1]!! + 0.11 * mean[2]!!
        TestFiles.record(res, "range_input_mean", mean.toList())
        runBlocking {
            c.masks.setMode(MaskMode.PARAMETRIC)
            c.parametric.addChannel(ParamChannels.of(c.parametric.state.value.space).first { it.base == "g" })
            c.parametric.setRange("g_in", listOf(0.0, 0.0, lum.coerceIn(0.01, 0.9), (lum * 1.3).coerceIn(0.02, 1.0)))
        }
        val range = g(c, "g_in").range
        TestFiles.record(res, "range_set", range)
        val masked = runBlocking { preview(c) }
        c.masks.toggleShowMask()
        val shown = runBlocking { preview(c, RenderOptions(showMask = InstanceRef("exposure", 0))) }
        c.masks.toggleShowMask()
        var yellowN = 0; var greyN = 0; var yellowD = 0L; var greyD = 0L
        for (p in 0 until masked.width * masked.height) {
            val r = px(shown, p, 0); val gg = px(shown, p, 1); val b = px(shown, p, 2)
            val d = abs(px(masked, p, 0) - px(unmasked, p, 0)) + abs(px(masked, p, 1) - px(unmasked, p, 1)) + abs(px(masked, p, 2) - px(unmasked, p, 2))
            if (r > b + 60 && gg > b + 60) { yellowN++; yellowD += d }
            else if (maxOf(r, gg, b) - minOf(r, gg, b) < 16) { greyN++; greyD += d }
        }
        val total = masked.width * masked.height
        val yd = yellowD.toDouble() / maxOf(yellowN, 1) / 3
        val gd = greyD.toDouble() / maxOf(greyN, 1) / 3
        TestFiles.record(res, "range_show_mask", "yellow $yellowN of $total (mean change $yd), grey $greyN (mean change $gd)")
        assertTrue("the mask covers part of the picture: yellow $yellowN of $total", yellowN > total / 50 && greyN > total / 50)
        assertTrue("where the mask is on the picture changes: $yd", yd > 2.0)
        assertTrue("where it is off the picture stays: grey $gd vs yellow $yd", gd < yd / 3)
        // written and reopened: the same range
        runBlocking { c.save() }
        File(item.xmpPath).copyTo(File(TestFiles.out, "p3d-parametric.xmp"), overwrite = true)
        runBlocking {
            c.closePhoto()
            c.start(Series("p3dp again", listOf(item)), 0, filmstrip = false, thumbnails = false)
        }
        waitFor("preview again") { c.state.value.preview != null }
        c.setView(BottomView.MASKS)
        runBlocking { c.masks.select(exposure) }
        waitFor("blend read again") { c.parametric.state.value.blend?.parametric?.get("g_in")?.active == true }
        val again = g(c, "g_in").range
        TestFiles.record(res, "range_reopened", again)
        for (i in 0..3) assertTrue("reopened range marker $i: ${range[i]} vs ${again[i]}", abs(range[i] - again[i]) < 1e-5)
        val d2 = runBlocking { preview(c) }.let { a -> var s = 0L; for (i in a.rgba.indices) if (i % 4 != 3) s += abs(px(a, i / 4, i % 4) - px(masked, i / 4, i % 4)); s.toDouble() / (a.width * a.height * 3) }
        TestFiles.record(res, "range_diff_reopened", d2)
        assertTrue("reopened, the same picture: $d2", d2 < 0.5)
        // the export of that XMP, for the workflow's darktable-cli comparison
        val xmp = File(TestFiles.out, "p3d-parametric.xmp")
        val jpg = File(TestFiles.out, "p3d-parametric.jpg").apply { delete() }
        runBlocking {
            c.closePhoto()
            c.withEngine { e ->
                runCatching { e.close() }
                e.open(item.rawPath, xmp.path)
                try { e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpg.path, o)
        TestFiles.record(res, "range_export", "${o.outWidth}x${o.outHeight}")
        assertTrue(o.outWidth > 0)
        TestFiles.record(res, "range_passed", "true")
    }

    @Test
    fun e2_rangeFromAPickedArea() {
        val (c, _) = open("pick")
        runBlocking {
            c.masks.setMode(MaskMode.PARAMETRIC)
            c.parametric.addChannel(ParamChannels.of(c.parametric.state.value.space).first { it.base == "g" })
        }
        waitFor("channel on") { c.parametric.state.value.blend?.parametric?.get("g_in")?.active == true }
        val id = c.parametric.pickerId("g_in")
        runBlocking { c.togglePicker(exposure, id) }
        waitFor("picker armed") { c.state.value.picker?.controlId == id }
        c.pickerTap(0.4f, 0.45f, 0.04f, 0.04f)
        waitFor("range set from the area") { !RangeMath.isFull(g(c, "g_in").range) }
        val r = g(c, "g_in").range
        TestFiles.record(res, "pick_range", r)
        assertTrue("in order", r.zipWithNext().all { (a, b) -> a <= b })
        assertTrue("a narrow range around the picked values: ${r[3] - r[0]}", r[3] - r[0] < 0.5)
        assertEquals(id, c.state.value.picker?.controlId)
        TestFiles.record(res, "pick_passed", "true")
    }
}

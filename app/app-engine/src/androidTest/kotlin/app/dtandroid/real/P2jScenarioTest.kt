// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.schema.ComputedList
import app.dtandroid.schema.ComputedSlider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief P2j deliverable 6 on the emulator, with the real engine and the public test RAW. Each case sets controls
 * that desktop keeps outside the parameters through the darkroom (as a finger does), saves the XMP, and exports
 * it; the CI compare job renders the same XMP with darktable-cli and compares (PSNR >= 40 dB, as G0). Results in
 * files/p2b-test/p2j-results.txt; XMPs and exports as p2j-*.xmp / p2j-*.jpg.
 *
 * j1: color calibration with a custom illuminant set by hue and chroma. j2: spektrafilm with another film stock
 * and paper (needs the data pack in the APK). j3: framing with an aspect from its list.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P2jScenarioTest {
    private val res = "p2j-results.txt"

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    /** Open the test RAW with a fresh sidecar in its own folder, run [edit], save, export the saved XMP. */
    private fun case(name: String, edit: suspend (app.dtandroid.darkroom.DarkroomController) -> Unit) = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p2j-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        val c = AppHost.controller
        c.start(Series("p2j $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        try {
            edit(c)
            assertTrue("no error: ${c.state.value.message}", c.state.value.message == null)
            c.save()
        } finally {
            c.closePhoto()
        }
        val xmp = File(TestFiles.out, "p2j-$name.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        val jpg = File(TestFiles.out, "p2j-$name.jpg").apply { delete() }
        c.withEngine { e ->
            runCatching { e.close() }
            e.open(raw.path, xmp.path)
            try {
                e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE))
            } finally {
                runCatching { e.close() }
            }
        }
        assertTrue("export written", jpg.length() > 0)
        TestFiles.record(res, "${name}_export_bytes", jpg.length())
    }

    private fun params(c: app.dtandroid.darkroom.DarkroomController, op: String) = c.state.value.modules.first { it.op == op }.params
    private fun num(c: app.dtandroid.darkroom.DarkroomController, op: String, n: String) = params(c, op)[n]!!.jsonPrimitive.content.toDouble()

    @Test
    fun j1_colorCalibrationCustomIlluminantByHueAndChroma() = case("colorcalibration") { c ->
        val key = InstanceKey("channelmixerrgb", 0)
        c.setEnabled(key, true)
        c.setParam(key, "illuminant", JsonPrimitive("DT_ILLUMINANT_CUSTOM"))
        val ms = AppHost.schema.modules.getValue("channelmixerrgb")
        val hue = ms.control(11) as app.dtandroid.schema.Control.Slider
        val chroma = ms.control(12) as app.dtandroid.schema.Control.Slider
        // a drag on the hue slider (several steps, one history item), then an exact chroma
        for (h in listOf(150.0, 180.0, 210.0)) c.drag(key, hue.id, hue.binding, h, false)
        c.endDrag(key, hue.id, hue.binding, false)
        waitIdle(c)
        c.setSlider(key, chroma.binding, 35.0, false, chroma.id)
        val model = ms.computed.getValue(11) as ComputedSlider
        val p = params(c, "channelmixerrgb")
        val shownHue = model.value(p) { null }!!
        val shownChroma = (ms.computed.getValue(12) as ComputedSlider).value(p) { null }!!
        TestFiles.record(res, "j1_params", "x ${num(c, "channelmixerrgb", "x")} y ${num(c, "channelmixerrgb", "y")} temperature ${num(c, "channelmixerrgb", "temperature")}")
        TestFiles.record(res, "j1_shown", "hue $shownHue chroma $shownChroma")
        assertEquals(210.0, shownHue, 0.05)
        assertEquals(35.0, shownChroma, 0.01)
        assertEquals("DT_ILLUMINANT_CUSTOM", params(c, "channelmixerrgb")["illuminant"]!!.jsonPrimitive.content)
    }

    @Test
    fun j2_spektrafilmAnotherFilmStock() {
        TestFiles.engine()
        if (SpektrafilmPack.state.id == null) {
            TestFiles.record(res, "j2", "skipped: this build carries no spektrafilm data")
            return
        }
        case("spektrafilm") { c ->
            val key = InstanceKey("spektrafilm", 0)
            c.setEnabled(key, true)
            val ms = AppHost.schema.modules.getValue("spektrafilm")
            val film = ms.computed.getValue(2) as ComputedList
            val paper = ms.computed.getValue(3) as ComputedList
            c.chooseComputed(key, 2, film.entries(params(c, "spektrafilm")).indexOfFirst { it.label == "Kodak Portra 400" })
            c.chooseComputed(key, 3, paper.entries(params(c, "spektrafilm")).indexOfFirst { it.label == "Kodak Endura Premier" })
            val fmt = ms.computed.getValue(4) as ComputedList
            c.chooseComputed(key, 4, fmt.entries(params(c, "spektrafilm")).indexOfFirst { it.label == "6x7" })
            val p = params(c, "spektrafilm")
            TestFiles.record(res, "j2_params", "film_hash ${p["film_hash"]} paper_hash ${p["paper_hash"]} lut_hash ${p["lut_hash"]} film_format_mm ${p["film_format_mm"]}")
            assertEquals("Kodak Portra 400", film.entries(p)[film.current(p)!!].label)
            assertEquals("Kodak Endura Premier", paper.entries(p)[paper.current(p)!!].label)
            assertEquals(69.0, p["film_format_mm"]!!.jsonPrimitive.double, 1e-4)
        }
    }

    @Test
    fun j3_framingAspectFromItsList() = case("framing") { c ->
        val key = InstanceKey("borders", 0)
        c.setEnabled(key, true)
        val ms = AppHost.schema.modules.getValue("borders")
        val aspect = ms.computed.getValue(2) as ComputedList
        c.chooseComputed(key, 2, aspect.entries(params(c, "borders")).indexOfFirst { it.label == "16:9" })
        val pos = ms.computed.getValue(5) as ComputedList
        c.chooseComputed(key, 5, pos.entries(params(c, "borders")).indexOfFirst { it.label == "1/3" })
        val info = c.state.value.info!!
        TestFiles.record(res, "j3_size", "${info.width}x${info.height} aspect ${num(c, "borders", "aspect")} pos_h ${num(c, "borders", "pos_h")}")
        assertEquals((16f / 9f).toDouble(), num(c, "borders", "aspect"), 1e-6)
        val r = maxOf(info.width, info.height).toDouble() / minOf(info.width, info.height)
        assertEquals(16.0 / 9.0, r, 0.01)
    }

    private fun waitIdle(c: app.dtandroid.darkroom.DarkroomController) {
        val end = System.currentTimeMillis() + 60_000
        while (c.liveValues.value.isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(300)
    }
}

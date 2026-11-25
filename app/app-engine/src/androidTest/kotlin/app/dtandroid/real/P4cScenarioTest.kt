// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DtNames
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.RETOUCH_OP
import app.dtandroid.darkroom.RetouchTool
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.engine.api.CoordsDirection
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief P4c item 1 on the emulator, real engine, public test RAW: a crop of the middle, then retouch's panel opened
 * (the module takes the focus: the whole picture, crop's frame), a heal spot tapped left of and above the frame
 * (outside the edit's picture), the panel closed (the cropped picture again). The XMP is kept as p4c-heal.xmp with the
 * app's export p4c-heal.jpg; the workflow renders the XMP with darktable-cli and compares; the spot is read back from
 * the XMP in a new session.
 * r2 (item 3): diffuse or sharpen's presets sheet lists its built-in presets; "lens deblur | medium" is found by the
 * name shown and applied through the sheet: one history item, the preset's parameters, the picture changes.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P4cScenarioTest {
    private val res = "p4c-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null
    private val rt = InstanceKey(RETOUCH_OP, 0)
    private val t = 0.01

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!runCatching(cond).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; message ${AppHost.controller.state.value.message}; note ${AppHost.controller.retouch.state.value.note}")
            Thread.sleep(10)
        }
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size

    @Test fun r1_healOutsideTheCropRoundTrips() {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p4c-heal").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        runBlocking { c.start(Series("p4c", listOf(item)), 0, filmstrip = false, thumbnails = false) }
        waitFor("preview") { c.state.value.preview != null }
        // the user's crop: the middle of the photo
        runBlocking {
            c.withEngine { e ->
                e.paramsSet("crop", 0, mapOf("cx" to JsonPrimitive(0.3), "cy" to JsonPrimitive(0.3), "cw" to JsonPrimitive(0.8), "ch" to JsonPrimitive(0.8),
                    "ratio_d" to JsonPrimitive(0), "ratio_n" to JsonPrimitive(0)))
                e.setEnabled("crop", 0, true)
                e.historySeal()
            }
        }
        val cropped = c.state.value.info!!
        // retouch's panel: the focus, the whole picture
        c.toggleExpanded(rt)
        runBlocking { c.retouch.open(rt) }
        waitFor("focused") { c.retouch.state.value.focus?.frame != null && c.retouch.state.value.st != null }
        waitFor("whole picture") { c.state.value.info!!.width > cropped.width }
        val whole = c.state.value.info!!
        val frame = c.retouch.state.value.focus!!.frame!!
        TestFiles.record(res, "r1_focus", "picture ${cropped.width}x${cropped.height} -> ${whole.width}x${whole.height}, frame " +
            frame.joinToString(" ") { "%.4f,%.4f".format(it.first, it.second) })
        assertTrue("the whole picture is larger", whole.width > cropped.width && whole.height > cropped.height)
        // a heal spot left of and above the frame
        val n0 = items(c)
        val x = frame[0].first / 2
        val y = frame[0].second / 2
        c.retouch.setNewValue("size", 0.03)
        c.retouch.setTool(RetouchTool.CIRCLE)
        assertTrue(c.retouch.touchDown(x, y, t, t))
        c.retouch.touchUp(x, y, t, t)
        waitFor("spot placed") { c.retouch.state.value.st?.shapes?.size == 1 && c.retouch.state.value.outlines.size == 1 }
        assertEquals("one history item", n0 + 1, items(c))
        val s = c.retouch.state.value.st!!.shapes[0]
        val center = (s.geometry["center"] as JsonArray).map { (it as JsonPrimitive).doubleOrNull!! }
        val o = c.retouch.state.value.outlines[0]
        TestFiles.record(res, "r1_spot", "tap %.4f,%.4f, outline %.4f,%.4f, original %.4f,%.4f, %s".format(x, y, o.targetAt.first, o.targetAt.second, center[0], center[1], s.algorithm))
        assertEquals("heal", s.algorithm)
        assertEquals("the outline is where the finger was", x, o.targetAt.first, 0.01)
        // the panel closes: the edit's own picture, the spot outside it
        c.toggleExpanded(rt)
        waitFor("cropped again") { c.state.value.info!!.width == cropped.width && c.retouch.state.value.focus == null }
        val inEdit = runBlocking { c.withEngine { e -> e.coordsTransform(CoordsDirection.TO_PROCESSED, listOf(center[0] to center[1])) } }.points[0]
        TestFiles.record(res, "r1_outside", "spot in the edit's picture: ${inEdit?.let { "%.4f,%.4f".format(it.first, it.second) }}")
        assertTrue("the spot is outside the edit's picture", inEdit == null || inEdit.first < 0.0 || inEdit.second < 0.0)
        // save, keep the XMP, export with the engine, read the spot back from the XMP
        runBlocking { c.save() }
        val xmp = File(TestFiles.out, "p4c-heal.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        runBlocking { c.closePhoto() }
        val jpg = File(TestFiles.out, "p4c-heal.jpg").apply { delete() }
        val back = runBlocking {
            c.withEngine { e ->
                runCatching { e.close() }
                e.open(item.rawPath, xmp.path)
                try {
                    e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE))
                    e.retouch(RETOUCH_OP, 0)
                } finally { runCatching { e.close() } }
            }
        }
        val c2 = (back.shapes.single().geometry["center"] as JsonArray).map { (it as JsonPrimitive).doubleOrNull!! }
        TestFiles.record(res, "r1_xmp", "shapes ${back.shapes.size}, centre %.4f,%.4f, export ${jpg.length()} bytes".format(c2[0], c2[1]))
        assertNotNull(back.shapes.single().source)
        assertEquals(center[0], c2[0], 1e-4)
        assertEquals(center[1], c2[1], 1e-4)
    }

    @Test fun r2_diffuseBuiltInPresetAppliedByName() {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p4c-diffuse").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        runBlocking { c.start(Series("p4c diffuse", listOf(item)), 0, filmstrip = false, thumbnails = false) }
        waitFor("preview") { c.state.value.preview != null }
        val key = InstanceKey("diffuse", 0)
        val plain = runBlocking { c.withEngine { e -> e.renderPreview(800) } }
        runBlocking { c.stylesPresets.loadPresets(key) }
        val list = c.stylesPresets.state.value.presets
        val builtins = list.filter { it.builtin }
        TestFiles.record(res, "r2_list", builtins.joinToString(", ") { "${it.name} (usable ${it.usable})" })
        assertTrue("diffuse lists built-in presets", builtins.size >= 2)
        val stored = DtNames.storedName(builtins.map { it.name }, "lens deblur | medium")
        assertEquals("_builtin_lens deblur | medium", stored)
        val n0 = items(c)
        assertTrue("applied by the sheet: ${c.stylesPresets.state.value.note}", runBlocking { c.stylesPresets.applyPreset(key, stored!!) })
        val m = c.state.value.modules.first { it.key == key }
        val it = (m.params["iterations"] as JsonPrimitive).content.toDouble()
        val rad = (m.params["radius"] as JsonPrimitive).content.toDouble()
        val after = runBlocking { c.withEngine { e -> e.renderPreview(800) } }
        var sum = 0L
        for (i in plain.rgba.indices) if (i % 4 != 3) sum += kotlin.math.abs((plain.rgba[i].toInt() and 255) - (after.rgba[i].toInt() and 255))
        val d = sum.toDouble() / (plain.width * plain.height * 3)
        TestFiles.record(res, "r2_applied", "items $n0 -> ${items(c)}, enabled ${m.enabled}, iterations $it, radius $rad, preview diff %.4f".format(d))
        assertEquals("one history item", n0 + 1, items(c))
        assertTrue(m.enabled)
        assertEquals(16.0, it, 0.0)
        assertEquals(10.0, rad, 0.0)
        assertTrue("the picture changes ($d)", d > 0.0)
    }
}

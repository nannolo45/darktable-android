// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.LUT_OP
import app.dtandroid.darkroom.LutPatches
import app.dtandroid.darkroom.RETOUCH_OP
import app.dtandroid.darkroom.RetouchTool
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.key
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.RenderOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.abs

/**
 * Brief P3m on the emulator, with the real engine and the public test RAW (no sidecar: darktable's default edit),
 * through the controller's calls (the same calls the touch handler and the panel make):
 *  m1 a heal circle placed by a tap with retouch's panel open: one history item, the picture changes; its source
 *     dragged: one item, the picture changes again; "switch off shapes" renders without the module; XMP kept as
 *     p3m-heal.xmp / .jpg for darktable-cli;
 *  m2 the shapes of every algorithm (clone ellipse by press and drag, fill brush, blur path) kept as p3m-all;
 *  m3 a colour look up table patch changed through the panel's slider call: the picture changes; p3m-lut.
 * The workflow renders each XMP with darktable-cli and compares it with the app's export.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3mScenarioTest {
    private val res = "p3m-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null
    private val rt = InstanceKey(RETOUCH_OP, 0)
    private val lut = InstanceKey(LUT_OP, 0)
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

    private fun open(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3m-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p3m $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c to item
    }

    private suspend fun preview(c: DarkroomController, options: RenderOptions = RenderOptions.NONE): RenderedImage =
        c.withEngine { e -> if (options.isNone) e.renderPreview(800) else e.renderPreview(800, options) }

    private fun diff(a: RenderedImage, b: RenderedImage): Double {
        var sum = 0L
        for (i in a.rgba.indices) if (i % 4 != 3) sum += abs((a.rgba[i].toInt() and 255) - (b.rgba[i].toInt() and 255))
        return sum.toDouble() / (a.width * a.height * 3)
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size

    private fun openRetouch(c: DarkroomController) {
        c.toggleExpanded(rt)
        runBlocking { c.retouch.open(rt) }
        waitFor("retouch read") { c.retouch.state.value.st != null }
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

    @Test fun m1_healCircleTapAndSourceDrag() {
        val (c, item) = open("heal")
        openRetouch(c)
        val plain = runBlocking { preview(c) }
        val n0 = items(c)
        c.retouch.setNewValue("size", 0.05)
        c.retouch.setTool(RetouchTool.CIRCLE)
        assertTrue(c.retouch.touchDown(0.45, 0.55, t, t))
        c.retouch.touchUp(0.45, 0.55, t, t)
        waitFor("circle placed") { c.retouch.state.value.st?.shapes?.size == 1 && c.retouch.state.value.outlines.size == 1 }
        val s = c.retouch.state.value.st!!.shapes[0]
        assertEquals("heal", s.algorithm)
        assertNotNull(s.source)
        assertEquals("one history item", n0 + 1, items(c))
        val healed = runBlocking { preview(c) }
        val d1 = diff(plain, healed)
        TestFiles.record(res, "m1_heal_placed", "items ${n0}->${items(c)}, source ${s.source}, preview diff %.4f".format(d1))
        assertTrue("the heal changes the picture ($d1)", d1 > 0.0)
        // the source dragged to another place: one item, another picture
        val o = c.retouch.state.value.outlines[0]
        val at = o.sourceAt!!
        val n1 = items(c)
        assertTrue(c.retouch.touchDown(at.first, at.second, t, t))
        c.retouch.touchMove(at.first - 0.25, at.second + 0.2, t, t)
        c.retouch.touchUp(at.first - 0.25, at.second + 0.2, t, t)
        waitFor("source moved") { c.retouch.state.value.st!!.shapes[0].source != s.source }
        assertEquals("one history item for the drag", n1 + 1, items(c))
        val moved = runBlocking { preview(c) }
        val d2 = diff(healed, moved)
        TestFiles.record(res, "m1_source_moved", "source ${c.retouch.state.value.st!!.shapes[0].source}, preview diff %.4f".format(d2))
        assertTrue("moving the source changes the picture ($d2)", d2 > 0.0)
        // "temporarily switch off shapes": the render without retouch equals the plain one, the edit untouched
        val off = runBlocking { preview(c, RenderOptions.bypass(RETOUCH_OP, 0)) }
        val d3 = diff(plain, off)
        TestFiles.record(res, "m1_switched_off", "preview diff to the plain picture %.4f".format(d3))
        assertTrue("switched off = without retouch ($d3)", d3 < 0.5)
        export(c, item, "p3m-heal")
    }

    @Test fun m2_everyAlgorithmAndShape() {
        val (c, item) = open("all")
        openRetouch(c)
        val n0 = items(c)
        // clone ellipse, press and drag: the source where the finger lifts
        runBlocking { c.retouch.setAlgorithm("clone") }
        c.retouch.setTool(RetouchTool.ELLIPSE)
        c.retouch.touchDown(0.3, 0.4, t, t); c.retouch.touchMove(0.4, 0.3, t, t); c.retouch.touchUp(0.4, 0.3, t, t)
        waitFor("ellipse") { c.retouch.state.value.st?.shapes?.size == 1 }
        // fill brush
        runBlocking { c.retouch.setAlgorithm("fill") }
        c.retouch.setTool(RetouchTool.BRUSH)
        c.retouch.touchDown(0.6, 0.6, t, t); c.retouch.touchMove(0.65, 0.62, t, t); c.retouch.touchMove(0.7, 0.6, t, t); c.retouch.touchUp(0.7, 0.6, t, t)
        waitFor("brush") { c.retouch.state.value.st?.shapes?.size == 2 }
        runBlocking { c.retouch.setSetting("fill_mode", JsonPrimitive("color")) }
        runBlocking { c.retouch.setFillColorLater(listOf(0.8, 0.2, 0.1)) }
        waitFor("fill colour") { c.retouch.state.value.st!!.shapes[1].fillColor[0] > 0.7 }
        // blur path
        runBlocking { c.retouch.setAlgorithm("blur") }
        c.retouch.setTool(RetouchTool.PATH)
        for ((x, y) in listOf(0.2 to 0.7, 0.3 to 0.7, 0.25 to 0.8)) { c.retouch.touchDown(x, y, t, t); c.retouch.touchUp(x, y, t, t) }
        runBlocking { c.retouch.finishPath() }
        waitFor("path") { c.retouch.state.value.st?.shapes?.size == 3 }
        val st = c.retouch.state.value.st!!
        TestFiles.record(res, "m2_shapes", st.shapes.joinToString { "${it.type}/${it.algorithm}" } + ", items ${n0}->${items(c)}")
        assertEquals(listOf("ellipse/clone", "brush/fill", "path/blur"), st.shapes.map { "${it.type}/${it.algorithm}" })
        export(c, item, "p3m-all")
    }

    @Test fun m3_lookUpTablePatchChanged() {
        val (c, item) = open("lut")
        c.toggleExpanded(lut)
        c.lut.open(lut)
        val plain = runBlocking { preview(c) }
        val p0 = LutPatches.of(c.state.value.modules.first { it.key == lut }.params)!!
        TestFiles.record(res, "m3_patches", "${p0.n}")
        assertEquals(24, p0.n)
        // patch 13 (green): towards magenta and lighter, through the panel's slider calls
        c.lut.select(13)
        runBlocking { c.lut.setOffset("green-red", 60.0) }
        runBlocking { c.lut.setOffset("lightness", 15.0) }
        waitFor("patch changed") { LutPatches.of(c.state.value.modules.first { it.key == lut }.params)!!.altered(13) }
        val changed = runBlocking { preview(c) }
        val d = diff(plain, changed)
        TestFiles.record(res, "m3_lut_changed", "preview diff %.4f".format(d))
        assertTrue("the patch changes the picture ($d)", d > 0.0)
        export(c, item, "p3m-lut")
    }
}

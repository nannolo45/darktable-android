// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.BottomView
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.MaskMode
import app.dtandroid.darkroom.Masks
import app.dtandroid.darkroom.ParamChannels
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.ShapeType
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
 * Brief P3d stage 2c on the emulator, real engine, the public test RAW (exposure raised by 1.5 EV). Results go to
 * files/p2b-test/p3d-edit-results.txt; the XMP and the export of the last scenario as p3d-dp.xmp / .jpg, which the
 * workflow renders with darktable-cli and compares.
 *
 * f1: a circle moved after "done": the picture changes, one history item, one undo step puts it back.
 * f2: a shape added with a nested group chosen lands in that group.
 * f3: drawn & parametric: a circle intersected with a luminance range: the picture changes as expected, the XMP
 * reopens the same.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3dEditScenarioTest {
    private val res = "p3d-edit-results.txt"
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
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; masks ${AppHost.controller.masks.state.value}, message ${AppHost.controller.state.value.message}")
            Thread.sleep(10)
        }
    }

    private fun open(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3de-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p3de $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c.setEnabled(exposure, true)
        c.setParam(exposure, "exposure", JsonPrimitive(1.5))
        c.setView(BottomView.MASKS)
        c.masks.select(exposure)
        waitFor("exposure in the masks tab") { c.masks.state.value.module?.key == exposure && c.parametric.state.value.blend != null }
        c.masks.setMode(MaskMode.DRAWN)
        c to item
    }

    private suspend fun preview(c: DarkroomController, options: RenderOptions = RenderOptions.NONE): RenderedImage =
        c.withEngine { e -> if (options.isNone) e.renderPreview(800) else e.renderPreview(800, options) }

    private fun diff(a: RenderedImage, b: RenderedImage): Double {
        var sum = 0L
        for (i in a.rgba.indices) if (i % 4 != 3) sum += abs((a.rgba[i].toInt() and 255) - (b.rgba[i].toInt() and 255))
        return sum.toDouble() / (a.width * a.height * 3)
    }

    private fun yellow(a: RenderedImage): Double {
        var n = 0
        for (p in 0 until a.width * a.height) {
            val r = a.rgba[p * 4].toInt() and 255
            val g = a.rgba[p * 4 + 1].toInt() and 255
            val b = a.rgba[p * 4 + 2].toInt() and 255
            if (r > b + 40 && g > b + 40) n++
        }
        return n.toDouble() / (a.width * a.height)
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size

    private fun circle(c: DarkroomController, x: Double, size: Double): Int {
        runBlocking {
            c.masks.startDraw(ShapeType.CIRCLE)
            c.masks.setDrawValue("size", size)
            c.masks.touchDown(x, 0.5)
            c.masks.touchUp()
            c.masks.finishDraw()
        }
        waitFor("circle added") { c.masks.state.value.draw == null && c.masks.state.value.selected != null }
        return c.masks.state.value.selected!!
    }

    private fun form(c: DarkroomController, id: Int) = c.masks.state.value.forms.first { it.id == id }

    @Test
    fun f1_circleMovedAfterDone() {
        val (c, _) = open("move")
        val id = circle(c, 0.3, 0.12)
        val placed = runBlocking { preview(c) }
        val geo0 = form(c, id).geometry
        val n0 = items(c)
        runBlocking { c.masks.startEdit(id) }
        waitFor("edit open") { c.masks.state.value.draw?.editing == id }
        val d = c.masks.state.value.draw!!
        TestFiles.record(res, "move_start_centre", d.center.toString())
        c.masks.touchDown(d.center!!.first, d.center!!.second)
        c.masks.touchMove(0.7, 0.55)
        c.masks.touchUp()
        runBlocking { c.masks.finishDraw() }
        waitFor("edit done") { c.masks.state.value.draw == null }
        val moved = runBlocking { preview(c) }
        val geo1 = form(c, id).geometry
        TestFiles.record(res, "move_geometry", "before $geo0 after $geo1")
        val dm = diff(placed, moved)
        TestFiles.record(res, "move_diff", dm)
        assertTrue("the picture changes when the circle moves: $dm", dm > 0.5)
        assertEquals("one history item", n0 + 1, items(c))
        runBlocking { c.undo() }
        waitFor("one undo puts it back") { form(c, id).geometry == geo0 }
        val back = runBlocking { preview(c) }
        val db = diff(placed, back)
        TestFiles.record(res, "move_diff_after_undo", db)
        assertTrue("after one undo the picture is as before: $db", db < 0.5)
        TestFiles.record(res, "move_passed", "true")
    }

    @Test
    fun f2_shapeInANestedGroup() {
        val (c, _) = open("nested")
        val a = circle(c, 0.3, 0.1)
        val root = c.masks.state.value.module!!.rootId!!
        runBlocking { c.masks.wrapInGroup(root, a) }
        waitFor("nested group") { Masks.treeRows(c.masks.state.value.forms, root, emptySet()).any { it.isGroup } }
        val nested = Masks.treeRows(c.masks.state.value.forms, root, emptySet()).first { it.isGroup }.member.id
        runBlocking { c.masks.chooseGroup(nested) }
        val b = circle(c, 0.7, 0.1)
        val inNested = form(c, nested).members.map { it.id }
        val inRoot = form(c, root).members.map { it.id }
        TestFiles.record(res, "nested_members", "nested $inNested root $inRoot")
        assertTrue("the new circle is in the chosen group", b in inNested)
        assertTrue("and not in the module's group", b !in inRoot)
        TestFiles.record(res, "nested_passed", "true")
    }

    @Test
    fun f3_drawnAndParametric() {
        val (c, item) = open("dp")
        val unmasked = runBlocking { preview(c) }
        val mean = runBlocking { c.withEngine { e -> e.colorPick("exposure", 0, PickRequest(area = listOf(0.0, 0.0, 1.0, 1.0))).input!!.mean } }
        val lum = 0.3 * mean[0]!! + 0.59 * mean[1]!! + 0.11 * mean[2]!!
        val id = circle(c, 0.5, 0.4)
        val root = c.masks.state.value.module!!.rootId!!
        // the circle with the luminance range: only what is in both (minimum)
        runBlocking { c.masks.setGroupOperator(root, "minimum") }
        c.masks.toggleShowMask()
        val circleOnly = runBlocking { preview(c, RenderOptions(showMask = InstanceRef("exposure", 0))) }
        c.masks.toggleShowMask()
        val maskedCircle = runBlocking { preview(c) }
        val spec = ParamChannels.of(c.parametric.state.value.space).first { it.base == "g" }
        val n0 = items(c)
        runBlocking { c.parametric.addChannel(spec, c.masks.targetGroup(), select = true) }
        waitFor("element in the tree") { c.parametric.state.value.all.any { it.base == "g" } }
        val el = c.parametric.state.value.all.first { it.base == "g" }
        runBlocking { c.parametric.setRange(el.id, false, listOf(0.0, 0.0, lum.coerceIn(0.01, 0.9), (lum * 1.3).coerceIn(0.02, 1.0))) }
        assertTrue("an element and its range are history items", items(c) >= n0 + 1)
        val range = c.parametric.state.value.all.first { it.id == el.id }.inRange
        TestFiles.record(res, "dp_range", range)
        c.masks.toggleShowMask()
        val both = runBlocking { preview(c, RenderOptions(showMask = InstanceRef("exposure", 0))) }
        c.masks.toggleShowMask()
        val masked = runBlocking { preview(c) }
        val yc = yellow(circleOnly)
        val yb = yellow(both)
        val dd = diff(maskedCircle, masked)
        TestFiles.record(res, "dp_show_mask", "yellow share circle only $yc, circle and range $yb; picture change $dd; against unmasked ${diff(unmasked, masked)}")
        assertTrue("the circle alone covers part of the picture: $yc", yc > 0.02)
        assertTrue("the range cuts the circle down: $yb of $yc", yb < yc * 0.95 && yb > 0.0)
        assertTrue("the picture changes with the range: $dd", dd > 0.5)
        // written and reopened: the same tree
        val before = c.masks.state.value.forms.map { it.type }.sorted()
        runBlocking { c.save() }
        val xmp = File(TestFiles.out, "p3d-dp.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        runBlocking {
            c.closePhoto()
            c.start(Series("p3de dp again", listOf(item)), 0, filmstrip = false, thumbnails = false)
        }
        waitFor("preview again") { c.state.value.preview != null }
        c.setView(BottomView.MASKS)
        runBlocking { c.masks.select(exposure) }
        waitFor("tree read again") { c.parametric.state.value.all.any { it.base == "g" } }
        val again = c.parametric.state.value.all.first { it.base == "g" }.inRange
        TestFiles.record(res, "dp_reopened", again)
        for (i in 0..3) assertTrue("reopened range marker $i: ${range[i]} vs ${again[i]}", abs(range[i] - again[i]) < 1e-5)
        assertEquals("the same forms", before, c.masks.state.value.forms.map { it.type }.sorted())
        val d2 = diff(masked, runBlocking { preview(c) })
        TestFiles.record(res, "dp_diff_reopened", d2)
        assertTrue("reopened, the same picture: $d2", d2 < 0.5)
        assertTrue(id != 0)
        // the export of that XMP, for the workflow's darktable-cli comparison
        val jpg = File(TestFiles.out, "p3d-dp.jpg").apply { delete() }
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
        TestFiles.record(res, "dp_export", "${o.outWidth}x${o.outHeight}")
        assertTrue(o.outWidth > 0)
        TestFiles.record(res, "dp_passed", "true")
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dtandroid.darkroom.BottomView
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.MaskMode
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.ShapeType
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.InstanceRef
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.RenderedImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Brief P3d stage 1 on the emulator, with the real engine and the public test RAW (darktable's default edit, exposure
 * raised by 1.5 EV so its mask shows). Results go to files/p2b-test/p3d-results.txt; each scenario's XMP and the
 * app's export of it as p3d-<name>.xmp / .jpg, which the workflow renders with darktable-cli and compares.
 *
 * d1: a circle added to exposure through the masks tab: the preview differs from the unmasked one, "show mask"
 * renders the engine's mask display (yellow where the mask is), the XMP written and reopened holds the circle in
 * exposure's mask.
 * d2: a brush stroke painted with one finger by touch events injected into the system; the same checks.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3dScenarioTest {
    private val res = "p3d-results.txt"
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
        val dir = File(TestFiles.ctx.filesDir, "p3d-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p3d $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c.setEnabled(exposure, true)
        c.setParam(exposure, "exposure", JsonPrimitive(1.5))
        c.setView(BottomView.MASKS)
        c.masks.select(exposure)
        waitFor("exposure in the masks tab") { c.masks.state.value.module?.key == exposure }
        c to item
    }

    private suspend fun preview(c: DarkroomController, options: RenderOptions = RenderOptions.NONE): RenderedImage =
        c.withEngine { e -> if (options.isNone) e.renderPreview(800) else e.renderPreview(800, options) }

    /** mean absolute difference of two pictures of the same size, 0..255 */
    private fun diff(a: RenderedImage, b: RenderedImage): Double {
        assertEquals(a.width, b.width)
        var sum = 0L
        for (i in a.rgba.indices) if (i % 4 != 3) sum += abs((a.rgba[i].toInt() and 255) - (b.rgba[i].toInt() and 255))
        return sum.toDouble() / (a.width * a.height * 3)
    }

    /** share of pixels the mask display paints yellow (red and green clearly above blue) */
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

    /** the mean colour of a small square around fractions ([fx], [fy]) of the picture */
    private fun at(a: RenderedImage, fx: Double, fy: Double): IntArray {
        val cx = (fx * a.width).toInt()
        val cy = (fy * a.height).toInt()
        val sum = IntArray(3)
        var n = 0
        for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) {
            if (x !in 0 until a.width || y !in 0 until a.height) continue
            for (k in 0..2) sum[k] += a.rgba[(y * a.width + x) * 4 + k].toInt() and 255
            n++
        }
        return IntArray(3) { sum[it] / maxOf(n, 1) }
    }

    /**
     * The checks both scenarios share, after their shape was added to exposure's mask. [inside] and [outside] are
     * points of the picture (fractions) on the shape and far from it: with "show mask" the first is yellow (the mask
     * display's colour), the second grey (the picture without colour, engine-api.md 6.8).
     */
    private fun checkMasked(c: DarkroomController, item: SeriesItem, name: String, unmasked: RenderedImage, type: String,
                            inside: Pair<Double, Double>, outside: Pair<Double, Double>) = runBlocking {
        val s = c.masks.state.value
        val shapes = s.module!!.members.mapNotNull { m -> s.forms.firstOrNull { it.id == m.id } }
        TestFiles.record(res, "${name}_shapes", shapes.map { "${it.type} ${it.name} ${it.geometry}" })
        TestFiles.record(res, "${name}_history", c.state.value.history.items.map { it.op })
        assertTrue(shapes.any { it.type == type })
        assertEquals(MaskMode.DRAWN, s.module!!.mode)
        val masked = preview(c)
        val d = diff(unmasked, masked)
        TestFiles.record(res, "${name}_diff_unmasked", d)
        assertTrue("the masked picture differs from the unmasked one: $d", d > 1.0)
        // show mask: through the tab, then the same render read directly
        c.masks.toggleShowMask()
        assertEquals(exposure, c.state.value.showMask)
        val shown = preview(c, RenderOptions(showMask = InstanceRef("exposure", 0)))
        val y = yellow(shown)
        val yPlain = yellow(masked)
        val inC = at(shown, inside.first, inside.second)
        val outC = at(shown, outside.first, outside.second)
        val dShown = diff(masked, shown)
        TestFiles.record(res, "${name}_show_mask", "yellow share $y (plain picture $yPlain), on the shape ${inC.toList()}, " +
            "away ${outC.toList()}, differs from the picture by $dShown")
        assertTrue("show mask: another picture ($dShown)", dShown > 1.0)
        assertTrue("show mask: yellow on the shape ${inC.toList()}", inC[0] > inC[2] + 60 && inC[1] > inC[2] + 60)
        assertTrue("show mask: grey away from it ${outC.toList()}", maxOf(outC[0], outC[1], outC[2]) - minOf(outC[0], outC[1], outC[2]) < 16)
        c.masks.toggleShowMask()
        // written and reopened: the same masks
        val before = shapes.map { it.type to it.geometry.toString() }
        c.save()
        val xmp = File(TestFiles.out, "p3d-$name.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        c.closePhoto()
        c.start(Series("p3d $name again", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview again") { c.state.value.preview != null }
        c.setView(BottomView.MASKS)
        c.masks.select(exposure)
        waitFor("mask read again") { c.masks.state.value.module?.key == exposure }
        val s2 = c.masks.state.value
        val after = s2.module!!.members.mapNotNull { m -> s2.forms.firstOrNull { it.id == m.id } }.map { it.type to it.geometry.toString() }
        TestFiles.record(res, "${name}_reopened", after)
        assertEquals("the XMP reopens with the same masks", before.map { it.first }, after.map { it.first })
        assertEquals(before.size, after.size)
        val again = preview(c)
        val d2 = diff(masked, again)
        TestFiles.record(res, "${name}_diff_reopened", d2)
        assertTrue("reopened, the same picture: $d2", d2 < 0.5)
        // the export of that XMP, for the workflow's darktable-cli comparison
        c.closePhoto()
        val jpg = File(TestFiles.out, "p3d-$name.jpg").apply { delete() }
        c.withEngine { e ->
            runCatching { e.close() }
            e.open(item.rawPath, xmp.path)
            try { e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpg.path, o)
        TestFiles.record(res, "${name}_export", "${o.outWidth}x${o.outHeight}")
        assertTrue(o.outWidth > 0)
        TestFiles.record(res, "${name}_passed", "true")
    }

    @Test
    fun d1_circleOnExposure() {
        val (c, item) = open("circle")
        val unmasked = runBlocking { preview(c) }
        runBlocking {
            c.masks.setMode(MaskMode.DRAWN)
            c.masks.startDraw(ShapeType.CIRCLE)
            c.masks.setDrawValue("size", 0.15)
            c.masks.touchDown(0.4, 0.45)
            c.masks.touchUp()
            c.masks.finishDraw()
        }
        waitFor("circle in the mask") { c.masks.state.value.module?.members?.size == 1 && c.masks.state.value.draw == null }
        checkMasked(c, item, "circle", unmasked, "circle", inside = 0.4 to 0.45, outside = 0.9 to 0.9)
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

    private fun swipe(from: Pair<Float, Float>, to: Pair<Float, Float>, steps: Int = 16) {
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
    fun d2_brushStrokeByTouch() {
        val (c, item) = open("brush")
        val unmasked = runBlocking { preview(c) }
        runBlocking { c.masks.setMode(MaskMode.DRAWN) }
        c.masks.startDraw(ShapeType.BRUSH)
        c.masks.setDrawValue("size", 0.08)
        waitFor("photo on the screen") { c.photoArea != null && c.currentViewport != null }
        swipe(onScreen(c, 0.2f, 0.5f), onScreen(c, 0.75f, 0.35f))
        waitFor("stroke") { c.masks.state.value.draw?.strokes?.size == 1 }
        TestFiles.record(res, "brush_samples", c.masks.state.value.draw!!.strokes[0].points.size)
        runBlocking { c.masks.finishDraw() }
        waitFor("brush in the mask") { c.masks.state.value.module?.members?.size == 1 && c.masks.state.value.draw == null }
        checkMasked(c, item, "brush", unmasked, "brush", inside = 0.475 to 0.425, outside = 0.5 to 0.92)
    }

    // ---- P3d fix: "use an existing shape…" (the owner's phone closed the app there)

    private fun drawCircle(c: DarkroomController, x: Double, members: Int) {
        runBlocking {
            c.masks.setMode(MaskMode.DRAWN)
            c.masks.startDraw(ShapeType.CIRCLE)
            c.masks.setDrawValue("size", 0.1)
            c.masks.touchDown(x, 0.5)
            c.masks.touchUp()
            c.masks.finishDraw()
        }
        waitFor("circle in the mask") { c.masks.state.value.module?.members?.size == members && c.masks.state.value.draw == null }
    }

    /** the sheet is composed on the real screen: a few frames, then the app must still be alive */
    private fun showSheet(c: DarkroomController, sheet: app.dtandroid.darkroom.MaskSheet) {
        c.masks.openSheet(sheet)
        waitFor("sheet open") { c.masks.state.value.sheet == sheet }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
    }

    @Test
    fun d4_existingShapeSheet() {
        val (c, item) = open("existing")
        val ex = app.dtandroid.darkroom.MaskSheet.Existing
        // no shape in the photo, the module without a group
        showSheet(c, ex)
        assertTrue(c.masks.state.value.forms.isEmpty())
        c.masks.openSheet(null)
        // one shape, then not in the module: the sheet offers it, picking it attaches it
        drawCircle(c, 0.3, 1)
        val shape = c.masks.state.value.module!!.members[0].id
        runBlocking { c.masks.detach(shape) }
        waitFor("detached") { c.masks.state.value.module?.members?.isEmpty() == true }
        showSheet(c, ex)
        c.masks.openSheet(null)
        runBlocking { c.masks.attach(shape) }
        waitFor("attached") { c.masks.state.value.module?.members?.map { it.id } == listOf(shape) }
        showSheet(c, ex) // now "in this module"
        c.masks.openSheet(null)
        // a second module with its own shape: the other's group offered and picked, both ways
        val other = c.state.value.modules.first { it.supportsBlending && it.op != "exposure" }.let { InstanceKey(it.op, it.priority) }
        runBlocking { c.setEnabled(other, true) }
        runBlocking { c.masks.select(other) }
        waitFor("other in the tab") { c.masks.state.value.module?.key == other }
        drawCircle(c, 0.7, 1)
        val rootEx = c.masks.state.value.forms.first { it.rootOf == exposure }.id
        val rootOther = c.masks.state.value.module!!.rootId!!
        showSheet(c, ex)
        c.masks.openSheet(null)
        runBlocking { c.masks.attach(rootEx) }
        waitFor("group attached") { c.masks.state.value.module?.members?.any { it.id == rootEx } == true || c.masks.state.value.note != null }
        showSheet(c, ex)
        c.masks.openSheet(null)
        // the cycle: exposure's group into the group that now holds it, and a group into itself
        runBlocking { c.masks.select(exposure) }
        waitFor("exposure again") { c.masks.state.value.module?.key == exposure }
        runBlocking { c.masks.attach(rootOther) }
        runBlocking { c.masks.attach(rootEx) }
        showSheet(c, ex)
        c.masks.openSheet(null)
        TestFiles.record(res, "existing_forms", c.masks.state.value.forms.size)
        // closed and reopened from the XMP the app wrote
        runBlocking { c.closePhoto() }
        runBlocking { c.start(Series("p3d existing", listOf(item)), 0, filmstrip = false, thumbnails = false) }
        waitFor("preview again") { c.state.value.preview != null }
        c.setView(BottomView.MASKS)
        runBlocking { c.masks.select(exposure) }
        waitFor("exposure after reopen") { c.masks.state.value.module?.key == exposure && c.masks.state.value.forms.isNotEmpty() }
        showSheet(c, ex)
        c.masks.openSheet(null)
        TestFiles.record(res, "existing_passed", "true")
    }

    private fun drawShape(c: DarkroomController, type: ShapeType, n: Int) {
        runBlocking {
            c.masks.setMode(MaskMode.DRAWN)
            c.masks.startDraw(type)
            c.masks.setDrawValue("size", 0.08)
            c.masks.touchDown(0.2, 0.5)
            if (type == ShapeType.BRUSH) { c.masks.touchMove(0.4, 0.45); c.masks.touchMove(0.6, 0.4) }
            c.masks.touchUp()
            c.masks.finishDraw()
        }
        waitFor("shape in the mask") { c.masks.state.value.module?.members?.size == n && c.masks.state.value.draw == null }
    }

    private fun otherKey(c: DarkroomController) =
        c.state.value.modules.first { it.supportsBlending && it.op != "exposure" }.let { InstanceKey(it.op, it.priority) }

    private fun selectModule(c: DarkroomController, k: InstanceKey) {
        runBlocking { c.masks.select(k) }
        waitFor("module $k in the tab") { c.masks.state.value.module?.key == k }
    }

    /** the owner's case: a shape drawn in A, module B selected (no group yet), the shape picked in "use an existing shape" */
    private fun pickInOther(name: String, type: ShapeType, group: Boolean, showMask: Boolean) {
        val (c, _) = open(name)
        drawShape(c, type, 1)
        val shape = c.masks.state.value.module!!.members[0].id
        val root = c.masks.state.value.module!!.rootId!!
        val b = otherKey(c)
        runBlocking { c.setEnabled(b, true) }
        selectModule(c, b)
        assertNull(c.masks.state.value.module!!.rootId)
        if (showMask) c.masks.toggleShowMask()
        showSheet(c, app.dtandroid.darkroom.MaskSheet.Existing)
        val pick = if (group) root else shape
        assertTrue(pick in app.dtandroid.darkroom.Masks.existingOffers(c.masks.state.value.forms, c.masks.state.value.module!!).map { it.id })
        runBlocking { c.masks.attach(pick) }
        waitFor("picked") { c.masks.state.value.module?.members?.any { it.id == pick } == true || c.masks.state.value.note != null }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
        TestFiles.record(res, "${name}_note", c.masks.state.value.note ?: "none")
        // the engine still answers
        runBlocking { preview(c) }
    }

    @Test fun d5a_brushIntoOtherModule() = pickInOther("pick-brush", ShapeType.BRUSH, group = false, showMask = false)
    @Test fun d5b_circleIntoOtherModule() = pickInOther("pick-circle", ShapeType.CIRCLE, group = false, showMask = false)
    @Test fun d5c_brushGroupIntoOtherModule() = pickInOther("pick-group", ShapeType.BRUSH, group = true, showMask = false)
    @Test fun d5d_brushIntoOtherModuleWithShowMask() = pickInOther("pick-show", ShapeType.BRUSH, group = false, showMask = true)

    @Test fun d5e_removedAndAttachedAgain() {
        val (c, _) = open("reattach")
        drawShape(c, ShapeType.BRUSH, 1)
        val shape = c.masks.state.value.module!!.members[0].id
        runBlocking { c.masks.detach(shape) }
        waitFor("detached") { c.masks.state.value.module?.members?.isEmpty() == true }
        runBlocking { c.masks.attach(shape) }
        waitFor("attached") { c.masks.state.value.module?.members?.any { it.id == shape } == true }
        runBlocking { preview(c) }
    }

    // ---- P3d stage 2a: one history item per shape and brush session; the mask tree (engine-api.md 6.9)

    @Test fun d6_oneItemPerShapeAndPerBrushSession() {
        val (c, _) = open("items")
        runBlocking { c.masks.setMode(MaskMode.DRAWN) }
        val n0 = c.state.value.history.items.size
        drawCircle(c, 0.4, 1)
        val n1 = c.state.value.history.items.size
        TestFiles.record(res, "items_circle_history", c.state.value.history.items.map { it.op })
        assertEquals("a circle is one history item", n0 + 1, n1)
        runBlocking { c.undo() }
        waitFor("one undo takes the circle") { c.masks.state.value.module?.members?.isEmpty() == true }
        runBlocking { c.redo() }
        waitFor("redo") { c.masks.state.value.module?.members?.size == 1 }
        // three strokes by touch, then done
        c.masks.startDraw(ShapeType.BRUSH)
        c.masks.setDrawValue("size", 0.05)
        waitFor("photo on the screen") { c.photoArea != null && c.currentViewport != null }
        for (k in 0 until 3) {
            swipe(onScreen(c, 0.2f, 0.3f + 0.2f * k), onScreen(c, 0.7f, 0.35f + 0.2f * k))
            waitFor("stroke ${k + 1}") { c.masks.state.value.draw?.strokes?.size == k + 1 }
        }
        runBlocking { c.masks.finishDraw() }
        waitFor("strokes in the mask") { c.masks.state.value.module?.members?.size == 4 && c.masks.state.value.draw == null }
        val n2 = c.state.value.history.items.size
        TestFiles.record(res, "items_brush_history", c.state.value.history.items.map { it.op })
        assertEquals("a brush session of three strokes is one history item", n1 + 1, n2)
        runBlocking { c.undo() }
        waitFor("one undo takes the three strokes") { c.masks.state.value.module?.members?.size == 1 }
        TestFiles.record(res, "items_passed", "true")
    }

    @Test fun d7_differenceOrderAndTree() {
        val (c, item) = open("tree")
        runBlocking {
            c.masks.setMode(MaskMode.DRAWN)
            for ((size, n) in listOf(0.3 to 1, 0.1 to 2)) {
                c.masks.startDraw(ShapeType.CIRCLE)
                c.masks.setDrawValue("size", size)
                c.masks.touchDown(0.5, 0.5); c.masks.touchUp()
                c.masks.finishDraw()
                waitFor("circle $n") { c.masks.state.value.module?.members?.size == n && c.masks.state.value.draw == null }
            }
        }
        val root = c.masks.state.value.module!!.rootId!!
        val (big, small) = c.masks.state.value.module!!.members.map { it.id }
        runBlocking { c.masks.setGroupOperator(root, "difference") }
        waitFor("difference") { c.masks.state.value.module?.operator == "difference" }
        val ring = runBlocking { preview(c) }
        val n0 = c.state.value.history.items.size
        runBlocking { c.masks.moveMember(root, small, up = false) }
        waitFor("swapped") { c.masks.state.value.module?.members?.map { it.id } == listOf(small, big) }
        assertEquals("a move is one history item", n0 + 1, c.state.value.history.items.size)
        val swapped = runBlocking { preview(c) }
        val d = diff(ring, swapped)
        TestFiles.record(res, "tree_difference_swap_diff", d)
        assertTrue("difference changes with the order of its members: $d", d > 1.0)
        // wrap the big circle in a group: the same picture (a group of one with union)
        runBlocking { c.masks.wrapInGroup(root, big) }
        waitFor("wrapped") { c.masks.state.value.forms.count { it.isGroup } >= 2 }
        val wrapped = runBlocking { preview(c) }
        TestFiles.record(res, "tree_wrap_diff", diff(swapped, wrapped))
        // written and reopened: the same tree and picture
        fun shape(cc: DarkroomController): List<String> {
            val s = cc.masks.state.value
            return app.dtandroid.darkroom.Masks.treeRows(s.forms, s.module?.rootId, s.forms.filter { it.isGroup }.map { it.id }.toSet())
                .map { "${it.depth}:${it.form?.type}:${it.member.inverse}" }
        }
        val before = shape(c)
        TestFiles.record(res, "tree_rows", before)
        runBlocking {
            c.save()
            val xmp = File(TestFiles.out, "p3d-tree.xmp")
            File(item.xmpPath).copyTo(xmp, overwrite = true)
            c.closePhoto()
            c.start(Series("p3d tree again", listOf(item)), 0, filmstrip = false, thumbnails = false)
            waitFor("preview again") { c.state.value.preview != null }
            c.setView(BottomView.MASKS)
            c.masks.select(exposure)
            waitFor("mask read again") { c.masks.state.value.module?.key == exposure && c.masks.state.value.forms.isNotEmpty() }
            val after = shape(c)
            TestFiles.record(res, "tree_rows_reopened", after)
            assertEquals("the XMP reopens with the same tree", before, after)
            val again = preview(c)
            val d2 = diff(wrapped, again)
            TestFiles.record(res, "tree_diff_reopened", d2)
            assertTrue("reopened, the same picture: $d2", d2 < 0.5)
            c.closePhoto()
            val jpg = File(TestFiles.out, "p3d-tree.jpg").apply { delete() }
            c.withEngine { e ->
                runCatching { e.close() }
                e.open(item.rawPath, xmp.path)
                try { e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
            }
            assertTrue(jpg.length() > 0)
        }
        TestFiles.record(res, "tree_passed", "true")
    }
}

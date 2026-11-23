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
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.darkroom.ui.graph.CurveEdit
import app.dtandroid.darkroom.ui.graph.CurveLayout
import app.dtandroid.darkroom.ui.graph.CurveNode
import app.dtandroid.darkroom.ui.graph.LevelsEdit
import app.dtandroid.darkroom.ui.graph.ToneEqMath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Brief P3e on the emulator, with the real engine and the public test RAW (without a sidecar: darktable's default
 * edit). Results go to files/p2b-test/p3e-results.txt; each scenario's XMP and the app's export of it as
 * p3e-<name>.xmp / .jpg, which the workflow renders with darktable-cli and compares (size and PSNR).
 *
 * tonecurve: a node added and moved as the graph editor writes it (curveSet, curveDrag, curveEnd): the picture
 * changes, one history item per drag, and the XMP read back by the engine holds the same nodes.
 * rgbcurve: "create curve" (module action set_nodes_from_area), armed by its picker button: nodes at min, mean and max.
 * rgblevels: the black handle moved as the editor writes it, on the green channel with independent channels.
 * toneeq: a vertical drag on the photo (touch events injected into the system) with the module's panel open: a band
 * goes up, the picture changes, one history item.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3eScenarioTest {
    private val res = "p3e-results.txt"
    private val tone = InstanceKey("tonecurve", 0)
    private val rgb = InstanceKey("rgbcurve", 0)
    private val levels = InstanceKey("rgblevels", 0)
    private val eq = InstanceKey("toneequal", 0)
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
        val dir = File(TestFiles.ctx.filesDir, "p3e-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p3e $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
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

    private fun exportAndKeep(c: DarkroomController, item: SeriesItem, name: String): Pair<Int, Int> = runBlocking {
        c.save()
        val xmp = File(TestFiles.out, "p3e-$name.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        val info = c.state.value.info!!
        c.closePhoto()
        val jpg = File(TestFiles.out, "p3e-$name.jpg").apply { delete() }
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


    private fun params(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }.params

    private fun items(c: DarkroomController) = c.state.value.history.items.size

    /** The mean absolute difference of two renders of the picture, 0..255. */
    private fun difference(a: RenderedImage, b: RenderedImage): Double {
        assertEquals(a.rgba.size, b.rgba.size)
        var sum = 0L
        for (i in a.rgba.indices) if (i % 4 != 3) sum += abs((a.rgba[i].toInt() and 255) - (b.rgba[i].toInt() and 255))
        return sum.toDouble() / (a.rgba.size / 4 * 3)
    }

    private fun render(c: DarkroomController) = runBlocking { c.withEngine { it.renderPreview(320) } }

    /** The XMP as saved, read again by the engine: [key]'s parameters. */
    private fun reopened(c: DarkroomController, item: SeriesItem, name: String, key: InstanceKey): JsonObject = runBlocking {
        exportAndKeep(c, item, name)
        val xmp = File(TestFiles.out, "p3e-$name.xmp")
        c.withEngine { e ->
            runCatching { e.close() }
            e.open(item.rawPath, xmp.path)
            try { e.modules().first { it.key == key }.params } finally { runCatching { e.close() } }
        }
    }

    private fun sameNodes(layout: CurveLayout, a: JsonObject, b: JsonObject, ch: Int) {
        val x = layout.read(a, ch)
        val y = layout.read(b, ch)
        assertEquals("node count", x.size, y.size)
        for (i in x.indices) {
            assertEquals("x[$i]", x[i].x, y[i].x, 1e-5)
            assertEquals("y[$i]", x[i].y, y[i].y, 1e-5)
        }
    }

    @Test
    fun p1_toneCurveNodeMoved() = runBlocking<Unit> {
        val (c, item) = open("tonecurve")
        val L = CurveLayout.TONECURVE
        c.toggleExpanded(tone)
        val picture0 = render(c)
        val default = L.read(params(c, tone), 0)
        TestFiles.record(res, "p1_default", "${default.size} nodes ${default}")
        val three = listOf(CurveNode(0.0, 0.0), CurveNode(0.5, 0.5), CurveNode(1.0, 1.0))
        val items0 = items(c)
        // a node added (one step), then moved in several steps (one item)
        c.curveSet(tone, L.writes(0, three, L.countOf(params(c, tone), 0)))
        assertEquals(items0 + 1, items(c))
        for (y in listOf(0.55, 0.62, 0.7, 0.76)) c.curveDrag(tone, L.writes(0, CurveEdit.move(three, 1, 0.5, y), 3))
        c.curveEnd(tone)
        waitFor("moved") { abs(L.read(params(c, tone), 0)[1].y - 0.76) < 1e-6 }
        waitFor("one item") { items(c) == items0 + 2 }
        val moved = L.read(params(c, tone), 0)
        TestFiles.record(res, "p1_moved", "$moved, items ${items(c)}")
        assertTrue(CurveEdit.valid(moved))
        assertEquals(3, moved.size)
        // the picture changed
        val d = difference(picture0, render(c))
        TestFiles.record(res, "p1_difference", String.format(Locale.ROOT, "%.3f", d))
        assertTrue("the picture changes: $d", d > 0.5)
        // the XMP, read again by the engine, holds the same nodes
        val live = params(c, tone)
        val back = reopened(c, item, "tonecurve", tone)
        sameNodes(L, live, back, 0)
        TestFiles.record(res, "p1_passed", "true")
    }

    @Test
    fun p2_rgbCurveFromAnArea() = runBlocking<Unit> {
        val (c, item) = open("rgbcurve")
        val L = CurveLayout.RGBCURVE
        c.toggleExpanded(rgb)
        waitFor("photo on the screen") { c.photoArea != null && c.currentViewport != null }
        val items0 = items(c)
        // the picker button next to the channel tabs ("create curve"): arming picks the area at once
        c.togglePicker(rgb, 7)
        waitFor("armed and picked") { c.state.value.picker?.readout?.isNotEmpty() == true }
        runCatching { waitFor("the nodes from the area", 20_000) { L.read(params(c, rgb), 0).size >= 3 } }
        val m0 = c.state.value.modules.first { it.key == rgb }
        TestFiles.record(res, "p2_diag", "enabled ${m0.enabled}, num ${m0.params["curve_num_nodes"]}, nodes ${m0.params["curve_nodes"].toString().take(300)}, message ${c.state.value.message}")
        Thread.sleep(300)
        val nodes = L.read(params(c, rgb), 0)
        TestFiles.record(res, "p2_nodes", "${nodes.size} nodes $nodes, items $items0 -> ${items(c)}")
        assertTrue("nodes at min, mean and max: ${nodes.size}", nodes.size >= 3)
        assertTrue(CurveEdit.valid(nodes))
        assertEquals("one history item", items0 + 1, items(c))
        c.disarmPicker()
        val live = params(c, rgb)
        val back = reopened(c, item, "rgbcurve", rgb)
        sameNodes(L, live, back, 0)
        TestFiles.record(res, "p2_passed", "true")
    }

    @Test
    fun p3_rgbLevelsBlackHandle() = runBlocking<Unit> {
        val (c, item) = open("rgblevels")
        c.toggleExpanded(levels)
        c.setEnabled(levels, true)
        c.setParam(levels, "autoscale", JsonPrimitive(1L))
        val picture1 = render(c)
        val items0 = items(c)
        val start = LevelsEdit.read(params(c, levels), 1)
        val red0 = LevelsEdit.read(params(c, levels), 0)
        for (x in listOf(0.1, 0.2, 0.3)) c.curveDrag(levels, LevelsEdit.writes(1, LevelsEdit.move(start, 0, x, LevelsEdit.pct(start))))
        c.curveEnd(levels)
        waitFor("moved") { abs(LevelsEdit.read(params(c, levels), 1)[0] - 0.3) < 1e-6 }
        waitFor("one item") { items(c) == items0 + 1 }
        val l = LevelsEdit.read(params(c, levels), 1)
        TestFiles.record(res, "p3_levels", "${l.toList()} (was ${start.toList()})")
        assertTrue(l[0] < l[1] && l[1] < l[2])
        assertEquals("the red channel is untouched", red0.toList(), LevelsEdit.read(params(c, levels), 0).toList())
        val m1 = c.state.value.modules.first { it.key == levels }
        TestFiles.record(res, "p3_diag", "enabled ${m1.enabled}, params ${m1.params.toString().take(400)}")
        val d = difference(picture1, render(c))
        TestFiles.record(res, "p3_difference", String.format(Locale.ROOT, "%.3f", d))
        assertTrue("the picture changes: $d", d > 0.2)
        val back = reopened(c, item, "rgblevels", levels)
        for (k in 0..2) assertEquals("levels[1][$k]", l[k], LevelsEdit.read(back, 1)[k], 1e-5)
        TestFiles.record(res, "p3_passed", "true")
    }

    @Test
    fun p4_toneEqualizerDragOnThePhoto() = runBlocking<Unit> {
        val (c, item) = open("toneeq")
        c.toggleExpanded(eq)
        waitFor("photo on the screen") { c.photoArea != null && c.currentViewport != null }
        waitFor("the editor is wanted") { c.toneEqWanted(c.state.value) == eq }
        val picture0 = render(c)
        val items0 = items(c)
        val g0 = ToneEqMath.read(params(c, eq))
        // a vertical drag up on the photo, a quarter of its height, through the system's touch input
        swipe(onScreen(c, 0.5f, 0.6f), onScreen(c, 0.5f, 0.35f), steps = 16)
        waitFor("a band went up") { ToneEqMath.read(params(c, eq)).max() > 0.1 }
        waitFor("sealed, one item") { items(c) == items0 + 1 }
        val g = ToneEqMath.read(params(c, eq))
        TestFiles.record(res, "p4_bands", "${g.toList()} (was ${g0.toList()}), items $items0 -> ${items(c)}")
        assertTrue("the module is on", c.state.value.modules.first { it.key == eq }.enabled)
        assertTrue("within +-2 EV", g.all { it in -2.0..2.0 })
        val d = difference(picture0, render(c))
        TestFiles.record(res, "p4_difference", String.format(Locale.ROOT, "%.3f", d))
        assertTrue("the picture changes: $d", d > 0.2)
        val back = reopened(c, item, "toneeq", eq)
        val gb = ToneEqMath.read(back)
        for (i in 0 until 9) assertEquals("band $i", g[i], gb[i], 1e-5)
        TestFiles.record(res, "p4_passed", "true")
    }
}

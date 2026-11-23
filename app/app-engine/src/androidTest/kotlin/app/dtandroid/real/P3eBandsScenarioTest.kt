// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.key
import app.dtandroid.darkroom.ui.graph.BandEdit
import app.dtandroid.darkroom.ui.graph.BandModels
import app.dtandroid.darkroom.ui.graph.BandOptions
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief P3e-b on the emulator, with the real engine and the public test RAW (without a sidecar: darktable's default
 * edit). For each of contrast equalizer, colour zones and colour equalizer a node is moved through the band editor's
 * own model and the controller's drag calls (the same calls the touch handler makes): one history item, the picture
 * changed, the XMP written and read again with the same parameters. Each scenario's XMP and the app's export of it are
 * kept as p3e-<name>.xmp / .jpg; the workflow renders the XMP with darktable-cli and compares (size and PSNR).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3eBandsScenarioTest {
    private val res = "p3e-bands-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!runCatching(cond).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; message ${AppHost.controller.state.value.message}")
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

    private fun params(c: DarkroomController, key: InstanceKey): JsonObject = c.state.value.modules.first { it.key == key }.params

    /** the module's picture: the app's export of its XMP (the same call the workflow compares with darktable-cli) */
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
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpg.path, o)
        TestFiles.record(res, "${file}_export", "${o.outWidth}x${o.outHeight}")
        jpg
    }

    /**
     * Move the first node of tab [tab] of [op] up by [dy] as the editor does: a drag with several steps, then the lift.
     * Returns the number of history items the drag added.
     */
    private fun moveNode(c: DarkroomController, key: InstanceKey, tab: Int, dy: Double): Int {
        val before = c.state.value.history.items.size
        val p = params(c, key)
        val view = BandModels.build(key.op, p)!!
        val t = view.tabs[tab]
        val curve = t.curves[0]
        val x = curve.xs[0]
        val y = curve.ys[0]
        val opts = BandOptions(view.defaultRadius, brush = view.brushAlways, tolX = 0.05, tolY = 0.05)
        val drag = BandEdit.begin(t, x, y, opts, inStrip = false, xStrip = view.xStrip)!!
        var last = t
        for (i in 1..6) {
            last = BandEdit.update(drag, x, y + dy * i / 6)
            c.bandDrag(key, last.writesFrom(t))
            Thread.sleep(30)
        }
        c.bandEnd(key, last.writesFrom(t))
        waitFor("one history item for the drag") { c.state.value.history.items.size > before && params(c, key) != p }
        Thread.sleep(300)
        return c.state.value.history.items.size - before
    }

    private fun scenario(name: String, op: String, tab: Int, dy: Double) = runBlocking<Unit> {
        val (c, item) = open(name)
        val key = InstanceKey(op, 0)
        c.chooseTab(key, 0, tab)
        val before = params(c, key)
        val added = moveNode(c, key, tab, dy)
        assertEquals("$op: one history item for one drag", 1, added)
        val after = params(c, key)
        assertNotEquals("$op: the parameters changed", before, after)
        val jpg = export(c, item, "p3e-$name")
        TestFiles.record(res, "${name}_moved", "ok")
        // the XMP read again: the same parameters
        c.start(Series("p3e $name again", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("reopened") { c.state.value.preview != null }
        assertEquals("$op: the XMP reopens identical", after, params(c, key))
        TestFiles.record(res, "${name}_reopen", "identical")
        // the picture differs from the unedited one
        val plain = File(TestFiles.out, "p3e-$name-plain.jpg").apply { delete() }
        val plainDir = File(TestFiles.ctx.filesDir, "p3e-$name-plain").apply { deleteRecursively(); mkdirs() }
        val plainRaw = File(plainDir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it) }
        c.closePhoto()
        c.withEngine { e ->
            runCatching { e.close() }
            e.open(plainRaw.path, null)
            try { e.export(plain.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
        }
        assertTrue("$op: the picture changed", !plain.readBytes().contentEquals(jpg.readBytes()))
        assertFalse(plain.length() == 0L)
    }

    @Test fun a_contrastEqualizerNodeMoved() = scenario("atrous", "atrous", 0, 0.3)
    @Test fun b_colorZonesNodeMoved() = scenario("colorzones", "colorzones", 2, 0.35)
    @Test fun c_colorEqualizerNodeMoved() = scenario("colorequal", "colorequal", 1, 0.3)
}

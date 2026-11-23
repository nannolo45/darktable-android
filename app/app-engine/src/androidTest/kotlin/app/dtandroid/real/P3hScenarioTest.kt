// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.key
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.schema.Control
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Brief P3h on the emulator, with the real engine and the public test RAW, through the darkroom's controller (the
 * code the snapshots panel's buttons call). Results go to files/p2b-test/p3h-results.txt.
 *
 * Take a snapshot, raise exposure by 1.5 EV: the split view's snapshot side shows the picture from before the change
 * and the live side the brighter one (left half of the split darker than the same half of the live picture, right
 * half the live picture), the snapshot's picture equals the engine's render of that history position, and the
 * history end is the one of the edit (the snapshot render did not move it). "before" is rendered the same way.
 */
@RunWith(AndroidJUnit4::class)
class P3hScenarioTest {
    private val res = "p3h-results.txt"
    private val exposure = InstanceKey("exposure", 0)
    private var scenario: ActivityScenario<MainActivity>? = null
    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(10) }
    }

    /** mean of R, G and B over the columns [x0, x1) */
    private fun mean(img: RenderedImage, x0: Int, x1: Int): Double {
        var sum = 0.0
        var n = 0L
        for (y in 0 until img.height) for (x in x0 until x1) {
            val i = 4 * (y * img.width + x)
            sum += (img.rgba[i].toInt() and 0xff) + (img.rgba[i + 1].toInt() and 0xff) + (img.rgba[i + 2].toInt() and 0xff)
            n += 3
        }
        return sum / n
    }

    @Test fun snapshotSplitShowsBothAndKeepsTheHistoryEnd() = runBlocking<Unit> {
        val engine = TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3h").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val xmp = File(dir, raw.name + ".xmp").also { TestFiles.xmp.copyTo(it) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c: DarkroomController = AppHost.controller
        c.start(Series("p3h", listOf(SeriesItem(raw.name, raw.path, xmp.path))), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }

        val end0 = c.state.value.history.end
        c.takeSnapshot()
        val snap = c.state.value.snapshots.selectedSnapshot
        assertNotNull("the snapshot is taken and shown", snap)
        assertEquals(end0, snap!!.historyEnd)
        waitFor("snapshot picture") { c.state.value.snapshots.preview != null }
        val snapPic = c.state.value.snapshots.preview!!.image
        val askedSize = c.state.value.snapshots.previewKey!!.second

        val e = AppHost.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        val ev0 = (c.state.value.modules.first { it.key == exposure }.params["exposure"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
        val oldPreview = c.state.value.preview
        c.setSlider(exposure, e.binding, ev0 + 1.5, false)
        waitFor("live preview after the change") { c.state.value.preview !== oldPreview && c.state.value.preview != null }
        c.renderNow()
        val live = c.state.value.preview!!.image
        val end1 = c.state.value.history.end
        TestFiles.record(res, "history_end", "before $end0, after the change $end1, engine ${engine.history().end}")
        assertEquals("one history item for the change", end0 + 1, end1)
        assertEquals("the engine's history end is the edit's", end1, engine.history().end)
        assertTrue("the snapshot stays the picture taken", c.state.value.snapshots.preview!!.image === snapPic ||
            c.state.value.snapshots.preview!!.image.rgba.contentEquals(snapPic.rgba))

        // the split as the screen draws it: snapshot on the left of the middle, the live edit on the right
        val split = c.state.value.snapshots.split
        assertTrue(split.vertical && split.snapshotFirst)
        assertEquals(live.width, snapPic.width)
        val half = live.width / 2
        val snapLeft = mean(snapPic, 0, half)
        val liveLeft = mean(live, 0, half)
        val liveRight = mean(live, half, live.width)
        val snapRight = mean(snapPic, half, live.width)
        TestFiles.record(res, "split_means", "left (snapshot) %.2f vs live there %.2f; right (live) %.2f vs snapshot there %.2f (0..255)"
            .format(java.util.Locale.ROOT, snapLeft, liveLeft, liveRight, snapRight))
        assertTrue("left half: the snapshot is darker than the live picture", snapLeft + 5 < liveLeft)
        assertTrue("right half: the live picture is brighter than the snapshot", liveRight > snapRight + 5)

        // the snapshot's picture equals a render of that history position, and that render moves nothing
        val ref = engine.renderPreview(askedSize, app.dtandroid.engine.api.RenderOptions.at(end0))
        var maxd = 0
        if (ref.width == snapPic.width && ref.height == snapPic.height)
            for (i in ref.rgba.indices) maxd = maxOf(maxd, kotlin.math.abs((ref.rgba[i].toInt() and 0xff) - (snapPic.rgba[i].toInt() and 0xff)))
        TestFiles.record(res, "snapshot_vs_render_at", "${ref.width}x${ref.height} vs ${snapPic.width}x${snapPic.height}, max difference $maxd")
        assertEquals(snapPic.width, ref.width)
        assertEquals(0, maxd)
        assertEquals(end1, engine.history().end)

        // "before" on the same call
        c.setBefore(true)
        waitFor("before picture") { c.state.value.beforePreview != null }
        assertEquals("before moves no history end", end1, engine.history().end)
        c.setBefore(false)
        TestFiles.record(res, "result", "passed")
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Timeline
import app.dtandroid.lpl.StubLpl
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.net.URLEncoder

/**
 * Brief P2f on the emulator, with the real engine, the public test RAW and the stub LightPhotoLibrary
 * server. Results go to files/p2b-test/p2f-results.txt (the CI script pulls the folder).
 *
 * f1: the black picture after "done" (deliverable 1): a heavy edit (diffuse or sharpen, denoise
 * (profiled), local contrast, sharpen switched on with their defaults, as a user does), "done", and the
 * photo opened again from the list while the 1920 px preview for LightPhotoLibrary renders. The
 * timeline (done → XMP posted → preview render start and end → open requested → first picture → real
 * preview) is recorded first, then checked.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P2fScenarioTest {
    private val res = "p2f-results.txt"
    private val stubs = ArrayList<StubLpl>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun tearDown() {
        scenario?.let { sc -> runCatching { sc.onActivity { it.vm.leaveDarkroom() } }; Thread.sleep(500); sc.close() }
        stubs.forEach { it.close() }
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(10)
        }
    }

    private fun vm(): AppViewModel { var v: AppViewModel? = null; scenario!!.onActivity { v = it.vm }; return v!! }

    private fun openLink(s: StubLpl, id: Long): AppViewModel {
        TestFiles.engine()
        val l = "lpldt://edit?server=${URLEncoder.encode(s.url, "UTF-8")}&ids=$id&start=$id&title=Test%20series"
        scenario = ActivityScenario.launch(Intent(Intent.ACTION_VIEW, Uri.parse(l))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage("app.dtandroid").setClassName(TestFiles.ctx, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val v = vm()
        waitFor("darkroom with the photo open", 300_000) { v.screen.value == Screen.DARKROOM && v.controller.state.value.info != null }
        return v
    }

    /** Modules of the heavy edit, switched on with their defaults. */
    private val heavy = listOf("denoiseprofile", "diffuse", "bilat", "sharpen")

    private fun ms(t0: Long, t: Long?) = t?.let { (it - t0) / 1_000_000 }

    /** Whether the darkroom shows a picture of the photo (a preview, or a stored picture while it opens). */
    private fun pictureShown(v: AppViewModel): Boolean = v.controller.state.value.preview != null

    @Test
    fun f1_openRightAfterDoneTimeline() {
        val s = StubLpl().also { stubs += it }
        s.add(StubLpl.Photo(71, TestFiles.raw.name, TestFiles.raw.readBytes(), rating = 1))
        AppHost.servers.put(s.origin, "Phone", null)
        val v = openLink(s, 71)
        val c = v.controller
        for (op in heavy) {
            val j = c.launch { setEnabled(InstanceKey(op, 0), true) }
            waitFor("$op on") { j.isCompleted && c.state.value.modules.any { it.op == op && it.enabled } }
        }
        waitFor("preview of the heavy edit") { c.state.value.preview != null }
        val record = AppHost.agent.record(s.origin, 71)!!
        File(TestFiles.out, "p2f-heavy-edit.xmp").let { f -> c.launch { writeXmp(f.path) }.let { j -> waitFor("xmp written") { j.isCompleted } } }

        Timeline.clear()
        Timeline.mark("done")
        val tDone = System.nanoTime()
        scenario!!.onActivity { it.vm.leaveDarkroom() }
        waitFor("XMP posted") { s.photos[71]!!.xmpPosts >= 1 }
        val tPosted = System.nanoTime()
        Timeline.mark("XMP posted (seen by the stub)")
        // the owner's case: open the photo again from the list while the LPL preview renders
        waitFor("LPL preview render started", 120_000) { Timeline.first("LPL preview: render start") != null }
        Timeline.mark("open requested")
        val tOpen = System.nanoTime()
        scenario!!.onActivity { it.vm.openLplPhoto(AppHost.agent.record(s.origin, 71)!!) }
        waitFor("first picture", 600_000) { v.screen.value == Screen.DARKROOM && pictureShown(v) }
        val tFirst = System.nanoTime()
        Timeline.mark("first picture on screen (test)")
        waitFor("real preview", 600_000) { Timeline.first("darkroom: preview shown", tOpen) != null }
        val tReal = Timeline.first("darkroom: preview shown", tOpen)!!.nanos
        waitFor("preview posted", 600_000) { s.photos[71]!!.previewPosts >= 1 }
        val tPreviewPosted = System.nanoTime()
        Timeline.mark("preview posted (seen by the stub)")

        val renderStart = Timeline.first("LPL preview: render start")?.nanos
        val renderEnd = Timeline.first("LPL preview: render end")?.nanos
        TestFiles.record(res, "f1_heavy_edit", heavy.joinToString(","))
        TestFiles.record(res, "f1_done_to_xmp_posted_ms", ms(tDone, tPosted)!!)
        TestFiles.record(res, "f1_done_to_lpl_preview_render_start_ms", ms(tDone, renderStart) ?: "none")
        TestFiles.record(res, "f1_done_to_lpl_preview_render_end_ms", ms(tDone, renderEnd) ?: "none")
        TestFiles.record(res, "f1_done_to_open_requested_ms", ms(tDone, tOpen)!!)
        TestFiles.record(res, "f1_open_to_first_picture_ms", ms(tOpen, tFirst)!!)
        TestFiles.record(res, "f1_open_to_real_preview_ms", ms(tOpen, tReal)!!)
        TestFiles.record(res, "f1_done_to_preview_posted_ms", ms(tDone, tPreviewPosted)!!)
        TestFiles.record(res, "f1_lpl_preview_render_ms", if (renderStart != null && renderEnd != null) (renderEnd - renderStart) / 1_000_000 else "none")
        TestFiles.record(res, "f1_list_thumbnail_before_reopen", v.lplThumbs.value.containsKey(record.key))
        File(TestFiles.out, "p2f-f1-timeline.txt").writeText(Timeline.text() + "\n")

        // acceptance (brief P2f): a picture at once, and the real preview without waiting for background work
        val firstMs = ms(tOpen, tFirst)!!
        assertTrue("first picture after $firstMs ms (want under 1000 ms)", firstMs < 1000)
        if (renderEnd != null && renderEnd > tOpen)
            assertTrue("the real preview waited for the LPL preview render", tReal < renderEnd)
        TestFiles.record(res, "f1", "passed")
    }
}

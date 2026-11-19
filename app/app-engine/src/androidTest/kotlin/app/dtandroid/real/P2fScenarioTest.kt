// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Timeline
import app.dtandroid.lpl.StubLpl
import kotlinx.coroutines.async
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

    /**
     * Whether the darkroom shows a picture of the photo: a preview, or (since the P2f fix) the photo's
     * stored last picture while it opens. Before the fix only the preview counted (there was nothing else).
     */
    private fun pictureShown(v: AppViewModel): Boolean = v.controller.state.value.let { it.preview != null || it.placeholder != null }

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

    /**
     * f2: the LightPhotoLibrary preview now comes from the full pipe (cancellable) instead of dtb_export.
     * Both are darktable's export pipe type; this measures how far the two pictures are apart (test RAW and
     * test XMP, the photo's own output profile is sRGB there) and that a cancel stops the new one.
     */
    @Test
    fun f2_previewFromTheFullPipeMatchesTheExport() = kotlinx.coroutines.runBlocking<Unit> {
        val e = TestFiles.engine()
        runCatching { e.close() }
        e.open(TestFiles.raw.path, TestFiles.xmp.path)
        try {
            val jpg = File(TestFiles.out, "p2f-f2-export.jpg").apply { delete() }
            val t0 = System.nanoTime()
            e.export(jpg.path, app.dtandroid.engine.api.ExportOptions(quality = 90, maxWidth = AppHost.PREVIEW_SIZE, maxHeight = AppHost.PREVIEW_SIZE,
                profile = app.dtandroid.engine.api.ExportProfile.SRGB))
            val tExport = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            val img = AppHost.previewPicture(e)
            val tRegion = (System.nanoTime() - t1) / 1_000_000
            val mine = File(TestFiles.out, "p2f-f2-region.jpg").apply { delete() }
            PreviewJpeg.write(img, mine, AppHost.PREVIEW_QUALITY)
            val a = android.graphics.BitmapFactory.decodeFile(jpg.path)
            val b = android.graphics.BitmapFactory.decodeFile(mine.path)
            TestFiles.record(res, "f2_export", "${a.width}x${a.height} ${jpg.length()} bytes $tExport ms")
            TestFiles.record(res, "f2_full_pipe", "${b.width}x${b.height} ${mine.length()} bytes $tRegion ms (pipe cached from the export's input)")
            assertTrue("same size ${a.width}x${a.height} vs ${b.width}x${b.height}", Math.abs(a.width - b.width) <= 1 && Math.abs(a.height - b.height) <= 1)
            val w = minOf(a.width, b.width); val h = minOf(a.height, b.height)
            var se = 0.0
            val pa = IntArray(w); val pb = IntArray(w)
            for (y in 0 until h) {
                a.getPixels(pa, 0, w, 0, y, w, 1); b.getPixels(pb, 0, w, 0, y, w, 1)
                for (x in 0 until w) for (sh in intArrayOf(0, 8, 16)) {
                    val d = ((pa[x] shr sh) and 0xff) - ((pb[x] shr sh) and 0xff); se += d * d
                }
            }
            val mse = se / (w.toDouble() * h * 3)
            val psnr = if (mse == 0.0) Double.POSITIVE_INFINITY else 10 * Math.log10(255.0 * 255.0 / mse)
            TestFiles.record(res, "f2_psnr_db", String.format(java.util.Locale.ROOT, "%.2f", psnr))
            // a cancel from another thread stops the new preview render
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                runCatching { e.cacheDrop() }
                runCatching { AppHost.previewPicture(e) }
            }
            kotlinx.coroutines.delay(30)
            e.renderCancel()
            val r = job.await()
            TestFiles.record(res, "f2_cancel", r.exceptionOrNull()?.message ?: "finished before the cancel")
            assertTrue("PSNR $psnr dB (want >= 40, as the G0 comparison)", psnr >= 40)
            TestFiles.record(res, "f2", "passed")
        } finally {
            runCatching { e.close() }
        }
    }
}

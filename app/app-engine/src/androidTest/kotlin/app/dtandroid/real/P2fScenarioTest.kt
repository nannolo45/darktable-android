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
import org.junit.Assert.assertEquals
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

    /** brief P2f: no test runs for ever; a failure leaves every thread's stack in the results */
    @get:org.junit.Rule val guard = HangGuard.rule(12)
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
        // the first render that finished (one that interactive work cancelled ends "render cancelled")
        val renderEnd = Timeline.first("LPL preview: render end")?.nanos
        val cancelledAt = Timeline.first("LPL preview: render cancelled")?.nanos
        TestFiles.record(res, "f1_heavy_edit", heavy.joinToString(","))
        TestFiles.record(res, "f1_done_to_xmp_posted_ms", ms(tDone, tPosted)!!)
        TestFiles.record(res, "f1_done_to_lpl_preview_render_start_ms", ms(tDone, renderStart) ?: "none")
        TestFiles.record(res, "f1_done_to_lpl_preview_render_end_ms", ms(tDone, renderEnd) ?: "none")
        TestFiles.record(res, "f1_done_to_open_requested_ms", ms(tDone, tOpen)!!)
        TestFiles.record(res, "f1_open_to_first_picture_ms", ms(tOpen, tFirst)!!)
        TestFiles.record(res, "f1_open_to_real_preview_ms", ms(tOpen, tReal)!!)
        TestFiles.record(res, "f1_done_to_preview_posted_ms", ms(tDone, tPreviewPosted)!!)
        TestFiles.record(res, "f1_lpl_preview_cancelled_for_the_open_ms", ms(tDone, cancelledAt) ?: "not cancelled")
        TestFiles.record(res, "f1_done_to_lpl_preview_finished_ms", ms(tDone, renderEnd) ?: "none")
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
     * f10 (brief P2i deliverables 1 to 4): with f1's heavy edit, at fit, a change is followed by the preview in the
     * time the preview costs, and no full-pipe render runs: the darkroom starts no region, the bridge gets no
     * `render_region` call, and darktable's per-module lines of the change show each module once (one pipe). Then
     * zoomed in: the preview stays, "rendering…", the region; back at fit while a region renders after a change:
     * the region render is stopped. Named f10 so that it runs after f1 and before f9 (name order).
     */
    @Test
    fun f10_aChangeAtFitRendersThePreviewOnly() {
        val s = StubLpl().also { stubs += it }
        s.add(StubLpl.Photo(101, TestFiles.raw.name, TestFiles.raw.readBytes(), rating = 1))
        AppHost.servers.put(s.origin, "Phone", null)
        val v = openLink(s, 101)
        val c = v.controller
        val e = TestFiles.engine()
        for (op in heavy) {
            val j = c.launch { setEnabled(InstanceKey(op, 0), true) }
            waitFor("$op on") { j.isCompleted && c.state.value.modules.any { it.op == op && it.enabled } }
        }
        // a phone's darkroom frame (1080 px wide, portrait screen), at fit; the emulator's own frame is small (run
        // 44701978994: 304 x 250, so its preview stayed at 1024 px). The screen sends its size again only when it changes.
        waitFor("the view's size") { c.currentViewport != null }
        TestFiles.record(res, "f10_emulator_frame", c.currentViewport!!.let { "${it.widthPx}x${it.heightPx}" })
        val fit = app.dtandroid.darkroom.Viewport(1080, 1500)
        c.setViewport(fit)
        waitFor("preview of the heavy edit at the frame's size") { c.state.value.preview != null && Math.abs((c.renderTimes.value.previewPx ?: 0) - c.previewSize()) <= 1 && c.state.value.region == null }
        Thread.sleep(1500)
        val info = c.state.value.info!!
        TestFiles.record(res, "f10_view", "${fit.widthPx}x${fit.heightPx}, photo ${info.width}x${info.height}, preview asked ${c.previewSize()} px")
        val exp0 = (c.state.value.modules.first { it.op == "exposure" && it.priority == 0 }.params["exposure"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
        val regions0 = c.regionRenders.get()
        val bridgeRegions0 = e.regionCalls.get()
        val lines = StringBuilder()
        for (i in 1..3) {
            Timeline.clear()
            val mark = bridgeLogMark()
            val t0 = System.nanoTime()
            Timeline.mark("change $i asked")
            val j = c.launch { setParam(InstanceKey("exposure", 0), "exposure", kotlinx.serialization.json.JsonPrimitive(exp0 + 0.1 * i)) }
            waitFor("preview after change $i", 120_000) { j.isCompleted && Timeline.first("darkroom: preview shown") != null }
            val shown = (Timeline.first("darkroom: preview shown")!!.nanos - t0) / 1_000_000
            val previewMs = c.renderTimes.value.previewMs ?: -1.0
            Thread.sleep(2000) // anything that would follow the preview (before P2i: the region at fit) has time to start
            val modules = app.dtandroid.engine.ModuleTimes.parse(bridgeLogSince(mark))
            val twice = modules.groupBy { it.module }.filter { it.value.size > 1 }.keys
            val line = "change $i: preview shown $shown ms after the change (its render ${"%.0f".format(java.util.Locale.ROOT, previewMs)} ms, " +
                "${c.renderTimes.value.previewPx} px); per module: ${app.dtandroid.engine.ModuleTimes.table(modules)}"
            lines.append(line).append('\n')
            TestFiles.record(res, "f10_change_$i", line)
            File(TestFiles.out, "p2i-f10-timeline-$i.txt").writeText(Timeline.text() + "\n")
            assertTrue("change $i: a module ran twice (two pipes?): $twice", twice.isEmpty())
            assertTrue("change $i: no region render: ${Timeline.text()}", Timeline.events().none { it.name.startsWith("darkroom: region render start") })
            // the change's own work (param_set, history, modules(), image_info) plus the preview's render
            assertTrue("change $i: preview shown after $shown ms, its render took $previewMs ms", shown <= previewMs + 1000)
        }
        assertEquals("no region render at fit", regions0, c.regionRenders.get())
        assertEquals("no render_region call in the bridge at fit", bridgeRegions0, e.regionCalls.get())
        assertEquals(null, c.state.value.region)
        File(TestFiles.out, "p2i-f10-changes-at-fit.txt").writeText(lines.toString())

        // zoomed in: the preview stays (scaled up), "rendering…" meanwhile, then the region
        val preview = c.state.value.preview
        Timeline.clear()
        val tz = System.nanoTime()
        c.setViewport(fit.copy(zoom = 3f))
        assertTrue("the preview is kept at once", c.state.value.preview === preview)
        waitFor("rendering… while the region renders", 30_000) { c.state.value.regionPending || c.state.value.region != null }
        val sawPending = c.state.value.regionPending
        waitFor("region when zoomed in", 300_000) { c.state.value.region != null && !c.state.value.regionPending }
        val regionMs = (Timeline.first("darkroom: region shown")!!.nanos - tz) / 1_000_000
        TestFiles.record(res, "f10_zoom_in", "region shown $regionMs ms after the zoom (render ${c.renderTimes.value.regionMs} ms), rendering… seen $sawPending")
        assertTrue(c.state.value.preview === preview)

        // a change while zoomed in, then back to fit while its region renders: the region render is stopped
        Timeline.clear()
        val j = c.launch { setParam(InstanceKey("exposure", 0), "exposure", kotlinx.serialization.json.JsonPrimitive(exp0 + 0.5)) }
        waitFor("region render after the change", 300_000) { j.isCompleted && Timeline.events().any { it.name.startsWith("darkroom: region render start") } }
        Thread.sleep(50)
        val tb = System.nanoTime()
        c.setViewport(fit)
        waitFor("nothing pending at fit", 120_000) { !c.state.value.regionPending }
        val stopped = (System.nanoTime() - tb) / 1_000_000
        val cancelled = Timeline.first("darkroom: region render cancelled") != null
        val finished = Timeline.first("darkroom: region shown") != null
        TestFiles.record(res, "f10_back_to_fit", "region render ${if (cancelled) "cancelled" else if (finished) "finished first" else "?"}, nothing pending $stopped ms after the zoom out")
        File(TestFiles.out, "p2i-f10-zoom-timeline.txt").writeText(Timeline.text() + "\n")
        assertEquals("no region at fit", null, c.state.value.region)
        assertTrue("the region render was cancelled or had ended: ${Timeline.text()}", cancelled || finished)
        TestFiles.record(res, "f10", "passed")
    }

    /**
     * f2: the LightPhotoLibrary preview now comes from the full pipe (cancellable) instead of dtb_export.
     * Both are darktable's export pipe type; this measures how far the two pictures are apart (test RAW and
     * test XMP, the photo's own output profile is sRGB there) and that a cancel stops the new one.
     */
    @Test
    fun f2_previewFromTheFullPipeMatchesTheExport() = kotlinx.coroutines.runBlocking<Unit> {
        TestFiles.engine()
        // the engine has one session: run as the darkroom's own work, so nothing of the app's (a background
        // thumbnail, a photo closed by the previous test) can close or replace this test's session meanwhile
        AppHost.controller.closePhoto()
        AppHost.controller.withEngine { e ->
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

    /**
     * f3 (deliverable 4, the addendum): how the drag picture looks on a 1080 px wide screen. The test RAW is
     * rendered as drag pictures of 320, 512 and 768 px and as the 1024 px preview, each scaled to 1080 px wide
     * the way the screen scales it (bilinear); the PNGs go to the results for the owner to compare, with the
     * render time of each size on the emulator (not a phone).
     */
    @Test
    fun f3_dragPictureSizesOnA1080PxScreen() = kotlinx.coroutines.runBlocking<Unit> {
        TestFiles.engine()
        // the engine has one session: run as the darkroom's own work, so nothing of the app's (a background
        // thumbnail, a photo closed by the previous test) can close or replace this test's session meanwhile
        AppHost.controller.closePhoto()
        AppHost.controller.withEngine { e ->
        runCatching { e.close() }
        e.open(TestFiles.raw.path, TestFiles.xmp.path)
        try {
            e.renderPreview(1024)
            val exp0 = (e.modules().first { it.op == "exposure" }.params["exposure"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
            var k = 0
            for (size in listOf(320, 512, 768, 1024)) {
                e.paramSet("exposure", 0, "exposure", app.dtandroid.engine.api.ParamValue.of(exp0 + 0.01 * (++k)))
                val t0 = System.nanoTime()
                val img = e.renderPreview(size)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val bmp = android.graphics.Bitmap.createBitmap(img.width, img.height, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(img.rgba))
                val h = Math.round(1080.0 * img.height / img.width).toInt()
                val big = android.graphics.Bitmap.createScaledBitmap(bmp, 1080, h, true)
                File(TestFiles.out, "p2f-drag-$size-on-1080.png").outputStream().use { big.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                TestFiles.record(res, "f3_drag_${size}", "${img.width}x${img.height} rendered in $ms ms (emulator), shown at 1080x$h")
            }
            e.historySeal()
            TestFiles.record(res, "f3", "passed")
        } finally {
            runCatching { e.close() }
        }
        }
    }

    /**
     * f4 (deliverable 4, the reviewer's addition): the timings run on a photo from LightPhotoLibrary, both from
     * inside the darkroom ("timings…" keeps the photo open) and from the list after the darkroom was left (the
     * last opened photo, not the working folder); the text names the source, the edit and the per-module times.
     */
    @Test
    fun f4_timingsRunOnAPhotoFromLightPhotoLibrary() {
        val s = StubLpl().also { stubs += it }
        s.add(StubLpl.Photo(81, TestFiles.raw.name, TestFiles.raw.readBytes(), rating = 1))
        AppHost.servers.put(s.origin, "Phone", null)
        val v = openLink(s, 81)
        // the heavy edit of f1, so the per-module tables show where its time goes (deliverable 4)
        val c = v.controller
        for (op in heavy) {
            val j = c.launch { setEnabled(InstanceKey(op, 0), true) }
            waitFor("$op on") { j.isCompleted && c.state.value.modules.any { it.op == op && it.enabled } }
        }
        // from inside the darkroom: the menu's "timings…" only switches the screen
        scenario!!.onActivity { it.vm.openTimings() }
        assertTrue("the photo stays open", v.controller.state.value.info != null)
        scenario!!.onActivity { it.vm.runTimings(1080, 1500, "test") }
        waitFor("timings text (open photo)", 600_000) { v.timings.value.text != null }
        val t1 = v.timings.value.text!!
        File(TestFiles.out, "p2f-f4-timings-open.txt").writeText(t1)
        assertTrue(t1, t1.contains("photo source: LightPhotoLibrary"))
        assertTrue(t1, t1.contains("history items"))
        assertTrue(t1, t1.contains("modules on ("))
        assertTrue(t1, t1.contains("pinning: "))
        assertTrue(t1, t1.contains("per module, first preview, every module (ms, slowest first): sum "))
        // from the list, after leaving the darkroom: the last opened photo (no file-picker photos needed)
        scenario!!.onActivity { it.vm.leaveDarkroom() }
        waitFor("darkroom closed") { v.controller.state.value.info == null }
        scenario!!.onActivity { it.vm.show(Screen.TIMINGS); it.vm.runTimings(1080, 1500, "test") }
        waitFor("timings text (last opened)", 600_000) { v.timings.value.text?.let { it != t1 } == true && v.timings.value.running == null }
        val t2 = v.timings.value.text!!
        File(TestFiles.out, "p2f-f4-timings-last.txt").writeText(t2)
        assertTrue(t2, t2.contains("photo source: LightPhotoLibrary"))
        assertTrue(t2, t2.contains("per module, sharp region at fit after an exposure change"))
        // brief P2i: the timings page calls the full pipe itself: the fit and 100 % region numbers are still there,
        // and the preview at the darkroom's fit size (what a change at fit costs since P2i)
        for (t in listOf(t1, t2)) {
            assertTrue(t, Regex("sharp region at fit \\d+x\\d+ after a change ms: [0-9.]+").containsMatchIn(t))
            assertTrue(t, Regex("sharp region at 100 % \\d+x\\d+ after a change ms: [0-9.]+").containsMatchIn(t))
            assertTrue(t, t.contains("per module, preview at the darkroom's fit size after an exposure change"))
            assertTrue(t, Regex("preview at the darkroom's fit size \\(asked \\d+ px, got \\d+x\\d+[^)]*\\) after an exposure change ms: [0-9.]+").containsMatchIn(t))
        }
        TestFiles.record(res, "f4", "passed")
    }

    /**
     * f5 (deliverable 8): spektrafilm. Its data pack is in the app (About says so); the public test case
     * (darktable-tests 0179-spektrafilm) exports to spektrafilm.jpg, which the workflow compares with
     * darktable-cli given the same pack. In the darkroom the search line finds the module, and the default
     * layout lists it in the group desktop puts it in.
     */
    @Test
    fun f5_spektrafilmHasItsDataAndIsFound() = kotlinx.coroutines.runBlocking<Unit> {
        val (raw, xmp) = TestFiles.spektrafilm ?: run { TestFiles.record(res, "f5", "skipped: no spektrafilm case given"); return@runBlocking }
        val e = TestFiles.engine()
        val pack = SpektrafilmPack.state
        TestFiles.record(res, "f5_pack", "${pack.text}, ${pack.bytes} bytes")
        assertTrue(pack.text, pack.id != null)
        assertTrue(File(SpektrafilmPack.dir(TestFiles.ctx), "LICENSE").isFile)
        AppHost.controller.closePhoto()
        AppHost.controller.withEngine { e ->
        runCatching { e.close() }
        e.open(raw.path, xmp.path)
        try {
            val sf = e.modules().filter { it.op == "spektrafilm" }
            TestFiles.record(res, "f5_module", sf.joinToString { "enabled=${it.enabled} hidden=${it.hidden}" })
            assertTrue("spektrafilm is on in the test edit", sf.any { it.enabled })
            val jpg = File(TestFiles.out, "spektrafilm.jpg").apply { delete() }
            val t0 = System.nanoTime()
            e.export(jpg.path, app.dtandroid.engine.api.ExportOptions(quality = 95, profile = app.dtandroid.engine.api.ExportProfile.IMAGE))
            TestFiles.record(res, "f5_export_ms_emulator", (System.nanoTime() - t0) / 1_000_000)
            assertTrue(jpg.length() > 0)
        } finally {
            runCatching { e.close() }
        }
        }
        // where it appears in the darkroom
        val c = AppHost.controller
        c.start(app.dtandroid.darkroom.Series("spektrafilm", listOf(app.dtandroid.darkroom.SeriesItem(raw.name, raw.path, xmp.path))), 0, filmstrip = false, thumbnails = false)
        try {
            c.setSearch("spektra")
            assertTrue("the search line finds it", c.visibleModules(c.state.value).any { it.op == "spektrafilm" })
            c.setSearch("film simulation")
            assertTrue("and by its aliases", c.visibleModules(c.state.value).any { it.op == "spektrafilm" })
            c.setSearch("")
            val groups = c.state.value.layout.groups
            val gi = groups.indexOfFirst { "spektrafilm" in it.modules }
            TestFiles.record(res, "f5_layout", "${c.state.value.layoutName}: group ${if (gi >= 0) "\"${groups[gi].name}\" (${gi + 1} of ${groups.size})" else "none"}")
            // as desktop (src/libs/modulegroups.c): the default workflow's layout lists it in no group; the search finds it
            assertTrue("as desktop, no group of the default layout lists spektrafilm", gi < 0)
        } finally {
            c.closePhoto()
        }
        TestFiles.record(res, "f5", "passed")
    }

    /**
     * f6 (deliverable 10): crop's aspect list on the real engine. "square" chosen in the darkroom crops the
     * picture square; the flip and 3:2 follow; the XMP written and opened again keeps ratio and frame. The XMP
     * is kept (p2f-crop.xmp) and the workflow renders it with darktable-cli to check desktop reads the same crop.
     */
    @Test
    fun f6_cropAspectOnTheRealEngine() = kotlinx.coroutines.runBlocking<Unit> {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p2f-crop").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = app.dtandroid.darkroom.SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        val c = AppHost.controller
        c.start(app.dtandroid.darkroom.Series("crop", listOf(item)), 0, filmstrip = false, thumbnails = false)
        try {
            val key = InstanceKey("crop", 0)
            val spec = AppHost.schema.modules.getValue("crop").aspect!!
            val w0 = c.state.value.info!!.width; val h0 = c.state.value.info!!.height
            c.chooseAspect(key, spec.entries.indexOfFirst { it.label.startsWith("square") })
            val i1 = c.state.value.info!!
            TestFiles.record(res, "f6_square", "${w0}x$h0 -> ${i1.width}x${i1.height}")
            assertTrue("square: ${i1.width}x${i1.height}", Math.abs(i1.width - i1.height) <= 2)
            c.chooseAspect(key, spec.entries.indexOfFirst { it.label.startsWith("3:2") })
            c.flipAspect(key)
            val i2 = c.state.value.info!!
            TestFiles.record(res, "f6_3to2_flipped", "${i2.width}x${i2.height}")
            assertTrue("portrait 2:3: ${i2.width}x${i2.height}", Math.abs(i2.width * 3.0 / (i2.height * 2.0) - 1.0) < 0.01)
            c.save()
            File(item.xmpPath).copyTo(File(TestFiles.out, "p2f-crop.xmp"), overwrite = true)
            c.closePhoto()
            c.openPhoto(0)
            val m = c.state.value.modules.first { it.op == "crop" }
            fun p(n: String) = (m.params[n] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
            TestFiles.record(res, "f6_reopened", "ratio_d ${p("ratio_d")} ratio_n ${p("ratio_n")} ${c.state.value.info!!.width}x${c.state.value.info!!.height}")
            assertEquals(-3.0, p("ratio_d"), 0.0); assertEquals(2.0, p("ratio_n"), 0.0)
            assertEquals("3:2, 4x6, 35mm  1.50", app.dtandroid.schema.CropAspect.label(spec, p("ratio_d").toInt(), p("ratio_n").toInt()))
            assertEquals(i2.width, c.state.value.info!!.width); assertEquals(i2.height, c.state.value.info!!.height)
        } finally {
            c.closePhoto()
        }
        TestFiles.record(res, "f6", "passed")
    }

    /**
     * A file of junk bytes darktable cannot decode, as l1's placeholder `OTHER_0023.CR2` (2000 bytes), under a
     * name of its own per test so no earlier test has met it (brief P2h).
     */
    private fun junkFile(name: String): File {
        val dir = File(TestFiles.ctx.filesDir, "p2h").apply { mkdirs() }
        return File(dir, name).apply { delete(); writeBytes(ByteArray(2000) { it.toByte() }) }
    }

    private fun bridgeLogMark(): Long = LongArray(1).also { app.dtandroid.engine.NativeBridge.logSince(Long.MAX_VALUE, it) }[0]

    private fun bridgeLogSince(mark: Long): String {
        Thread.sleep(300) // the log is read by a thread of its own
        return app.dtandroid.engine.NativeBridge.logSince(mark, LongArray(1)) ?: ""
    }

    /**
     * f7 (brief P2h deliverable 1): what `open` and `image_info` answer for the junk file on the real engine,
     * recorded with the engine's log lines in between (p2h-f7-engine-log.txt). No render. Since brief P1b (E1) the
     * answer is asserted: `open` refuses the file (IO, "cannot decode", engine-api.md 5.1) and leaves no session.
     */
    @Test
    fun f7_imageInfoOfAnUndecodableFile() = kotlinx.coroutines.runBlocking<Unit> {
        TestFiles.engine()
        val junk = junkFile("JUNK_F7.CR2")
        AppHost.controller.closePhoto()
        val mark = bridgeLogMark()
        val answer = AppHost.controller.withEngine { e ->
            runCatching { e.close() }
            val t0 = System.nanoTime()
            val open = try { e.open(junk.path, null); "OK" } catch (ex: app.dtandroid.engine.api.EngineException) { "${ex.error}: ${ex.message}" }
            val tOpen = (System.nanoTime() - t0) / 1_000_000
            if (open != "OK") return@withEngine "open answered $open ($tOpen ms); image_info not asked"
            try {
                val t1 = System.nanoTime()
                val info = try {
                    e.imageInfo().let { "ImageInfo ${it.width}x${it.height}, raw ${it.rawWidth}x${it.rawHeight}, class ${it.imageClass}" }
                } catch (ex: app.dtandroid.engine.api.EngineException) { "error ${ex.error} (${ex.error.code}): ${ex.message}" }
                "open answered OK ($tOpen ms); image_info answered $info (${(System.nanoTime() - t1) / 1_000_000} ms)"
            } finally {
                runCatching { e.close() }
            }
        }
        val log = bridgeLogSince(mark)
        File(TestFiles.out, "p2h-f7-engine-log.txt").writeText("$answer\n\n$log\n")
        TestFiles.record(res, "f7_junk_file", "${junk.name}, ${junk.length()} bytes")
        TestFiles.record(res, "f7_answer", answer)
        android.util.Log.i("dt-android-test", "P2h f7: $answer")
        log.lines().filter { it.isNotBlank() }.take(40).forEach { android.util.Log.i("dt-android-test", "P2h f7 engine log: $it") }
        // brief P1b (E1): since the engine refuses such files at open, the truth is "open answered IO, cannot
        // decode" (engine-api.md 5.1); before P1b open answered OK and image_info ENGINE (P2h report)
        assertTrue(answer, answer.startsWith("open answered IO: IO: ${app.dtandroid.engine.api.EngineException.UNDECODABLE_PREFIX}"))
        val after = AppHost.controller.withEngine { e ->
            try { e.imageInfo(); "a session is open" } catch (ex: app.dtandroid.engine.api.EngineException) { ex.error.name }
        }
        assertEquals("no session left open by the refused open", "STATE", after)
        TestFiles.record(res, "f7", "done")
    }

    /**
     * f8 (brief P2h deliverables 2 and 4): the junk file opened in the darkroom gives the sentence at once and
     * closes its session (no hang, no render); the LightPhotoLibrary preview of the good photo is made right
     * after, on a session of its own (before P2h the junk file's session could stay open and refuse it); the
     * junk file's own preview is refused without opening it again; then the good photo opens in the darkroom
     * with its preview.
     */
    @Test
    fun f8_undecodableFileInTheDarkroomThenTheGoodPhoto() = kotlinx.coroutines.runBlocking<Unit> {
        TestFiles.engine()
        val junk = junkFile("JUNK_F8.CR2")
        val dir = File(TestFiles.ctx.filesDir, "p2h-good").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val xmp = File(dir, raw.name + ".xmp").also { TestFiles.xmp.copyTo(it) }
        val items = listOf(
            app.dtandroid.darkroom.SeriesItem(junk.name, junk.path, junk.path + ".xmp"),
            app.dtandroid.darkroom.SeriesItem(raw.name, raw.path, xmp.path),
        )
        val c = AppHost.controller
        c.closePhoto()
        Timeline.clear()
        val t0 = System.nanoTime()
        c.start(app.dtandroid.darkroom.Series("p2h", items), 0, filmstrip = true, thumbnails = false)
        val tOpen = (System.nanoTime() - t0) / 1_000_000
        try {
            val st = c.state.value
            TestFiles.record(res, "f8_junk_message", "${st.message} ($tOpen ms)")
            assertEquals(null, st.info)
            assertEquals("cannot open ${junk.name}: ${app.dtandroid.darkroom.DarkroomController.NO_PICTURE}", st.message)
            assertEquals("the session is closed", null, c.openRawPath())
            assertTrue("remembered", c.knownUnreadable(junk.path))
            // brief P1b (E1): the engine's open refused it (before P1b: open OK, then image_info failed)
            assertTrue(Timeline.events().any { it.name.startsWith("darkroom: ${junk.name} has no picture (open answered") })

            // the LightPhotoLibrary preview of the good photo, on a session of its own, right away
            val out = File(TestFiles.out, "p2h-f8-lpl-preview.jpg").apply { delete() }
            val t1 = System.nanoTime()
            assertTrue("LPL preview of the good photo", AppHost.Editor.renderPreview(raw, xmp, out))
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(out.path, o)
            TestFiles.record(res, "f8_lpl_preview_good", "${o.outWidth}x${o.outHeight} ${out.length()} bytes ${(System.nanoTime() - t1) / 1_000_000} ms (emulator)")
            assertEquals(AppHost.PREVIEW_SIZE, maxOf(o.outWidth, o.outHeight))

            // the junk file's LPL preview: refused without a session
            val junkOut = File(TestFiles.out, "p2h-f8-junk-preview.jpg").apply { delete() }
            assertEquals(false, AppHost.Editor.renderPreview(junk, File(junk.path + ".xmp"), junkOut))
            assertTrue(Timeline.first("darkroom: ${junk.name} known to have no picture, no session opened") != null)

            // the good photo in the darkroom: its preview is shown
            c.openPhoto(1)
            waitFor("preview of the good photo", 300_000) { c.state.value.preview != null && c.state.value.index == 1 }
            assertEquals(raw.path, c.openRawPath())
            TestFiles.record(res, "f8_good_photo_preview", c.state.value.preview!!.image.let { "${it.width}x${it.height}" })

            // the junk file once more from the filmstrip: brief P2i: a photo the user opens is always tried (the
            // memory is for background passes only): opened again, the sentence, the session closed
            c.openPhoto(0)
            assertEquals("cannot open ${junk.name}: ${app.dtandroid.darkroom.DarkroomController.NO_PICTURE}", c.state.value.message)
            assertTrue(Timeline.first("darkroom: ${junk.name} remembered as having no picture, tried again (the user opened it)") != null)
            assertEquals("the session is closed", null, c.openRawPath())
            val noPicture = Timeline.events().filter { it.name.startsWith("darkroom: ${junk.name} has no picture") }
            assertEquals("found by the first open, and again by the user's second open", 2, noPicture.size)
            assertTrue("still remembered for background passes", c.knownUnreadable(junk.path))
            TestFiles.record(res, "f8_no_picture_mark", noPicture.first().name)
        } finally {
            File(TestFiles.out, "p2h-f8-timeline.txt").writeText(Timeline.text() + "\n")
            c.closePhoto()
        }
        TestFiles.record(res, "f8", "passed")
    }

    /**
     * f9 (the reviewer's question on the heap corruption of run 44616800258): over every test of this run, no
     * two bridge calls overlapped (dtbridge.h: one engine thread), and every cancel passed to the bridge came
     * while a render ran. Runs last (name order) so it covers the whole process.
     */
    @Test
    fun f9_theBridgeWasCalledAsItsHeaderAllows() {
        val e = TestFiles.engine()
        TestFiles.record(res, "f9_bridge_overlaps", e.bridgeOverlaps)
        TestFiles.record(res, "f9_cancels_outside_a_render_not_passed_on", e.cancelsOutsideRender)
        assertEquals("bridge calls that overlapped", 0L, e.bridgeOverlaps)
        TestFiles.record(res, "f9", "passed")
    }
}

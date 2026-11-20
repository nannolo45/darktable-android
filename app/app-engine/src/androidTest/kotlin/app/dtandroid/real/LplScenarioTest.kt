// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.lpl.PhotoStatus
import app.dtandroid.lpl.StubLpl
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.net.URLEncoder

/**
 * Brief P2e item 9, third point: the app with the real engine against the stub LPL server (written for
 * the tests, `app/lpl` test fixtures) on the emulator, driven through the activity and its view model
 * the way the screens drive them. The public test RAW (instrumentation argument `raw`) is the photo.
 * Results go to files/p2b-test/lpl-results.txt, which the CI script pulls.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LplScenarioTest {
    private val res = "lpl-results.txt"
    private val stubs = ArrayList<StubLpl>()

    /** brief P2f: no test runs for ever; a failure leaves every thread's stack in the results */
    @get:org.junit.Rule val guard = HangGuard.rule(10)
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun stub(): StubLpl = StubLpl().also { stubs += it }

    @After fun tearDown() {
        scenario?.let { sc -> runCatching { sc.onActivity { it.vm.leaveDarkroom() } }; Thread.sleep(500); sc.close() }
        stubs.forEach { it.close() }
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    private fun link(s: StubLpl, ids: List<Long>, start: Long) =
        "lpldt://edit?server=${URLEncoder.encode(s.url, "UTF-8")}&ids=${ids.joinToString(",")}&start=$start&title=Test%20series"

    /** The intent as the LightPhotoLibrary app (P2c `handOffIntent`) and browsers send it. */
    private fun handOffIntent(l: String) = Intent(Intent.ACTION_VIEW, Uri.parse(l))
        .addCategory(Intent.CATEGORY_BROWSABLE).setPackage("app.dtandroid").setClassName(TestFiles.ctx, MainActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun vm(): AppViewModel { var v: AppViewModel? = null; scenario!!.onActivity { v = it.vm }; return v!! }

    private fun rawPhoto(s: StubLpl, id: Long, name: String = TestFiles.raw.name) = s.add(StubLpl.Photo(id, name, TestFiles.raw.readBytes(), rating = 1))

    /** Open the link, wait for the darkroom with the photo open. */
    private fun openLink(l: String): AppViewModel {
        TestFiles.engine()
        scenario = ActivityScenario.launch(handOffIntent(l))
        val v = vm()
        waitFor("darkroom with the photo open", 300_000) { v.screen.value == Screen.DARKROOM && v.controller.state.value.info != null }
        return v
    }

    private fun change(v: AppViewModel, ev: Double) {
        val c = v.controller
        val j = c.launch { setParam(InstanceKey("exposure", 0), "exposure", JsonPrimitive(ev)) }
        waitFor("change applied") { j.isCompleted }
        // the engine has the new value (the history may grow, or darktable may fold the change into its top item)
        fun value() = (c.state.value.modules.firstOrNull { it.op == "exposure" }?.params?.get("exposure") as? JsonPrimitive)?.content?.toDoubleOrNull()
        try {
            waitFor("exposure is $ev", 30_000) { value()?.let { Math.abs(it - ev) < 1e-6 } == true }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; value ${value()}, darkroom message: ${c.state.value.message}, history ${c.state.value.history.end}/${c.state.value.history.items.size}")
        }
    }

    @Test
    fun l1_linkDownloadDarkroomChangeDoneSync() {
        val s = stub()
        rawPhoto(s, 21)
        s.add(StubLpl.Photo(22, "OTHER_0022.CR2", ByteArray(1000) { it.toByte() }))
        s.add(StubLpl.Photo(23, "OTHER_0023.CR2", ByteArray(2000) { it.toByte() }))
        AppHost.servers.put(s.origin, "Phone", null)
        val md5 = TestFiles.md5(TestFiles.raw)
        val v = openLink(link(s, listOf(22, 21, 23), 21))
        val st = v.controller.state.value
        // LPL's order, the start photo open, the others as placeholders with their names
        assertEquals(1, st.index)
        waitFor("placeholder names") { v.controller.state.value.series.items.map { it.fileName } == listOf("OTHER_0022.CR2", TestFiles.raw.name, "OTHER_0023.CR2") }
        assertTrue(st.filmstrip)
        // the photo after the open one is fetched ahead of time; the one before is not fetched at all
        waitFor("next photo fetched ahead") { s.seen("GET", "/api/photos/23/original").isNotEmpty() }
        assertTrue("photo 22 not downloaded", s.seen("GET", "/api/photos/22/original").isEmpty())
        val r0 = AppHost.agent.record(s.origin, 21)!!
        assertEquals(PhotoStatus.EDITING, r0.status)
        TestFiles.record(res, "l1_downloaded_bytes", File(AppHost.work.raw(r0).path).length())
        change(v, 1.3)
        val t0 = System.nanoTime()
        scenario!!.onActivity { it.vm.leaveDarkroom() }   // "done"
        assertEquals(Screen.LIBRARY, v.screen.value)
        TestFiles.record(res, "l1_done_to_list_ms", (System.nanoTime() - t0) / 1_000_000)
        waitFor("XMP and preview at LPL") { s.photos[21]!!.xmpPosts == 1 && s.photos[21]!!.previewPosts == 1 }
        TestFiles.record(res, "l1_done_to_synced_ms", (System.nanoTime() - t0) / 1_000_000)
        val posted = s.photos[21]!!.xmp!!.decodeToString()
        assertTrue("the edit is in the posted XMP", posted.contains("darktable:operation=\"exposure\""))
        val order = s.seen.filter { it.method == "POST" && it.path.startsWith("/api/photos/21/") }.map { it.path.substringBefore('?') }
        assertEquals(listOf("/api/photos/21/xmp", "/api/photos/21/preview"), order)
        assertEquals(LplClientTs.of(r0.checkoutTs), s.photos[21]!!.lastCheckoutTs)
        val pv = s.photos[21]!!.preview!!
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pv, 0, pv.size, o)
        TestFiles.record(res, "l1_preview", "${o.outWidth}x${o.outHeight} ${pv.size} bytes")
        assertEquals(1920, maxOf(o.outWidth, o.outHeight))
        waitFor("synced") { AppHost.agent.record(s.origin, 21)!!.status == PhotoStatus.SYNCED }
        // the RAW was never sent and never changed
        assertTrue(s.seen.none { it.method == "POST" && it.bodyBytes >= TestFiles.raw.length() })
        assertEquals(md5, TestFiles.md5(AppHost.work.raw(AppHost.agent.record(s.origin, 21)!!)))
        // the heartbeat reports the device and its working set
        waitFor("heartbeat with the working set") { s.heartbeats.values.any { it.contains("\"id\":21") && it.contains("\"status\":\"synced\"") } }
        val hb = s.heartbeats.values.last()
        assertTrue(hb, hb.contains("\"os\":\"android\"") && hb.contains("\"name\":\"Phone\"") && hb.contains("darktable"))
        TestFiles.record(res, "l1", "passed")
    }

    @Test
    fun l2_offlineEditIsDirtyThenTheBackgroundJobSyncsIt() {
        val s = stub()
        rawPhoto(s, 31)
        AppHost.servers.put(s.origin, "Phone", null)
        val v = openLink(link(s, listOf(31), 31))
        s.stop()                                   // the server is gone (no connection)
        change(v, -0.4)
        scenario!!.onActivity { it.vm.leaveDarkroom() }
        waitFor("dirty with a plain reason") {
            AppHost.agent.record(s.origin, 31)!!.let { it.status == PhotoStatus.DIRTY && it.error == "LightPhotoLibrary is not reachable right now" }
        }
        TestFiles.record(res, "l2_offline_status", AppHost.agent.record(s.origin, 31)!!.status.word)
        s.start()                                  // back on the same port
        // run the background job now, as Android would when a network is there
        val out = TestFiles.shell("cmd jobscheduler run -f ${TestFiles.ctx.packageName} 4201")
        TestFiles.record(res, "l2_jobscheduler", out.trim().replace('\n', ' '))
        waitFor("synced by the background job") { s.photos[31]!!.xmpPosts == 1 && s.photos[31]!!.previewPosts == 1 }
        waitFor("status synced") { AppHost.agent.record(s.origin, 31)!!.status == PhotoStatus.SYNCED }
        TestFiles.record(res, "l2", "passed")
    }

    @Test
    fun l3_unknownServerIsRefusedUntilAdded() {
        val s = stub()
        s.password = "pw-for-the-emulator-test"
        rawPhoto(s, 41)
        TestFiles.engine()
        scenario = ActivityScenario.launch(handOffIntent(link(s, listOf(41), 41)))
        val v = vm()
        waitFor("hand-off screen asks about the server") { v.handOff.value is HandOffUi.UnknownServer }
        Thread.sleep(3000)
        assertEquals("nothing was sent to a server the user has not added", 0, s.seen.size)
        // the user taps "add this server", enters the password and a name, and saves
        scenario!!.onActivity { it.vm.showServerForm((v.handOff.value as HandOffUi.UnknownServer).handOff) }
        assertEquals(s.url, v.serverForm.value.address)
        scenario!!.onActivity { it.vm.editServerForm(v.serverForm.value.copy(password = "wrong", deviceName = "Phone")); it.vm.saveServer() }
        waitFor("wrong password reported") { v.serverForm.value.error == "wrong password" }
        scenario!!.onActivity { it.vm.editServerForm(v.serverForm.value.copy(password = "pw-for-the-emulator-test")); it.vm.saveServer() }
        waitFor("darkroom after adding", 300_000) { v.screen.value == Screen.DARKROOM && v.controller.state.value.info != null }
        assertEquals("pw-for-the-emulator-test", s.seen.last { it.path.startsWith("/api/photos/41/") }.token)
        val stored = File(TestFiles.ctx.filesDir, "lpl/servers.json").readText()
        assertFalse("the password is not stored in plain text", stored.contains("pw-for-the-emulator-test"))
        assertTrue(stored.contains("\"device_name\":\"Phone\""))
        TestFiles.record(res, "l3", "passed")
    }

    @Test
    fun l4_aJobOfEachKind() {
        val s = stub()
        rawPhoto(s, 51); rawPhoto(s, 52, "SECOND.cr2"); rawPhoto(s, 53, "THIRD.cr2")
        AppHost.servers.put(s.origin, "Phone", null)
        val id = AppHost.servers.installId
        TestFiles.engine()
        kotlinx.coroutines.runBlocking { AppHost.agent.take(s.origin, 51) }
        val r51 = AppHost.agent.record(s.origin, 51)!!
        // an edit LPL does not have yet (as the darkroom would write it)
        val x = AppHost.work.xmp(r51)
        x.writeText(x.readText().replace("</rdf:RDF>", "<!-- edited --></rdf:RDF>"))
        val sync = s.queueJob(id, "sync", listOf(51))
        val release = s.queueJob(id, "release", listOf(51))
        val open = s.queueJob(id, "open", listOf(52))
        val edit = s.queueJob(id, "edit", listOf(53))
        val pc = s.queueJob(null, "edit", listOf(53))          // unaddressed: for a PC, never taken by the phone
        // brief P2i: the timeline of thumbnail renders and stores and of the removal, kept with the results (P2h's
        // failure of "released: gone from the phone": a thumbnail stored after the removal made the folder again)
        app.dtandroid.darkroom.Timeline.clear()
        try {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val v = vm()
        waitFor("all four jobs reported", 300_000) { listOf(sync, release, open, edit).all { it.status == "done" || it.status == "failed" } }
        TestFiles.record(res, "l4_job_states", listOf(sync, release, open, edit).joinToString { "${it.kind}=${it.status}" })
        assertEquals("done", sync.status)
        assertEquals("done", release.status)
        assertEquals("done", open.status)
        assertEquals("done", edit.status)
        assertEquals("queued", pc.status)
        // the edit reached LPL: sent when the app came to the foreground (it was waiting) and again by the
        // sync job, which always sends (as the PC agent does)
        assertTrue(s.photos[51]!!.xmpPosts >= 1)
        assertTrue(s.photos[51]!!.xmp!!.decodeToString().contains("<!-- edited -->"))
        TestFiles.record(res, "l4_xmp_posts_51", s.photos[51]!!.xmpPosts)
        assertTrue("released: gone from the phone", AppHost.agent.record(s.origin, 51) == null && !AppHost.work.folder(s.origin, 51).exists())
        // open: the darkroom shows 52; edit (arriving while editing): 53 fetched into the list
        assertEquals("SECOND.cr2", v.controller.state.value.current?.fileName)
        assertNotNull(AppHost.agent.record(s.origin, 53))
        // brief P2i: run 44704862471 showed the background thumbnail pass starting to render photo 51 after the
        // release job had removed it (darktable opened the deleted file). When it does, wait for that pass to
        // finish and check again: its thumbnail must not bring the folder back (before P2i it did)
        val tl = app.dtandroid.darkroom.Timeline
        val removedAt = tl.events().firstOrNull { it.name.startsWith("lpl: 51/") && it.name.contains("removed from the phone") }?.nanos
        val lateRender = removedAt != null && tl.events().any { it.nanos > removedAt && it.name.startsWith("thumbnail pass: 51/") && it.name.endsWith("render start") }
        if (lateRender) {
            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end && tl.events().none { it.nanos > removedAt!! && it.name.startsWith("thumbnail pass: 51/") && it.name.endsWith(" store") }) Thread.sleep(100)
            Thread.sleep(500)
        }
        TestFiles.record(res, "l4_late_thumbnail_render_of_51", lateRender)
        assertTrue("released: still gone after the background work", AppHost.agent.record(s.origin, 51) == null && !AppHost.work.folder(s.origin, 51).exists())
        TestFiles.record(res, "l4", "passed")
        } finally {
            val t = app.dtandroid.darkroom.Timeline.text()
            File(TestFiles.out, "p2i-l4-timeline.txt").writeText(t + "\n")
            val ev = app.dtandroid.darkroom.Timeline.events().map { it.name }
            TestFiles.record(res, "l4_thumbnail_renders", ev.count { it.startsWith("thumbnail pass:") && it.endsWith("render start") })
            TestFiles.record(res, "l4_writes_dropped_after_removal", ev.count { it.contains("not written: the photo is no longer on the phone") })
            TestFiles.record(res, "l4_removal_and_thumbnail_lines", ev.filter { it.startsWith("lpl:") || it.startsWith("thumbnail pass:") }.joinToString(" | "))
            // photo 51's own lines (file names repeat across the photos of earlier tests; its folder is named 51)
            TestFiles.record(res, "l4_photo_51", app.dtandroid.darkroom.Timeline.text().lines().filter { "51/" in it }.joinToString(" | "))
        }
    }

    /**
     * l7 (brief P2i deliverable 7, second point): a thumbnail or last picture stored through the app's store after a
     * `release` job removed the photo does not bring its folder back (the removal wins). Before P2i the store wrote
     * the file with `AtomicFiles.write`, which makes missing folders, so this test failed.
     */
    @Test
    fun l7_aLateThumbnailDoesNotBringAReleasedPhotoBack() = kotlinx.coroutines.runBlocking<Unit> {
        val s = stub()
        rawPhoto(s, 57)
        AppHost.servers.put(s.origin, "Phone", null)
        TestFiles.engine()
        AppHost.agent.take(s.origin, 57)
        val item = AppHost.itemFor(s.origin, 57)
        val dir = AppHost.work.folder(s.origin, 57)
        val img = app.dtandroid.engine.api.RenderedImage(4, 4, ByteArray(64) { 0x7f })
        AppHost.thumbs.store(item, img)
        assertTrue("stored while held", File(dir, "thumb.rgba").isFile)
        val release = s.queueJob(AppHost.servers.installId, "release", listOf(57))
        for ((o, j) in AppHost.agent.heartbeat().filter { it.first == s.origin }) AppHost.agent.jobDone(o, j, AppHost.agent.runSyncOrRelease(o, j))
        assertEquals("done", release.status)
        assertFalse("removed", dir.exists())
        // the background thumbnail pass and a closing darkroom finishing late
        AppHost.thumbs.store(item, img)
        AppHost.thumbs.storePicture(item, img)
        assertFalse("released: gone from the phone", dir.exists())
        assertTrue(AppHost.agent.record(s.origin, 57) == null)
        TestFiles.record(res, "l7", "passed")
    }

    @Test
    fun l5_readOnlyPhotoIsNeverPostedBack() {
        val s = stub()
        val p = rawPhoto(s, 61)
        val newer = File(TestFiles.ctx.cacheDir, "newer.xmp")
        TestFiles.writeXmpWithUnknownModule(newer)
        p.xmp = newer.readBytes()
        AppHost.servers.put(s.origin, "Phone", null)
        val v = openLink(link(s, listOf(61), 61))
        assertTrue(v.controller.state.value.readOnly)
        assertTrue(AppHost.agent.record(s.origin, 61)!!.readOnly)
        val before = AppHost.work.xmp(AppHost.agent.record(s.origin, 61)!!).readBytes()
        val j = v.controller.launch { setParam(InstanceKey("exposure", 0), "exposure", JsonPrimitive(1.0)) }
        waitFor("refused change done") { j.isCompleted }
        scenario!!.onActivity { it.vm.leaveDarkroom() }
        scenario!!.onActivity { it.vm.syncNow(AppHost.agent.record(s.origin, 61)!!) }
        TestFiles.shell("cmd jobscheduler run -f ${TestFiles.ctx.packageName} 4201")
        Thread.sleep(8000)
        assertTrue(s.seen.none { it.method == "POST" && it.path.startsWith("/api/photos/61/") })
        assertTrue("the XMP was not rewritten", before.contentEquals(AppHost.work.xmp(AppHost.agent.record(s.origin, 61)!!).readBytes()))
        TestFiles.record(res, "l5", "passed")
    }

    @Test
    fun l6_theShellsHandOffIntentReachesThisApp() {
        // the exact intent the LightPhotoLibrary app builds (P2c: VIEW, BROWSABLE, the lpldt data, package app.dtandroid,
        // NEW_TASK, no component) resolves to this activity
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("lpldt://edit?server=http%3A%2F%2F192.0.2.10%3A8080&ids=1&start=1"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage("app.dtandroid").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val found = TestFiles.ctx.packageManager.queryIntentActivities(i, 0)
        assertEquals(1, found.size)
        assertEquals(MainActivity::class.java.name, found[0].activityInfo.name)
        // and a hostile link is refused with a plain reason
        TestFiles.engine()
        scenario = ActivityScenario.launch(handOffIntent("lpldt://edit?server=http%3A%2F%2Fuser%3Apw%40192.0.2.10&ids=1"))
        val v = vm()
        waitFor("refused") { v.handOff.value is HandOffUi.Invalid }
        TestFiles.record(res, "l6", "passed")
    }
}

/** `checkout_ts` as the client sends it. */
private object LplClientTs {
    fun of(ts: Double) = app.dtandroid.lpl.LplClient.formatTs(ts)
}

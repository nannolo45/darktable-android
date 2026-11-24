// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.job.JobScheduler
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.lpl.StubLpl
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch

/**
 * Brief P3k on the emulator (API 33 x86_64, the public test RAW, the LPL client tests' stub server):
 * s1: edit a photo, "done", the activity stopped at once: XMP and preview arrive, the sync service was seen, then gone;
 * s2: a two-photo batch download started, then the activity stopped: both photos arrive (service seen);
 * s3: "sync automatically" off: no service, nothing sent;
 * s4: server unreachable: the service stops handing over, the background job is scheduled.
 * In an instrumented test the process is never killed, so these show the service's starts, stops and work, not that
 * Android would have stopped the process without it. Results go to files/p2b-test/p3k-results.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3kScenarioTest {
    private val res = "p3k-results.txt"
    private val stubs = ArrayList<StubLpl>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    /**
     * Each scenario starts clean: the photos earlier scenario classes left on the phone (some with edits for stub servers
     * that are gone, which the service would try and hand over) are removed, and no sync service runs.
     */
    @Before fun clean() {
        runBlocking {
            runCatching { AppHost.controller.closePhoto() }
            for (r in AppHost.agent.photos.value) runCatching { AppHost.agent.remove(r.server, r.id, evenUnsynced = true) }
        }
        waitFor("no sync service left from before", 120_000) { !SyncService.running }
        SyncService.titles.clear()
        TestFiles.record(res, "before", "photos left ${AppHost.agent.photos.value.size}, queued exports ${AppHost.agent.queuedExports()}, work ${AppHost.syncWork()}")
    }

    @After fun tearDown() {
        runCatching { AppHost.agent.settings.setAutoSync(true) }
        scenario?.let { sc ->
            runCatching { sc.moveToState(Lifecycle.State.RESUMED) }
            runCatching { sc.onActivity { it.vm.leaveDarkroom() } }
            Thread.sleep(500); sc.close()
        }
        stubs.forEach { it.close() }
        // the service of one scenario must not be counted in the next
        runCatching { waitFor("service gone after the test", 120_000) { !SyncService.running } }
    }

    private fun stub(): StubLpl = StubLpl().also { stubs += it }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(20)
        }
    }

    private fun link(s: StubLpl, id: Long) = "lpldt://edit?server=${URLEncoder.encode(s.url, "UTF-8")}&ids=$id&start=$id&title=P3k"

    private fun openLink(s: StubLpl, id: Long): AppViewModel {
        TestFiles.engine()
        scenario = ActivityScenario.launch(Intent(Intent.ACTION_VIEW, Uri.parse(link(s, id))).addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage("app.dtandroid").setClassName(TestFiles.ctx, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var v: AppViewModel? = null
        scenario!!.onActivity { v = it.vm }
        waitFor("darkroom with the photo open", 300_000) { v!!.screen.value == Screen.DARKROOM && v!!.controller.state.value.info != null }
        return v!!
    }

    private fun change(v: AppViewModel, ev: Double) {
        val c = v.controller
        val j = c.launch { setParam(InstanceKey("exposure", 0), "exposure", JsonPrimitive(ev)) }
        waitFor("change applied") { j.isCompleted }
        waitFor("exposure is $ev", 30_000) {
            (c.state.value.modules.firstOrNull { it.op == "exposure" }?.params?.get("exposure") as? JsonPrimitive)?.content?.toDoubleOrNull()
                ?.let { Math.abs(it - ev) < 1e-6 } == true
        }
    }

    private fun rawPhoto(s: StubLpl, id: Long, name: String = TestFiles.raw.name) = s.add(StubLpl.Photo(id, name, TestFiles.raw.readBytes(), rating = 1))

    /** "done", then the activity stopped right away (the app leaves the screen) */
    private fun doneAndLeave() {
        scenario!!.onActivity { it.vm.leaveDarkroom() }
        scenario!!.moveToState(Lifecycle.State.CREATED)
    }

    @Test
    fun s1_doneThenBackgroundSendsXmpAndPreview() {
        val s = stub()
        rawPhoto(s, 81)
        AppHost.servers.put(s.origin, "Phone", null)
        val v = openLink(s, 81)
        change(v, 0.7)
        val before = SyncService.created.get()
        var seen = false
        val t0 = System.nanoTime()
        doneAndLeave()
        waitFor("XMP and preview at LPL", 300_000) {
            if (SyncService.running) seen = true
            s.photos[81]!!.xmpPosts == 1 && s.photos[81]!!.previewPosts == 1
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        waitFor("service gone", 120_000) { if (SyncService.running) seen = true; !SyncService.running }
        val gone = (System.nanoTime() - t0) / 1_000_000
        TestFiles.record(res, "s1_end", "synced after $ms ms; service created ${SyncService.created.get() - before}x, seen running: $seen, " +
            "gone after $gone ms, stop: ${SyncService.lastStop}; titles ${SyncService.titles}")
        assertTrue("the posted XMP has the edit", s.photos[81]!!.xmp!!.decodeToString().contains("darktable:operation=\"exposure\""))
        assertTrue("the sync service was seen running after done", seen)
        assertEquals("nothing left: the service stopped by itself", "STOP_DONE", SyncService.lastStop)
        TestFiles.record(res, "s1", "passed")
    }

    @Test
    fun s2_batchDownloadGoesOnInTheBackground() {
        val s = stub()
        s.batch = true
        rawPhoto(s, 91); rawPhoto(s, 92, "P3K_SECOND.cr2")
        // the first original stops after 64 KiB until the activity is stopped
        val gate = CountDownLatch(1)
        s.stallPhoto = 91; s.stallAfter = 65_536; s.stallGate = gate
        AppHost.servers.put(s.origin, "Phone", null)
        TestFiles.engine()
        val before = SyncService.created.get()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // the job is queued once the app's own heartbeat runs, so the foreground takes it (not a background heartbeat)
        waitFor("the app's heartbeat", 60_000) { AppHost.inForeground }
        val job = s.queueJob(AppHost.servers.installId, "edit", listOf(91, 92), open = false)
        waitFor("the batch is copying", 120_000) { s.seen("GET", "/api/photos/91/original").isNotEmpty() }
        var seen = SyncService.running
        scenario!!.moveToState(Lifecycle.State.CREATED)
        gate.countDown()
        waitFor("the batch job reported", 300_000) {
            if (SyncService.running) seen = true
            job.status == "done" || job.status == "failed" || job.status == "stopped"
        }
        waitFor("service gone", 120_000) { if (SyncService.running) seen = true; !SyncService.running }
        TestFiles.record(res, "s2_end", "job ${job.status} ${job.doneBody}; service created ${SyncService.created.get() - before}x, seen running: $seen, " +
            "stop: ${SyncService.lastStop}; titles ${SyncService.titles}")
        assertEquals("done", job.status)
        assertTrue(job.doneBody!!.contains("\"arrived\":[91,92]"))
        assertTrue("the sync service was seen running", seen)
        assertEquals("STOP_DONE", SyncService.lastStop)
        TestFiles.record(res, "s2", "passed")
    }

    @Test
    fun s3_automaticSyncOffNoServiceNothingSent() {
        val s = stub()
        rawPhoto(s, 101)
        AppHost.servers.put(s.origin, "Phone", null)
        val v = openLink(s, 101)
        AppHost.agent.settings.setAutoSync(false)
        change(v, -0.3)
        val before = SyncService.created.get()
        val w = AppHost.syncWork()
        doneAndLeave()
        var seen = false
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) { if (SyncService.running) seen = true; Thread.sleep(20) }
        TestFiles.record(res, "s3_end", "work at done: $w; service created ${SyncService.created.get() - before}x, seen running: $seen; " +
            "xmp posts ${s.photos[101]!!.xmpPosts}, preview posts ${s.photos[101]!!.previewPosts}")
        assertEquals(0, s.photos[101]!!.xmpPosts)
        assertEquals(0, s.photos[101]!!.previewPosts)
        assertEquals("no sync service", before, SyncService.created.get())
        assertTrue("no sync service seen", !seen)
        TestFiles.record(res, "s3", "passed")
    }

    @Test
    fun s4_unreachableServerStopsTheServiceAndSchedulesTheJob() {
        val s = stub()
        rawPhoto(s, 111)
        AppHost.servers.put(s.origin, "Phone", null)
        val v = openLink(s, 111)
        change(v, 0.4)
        val js = TestFiles.ctx.getSystemService(JobScheduler::class.java)
        js.cancel(4201)
        s.stop()   // the server is gone (no connection)
        val before = SyncService.created.get()
        var seen = false
        val t0 = System.nanoTime()
        doneAndLeave()
        waitFor("service started", 30_000) { SyncService.running }
        waitFor("service gone", 180_000) { if (SyncService.running) seen = true; !SyncService.running }
        val gone = (System.nanoTime() - t0) / 1_000_000
        val pending = js.getPendingJob(4201)
        TestFiles.record(res, "s4_end", "service seen running: $seen, gone after $gone ms, stop: ${SyncService.lastStop}; job 4201 pending: ${pending != null}; " +
            "xmp posts ${s.photos[111]!!.xmpPosts}")
        assertEquals("STOP_HANDOVER", SyncService.lastStop)
        assertTrue("the background job is scheduled", pending != null)
        assertEquals(0, s.photos[111]!!.xmpPosts)
        TestFiles.record(res, "s4", "passed")
    }
}

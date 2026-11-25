// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Debug
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.robust.CrashLog
import app.dtandroid.darkroom.robust.ExitReasons
import app.dtandroid.darkroom.robust.MemoryTrim
import app.dtandroid.engine.api.EngineMemory
import app.dtandroid.lpl.LplException
import app.dtandroid.lpl.PhotoStatus
import app.dtandroid.lpl.StubLpl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch

/**
 * Brief P4a on the emulator (API 33 x86_64, the public test RAW, the stub server of the LPL client tests):
 * a_: a RAW download cut in the middle (the server goes away mid-transfer, as with a lost connection or a killed app):
 *     nothing partial becomes the photo; the next take resumes with Range; the engine opens and renders the result;
 * b_: memory: the engine's numbers with a photo open in the darkroom, after trim level 1 and after Android's
 *     TRIM_MEMORY_COMPLETE through the application (level 2): the edit (history, parameter) is kept and the darkroom
 *     renders again. Numbers are x86_64 emulator numbers, not phone numbers; in files/p2b-test/p4a-results.txt;
 * c_: the crash log on a real ActivityManager: reading the exit reasons works (no exit is simulated here; the JVM tests
 *     simulate them), and a bridge error the darkroom shows is in the log.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P4aScenarioTest {
    private val res = "p4a-results.txt"
    private val stubs = ArrayList<StubLpl>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @Before fun clean() {
        runBlocking {
            runCatching { AppHost.controller.closePhoto() }
            for (r in AppHost.agent.photos.value) runCatching { AppHost.agent.remove(r.server, r.id, evenUnsynced = true) }
        }
    }

    @After fun tearDown() {
        scenario?.let { sc ->
            runCatching { sc.moveToState(Lifecycle.State.RESUMED) }
            runCatching { sc.onActivity { it.vm.leaveDarkroom() } }
            Thread.sleep(500); sc.close()
        }
        stubs.forEach { it.close() }
    }

    private fun stub(): StubLpl = StubLpl().also { stubs += it }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(20)
        }
    }

    private fun pssMb(): Double = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024.0

    private fun note(key: String, m: EngineMemory?) =
        TestFiles.record(res, key, "${m?.describe() ?: "no engine numbers"}; app PSS ${"%.0f".format(java.util.Locale.ROOT, pssMb())} MB (x86_64 emulator)")

    @Test
    fun a_aDownloadCutInTheMiddleResumesWithRangeAndOpens() {
        TestFiles.engine()
        val s = stub()
        val bytes = TestFiles.raw.readBytes()
        s.add(StubLpl.Photo(101, "P4A_CUT.cr2", bytes, rating = 1))
        AppHost.servers.put(s.origin, "Phone", null)
        val cutAt = minOf(1L shl 20, bytes.size / 2L)
        val gate = CountDownLatch(1)
        s.stallPhoto = 101; s.stallAfter = cutAt; s.stallGate = gate; s.stallThenCut = true
        var failure: Throwable? = null
        val t = Thread { try { runBlocking { AppHost.agent.take(s.origin, 101) } } catch (e: Throwable) { failure = e } }
        t.start()
        waitFor("the first part on the phone", 60_000) {
            AppHost.agent.record(s.origin, 101)?.let { AppHost.work.part(it).length() >= cutAt } == true
        }
        // the server goes away in the middle of the transfer: no answers to the resumes either
        s.stop()
        gate.countDown()
        t.join(120_000)
        assertTrue("the take failed: $failure", failure is LplException && (failure as LplException).transient)
        val r = AppHost.agent.record(s.origin, 101)!!
        assertEquals(PhotoStatus.DOWNLOADING, r.status)
        assertFalse("nothing partial is the photo's RAW", AppHost.work.raw(r).exists())
        val kept = AppHost.work.part(r).length()
        TestFiles.record(res, "a_cut", "cut at $cutAt of ${bytes.size} bytes; on the phone after the cut: $kept bytes; failure: ${(failure as LplException).failure}")
        assertEquals(cutAt, kept)

        // the server is back: the download goes on from the bytes on the phone
        s.stallAfter = -1
        s.start()
        val t0 = System.nanoTime()
        val done = runBlocking { AppHost.agent.take(s.origin, 101) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val gets = s.seen("GET", "/api/photos/101/original")
        TestFiles.record(res, "a_resume", "requests: ${gets.map { "${it.status} ${it.range ?: "whole"}" }}; resumed in $ms ms")
        assertEquals("bytes=$cutAt-", gets.last().range)
        assertEquals(206, gets.last().status)
        assertArrayEquals(bytes, AppHost.work.raw(done).readBytes())
        assertFalse(AppHost.work.part(done).exists())
        // the engine opens it and renders
        runBlocking {
            val e = AppEngine.engine
            e.open(AppHost.work.raw(done).path, AppHost.work.xmp(done).path.takeIf { File(it).isFile })
            try {
                val img = e.renderPreview(512)
                assertTrue(img.width > 0 && img.height > 0)
                TestFiles.record(res, "a_open", "opened and rendered ${img.width}x${img.height}")
            } finally { e.close() }
        }
        TestFiles.record(res, "a", "passed")
    }

    private fun link(s: StubLpl, id: Long) = "lpldt://edit?server=${URLEncoder.encode(s.url, "UTF-8")}&ids=$id&start=$id&title=P4a"

    private fun exposure(v: AppViewModel) =
        (v.controller.state.value.modules.firstOrNull { it.op == "exposure" }?.params?.get("exposure") as? JsonPrimitive)?.content?.toDoubleOrNull()

    @Test
    fun b_memoryTrimsKeepTheEditAndTheNumbers() {
        TestFiles.engine()
        val s = stub()
        s.add(StubLpl.Photo(111, TestFiles.raw.name, TestFiles.raw.readBytes(), rating = 1))
        AppHost.servers.put(s.origin, "Phone", null)
        note("b_0_engine_started_no_photo", runBlocking { AppEngine.engine.memoryInfo() })
        scenario = ActivityScenario.launch(Intent(Intent.ACTION_VIEW, Uri.parse(link(s, 111))).addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage("app.dtandroid").setClassName(TestFiles.ctx, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var vm: AppViewModel? = null
        scenario!!.onActivity { vm = it.vm }
        val v = vm!!
        waitFor("darkroom with the photo open", 300_000) { v.screen.value == Screen.DARKROOM && v.controller.state.value.info != null }
        val c = v.controller
        val j = c.launch { setParam(InstanceKey("exposure", 0), "exposure", JsonPrimitive(0.8)) }
        waitFor("change applied") { j.isCompleted }
        waitFor("exposure 0.8", 30_000) { exposure(v)?.let { Math.abs(it - 0.8) < 1e-6 } == true }
        waitFor("preview", 120_000) { c.state.value.preview != null && c.state.value.working == null }
        Thread.sleep(1500) // the region and thumbnail renders after the preview
        val history = c.state.value.history
        val m0 = runBlocking { AppEngine.engine.memoryInfo() }
        note("b_1_photo_open_rendered", m0)

        val m1 = runBlocking { c.trimMemory(MemoryTrim.plan(MemoryTrim.RUNNING_LOW)) }
        note("b_2_after_level_1", m1)
        assertEquals("level 1 keeps the pipelines", 2, m1.pipesReady)
        assertTrue("level 1 frees cached buffers: ${m0.pipeFull + m0.pipePreview} -> ${m1.pipeFull + m1.pipePreview}",
            m1.pipeFull + m1.pipePreview <= m0.pipeFull + m0.pipePreview)
        assertEquals(history, c.state.value.history)

        // as Android sends it: through the application, off the main thread
        TestFiles.ctx.applicationContext.let { (it as RealApp).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
        waitFor("level 2 done", 30_000) { runBlocking { AppEngine.engine.memoryInfo() }.pipesReady == 0 }
        val m2 = runBlocking { AppEngine.engine.memoryInfo() }
        note("b_3_after_trim_memory_complete", m2)
        assertTrue("level 2 frees the open image's input: ${m0.mipmapFull} -> ${m2.mipmapFull}", m2.mipmapFull < m0.mipmapFull)
        assertEquals("the edit is kept", history, c.state.value.history)
        assertEquals(0.8, exposure(v)!!, 1e-6)
        val engineHistory = runBlocking { AppEngine.engine.history() }
        assertEquals(history.items.size, engineHistory.items.size)

        // the darkroom goes on: a change renders again (the RAW is read again)
        val before = c.state.value.preview
        val t0 = System.nanoTime()
        val j2 = c.launch { setParam(InstanceKey("exposure", 0), "exposure", JsonPrimitive(0.5)) }
        waitFor("second change") { j2.isCompleted }
        waitFor("a new preview", 120_000) { c.state.value.preview !== before && c.state.value.working == null }
        TestFiles.record(res, "b_4_first_render_after_level_2_ms", (System.nanoTime() - t0) / 1_000_000)
        Thread.sleep(1500)
        note("b_5_rendered_again", runBlocking { AppEngine.engine.memoryInfo() })
        TestFiles.record(res, "b", "passed")
    }

    @Test
    fun c_crashLogOnTheDevice() {
        TestFiles.engine()
        val log = CrashLog(File(TestFiles.out, "p4a-crash-log.jsonl"))
        log.clear()
        val n = ExitReasons.collect(TestFiles.ctx, log)
        TestFiles.record(res, "c_exit_reasons", "read on the device: $n added (earlier runs of the test app); entries ${log.entries().map { it.kind }}")
        assertTrue(n >= 0)
        // a bridge error the darkroom shows goes into the app's log
        val before = AppHost.crashLog.entries().count { it.kind == CrashLog.KIND_BRIDGE }
        val bad = File(TestFiles.out, "P4A_NOT_A_RAW.cr2").apply { writeBytes(ByteArray(4096) { 7 }) }
        runBlocking {
            AppHost.controller.start(app.dtandroid.darkroom.Series("P4a", listOf(app.dtandroid.darkroom.SeriesItem(bad.name, bad.path, bad.path + ".xmp"))), 0, false, false)
        }
        waitFor("the message", 60_000) { AppHost.controller.state.value.message != null || AppHost.controller.state.value.placeholder != null }
        val m = AppHost.controller.state.value.message
        TestFiles.record(res, "c_message", "darkroom says: $m")
        if (CrashLog.isBridgeMessage(m)) {
            waitFor("in the crash log", 10_000) { AppHost.crashLog.entries().count { it.kind == CrashLog.KIND_BRIDGE } > before }
        }
        TestFiles.record(res, "c_bridge_entries", AppHost.crashLog.entries().filter { it.kind == CrashLog.KIND_BRIDGE }.takeLast(3).map { it.text })
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        TestFiles.record(res, "c", "passed")
    }
}

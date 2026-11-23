// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.ExportDelivery
import app.dtandroid.darkroom.ExportPhase
import app.dtandroid.darkroom.ExportScope
import app.dtandroid.darkroom.ExportSettings
import app.dtandroid.darkroom.ExportTarget
import app.dtandroid.darkroom.FolderExportDelivery
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.lpl.StubLpl
import kotlinx.coroutines.runBlocking
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
 * Brief P3i on the emulator (API 33 x86_64, the public test RAW):
 * i1: a two-photo series exported with the activity stopped (the app in the background): both files present, the
 *     export foreground service was running during it, the darkroom's photo is open again after it.
 * i2: an export to LightPhotoLibrary against the stub server (R18): the server received the file with the photo's id and
 *     version 0, and a JPEG.
 * Results go to files/p2b-test/p3i-results.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3iScenarioTest {
    private val res = "p3i-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null
    private var appDelivery: ExportDelivery? = null
    private var stub: StubLpl? = null

    @get:org.junit.Rule val guard = HangGuard.rule(15)

    @After fun tearDown() {
        val c = AppHost.controller
        c.exports.cancel()
        runCatching { waitFor("export stopped", 120_000) { c.exports.job.value?.running != true } }
        c.exports.dismiss()
        appDelivery?.let { c.exports.delivery = it }
        c.exports.set { ExportSettings() }
        runBlocking { runCatching { c.closePhoto() } }
        runCatching { c.setOverlay(null) }
        scenario?.close()
        stub?.close()
    }

    private fun waitFor(what: String, ms: Long = 300_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(5)
        }
    }

    private fun serviceRunning(): Boolean {
        val am = TestFiles.ctx.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return am.getRunningServices(100).any { it.service.className == ExportService::class.java.name }
    }

    @Test
    fun i1_seriesWithTheAppInTheBackground() = runBlocking<Unit> {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3i-series").apply { deleteRecursively(); mkdirs() }
        val items = (1..2).map { k ->
            val raw = File(dir, "p3i_$k." + TestFiles.raw.extension).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
            val xmp = File(dir, raw.name + ".xmp").also { TestFiles.xmp.copyTo(it) }
            SeriesItem(raw.name, raw.path, xmp.path)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        appDelivery = c.exports.delivery
        c.start(Series("p3i", items), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        val out = File(TestFiles.ctx.cacheDir, "p3i-series-out").apply { deleteRecursively() }
        c.exports.delivery = FolderExportDelivery(File(TestFiles.ctx.cacheDir, "p3i-scratch"), out)
        c.exports.set { it.copy(scope = ExportScope.SERIES) }
        assertTrue(c.exports.start())
        // the app goes to the background: the activity is stopped while the series runs
        scenario!!.moveToState(Lifecycle.State.CREATED)
        var sawService = false
        waitFor("series done", 600_000) { if (serviceRunning()) sawService = true; c.exports.job.value?.running == false }
        val end = c.exports.job.value!!
        val files = out.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        TestFiles.record(res, "i1_end", "${end.phase} finished ${end.finished} of ${end.total}; files $files; foreground service seen running: $sawService; ${end.message}")
        assertEquals(end.message, ExportPhase.DONE, end.phase)
        assertEquals(2, files.size)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitFor("photo 1 open again", 120_000) { c.state.value.index == 0 && c.state.value.info != null }
        TestFiles.record(res, "i1", "passed")
    }

    @Test
    fun i2_exportToLightPhotoLibrary() {
        TestFiles.engine()
        val s = StubLpl().also { it.r18 = true; stub = it }
        s.add(StubLpl.Photo(21, TestFiles.raw.name, TestFiles.raw.readBytes(), rating = 1))
        AppHost.servers.put(s.origin, "Phone", null)
        val link = "lpldt://edit?server=${URLEncoder.encode(s.url, "UTF-8")}&ids=21&start=21&title=Test"
        scenario = ActivityScenario.launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage("app.dtandroid").setClassName(TestFiles.ctx, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val c = AppHost.controller
        waitFor("darkroom with the photo open", 300_000) { c.state.value.info != null }
        // the heartbeat's answer says the server receives exports
        waitFor("server_caps with exports", 60_000) { AppHost.agent.receivesExports(s.origin) == true }
        c.exports.dismiss()
        c.exports.set { it.copy(target = ExportTarget.LPL) }
        assertTrue(c.exports.delivery!!.lplOffer(c.state.value.current) is app.dtandroid.darkroom.LplOffer.Available)
        assertTrue(c.exports.start())
        waitFor("export done", 300_000) { c.exports.job.value?.running == false }
        val end = c.exports.job.value!!
        val e = s.photos[21]!!.exports
        TestFiles.record(res, "i2_end", "${end.phase}; ${end.message}; server has ${e.size} export(s): " +
            e.joinToString { "${it.name} v${it.version} ${it.bytes.size} bytes agent ${it.agent}" })
        assertEquals(end.message, ExportPhase.DONE, end.phase)
        assertEquals(1, e.size)
        assertEquals(0, e[0].version)
        assertTrue("not a JPEG", e[0].bytes[0] == 0xFF.toByte() && e[0].bytes[1] == 0xD8.toByte())
        TestFiles.record(res, "i2", "passed")
    }
}

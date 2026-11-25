// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.robust.CrashLog
import app.dtandroid.darkroom.speed.ProcessingResources
import app.dtandroid.darkroom.speed.TestRaw
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Locale

/**
 * Brief P4b on the emulator (API 33 x86_64, the public test RAW mire1.cr2; x86_64 emulator numbers, not phone numbers;
 * results in files/p2b-test/p4b-results.txt):
 * a_: the engine started with the memory plan chosen for the emulator's memory: darktable reports the values;
 * b_: switching photos in a series (two copies of the RAW): times, decodes and memory; going back is not decoded
 *     again while the plan keeps two or more decoded photos; the pictures are the same;
 * c_: "use the GPU" switched on through the processing screen's action: the emulator has no OpenCL GPU, so it goes
 *     back off with the reason (in the settings and the crash log); renders afterwards run on the CPU;
 * d_: "measure speed" through the screen's action, on the CPU: the text has every row; the times are recorded.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P4bScenarioTest {
    private val res = "p4b-results.txt"

    @get:org.junit.Rule val guard = HangGuard.rule(30)

    @Before fun clean() {
        TestFiles.engine()
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        // the benchmark's photo is the test RAW the CI script pushed (the same file: darktable-tests' mire1.cr2)
        val bench = File(TestFiles.ctx.filesDir, "benchmark/${TestRaw.NAME}")
        if (!bench.isFile || bench.length() != TestRaw.SIZE) {
            bench.parentFile!!.mkdirs()
            TestFiles.raw.copyTo(bench, overwrite = true)
        }
        assertEquals("the CI test RAW is the benchmark's photo", TestRaw.SIZE, bench.length())
    }

    private fun waitFor(what: String, ms: Long = 600_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    private fun pssMb(): Double = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024.0

    @Test
    fun a_theEngineStartsWithTheMemoryPlan() = runBlocking<Unit> {
        val plan = Processing.plan
        assertNotNull("a plan was chosen at the start", plan)
        val r = AppEngine.engine.resourcesInfo()
        TestFiles.record(res, "a_plan", plan!!.describe())
        TestFiles.record(res, "a_resources", "${r.describe()}; worker threads ${r.workerThreads} (x86_64 emulator)")
        assertEquals(plan.options.resourceLevel, r.level)
        assertEquals(plan.options.resourceValues, r.values)
        assertEquals(plan.options.inputsKept.toLong(), r.inputsKept)
        val frac = plan.options.resourceValues!!.split(' ')[0].toLong()
        assertEquals(maxOf(512L shl 20, r.totalMemory / 1024 * frac), r.availableMemory)
        val cl = AppEngine.engine.openclInfo()
        TestFiles.record(res, "a_opencl_at_start", cl.toString())
        assertTrue("OpenCL is built in and allowed", cl.built && cl.allowed)
        assertFalse("but not started while the setting is off", cl.started)
    }

    @Test
    fun b_switchingPhotosInASeries() = runBlocking<Unit> {
        val dir = File(TestFiles.ctx.cacheDir, "p4b-series").apply { mkdirs() }
        val a = File(dir, "P4B_A.cr2"); val b = File(dir, "P4B_B.cr2")
        TestFiles.raw.copyTo(a, overwrite = true); TestFiles.raw.copyTo(b, overwrite = true)
        val kept = Processing.plan!!.options.inputsKept
        AppHost.controller.withEngine { e ->
            fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
            var t0 = System.nanoTime()
            e.open(a.path, null)
            val pa = e.renderPreview(1024)
            val ra = e.renderRegion(0, 0, 640, 480, 0.5)
            val firstA = ms(t0)
            t0 = System.nanoTime()
            e.renderPreview(1024)
            val againA = ms(t0)
            val afterA = e.resourcesInfo()
            assertTrue("a second preview of the same state comes from darktable's pipeline cache (hits ${afterA.pipePreviewHits})", afterA.pipePreviewHits > 0)
            val pf = Processing.plan!!.options.pipeCacheFraction
            assertEquals(listOf(pf.first.toLong(), pf.second.toLong()), afterA.pipeCacheFraction)
            e.close()
            t0 = System.nanoTime()
            e.open(b.path, null)
            e.renderPreview(1024)
            val firstB = ms(t0)
            e.close()
            val m0 = e.resourcesInfo().mipmapFullMisses
            t0 = System.nanoTime()
            e.open(a.path, null)
            val pa2 = e.renderPreview(1024)
            val ra2 = e.renderRegion(0, 0, 640, 480, 0.5)
            val backA = ms(t0)
            val r = e.resourcesInfo()
            val mem = e.memoryInfo()
            e.close()
            TestFiles.record(res, "b_series", String.format(Locale.ROOT,
                "decoded photos kept %d: photo A open + preview + region %d ms, same preview again %d ms, photo B open + preview %d ms, " +
                    "back to A open + preview + region %d ms, decodes on going back %d; %s; app PSS %.0f MB (x86_64 emulator)",
                kept, firstA, againA, firstB, backA, r.mipmapFullMisses - m0, mem.describe(), pssMb()))
            assertArrayEquals("the same preview after going back", pa.rgba, pa2.rgba)
            assertArrayEquals("the same region after going back", ra.rgba, ra2.rgba)
            if (kept >= 2) assertEquals("going back to A decodes nothing again", m0, r.mipmapFullMisses)
        }
        a.delete(); b.delete()
    }

    @Test
    fun c_gpuOnWithoutADeviceGoesBackOff() {
        val before = AppHost.crashLog.entries().size
        Processing.setGpu(true, AppHost.controller)
        waitFor("the GPU check", 900_000) { Processing.ui.value.gpuBusy == null && Processing.ui.value.gpuNote != null }
        Thread.sleep(200)
        val ui = Processing.ui.value
        val cl = runBlocking { AppEngine.engine.openclInfo() }
        TestFiles.record(res, "c_gpu", "${ui.gpuNote}; opencl ${cl}")
        assertTrue("OpenCL was started", cl.started)
        if (cl.devices.isEmpty()) {
            assertFalse("no device: the GPU setting is off again", ui.gpu)
            assertFalse(Processing.settings.values.value.gpu)
            assertTrue(ui.gpuNote!!, ui.gpuNote!!.startsWith("GPU off: "))
            assertFalse("and OpenCL is not used", cl.running)
            val e = AppHost.crashLog.entries()
            assertTrue("the reason is in the crash log", e.size > before && e.last().kind == CrashLog.KIND_OPENCL)
        } else {
            // an emulator image with an OpenCL device: the check decided; either way it said why
            assertTrue(ui.gpuNote!!, ui.gpuNote!!.contains("PSNR") || ui.gpuNote!!.startsWith("GPU off"))
        }
        // renders still work, on the CPU
        runBlocking {
            AppHost.controller.withEngine { e ->
                e.open(TestFiles.raw.path, null)
                e.renderPreview(512)
                e.close()
            }
        }
        val after = runBlocking { AppEngine.engine.openclInfo() }
        if (!ui.gpu) assertEquals("cpu", after.lastRender)
        // back to the default for the next tests
        if (Processing.settings.values.value.gpu) { Processing.setGpu(false, AppHost.controller); waitFor("off") { !Processing.ui.value.gpu } }
    }

    @Test
    fun d_measureSpeedOnTheCpu() {
        assertFalse(Processing.settings.values.value.gpu)
        val t0 = System.nanoTime()
        Processing.measure(AppHost.controller, "p4b-emulator-test")
        waitFor("measure speed", 1_500_000) { Processing.ui.value.speedRunning == null && Processing.ui.value.speedText != null }
        val s = (System.nanoTime() - t0) / 1e9
        val text = Processing.ui.value.speedText!!
        File(TestFiles.out, "p4b-speed.txt").writeText("x86_64 emulator numbers, not phone numbers; whole run %.0f s\n".format(Locale.ROOT, s) + text)
        TestFiles.record(res, "d_speed", "${"%.0f".format(Locale.ROOT, s)} s; see p4b-speed.txt")
        val rows = text.lines().filter { it.startsWith("cpu ") }
        assertEquals(text, 4, rows.size)
        assertTrue(text, rows.all { Regex("""first +\d+ \| \d+ \d+ \d+ \| median \d+""").containsMatchIn(it) })
        assertFalse(text, text.contains("stopped"))
        assertTrue(text.contains("resource level " + Processing.plan!!.options.resourceLevel))
        assertFalse("no photo left open", AppHost.controller.state.value.info != null)
    }
}

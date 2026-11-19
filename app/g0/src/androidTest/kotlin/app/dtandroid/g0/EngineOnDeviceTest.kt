// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.g0

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dtandroid.engine.Engine
import app.dtandroid.engine.NativeBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief G0, CI emulator test. Started by tools/app/emulator-g0.sh with
 *   -e raw <path on the device> -e xmp <path on the device>
 * (a public darktable-tests image pushed to /data/local/tmp). Writes its
 * results to the app's files/g0-test/, which the script pulls with run-as.
 *
 * Runs the same code the app runs: Engine.start (unpack, load, dtb_init),
 * EngineOps and Benchmark.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineOnDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val out = File(ctx.filesDir, "g0-test").apply { mkdirs() }
    private val results = File(out, "results.txt")

    private fun record(key: String, value: Any) {
        results.appendText("$key\t$value\n")
    }

    /** Copy a file the shell can read (/data/local/tmp) into app storage. */
    private fun copyFromShell(src: String, dst: File) {
        val pfd: ParcelFileDescriptor = instr.uiAutomation.executeShellCommand("cat $src")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        assertTrue("could not copy $src (empty)", dst.length() > 0)
    }

    private fun start() = Engine.start(ctx) { NativeBridge.runCatching { logLine("[test] $it") } }

    @Test
    fun t1_engineStartsAndAllPluginsLoad() {
        results.delete()
        val r = start()
        File(out, "start-report.txt").writeText(r.summary())
        r.pluginsJson?.let { File(out, "plugins.json").writeText(it) }
        record("unpack_fresh", r.freshUnpack)
        record("unpack_ms", r.unpackMs)
        record("unpacked_files", r.unpackedFiles)
        record("unpacked_bytes", r.unpackedBytes)
        record("libs_loaded", "${r.libsInLoadOrder - r.libFailures.size}/${r.libsInLoadOrder}")
        record("plugin_files_dlopen_ok", "${r.pluginFiles - r.pluginDlopenFailures.size}/${r.pluginFiles}")
        record("init_ms", r.initMs)
        record("engine_version", r.engineVersion ?: "-")
        record("threads", r.threads)
        record("darktable_plugins_loaded", "${r.pluginsLoaded}/${r.pluginsExpected}")
        record("iop_loaded", "${r.iopLoaded}/${r.iopFiles}")
        record("format_loaded", "${r.formatLoaded}/${r.formatFiles}")
        record("storage_loaded", "${r.storageLoaded}/${r.storageFiles}")
        assertTrue(r.summary(), r.ok)
        assertTrue("no plugins found", r.pluginsExpected > 0)
    }

    @Test
    fun t2_openRenderExport() {
        assertTrue(start().ok)
        val rawSrc = args.getString("raw") ?: error("instrumentation argument 'raw' missing")
        val xmpSrc = args.getString("xmp")
        val work = File(ctx.filesDir, "work").apply { mkdirs() }
        val raw = File(work, File(rawSrc).name)
        copyFromShell(rawSrc, raw)
        val xmp = xmpSrc?.let { File(work, raw.name + ".xmp").also { f -> copyFromShell(it, f) } }
        val rawMd5 = md5(raw)

        Engine.callBlocking {
            if (xmp != null) {
                val chk = NativeBridge.xmpCheck(xmp.absolutePath)
                assertNotNull("xmp check: ${NativeBridge.lastError()}", chk)
                File(out, "xmp-check.json").writeText(chk!!)
                assertTrue("guard refused the test XMP: $chk", JSONObject(chk).getBoolean("ok"))
            }
            NativeBridge.close()
            val open = timed { check(NativeBridge.open(raw.absolutePath, xmp?.absolutePath), "open") }
            record("open_ms", open.ms)
            assertTrue("session read-only", !NativeBridge.isReadOnly())
            val (fw, fh) = EngineOps.fullSize()
            record("full_size", "${fw}x$fh")

            val p = timed { EngineOps.preview() }
            record("preview_first_ms", p.ms)
            record("preview_size", "${p.value.width}x${p.value.height}")
            assertTrue(p.value.width > 0 && p.value.height > 0)

            // export the unchanged edit first: CI compares it with darktable-cli on Linux
            val jpg = File(out, "export.jpg")
            jpg.delete()
            val ex = timed { check(NativeBridge.exportJpeg(jpg.absolutePath, 95, 0, 0), "export") }
            record("export_ms", ex.ms)
            assertTrue("no export written", jpg.length() > 0)

            val e0 = EngineOps.exposure()
            record("exposure_before", e0)
            val pc = timed { EngineOps.setExposure(e0 + 1f); EngineOps.preview() }
            record("preview_after_change_ms", pc.ms)
            NativeBridge.historySeal()
            assertEquals(e0 + 1f, EngineOps.exposure(), 1e-4f)

            // a phone-screen-sized region at 100 %, rendered with a margin and cropped
            val r = EngineOps.centredRegion(1080, 2400)
            val rg = timed { EngineOps.region(r) }
            record("region_first_ms", rg.ms)
            record("region", "${r.w}x${r.h}+${r.x}+${r.y}")
            val rc = timed { EngineOps.setExposure(e0 + 0.5f); EngineOps.region(r) }
            record("region_after_change_ms", rc.ms)
            assertEquals(r.w, rc.value.width)

            // cancel: a render cancelled from another thread returns DTB_E_CANCELLED
            EngineOps.setExposure(e0 + 0.7f)
            val canceller = Thread { Thread.sleep(30); NativeBridge.renderCancel() }
            canceller.start()
            val t0 = System.nanoTime()
            val cancelCode = try { EngineOps.fullRender(null); 0 } catch (e: BridgeException) { e.code }
            canceller.join()
            record("cancel_result", NativeBridge.codeName(cancelCode))
            record("cancel_return_ms", (System.nanoTime() - t0) / 1e6)

            // switch the first module of the history off and on again
            val mods = EngineOps.historyModules()
            record("history_modules", mods.joinToString(" ") { "${it.op}.${it.prio}=${if (it.enabled) "on" else "off"}" })

            val saved = File(out, "saved.xmp")
            saved.delete()
            check(NativeBridge.xmpWrite(saved.absolutePath), "xmp write")
            val back = JSONObject(NativeBridge.xmpCheck(saved.absolutePath)!!)
            record("saved_xmp_guard_ok", back.getBoolean("ok"))
            assertTrue(back.getBoolean("ok"))
            NativeBridge.close()
        }
        assertEquals("the RAW copy changed", rawMd5, md5(raw))
        record("raw_unchanged", true)
    }

    @Test
    fun t3_benchmark() {
        assertTrue(start().ok)
        val work = File(ctx.filesDir, "work")
        val raw = work.listFiles()!!.first { !it.name.endsWith(".xmp") }
        val xmp = File(work, raw.name + ".xmp").takeIf { it.isFile }
        val text = Engine.callBlocking {
            Benchmark(ctx, raw, xmp, 1080, 2400) { NativeBridge.logLine("[test] $it") }.run()
        }
        File(out, "benchmark.txt").writeText(text)
        assertTrue(text, !text.contains("FAILURES"))
    }

    @Test
    fun t4_appStartsAndShowsEngineReady() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var ok = false
            sc.onActivity { ok = !it.isFinishing }
            assertTrue(ok)
        }
        File(out, "log.txt").writeText(NativeBridge.logSince(0, LongArray(1)) ?: "")
    }

    private fun md5(f: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 16)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

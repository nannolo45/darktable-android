// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DragPicture
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.Viewport
import app.dtandroid.engine.EnginePinning
import app.dtandroid.engine.FastCores
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.FileLayoutStore
import app.dtandroid.engine.api.ParamValue
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.schema.Control
import app.dtandroid.schema.SchemaLoader
import app.dtandroid.schema.layout.BuiltinLayouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief P2b, deliverable 7, on the emulator with the real engine and a public test RAW. Results go
 * to files/p2b-test/ (results.txt, exports, XMPs, timings.txt), which the CI script pulls; the
 * workflow then compares the exports with darktable-cli on Linux.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealEngineScenarioTest {
    private val res = "results.txt"

    private fun maxDiff(a: RenderedImage, b: RenderedImage): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var m = 0
        for (i in a.rgba.indices) m = maxOf(m, kotlin.math.abs((a.rgba[i].toInt() and 0xff) - (b.rgba[i].toInt() and 0xff)))
        return m
    }

    private fun item(): SeriesItem {
        // the darkroom works on a copy in a working folder, with its sidecar next to it
        val dir = File(TestFiles.ctx.filesDir, "p2b-darkroom").apply { mkdirs() }
        val raw = File(dir, TestFiles.raw.name)
        if (!raw.isFile) { TestFiles.raw.copyTo(raw); raw.setWritable(false, false) }
        return SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
    }

    private fun controller(scope: CoroutineScope): DarkroomController {
        val ctx = TestFiles.ctx
        val builtins = BuiltinLayouts.fromResources()
        val layouts = FileLayoutStore(File(ctx.filesDir, "p2b-test-layouts/layouts.json"),
            { wf -> builtins.presets(wf).map { it.name to it.params } }, builtins.defaultWorkflow, builtins.fallbackLayout)
        return DarkroomController(TestFiles.engine(), SchemaLoader.fromResources(), builtins, layouts, scope)
    }

    private suspend fun waitFor(what: String, ms: Long = 120_000, cond: () -> Boolean) {
        try { withTimeout(ms) { while (!cond()) delay(10) } } catch (e: Exception) { throw AssertionError("timed out: $what") }
    }

    @Test
    fun t1_startReportAndFastCores() {
        val e = TestFiles.engine()
        val ready = AppEngine.status.value as AppEngine.Status.Ready
        TestFiles.record(res, "engine", ready.info.darktableVersion)
        TestFiles.record(res, "plugins", "${ready.info.pluginsLoaded}/${ready.info.pluginFiles}")
        TestFiles.record(res, "cores", ready.cores.describe())
        TestFiles.record(res, "pinning_at_start", ready.pinningAtStart.describe())
        TestFiles.record(res, "engine_thread_cpus_at_start", AppEngine.cpusAtStart?.let { FastCores.formatCpuList(it) } ?: "not readable")
        TestFiles.record(res, "unpack_fresh", ready.freshUnpack)
        TestFiles.record(res, "unpack_ms", ready.unpackMs)
        TestFiles.record(res, "init_ms", ready.initMs)
        assertTrue(ready.info.pluginsMissing.isEmpty())
        assertEquals(listOf(4, 5, 6, 7), FastCores.choose((0..7).toList(), (0..7).associateWith { if (it < 4) 1_800_000L else if (it < 7) 2_420_000L else 2_840_000L }))
        assertEquals(listOf(0, 1), FastCores.choose(listOf(0, 1), mapOf(0 to null, 1 to null)))
        runBlocking { TestFiles.record(res, "engine_info", e.engineInfoJson()) }
    }

    /**
     * Brief P2g on the real bridge: a pinning the bridge refuses (a CPU outside the engine thread's
     * start-up set, the phone's "CPU 4 is not available to this process") leaves the engine unpinned
     * and working, without an exception; then the app's own pinning is applied again as on a return
     * to the foreground. (The process itself cannot be started with a narrowed CPU set from a test:
     * see the P2g report.)
     */
    @Test
    fun t1b_pinningRefusedByTheBridgeLeavesTheEngineWorking() = runBlocking<Unit> {
        val e = TestFiles.engine()
        val atStart = AppEngine.cpusAtStart
        assertNotNull("the engine thread's CPUs are readable on Android", atStart)
        val outside = atStart!!.max() + 1
        // a device whose "fast cores" are a CPU the engine thread could not use at start
        val all = (0..outside).toList()
        val cores = FastCores(all, all.associateWith { if (it == outside) 3_000_000L else 1_000_000L }, listOf(outside))
        assertEquals(listOf(outside), EnginePinning.plan(cores, null, null).cpus)
        val before = AppEngine.pinning.value
        val p = EnginePinning.pin(e, cores, allowed = null, atStart = null, previous = before)
        TestFiles.record(res, "p2g_refused", p.describe())
        assertFalse(p.pinned)
        assertTrue(p.describe(), p.describe().contains("CPU $outside is not available to this process"))
        val info = e.engineInfoJson()
        TestFiles.record(res, "p2g_after_refusal_engine_info", info)
        assertEquals("[]", info["thread_cpus"].toString())
        // with the start-up set known, the plan does not even ask the bridge
        assertTrue(EnginePinning.plan(cores, atStart, atStart).cpus.isEmpty())
        // the engine works unpinned
        runCatching { e.close() }
        e.open(TestFiles.raw.path, TestFiles.xmp.path)
        val pic = e.renderPreview(256)
        assertTrue(pic.width > 0)
        e.close()
        // the app's pinning again, as onForeground does it
        val runs = AppEngine.foregroundRuns
        AppEngine.onForeground()
        waitFor("pinning applied again") { AppEngine.foregroundRuns > runs }
        TestFiles.record(res, "p2g_foreground", AppEngine.lastForeground)
        assertEquals(before.cpus, AppEngine.pinning.value.cpus)
    }

    /** open -> change -> undo -> write XMP -> reopen gives the same state (and the same pixels). */
    @Test
    fun t2_openChangeUndoWriteReopen() = runBlocking<Unit> {
        val e = TestFiles.engine()
        val md5 = TestFiles.md5(TestFiles.raw)
        runCatching { e.close() }
        e.open(TestFiles.raw.path, TestFiles.xmp.path)
        assertFalse(e.imageInfo().readOnly)
        val exp0 = (e.modules().first { it.op == "exposure" }.params["exposure"] as JsonPrimitive).doubleOrNull!!
        e.paramSet("exposure", 0, "exposure", ParamValue.of(exp0 + 0.5)); e.historySeal()
        val afterFirst = e.modules() to e.history()
        e.paramSet("colorbalancergb", 0, "vibrance", ParamValue.of(0.3)); e.historySeal()
        e.undo()
        assertEquals("undo gives the state before the change", afterFirst.first, e.modules())
        assertEquals(afterFirst.second, e.history())
        val pic = e.renderPreview(512)
        val xmp = File(TestFiles.out, "app-edit.xmp").apply { delete() }
        e.xmpWrite(xmp.path)
        assertTrue(e.xmpCheck(xmp.path).ok)
        // the app's own export of this edit; CI renders app-edit.xmp with darktable-cli and compares
        val jpg = File(TestFiles.out, "app-edit.jpg").apply { delete() }
        e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE))
        e.close()
        e.open(TestFiles.raw.path, xmp.path)
        val m2 = e.modules()
        val h2 = e.history()
        assertEquals(afterFirst.second.end, h2.end)
        assertEquals(afterFirst.second.items, h2.items)
        val diffs = afterFirst.first.zip(m2).filter { (a, b) -> a != b }.map { (a, _) -> "${a.op}/${a.priority}" }
        TestFiles.record(res, "reopen_module_differences", diffs.ifEmpty { listOf("none") }.joinToString(","))
        assertEquals("modules() after reopen", afterFirst.first, m2)
        val d = maxDiff(pic, e.renderPreview(512))
        TestFiles.record(res, "reopen_preview_max_diff", d)
        assertEquals(0, d)
        e.close()
        assertEquals("the RAW was not changed", md5, TestFiles.md5(TestFiles.raw))
        TestFiles.record(res, "raw_unchanged", true)
    }

    /** The G0 comparison: the test XMP as it is, exported by the app (CI compares with darktable-cli). */
    @Test
    fun t3_exportOfTheTestEdit() = runBlocking<Unit> {
        val e = TestFiles.engine()
        runCatching { e.close() }
        e.open(TestFiles.raw.path, TestFiles.xmp.path)
        val jpg = File(TestFiles.out, "export.jpg").apply { delete() }
        val t0 = System.nanoTime()
        e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE))
        TestFiles.record(res, "export_ms_emulator", (System.nanoTime() - t0) / 1_000_000)
        assertTrue(jpg.length() > 0)
        e.close()
    }

    /** A photo whose XMP fails the guard: banner, nothing changes, its XMP is never rewritten. */
    @Test
    fun t4_readOnlyPath() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val it0 = item()
            File(it0.xmpPath).delete()
            TestFiles.writeXmpWithUnknownModule(File(it0.xmpPath))
            val before = File(it0.xmpPath).readText()
            val c = controller(scope)
            c.start(Series("test", listOf(it0)), 0, filmstrip = false, thumbnails = false)
            val s = c.state.value
            assertTrue(s.readOnly)
            assertNotNull("the banner text", s.readOnlyReason)
            TestFiles.record(res, "read_only_banner", s.readOnlyReason!!)
            assertTrue(s.readOnlyReason!!.contains("dtandroidunknownmodule"))
            waitFor("preview of a read-only photo") { c.state.value.preview != null }
            val exposure = TestFiles.engine().modules().first { it.op == "exposure" }
            val slider = c.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
            // "before" in a read-only session goes to position 0 and back (bridge gap: end > items, see the report)
            val endBefore = c.state.value.history.end
            c.setBefore(true)
            assertNotNull(c.state.value.beforePreview)
            c.setBefore(false)
            TestFiles.record(res, "read_only_history_end_and_items", "${c.state.value.history.end} ${c.state.value.history.items.size} (before: $endBefore)")
            assertNull("before and back without an error: ${c.state.value.message}", c.state.value.message)
            c.setSlider(InstanceKey("exposure", 0), slider.binding, 2.0, false)
            assertTrue("refused with a message", c.state.value.message?.contains("READ_ONLY") == true)
            assertEquals(exposure.params, TestFiles.engine().modules().first { it.op == "exposure" }.params)
            try { c.writeXmp(File(TestFiles.out, "ro.xmp").path); throw AssertionError("save XMP was not refused") }
            catch (e: EngineException) { assertEquals(EngineError.READ_ONLY, e.error) }
            c.closePhoto()
            assertEquals("the XMP was never rewritten", before, File(it0.xmpPath).readText())
            File(it0.xmpPath).delete()
        } finally { scope.cancel() }
    }

    /** Dragging cancels the sharp render in flight; the small picture follows; the full one comes after. */
    @Test
    fun t5_cancellationWhileDragging() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val it0 = item()
            File(it0.xmpPath).delete()
            TestFiles.xmp.copyTo(File(it0.xmpPath), overwrite = true)
            val c = controller(scope)
            val e = TestFiles.engine()
            // a phone-sized view at 100 %: a large region, so a drag starts while it renders
            c.setViewport(Viewport(1080, 1500, zoom = 4f))
            c.start(Series("test", listOf(it0)), 0, filmstrip = false, thumbnails = false)
            waitFor("first preview and region") { c.state.value.preview != null && c.state.value.region != null }
            val key = InstanceKey("exposure", 0)
            val slider = c.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
            val n0 = c.state.value.history.items.size
            val base = c.sliderValue(c.state.value, key, slider, emptyMap())!!
            c.requestRender()
            delay(30) // the full preview and the region are now being rendered
            val cancels0 = e.cancels.get()
            val t0 = System.nanoTime()
            for (i in 1..20) {
                c.drag(key, slider.id, slider.binding, base + i * 0.02, false)
                delay(16) // a finger reports about 60 moves a second
            }
            c.endDrag(key, slider.id, slider.binding, false)
            waitFor("drag ended") { c.liveValues.value.isEmpty() }
            waitFor("full preview and region after the drag") {
                c.state.value.region != null && (c.state.value.preview?.image?.let { maxOf(it.width, it.height) } ?: 0) >= 1024
            }
            val total = (System.nanoTime() - t0) / 1_000_000
            assertTrue("renders were cancelled", e.cancels.get() > cancels0)
            assertNull("no error shown: ${c.state.value.message}", c.state.value.message)
            assertEquals("one history item for the whole drag", n0 + 1, c.state.value.history.items.size)
            assertEquals(base + 0.4, c.sliderValue(c.state.value, key, slider, emptyMap())!!, 1e-4)
            val d: DragPicture = c.dragPicture
            TestFiles.record(res, "drag_renders", d.dragRenders)
            TestFiles.record(res, "drag_size_px_emulator", d.lastSize ?: -1)
            TestFiles.record(res, "drag_last_ms_emulator", d.lastDragMs ?: -1)
            TestFiles.record(res, "drag_estimate_emulator", d.describe())
            TestFiles.record(res, "drag_to_full_picture_ms_emulator", total)
            TestFiles.record(res, "region_ms_emulator", c.renderTimes.value.regionMs ?: -1)
            assertTrue(d.dragRenders >= 1)
            c.closePhoto()
            File(it0.xmpPath).delete()
        } finally { scope.cancel() }
    }

    /** The timings page's sequence on the emulator (its text is what the owner will send back from the phone). */
    @Test
    fun t6_timingsSequence() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val it0 = item()
            File(it0.xmpPath).delete()
            TestFiles.xmp.copyTo(File(it0.xmpPath), overwrite = true)
            val c = controller(scope)
            c.start(Series("test", listOf(it0)), 0, filmstrip = false, thumbnails = false)
            val histBefore = c.state.value.history
            val ready = AppEngine.status.value as AppEngine.Status.Ready
            val t = Timings(TestFiles.engine(), c, File(TestFiles.ctx.cacheDir, "out"), 1080, 1500, "test", AppEngine.engineId, ready.cores)
            val r = t.run(it0) {}
            File(TestFiles.out, "timings.txt").writeText(r.text)
            assertFalse(r.text, r.text.contains("stopped:") || r.text.contains("FAILED"))
            assertTrue(r.text.contains("modules() size bytes"))
            assertFalse("no device model in the text", r.text.contains(android.os.Build.MODEL))
            assertEquals("the edit is back as it was", histBefore.items, c.state.value.history.items)
            c.closePhoto()
            File(it0.xmpPath).delete()
        } finally { scope.cancel() }
    }

    /** The app starts and shows its first screen without crashing. */
    @Test
    fun t7_appStarts() {
        TestFiles.engine()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(2000)
            var ok = false
            sc.onActivity { ok = !it.isFinishing }
            assertTrue(ok)
        }
        File(TestFiles.out, "log.txt").writeText(app.dtandroid.engine.NativeBridge.logSince(0, LongArray(1)) ?: "")
    }
}

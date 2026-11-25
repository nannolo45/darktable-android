// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.speed.GpuCheck
import app.dtandroid.darkroom.speed.ProcessingResources
import app.dtandroid.darkroom.speed.ProcessingResources.Choice
import app.dtandroid.darkroom.speed.ProcessingResources.RamClass
import app.dtandroid.darkroom.speed.ProcessingSettings
import app.dtandroid.darkroom.speed.SpeedBenchmark
import app.dtandroid.darkroom.speed.TestRaw
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.OpenClDevice
import app.dtandroid.engine.api.OpenClInfo
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Brief P4b deliverable 5 (JVM): the resource choice by the phone's memory, the GPU check against a fake engine with a
 * fake GPU (pictures that agree, that differ, no device, an error during the GPU render, darktable's CPU fallback), the
 * benchmark's sequence and text, the test photo's checks, the settings file.
 */
class P4bSpeedTest {
    @get:Rule val tmp = TemporaryFolder()

    private val gib = 1L shl 30

    // ------------------------------------------------------------------ resources by RAM

    @Test
    fun resourcesByTotalMemory() {
        // what Android reports (MemoryInfo.totalMem) on phones sold as 12, 8, 6 and 4 GB
        val twelve = (11.2 * gib).toLong(); val eight = (7.5 * gib).toLong(); val six = (5.6 * gib).toLong(); val four = (3.7 * gib).toLong()
        assertEquals(RamClass.LARGE, ProcessingResources.ramClass(twelve))
        assertEquals(RamClass.LARGE, ProcessingResources.ramClass(16 * gib))
        assertEquals(RamClass.MEDIUM, ProcessingResources.ramClass(eight))
        assertEquals(RamClass.MEDIUM, ProcessingResources.ramClass(six))
        assertEquals(RamClass.SMALL, ProcessingResources.ramClass(four))

        val p = ProcessingResources.plan(twelve, Choice.NORMAL)
        assertEquals("default", p.options.resourceLevel)
        assertEquals("384 8 32 700", p.options.resourceValues)
        assertEquals(8 to 32, p.options.pipeCacheFraction)
        assertEquals(3, p.options.inputsKept)
        assertTrue(p.options.opencl)
        assertEquals("""{"opencl":true,"resource_level":"default","resource_values":"384 8 32 700","pipe_cache_fraction":[8,32],"inputs_kept":3}""",
            p.options.toJson())
        assertEquals("512 8 64 700", ProcessingResources.plan(twelve, Choice.MORE).options.resourceValues)
        assertEquals(4, ProcessingResources.plan(twelve, Choice.MORE).options.inputsKept)
        assertEquals("256 8 32 700", ProcessingResources.plan(twelve, Choice.LESS).options.resourceValues)
        assertEquals(16 to 64, ProcessingResources.plan(twelve, Choice.LESS).options.pipeCacheFraction)

        assertEquals("256 8 32 700", ProcessingResources.plan(eight, Choice.NORMAL).options.resourceValues)
        assertEquals(2, ProcessingResources.plan(six, Choice.NORMAL).options.inputsKept)
        val small = ProcessingResources.plan(four, Choice.NORMAL).options
        assertEquals("small", small.resourceLevel)
        assertEquals("128 4 16 400", small.resourceValues)
        assertEquals(1, ProcessingResources.plan(four, Choice.LESS).options.inputsKept)

        // every plan: four positive numbers, never more than desktop's own "default" share for processing
        for (t in listOf(twelve, eight, six, four)) for (c in Choice.entries) {
            val o = ProcessingResources.plan(t, c).options
            val v = o.resourceValues!!.split(' ').map { it.toInt() }
            assertEquals(4, v.size)
            assertTrue(v.all { it > 0 } && v[0] <= 512)
            assertTrue(o.inputsKept in 1..4)
            // more never gives less than normal, less never more
            val n = ProcessingResources.plan(t, Choice.NORMAL).options.resourceValues!!.split(' ')[0].toInt()
            if (c == Choice.MORE) assertTrue(v[0] >= n)
            if (c == Choice.LESS) assertTrue(v[0] <= n)
        }
        assertTrue(ProcessingResources.plan(twelve, Choice.NORMAL).describe().contains("12 GB and more"))
    }

    @Test
    fun settingsKeepGpuMemoryAndNote() {
        val f = File(tmp.root, "settings/processing.json")
        val s = ProcessingSettings(f)
        assertFalse("off by default", s.values.value.gpu)
        assertEquals(Choice.NORMAL, s.values.value.memory)
        s.update { it.copy(gpu = true, memory = Choice.MORE, gpuNote = "GPU on: Adreno") }
        val again = ProcessingSettings(f).values.value
        assertTrue(again.gpu)
        assertEquals(Choice.MORE, again.memory)
        assertEquals("GPU on: Adreno", again.gpuNote)
        f.writeText("not json")
        assertFalse(ProcessingSettings(f).values.value.gpu)
    }

    // ------------------------------------------------------------------ the GPU check

    /** A fake engine with a fake GPU: what the GPU's pictures look like and how OpenCL behaves. */
    private class FakeGpu(
        val base: FakeEngine,
        val device: Boolean = true,
        val built: Boolean = true,
        /** changes a GPU picture (the driver's error) */
        val gpuPicture: (RenderedImage) -> RenderedImage = { it },
        val failOnGpu: Boolean = false,
        val fallback: Boolean = false,
    ) : DarktableEngine by base {
        var cl = false
        var started = false
        var last = "none"
        val calls = ArrayList<String>()

        private fun info() = OpenClInfo(built = built, allowed = true, started = started, library = if (device) "libOpenCL.so" else null,
            inited = started && device, enabled = cl && device, running = cl && device,
            devices = if (started && device) listOf(OpenClDevice("QUALCOMM Adreno(TM) 650", "QUALCOMM Snapdragon(TM)", "OpenCL 2.0", 3L shl 30, true, false)) else emptyList(),
            lastRender = last)

        override suspend fun openclSet(enabled: Boolean): OpenClInfo {
            calls += "opencl $enabled"
            if (!built) throw EngineException(EngineError.STATE, "this engine was built without OpenCL")
            if (enabled) started = true
            cl = enabled && device
            return info()
        }

        override suspend fun openclInfo(): OpenClInfo = info()

        override suspend fun renderPreview(maxSize: Int): RenderedImage = renderPreview(maxSize, RenderOptions.NONE)

        override suspend fun renderPreview(maxSize: Int, options: RenderOptions): RenderedImage {
            val img = base.renderPreview(maxSize, options)
            return onDevice(img)
        }

        override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double): RenderedImage = renderRegion(x, y, w, h, scale, RenderOptions.NONE)

        override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double, options: RenderOptions): RenderedImage =
            onDevice(base.renderRegion(x, y, w, h, scale, options))

        private fun onDevice(img: RenderedImage): RenderedImage {
            if (!cl) { last = "cpu"; return img }
            if (failOnGpu) throw EngineException(EngineError.ENGINE, "pixelpipe stopped early (OpenCL error -5)")
            if (fallback) { last = "fallback"; return img }
            last = "gpu"
            return gpuPicture(img)
        }
    }

    private fun engine(dir: File): Pair<FakeEngine, String> {
        val e = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { e.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val raw = File(dir, TestRaw.NAME).apply { writeBytes(ByteArray(64)) }
        return e to raw.path
    }

    /** +1 on every 7th channel value: rounding as a correct GPU gives it */
    private val rounding: (RenderedImage) -> RenderedImage = { img ->
        val p = img.rgba.copyOf()
        for (i in p.indices step 7) if (i % 4 != 3) p[i] = ((p[i].toInt() and 0xff).let { if (it == 255) 254 else it + 1 }).toByte()
        RenderedImage(img.width, img.height, p)
    }

    /** the lower half inverted: a driver that computes wrongly */
    private val broken: (RenderedImage) -> RenderedImage = { img ->
        val p = img.rgba.copyOf()
        for (i in p.size / 2 until p.size) if (i % 4 != 3) p[i] = (255 - (p[i].toInt() and 0xff)).toByte()
        RenderedImage(img.width, img.height, p)
    }

    @Test
    fun gpuStaysOnWhenThePicturesAgree() = runBlocking<Unit> {
        val (base, raw) = engine(tmp.root)
        val g = FakeGpu(base, gpuPicture = rounding)
        val steps = ArrayList<String>()
        val r = GpuCheck(g, raw).run { steps += it }
        assertTrue(r.text, r is GpuCheck.Result.On)
        r as GpuCheck.Result.On
        assertTrue(r.psnr >= GpuCheck.PSNR_MIN_DB)
        assertEquals(1, r.maxDiff)
        assertTrue(r.text.contains("Adreno"))
        assertTrue("OpenCL left on", g.cl)
        assertEquals(listOf("opencl true", "opencl false", "opencl true"), g.calls)
        assertEquals(3, steps.size)
        // nothing left open
        try { base.imageInfo(); fail("a photo is still open") } catch (e: EngineException) { assertEquals(EngineError.STATE, e.error) }
    }

    @Test
    fun gpuGoesOffWhenThePicturesDiffer() = runBlocking<Unit> {
        val (base, raw) = engine(tmp.root)
        val g = FakeGpu(base, gpuPicture = broken)
        val r = GpuCheck(g, raw).run()
        assertTrue(r.text, r is GpuCheck.Result.Off)
        assertTrue(r.text, r.text.contains("differs from the CPU's") && r.text.contains("40.0 dB"))
        assertFalse("OpenCL switched off again", g.cl)
        assertEquals("opencl false", g.calls.last())
    }

    @Test
    fun gpuGoesOffWithoutADevice() = runBlocking<Unit> {
        val (base, raw) = engine(tmp.root)
        val g = FakeGpu(base, device = false)
        val r = GpuCheck(g, raw).run()
        assertTrue(r is GpuCheck.Result.Off)
        assertEquals("no OpenCL library was found on this phone", r.text)
        assertFalse(g.cl)
        val nb = FakeGpu(base, built = false)
        val r2 = GpuCheck(nb, raw).run()
        assertTrue(r2.text, r2.text.contains("built without OpenCL"))
    }

    @Test
    fun gpuGoesOffOnAnErrorDuringTheRender() = runBlocking<Unit> {
        val (base, raw) = engine(tmp.root)
        val g = FakeGpu(base, failOnGpu = true)
        val r = GpuCheck(g, raw).run()
        assertTrue(r is GpuCheck.Result.Off)
        assertTrue(r.text, r.text.contains("test render failed") && r.text.contains("OpenCL error"))
        assertFalse(g.cl)
        // darktable's own fallback (an OpenCL error, the render finished on the CPU) also switches it off
        val f = FakeGpu(base, fallback = true)
        val r2 = GpuCheck(f, raw).run()
        assertTrue(r2.text, r2 is GpuCheck.Result.Off && r2.text.contains("finished it on the CPU"))
        assertFalse(f.cl)
    }

    @Test
    fun psnrOfEqualAndDifferentPictures() {
        val a = RenderedImage(2, 1, byteArrayOf(10, 20, 30, -1, 40, 50, 60, -1))
        assertTrue(GpuCheck.compare(a, a).first.isInfinite())
        val b = RenderedImage(2, 1, byteArrayOf(11, 20, 30, 0, 40, 50, 60, 0))
        val (psnr, mx) = GpuCheck.compare(a, b)
        // mse = 1/6 -> 10 log10(65025 * 6)
        assertEquals(10 * kotlin.math.log10(65025.0 * 6), psnr, 1e-9)
        assertEquals("alpha is not compared", 1, mx)
    }

    // ------------------------------------------------------------------ the benchmark

    @Test
    fun benchmarkRunsTheFixedSequenceAndFormatsIt() = runBlocking<Unit> {
        val (base, raw) = engine(tmp.root)
        val g = FakeGpu(base, gpuPicture = rounding)
        g.openclSet(true)
        val steps = ArrayList<String>()
        val r = SpeedBenchmark(g, raw, gpu = true, header = listOf("app: test")).run { steps += it }
        assertEquals(8, r.rows.size)
        assertEquals(8, steps.size)
        assertEquals(listOf("cpu", "gpu"), r.rows.map { it.device }.distinct())
        assertEquals(listOf("default", "heavy"), r.rows.map { it.edit }.distinct())
        assertEquals(listOf("fit", "100%"), r.rows.map { it.view }.distinct())
        assertTrue(r.rows.all { it.runsMs.size == SpeedBenchmark.RUNS })
        assertTrue("each device ran on itself", r.rows.all { it.ranOn == it.device })
        val lines = r.text.lines()
        assertTrue(lines[0].startsWith("dt-android speed"))
        assertEquals("app: test", lines[1])
        assertEquals(8, lines.count { it.matches(Regex("""(cpu|gpu) +(default|heavy) +(fit|100%) +first +\d+ \| \d+ \d+ \d+ \| median \d+""")) })
        assertTrue("the GPU as the setting says afterwards", g.cl)
        try { base.imageInfo(); fail("a photo is still open") } catch (e: EngineException) { assertEquals(EngineError.STATE, e.error) }

        // GPU off: the CPU half only, on an engine without OpenCL too
        val cpuOnly = SpeedBenchmark(FakeGpu(base, built = false), raw, gpu = false).run()
        assertEquals(4, cpuOnly.rows.size)
        assertTrue(cpuOnly.rows.all { it.device == "cpu" })
    }

    // ------------------------------------------------------------------ the test photo

    @Test
    fun testPhotoIsCheckedBeforeUse() {
        val dir = File(tmp.root, "bench")
        val wrong = TestRaw(dir, "x") { ByteArrayInputStream(ByteArray(1000) { 7 }) }
        assertFalse(wrong.present())
        try { wrong.ensure(); fail("a wrong file was accepted") } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("not the expected file")) }
        assertFalse(wrong.file.exists())
        assertNull(dir.listFiles()?.firstOrNull { it.name.endsWith(".part") })
        val failing = TestRaw(dir, "x") { throw IOException("no network") }
        try { failing.ensure(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("could not be fetched: no network")) }
        // a file of the right size counts as present (its hash was checked when it was fetched)
        dir.mkdirs()
        java.io.RandomAccessFile(File(dir, TestRaw.NAME), "rw").use { it.setLength(TestRaw.SIZE) }
        assertTrue(TestRaw(dir).present())
    }
}

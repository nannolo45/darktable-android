// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.speed

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.OpenClInfo
import app.dtandroid.engine.api.RenderedImage
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.log10

/**
 * Brief P4b deliverable 1 (PLAN.md risk row "vendor OpenCL driver gives wrong results"): what happens when the user
 * switches "use the GPU (OpenCL)" on. OpenCL is started; if no device was found it goes back off with the reason. Then
 * one test render of a fixed photo (the public test RAW, darktable's default edit, [SIZE] px) runs on the CPU and on the
 * GPU; the GPU stays on only if the render ran on the GPU without an OpenCL error and the two pictures agree
 * ([PSNR_MIN_DB]). Any other outcome switches OpenCL off again and says why. Runs on an engine with no photo open; leaves
 * none open.
 */
class GpuCheck(private val engine: DarktableEngine, private val testRaw: String, private val libraries: OpenClLibraries? = null) {
    /** Brief P4d: what the app's own library loading found (null: not run, OpenCL was already started) */
    var probe: OpenClLibraries.Probe? = null
        private set

    sealed interface Result {
        val text: String
        /** OpenCL stays on. */
        data class On(override val text: String, val device: String, val psnr: Double, val maxDiff: Int, val cpuMs: Long, val gpuMs: Long) : Result
        /** OpenCL was switched off again; [text] says why. */
        data class Off(override val text: String) : Result
    }

    suspend fun run(progress: (String) -> Unit = {}): Result {
        var keep = false
        try {
            progress("starting the GPU (the first time darktable prepares its GPU programs, which can take a few minutes)")
            // brief P4d: libraries the app itself can load go first, before darktable's start
            if (libraries != null && !engine.openclInfo().started) {
                val p = libraries.probe()
                probe = p
                runCatching { engine.openclLibraries(p.preferred) }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            }
            val started = try { engine.openclSet(true) } catch (e: EngineException) { return Result.Off("the GPU cannot be used: ${e.detail}") }
            val device = started.usableDevices.firstOrNull()
            if (!started.running || device == null) {
                // brief P4d: the engine's diagnostics say why, when it has them
                val d = runCatching { engine.openclDiagnostics() }.getOrNull()
                val why = if (d != null && d.started && started.library != null && started.devices.isEmpty()) GpuDetails.reason(d, started) else null
                return Result.Off(why ?: noDevice(started))
            }

            progress("test render on the CPU")
            engine.openclSet(false)
            val (cpu, cpuMs) = render()
            progress("test render on the GPU")
            engine.openclSet(true)
            val (gpu, gpuMs) = try { render() } catch (e: EngineException) {
                return Result.Off("the GPU's test render failed: ${e.detail}")
            }
            val after = engine.openclInfo()
            when (after.lastRender) {
                "fallback" -> return Result.Off("the GPU reported an error during the test render (darktable finished it on the CPU)")
                "gpu" -> Unit
                else -> return Result.Off("the test render did not run on the GPU (${after.lastRender})")
            }
            if (cpu.width != gpu.width || cpu.height != gpu.height)
                return Result.Off("the GPU's test picture has another size (${gpu.width} x ${gpu.height}, the CPU's ${cpu.width} x ${cpu.height})")
            val (psnr, maxDiff) = compare(cpu, gpu)
            if (psnr < PSNR_MIN_DB)
                return Result.Off("the GPU's test picture differs from the CPU's: PSNR ${db(psnr)} dB, below the ${db(PSNR_MIN_DB)} dB needed " +
                    "(largest difference $maxDiff of 255); the GPU driver may compute wrongly")
            keep = true
            return Result.On("GPU on: ${device.name} (${device.platform}); the test picture agrees with the CPU's: PSNR ${db(psnr)} dB, " +
                "largest difference $maxDiff of 255; test render CPU $cpuMs ms, GPU $gpuMs ms", device.name, psnr, maxDiff, cpuMs, gpuMs)
        } catch (e: EngineException) {
            return Result.Off("the GPU check failed: ${e.detail}")
        } finally {
            runCatching { engine.close() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (!keep) runCatching { engine.openclSet(false) }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    private fun noDevice(i: OpenClInfo): String = when {
        !i.built -> "this engine was built without OpenCL"
        i.library == null && i.devices.isEmpty() -> "no OpenCL library was found on this phone"
        i.devices.isEmpty() -> "darktable found no GPU it can use (OpenCL library ${i.library})"
        i.usableDevices.isEmpty() -> "darktable found ${i.devices.joinToString { it.name }} but does not use it (switched off in darktable's settings for this device)"
        else -> "darktable stopped OpenCL after errors (${i.errorCount})"
    }

    /** One test render from the RAW, with the caches dropped first, so the device computes every module. */
    private suspend fun render(): Pair<RenderedImage, Long> {
        runCatching { engine.close() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        engine.cacheDrop()
        engine.open(testRaw, null)
        val t0 = System.nanoTime()
        val img = engine.renderPreview(SIZE)
        val ms = (System.nanoTime() - t0) / 1_000_000
        engine.close()
        return img to ms
    }

    companion object {
        /** The test render's long side, px: the darkroom's preview size. */
        const val SIZE = 1024

        /**
         * Pictures agree from this PSNR (8-bit RGB, dB): 40 dB is a root-mean-square difference of 2.55 levels of 255,
         * around what the eye can just see in a smooth area; darktable's own integration tests accept CPU and OpenCL
         * results that differ by a small delta E for the same reason (rounding, fast maths on the GPU). A driver that
         * computes wrongly gives far lower numbers (whole areas off).
         */
        const val PSNR_MIN_DB = 40.0

        private fun db(v: Double) = if (v.isInfinite()) "∞" else String.format(Locale.ROOT, "%.1f", v)

        /** PSNR over RGB (dB, infinity when equal) and the largest channel difference. */
        fun compare(a: RenderedImage, b: RenderedImage): Pair<Double, Int> {
            require(a.width == b.width && a.height == b.height)
            var se = 0.0
            var mx = 0
            val pa = a.rgba; val pb = b.rgba
            var i = 0
            while (i < pa.size) {
                for (c in 0..2) {
                    val d = (pa[i + c].toInt() and 0xff) - (pb[i + c].toInt() and 0xff)
                    se += (d * d).toDouble()
                    if (abs(d) > mx) mx = abs(d)
                }
                i += 4
            }
            if (se == 0.0) return Double.POSITIVE_INFINITY to 0
            val mse = se / (3.0 * a.width * a.height)
            return 10.0 * log10(255.0 * 255.0 / mse) to mx
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.g0

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.dtandroid.engine.Engine
import app.dtandroid.engine.NativeBridge
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/** Device facts for the results table. Shown on the device only, never sent. */
object DeviceInfo {
    fun lines(ctx: Context): List<String> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return listOf(
            "device: ${Build.MANUFACTURER} ${Build.MODEL} (${soc()})",
            "android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI ${Build.SUPPORTED_ABIS.firstOrNull()}",
            "cores: ${Runtime.getRuntime().availableProcessors()}",
            String.format(Locale.ROOT, "memory: %.1f GB total, %.1f GB available", mi.totalMem / 1e9, mi.availMem / 1e9),
        )
    }

    private fun soc(): String =
        if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
}

/**
 * The benchmark of brief G0: on the open image, for 1, 4 and all threads,
 * open, first preview, preview after an exposure change, screen-sized region
 * (first and after a change), full-size render after a change, and a full
 * size JPEG export. Runs on the engine thread. Between thread settings the
 * image is closed and its decoded data dropped from the engine's caches
 * (dtb_cache_drop), so each setting starts from the RAW file.
 */
class Benchmark(
    private val ctx: Context,
    private val raw: File,
    /** XMP the image is reopened from each round: a snapshot of the current edit */
    private val xmp: File?,
    private val screenW: Int,
    private val screenH: Int,
    private val progress: (String) -> Unit,
) {
    data class Row(val label: String, val ms: MutableMap<Int, Double> = LinkedHashMap())

    private val rows = LinkedHashMap<String, Row>()
    private fun put(label: String, threads: Int, ms: Double) {
        rows.getOrPut(label) { Row(label) }.ms[threads] = ms
    }

    fun threadSettings(): List<Int> {
        val all = NativeBridge.setThreads(0)
        return listOf(1, 4, all).filter { it <= all }.distinct()
    }

    /** Engine thread only. Returns the results as plain text. */
    fun run(): String {
        val settings = threadSettings()
        var imageInfo = ""
        var regionInfo = ""
        var full: ByteBuffer? = null
        val failures = mutableListOf<String>()
        for (t in settings) {
            try {
                val eff = NativeBridge.setThreads(t)
                progress("benchmark: $eff threads")
                NativeBridge.close()  // may not be open; error ignored
                check(NativeBridge.cacheDrop(), "cache drop")
                put("open (import, XMP, history)", t, timed { check(NativeBridge.open(raw.absolutePath, xmp?.absolutePath), "open") }.ms)
                val e0 = EngineOps.exposure()
                put("preview ${EngineOps.PREVIEW_SIZE} px, first", t, timed { EngineOps.preview() }.ms)
                put("preview after exposure change", t, timed { EngineOps.setExposure(e0 + 0.5f); EngineOps.preview() }.ms)
                val r = EngineOps.centredRegion(screenW, screenH)
                regionInfo = "${r.w}x${r.h} at 100 %, +${EngineOps.REGION_MARGIN} px margin"
                put("screen region, first", t, timed { EngineOps.region(r) }.ms)
                put("screen region after exposure change", t, timed { EngineOps.setExposure(e0); EngineOps.region(r) }.ms)
                val (fw, fh) = EngineOps.fullSize()
                imageInfo = "${raw.extension.lowercase(Locale.ROOT)} file, ${fw}x$fh after the edit " +
                    String.format(Locale.ROOT, "(%.1f MP)", fw * fh / 1e6)
                put("full size render after exposure change", t, timed {
                    EngineOps.setExposure(e0 + 0.5f); full = EngineOps.fullRender(full)
                }.ms)
                val out = File(ctx.cacheDir, "bench-export.jpg")
                put("export JPEG full size", t, timed { check(NativeBridge.exportJpeg(out.absolutePath, 95, 0, 0), "export") }.ms)
                out.delete()
                NativeBridge.close()
            } catch (e: Exception) {
                failures += "threads $t: ${e.message}"
                NativeBridge.close()
            }
        }
        full = null
        NativeBridge.setThreads(0)
        return format(settings, imageInfo, regionInfo, failures)
    }

    private fun format(settings: List<Int>, image: String, region: String, failures: List<String>): String = buildString {
        val rep = Engine.report
        appendLine("dt-android G0 benchmark (diagnostic test app, unofficial darktable port)")
        appendLine("app ${BuildConfigInfo.versionName(ctx)}, engine darktable ${NativeBridge.engineVersion()}")
        DeviceInfo.lines(ctx).forEach { appendLine(it) }
        appendLine("image: $image")
        appendLine("screen region: $region")
        if (rep != null) {
            appendLine("engine start: unpack ${if (rep.freshUnpack) "${rep.unpackMs} ms" else "(already unpacked)"}, dtb_init ${rep.initMs} ms, " +
                "${rep.pluginsLoaded} of ${rep.pluginsExpected} plugins loaded")
        }
        appendLine()
        appendLine("milliseconds")
        val head = "%-40s".format("") + settings.joinToString("") { "%12s".format(if (it == 1) "1 thread" else "$it threads") }
        appendLine(head)
        for (r in rows.values) {
            append("%-40s".format(r.label))
            for (t in settings) append("%12s".format(r.ms[t]?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "-"))
            appendLine()
        }
        if (failures.isNotEmpty()) {
            appendLine()
            appendLine("FAILURES")
            failures.forEach { appendLine(it) }
        }
        appendLine()
        appendLine("Each thread setting starts from the RAW file (image closed, caches dropped).")
    }
}

object BuildConfigInfo {
    fun versionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}

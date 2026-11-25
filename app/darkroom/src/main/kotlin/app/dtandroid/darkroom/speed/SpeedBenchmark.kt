// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.speed

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ParamValue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * Brief P4b deliverable 2: Settings › processing › "measure speed". A fixed sequence on the public test RAW
 * ([TestRaw]), so numbers from different phones and app versions compare: two edits (darktable's default edit, and a
 * heavier one: [HEAVY]), two views (the whole photo fitted into [FIT_W] x [FIT_H], and a [REGION] x [REGION] region at
 * 100 %), on the CPU and, when the GPU is on, on the GPU; [RUNS] runs each after a small exposure change (the time a
 * slider move costs: darktable recomputes the modules from exposure on), plus the first render after opening (RAW
 * decode included). The edit lives only in the engine's memory; nothing is written. The result is text the owner can
 * copy; nothing is sent.
 */
class SpeedBenchmark(
    private val engine: DarktableEngine,
    private val testRaw: String,
    /** run the GPU half too (the setting is on and its check passed) */
    private val gpu: Boolean,
    /** lines that describe the device and app, put first (no phone model unless the caller adds it) */
    private val header: List<String> = emptyList(),
) {
    data class Row(val device: String, val edit: String, val view: String, val firstMs: Long, val runsMs: List<Long>, val ranOn: String) {
        val medianMs: Long get() = runsMs.sorted()[runsMs.size / 2]
    }

    class Result(val rows: List<Row>, val text: String, val notes: List<String>)

    suspend fun run(progress: (String) -> Unit = {}): Result {
        val rows = ArrayList<Row>()
        val notes = ArrayList<String>()
        val devices = if (gpu) listOf("cpu", "gpu") else listOf("cpu")
        try {
            for (d in devices) {
                setGpu(d == "gpu", notes)
                for (edit in listOf("default", "heavy")) {
                    for (view in listOf("fit", "100%")) {
                        progress("$d, $edit edit, $view")
                        rows += measure(d, edit, view, notes)
                    }
                }
            }
        } finally {
            runCatching { engine.close() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            // the GPU as the setting says
            if (gpu) runCatching { engine.openclSet(true) }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
        return Result(rows, format(rows, notes), notes)
    }

    private suspend fun setGpu(on: Boolean, notes: MutableList<String>) {
        try { engine.openclSet(on) } catch (e: EngineException) {
            if (on) throw e
            // an engine without OpenCL renders on the CPU anyway
        }
    }

    private suspend fun measure(device: String, edit: String, view: String, notes: MutableList<String>): Row {
        runCatching { engine.close() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        engine.cacheDrop()
        engine.open(testRaw, null)
        if (edit == "heavy") heavy(notes)
        val info = engine.imageInfo()
        val fit = min(FIT_W.toDouble() / info.width, FIT_H.toDouble() / info.height)
        val fs = engine.renderSize(fit)
        val rw = min(REGION, info.width); val rh = min(REGION, info.height)
        suspend fun render() {
            if (view == "fit") engine.renderRegion(0, 0, fs.w, fs.h, fit)
            else engine.renderRegion((info.width - rw) / 2, (info.height - rh) / 2, rw, rh, 1.0)
        }
        val first = timed { render() }
        val exp0 = (engine.modules().firstOrNull { it.op == "exposure" && it.priority == 0 }?.params?.get("exposure") as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val runs = (1..RUNS).map { i ->
            engine.paramSet("exposure", 0, "exposure", ParamValue.of(exp0 + i * EXPOSURE_STEP))
            engine.historySeal()
            timed { render() }
        }
        val ranOn = engine.openclInfo().lastRender.takeIf { it != "none" } ?: "cpu"
        if (device == "gpu" && ranOn != "gpu") notes += "$edit, $view: asked for the GPU, ran as \"$ranOn\""
        engine.close()
        return Row(device, edit, view, first, runs, ranOn)
    }

    /** The heavier edit: diffuse or sharpen's "lens deblur | medium" preset, local contrast, color calibration, sigmoid. */
    private suspend fun heavy(notes: MutableList<String>) {
        try { engine.presetApply("diffuse", 0, HEAVY_DIFFUSE_PRESET) } catch (e: EngineException) {
            notes += "diffuse or sharpen: preset \"$HEAVY_DIFFUSE_PRESET\" not applied (${e.detail}); switched on with its defaults"
            engine.setEnabled("diffuse", 0, true)
        }
        for (op in HEAVY.drop(1)) engine.setEnabled(op, 0, true)
        engine.historySeal()
    }

    private inline fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun format(rows: List<Row>, notes: List<String>): String = buildString {
        append("dt-android speed (brief P4b): public test RAW mire1.cr2 (darktable-tests), measured on this device\n")
        for (h in header) append(h).append('\n')
        append("fit = whole photo in $FIT_W x $FIT_H; 100% = $REGION x $REGION at 100 %; heavy = default + ${HEAVY.joinToString(" + ")}\n")
        append("times in ms: first render after opening (RAW decode included) | $RUNS renders after an exposure change | median\n")
        for (r in rows) {
            append(String.format(Locale.ROOT, "%-3s %-7s %-4s first %6d | %s | median %d", r.device, r.edit, r.view, r.firstMs,
                r.runsMs.joinToString(" "), r.medianMs))
            if (r.ranOn != r.device) append(" (ran: ${r.ranOn})")
            append('\n')
        }
        for (n in notes) append("note: ").append(n).append('\n')
    }

    companion object {
        const val RUNS = 3
        /** the fit view's box: the target phone's screen (2400 x 1080) held sideways */
        const val FIT_W = 2400
        const val FIT_H = 1080
        /** the 100 % region's side: the target phone's screen width */
        const val REGION = 1080
        const val EXPOSURE_STEP = 0.02
        const val HEAVY_DIFFUSE_PRESET = "lens deblur | medium"
        /** the heavier edit's modules, after the default edit (darktable's names: diffuse or sharpen, local contrast, color calibration, sigmoid) */
        val HEAVY = listOf("diffuse", "bilat", "channelmixerrgb", "sigmoid")
    }
}

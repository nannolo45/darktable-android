// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.engine.FastCores
import app.dtandroid.engine.JniEngine
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.ParamValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.util.Locale

/**
 * The timings page's fixed sequence (brief P2b, deliverable 6), run on the open photo. Every number
 * is a measurement on the device that runs it; the text says so. The device model is not part of
 * the text (it is shown on the page only).
 *
 * The sequence closes the photo and opens it again from a snapshot of its edit, so it starts from
 * the RAW. Changes made for the measurements are thrown away at the end: the photo is opened again
 * from the snapshot (its edit is unchanged; its undo steps start afresh).
 */
class Timings(
    private val engine: JniEngine,
    private val controller: DarkroomController,
    private val scratch: File,
    private val screenW: Int,
    private val screenH: Int,
    private val appVersion: String,
    private val engineId: String,
    private val cores: FastCores?,
) {
    class Result(val text: String, val lines: List<Pair<String, String>>)

    private val lines = ArrayList<Pair<String, String>>()
    private fun put(k: String, v: String) { lines += k to v }
    private fun ms(ns: Long) = String.format(Locale.ROOT, "%.1f", ns / 1e6)

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0)
    }

    private fun median(v: List<Long>) = v.sorted()[v.size / 2]

    suspend fun run(item: SeriesItem, progress: (String) -> Unit): Result {
        lines.clear()
        scratch.mkdirs()
        put("what", "dt-android timings (brief P2b); numbers measured on this device")
        put("app", appVersion)
        put("engine package", engineId)
        controller.withEngine { e -> sequence(e, item, progress) }
        val text = buildString {
            for ((k, v) in lines) append(k).append(": ").append(v).append('\n')
        }
        return Result(text, lines.toList())
    }

    private suspend fun sequence(e: DarktableEngine, item: SeriesItem, progress: (String) -> Unit) {
        val info0 = runCatching { e.imageInfo() }.getOrNull()
        val readOnly = info0?.readOnly == true
        // the current edit as it is now, to start from the RAW and to come back to
        val snap = File(scratch, "timings-snapshot.xmp")
        snap.delete()
        val start: String? = if (!readOnly && info0 != null) { e.xmpWrite(snap.path); snap.path } else File(item.xmpPath).takeIf { it.isFile }?.path
        try {
            val ji = engine.engineInfoJson()
            put("darktable", (ji["darktable_version"] as? JsonPrimitive)?.content ?: "?")
            put("engine api", (ji["api_version"] as? JsonPrimitive)?.content ?: "?")
            put("threads", "${(ji["threads"] as? JsonPrimitive)?.content} of ${(ji["threads_max"] as? JsonPrimitive)?.content} at start" +
                "; pinned to CPUs ${(ji["thread_cpus"] as? JsonArray)?.joinToString(",") { (it as JsonPrimitive).content }?.ifEmpty { "none" }}")
            put("cores", cores?.describe() ?: "?")
            put("android api", android.os.Build.VERSION.SDK_INT.toString())
            put("photo", "${File(item.rawPath).length() / 1_000_000} MB RAW, ${File(item.rawPath).extension.uppercase(Locale.ROOT)}" +
                (if (readOnly) ", read-only (changes not measured)" else ""))

            progress("open")
            runCatching { e.close() }
            e.cacheDrop()
            val (_, tOpen) = timed { e.open(item.rawPath, start) }
            put("open ms", ms(tOpen))
            val (info, tInfo) = timed { e.imageInfo() }
            put("image info (loads the full image) ms", ms(tInfo))
            put("image", "${info.width} x ${info.height} processed, ${info.rawWidth} x ${info.rawHeight} sensor, ${info.imageClass.json}")

            progress("first preview")
            val (p0, tP0) = timed { e.renderPreview(PREVIEW) }
            put("first preview ${p0.width}x${p0.height} ms", ms(tP0))
            val (_, tP1) = timed { e.renderPreview(PREVIEW) }
            put("preview again, nothing changed ms", ms(tP1))

            if (!readOnly) {
                progress("module changes")
                val late = e.modules().first { it.op == "colorbalancergb" }
                val vib = (late.params["vibrance"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                e.paramSet("colorbalancergb", 0, "vibrance", ParamValue.of(vib + 0.05)); e.historySeal()
                val (_, tLate) = timed { e.renderPreview(PREVIEW) }
                put("preview after a late module change (color balance rgb) ms", ms(tLate))
                val exp0 = (e.modules().first { it.op == "exposure" && it.priority == 0 }.params["exposure"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                e.paramSet("exposure", 0, "exposure", ParamValue.of(exp0 + 0.1)); e.historySeal()
                val (_, tEarly) = timed { e.renderPreview(PREVIEW) }
                put("preview after an early module change (exposure) ms", ms(tEarly))

                progress("drag picture")
                // the size the app chooses from what it measured so far, then a fixed table of sizes
                val chosen = controller.dragPicture.size()
                val dragTimes = ArrayList<Long>()
                for (i in 1..6) {
                    e.paramSet("exposure", 0, "exposure", ParamValue.of(exp0 + 0.1 + i * 0.02))
                    val (img, t) = timed { e.renderPreview(chosen) }
                    controller.dragPicture.record(img.width * img.height, t, drag = true)
                    dragTimes += t
                }
                put("drag picture (chosen size $chosen px), 6 steps: median ms", "${ms(median(dragTimes))} (min ${ms(dragTimes.min())}, max ${ms(dragTimes.max())})")
                val table = StringBuilder()
                var k = 0
                for (size in DRAG_SIZES) {
                    val ts = ArrayList<Long>()
                    for (r in 0 until 3) {
                        e.paramSet("exposure", 0, "exposure", ParamValue.of(exp0 + 0.3 + (k++) * 0.01))
                        val (img, t) = timed { e.renderPreview(size) }
                        // these renders of several sizes let the drag picture's line be fitted (DragPicture)
                        controller.dragPicture.record(img.width * img.height, t, drag = true)
                        ts += t
                    }
                    table.append("$size px ${ms(median(ts))}; ")
                }
                e.historySeal()
                put("drag picture by size, median of 3 after an exposure step (ms)", table.toString().trimEnd(' ', ';'))
                put("drag picture chosen now", controller.dragPicture.describe())

                progress("sharp region")
                // what the darkroom renders after the finger lifts: the screen's size at fit, and at 100 %
                val fit = minOf(screenW.toDouble() / info.width, screenH.toDouble() / info.height).coerceAtMost(1.0)
                val fs = e.renderSize(fit)
                e.paramSet("exposure", 0, "exposure", ParamValue.of(exp0 + 0.2)); e.historySeal()
                val (_, tFit) = timed { e.renderRegion(0, 0, fs.w, fs.h, fit) }
                put("sharp region at fit ${fs.w}x${fs.h} after a change ms", ms(tFit))
                val w = minOf(screenW, info.width)
                val h = minOf(screenH, info.height)
                e.paramSet("exposure", 0, "exposure", ParamValue.of(exp0 + 0.25)); e.historySeal()
                val (_, t100) = timed { e.renderRegion((info.width - w) / 2, (info.height - h) / 2, w, h, 1.0) }
                put("sharp region at 100 % ${w}x$h after a change ms", ms(t100))
                put("region rendered in place (no JNI copy)", (!engine.lastRegionCopied).toString())
            }

            progress("modules()")
            val mt = ArrayList<JniEngine.ModulesTiming>()
            for (i in 0 until 5) { e.modules(); engine.lastModulesTiming?.let { mt += it } }
            if (mt.isNotEmpty()) {
                put("modules() size bytes", mt.last().bytes.toString())
                put("modules() bridge call ms (median of 5)", ms(median(mt.map { it.bridgeNs })))
                put("modules() JNI string ms (median of 5)", ms(median(mt.map { it.jniNs })))
                put("modules() Kotlin parse ms (median of 5)", ms(median(mt.map { it.parseNs })))
            }

            progress("export")
            val out = File(scratch, "timings-export.jpg")
            out.delete()
            val (_, tExp) = timed { e.export(out.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) }
            put("export full-size JPEG ms", ms(tExp))
            put("export size bytes", out.length().toString())
            out.delete()
        } catch (ex: EngineException) {
            put("stopped", "${ex.error}: ${ex.message}")
        } finally {
            progress("reopening the photo")
            runCatching { e.close() }
            try {
                e.open(item.rawPath, start)
            } catch (ex: EngineException) {
                put("reopen", "FAILED ${ex.message}")
            }
            snap.delete()
        }
    }

    companion object {
        const val PREVIEW = 1024
        val DRAG_SIZES = listOf(256, 384, 512, 640, 768, 1024)
    }
}

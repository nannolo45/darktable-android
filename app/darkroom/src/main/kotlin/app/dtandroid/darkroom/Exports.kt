// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ExportFormat
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.ExportProgress
import app.dtandroid.engine.api.ExportStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.math.roundToInt

/*
 * Brief P3g: the export screen's settings and the export under way (mockup B3-Export, ui-spec.md "export").
 * The engine reports its steps and "module n of N" (engine-api.md section 7); this adds the photos of a series,
 * the hand-off to the destination, and a cancel. The job runs in the darkroom's scope, so it goes on when the
 * export screen is closed (the darkroom's top bar shows it) and while the app is in the foreground elsewhere;
 * a series stops after the photo under way when the darkroom is left (it opens each photo in the darkroom).
 */

enum class ExportScope(val label: String) { PHOTO("this image"), SERIES("whole series") }

/** Where the files go. LightPhotoLibrary is not here: the app has no call for R5 yet (P3g report). */
enum class ExportTarget(val label: String, val note: String, val done: String, val delivering: String) {
    DEVICE("this device", "Saved on this device, in Pictures/darktable.", "exported to Pictures/darktable on this device", "saving to this device"),
    SHARE("share to an app", "Opens the Android share sheet when the export is done.", "exported · choose an app to share to", "getting it ready to share"),
}

/** darktable's "set size: in pixels" with a few choices; 0 = no limit, darktable's default (manual: max size). */
enum class ExportSize(val label: String, val longEdge: Int) {
    ORIGINAL("original size", 0), E2048("long edge 2048 px", 2048), E3840("long edge 3840 px", 3840),
}

/**
 * The export module's options the engine takes, with darktable's defaults: JPEG, quality 95 (manual), 8 bit,
 * no size limit, no upscaling, high quality resampling "no" and profile "image settings"
 * (data/darktableconfig.xml.in: plugins/lighttable/export/high_quality_processing false, icctype -1).
 */
data class ExportSettings(
    val scope: ExportScope = ExportScope.PHOTO,
    val target: ExportTarget = ExportTarget.DEVICE,
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 95,
    val bitDepth: Int = 8,
    val size: ExportSize = ExportSize.ORIGINAL,
    val highQuality: Boolean = false,
    val profile: ExportProfile = ExportProfile.IMAGE,
) {
    val lossy: Boolean get() = format == ExportFormat.JPEG || format == ExportFormat.WEBP
    val extension: String get() = when (format) {
        ExportFormat.JPEG -> "jpg"; ExportFormat.PNG -> "png"; ExportFormat.TIFF -> "tif"; ExportFormat.WEBP -> "webp"
    }
    val mime: String get() = when (format) {
        ExportFormat.JPEG -> "image/jpeg"; ExportFormat.PNG -> "image/png"; ExportFormat.TIFF -> "image/tiff"; ExportFormat.WEBP -> "image/webp"
    }

    fun options() = ExportOptions(
        format = format, quality = quality, bitDepth = bitDepth, maxWidth = size.longEdge, maxHeight = size.longEdge,
        upscale = false, highQuality = highQuality, profile = profile,
    )

    /** another format: the bit depth falls back to 8 where the format cannot hold it */
    fun withFormat(f: ExportFormat) = copy(format = f, bitDepth = if (bitDepth in bitDepths(f)) bitDepth else 8)

    companion object {
        val QUALITIES = listOf(100, 95, 90, 85, 80, 70, 60, 50)
        fun bitDepths(f: ExportFormat): List<Int> = when (f) {
            ExportFormat.JPEG, ExportFormat.WEBP -> listOf(8)
            ExportFormat.PNG -> listOf(8, 16)
            ExportFormat.TIFF -> listOf(8, 16, 32)
        }
        fun formatLabel(f: ExportFormat) = when (f) {
            ExportFormat.JPEG -> "JPEG"; ExportFormat.PNG -> "PNG"; ExportFormat.TIFF -> "TIFF"; ExportFormat.WEBP -> "WebP"
        }
        fun profileLabel(p: ExportProfile) = when (p) {
            ExportProfile.IMAGE -> "image settings"; ExportProfile.SRGB -> "sRGB"; ExportProfile.ADOBERGB -> "Adobe RGB"
            ExportProfile.DISPLAYP3 -> "Display P3"
        }
    }
}

enum class ExportPhase { RUNNING, DONE, CANCELLED, FAILED }

/** The export under way or just ended. [finished] counts photos exported and delivered, exactly. */
data class ExportJob(
    val phase: ExportPhase,
    val target: ExportTarget,
    val total: Int,
    val finished: Int = 0,
    val failed: Int = 0,
    /** 1-based: the photo being exported now */
    val number: Int = 1,
    val fileName: String = "",
    /** the current step in plain words */
    val step: String = STEP_PREPARING,
    /** share of the current photo done, 0..1 */
    val photoFraction: Double = 0.0,
    val message: String? = null,
) {
    /** the whole job's share done */
    val overall: Float get() {
        val current = if (phase == ExportPhase.RUNNING) photoFraction else 0.0
        return ((finished + failed + current) / total.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
    }
    val percent: Int get() = (overall * 100).roundToInt()
    val running: Boolean get() = phase == ExportPhase.RUNNING
    val series: Boolean get() = total > 1

    /** BatchProgress's label: "photo 3 of 12" for a series, the file name for one photo; the end state after */
    val label: String get() = when (phase) {
        ExportPhase.RUNNING -> if (series) "photo $number of $total" else "exporting $fileName"
        ExportPhase.DONE -> if (failed == 0) target.done else "$finished of $total exported · $failed failed"
        ExportPhase.CANCELLED -> "cancelled · " + if (series) "$finished of $total exported" else "nothing written"
        ExportPhase.FAILED -> "export failed"
    }

    val photoPercent: Int get() = (photoFraction * 100).roundToInt()

    /** the line under the main bar: one photo: its step and percent; a series: photos finished and the overall percent */
    val stepLine: String get() = when (phase) {
        ExportPhase.RUNNING -> if (series) "$finished finished · $percent %" else "$step · $percent %"
        else -> message ?: ""
    }

    /** a series: the line under the current photo's bar */
    val photoLine: String get() = "$step · $photoPercent %"

    /** the darkroom's top-bar indicator */
    val short: String get() = when (phase) {
        ExportPhase.RUNNING -> if (series) "export $number/$total · $percent %" else "export $percent %"
        ExportPhase.DONE -> if (failed == 0) "exported" else "export: $failed failed"
        ExportPhase.CANCELLED -> "export cancelled"
        ExportPhase.FAILED -> "export failed"
    }

    companion object {
        const val STEP_PREPARING = "preparing"
        const val STEP_WRITING = "writing the file"
        const val STEP_CANCELLING = "cancelling…"
        const val STEP_LPL = "sending to LightPhotoLibrary"

        /** The engine's step in plain words (engine-api.md section 7). */
        fun words(p: ExportProgress): String = when (p.step) {
            ExportStep.PREPARING -> STEP_PREPARING
            ExportStep.PROCESSING -> if (p.modules > 0) "processing · module ${p.module} of ${p.modules}" else "processing"
            ExportStep.WRITING, ExportStep.DONE -> STEP_WRITING
        }
    }
}

/**
 * The host's side of an export: a folder for the engine's file, and the hand-off of each file to the chosen
 * [ExportTarget]. [deliver] runs off the main thread and throws when the file cannot be delivered; what it
 * returns (a content URI, a path) is passed to [finished] after the whole job, for the share sheet.
 */
interface ExportDelivery {
    val scratch: File
    suspend fun deliver(file: File, name: String, mime: String, target: ExportTarget): String
    fun finished(target: ExportTarget, mime: String, delivered: List<String>) {}
}

class Exports internal constructor(private val c: DarkroomController) {
    /** set by the app; null (screenshots, tests without one) = the export button says so */
    @Volatile var delivery: ExportDelivery? = null

    private val _settings = MutableStateFlow(ExportSettings())
    val settings: StateFlow<ExportSettings> = _settings.asStateFlow()

    private val _job = MutableStateFlow<ExportJob?>(null)
    val job: StateFlow<ExportJob?> = _job.asStateFlow()

    @Volatile private var cancelled = false

    fun set(change: (ExportSettings) -> ExportSettings) = _settings.update(change)

    /** The export screen. */
    fun open() = c.setOverlay(Overlay.Export)

    /** For screenshots and tests: show a job as it would be. */
    fun showJob(j: ExportJob?) { _job.value = j }

    /** Start the export with the current settings; false when one runs, no photo is open or there is no destination. */
    fun start(): Boolean {
        if (_job.value?.running == true) return false
        val st = c.state.value
        if (st.info == null) return false
        val d = delivery
        val s = _settings.value
        if (d == null) {
            _job.value = ExportJob(ExportPhase.FAILED, s.target, 1, message = "this build has no place to export to")
            return false
        }
        val series = st.series
        val home = st.index
        val indices = if (s.scope == ExportScope.SERIES && series.items.size > 1) series.items.indices.toList() else listOf(home)
        cancelled = false
        _job.value = ExportJob(ExportPhase.RUNNING, s.target, indices.size, fileName = series.items.getOrNull(indices[0])?.fileName ?: "")
        c.launch { runJob(s, d, series.name, series.items.size, indices, home) }
        return true
    }

    /** Stop the export: the engine stops between modules and writes nothing; a series stops there too. */
    fun cancel() {
        if (_job.value?.running != true) return
        cancelled = true
        _job.update { it?.copy(step = ExportJob.STEP_CANCELLING) }
        c.exportCancel()
    }

    /** Forget an ended job (the indicator goes away). */
    fun dismiss() { if (_job.value?.running != true) _job.value = null }

    private fun sameSeries(name: String, size: Int): Boolean {
        val s = c.state.value
        return s.info != null && s.series.name == name && s.series.items.size == size
    }

    private suspend fun runJob(s: ExportSettings, d: ExportDelivery, seriesName: String, seriesSize: Int, indices: List<Int>, home: Int) {
        val delivered = ArrayList<String>()
        var finished = 0
        var failed = 0
        var lastError: String? = null
        var stopped: String? = null
        for ((k, i) in indices.withIndex()) {
            if (cancelled) break
            if (!sameSeries(seriesName, seriesSize)) { stopped = "stopped: the darkroom was left · $finished of ${indices.size} exported"; break }
            if (c.state.value.index != i) c.openPhoto(i)
            val now = c.state.value
            val item = now.series.items.getOrNull(i)
            _job.update { it?.copy(number = k + 1, fileName = item?.fileName ?: "", step = ExportJob.STEP_PREPARING, photoFraction = 0.0) }
            if (cancelled) break
            if (item == null || now.index != i || now.info == null) {
                failed++
                lastError = "${item?.fileName ?: "photo ${i + 1}"} could not be opened"
                _job.update { it?.copy(failed = failed) }
                continue
            }
            val name = item.fileName.substringBeforeLast('.') + "." + s.extension
            val tmp = File(d.scratch, name)
            var stop = false
            try {
                d.scratch.mkdirs()
                c.export(tmp.path, s.options()) { p ->
                    _job.update { j -> if (j == null || cancelled) j else j.copy(step = ExportJob.words(p), photoFraction = p.fraction * ENGINE_SHARE) }
                }
                if (cancelled) {
                    stop = true
                } else {
                    _job.update { it?.copy(step = s.target.delivering, photoFraction = ENGINE_SHARE) }
                    delivered += d.deliver(tmp, name, s.mime, s.target)
                    finished++
                }
            } catch (e: EngineException) {
                if (e.error == EngineError.CANCELLED || cancelled) stop = true
                else { failed++; lastError = "${item.fileName}: ${e.detail}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                lastError = "${item.fileName}: the file could not be saved (${e.message})"
            } finally {
                tmp.delete()
            }
            _job.update { it?.copy(finished = finished, failed = failed) }
            if (stop) break
        }
        // a series: back to the photo the export started from
        if (indices.size > 1 && sameSeries(seriesName, seriesSize) && c.state.value.index != home) c.openPhoto(home)
        val phase = when {
            cancelled -> ExportPhase.CANCELLED
            stopped != null -> ExportPhase.CANCELLED
            finished == 0 -> ExportPhase.FAILED
            else -> ExportPhase.DONE
        }
        _job.update {
            it?.copy(phase = phase, finished = finished, failed = failed, photoFraction = 0.0,
                message = stopped ?: lastError ?: if (phase == ExportPhase.DONE) "${Words.count(finished, "photo")} · ${s.target.done}" else null)
        }
        if (phase != ExportPhase.FAILED && delivered.isNotEmpty() && !cancelled) d.finished(s.target, s.mime, delivered)
    }

    companion object {
        /** the engine's part of one photo; the hand-off to the destination is the rest */
        const val ENGINE_SHARE = 0.95
    }
}

/** A delivery into a plain folder, whatever the target: the stand-in app and tests. */
class FolderExportDelivery(override val scratch: File, private val folder: File) : ExportDelivery {
    val finishedWith = java.util.concurrent.CopyOnWriteArrayList<Pair<ExportTarget, List<String>>>()
    override suspend fun deliver(file: File, name: String, mime: String, target: ExportTarget): String {
        folder.mkdirs()
        val out = File(folder, name)
        file.copyTo(out, overwrite = true)
        return out.path
    }
    override fun finished(target: ExportTarget, mime: String, delivered: List<String>) { finishedWith += target to delivered }
}

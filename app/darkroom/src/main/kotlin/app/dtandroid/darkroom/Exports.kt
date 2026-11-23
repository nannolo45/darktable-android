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
 * brief P3i: a series no longer opens each photo in the darkroom: it runs on engine sessions of its own and
 * does not stop when the darkroom is left.
 */

enum class ExportScope(val label: String) { PHOTO("this image"), SERIES("whole series") }

/** Where the files go. LightPhotoLibrary (brief P3i): the photo's own server, request R18; offered only when [ExportDelivery.lplOffer] says so. */
enum class ExportTarget(val label: String, val note: String, val done: String, val delivering: String) {
    DEVICE("this device", "Saved on this device, in Pictures/darktable.", "exported to Pictures/darktable on this device", "saving to this device"),
    SHARE("share to an app", "Opens the Android share sheet when the export is done.", "exported · choose an app to share to", "getting it ready to share"),
    LPL("LightPhotoLibrary", "Sent to the server this photo came from, with its version; if the server cannot be reached the export waits and is sent with the next sync.",
        "sent to LightPhotoLibrary", ExportJob.STEP_LPL),
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
    val quality: Int = QUALITY_DEFAULT,
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

    /** brief P3i: quality as desktop's slider, 5..100 (whole numbers) */
    fun withQuality(q: Int) = copy(quality = q.coerceIn(QUALITY_MIN, QUALITY_MAX))

    /** another format: the bit depth falls back to 8 where the format cannot hold it */
    fun withFormat(f: ExportFormat) = copy(format = f, bitDepth = if (bitDepth in bitDepths(f)) bitDepth else 8)

    companion object {
        const val QUALITY_MIN = 5
        const val QUALITY_MAX = 100
        /** darktable's default (manual: export, JPEG quality) */
        const val QUALITY_DEFAULT = 95
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

/** Brief P3i: what the export's notification says. [percent] is the whole job's share done. */
data class ExportNotice(val title: String, val text: String, val percent: Int, val ongoing: Boolean)

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
    /** brief P3i: photos handed to the LightPhotoLibrary queue instead of sent (the server could not be reached) */
    val queued: Int = 0,
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
        ExportPhase.DONE -> if (failed == 0) doneText else "$finished of $total exported · $failed failed"
        ExportPhase.CANCELLED -> "cancelled · " + if (series) "$finished of $total exported" else "nothing written"
        ExportPhase.FAILED -> "export failed"
    }

    /** the end line of a good job; for LightPhotoLibrary it says when photos wait for the server */
    val doneText: String get() = when {
        target == ExportTarget.LPL && queued > 0 && queued >= finished -> "waiting for LightPhotoLibrary · sent with the next sync"
        target == ExportTarget.LPL && queued > 0 -> "${finished - queued} sent to LightPhotoLibrary · $queued waiting for the next sync"
        else -> target.done
    }

    val photoPercent: Int get() = (photoFraction * 100).roundToInt()

    /** the line under the main bar: one photo: its step and percent; a series: photos finished and the overall percent */
    val stepLine: String get() = when (phase) {
        ExportPhase.RUNNING -> if (series) "$finished finished · $percent %" else "$step · $percent %"
        else -> message ?: ""
    }

    /** a series: the line under the current photo's bar */
    val photoLine: String get() = "$step · $photoPercent %"

    /** brief P3i: the Android notification of the job (the foreground service shows it): title, text and the bar */
    val notice: ExportNotice get() = when (phase) {
        ExportPhase.RUNNING -> ExportNotice(
            title = if (series) "Exporting photo $number of $total" else "Exporting $fileName",
            text = if (series) "$fileName · $step" else "$step · $percent %",
            percent = percent, ongoing = true)
        else -> ExportNotice(title = label, text = message ?: "", percent = 100, ongoing = false)
    }

    /** the darkroom's top-bar indicator */
    val short: String get() = when (phase) {
        ExportPhase.RUNNING -> if (series) "export $number/$total · $percent %" else "export $percent %"
        ExportPhase.DONE -> if (failed == 0) "exported" else "export: $failed failed"
        ExportPhase.CANCELLED -> "export cancelled"
        ExportPhase.FAILED -> "export failed"
    }

    /** the phone's top bar, where the photo's name needs the room: the numbers only */
    val compact: String get() = when (phase) {
        ExportPhase.RUNNING -> if (series) "$number/$total · $percent %" else "$percent %"
        ExportPhase.DONE -> if (failed == 0) "exported" else "$failed failed"
        ExportPhase.CANCELLED -> "cancelled"
        ExportPhase.FAILED -> "failed"
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

/** Brief P3i: whether the LightPhotoLibrary destination is on offer for a photo, and if not, why (one line). */
sealed interface LplOffer {
    data object Available : LplOffer
    data class Unavailable(val reason: String) : LplOffer
}

/** What [ExportDelivery.deliver] hands back: where the file went, and whether it only waits to be sent ([queued]). */
data class Delivered(val ref: String, val queued: Boolean = false)

/**
 * The host's side of an export: a folder for the engine's file, and the hand-off of each file to the chosen
 * [ExportTarget]. [deliver] runs off the main thread and throws when the file cannot be delivered; what it
 * returns (a content URI, a path) is passed to [finished] after the whole job, for the share sheet.
 */
interface ExportDelivery {
    val scratch: File
    /** [item] is the photo exported (its LightPhotoLibrary id and version are found from it); [progress] takes the share sent, 0..1 */
    suspend fun deliver(file: File, name: String, mime: String, target: ExportTarget, item: SeriesItem, progress: (Float) -> Unit): Delivered
    fun finished(target: ExportTarget, mime: String, delivered: List<String>) {}

    /** brief P3i: the LightPhotoLibrary destination for [item]; by default not on offer */
    fun lplOffer(item: SeriesItem?): LplOffer = LplOffer.Unavailable("this build cannot send to LightPhotoLibrary")

    /** brief P3i: a new number whenever the answer of [lplOffer] may have changed (a heartbeat answered) */
    val lplOfferChanges: kotlinx.coroutines.flow.Flow<Int> get() = kotlinx.coroutines.flow.emptyFlow()
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
        if (s.target == ExportTarget.LPL) {
            val offer = d.lplOffer(st.current)
            if (offer is LplOffer.Unavailable) {
                _job.value = ExportJob(ExportPhase.FAILED, s.target, 1, message = offer.reason)
                return false
            }
        }
        val indices = if (s.scope == ExportScope.SERIES && series.items.size > 1) series.items.indices.toList() else listOf(home)
        cancelled = false
        _job.value = ExportJob(ExportPhase.RUNNING, s.target, indices.size, fileName = series.items.getOrNull(indices[0])?.fileName ?: "")
        if (indices.size > 1) c.launch { runSeries(s, d, indices.map { series.items[it] }) }
        else c.launch { runJob(s, d) }
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

    /** What a job has done so far, exactly. */
    private class Tally {
        var finished = 0
        var failed = 0
        var queued = 0
        var lastError: String? = null
        val delivered = ArrayList<String>()
    }

    /**
     * Export one photo with [engineExport] and hand the file to the destination. True when the job must stop
     * (a cancel). The photo's share of the bar starts again from 0 in the same update that counts it, so the bar
     * never goes back.
     */
    private suspend fun one(
        s: ExportSettings, d: ExportDelivery, item: SeriesItem, t: Tally,
        engineExport: suspend (String, app.dtandroid.engine.api.ExportOptions, (ExportProgress) -> Unit) -> Unit,
    ): Boolean {
        val share = engineShare(s.target)
        val name = item.fileName.substringBeforeLast('.') + "." + s.extension
        val tmp = File(d.scratch, name)
        var stop = false
        try {
            d.scratch.mkdirs()
            engineExport(tmp.path, s.options()) { p ->
                _job.update { j -> if (j == null || cancelled) j else j.copy(step = ExportJob.words(p), photoFraction = p.fraction * share) }
            }
            if (cancelled) {
                stop = true
            } else {
                _job.update { it?.copy(step = s.target.delivering, photoFraction = share) }
                val r = d.deliver(tmp, name, s.mime, s.target, item) { f ->
                    _job.update { j -> if (j == null || cancelled) j else j.copy(photoFraction = share + (1 - share) * f.coerceIn(0f, 1f)) }
                }
                t.delivered += r.ref
                if (r.queued) t.queued++
                t.finished++
            }
        } catch (e: EngineException) {
            if (e.error == EngineError.CANCELLED || cancelled) stop = true
            else { t.failed++; t.lastError = "${item.fileName}: ${e.detail}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            t.failed++
            t.lastError = "${item.fileName}: ${deliveryProblem(s.target, e)}"
        } finally {
            tmp.delete()
        }
        _job.update { it?.copy(finished = t.finished, failed = t.failed, queued = t.queued, photoFraction = 0.0) }
        return stop
    }

    private fun deliveryProblem(target: ExportTarget, e: Exception) =
        if (target == ExportTarget.LPL) "could not be sent to LightPhotoLibrary (${e.message})" else "the file could not be saved (${e.message})"

    /** One photo: the open photo, through the darkroom's own session. */
    private suspend fun runJob(s: ExportSettings, d: ExportDelivery) {
        val t = Tally()
        val item = c.state.value.current
        if (item == null || c.state.value.info == null) {
            t.failed++
            t.lastError = "no photo is open"
            _job.update { it?.copy(failed = t.failed) }
        } else {
            one(s, d, item, t) { path, o, p -> c.export(path, o, p) }
        }
        end(s, d, t, null)
    }

    /**
     * Brief P3i: a series. The photos are fetched first when they are not on the phone (outside the engine's lock),
     * then each is opened, exported and closed on an engine session of its own ([DarkroomController.withExportSessions]):
     * nothing is shown in the darkroom, no activity is needed, and the job lives in the controller's scope, so it
     * goes on with the app in the background (the foreground service keeps the process).
     */
    private suspend fun runSeries(s: ExportSettings, d: ExportDelivery, items: List<SeriesItem>) {
        val t = Tally()
        val ready = arrayOfNulls<SeriesItem>(items.size)
        for ((k, item) in items.withIndex()) {
            if (cancelled) break
            if (!c.needsFetch(item)) { ready[k] = item; continue }
            _job.update { it?.copy(number = k + 1, fileName = item.fileName, step = ExportJob.STEP_PREPARING, photoFraction = 0.0) }
            try {
                ready[k] = c.fetchForExport(item) { text, _ -> _job.update { j -> if (j == null || cancelled) j else j.copy(step = text) } }
            } catch (e: PhotoSourceException) {
                t.failed++
                t.lastError = "${item.fileName}: ${e.message}"
                _job.update { it?.copy(failed = t.failed) }
            }
        }
        var stopped: String? = null
        val pending = ArrayList<Pending>()
        if (!cancelled && ready.any { it != null }) {
            try {
                // brief P3i: under the engine's lock the files are only made; they are handed over after it is released,
                // so the darkroom's actions never wait for a network ([deliverPending])
                c.withExportSessions { run ->
                    for ((k, item) in ready.withIndex()) {
                        if (item == null) continue
                        if (cancelled) break
                        _job.update { it?.copy(number = k + 1, fileName = item.fileName, step = ExportJob.STEP_PREPARING, photoFraction = 0.0) }
                        val name = item.fileName.substringBeforeLast('.') + "." + s.extension
                        val tmp = File(File(d.scratch, "s$k"), name)
                        var stop = false
                        try {
                            tmp.parentFile?.mkdirs()
                            val share = engineShare(s.target)
                            run.export(item, tmp.path, s.options()) { p ->
                                _job.update { j -> if (j == null || cancelled) j else j.copy(step = ExportJob.words(p), photoFraction = p.fraction * share) }
                            }
                            if (cancelled) { tmp.delete(); stop = true } else {
                                // provisional: counted as done now so the bar never goes back; [deliverPending] corrects a failed hand-over
                                t.finished++
                                pending += Pending(item, name, tmp)
                            }
                        } catch (e: EngineException) {
                            tmp.delete()
                            if (e.error == EngineError.CANCELLED || cancelled) stop = true
                            else { t.failed++; t.lastError = "${item.fileName}: ${e.detail}" }
                        }
                        _job.update { it?.copy(finished = t.finished, failed = t.failed, photoFraction = 0.0) }
                        if (stop) break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopped = "stopped: ${e.message} · ${t.finished} of ${items.size} exported"
            }
        }
        deliverPending(s, d, t, pending)
        end(s, d, t, stopped)
    }

    private class Pending(val item: SeriesItem, val name: String, val tmp: File)

    /** Hand the series' files to the destination, after the engine's lock is released. A failure or a cancel un-counts the photo. */
    private suspend fun deliverPending(s: ExportSettings, d: ExportDelivery, t: Tally, pending: List<Pending>) {
        val share = engineShare(s.target)
        // photos made before a cancel are still handed over (as before); a cancel during the hand-over drops the rest
        val cancelledBefore = cancelled
        try {
            for (p in pending) {
                if (cancelled && !cancelledBefore) { t.finished--; continue }
                _job.update { it?.copy(fileName = p.item.fileName, step = s.target.delivering, photoFraction = share) }
                try {
                    val r = d.deliver(p.tmp, p.name, s.mime, s.target, p.item) { f ->
                        _job.update { j -> if (j == null || cancelled) j else j.copy(photoFraction = share + (1 - share) * f.coerceIn(0f, 1f)) }
                    }
                    t.delivered += r.ref
                    if (r.queued) t.queued++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    t.finished--
                    t.failed++
                    t.lastError = "${p.item.fileName}: ${deliveryProblem(s.target, e)}"
                }
                _job.update { it?.copy(finished = t.finished, failed = t.failed, queued = t.queued, photoFraction = 0.0) }
            }
        } finally {
            for (p in pending) p.tmp.delete()
        }
    }

    private fun end(s: ExportSettings, d: ExportDelivery, t: Tally, stopped: String?) {
        val phase = when {
            cancelled -> ExportPhase.CANCELLED
            stopped != null -> ExportPhase.CANCELLED
            t.finished == 0 -> ExportPhase.FAILED
            else -> ExportPhase.DONE
        }
        _job.update { j ->
            if (j == null) null
            else j.copy(phase = phase, finished = t.finished, failed = t.failed, queued = t.queued, photoFraction = 0.0,
                message = stopped ?: t.lastError ?: if (phase == ExportPhase.DONE) "${Words.count(t.finished, "photo")} · ${j.doneText}" else null)
        }
        if (phase != ExportPhase.FAILED && t.delivered.isNotEmpty() && !cancelled) d.finished(s.target, s.mime, t.delivered)
    }

    companion object {
        /** the engine's part of one photo; the hand-off to the destination is the rest */
        const val ENGINE_SHARE = 0.95

        /** sending to a server takes a larger share of the bar than a copy on the phone */
        fun engineShare(target: ExportTarget) = if (target == ExportTarget.LPL) 0.7 else ENGINE_SHARE
    }
}

/** A delivery into a plain folder, whatever the target: the stand-in app and tests. */
class FolderExportDelivery(override val scratch: File, private val folder: File) : ExportDelivery {
    val finishedWith = java.util.concurrent.CopyOnWriteArrayList<Pair<ExportTarget, List<String>>>()
    override suspend fun deliver(file: File, name: String, mime: String, target: ExportTarget, item: SeriesItem, progress: (Float) -> Unit): Delivered {
        folder.mkdirs()
        val out = File(folder, name)
        file.copyTo(out, overwrite = true)
        return Delivered(out.path)
    }
    override fun finished(target: ExportTarget, mime: String, delivered: List<String>) { finishedWith += target to delivered }
}

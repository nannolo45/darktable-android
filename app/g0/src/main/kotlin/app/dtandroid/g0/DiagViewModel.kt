// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.g0

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dtandroid.engine.Engine
import app.dtandroid.engine.EngineStartReport
import app.dtandroid.engine.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** State and actions of the one diagnostic screen. All engine work goes through [Engine]. */
class DiagViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val work = File(ctx.filesDir, "work").apply { mkdirs() }

    var startStatus by mutableStateOf("starting the engine ...")
    var report by mutableStateOf<EngineStartReport?>(null)
    var startError by mutableStateOf<String?>(null)

    var rawFile by mutableStateOf<File?>(null)
    var xmpFile by mutableStateOf<File?>(null)
    var xmpCheckText by mutableStateOf<String?>(null)
    var sessionOpen by mutableStateOf(false)
    var readOnly by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)

    var preview by mutableStateOf<Bitmap?>(null)
    var previewMs by mutableStateOf<Double?>(null)
    var region by mutableStateOf<Bitmap?>(null)
    var regionMs by mutableStateOf<Double?>(null)
    var regionDesc by mutableStateOf("")
    var exposure by mutableStateOf(0f)
    var exposureBase by mutableStateOf(0f)
    var modules = mutableStateListOf<ModuleRow>()
    var exportText by mutableStateOf<String?>(null)
    var benchmarkText by mutableStateOf<String?>(null)
    val logLines = mutableStateListOf<String>()

    var screenW = 1080
    var screenH = 2400

    private val renderSignal = Channel<Unit>(Channel.CONFLATED)
    private val generation = AtomicInteger(0)
    @Volatile private var wantedExposure: Float? = null
    @Volatile private var appliedExposure: Float? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val r = try {
                Engine.start(ctx) { line -> viewModelScope.launch { appLog(line) } }
            } catch (e: Throwable) {
                startError = "engine start crashed: ${e.cause ?: e}"
                startStatus = "engine start FAILED"
                return@launch
            }
            report = r
            startStatus = if (r.ok) "engine ready" else "engine start FAILED"
        }
        viewModelScope.launch { pollLog() }
        viewModelScope.launch { renderLoop() }
    }

    private fun appLog(line: String) {
        logLines += "[app] $line"
    }

    private suspend fun pollLog() {
        var since = 0L
        val next = LongArray(1)
        while (true) {
            delay(500)
            if (Engine.report == null || startError != null && report == null) continue
            val text = try {
                NativeBridge.logSince(since, next)
            } catch (e: UnsatisfiedLinkError) {
                null
            } ?: continue
            since = next[0]
            if (text.isNotEmpty()) {
                logLines += text.trimEnd('\n').split('\n')
                if (logLines.size > 3000) logLines.removeRange(0, logLines.size - 3000)
            }
        }
    }

    fun logText(): String = logLines.joinToString("\n")

    private fun engineOk() = report?.ok == true || (report?.initCode == 0 && report?.fatal == null)

    // ---- picking files -------------------------------------------------

    private fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "image"
    }

    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "image" }

    /** Copy the chosen RAW into app storage (the original is only read). */
    fun pickRaw(uri: Uri) = viewModelScope.launch {
        busy = "copying the RAW into app storage ..."
        try {
            val f = withContext(Dispatchers.IO) {
                closeSession()
                work.listFiles()?.forEach { it.delete() }
                val dst = File(work, safeName(displayName(uri)))
                ctx.contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { i.copyTo(it) } }
                dst
            }
            rawFile = f
            xmpFile = null
            xmpCheckText = null
            message = "RAW copied: ${f.name}, ${f.length() / 1_000_000.0} MB. Pick its XMP (optional), then Open."
        } catch (e: Exception) {
            message = "could not copy the RAW: $e"
        } finally {
            busy = null
        }
    }

    fun pickXmp(uri: Uri) = viewModelScope.launch {
        val raw = rawFile ?: run { message = "pick the RAW first"; return@launch }
        try {
            val f = withContext(Dispatchers.IO) {
                val dst = File(work, raw.name + ".xmp")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { i.copyTo(it) } }
                dst
            }
            xmpFile = f
            xmpCheckText = Engine.call { NativeBridge.xmpCheck(f.absolutePath) ?: "cannot read: ${NativeBridge.lastError()}" }
                .let { prettyCheck(it) }
        } catch (e: Exception) {
            message = "could not copy the XMP: $e"
        }
    }

    private fun prettyCheck(json: String): String = try {
        val o = JSONObject(json)
        if (o.getBoolean("ok")) {
            "XMP check: ok (${o.optInt("entries")} history entries)"
        } else {
            val p = o.getJSONArray("problems")
            "XMP check: FAILED, the photo will open read-only.\n" + (0 until p.length()).joinToString("\n") {
                val x = p.getJSONObject(it)
                "  ${x.optString("operation")} v${x.optInt("modversion")}: ${x.optString("reason")}"
            }
        }
    } catch (e: Exception) {
        json
    }

    private fun closeSession() {
        if (sessionOpen) {
            NativeBridge.renderCancel()
            Engine.callBlocking { NativeBridge.close() }
            sessionOpen = false
        }
    }

    fun openImage() = viewModelScope.launch {
        val raw = rawFile ?: return@launch
        busy = "opening ..."
        try {
            val t = Engine.call {
                NativeBridge.close()
                timed { check(NativeBridge.open(raw.absolutePath, xmpFile?.absolutePath), "open") }
            }
            readOnly = Engine.call { NativeBridge.isReadOnly() }
            sessionOpen = true
            exposureBase = Engine.call { EngineOps.exposure() }
            exposure = exposureBase
            wantedExposure = null
            appliedExposure = exposureBase
            refreshModules()
            message = "opened in ${"%.0f".format(t.ms)} ms" + if (readOnly) " - READ-ONLY: the XMP failed the version check" else ""
            requestRender()
        } catch (e: Exception) {
            message = "open failed: ${e.message}"
        } finally {
            busy = null
        }
    }

    private suspend fun refreshModules() {
        val m = Engine.call { EngineOps.historyModules() }
        modules.clear()
        modules.addAll(m)
    }

    // ---- rendering -----------------------------------------------------

    fun onExposure(v: Float) {
        exposure = v
        wantedExposure = v
        requestRender()
    }

    fun onExposureDone() = viewModelScope.launch { Engine.call { NativeBridge.historySeal() } }

    fun toggle(row: ModuleRow, on: Boolean) = viewModelScope.launch {
        try {
            Engine.call { check(NativeBridge.setEnabled(row.op, row.prio, on), "switch ${row.name}") }
            refreshModules()
            requestRender()
        } catch (e: Exception) {
            message = e.message
        }
    }

    /** A new change: cancel the render in flight and render again (preview first, then the region). */
    private fun requestRender() {
        generation.incrementAndGet()
        NativeBridge.renderCancel()
        renderSignal.trySend(Unit)
    }

    private suspend fun renderLoop() {
        for (signal in renderSignal) {
            while (sessionOpen) {
                val gen = generation.get()
                try {
                    val p = Engine.call {
                        wantedExposure?.let {
                            if (it != appliedExposure) { EngineOps.setExposure(it); appliedExposure = it }
                        }
                        timed { EngineOps.preview() }
                    }
                    preview = p.value
                    previewMs = p.ms
                    if (gen != generation.get()) continue
                    val reg = Engine.call { EngineOps.centredRegion(screenW, screenH) }
                    regionDesc = "${reg.w} x ${reg.h} px at 100 % from the centre (rendered with a ${EngineOps.REGION_MARGIN} px margin, then cropped)"
                    val r = Engine.call { timed { EngineOps.region(reg) } }
                    if (gen != generation.get()) continue
                    region = r.value
                    regionMs = r.ms
                } catch (e: BridgeException) {
                    if (e.cancelled && gen != generation.get()) continue
                    message = e.message
                } catch (e: Exception) {
                    message = "render failed: $e"
                }
                break
            }
        }
    }

    // ---- output ----------------------------------------------------------

    fun exportJpeg() = viewModelScope.launch {
        val raw = rawFile ?: return@launch
        busy = "exporting at full size ..."
        try {
            val tmp = File(ctx.cacheDir, "export.jpg")
            val t = Engine.call {
                tmp.delete()
                timed { check(NativeBridge.exportJpeg(tmp.absolutePath, 95, 0, 0), "export") }
            }
            val name = raw.nameWithoutExtension + "-dt-android.jpg"
            val uri = withContext(Dispatchers.IO) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/dt-android")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val r = ctx.contentResolver
                val u = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                r.openOutputStream(u)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                r.update(u, cv, null, null)
                tmp.delete()
                u
            }
            exportText = String.format(Locale.ROOT, "exported in %.0f ms to Pictures/dt-android/%s", t.ms, name)
            appLog("export written: $uri")
        } catch (e: Exception) {
            exportText = "export failed: ${e.message}"
        } finally {
            busy = null
        }
    }

    fun suggestedXmpName(): String = (rawFile?.name ?: "image") + ".xmp"

    /** Write the session's XMP and copy it to the place the user chose in the system dialog. */
    fun saveXmp(uri: Uri) = viewModelScope.launch {
        try {
            val tmp = File(ctx.cacheDir, "save.xmp")
            Engine.call {
                tmp.delete()
                check(NativeBridge.xmpWrite(tmp.absolutePath), "write XMP")
            }
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                tmp.delete()
            }
            message = "XMP saved"
        } catch (e: Exception) {
            message = "XMP not saved: ${e.message}"
        }
    }

    fun runBenchmark() = viewModelScope.launch {
        val raw = rawFile ?: return@launch
        busy = "benchmark running (a few minutes) ..."
        benchmarkText = null
        try {
            NativeBridge.renderCancel()
            val text = Engine.call {
                // reopen from a snapshot of the current edit each round; a
                // read-only session keeps using the XMP that was picked
                val snap = if (NativeBridge.isReadOnly()) xmpFile else
                    File(ctx.cacheDir, "bench-snapshot.xmp").also { it.delete(); check(NativeBridge.xmpWrite(it.absolutePath), "snapshot") }
                val t = Benchmark(ctx, raw, snap, screenW, screenH) { line -> NativeBridge.logLine("[app] $line") }.run()
                NativeBridge.close()
                check(NativeBridge.open(raw.absolutePath, snap?.absolutePath), "reopen")
                t
            }
            benchmarkText = text
            exposure = Engine.call { EngineOps.exposure() }
            wantedExposure = null
            appliedExposure = exposure
            refreshModules()
            requestRender()
        } catch (e: Exception) {
            benchmarkText = "benchmark failed: ${e.message}"
        } finally {
            busy = null
        }
    }
}

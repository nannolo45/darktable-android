// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.FileThumbnailStore
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.work.WorkFolder
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.FileLayoutStore
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.schema.SchemaLoader
import app.dtandroid.schema.layout.BuiltinLayouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which screen the app shows. */
enum class Screen { LIBRARY, DARKROOM, ABOUT, TIMINGS }

/** The working folder's photos as the library screen lists them. */
data class LibraryState(
    val items: List<SeriesItem> = emptyList(),
    val thumbnails: Map<String, RenderedImage> = emptyMap(),
    /** messages of the last import, export or save */
    val messages: List<String> = emptyList(),
    val busy: String? = null,
)

data class TimingsState(val running: String? = null, val text: String? = null, val photo: String? = null)

/**
 * Holds the darkroom and the working folder across configuration changes (brief P2b). The engine
 * itself lives in the process ([AppEngine]); this view model only drives it.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val schema = SchemaLoader.fromResources()
    private val builtins = BuiltinLayouts.fromResources()
    private val layouts = FileLayoutStore(
        File(app.filesDir, "layouts/layouts.json"),
        { wf -> builtins.presets(wf).map { it.name to it.params } },
        builtins.defaultWorkflow, builtins.fallbackLayout,
    )
    val work = WorkFolder(File(app.filesDir, "work"))
    private val thumbs = FileThumbnailStore(File(app.filesDir, "thumbs"))
    private val scratch = File(app.cacheDir, "out")

    val controller = DarkroomController(AppEngine.engine, schema, builtins, layouts, viewModelScope, thumbnailStore = thumbs)

    private val _screen = MutableStateFlow(Screen.LIBRARY)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _timings = MutableStateFlow(TimingsState())
    val timings: StateFlow<TimingsState> = _timings.asStateFlow()

    /** The photo the darkroom has open, or had open last (the timings page runs on it). */
    private var lastIndex: Int? = null

    /** The file name suggested to the save dialogs, kept across a rotation while a dialog is open. */
    var pendingName: String? = null

    init {
        refreshLibrary()
    }

    fun refreshLibrary() = viewModelScope.launch(Dispatchers.IO) {
        val items = work.items()
        val t = items.mapNotNull { i -> thumbs.load(i)?.let { i.fileName to it } }.toMap()
        _library.update { it.copy(items = items, thumbnails = t) }
    }

    fun show(s: Screen) { _screen.value = s }

    // ------------------------------------------------------------------ photos in

    /** Copy the picked files into the working folder (brief P2b deliverable 4). */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val cr = getApplication<Application>().contentResolver
        viewModelScope.launch {
            _library.update { it.copy(busy = "copying ${uris.size} file(s)…", messages = emptyList()) }
            val r = withContext(Dispatchers.IO) {
                val incoming = uris.map { u -> WorkFolder.Incoming(displayName(u)) { cr.openInputStream(u) ?: throw java.io.IOException("cannot read $u") } }
                work.import(incoming)
            }
            _library.update { it.copy(busy = null, messages = r.messages + "${r.imported.size} photo(s) in the working folder from this pick") }
            refreshLibrary()
        }
    }

    private fun displayName(uri: Uri): String {
        val cr = getApplication<Application>().contentResolver
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
    }

    // ------------------------------------------------------------------ darkroom

    fun openDarkroom(index: Int) {
        lastIndex = index
        _screen.value = Screen.DARKROOM
        viewModelScope.launch {
            val tablet = getApplication<Application>().resources.configuration.smallestScreenWidthDp >= 600
            controller.start(work.series(), index, filmstrip = tablet)
        }
    }

    /** "done" and back: the edit is written to the photo's sidecar in the working folder, then the library. */
    fun leaveDarkroom() {
        _screen.value = Screen.LIBRARY
        AppEngine.scope.launch {
            controller.closePhoto()
            refreshLibrary()
        }
    }

    /** The app goes to the background: write the edit now (not for read-only photos). */
    fun saveNow() {
        if (controller.state.value.info != null) AppEngine.scope.launch { controller.save() }
    }

    private fun current() = controller.state.value.current

    fun exportName(): String = current()?.fileName?.substringBeforeLast('.')?.plus(".jpg") ?: "export.jpg"
    fun xmpName(): String = (current()?.fileName ?: "photo") + ".xmp"

    /**
     * "export": darktable's export of the open photo, full size, JPEG quality 95, the history's
     * output profile (as darktable-cli), written to the app's cache and then copied to [target].
     * Runs in the process scope, so it finishes when the activity goes away.
     */
    fun exportTo(target: Uri) {
        val name = exportName()
        AppEngine.scope.launch {
            controller.showMessage("exporting $name…")
            val tmp = File(scratch, name)
            try {
                scratch.mkdirs()
                controller.export(tmp.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE))
                copyOut(tmp, target)
                controller.showMessage("exported $name")
            } catch (e: EngineException) {
                controller.showMessage("export failed: ${e.message}")
            } catch (e: Exception) {
                controller.showMessage("export failed: the file could not be written ($e)")
            } finally {
                tmp.delete()
            }
        }
    }

    /** "save XMP": the open photo's edit as a darktable sidecar, to [target]. Refused for read-only photos. */
    fun saveXmpTo(target: Uri) {
        val name = xmpName()
        AppEngine.scope.launch {
            val tmp = File(scratch, name)
            try {
                scratch.mkdirs()
                tmp.delete()
                controller.writeXmp(tmp.path)
                copyOut(tmp, target)
                controller.showMessage("saved $name")
            } catch (e: EngineException) {
                controller.showMessage(
                    if (e.error == EngineError.READ_ONLY) "this photo is read-only: its XMP is never rewritten here"
                    else "XMP not saved: ${e.message}"
                )
            } catch (e: Exception) {
                controller.showMessage("XMP not saved: the file could not be written ($e)")
            } finally {
                tmp.delete()
            }
        }
    }

    private suspend fun copyOut(src: File, target: Uri) = withContext(Dispatchers.IO) {
        val cr = getApplication<Application>().contentResolver
        (cr.openOutputStream(target, "wt") ?: throw java.io.IOException("cannot open the target")).use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
    }

    // ------------------------------------------------------------------ timings

    fun openTimings() { _screen.value = Screen.TIMINGS }

    /** Run the fixed sequence on the open photo, or on the last opened (or first) photo of the working folder. */
    fun runTimings(screenW: Int, screenH: Int, appVersion: String) {
        if (_timings.value.running != null) return
        AppEngine.scope.launch {
            val ready = AppEngine.status.value as? AppEngine.Status.Ready
            if (ready == null) { _timings.value = TimingsState(text = "the engine is not running"); return@launch }
            if (controller.state.value.info == null) {
                val items = work.items()
                if (items.isEmpty()) { _timings.value = TimingsState(text = "open a photo first: the timings run on a photo of the working folder"); return@launch }
                val i = (lastIndex ?: 0).coerceIn(0, items.size - 1)
                lastIndex = i
                _timings.value = TimingsState(running = "opening ${items[i].fileName}", photo = items[i].fileName)
                controller.start(work.series(), i, filmstrip = false, thumbnails = false)
            }
            val item = controller.state.value.current ?: return@launch
            _timings.value = TimingsState(running = "starting", photo = item.fileName)
            val t = Timings(AppEngine.engine, controller, scratch, screenW, screenH, appVersion, AppEngine.engineId, ready.cores)
            val r = t.run(item) { step -> _timings.update { it.copy(running = step) } }
            _timings.value = TimingsState(text = r.text, photo = item.fileName)
        }
    }
}

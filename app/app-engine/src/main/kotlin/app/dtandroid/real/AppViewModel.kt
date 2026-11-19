// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dtandroid.darkroom.FileThumbnailStore
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.work.WorkFolder
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.lpl.AddressInput
import app.dtandroid.lpl.AddressProblem
import app.dtandroid.lpl.HandOff
import app.dtandroid.lpl.HandOffResult
import app.dtandroid.lpl.LplAgent
import app.dtandroid.lpl.LplClient
import app.dtandroid.lpl.LplException
import app.dtandroid.lpl.LplFailure
import app.dtandroid.lpl.PhotoRecord
import app.dtandroid.lpl.ServerCheckResult
import app.dtandroid.lpl.ServerNotAddedException
import app.dtandroid.lpl.ServerOrigin
import app.dtandroid.lpl.ServerStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which screen the app shows. */
enum class Screen { LIBRARY, DARKROOM, ABOUT, TIMINGS, SERVER, HANDOFF }

/** What the hand-off screen shows (brief P2e item 4). */
sealed interface HandOffUi {
    data class Invalid(val reason: String) : HandOffUi
    /** the link names a server the user has not added: explain, offer to add it, then continue */
    data class UnknownServer(val handOff: HandOff) : HandOffUi
    data class Fetching(val handOff: HandOff, val name: String, val text: String) : HandOffUi
    data class Failed(val handOff: HandOff, val text: String) : HandOffUi
}

/** The "server" screen: add the LPL server, or change its password or this device's name (brief P2e item 2). */
data class ServerForm(
    val address: String = "",
    val deviceName: String = "",
    val password: String = "",
    /** the server being changed; null = adding one */
    val editing: ServerOrigin? = null,
    val busy: String? = null,
    val error: String? = null,
    /** a hand-off waiting for this server to be added */
    val then: HandOff? = null,
)

/** The working folder's photos as the library screen lists them. */
data class LibraryState(
    val items: List<SeriesItem> = emptyList(),
    val thumbnails: Map<String, RenderedImage> = emptyMap(),
    /** messages of the last import, export or save */
    val messages: List<String> = emptyList(),
    val busy: String? = null,
    /** brief P2e: a photo with an unsynced edit the user asked to remove; asked first */
    val confirmRemove: PhotoRecord? = null,
)

data class TimingsState(val running: String? = null, val text: String? = null, val photo: String? = null)

/**
 * Holds the darkroom and the working folder across configuration changes (brief P2b). The engine
 * itself lives in the process ([AppEngine]); this view model only drives it.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val schema get() = AppHost.schema
    val work = WorkFolder(File(app.filesDir, "work"))
    private val thumbs = FileThumbnailStore(File(app.filesDir, "thumbs"))
    private val scratch = File(app.cacheDir, "out")

    /** The darkroom lives in the process since P2e (the background sync uses its engine session too). */
    val controller get() = AppHost.controller
    val agent: LplAgent get() = AppHost.agent
    val servers: ServerStore get() = AppHost.servers

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

    private val _handOff = MutableStateFlow<HandOffUi?>(null)
    val handOff: StateFlow<HandOffUi?> = _handOff.asStateFlow()

    private val _server = MutableStateFlow(ServerForm())
    val serverForm: StateFlow<ServerForm> = _server.asStateFlow()

    /** brief P2e: the LPL photos' thumbnails, by server and id */
    private val _lplThumbs = MutableStateFlow<Map<Pair<ServerOrigin, Long>, RenderedImage>>(emptyMap())
    val lplThumbs: StateFlow<Map<Pair<ServerOrigin, Long>, RenderedImage>> = _lplThumbs.asStateFlow()

    /** the open-request of an `edit` or `open` job, answered when its first photo is open */
    private var pendingRequest: AppHost.OpenRequest? = null

    init {
        refreshLibrary()
        viewModelScope.launch { AppHost.openRequests.collect { onOpenRequest(it) } }
    }

    fun refreshLibrary() = viewModelScope.launch(Dispatchers.IO) {
        val items = work.items()
        val t = items.mapNotNull { i -> thumbs.load(i)?.let { i.fileName to it } }.toMap()
        _library.update { it.copy(items = items, thumbnails = t) }
        val lt = agent.photos.value.mapNotNull { r ->
            val f = File(AppHost.work.folder(r.server, r.id), "thumb.rgba")
            FolderThumb.load(f, AppHost.work.xmp(r))?.let { r.key to it }
        }.toMap()
        _lplThumbs.value = lt
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
        AppEngine.scope.launch {
            controller.start(work.series(), index, filmstrip = tablet())
        }
    }

    private fun tablet() = getApplication<Application>().resources.configuration.smallestScreenWidthDp >= 600

    /** "done" and back: the edit is written to the photo's sidecar in the working folder, then the library. */
    fun leaveDarkroom() {
        _screen.value = Screen.LIBRARY
        AppEngine.scope.launch {
            controller.closePhoto()
            refreshLibrary()
            // brief P2e: previews that waited for the engine (another photo was open) can be made now
            if (agent.pending().isNotEmpty()) agent.syncPending()
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

    // ------------------------------------------------------------------ LightPhotoLibrary (brief P2e)

    /** An `lpldt://edit` link arrived (from LPL in a browser, or from the LightPhotoLibrary app). */
    fun handOffLink(link: String) {
        when (val r = HandOff.parse(link)) {
            is HandOffResult.Invalid -> { _handOff.value = HandOffUi.Invalid(r.reason); _screen.value = Screen.HANDOFF }
            is HandOffResult.Ok -> startHandOff(r.handOff)
        }
    }

    /**
     * Open the hand-off's start photo in the darkroom with the others as filmstrip, in LPL's order. A
     * server the user has not added is not contacted: the screen explains and offers to add it.
     */
    fun startHandOff(h: HandOff, request: AppHost.OpenRequest? = null) {
        if (servers.get(h.server) == null) {
            request?.done?.complete(false)
            _handOff.value = HandOffUi.UnknownServer(h)
            _screen.value = Screen.HANDOFF
            return
        }
        pendingRequest?.done?.complete(false)
        pendingRequest = request
        val startName = agent.knownName(h.server, h.start) ?: "photo ${h.start}"
        _handOff.value = HandOffUi.Fetching(h, startName, "asking LightPhotoLibrary for the photo…")
        _screen.value = Screen.HANDOFF
        AppEngine.scope.launch {
            val watcher = launch {
                agent.progress.collect { m ->
                    m[h.server to h.start]?.let { p ->
                        val name = agent.knownName(h.server, h.start) ?: startName
                        _handOff.update { u -> if (u is HandOffUi.Fetching && u.handOff == h) u.copy(name = name, text = AppHost.progressText(name, p.done, p.total)) else u }
                    }
                }
            }
            try {
                agent.take(h.server, h.start)
            } catch (e: LplException) {
                watcher.cancel()
                fail(h, e.userText)
                return@launch
            } catch (e: ServerNotAddedException) {
                watcher.cancel()
                fail(h, "this server is not in the app's list")
                return@launch
            }
            watcher.cancel()
            if (_handOff.value.let { it !is HandOffUi.Fetching || it.handOff != h }) return@launch // cancelled
            val items = h.ids.map { AppHost.itemFor(h.server, it) }
            controller.closePhoto() // a photo open from before is saved (and synced) first
            _handOff.value = null
            _screen.value = Screen.DARKROOM
            controller.start(Series(h.title ?: "LightPhotoLibrary", items), h.ids.indexOf(h.start).coerceAtLeast(0), filmstrip = true)
            pendingRequest?.done?.complete(controller.state.value.info != null)
            pendingRequest = null
            // names for the placeholders, in the background; nothing is downloaded up front
            // (photos held already are named; the one after the start photo may be downloading ahead meanwhile)
            val missing = h.ids.filterIndexed { i, _ -> !File(items[i].rawPath).isFile }
            if (missing.isNotEmpty()) runCatching {
                agent.names(h.server, missing) { id, name ->
                    val i = h.ids.indexOf(id)
                    val cur = controller.state.value.series.items.getOrNull(i)
                    if (cur != null && !File(cur.rawPath).isFile) controller.replaceItem(i, AppHost.itemFor(h.server, id, name))
                }
            }
        }
    }

    private fun fail(h: HandOff, text: String) {
        pendingRequest?.done?.complete(false)
        pendingRequest = null
        _handOff.value = HandOffUi.Failed(h, text)
        refreshLibrary()
    }

    /** "back" on the hand-off screen: stop waiting (a download carries on and resumes later). */
    fun cancelHandOff() {
        pendingRequest?.done?.complete(false)
        pendingRequest = null
        _handOff.value = null
        _screen.value = Screen.LIBRARY
        refreshLibrary()
    }

    private fun onOpenRequest(r: AppHost.OpenRequest) {
        if (_screen.value == Screen.DARKROOM) {
            // the user is editing: fetch the photos into the list instead of switching photos under them
            AppEngine.scope.launch {
                var ok = true
                for (id in r.ids.take(1)) ok = runCatching { agent.take(r.origin, id) }.isSuccess && ok
                controller.showMessage("LightPhotoLibrary sent ${r.ids.size} photo(s); they are in the list")
                r.done.complete(ok)
            }
            return
        }
        startHandOff(HandOff(r.origin, r.ids.take(HandOff.MAX_IDS), r.ids.first()), r)
    }

    /** Open a photo of the working set; the server's other photos are the filmstrip. */
    fun openLplPhoto(r: PhotoRecord) {
        val list = AppHost.records(r.server)
        val ids = list.map { it.id }
        val h = HandOff(r.server, ids, r.id)
        if (AppHost.work.hasRaw(r)) {
            _screen.value = Screen.DARKROOM
            AppEngine.scope.launch {
                controller.closePhoto()
                controller.start(Series("LightPhotoLibrary", ids.map { AppHost.itemFor(r.server, it) }), ids.indexOf(r.id), filmstrip = true)
            }
        } else {
            startHandOff(h)
        }
    }

    fun syncNow(r: PhotoRecord) {
        AppEngine.scope.launch {
            agent.sync(r.server, r.id)
            if (agent.pending().isNotEmpty()) SyncJob.schedule(getApplication())
            refreshLibrary()
        }
    }

    fun syncAll() {
        AppEngine.scope.launch {
            if (!agent.syncPending()) SyncJob.schedule(getApplication())
            refreshLibrary()
        }
    }

    /** "remove from this phone": asks first when the photo has an edit LPL does not have. */
    fun remove(r: PhotoRecord, evenUnsynced: Boolean = false) {
        AppEngine.scope.launch {
            when (agent.remove(r.server, r.id, evenUnsynced)) {
                LplAgent.RemoveResult.Unsynced -> _library.update { it.copy(confirmRemove = r) }
                LplAgent.RemoveResult.OpenInDarkroom -> _library.update { it.copy(messages = listOf("${r.filename} is open in the darkroom"), confirmRemove = null) }
                LplAgent.RemoveResult.Removed -> { _library.update { it.copy(confirmRemove = null, messages = listOf("${r.filename} removed from this phone")) }; refreshLibrary() }
            }
        }
    }

    fun keep() = _library.update { it.copy(confirmRemove = null) }

    /** The LPL photo open in the darkroom, if any. */
    fun currentLplPhoto(): Pair<ServerOrigin, Long>? = controller.state.value.current?.let { AppHost.lplPhotoOf(it) }

    // ------------------------------------------------------------------ servers

    fun showServerForm(prefill: HandOff? = null) {
        val existing = prefill?.server?.let { servers.get(it) } ?: if (prefill == null) servers.list().firstOrNull() else null
        _server.value = if (existing != null) ServerForm(existing.origin.url, existing.deviceName, "", existing.origin, then = prefill)
                        else ServerForm(prefill?.server?.url ?: "", "", "", null, then = prefill)
        _screen.value = Screen.SERVER
    }

    fun editServerForm(f: ServerForm) { _server.value = f.copy(error = null) }

    /**
     * "check and save": the address is checked with a request (https first when no scheme was typed,
     * then http), LPL must answer, and the password must be accepted. Only then is the server stored.
     */
    fun saveServer() {
        val f = _server.value
        if (f.busy != null) return
        val name = ServerStore.cleanName(f.deviceName)
        if (name == null) { _server.update { it.copy(error = "give this device a name, for example Phone") }; return }
        val tries = if (f.editing != null) listOf(f.editing) else when (val a = ServerOrigin.candidates(f.address)) {
            is AddressInput.Invalid -> { _server.update { it.copy(error = addressText(a.problem)) }; return }
            is AddressInput.Ok -> a.tries
        }
        _server.update { it.copy(busy = "checking ${tries.first().url}…", error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // when only the name changes, the saved password stays
            val keep = f.editing != null && f.password.isEmpty()
            val pw = if (keep) runCatching { servers.password(f.editing!!) }.getOrNull() else f.password.ifEmpty { null }
            var result: Pair<ServerOrigin, ServerCheckResult>? = null
            for (o in tries) {
                _server.update { it.copy(busy = "checking ${o.url}…") }
                val r = LplClient(o, { pw }).check(pw)
                result = o to r
                if (r !is ServerCheckResult.Failed) break
            }
            val (origin, r) = result!!
            val error = when (r) {
                is ServerCheckResult.Lpl -> when {
                    r.passwordOk -> null
                    pw == null -> "this LightPhotoLibrary asks for a password"
                    else -> "wrong password"
                }
                ServerCheckResult.NotLpl -> "${origin.url} answers, but it is not LightPhotoLibrary"
                is ServerCheckResult.Failed -> if (r.error.failure == LplFailure.TLS) "${origin.url}: ${r.error.userText}" else "${origin.url}: the server is not reachable (check the address and that the phone is on the right network)"
            }
            if (error != null) { _server.update { it.copy(busy = null, error = error) }; return@launch }
            try {
                if (f.editing != null && f.editing != origin) servers.remove(f.editing)
                servers.put(origin, name, pw)
            } catch (e: java.io.IOException) {
                _server.update { it.copy(busy = null, error = "the server could not be saved on this phone (storage full?)") }
                return@launch
            }
            agent.resetClients()
            _server.value = ServerForm()
            withContext(Dispatchers.Main) {
                val then = f.then
                if (then != null && then.server == origin) startHandOff(then)
                else { _screen.value = Screen.LIBRARY; refreshLibrary() }
                AppHost.foreground()
            }
        }
    }

    /** Remove the server: only when the phone holds none of its photos (they would lose their way back). */
    fun removeServer(o: ServerOrigin) {
        if (AppHost.records(o).isNotEmpty()) {
            _server.update { it.copy(error = "remove this server's photos from the phone first") }
            return
        }
        try {
            servers.remove(o)
        } catch (e: java.io.IOException) {
            _server.update { it.copy(error = "the change could not be saved on this phone (storage full?)") }
            return
        }
        agent.resetClients()
        _server.value = ServerForm()
        _screen.value = Screen.LIBRARY
    }

    private fun addressText(p: AddressProblem) = when (p) {
        AddressProblem.EMPTY -> "type the server's address, for example 192.0.2.10:8080"
        AddressProblem.SPACES -> "the address cannot contain spaces"
        AddressProblem.SCHEME -> "only http:// and https:// addresses"
        AddressProblem.USER_INFO -> "put the password in its own field, not in the address"
        AddressProblem.NOT_AN_ADDRESS -> "this is not a server address"
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

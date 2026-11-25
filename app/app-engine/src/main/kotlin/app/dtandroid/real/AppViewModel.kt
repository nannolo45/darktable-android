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
import app.dtandroid.darkroom.Words
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
enum class Screen { LIBRARY, DARKROOM, ABOUT, TIMINGS, SERVER, HANDOFF, CRASH_LOG }

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
    private val thumbs get() = AppHost.localThumbs
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
        // brief P3l: what waited when the app was closed shows at once (the server is asked within the minute)
        runCatching { agent.shared.showStored() }
    }

    // ------------------------------------------------------------------ brief P3l: shared styles, presets and layouts

    /** The review sheet with its choices; [dialog] = the conflict or deletion being decided. */
    data class SharedReviewUi(
        val origin: ServerOrigin,
        val review: app.dtandroid.darkroom.ui.SharedUi.Review,
        val dialog: app.dtandroid.lpl.SharedRow? = null,
        val sameForOthers: Boolean = false,
    )

    private val _sharedReview = MutableStateFlow<SharedReviewUi?>(null)
    val sharedReview: StateFlow<SharedReviewUi?> = _sharedReview.asStateFlow()

    /** Open the review sheet of [o]: new and changed items ticked, conflicts and deletions still to decide. */
    fun openSharedReview(o: ServerOrigin) {
        val v = agent.shared.views.value[o] ?: return
        val take = v.rows.filter { it.change == app.dtandroid.lpl.SharedRow.Change.NEW || it.change == app.dtandroid.lpl.SharedRow.Change.CHANGED }.map { it.meta.id }.toSet()
        _sharedReview.value = SharedReviewUi(o, app.dtandroid.darkroom.ui.SharedUi.Review(v.rows, take))
        _screen.value = Screen.LIBRARY
    }

    fun closeSharedReview() { _sharedReview.value = null }

    private fun review(f: (SharedReviewUi) -> SharedReviewUi) = _sharedReview.update { it?.let(f) }

    fun toggleShared(id: Long) = review { u ->
        val t = u.review.take
        u.copy(review = u.review.copy(take = if (id in t) t - id else t + id))
    }

    fun openSharedDialog(row: app.dtandroid.lpl.SharedRow) = review { it.copy(dialog = row) }
    fun closeSharedDialog() = review { it.copy(dialog = null) }
    fun toggleSameForOthers() = review { it.copy(sameForOthers = !it.sameForOthers) }

    /** The conflict dialog's choice; with "same choice for the others", every open conflict of this update gets it ("both" only where it is possible). */
    fun chooseConflict(choice: String) = review { u ->
        val row = u.dialog ?: return@review u
        val targets = if (u.sameForOthers) u.review.rows.filter { it.change == app.dtandroid.lpl.SharedRow.Change.CONFLICT } else listOf(row)
        val m = HashMap(u.review.conflicts)
        for (r in targets) {
            val deletion = r.meta.deleted || r.conflict?.deleted == true
            m[r.meta.id] = if (choice == "both" && deletion) (if (r == row) "mine" else m[r.meta.id] ?: continue) else choice
        }
        u.copy(review = u.review.copy(conflicts = m), dialog = null)
    }

    /** The deletion dialog's choice: true = delete here too, false = keep on this phone, null = decide later. */
    fun chooseDeletion(delete: Boolean?) = review { u ->
        val row = u.dialog ?: return@review u
        val m = HashMap(u.review.deletions)
        if (delete == null) m.remove(row.meta.id) else m[row.meta.id] = delete
        u.copy(review = u.review.copy(deletions = m), dialog = null)
    }

    /** "Update n": everything chosen is done now; the banner then says what was taken in and what kept waiting. */
    fun runSharedUpdate() {
        val u = _sharedReview.value ?: return
        review { it.copy(review = it.review.copy(busy = true, error = null)) }
        AppEngine.scope.launch {
            try {
                agent.shared.update(u.origin, app.dtandroid.lpl.SharedChoices(u.review.take, u.review.deletions, u.review.conflicts))
                _sharedReview.value = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                review { it.copy(review = it.review.copy(busy = false, error = "The update stopped: " + ((e as? app.dtandroid.lpl.LplException)?.userText ?: e.message))) }
            }
        }
    }

    fun dismissSharedSummary(o: ServerOrigin) = agent.shared.dismissSummary(o)

    /** Settings › LightPhotoLibrary: sharing on or off, the kinds, the policy. A change is told to the server at the next pass, started now. */
    fun setSharedSettings(v: app.dtandroid.lpl.SharedSettings.Values) {
        try {
            agent.shared.settings.set(v)
        } catch (e: java.io.IOException) {
            _server.update { it.copy(error = "the change could not be saved on this phone (storage full?)") }
            return
        }
        AppEngine.scope.launch { runCatching { agent.shared.passAll() } }
    }

    fun refreshLibrary() = viewModelScope.launch(Dispatchers.IO) {
        val items = work.items()
        val t = items.mapNotNull { i -> thumbs.load(i)?.let { i.fileName to it } }.toMap()
        _library.update { it.copy(items = items, thumbnails = t) }
        val lt = agent.photos.value.mapNotNull { r ->
            val f = File(AppHost.work.folder(r.server, r.id), "thumb.rgba")
            FolderThumb.load(f)?.let { r.key to it }
        }.toMap()
        _lplThumbs.value = lt
        refreshThumbnails()
    }

    private var thumbJob: kotlinx.coroutines.Job? = null

    /**
     * Brief P2f: thumbnails missing or older than their photo's edit are rendered again, as background work
     * (never while the user works in the darkroom, cancelled when he starts), then the list shows them.
     */
    private fun refreshThumbnails() {
        if (thumbJob?.isActive == true) return
        thumbJob = AppEngine.scope.launch {
            if (AppEngine.status.value !is AppEngine.Status.Ready) return@launch
            val lpl = agent.photos.value.filter { AppHost.work.hasRaw(it) }.map { it to AppHost.itemFor(it.server, it.id) }
            val local = work.items()
            val todo = (lpl.map { it.second } + local).filter { AppHost.thumbStale(it) }
            if (todo.isEmpty()) return@launch
            if (controller.renderThumbnails(todo) { AppHost.thumbStale(it) } > 0) {
                _lplThumbs.value = agent.photos.value.mapNotNull { r ->
                    FolderThumb.load(File(AppHost.work.folder(r.server, r.id), "thumb.rgba"))?.let { r.key to it }
                }.toMap()
                _library.update { st -> st.copy(thumbnails = local.mapNotNull { i -> AppHost.localThumbs.load(i)?.let { i.fileName to it } }.toMap()) }
            }
        }
    }

    fun show(s: Screen) { _screen.value = s }

    // ------------------------------------------------------------------ photos in

    /** Copy the picked files into the working folder (brief P2b deliverable 4). */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val cr = getApplication<Application>().contentResolver
        viewModelScope.launch {
            _library.update { it.copy(busy = "copying ${Words.count(uris.size, "file")}…", messages = emptyList()) }
            val r = withContext(Dispatchers.IO) {
                val incoming = uris.map { u -> WorkFolder.Incoming(displayName(u)) { cr.openInputStream(u) ?: throw java.io.IOException("cannot read $u") } }
                work.import(incoming)
            }
            _library.update { it.copy(busy = null, messages = r.messages + "${Words.count(r.imported.size, "photo").replaceFirstChar { it.uppercase() }} copied from this pick") }
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
        // brief P3k: the photo's sync goes on if the app leaves the screen right after "done": the sync service is
        // started now, while the activity is visible, and counts this hand-over as work until it ends
        val handing = agent.autoSync && current()?.let { AppHost.lplPhotoOf(it) } != null
        if (handing) AppHost.handOverStarted()
        AppHost.startSyncIfNeeded()
        AppEngine.scope.launch {
            try {
                controller.closePhoto()
                refreshLibrary()
                // brief P2e: previews that waited for the engine (another photo was open) can be made now
                // brief P2i: not with automatic syncing off ("done" keeps the edit on the phone)
                if (agent.pending().isNotEmpty()) agent.syncPendingAutomatically()
                refreshLibrary()
            } finally {
                if (handing) AppHost.handOverEnded()
            }
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
            // brief P3f: the start photo opens the version the link names, else LPL's cover
            val items = h.ids.map { AppHost.itemFor(h.server, it, version = if (it == h.start) (h.version ?: agent.record(h.server, it)?.cover) else null) }
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
                controller.showMessage(if (r.ids.size == 1) "1 photo arrived from LightPhotoLibrary; it is in the list" else "${r.ids.size} photos arrived from LightPhotoLibrary; they are in the list")
                r.done.complete(ok)
            }
            return
        }
        startHandOff(HandOff(r.origin, r.ids.take(HandOff.MAX_IDS), r.ids.first()), r)
    }

    /** Open a photo of the working set; the server's other photos are the filmstrip. */
    fun openLplPhoto(r: PhotoRecord) {
        agent.clearNote(r.server, r.id)
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
        agent.clearNote(r.server, r.id)
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

    /** Brief PUI2a: switch the interface skin (setting `interface`). */
    fun setSkin(skin: app.dtandroid.ui.theme.Skin) {
        try { AppHost.settings.setSkin(skin) } catch (e: java.io.IOException) { /* kept for this run only */ }
    }

    fun keep() = _library.update { it.copy(confirmRemove = null) }

    /**
     * Brief P2i: the "sync automatically" switch. Switched on again: what waited is sent now, and the background
     * job takes over what cannot be sent yet.
     */
    // brief P3k item 6: whether Android exempts the app from battery optimisation (read again when the app is resumed,
    // so coming back from the system's list shows the new state)
    private val _batteryExempt = MutableStateFlow(true)
    val batteryExempt: StateFlow<Boolean> = _batteryExempt.asStateFlow()

    fun refreshBattery() {
        val app = getApplication<Application>()
        val pm = app.getSystemService(android.os.PowerManager::class.java)
        _batteryExempt.value = pm?.isIgnoringBatteryOptimizations(app.packageName) == true
    }

    /** The system's list of apps and their battery optimisation (no permission needed for this screen). */
    fun openBatterySettings() {
        val app = getApplication<Application>()
        runCatching {
            app.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { _server.update { f -> f.copy(error = "this phone has no battery optimisation list") } }
    }

    fun setAutoSync(on: Boolean) {
        try {
            agent.settings.setAutoSync(on)
        } catch (e: java.io.IOException) {
            _server.update { it.copy(error = "the change could not be saved on this phone (storage full?)") }
            return
        }
        if (on) AppEngine.scope.launch {
            if (agent.pending().isNotEmpty() && agent.syncPendingAutomatically() == false) SyncJob.schedule(getApplication())
            refreshLibrary()
        } else refreshLibrary()
    }

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

    /**
     * Run the fixed sequence on the open photo, whatever its source; with none open, on the last photo the
     * darkroom opened (LightPhotoLibrary or this phone's files), else on a photo of the working folder
     * (brief P2f: before, only the working folder was considered, so photos from LightPhotoLibrary could
     * not be measured unless open). "timings…" in the darkroom's menu keeps the photo open: it only
     * switches the screen.
     */
    fun runTimings(screenW: Int, screenH: Int, appVersion: String) {
        if (_timings.value.running != null) return
        AppEngine.scope.launch {
            val ready = AppEngine.status.value as? AppEngine.Status.Ready
            if (ready == null) { _timings.value = TimingsState(text = "the engine is not running"); return@launch }
            if (controller.state.value.info == null) {
                val last = controller.lastOpened?.takeIf { (series, i) -> series.items.getOrNull(i)?.let { File(it.rawPath).isFile } == true }
                val held = agent.photos.value.lastOrNull { AppHost.work.hasRaw(it) }
                val (series, i) = when {
                    last != null -> last
                    work.items().isNotEmpty() -> work.series() to (lastIndex ?: 0).coerceIn(0, work.items().size - 1)
                    held != null -> Series("LightPhotoLibrary", listOf(AppHost.itemFor(held.server, held.id))) to 0
                    else -> { _timings.value = TimingsState(text = "open a photo first: the timings run on the open photo, or on the last one opened"); return@launch }
                }
                val name = series.items[i].fileName
                _timings.value = TimingsState(running = "opening $name", photo = name)
                controller.start(series, i, filmstrip = false, thumbnails = false)
            }
            val item = controller.state.value.current ?: return@launch
            if (controller.state.value.info == null) { _timings.value = TimingsState(text = "${item.fileName} did not open: ${controller.state.value.message ?: "?"}"); return@launch }
            _timings.value = TimingsState(running = "starting", photo = item.fileName)
            val source = if (AppHost.lplPhotoOf(item) != null) "LightPhotoLibrary (a copy downloaded to this phone)" else "this phone's files (working folder)"
            val t = Timings(AppEngine.engine, controller, scratch, screenW, screenH, appVersion, AppEngine.engineId, ready.cores, source)
            val r = t.run(item) { step -> _timings.update { it.copy(running = step) } }
            _timings.value = TimingsState(text = r.text, photo = item.fileName)
        }
    }
}

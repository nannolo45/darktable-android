// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import app.dtandroid.lpl.SharedSettings
import app.dtandroid.lpl.SharedPort
import app.dtandroid.lpl.SharedLocal
import app.dtandroid.lpl.sharedLocalsOf
import app.dtandroid.lpl.SharedLayout
import app.dtandroid.lpl.SharedKey
import app.dtandroid.lpl.SharedKind
import kotlinx.serialization.json.JsonObject
import android.app.Application
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.FileStyleSettings
import app.dtandroid.darkroom.FileThumbnailStore
import app.dtandroid.darkroom.PhotoSource
import app.dtandroid.darkroom.PhotoSourceException
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.VersionHost
import app.dtandroid.darkroom.VersionSync
import app.dtandroid.darkroom.atVersion
import app.dtandroid.darkroom.ThumbnailStore
import app.dtandroid.darkroom.Timeline
import app.dtandroid.darkroom.RgbaFile
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.FileLayoutStore
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.lpl.BackgroundSync
import app.dtandroid.lpl.EditorPort
import app.dtandroid.lpl.SyncWork
import java.util.concurrent.atomic.AtomicInteger
import app.dtandroid.lpl.Job
import app.dtandroid.lpl.LplAgent
import app.dtandroid.lpl.LplException
import app.dtandroid.lpl.PhotoRecord
import app.dtandroid.lpl.ServerNotAddedException
import app.dtandroid.lpl.ServerOrigin
import app.dtandroid.lpl.ServerStore
import app.dtandroid.lpl.SyncOutcome
import app.dtandroid.lpl.SyncSettings
import app.dtandroid.lpl.Step as LplStep
import app.dtandroid.lpl.WorkStore
import app.dtandroid.schema.SchemaLoader
import app.dtandroid.schema.layout.BuiltinLayouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.dtandroid.darkroom.ExportTarget
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import app.dtandroid.darkroom.robust.CrashLog
import java.util.Locale

/**
 * What lives as long as the process (brief P2e): the darkroom (moved here from the view model, so the
 * background sync and the darkroom share one engine session), and the LightPhotoLibrary agent with its
 * servers, working set, heartbeat and jobs. The activity shows it; the background job uses it too.
 */
object AppHost {
    lateinit var app: Application
        private set

    @Synchronized
    fun init(a: Application) {
        if (this::app.isInitialized) return
        app = a
        // installed over the old interface preview (same id until P2e): its sample series is of no use here
        runCatching { File(a.filesDir, "preview-series").takeIf { it.isDirectory }?.deleteRecursively() }
    }

    /** brief P4a: the local crash log (Settings › About › crash log); nothing in it is sent anywhere */
    val crashLog: CrashLog by lazy {
        val v = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        CrashLog(File(app.filesDir, "logs/crash-log.jsonl"), v)
    }

    /** brief P3i: set by the activity: asks for the notification permission once */
    @Volatile var askNotifications: (() -> Unit)? = null

    val schema by lazy { SchemaLoader.fromResources() }
    private val builtins by lazy { BuiltinLayouts.fromResources() }
    private val layouts by lazy {
        FileLayoutStore(
            File(app.filesDir, "layouts/layouts.json"),
            { wf -> builtins.presets(wf).map { it.name to it.params } },
            builtins.defaultWorkflow, builtins.fallbackLayout,
        )
    }

    /** P2b's thumbnails for the file-picker photos; LPL photos keep theirs in their own folder (file names repeat across a library). */
    val localThumbs by lazy { FileThumbnailStore(File(app.filesDir, "thumbs")) }

    private fun lplDir(item: SeriesItem): File? =
        File(item.rawPath).takeIf { it.path.startsWith(work.root.path + File.separator) }?.parentFile

    /** Thumbnails (and, brief P2f, the last picture) of both kinds of photos. */
    val thumbs: ThumbnailStore by lazy {
        object : ThumbnailStore {
            override fun load(item: SeriesItem): RenderedImage? = lplDir(item)?.let { d -> FolderThumb.load(File(d, "thumb.rgba")) } ?: localThumbs.load(item)
            override fun store(item: SeriesItem, image: RenderedImage) {
                if (lplDir(item) == null) { localThumbs.store(item, image); return }
                // brief P2i: only while the photo is held; a removal (a `release` job) wins over a late thumbnail
                writeHeld(item, "thumbnail") { d -> FolderThumb.store(File(d, "thumb.rgba"), image) }
            }
            override fun loadPicture(item: SeriesItem): RenderedImage? =
                lplDir(item)?.let { RgbaFile.read(File(it, "picture.rgba")) } ?: localThumbs.loadPicture(item)
            override fun storePicture(item: SeriesItem, image: RenderedImage) {
                if (lplDir(item) == null) { localThumbs.storePicture(item, image); return }
                writeHeld(item, "last picture") { d -> RgbaFile.write(File(d, "picture.rgba"), image) }
            }
        }
    }

    /**
     * Brief P2i: write into an LPL photo's folder only while the agent holds the photo ([LplAgent.writeIfHeld]);
     * a photo the agent does not know (its server removed, or the photo removed) gets nothing written.
     */
    private fun writeHeld(item: SeriesItem, what: String, write: (File) -> Unit) {
        val p = lplPhotoOf(item)
        if (p == null) { Timeline.mark("lpl: $what of ${item.fileName} not written: the photo is not held"); return }
        agent.writeIfHeld(p.first, p.second, what, write)
    }

    /** Brief P2f: the thumbnail is missing or older than the photo's edit: render it again in the background. */
    fun thumbStale(item: SeriesItem): Boolean {
        val d = lplDir(item) ?: return localThumbs.stale(item)
        val f = File(d, "thumb.rgba")
        val xmp = File(item.xmpPath)
        return !f.isFile || (xmp.isFile && xmp.lastModified() > f.lastModified())
    }

    val controller: DarkroomController by lazy {
        DarkroomController(AppEngine.engine, schema, builtins, layouts, AppEngine.scope, thumbnailStore = thumbs).also { c ->
            c.source = Source
            c.versionHost = Versions // brief P3f
            // brief P3b: favourite and recently used styles and the apply mode, in the app's settings (not darktable's database)
            c.stylesPresets.settings = FileStyleSettings(File(app.filesDir, "styles/settings.json"))
            // brief P3g: where the export screen's files go (Pictures/darktable, the share sheet)
            c.exports.delivery = AppExportDelivery(app)
            // brief P3j: the paste mode (append / overwrite) is remembered between starts
            c.copyPaste.modeFile = File(app.filesDir, "settings/paste-mode.txt")
            // brief P4a: engine (bridge) errors the darkroom shows go into the crash log as well
            AppEngine.scope.launch {
                c.state.map { it.message }.distinctUntilChanged().collect { m ->
                    if (CrashLog.isBridgeMessage(m)) crashLog.append(CrashLog.KIND_BRIDGE, m!!)
                }
            }
            // brief P3i: while an export runs the process is kept alive by a foreground service (notification, cancel);
            // an export to LightPhotoLibrary also heartbeats while the app is in the background (step "exporting")
            AppEngine.scope.launch {
                c.exports.job.map { it?.running == true }.distinctUntilChanged().collect { running ->
                    if (running) {
                        askNotifications?.invoke()
                        ExportService.start(app)
                        if (c.exports.job.value?.target == ExportTarget.LPL) exportHeartbeats()
                    }
                }
            }
            // brief P2f: the open photo's sync step (R16) and last edit (R15), from the agent
            // brief P2f: what a job from LightPhotoLibrary did with the open photo, said in the darkroom once
            AppEngine.scope.launch {
                var said: String? = null
                agent.notes.collect { notes ->
                    val item = c.state.value.current?.takeIf { c.state.value.info != null } ?: return@collect
                    val (origin, id) = lplPhotoOf(item) ?: return@collect
                    val n = notes[origin to id]
                    if (n != null && n != said) { said = n; c.showMessage(n) }
                }
            }
            AppEngine.scope.launch {
                kotlinx.coroutines.flow.combine(c.state, agent.steps, agent.photos) { st, steps, _ -> st.current to steps }.collect { (item, steps) ->
                    val it0 = item ?: return@collect
                    val (origin, id) = lplPhotoOf(it0) ?: return@collect
                    val r = agent.record(origin, id) ?: return@collect
                    val step = steps[origin to id]
                    val stepText = step?.let { s -> when (s.step) {
                        LplStep.SENDING_EDIT -> "syncing: sending the edit"
                        LplStep.RENDERING_PREVIEW -> "syncing: making the preview"
                        LplStep.SENDING_PREVIEW -> "syncing: sending the preview"
                        LplStep.DOWNLOADING -> "downloading"
                        else -> s.step
                    } }
                    val last = if (agent.isDirty(r) && !r.readOnly) "edited here, not synced yet" else r.lastEdit?.line(thisDevice = servers.installId)
                    val cur = c.state.value
                    if (cur.syncStep != stepText || cur.syncProgress != step?.progress?.toFloat() || cur.lastEdit != last)
                        c.setPhotoNotes(it0.rawPath, stepText, step?.progress?.toFloat(), last)
                }
            }
        }
    }

    // ------------------------------------------------------------------ LightPhotoLibrary

    /** brief PUI2a: the app's own settings (the interface skin) */
    val settings: AppSettings by lazy { AppSettings(File(app.filesDir, "settings/app.json")) }

    val servers: ServerStore by lazy { ServerStore(File(app.filesDir, "lpl"), KeystoreSecretBox()) }
    val work: WorkStore by lazy { WorkStore(File(app.filesDir, "lpl/work")) }

    fun appVersion(): String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun versionText(): String {
        val dt = (AppEngine.status.value as? AppEngine.Status.Ready)?.info?.darktableVersion ?: "not started"
        return "dt-android ${appVersion()} / darktable $dt"
    }

    val agent: LplAgent by lazy {
        LplAgent(servers, work, Editor, ::versionText,
            // brief P2i: "sync automatically" (default on), kept with the servers
            settings = SyncSettings(File(app.filesDir, "lpl/settings.json")),
            trace = { Timeline.mark(it) },
            // brief P3l: sharing styles, presets and layouts (default on), kept with the servers
            sharedSettings = SharedSettings(File(app.filesDir, "lpl/shared-settings.json")),
        ).also { it.shared.port = SharedItems }
    }

    /**
     * Brief P3l (R11, PLAN.md 4.5): the phone's shared items. Presets and styles are the engine's (its data.db, through
     * dtb_shared_*); module layouts are the app's own (layouts.json; the built-in ones are not shared). Applying a layout
     * adds or replaces it and never changes which one is active.
     */
    object SharedItems : SharedPort {
        private suspend fun ready() { engineReady() ?: throw IllegalStateException("the engine did not start") }

        override suspend fun snapshot(): List<SharedLocal> {
            ready()
            val engine = controller.bg("shared items") { e -> sharedLocalsOf(e.sharedSnapshot()) }
            val mine = layouts.list().filter { !it.writeProtected }.map { SharedLayout.local(it.name, it.params) }
            return engine + mine
        }

        override suspend fun apply(body: JsonObject): Boolean {
            val key = SharedKey.of(body) ?: return false
            if (key.kind == SharedKind.LAYOUT) {
                val text = SharedLayout.params(body) ?: return false
                // a name of a built-in layout is refused by the store: reported as not usable here
                if (runCatching { layouts.write(key.name, text) }.isFailure) return false
                runCatching { controller.loadLayout() }
                return true
            }
            ready()
            val usable = controller.bg("shared apply") { e -> e.sharedApply(body) }
            if (usable && key.kind == SharedKind.STYLE) runCatching { controller.stylesPresets.loadStyles(force = true) }
            return usable
        }

        override suspend fun engineVersion(): String? =
            (AppEngine.status.value as? AppEngine.Status.Ready)?.let { "${it.info.darktableVersion} ${AppEngine.engineId}" }

        override suspend fun delete(key: SharedKey) {
            if (key.kind == SharedKind.LAYOUT) {
                runCatching { layouts.delete(key.name) }
                runCatching { controller.loadLayout() }
                return
            }
            ready()
            try {
                controller.bg("shared delete") { e -> e.sharedDelete(key.kind.json, key.operation, key.opVersion, key.name) }
            } catch (e: EngineException) {
                if (e.error != EngineError.NOT_FOUND) throw e
            }
            if (key.kind == SharedKind.STYLE) runCatching { controller.stylesPresets.loadStyles(force = true) }
        }
    }

    /** Waits for the engine (the background job may start the process); null when it did not start. */
    suspend fun engineReady(timeoutMs: Long = 120_000): AppEngine.Status.Ready? {
        AppEngine.start(app)
        return withTimeoutOrNull(timeoutMs) {
            AppEngine.status.first { it is AppEngine.Status.Ready || it is AppEngine.Status.Failed } as? AppEngine.Status.Ready
        }
    }

    /** The darkroom and the engine as the agent needs them (PLAN.md 4.1, brief P2e item 5). */
    object Editor : EditorPort {
        override suspend fun xmpReadOnly(xmp: File): Boolean {
            if (!xmp.isFile || engineReady() == null) return false
            return try { !AppEngine.engine.xmpCheck(xmp.path).ok } catch (e: EngineException) { false }
        }

        override suspend fun writeIfOpen(raw: File, xmp: File) = controller.saveIfOpen(raw.path)

        override suspend fun reloadIfOpen(raw: File, xmp: File) = controller.reloadMetadataIfOpen(raw.path, xmp.path)

        /**
         * The preview LightPhotoLibrary shows: 1920 px on the longer side, JPEG quality 90.
         *
         * Brief P2f: rendered as background work through the bridge's full pipe (render_region over the whole
         * image at the preview's scale), which is darktable's export pipe type (engine/src/render.c), and
         * encoded here. Unlike dtb_export it can be cancelled between modules: when the user opens a photo
         * or changes something, this render stops and is started again when the darkroom is quiet
         * ([DarkroomController.bg]). Before P2f it was dtb_export under the darkroom's lock, which nothing
         * could interrupt, so a photo opened right after "done" waited for it with a black frame.
         * Difference: the output profile is the edit's own (colorout; sRGB unless the user changed it), where
         * the export forced sRGB; the emulator test f2 measures the two against each other.
         */
        override suspend fun renderPreview(raw: File, xmp: File, out: File): Boolean {
            if (engineReady(10_000) == null) return false
            val tmp = File(out.parentFile, "preview-tmp.jpg")
            tmp.delete()
            return try {
                val img = controller.withPhotoSession(raw.path, xmp.path) { e -> previewPicture(e) } ?: return false
                PreviewJpeg.write(img, tmp, PREVIEW_QUALITY)
                tmp.isFile && tmp.length() > 0 && tmp.renameTo(out)
            } catch (e: EngineException) {
                false
            } catch (e: java.io.IOException) {
                false
            } finally {
                tmp.delete()
            }
        }

        override fun isOpen(raw: File) = controller.openRawPath() == raw.path
        override fun darkroomOpen() = controller.openRawPath() != null
        override fun openVersion(raw: File): Int =
            controller.state.value.takeIf { it.info != null }?.current?.takeIf { it.rawPath == raw.path }?.version ?: 0
    }

    const val PREVIEW_SIZE = 1920
    const val PREVIEW_QUALITY = 90

    /** The whole photo at [PREVIEW_SIZE] px on the longer side, from the full pipe (cancellable). */
    suspend fun previewPicture(e: app.dtandroid.engine.api.DarktableEngine): RenderedImage {
        Timeline.mark("LPL preview: render start")
        try {
            // brief P2f/P2h: never render a file darktable found no picture in: 0 x 0, or an engine error from
            // image_info other than CANCELLED (DarkroomController.pictureOrClose; withPhotoSession has already
            // closed such a session and remembered the file before this runs, this is the second guard)
            val info = try { e.imageInfo() } catch (ex: EngineException) {
                if (ex.error == EngineError.CANCELLED) throw ex
                Timeline.mark("LPL preview: no picture (image_info answered ${ex.message})")
                throw EngineException(EngineError.IO, DarkroomController.NO_PICTURE)
            }
            if (info.width <= 0 || info.height <= 0) {
                Timeline.mark("LPL preview: no picture (${info.width}x${info.height})")
                throw EngineException(EngineError.IO, DarkroomController.NO_PICTURE)
            }
            val full = e.renderSize(1.0)
            val scale = minOf(1.0, PREVIEW_SIZE.toDouble() / maxOf(full.w, full.h))
            val sz = e.renderSize(scale)
            return e.renderRegion(0, 0, sz.w, sz.h, scale).also { Timeline.mark("LPL preview: render end") }
        } catch (ex: EngineException) {
            Timeline.mark(if (ex.error == EngineError.CANCELLED) "LPL preview: render cancelled" else "LPL preview: render failed")
            throw ex
        }
    }

    /** Brief P3f: versions of the open photo: files for every photo, LightPhotoLibrary's sync state and calls for its photos. */
    object Versions : VersionHost {
        override fun canMake(item: SeriesItem): Boolean = lplPhotoOf(item)?.let { (o, id) -> agent.canMakeVersion(o, id) } ?: true

        override fun syncOf(item: SeriesItem, n: Int): VersionSync {
            val (o, id) = lplPhotoOf(item) ?: return VersionSync.LOCAL
            if (n > 0 && !agent.versionsSynced(o, id)) return VersionSync.LOCAL
            return if (agent.isVersionDirty(o, id, n)) VersionSync.WAITING else VersionSync.SYNCED
        }

        override fun made(item: SeriesItem, n: Int) {
            val (o, id) = lplPhotoOf(item) ?: return
            agent.versionMade(o, id, n)
            syncLater(o, id)
        }

        override suspend fun delete(item: SeriesItem, n: Int): Boolean? {
            val (o, id) = lplPhotoOf(item) ?: return null
            return agent.deleteVersion(o, id, n).also { if (it) syncLater(o, id) }
        }

        override fun cover(item: SeriesItem): Int = lplPhotoOf(item)?.let { (o, id) -> agent.record(o, id)?.cover } ?: 0

        override fun canSetCover(item: SeriesItem): Boolean = lplPhotoOf(item)?.let { (o, id) -> agent.versionsSynced(o, id) } == true

        override suspend fun setCover(item: SeriesItem, n: Int): String? {
            val (o, id) = lplPhotoOf(item) ?: return "this photo is not in LightPhotoLibrary"
            return agent.setCover(o, id, n)
        }
    }

    /** The LPL photo a darkroom item stands for: its folder is `<work>/<server key>/<id>/`. */
    fun lplPhotoOf(item: SeriesItem): Pair<ServerOrigin, Long>? {
        val dir = File(item.rawPath).parentFile ?: return null
        val serverDir = dir.parentFile ?: return null
        if (serverDir.parentFile?.path != work.root.path) return null
        val id = dir.name.toLongOrNull() ?: return null
        val origin = servers.list().firstOrNull { it.origin.key == serverDir.name }?.origin
            ?: agent.photos.value.firstOrNull { it.server.key == serverDir.name }?.server ?: return null
        return origin to id
    }

    /** The darkroom item for an LPL photo; a photo not on the phone yet gets a path that does not exist. */
    fun itemFor(origin: ServerOrigin, id: Long, name: String? = null, version: Int? = null): SeriesItem {
        val base = itemOf(origin, id, name)
        // brief P3f: a version asked for (the hand-off link's `version`, or the cover) when its file is there; else the photo's own edit
        val n = version ?: return base
        if (n <= 0) return base
        val v = base.atVersion(n)
        return if (File(base.rawPath).name == NOT_HERE || File(v.xmpPath).isFile) v else base
    }

    private fun itemOf(origin: ServerOrigin, id: Long, name: String? = null): SeriesItem {
        val r = agent.record(origin, id)
        val folder = work.folder(origin, id)
        return if (r != null && work.hasRaw(r)) SeriesItem(r.filename, work.raw(r).path, work.xmp(r).path)
        else SeriesItem(r?.filename ?: name ?: agent.knownName(origin, id) ?: "photo $id", File(folder, NOT_HERE).path, File(folder, "$NOT_HERE.xmp").path)
    }

    private const val NOT_HERE = ".not-downloaded"

    /** Photos not on the phone are fetched when the darkroom opens them; the next one ahead of time (brief P2e item 4). */
    object Source : PhotoSource {
        override suspend fun prepare(item: SeriesItem, progress: (String, Float?) -> Unit): SeriesItem {
            val (origin, id) = lplPhotoOf(item) ?: throw PhotoSourceException("this photo is not on the phone")
            val watcher = AppEngine.scope.launch {
                agent.progress.collect { m ->
                    m[origin to id]?.let { p -> progress(progressText(item.fileName, p.done, p.total), if (p.total > 0) p.done.toFloat() / p.total else null) }
                }
            }
            try {
                agent.take(origin, id)
            } catch (e: LplException) {
                throw PhotoSourceException("${item.fileName}: ${e.userText}")
            } catch (e: ServerNotAddedException) {
                throw PhotoSourceException("this server is not in the app's list")
            } finally {
                watcher.cancel()
            }
            return itemFor(origin, id)
        }

        override fun opened(item: SeriesItem, next: SeriesItem?) {
            val (origin, id) = lplPhotoOf(item) ?: return
            agent.opened(origin, id)
            val n = next?.let { lplPhotoOf(it) } ?: return
            // brief P2i: with automatic syncing off nothing is downloaded that the user did not ask for
            if (!File(next.rawPath).isFile && agent.fetchAhead) AppEngine.scope.launch { runCatching { agent.take(n.first, n.second, refresh = false) } }
        }

        override fun left(item: SeriesItem) {
            val (origin, id) = lplPhotoOf(item) ?: return
            agent.left(origin, id)
            syncLater(origin, id)
        }
    }

    fun progressText(name: String, done: Long, total: Long): String =
        if (total > 0) String.format(Locale.ROOT, "downloading %s… %d %%", name, (done * 100 / total).toInt())
        else String.format(Locale.ROOT, "downloading %s… %.1f MB", name, done / 1e6)

    /**
     * Sync in the background of the process; the darkroom never waits for the network (brief P2e). Brief P2i:
     * only with automatic syncing on; off, the edit stays on the phone until "sync now".
     */
    fun syncLater(origin: ServerOrigin, id: Long) {
        AppEngine.scope.launch {
            val o = agent.syncAfterDone(origin, id) ?: return@launch
            if (o == SyncOutcome.WAITING) SyncJob.schedule(app)
            else if (agent.pending().isNotEmpty()) SyncJob.schedule(app)
        }
    }

    // ------------------------------------------------------------------ heartbeat and jobs (foreground only)

    /** An `edit` or `open` job: the screen opens the photos and answers whether that worked. */
    class OpenRequest(val origin: ServerOrigin, val ids: List<Long>, val edit: Boolean, val done: CompletableDeferred<Boolean>)

    private val _openRequests = MutableSharedFlow<OpenRequest>(extraBufferCapacity = 8)
    val openRequests: SharedFlow<OpenRequest> = _openRequests.asSharedFlow()

    private var loop: CoJob? = null
    @Volatile private var sharedPoll: CoJob? = null
    private val jobLock = Mutex()

    /**
     * The app came to the foreground: heartbeat every 2 s (PLAN.md 4.1) and carry out the jobs LPL hands
     * to this device. Nothing of this runs in the background.
     */
    @Synchronized
    fun foreground() {
        if (loop?.isActive == true) return
        loop = AppEngine.scope.launch {
            // edits that waited while the app was away are sent now (the app is in front: no battery concern)
            // brief P2i: not with automatic syncing off
            // brief P3k: this work, the queued exports and the jobs below run in the process's scope, not as children
            // of this loop: leaving the foreground cancels the loop, and must not cancel a sync or a batch half-way
            // (the sync service carries them on with the app in the background)
            AppEngine.scope.launch { if (agent.pending().isNotEmpty()) agent.syncPendingAutomatically() }
            var beats = 0
            while (isActive) {
                if (servers.list().isNotEmpty()) {
                    val jobs = agent.heartbeat()
                    // brief P3i: exports that waited for their server go out as soon as it answers (looked at every ~10 s)
                    if (beats++ % EXPORT_QUEUE_BEATS == 0 && agent.queuedExports() > 0) AppEngine.scope.launch { agent.sendQueuedExports() }
                    // brief P3l: shared styles, presets and layouts: asked at most once a minute while the app is open
                    // (each pass also sends this phone's own changes, unless automatic sync is off)
                    if (sharedPoll?.isActive != true) sharedPoll = AppEngine.scope.launch { runCatching { agent.shared.pollIfDue() } }
                    // in the order LPL gave them, one after the other
                    if (jobs.isNotEmpty()) AppEngine.scope.launch { jobLock.withLock { for ((origin, job) in jobs) runJob(origin, job) } }
                    // brief P3k: a job kept by a background heartbeat (the sync service's, or the last one) that raced
                    // with the app coming back is run now, not only at the next start of the activity
                    if (keptJobs.isFile) runKeptJobs()
                }
                delay(HEARTBEAT_MS)
            }
        }
    }

    /**
     * Brief P3i: while an export to LightPhotoLibrary runs with the app in the background, the heartbeat goes on (every
     * 2 s, as in the foreground) so LPL shows the step "exporting". Jobs it hands out are dealt with as at the last
     * heartbeat of [background]. Ends with the export, or when the app is in the foreground again (its own loop).
     */
    private var exportBeat: CoJob? = null

    @Synchronized
    private fun exportHeartbeats() {
        if (exportBeat?.isActive == true) return
        exportBeat = AppEngine.scope.launch {
            while (isActive && controller.exports.job.value?.running == true) {
                if (loop?.isActive != true && servers.list().isNotEmpty()) heartbeatAndKeep()
                delay(HEARTBEAT_MS)
            }
        }
    }

    /**
     * One heartbeat of the background kind: sync and release jobs are done now, edit and open are kept for the screen
     * (on disk, run when the app is in the foreground again). Brief P3k: a sync or release of the photo open in the
     * darkroom is kept too: it is never synced behind the user's back.
     */
    private suspend fun heartbeatAndKeep() {
        val (now, kept) = BackgroundSync.splitJobs(agent.heartbeat(), controller.openRawPath() != null) { o, id -> isOpenInDarkroom(o, id) }
        for ((origin, job) in kept) keepJob(origin, job)
        if (now.isNotEmpty()) AppEngine.scope.launch { jobLock.withLock { for ((origin, job) in now) runJob(origin, job) } }
    }

    private fun isOpenInDarkroom(o: ServerOrigin, id: Long): Boolean {
        val open = controller.openRawPath() ?: return false
        return agent.record(o, id)?.let { work.raw(it).path == open } == true
    }

    // ------------------------------------------------------------------ brief P3k: the sync service

    /**
     * Work in flight the service must wait for: "done" handing its photo to the sync (the darkroom is closing it), or a
     * batch job being carried out (from its start to its `/done`).
     */
    private val handingOver = AtomicInteger(0)

    fun handOverStarted() { handingOver.incrementAndGet() }
    fun handOverEnded() { handingOver.decrementAndGet() }

    /** What LPL work there is now, for [BackgroundSync] (the service adds its own sync pass and counters). */
    fun syncWork(): SyncWork {
        val pending = agent.pending()
        val open = controller.openRawPath()
        val openCount = if (open == null) 0 else pending.count { work.raw(it).path == open }
        return SyncWork(
            autoSync = agent.autoSync,
            editsClosed = pending.size - openCount,
            editsOpen = openCount,
            exports = agent.queuedExports(),
            downloading = agent.transfers.copying(),
            inFlight = handingOver.get() > 0 || agent.steps.value.isNotEmpty(),
        )
    }

    /** The photos waiting to be sent that are not open in the darkroom (the notification's "n of N"). */
    fun waitingClosed(): List<Pair<ServerOrigin, Long>> {
        val open = controller.openRawPath()
        return agent.pending().filter { open == null || work.raw(it).path != open }.map { it.server to it.id }
    }

    /** Whether a server with waiting work did not answer its last call. */
    /**
     * Brief P4a: whether the phone has a network now (Android's ConnectivityManager; true until it said otherwise), for
     * "waiting for connection" in the list and the sync notification.
     */
    val online: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(true)
        runCatching {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            fun now() = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            flow.value = now()
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { flow.value = true }
                override fun onLost(network: android.net.Network) { flow.value = now() }
            })
        }
        flow
    }

    fun waitingServerUnreachable(): Boolean {
        val errors = agent.lastError.value
        val origins = agent.pending().map { it.server }.toSet() + if (agent.queuedExports() > 0) servers.list().map { it.origin } else emptyList()
        return origins.any { errors[it] != null }
    }

    /**
     * Start the sync service when there is LPL work in flight. Called while the activity is still visible (its
     * `onPause`, "done", a batch starting): Android 12 and later refuse a foreground service started from the
     * background. True when it was asked to start.
     */
    fun startSyncIfNeeded(): Boolean {
        if (servers.list().isEmpty()) return false
        val w = syncWork()
        if (!BackgroundSync.shouldStart(w)) return false
        return SyncService.start(app)
    }

    /** One heartbeat from the sync service, unless the foreground loop or the export's heartbeat already beats. */
    suspend fun serviceHeartbeat() {
        if (loop?.isActive == true || exportBeat?.isActive == true || servers.list().isEmpty()) return
        heartbeatAndKeep()
    }

    /** Whether the app is in the foreground (its own heartbeat loop runs). */
    val inForeground: Boolean get() = loop?.isActive == true

    /** The app left the foreground: stop the heartbeat, send a last one, leave pending syncs to the background job. */
    @Synchronized
    fun background() {
        loop?.cancel()
        loop = null
        AppEngine.scope.launch {
            if (servers.list().isNotEmpty()) {
                // LPL hands a job out once. Jobs that come with this last heartbeat: sync and release are
                // done now; edit and open need the screen and are kept (on disk) until the app is open again
                heartbeatAndKeep()
            }
            // a photo left open in the darkroom has its edit written by the activity; if the process ends
            // before the user comes back, the job sends it
            // brief P3i: exports that wait for their server go out with the next sync
            if (agent.pending().isNotEmpty() || controller.openRawPath() != null || agent.queuedExports() > 0) SyncJob.schedule(app)
            // brief P3l: changes to styles, presets and layouts made in this session go out now
            runCatching { agent.shared.passAll() }
        }
    }

    private val keptJobs get() = File(app.filesDir, "lpl/kept-jobs.txt")

    @Synchronized
    private fun keepJob(origin: ServerOrigin, job: Job) {
        keptJobs.parentFile?.mkdirs()
        keptJobs.appendText("${origin.url} ${job.id} ${job.kind} ${if (job.all) "all" else job.ids.joinToString(",")} ${if (job.open) "open" else "closed"}\n")
    }

    /** Jobs kept from the last heartbeat before the app left the foreground. */
    @Synchronized
    private fun takeKeptJobs(): List<Pair<ServerOrigin, Job>> {
        val f = keptJobs
        if (!f.isFile) return emptyList()
        val out = f.readLines().mapNotNull { l ->
            val p = l.split(' ')
            if (p.size != 4 && p.size != 5) return@mapNotNull null
            val o = ServerOrigin.parseExact(p[0]) ?: return@mapNotNull null
            val id = p[1].toLongOrNull() ?: return@mapNotNull null
            val all = p[3] == "all"
            o to Job(id, p[2], if (all) emptyList() else p[3].split(',').mapNotNull { it.toLongOrNull() }, all, open = p.getOrNull(4) != "closed")
        }
        f.delete()
        return out
    }

    const val HEARTBEAT_MS = 2_000L
    private const val EXPORT_QUEUE_BEATS = 5

    private suspend fun runJob(origin: ServerOrigin, job: Job) {
        // R13: a job handed out again (never reported done) that already ran here is reported, not run twice
        agent.ranBefore(origin, job)?.let { agent.jobDone(origin, job, it); return }
        val ok = when (job.kind) {
            "sync", "release" -> agent.runSyncOrRelease(origin, job)
            "edit" -> if (agent.runsAsBatch(origin, job)) {
                // brief P2k: copy first (progress, Stop), then open; the job is reported when the copying ends
                // brief P3k: the batch goes on with the app in the background (the sync service keeps the process);
                // the job is entered first (receive is idempotent) so the service sees the download from its first pass
                agent.transfers.receive(origin, job.id, agent.jobIds(origin, job).distinct())
                handOverStarted()
                val r = try {
                    SyncService.start(app)
                    agent.runEditJob(origin, job)
                } finally {
                    handOverEnded()
                }
                if (job.open && r.toOpen.isNotEmpty()) {
                    // not awaited: the job is reported already, and the next job must not wait for the user's editing
                    _openRequests.tryEmit(OpenRequest(origin, r.toOpen, true, CompletableDeferred()))
                }
                return
            } else openOrEdit(origin, job)
            "open" -> openOrEdit(origin, job)
            else -> false
        }
        agent.jobDone(origin, job, ok)
    }

    private suspend fun openOrEdit(origin: ServerOrigin, job: Job): Boolean {
        val ids = agent.jobIds(origin, job)
        if (ids.isEmpty()) return true
        val r = OpenRequest(origin, ids, job.kind == "edit", CompletableDeferred())
        return if (_openRequests.tryEmit(r)) withTimeoutOrNull(30 * 60_000L) { r.done.await() } ?: false else false
    }

    /** Run jobs kept from the last heartbeat (called when the app is in the foreground again). */
    fun runKeptJobs() {
        AppEngine.scope.launch {
            val kept = takeKeptJobs()
            if (kept.isNotEmpty()) jobLock.withLock { for ((origin, job) in kept) runJob(origin, job) }
        }
    }

    fun records(origin: ServerOrigin): List<PhotoRecord> = agent.photos.value.filter { it.server == origin }
}

/**
 * A thumbnail as raw RGBA in the photo's own folder. Brief P2f: shown even when older than the photo's XMP
 * (before P2f it was then dropped, so a photo whose XMP LightPhotoLibrary had just merged showed black);
 * [AppHost.thumbStale] has it rendered again in the background.
 */
internal object FolderThumb {
    fun load(f: File): RenderedImage? {
        if (!f.isFile) return null
        return runCatching {
            java.io.DataInputStream(f.inputStream().buffered()).use { s ->
                val w = s.readInt(); val h = s.readInt()
                require(w in 1..4096 && h in 1..4096)
                val px = ByteArray(w * h * 4); s.readFully(px); RenderedImage(w, h, px)
            }
        }.getOrNull()
    }

    /** Written whole and atomically like the photo's state (`AtomicFiles`); a failure only loses the thumbnail. */
    fun store(f: File, image: RenderedImage) {
        runCatching {
            val bytes = java.io.ByteArrayOutputStream(8 + image.rgba.size).also { b ->
                java.io.DataOutputStream(b).use { s -> s.writeInt(image.width); s.writeInt(image.height); s.write(image.rgba) }
            }.toByteArray()
            app.dtandroid.lpl.atomicWrite(f, bytes)
        }
    }
}

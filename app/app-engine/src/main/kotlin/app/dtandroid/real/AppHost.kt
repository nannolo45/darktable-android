// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Application
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.FileThumbnailStore
import app.dtandroid.darkroom.PhotoSource
import app.dtandroid.darkroom.PhotoSourceException
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.ThumbnailStore
import app.dtandroid.darkroom.Timeline
import app.dtandroid.darkroom.RgbaFile
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.FileLayoutStore
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.lpl.EditorPort
import app.dtandroid.lpl.Job
import app.dtandroid.lpl.LplAgent
import app.dtandroid.lpl.LplException
import app.dtandroid.lpl.PhotoRecord
import app.dtandroid.lpl.ServerNotAddedException
import app.dtandroid.lpl.ServerOrigin
import app.dtandroid.lpl.ServerStore
import app.dtandroid.lpl.SyncOutcome
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
            override fun store(item: SeriesItem, image: RenderedImage) { lplDir(item)?.let { FolderThumb.store(File(it, "thumb.rgba"), image) } ?: localThumbs.store(item, image) }
            override fun loadPicture(item: SeriesItem): RenderedImage? =
                lplDir(item)?.let { RgbaFile.read(File(it, "picture.rgba")) } ?: localThumbs.loadPicture(item)
            override fun storePicture(item: SeriesItem, image: RenderedImage) {
                lplDir(item)?.let { RgbaFile.write(File(it, "picture.rgba"), image) } ?: localThumbs.storePicture(item, image)
            }
        }
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

    val servers: ServerStore by lazy { ServerStore(File(app.filesDir, "lpl"), KeystoreSecretBox()) }
    val work: WorkStore by lazy { WorkStore(File(app.filesDir, "lpl/work")) }

    fun appVersion(): String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun versionText(): String {
        val dt = (AppEngine.status.value as? AppEngine.Status.Ready)?.info?.darktableVersion ?: "not started"
        return "dt-android ${appVersion()} / darktable $dt"
    }

    val agent: LplAgent by lazy { LplAgent(servers, work, Editor, ::versionText) }

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
    }

    const val PREVIEW_SIZE = 1920
    const val PREVIEW_QUALITY = 90

    /** The whole photo at [PREVIEW_SIZE] px on the longer side, from the full pipe (cancellable). */
    suspend fun previewPicture(e: app.dtandroid.engine.api.DarktableEngine): RenderedImage {
        Timeline.mark("LPL preview: render start")
        try {
            // brief P2f: never render a file darktable found no picture in (see DarkroomController.hasPicture)
            val info = e.imageInfo()
            if (info.width <= 0 || info.height <= 0) throw EngineException(EngineError.IO, "no picture in this file")
            val full = e.renderSize(1.0)
            val scale = minOf(1.0, PREVIEW_SIZE.toDouble() / maxOf(full.w, full.h))
            val sz = e.renderSize(scale)
            return e.renderRegion(0, 0, sz.w, sz.h, scale).also { Timeline.mark("LPL preview: render end") }
        } catch (ex: EngineException) {
            Timeline.mark(if (ex.error == EngineError.CANCELLED) "LPL preview: render cancelled" else "LPL preview: render failed")
            throw ex
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
    fun itemFor(origin: ServerOrigin, id: Long, name: String? = null): SeriesItem {
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
            if (!File(next.rawPath).isFile) AppEngine.scope.launch { runCatching { agent.take(n.first, n.second, refresh = false) } }
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

    /** Sync in the background of the process; the darkroom never waits for the network (brief P2e). */
    fun syncLater(origin: ServerOrigin, id: Long) {
        AppEngine.scope.launch {
            val o = agent.sync(origin, id)
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
            launch { if (agent.pending().isNotEmpty()) agent.syncPending() }
            while (isActive) {
                if (servers.list().isNotEmpty()) {
                    val jobs = agent.heartbeat()
                    // in the order LPL gave them, one after the other
                    if (jobs.isNotEmpty()) launch { jobLock.withLock { for ((origin, job) in jobs) runJob(origin, job) } }
                }
                delay(HEARTBEAT_MS)
            }
        }
    }

    /** The app left the foreground: stop the heartbeat, send a last one, leave pending syncs to the background job. */
    @Synchronized
    fun background() {
        loop?.cancel()
        loop = null
        AppEngine.scope.launch {
            if (servers.list().isNotEmpty()) {
                // LPL hands a job out once. Jobs that come with this last heartbeat: sync and release are
                // done now; edit and open need the screen and are kept (on disk) until the app is open again
                val now = ArrayList<Pair<ServerOrigin, Job>>()
                for ((origin, job) in agent.heartbeat()) {
                    if (job.kind == "sync" || job.kind == "release") now += origin to job else keepJob(origin, job)
                }
                if (now.isNotEmpty()) launch { jobLock.withLock { for ((origin, job) in now) runJob(origin, job) } }
            }
            // a photo left open in the darkroom has its edit written by the activity; if the process ends
            // before the user comes back, the job sends it
            if (agent.pending().isNotEmpty() || controller.openRawPath() != null) SyncJob.schedule(app)
        }
    }

    private val keptJobs get() = File(app.filesDir, "lpl/kept-jobs.txt")

    @Synchronized
    private fun keepJob(origin: ServerOrigin, job: Job) {
        keptJobs.parentFile?.mkdirs()
        keptJobs.appendText("${origin.url} ${job.id} ${job.kind} ${if (job.all) "all" else job.ids.joinToString(",")}\n")
    }

    /** Jobs kept from the last heartbeat before the app left the foreground. */
    @Synchronized
    private fun takeKeptJobs(): List<Pair<ServerOrigin, Job>> {
        val f = keptJobs
        if (!f.isFile) return emptyList()
        val out = f.readLines().mapNotNull { l ->
            val p = l.split(' ')
            if (p.size != 4) return@mapNotNull null
            val o = ServerOrigin.parseExact(p[0]) ?: return@mapNotNull null
            val id = p[1].toLongOrNull() ?: return@mapNotNull null
            val all = p[3] == "all"
            o to Job(id, p[2], if (all) emptyList() else p[3].split(',').mapNotNull { it.toLongOrNull() }, all)
        }
        f.delete()
        return out
    }

    const val HEARTBEAT_MS = 2_000L

    private suspend fun runJob(origin: ServerOrigin, job: Job) {
        // R13: a job handed out again (never reported done) that already ran here is reported, not run twice
        agent.ranBefore(origin, job)?.let { agent.jobDone(origin, job, it); return }
        val ok = when (job.kind) {
            "sync", "release" -> agent.runSyncOrRelease(origin, job)
            "edit", "open" -> {
                val ids = agent.jobIds(origin, job)
                if (ids.isEmpty()) true else {
                    val r = OpenRequest(origin, ids, job.kind == "edit", CompletableDeferred())
                    if (_openRequests.tryEmit(r)) withTimeoutOrNull(30 * 60_000L) { r.done.await() } ?: false else false
                }
            }
            else -> false
        }
        agent.jobDone(origin, job, ok)
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

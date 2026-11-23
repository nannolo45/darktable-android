// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ImageInfo
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.paramAt
import app.dtandroid.engine.api.XmpCheck
import app.dtandroid.engine.api.XmpProblemReason
import app.dtandroid.schema.AspectSpec
import app.dtandroid.schema.Binding
import app.dtandroid.schema.CropAspect
import app.dtandroid.schema.CropTool
import app.dtandroid.schema.Computed
import app.dtandroid.schema.ComputedList
import app.dtandroid.schema.ComputedSlider
import app.dtandroid.schema.ComputedToggle
import app.dtandroid.schema.Control
import app.dtandroid.engine.api.LayoutStore
import app.dtandroid.engine.api.LayoutStoreException
import app.dtandroid.schema.ParamRuleInputs
import app.dtandroid.schema.Rules
import app.dtandroid.schema.layout.BuiltinLayouts
import app.dtandroid.schema.layout.LayoutCodec
import app.dtandroid.schema.layout.QuickAccess
import app.dtandroid.schema.UiSchema
import app.dtandroid.schema.PickSelection
import app.dtandroid.schema.PickerFormulas
import app.dtandroid.schema.PickerReadout
import app.dtandroid.schema.PickerSetting
import app.dtandroid.schema.PickerSpec
import app.dtandroid.schema.PickerTool
import app.dtandroid.schema.settingNumber
import app.dtandroid.engine.api.CoordsDirection
import app.dtandroid.engine.api.PickRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What part of the photo the screen shows: view size in pixels, zoom (1 = fit) and centre (0..1). */
data class Viewport(val widthPx: Int, val heightPx: Int, val zoom: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f)

/**
 * The darkroom's logic, shared by the phone and tablet layouts. It talks to
 * the engine only through [DarktableEngine] and keeps the screen's state in
 * [state]. Engine calls that belong together run under one lock; a new change
 * cancels the render in flight first (engine-api.md 2.7, ui-spec.md 4).
 */
class DarkroomController(
    private val engine: DarktableEngine,
    val schema: UiSchema,
    val builtins: BuiltinLayouts,
    /** module layouts (PLAN.md 3.9); the darkroom shows the current one */
    val layouts: LayoutStore,
    private val scope: CoroutineScope,
    private val previewMaxSize: Int = 1024,
    /**
     * Brief P2i: the largest preview asked for the view at fit (the longer side of the photo's frame on screen,
     * at least [previewMaxSize]). See [previewSize] and the P2i report for the choice.
     */
    private val fitPreviewMax: Int = FIT_PREVIEW_MAX,
    private val thumbnailSize: Int = 128,
    /** brief P2b: the size of the picture that follows a finger, chosen from measured render times */
    val dragPicture: DragPicture = DragPicture(maxSize = previewMaxSize),
    /** brief P2b: thumbnails kept between starts (the real engine needs a RAW decode per thumbnail); null = render all */
    private val thumbnailStore: ThumbnailStore? = null,
) {
    /** brief P2e: photos not on the phone yet are fetched through this before they open; null = local files only */
    @Volatile var source: PhotoSource? = null

    private val _state = MutableStateFlow(DarkroomState())
    val state: StateFlow<DarkroomState> = _state.asStateFlow()

    /** Slider values while a finger is on them, by instance and control, ahead of the engine. */
    private val _live = MutableStateFlow<Map<Pair<InstanceKey, Int>, Double>>(emptyMap())
    val liveValues: StateFlow<Map<Pair<InstanceKey, Int>, Double>> = _live.asStateFlow()

    private val lock = Mutex()

    // ------------------------------------------------------------------ brief P2f: interactive work first
    //
    // The engine holds one photo and runs one call at a time. Work the user waits for (opening a photo, a
    // change, the drag picture, the preview and the visible region, before/after, export, writing the XMP)
    // runs through [fg]; work nobody waits for (the 1920 px preview for LightPhotoLibrary, thumbnails of
    // photos not shown) runs through [bg]. Order on the engine thread after P2f:
    //  1. interactive work, in the order it asks, one at a time (the lock);
    //  2. background work only when no interactive work waits or runs, no finger is dragging, and the
    //     darkroom has been quiet for [QUIET_MS];
    //  3. interactive work that arrives while background work runs cancels its render (dtb_render_cancel,
    //     repeated until the background task lets go of the lock) and goes first; the background task is
    //     started again from the beginning once the darkroom is quiet again.
    // A background task cannot be stopped inside an engine call that does not render (opening a photo,
    // loading the RAW for the full pipe): interactive work then waits for that call only.

    private val interactive = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var bgActive = false
    @Volatile private var lastInteractiveEnd = 0L

    /**
     * Whether a background task's render is running now. Only such a render is ever cancelled for interactive
     * work: the flag is cleared, under [cancelGuard], the moment the render returns, so a cancel can never land
     * on the interactive render that follows (run 44616800258: the darkroom stopped showing previews; a stray
     * cancel ending an interactive render that nothing asked again is one way that happens).
     */
    @Volatile private var bgRendering = false
    private val cancelGuard = Any()

    private fun cancelBackgroundRender() = synchronized(cancelGuard) { if (bgRendering) engine.renderCancel() }

    /** The engine as background tasks see it: its renders mark themselves, so only they are cancelled. */
    private val bgEngine: DarktableEngine = object : DarktableEngine by engine {
        private suspend fun <T> marked(block: suspend () -> T): T {
            synchronized(cancelGuard) { bgRendering = true }
            try { return block() } finally { synchronized(cancelGuard) { bgRendering = false } }
        }
        override suspend fun renderPreview(maxSize: Int) = marked { engine.renderPreview(maxSize) }
        override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double) = marked { engine.renderRegion(x, y, w, h, scale) }
    }

    /** Run [block] under the lock as interactive work: background work is cancelled and waits. */
    private suspend fun <T> fg(block: suspend () -> T): T {
        interactive.incrementAndGet()
        try {
            val canceller = if (bgActive) scope.launch {
                while (bgActive) { cancelBackgroundRender(); delay(CANCEL_REPEAT_MS) }
            } else null
            return lock.withLock {
                canceller?.let { it.cancel(); it.join() }
                block()
            }
        } finally {
            lastInteractiveEnd = System.nanoTime()
            interactive.decrementAndGet()
        }
    }

    private val dragging get() = dragSize != null

    /** Brief P2f: the scheduler's state in words, for test failures and the timings page. */
    fun schedulerState(): String = "lock ${if (lock.isLocked) "held" else "free"}, interactive $interactive, background ${if (bgActive) "running" else "idle"}, " +
        "dragging $dragging, quiet ${quiet()}, photo ${_state.value.current?.fileName} open ${_state.value.info != null}, loading ${_state.value.loading}, " +
        "fetching $fetching, working ${_state.value.working}"

    private fun quiet(): Boolean =
        interactive.get() == 0 && !dragging && (System.nanoTime() - lastInteractiveEnd) / 1_000_000 >= QUIET_MS

    /**
     * Run [block] under the lock as background work: only when the darkroom is quiet; if interactive work
     * cancels it (a CANCELLED render), it is run again from the start later. Returns [block]'s result.
     */
    suspend fun <T> bg(what: String, block: suspend (DarktableEngine) -> T): T {
        while (true) {
            while (!quiet()) delay(BG_POLL_MS)
            // null = put back (interactive work came first); a result, even a null one, ends the loop
            val r: Result<T>? = lock.withLock {
                if (!quiet()) return@withLock null
                bgActive = true
                try {
                    Result.success(block(bgEngine))
                } catch (e: EngineException) {
                    if (e.error == EngineError.CANCELLED) null else Result.failure(e)
                } finally {
                    bgActive = false
                }
            }
            if (r != null) return r.getOrThrow()
            Timeline.mark("background: $what put back for interactive work")
        }
    }

    private var renderJob: Job? = null
    private var regionJob: Job? = null
    private var viewport: Viewport? = null

    private data class Drag(val key: InstanceKey, val controlId: Int, val binding: Binding, val value: Double, val integer: Boolean, val end: Boolean,
        /** brief P3e-b: a band graph's drag writes these fields (all that changed since the finger came down) instead of one binding */
        val writes: List<Pair<String, JsonPrimitive>>? = null)

    private val drags = Channel<Drag>(Channel.CONFLATED)

    init {
        scope.launch { for (d in drags) applyDrag(d) }
    }

    /** Run a suspending action of this controller from the interface. */
    fun launch(block: suspend DarkroomController.() -> Unit): Job = scope.launch { block() }

    // ------------------------------------------------------------------ start and photos

    /** Open [series] at [index]; renders a thumbnail of every photo first (the filmstrip). */
    suspend fun start(series: Series, index: Int, filmstrip: Boolean, thumbnails: Boolean = true) {
        _state.update { it.copy(series = series, index = index, filmstrip = filmstrip, loading = true) }
        loadLayout(initial = true)
        if (thumbnails) {
            // brief P2f: the stored thumbnails only. Rendering the missing ones needs the engine's one session,
            // so they are made in the background when no photo is open ([renderThumbnails]), never before the
            // photo the user asked for (P2b rendered them all first, so the photo waited for them)
            val thumbs = HashMap<Int, app.dtandroid.engine.api.RenderedImage>()
            for ((i, item) in series.items.withIndex()) thumbnailStore?.load(item)?.let { thumbs[i] = it }
            if (thumbnailStore == null) {
                // the interface preview on the stand-in engine keeps no thumbnails between starts: it renders its
                // generated pictures first, as before P2f (each takes a few milliseconds there)
                fg {
                    for ((i, item) in series.items.withIndex()) {
                        if (source != null && !File(item.rawPath).isFile) continue
                        if (knownUnreadable(item.rawPath)) continue
                        try {
                            val stamp = stampOf(item.rawPath)
                            openOrRemember(engine, item.rawPath, item.xmpPath.takeIf { File(it).isFile }, stamp)
                            // brief P2h: never a render of a session without a picture (closed already then)
                            if (pictureOrClose(engine, item.rawPath, stamp) == null) continue
                            try { thumbs[i] = engine.renderPreview(thumbnailSize) } finally { runCatching { engine.close() } }
                        } catch (e: EngineException) {
                            // a photo that cannot be opened has no thumbnail
                        }
                    }
                }
            }
            _state.update { it.copy(thumbnails = thumbs) }
        }
        openPhoto(index, saveCurrent = false)
    }

    /**
     * Brief P2f: thumbnails for the photos of [items] that have none, or whose thumbnail [stale] says is
     * older than their edit, rendered as background work while no photo is open. Returns how many were made.
     */
    suspend fun renderThumbnails(items: List<SeriesItem>, stale: (SeriesItem) -> Boolean = { thumbnailStore?.load(it) == null }): Int {
        var n = 0
        for (item in items) {
            if (!File(item.rawPath).isFile && source != null) continue
            if (!stale(item)) continue
            val t = try {
                bg("thumbnail ${item.fileName}") { e ->
                    if (_state.value.info != null || fetching) return@bg null
                    // brief P2h: a file known to hold no picture is not opened again until it changes
                    if (knownUnreadable(item.rawPath)) return@bg null
                    val stamp = stampOf(item.rawPath)
                    openOrRemember(e, item.rawPath, item.xmpPath.takeIf { File(it).isFile }, stamp)
                    var open = true
                    try {
                        // no picture: pictureOrClose closed the session already
                        if (pictureOrClose(e, item.rawPath, stamp) == null) { open = false; return@bg null }
                        // brief P2i: a timeline line for thumbnail renders and stores (P2h's l4 failure)
                        Timeline.mark("thumbnail pass: ${thumbName(item)} render start")
                        e.renderPreview(thumbnailSize).also { Timeline.mark("thumbnail pass: ${thumbName(item)} rendered") }
                    } finally { if (open) runCatching { e.close() } }
                }
            } catch (e: EngineException) { null } ?: continue
            Timeline.mark("thumbnail pass: ${thumbName(item)} store")
            thumbnailStore?.store(item, t)
            val i = _state.value.series.items.indexOf(item)
            if (i >= 0) _state.update { st -> st.copy(thumbnails = st.thumbnails + (i to t)) }
            n++
        }
        return n
    }

    /** Brief P2i: a photo in the timeline: its folder's name and its file name (file names repeat across folders). */
    private fun thumbName(item: SeriesItem) = "${File(item.rawPath).parentFile?.name}/${item.fileName}"

    /** Switch to another photo of the series; the current edit is written to its sidecar first. */
    suspend fun openPhoto(index: Int, saveCurrent: Boolean = true) {
        var item = _state.value.series.items.getOrNull(index) ?: return
        val g = ++generation
        Timeline.mark("darkroom: open ${item.fileName} asked")
        cancelRenders()
        val src = source
        val fetch = src != null && !File(item.rawPath).isFile
        // brief P2f: never a black frame: the best picture already there, and what is happening
        showPlaceholder(index, item, if (fetch) "getting ${item.fileName}…" else "opening ${item.fileName}…")
        fg {
            leaveLocked(index, saveCurrent, fetch)
            if (!fetch) openLocked(item)
        }
        if (fetch && src != null) {
            // brief P2e: fetch the photo first, outside the lock (the engine is free meanwhile)
            try {
                item = src.prepare(item) { text, share -> if (generation == g) _state.update { it.copy(working = text, workingProgress = share) } }
            } catch (e: PhotoSourceException) {
                if (generation == g) { fetching = false; _state.update { it.copy(message = e.message, loading = false, working = null, workingProgress = null) } }
                return
            }
            if (generation != g) return // the user went to another photo, or left the darkroom, meanwhile
            replaceItem(index, item)
            _state.update { it.copy(message = null, working = "opening ${item.fileName}…", workingProgress = null) }
            fg {
                if (generation != g) return@fg
                openLocked(item)
            }
            if (generation != g) return
        }
        if (_state.value.info != null) {
            lastOpened = _state.value.series to index
            src?.opened(item, _state.value.series.items.getOrNull(index + 1))
        }
        requestRender()
    }

    /**
     * Brief P2f: files darktable opened but found no picture in (an undecodable or truncated RAW).
     * Rendering such a session hung the engine thread inside darktable's pipe and was followed by native heap
     * corruption on the emulator (runs 44616800258 and 44623988726, a test file of junk bytes the background
     * thumbnail pass picked up); the app never renders them. See the P2f report for the engine side.
     *
     * Brief P2h: kept with the file's size and modification time when it was found so; a file that changed
     * since (a repaired download) is tried again ([knownUnreadable]). For this process only.
     */
    private val unreadable = java.util.concurrent.ConcurrentHashMap<String, FileStamp>()

    private data class FileStamp(val length: Long, val modified: Long)

    private fun stampOf(path: String) = File(path).let { FileStamp(it.length(), it.lastModified()) }

    /**
     * Brief P2h: whether [rawPath] is remembered as holding no picture and has not changed since. A file that
     * changed is forgotten here, so the caller opens it again.
     */
    fun knownUnreadable(rawPath: String): Boolean {
        val s = unreadable[rawPath] ?: return false
        if (s == stampOf(rawPath)) return true
        if (unreadable.remove(rawPath, s)) Timeline.mark("darkroom: ${File(rawPath).name} changed since it had no picture, tried again")
        return false
    }

    /**
     * Brief P2h: right after `open` of [rawPath] (its [stamp] taken before the open): the session's picture, or
     * null when there is none. "None" is a picture of 0 x 0 or any engine error from `image_info` except
     * CANCELLED (the bridge builds the full pipe there and answers DTB_E_ENGINE when the image could not be
     * loaded): the session is then closed at once and the file remembered, so nothing renders it and no
     * background pass opens it again until it changes. CANCELLED is passed on (the caller's work runs again).
     */
    private suspend fun pictureOrClose(e: DarktableEngine, rawPath: String, stamp: FileStamp): ImageInfo? {
        val why = try {
            val i = e.imageInfo()
            if (i.width > 0 && i.height > 0 && i.rawWidth >= 0 && i.rawHeight >= 0) {
                // brief P2i: a file remembered as having none that now has a picture is forgotten
                if (unreadable.remove(rawPath) != null) Timeline.mark("darkroom: ${File(rawPath).name} has a picture now, no longer remembered as unreadable")
                return i
            }
            "${i.width}x${i.height}"
        } catch (ex: EngineException) {
            if (ex.error == EngineError.CANCELLED) throw ex
            "image_info answered ${ex.message}"
        }
        runCatching { e.close() }
        unreadable[rawPath] = stamp
        Timeline.mark("darkroom: ${File(rawPath).name} has no picture ($why), session closed, not rendered")
        return null
    }

    private fun noPicture(): Nothing = throw EngineException(EngineError.IO, NO_PICTURE)

    /**
     * Brief P1b (E1): `open` itself refuses a file without a decodable picture
     * ([EngineException.isUndecodableFile]). Handled as P2h/P2i handle "no picture" from image_info: remembered
     * with [stamp] for background passes, and the darkroom's NO_PICTURE sentence ([noPicture]). Any other
     * failure (a missing file, an unreadable XMP) is passed on as it is and not remembered.
     */
    private suspend fun openOrRemember(e: DarktableEngine, rawPath: String, xmp: String?, stamp: FileStamp) {
        try {
            e.open(rawPath, xmp)
        } catch (ex: EngineException) {
            if (!ex.isUndecodableFile) throw ex
            unreadable[rawPath] = stamp
            Timeline.mark("darkroom: ${File(rawPath).name} has no picture (open answered ${ex.message}), not opened")
            noPicture()
        }
    }

    /** Brief P2f: the photo's last picture, else its thumbnail, while it opens; and what happens, in words. */
    private fun showPlaceholder(index: Int, item: SeriesItem, working: String) {
        val pic = thumbnailStore?.loadPicture(item) ?: _state.value.thumbnails[index] ?: thumbnailStore?.load(item)
        _state.update { it.copy(placeholder = pic, working = working, workingProgress = null) }
        if (pic != null) Timeline.mark("darkroom: stored picture shown")
    }

    /** Brief P2f: keep the photo's last preview, to show it while the photo opens next time. */
    private fun keepPicture() {
        val st = _state.value
        val item = st.current ?: return
        val p = st.preview?.image ?: return
        if (st.info == null) return
        thumbnailStore?.storePicture(item, Thumbnails.downscale(p, ThumbnailStore.PICTURE_SIZE))
    }

    /** Brief P2f: the series and position of the last photo that opened, whatever its source (the timings run on it). */
    @Volatile var lastOpened: Pair<Series, Int>? = null
        private set

    /** brief P2e: counts photo switches and closes, so a fetch that finishes late opens nothing */
    @Volatile private var generation = 0

    /** brief P2e: true while the darkroom fetches the photo it is about to open (no session is open then). */
    @Volatile private var fetching = false

    private suspend fun leaveLocked(index: Int, saveCurrent: Boolean, fetch: Boolean) {
        val left = _state.value.current.takeIf { _state.value.info != null }
        keepPicture()
        // brief P3a: another photo leaves the crop tool: its frame is written before the edit is saved
        leaveCropLocked()
        if (saveCurrent) saveLocked()
        runCatching { engine.close() }
        fetching = fetch
        _state.update {
            it.copy(index = index, loading = true, preview = null, region = null, beforePreview = null, before = false,
                expanded = null, overlay = null, message = null, readOnlyReason = null, info = null, syncStep = null, syncProgress = null, lastEdit = null,
                cropTool = null, picker = null, showMask = null, maskModes = emptyMap())
        }
        masksOrNull?.reset() // brief P3d: another photo: no drawing, no sheet, no module chosen
        // told after the state says no photo is open, so the source sees the photo as closed
        if (left != null) source?.left(left)
    }

    private suspend fun openLocked(item: SeriesItem) {
        fetching = false
        try {
            val xmp = item.xmpPath.takeIf { File(it).isFile }
            val check = xmp?.let { engine.xmpCheck(it) }
            // brief P2i: a photo the user opens is always tried, even when remembered as having no picture: the
            // memory is for background passes only (image_info also fails when the full image could not be loaded
            // for want of memory, and a good photo must not stay refused until the app restarts)
            if (unreadable.containsKey(item.rawPath)) Timeline.mark("darkroom: ${item.fileName} remembered as having no picture, tried again (the user opened it)")
            val stamp = stampOf(item.rawPath)
            Timeline.mark("darkroom: engine open start")
            openOrRemember(engine, item.rawPath, xmp, stamp)
            // brief P2h: 0 x 0 or an engine error from image_info: the session is already closed here
            val info = pictureOrClose(engine, item.rawPath, stamp) ?: noPicture()
            Timeline.mark("darkroom: engine open end")
            _state.update { it.copy(info = info, readOnlyReason = if (info.readOnly) readOnlyText(check) else null) }
            refreshLocked()
        } catch (e: EngineException) {
            // brief P2h: no photo shows as open, so no session stays open either (background work needs its own)
            runCatching { engine.close() }
            // brief P2h: the app's sentence as it is, without the error's name in front
            val why = if (isNoPicture(e)) NO_PICTURE else e.message
            _state.update { it.copy(info = null, message = "cannot open ${item.fileName}: $why", working = null, workingProgress = null) }
        }
        _state.update { it.copy(loading = false, working = if (it.info != null && it.preview == null) RENDERING else it.working) }
    }

    /** brief P2e: a photo of the series got its name or its final paths (from LightPhotoLibrary). */
    fun replaceItem(index: Int, item: SeriesItem) = _state.update { s ->
        if (index !in s.series.items.indices || s.series.items[index] == item) s
        else s.copy(series = s.series.copy(items = s.series.items.toMutableList().also { it[index] = item }))
    }

    /**
     * Leave the photo (brief P2b: back to the working folder): the edit is written to its
     * sidecar (not for read-only photos), the session closed and the pictures dropped.
     */
    suspend fun closePhoto() {
        generation++
        fetching = false
        cancelRenders()
        fg {
            val left = _state.value.current.takeIf { _state.value.info != null }
            keepPicture()
            // brief P3a: "done" and "back" leave the crop tool: its frame is written before the edit is saved
            leaveCropLocked()
            saveLocked()
            runCatching { engine.close() }
            _state.update {
                it.copy(info = null, preview = null, region = null, beforePreview = null, before = false, modules = emptyList(),
                    expanded = null, overlay = null, readOnlyReason = null, loading = false, placeholder = null, working = null, workingProgress = null,
                    cropTool = null, picker = null, showMask = null, maskModes = emptyMap())
            }
            masksOrNull?.reset()
            if (left != null) source?.left(left)
        }
    }

    /** Write the current edit (not for read-only photos). Called when leaving the darkroom. */
    suspend fun save() {
        cancelRenders()
        fg { keepPicture(); saveLocked() }
    }

    private suspend fun saveLocked() {
        val s = _state.value
        val item = s.current ?: return
        if (s.info == null || s.readOnly) return
        try {
            engine.xmpWrite(item.xmpPath)
        } catch (e: EngineException) {
            _state.update { it.copy(message = "edit not saved: ${e.message}") }
        }
    }

    private fun readOnlyText(check: XmpCheck?): String {
        val p = check?.problems?.firstOrNull()
            ?: return "This photo opens for viewing only: its edit failed the version check. Its XMP is never changed."
        val name = schema.modules[p.operation]?.name ?: p.operation
        val what = when (p.reason) {
            XmpProblemReason.UNKNOWN_OPERATION -> "the module \"${p.operation}\", which this app does not know"
            XmpProblemReason.NEWER_MODULE_VERSION -> "$name version ${p.modversion}, newer than this app's version ${p.engineVersion}"
            XmpProblemReason.NEWER_BLEND_VERSION -> "blending settings newer than this app knows (${p.operation})"
            XmpProblemReason.NEWER_MASKS_VERSION -> "masks newer than this app knows (${p.operation})"
        }
        val more = (check.problems.size - 1).takeIf { it > 0 }?.let { " and $it more" } ?: ""
        return "Read only: this edit uses $what$more. Update the app to edit it; its XMP is never changed here."
    }

    // ------------------------------------------------------------------ state from the engine

    /** Brief P3c deliverable 5: the last answer of each action list, with the photo and parameters it was read for. */
    private val listCache = HashMap<Pair<InstanceKey, Int>, Pair<String, kotlinx.serialization.json.JsonElement>>()

    private suspend fun refreshLocked() {
        val modules = engine.modules()
        val history = engine.history()
        val undo = engine.undoState()
        val actions = HashMap<InstanceKey, JsonObject>()
        for (m in modules) {
            val ms = schema.modules[m.op] ?: continue
            val gets = ms.allControls().mapNotNull { (it as? Control.Slider)?.binding as? Binding.Action }.map { it.get }.distinct().toList()
            for (g in gets) {
                runCatching { engine.moduleAction(m.op, m.priority, g) as? JsonObject }.getOrNull()?.let { actions[m.key] = it }
            }
        }
        // brief P3c deliverable 5: the lists the engine gives per photo, read again when their instance changed
        val lists = HashMap<Pair<InstanceKey, Int>, kotlinx.serialization.json.JsonElement>()
        for (m in modules) {
            val ms = schema.modules[m.op] ?: continue
            for ((id, l) in ms.actionLists) {
                val stamp = "${_state.value.index} ${m.enabled} ${m.params}"
                val cached = listCache[m.key to id]
                val answer = if (cached != null && cached.first == stamp) cached.second
                    else runCatching { engine.moduleAction(m.op, m.priority, l.list) }.getOrNull()?.also { listCache[m.key to id] = stamp to it }
                if (answer != null) lists[m.key to id] = answer
            }
        }
        // brief P3d: the mask mode of every instance that can have one, for the mask indicator in the module headers
        // (an instance not in the history has darktable's default blending: no mask)
        val maskModes = HashMap<InstanceKey, String>()
        for (m in modules) if (m.supportsBlending && m.inHistory) {
            runCatching { Masks.modeName(engine.blendGet(m.op, m.priority)) }.getOrNull()?.let { maskModes[m.key] = it }
        }
        val info = engine.imageInfo()
        livePatched.clear()
        _state.update { s ->
            val expanded = s.expanded?.takeIf { k -> modules.any { it.key == k } }
            // brief P3d: "show mask" ends when its instance is gone, off, or has no drawn or parametric mask any more
            val shown = s.showMask?.takeIf { k -> modules.any { it.key == k && it.enabled } && Masks.displayable(maskModes[k]) }
            s.copy(modules = modules, history = history, undo = undo, actionValues = actions, info = info, expanded = expanded, actionLists = lists,
                maskModes = maskModes, showMask = shown)
        }
    }

    /**
     * Run a change: cancel the render, call the engine, re-read its state, render again.
     * Engine errors become [DarkroomState.message].
     */
    private suspend fun change(block: suspend () -> Unit) {
        cancelRenders()
        fg {
            val before = _state.value.modules
            try {
                // the "before" picture is kept for the session (engine-api.md, clarifications after P2a)
                _state.update { it.copy(message = null) }
                // brief P3a: an open crop tool is left first: its frame is written, the change applies on top
                commitCropLocked(close = false)
                block()
            } catch (e: EngineException) {
                _state.update { it.copy(message = e.message) }
            }
            try { refreshLocked() } catch (e: EngineException) { _state.update { it.copy(message = e.message) } }
            // brief P3a: the tool again on the new state (its uncropped picture rendered again only when needed)
            reopenCropLocked(before)
            // brief P3c: the armed picker's selection follows the geometry; its values are read again
            pickerAfterChangeLocked(before)
            // brief P3d: the masks tab reads its module's blending and the photo's shapes again (undo and redo too)
            masksOrNull?.afterChangeLocked(engine)
        }
        requestRender()
    }

    // ------------------------------------------------------------------ edits

    suspend fun setEnabled(key: InstanceKey, enabled: Boolean) = change { engine.setEnabled(key.op, key.priority, enabled) }

    suspend fun reset(key: InstanceKey) = change { engine.moduleReset(key.op, key.priority) }

    /** A list entry, a switch, or an override button: one history step. */
    suspend fun setParam(key: InstanceKey, path: String, value: JsonPrimitive) = change {
        engine.paramSet(key.op, key.priority, path, value)
        engine.historySeal()
    }

    /** Exact entry or reset to default of a slider, through its binding. */
    suspend fun setSlider(key: InstanceKey, binding: Binding, value: Double, integer: Boolean, controlId: Int? = null) {
        if (cropMargin(key, binding, value)) return // brief P3a: a margin of the frame in the crop tool
        if (binding is Binding.Setting) { if (controlId != null) setSetting(key, controlId, JsonPrimitive(value)); return }
        setSliderNow(key, binding, value, integer, controlId)
    }

    private suspend fun setSliderNow(key: InstanceKey, binding: Binding, value: Double, integer: Boolean, controlId: Int?) = change {
        writeBinding(key, binding, value, integer, controlId)
        engine.historySeal()
    }

    // ------------------------------------------------------------------ brief P2j: computed controls

    /** Last value the app set on a computed slider, for values desktop keeps on screen only (hue at chroma 0). */
    private val remembered = HashMap<Pair<InstanceKey, Int>, Double>()

    /** Parameters written during a drag, before modules() is read again when the finger lifts. */
    private val livePatched = HashMap<InstanceKey, JsonObject>()

    fun recall(key: InstanceKey, id: Int): Double? = remembered[key to id]

    /** What a computed slider shows: desktop's value from the parameters, else the last value set, else null. */
    fun computedValue(s: DarkroomState, key: InstanceKey, controlId: Int): Double? {
        val model = schema.modules[key.op]?.computed?.get(controlId) as? ComputedSlider ?: return null
        val m = s.modules.firstOrNull { it.key == key } ?: return null
        return model.value(m.params) { id -> remembered[key to id] } ?: remembered[key to controlId]
    }

    /** An entry of a computed list chosen: the parameters desktop's callback writes, one history step. */
    suspend fun chooseComputed(key: InstanceKey, controlId: Int, index: Int) = change {
        val model = schema.modules[key.op]?.computed?.get(controlId) as? ComputedList ?: throw EngineException(EngineError.STATE, "no list here")
        val m = _state.value.modules.firstOrNull { it.key == key } ?: throw EngineException(EngineError.STATE, "no such instance")
        // brief P3b (P2j section 6): values outside a field's limits go through the engine's module action
        model.chooseAction(index, m.params)?.let { a ->
            engine.moduleAction(key.op, key.priority, a.action, a.args)
            engine.historySeal()
            return@change
        }
        val writes = model.choose(index, m.params)
        if (writes.isNotEmpty()) {
            engine.paramsSet(key.op, key.priority, writes)
            engine.historySeal()
        }
    }

    /**
     * Brief P3c deliverable 5: an entry of a list the engine gives per photo chosen: its parameters (input colour
     * profile's type and filename) or its module action (denoise's set_profile), one history step.
     */
    suspend fun chooseActionList(key: InstanceKey, controlId: Int, index: Int) = change {
        val model = schema.modules[key.op]?.actionLists?.get(controlId) ?: throw EngineException(EngineError.STATE, "no list here")
        val entry = model.entries(_state.value.actionLists[key to controlId]).getOrNull(index) ?: throw EngineException(EngineError.STATE, "no such entry")
        val writes = model.paramWrites(entry)
        if (writes != null) {
            // only the fields that change (a built-in profile keeps the empty file name)
            val m = _state.value.modules.firstOrNull { it.key == key }
            // a text field may come as a string or as its characters (an array of codes ending in 0)
            fun text(e: kotlinx.serialization.json.JsonElement?): String? = when (e) {
                is JsonPrimitive -> e.content
                is kotlinx.serialization.json.JsonArray -> e.map { (it as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
                    .takeWhile { it != 0 }.map { it.toChar() }.joinToString("")
                else -> null
            }
            val changed = writes.filter { (k, v) -> text(m?.params?.paramAt(k)) != v.content }
            if (changed.isNotEmpty()) engine.paramsSet(key.op, key.priority, changed)
        } else engine.moduleAction(key.op, key.priority, model.action!!, model.args(entry))
        engine.historySeal()
    }

    /** A computed switch set: the parameters its callback writes, one history step. */
    suspend fun toggleComputed(key: InstanceKey, controlId: Int, on: Boolean) = change {
        val model = schema.modules[key.op]?.computed?.get(controlId) as? ComputedToggle ?: throw EngineException(EngineError.STATE, "no switch here")
        val m = _state.value.modules.firstOrNull { it.key == key } ?: throw EngineException(EngineError.STATE, "no such instance")
        model.action(on, m.params)?.let { a ->
            engine.moduleAction(key.op, key.priority, a.action, a.args)
            engine.historySeal()
            return@change
        }
        val writes = model.write(on, m.params)
        if (writes.isNotEmpty()) {
            engine.paramsSet(key.op, key.priority, writes)
            engine.historySeal()
        }
    }

    private suspend fun writeBinding(key: InstanceKey, binding: Binding, value: Double, integer: Boolean, controlId: Int? = null) {
        when (binding) {
            is Binding.Param -> {
                engine.paramSet(key.op, key.priority, binding.path, if (integer) JsonPrimitive(Math.round(value)) else JsonPrimitive(value))
                keepAspect(key, binding.path, value)
            }
            Binding.Aspect -> throw EngineException(EngineError.ARG, "the aspect list is chosen from its entries")
            Binding.Computed -> {
                // brief P2j: desktop's callback, repeated on the parameters (as single fields, so a drag merges)
                val model = controlId?.let { schema.modules[key.op]?.computed?.get(it) } as? ComputedSlider
                    ?: throw EngineException(EngineError.ARG, "no computed slider here")
                val m = _state.value.modules.firstOrNull { it.key == key } ?: throw EngineException(EngineError.STATE, "no such instance")
                remembered[key to model.controlId] = value
                val p = livePatched[key] ?: m.params
                val writes = model.write(value, p) { id -> remembered[key to id] }
                for ((path, v) in writes) engine.paramSet(key.op, key.priority, path, v)
                livePatched[key] = JsonObject(p + writes)
            }
            is Binding.Action -> {
                val cur = _state.value.actionValues[key] ?: (engine.moduleAction(key.op, key.priority, binding.get) as? JsonObject) ?: JsonObject(emptyMap())
                val args = binding.fields.associateWith { f -> cur[f] ?: JsonPrimitive(0.0) }.toMutableMap<String, kotlinx.serialization.json.JsonElement>()
                // brief P3c: arguments taken from other fields of the answer (white balance's preset name for its fine tuning)
                for ((arg, field) in binding.argsFrom) cur[field]?.let { args[arg] = it }
                args[binding.field] = if (binding.round) JsonPrimitive(Math.round(value)) else JsonPrimitive(value)
                engine.moduleAction(key.op, key.priority, binding.set, JsonObject(args))
            }
            Binding.None -> throw EngineException(EngineError.ARG, "this control has no parameter")
            is Binding.Setting -> throw EngineException(EngineError.ARG, "a picker setting is not written to the engine")
            Binding.ActionList -> throw EngineException(EngineError.ARG, "the list is chosen from its entries")
        }
    }

    // ------------------------------------------------------------------ brief P2f: crop's aspect (override aspect_list)

    private fun aspectState(key: InstanceKey): Triple<AspectSpec, ModuleInstance, Pair<Double, Double>>? {
        val spec = schema.modules[key.op]?.aspect ?: return null
        val m = _state.value.modules.firstOrNull { it.key == key } ?: return null
        val info = _state.value.info ?: return null
        val f = frameOf(spec, m.params)
        return Triple(spec, m, CropAspect.inputSize(info.width, info.height, f, m.enabled))
    }

    private fun num(params: JsonObject, name: String): Double? = (params.paramAt(name) as? JsonPrimitive)?.doubleOrNull

    private fun frameOf(spec: AspectSpec, p: JsonObject) =
        CropAspect.Frame(num(p, spec.left) ?: 0.0, num(p, spec.top) ?: 0.0, num(p, spec.right) ?: 1.0, num(p, spec.bottom) ?: 1.0)

    private fun ratioOf(spec: AspectSpec, p: JsonObject) = (num(p, spec.ratioD)?.toInt() ?: -1) to (num(p, spec.ratioN)?.toInt() ?: -1)

    private suspend fun writeAspect(key: InstanceKey, spec: AspectSpec, c: CropAspect.Change) {
        engine.paramsSet(key.op, key.priority, mapOf(
            spec.ratioD to JsonPrimitive(c.ratioD), spec.ratioN to JsonPrimitive(c.ratioN),
            spec.left to JsonPrimitive(c.frame.left), spec.top to JsonPrimitive(c.frame.top),
            spec.right to JsonPrimitive(c.frame.right), spec.bottom to JsonPrimitive(c.frame.bottom),
        ))
    }

    /** An entry of the aspect list chosen: the ratio and the frame as desktop sets them, one history step. */
    suspend fun chooseAspect(key: InstanceKey, entry: Int) {
        // brief P3a: with the crop tool open, the frame on the photo takes the ratio (written when the tool is left)
        if (cropToolEdit(key) { t, spec ->
                val e = spec.entries[entry]
                val d = kotlin.math.abs(e.d)
                val a = CropTool.aspect(spec, d, e.n, t.inW.toDouble(), t.inH.toDouble())
                t.copy(ratioD = d, ratioN = e.n, clip = CropTool.aspectApply(t.clip, a, CropTool.GRAB_HORIZONTAL, t.inW.toDouble(), t.inH.toDouble()))
            }) return
        chooseAspectNow(key, entry)
    }

    private suspend fun chooseAspectNow(key: InstanceKey, entry: Int) = change {
        val (spec, m, size) = aspectState(key) ?: throw EngineException(EngineError.STATE, "no aspect list here")
        val (d, _) = ratioOf(spec, m.params)
        writeAspect(key, spec, CropAspect.choose(spec, entry, d, frameOf(spec, m.params), size.first, size.second))
        engine.historySeal()
    }

    /** The aspect's flip button: portrait and landscape swapped, one history step. */
    suspend fun flipAspect(key: InstanceKey) {
        // brief P3a: in the crop tool, as desktop's _event_key_swap on the frame being edited
        if (cropToolEdit(key) { t, spec ->
                val (d0, n0) = CropAspect.effective(t.ratioD, t.ratioN)
                val d = -d0
                val horizontal = (t.inW >= t.inH) == (d < 0)
                val a = CropTool.aspect(spec, d, n0, t.inW.toDouble(), t.inH.toDouble())
                t.copy(ratioD = d, ratioN = n0, clip = CropTool.aspectApply(t.clip, a,
                    if (horizontal) CropTool.GRAB_HORIZONTAL else CropTool.GRAB_VERTICAL, t.inW.toDouble(), t.inH.toDouble()))
            }) return
        flipAspectNow(key)
    }

    private suspend fun flipAspectNow(key: InstanceKey) = change {
        val (spec, m, size) = aspectState(key) ?: throw EngineException(EngineError.STATE, "no aspect list here")
        val (d, n) = ratioOf(spec, m.params)
        writeAspect(key, spec, CropAspect.flip(spec, d, n, frameOf(spec, m.params), size.first, size.second))
        engine.historySeal()
    }

    /** A margin moved while a ratio is chosen: the other margins follow (desktop's gui_changed). */
    private suspend fun keepAspect(key: InstanceKey, path: String, value: Double) {
        val (spec, m, size) = aspectState(key) ?: return
        if (path !in listOf(spec.left, spec.top, spec.right, spec.bottom)) return
        val (d, n) = ratioOf(spec, m.params)
        if (CropAspect.aspect(spec, d, n, size.first, size.second) <= 0.0) return
        val p = JsonObject(m.params + (path to JsonPrimitive(value)))
        val before = frameOf(spec, p)
        val f = CropAspect.keep(spec, path, d, n, before, size.first, size.second)
        // as single fields, so a drag's steps still merge into one history item
        for ((name, v) in listOf(spec.left to f.left, spec.top to f.top, spec.right to f.right, spec.bottom to f.bottom)) {
            if (name != path || v != value) engine.paramSet(key.op, key.priority, name, JsonPrimitive(v))
        }
    }

    /** A finger moves a slider: the value shows at once; the engine follows with the latest value. */
    fun drag(key: InstanceKey, controlId: Int, binding: Binding, value: Double, integer: Boolean) {
        if (cropMargin(key, binding, value)) return // brief P3a: the frame in the crop tool follows the margin
        if (binding is Binding.Setting) { // brief P3c: a picker setting is the app's, the engine is not told
            _live.update { it + ((key to controlId) to value) }
            return
        }
        _live.update { it + ((key to controlId) to value) }
        drags.trySend(Drag(key, controlId, binding, value, integer, end = false))
    }

    /** The finger lifts: the last value is written and the history item sealed. */
    fun endDrag(key: InstanceKey, controlId: Int, binding: Binding, integer: Boolean) {
        if (cropMarginOf(key, binding) != null) return // brief P3a: nothing to write before the tool is left
        val v = _live.value[key to controlId] ?: return
        if (binding is Binding.Setting) {
            launch {
                setSetting(key, controlId, JsonPrimitive(v))
                _live.update { it - (key to controlId) }
            }
            return
        }
        drags.trySend(Drag(key, controlId, binding, v, integer, end = true))
    }

    private suspend fun writeDrag(d: Drag) {
        val w = d.writes
        if (w == null) writeBinding(d.key, d.binding, d.value, d.integer, d.controlId)
        else for ((path, v) in w) engine.paramSet(d.key.op, d.key.priority, path, v)
    }

    /**
     * Brief P3e-b: a finger moves on a band graph (contrast equalizer, colour zones, colour equalizer). [writes] holds
     * every field the drag has changed since the finger came down, so the newest message says it all (the channel
     * keeps only the newest). The values show at once; the engine follows; one history item for the whole drag.
     */
    fun bandDrag(key: InstanceKey, writes: List<Pair<String, JsonPrimitive>>) {
        if (writes.isEmpty()) return
        drags.trySend(Drag(key, BAND_DRAG, Binding.None, 0.0, false, end = false, writes = writes))
    }

    /** The finger lifts: the last values are written and the history item sealed. */
    fun bandEnd(key: InstanceKey, writes: List<Pair<String, JsonPrimitive>>) {
        if (writes.isEmpty()) return
        drags.trySend(Drag(key, BAND_DRAG, Binding.None, 0.0, false, end = true, writes = writes))
    }

    /** A band graph's change that is not a drag (a double tap's reset, a node taken away, "select by"): one history item. */
    suspend fun bandApply(key: InstanceKey, writes: List<Pair<String, JsonPrimitive>>) {
        if (writes.isEmpty()) return
        change {
            for ((path, v) in writes) engine.paramSet(key.op, key.priority, path, v)
            engine.historySeal()
        }
    }

    /** The drag picture size of the drag in progress, fixed from its first step to its end. */
    private var dragSize: Int? = null

    /**
     * One step of a drag (PLAN.md 3.9, brief P2b). While the finger moves, each step writes the
     * newest value and renders the small drag picture before the next step is taken (the channel
     * keeps only the newest value, so no render is started only to be cancelled). Nothing else is
     * read from the engine during the drag: `modules()` is about 185 KB of JSON, so it is re-read
     * once, when the finger lifts. Then the full preview and the sharp region follow, cancellable.
     */
    private suspend fun applyDrag(d: Drag) {
        cancelRenders()
        if (!d.end) {
            val size = dragSize ?: dragPicture.size().also { dragSize = it }
            val layer = fg {
                try {
                    // brief P3a: another module's slider (quick access panel) leaves the crop tool first
                    if (_state.value.cropTool != null) { dragBefore = _state.value.modules; leaveCropLocked(); refreshLocked() }
                    writeDrag(d)
                    markOn(d.key)
                    _state.update { it.copy(message = null) }
                    val info = _state.value.info ?: return@fg null
                    val t0 = System.nanoTime()
                    val img = engine.renderPreview(size)
                    dragPicture.record(img.width * img.height, System.nanoTime() - t0, drag = true)
                    PhotoLayer(img, 0, 0, img.width.toDouble() / info.width.coerceAtLeast(1))
                } catch (e: EngineException) {
                    if (e.error != EngineError.CANCELLED) _state.update { it.copy(message = e.message) }
                    null
                }
            }
            // the drag picture covers the whole photo, as the preview does: same framing; the region
            // (sharp, but showing the value before the drag) is hidden until the finger lifts
            if (layer != null) _state.update { it.copy(preview = layer, region = null) }
            return
        }
        fg {
            dragSize = null
            try {
                writeDrag(d)
                engine.historySeal()
                _state.update { it.copy(message = null) }
            } catch (e: EngineException) {
                _state.update { it.copy(message = e.message) }
            }
            try { refreshLocked() } catch (e: EngineException) { _state.update { it.copy(message = e.message) } }
            // brief P3a: crop's panel still open: the tool again on the new state
            dragBefore?.let { b -> dragBefore = null; reopenCropLocked(b) }
        }
        _live.update { it - (d.key to d.controlId) }
        requestRender()
    }

    /** Brief P3a: the modules before a drag that left the crop tool, to open it again when the finger lifts. */
    private var dragBefore: List<ModuleInstance>? = null

    // ------------------------------------------------------------------ brief P3a: the crop tool on the photo
    //
    // See docs/experiments/P3a-crop-tool.md ("deliverable 3"). While crop's panel is open on an editable photo the
    // photo shows crop's input (the uncropped picture) with the frame over it. The frame is kept here
    // ([CropToolState]), never in the engine, until the tool is left; then it is written with one params_set (one
    // history item, none when nothing changed). Brief P1b part 2: the uncropped picture is a render that leaves
    // crop out for that call only (engine-api.md 6.1, `bypass`): the edit, the history, the undo and redo steps
    // and the XMP are never touched by it (P3a's switch-off, render and undo bracket and its redo guard are gone).

    /** Where the photo's view is in its window (set by the screen), for tests that touch the photo. */
    @Volatile var photoArea: PhotoArea? = null

    /** The drag on the frame in progress (crop.c's button_pressed and mouse_moved), null when no finger is on it. */
    private var cropDrag: CropTool.Drag? = null

    /**
     * The crop instance whose tool should be open: crop's panel is expanded on an open, editable photo, and no
     * module after crop changes the picture's geometry (framing: desktop then maps the frame through that module,
     * which the app cannot do without the extended part of the engine API). Null otherwise.
     */
    fun cropWanted(s: DarkroomState): InstanceKey? {
        val k = s.expanded ?: return null
        if (k.op != CROP_OP || s.info == null || s.readOnly || s.loading) return null
        if (schema.modules[k.op]?.aspect == null) return null
        if (cropBlockedBy(s) != null) return null
        return k
    }

    /** Brief P3a: the switched-on module after crop that changes the geometry, if any (the tool does not open then). */
    fun cropBlockedBy(s: DarkroomState): ModuleInstance? {
        val crop = s.modules.firstOrNull { it.op == CROP_OP } ?: return null
        return s.modules.firstOrNull { it.enabled && it.op in GEOMETRY_AFTER_CROP && it.iopOrder > crop.iopOrder }
    }

    /** Open or leave the tool after crop's panel was opened or closed (or another module's opened). */
    suspend fun syncCropTool() {
        val s0 = _state.value
        if (cropWanted(s0) == s0.cropTool?.key) {
            if (s0.cropTool == null && s0.expanded?.op == CROP_OP) cropBlockedBy(s0)?.let { b ->
                _state.update { it.copy(message = "the crop tool on the photo is not available while ${b.name} is on: it changes the picture's size after crop. The margins below still work.") }
            }
            return
        }
        cancelRenders()
        fg {
            val s = _state.value
            if (s.cropTool != null && cropWanted(s) != s.cropTool.key) {
                val before = s.modules
                leaveCropLocked()
                try { refreshLocked() } catch (e: EngineException) { _state.update { it.copy(message = e.message) } }
                reopenCropLocked(before)
            } else if (s.cropTool == null) {
                cropWanted(s)?.let { openCropLocked(it) }
            }
        }
        if (_state.value.cropTool == null) requestRender()
    }

    /** The tool's frame written (when the user changed it) and the tool closed; errors become the message. */
    private suspend fun leaveCropLocked() {
        try { commitCropLocked(close = true) } catch (e: EngineException) {
            _state.update { it.copy(message = "crop not applied: ${e.message}", cropTool = null) }
        }
        cropDrag = null
    }

    /**
     * Brief P3a deliverable 2: the frame written as desktop's `_commit_box` writes it when crop loses the focus:
     * rounded to the ratio, one params_set and a seal (one history item), only when the frame changed by more
     * than 1e-6 or the ratio changed. [close] closes the tool; otherwise it stays open on the written frame.
     */
    private suspend fun commitCropLocked(close: Boolean) {
        val t = _state.value.cropTool ?: return
        if (close) cropDrag = null
        if (t.touched) {
            val m = _state.value.modules.firstOrNull { it.key == t.key }
            val spec = schema.modules[t.key.op]?.aspect
            if (m != null && spec != null) {
                val f = t.committed()
                // a switched-off crop starts from the whole picture (desktop: "first time crop, ... obsolete")
                val old = if (m.enabled) frameOf(spec, m.params) else CropAspect.Frame(0.0, 0.0, 1.0, 1.0)
                val (od, on) = ratioOf(spec, m.params)
                val writes = LinkedHashMap<String, JsonPrimitive>()
                if (CropTool.changed(f, old)) {
                    writes[spec.left] = JsonPrimitive(f.left); writes[spec.top] = JsonPrimitive(f.top)
                    writes[spec.right] = JsonPrimitive(f.right); writes[spec.bottom] = JsonPrimitive(f.bottom)
                }
                if (t.ratioD != od || t.ratioN != on) { writes[spec.ratioD] = JsonPrimitive(t.ratioD); writes[spec.ratioN] = JsonPrimitive(t.ratioN) }
                if (writes.isNotEmpty()) {
                    engine.paramsSet(t.key.op, t.key.priority, writes)
                    engine.historySeal()
                    Timeline.mark("darkroom: crop tool: frame written (${writes.keys.joinToString(",")})")
                }
                _state.update { s ->
                    s.copy(cropTool = if (close) null else s.cropTool?.copy(
                        clip = CropTool.Clip.fromParams(f.left.toFloat(), f.top.toFloat(), f.right.toFloat(), f.bottom.toFloat()), touched = false))
                }
                return
            }
        }
        if (close) _state.update { it.copy(cropTool = null) }
    }

    private fun geometryInputs(mods: List<ModuleInstance>) =
        mods.filter { it.op != CROP_OP }.map { listOf(it.op, it.priority, it.iopOrder, it.enabled, it.params) }

    /**
     * After a change while crop's panel is open: the tool on the new state. When nothing but crop changed (its
     * frame written, crop's reset, an undo of a crop step) the uncropped picture is still right and is kept;
     * otherwise it is rendered again. When crop's panel is no longer open the tool is closed.
     */
    private suspend fun reopenCropLocked(before: List<ModuleInstance>) {
        val s = _state.value
        val want = cropWanted(s)
        val t = s.cropTool
        if (want == null) { if (t != null) _state.update { it.copy(cropTool = null) }; return }
        if (t != null && t.key == want && t.picture != null && geometryInputs(before) == geometryInputs(s.modules)) {
            val m = s.modules.firstOrNull { it.key == want } ?: return
            val spec = schema.modules[want.op]?.aspect ?: return
            _state.update { it.copy(cropTool = toolFromParams(m, spec, t.inW, t.inH, t.picture).copy(options = t.options)) }
            return
        }
        _state.update { it.copy(cropTool = null) }
        openCropLocked(want)
    }

    private fun toolFromParams(m: ModuleInstance, spec: app.dtandroid.schema.AspectSpec, inW: Int, inH: Int, picture: PhotoLayer?): CropToolState {
        val (rd, rn) = ratioOf(spec, m.params)
        val f = frameOf(spec, m.params)
        // gui_focus: the frame from the parameters, or the whole picture when crop is off; gui_post_expose then
        // keeps the ratio (_aspect_apply with all sides)
        val start = if (m.enabled) CropTool.Clip.fromParams(f.left.toFloat(), f.top.toFloat(), f.right.toFloat(), f.bottom.toFloat()) else CropTool.Clip.FULL
        val (d, n) = CropAspect.effective(rd, rn)
        val a = CropTool.aspect(spec, d, n, inW.toDouble(), inH.toDouble())
        return CropToolState(m.key, picture, inW, inH, CropTool.aspectApply(start, a, CropTool.GRAB_ALL, inW.toDouble(), inH.toDouble()), rd, rn)
    }

    /**
     * Brief P3a deliverable 3, brief P1b part 2 deliverable 2: the uncropped picture. With crop on, a render that
     * leaves crop out for this call only (`bypass`); nothing in the engine changes. With crop off the picture is the
     * current one. [CropToolState.options] keeps the options, so a zoomed-in view renders its sharp region of the
     * same picture.
     */
    private suspend fun openCropLocked(key: InstanceKey) {
        val s = _state.value
        val info = s.info ?: return
        if (s.readOnly) return
        val m = s.modules.firstOrNull { it.key == key } ?: return
        val spec = schema.modules[key.op]?.aspect ?: return
        val options = if (m.enabled) RenderOptions.bypass(key.op, key.priority) else RenderOptions.NONE
        Timeline.mark("darkroom: crop tool: uncropped picture start (crop ${if (m.enabled) "on, left out of the render" else "off"})")
        val (sz, img) = try {
            val sz = sizeWith(1.0, options)
            val vp = viewport
            val size = if (vp == null) previewMaxSize else previewSizeFor(vp.widthPx, vp.heightPx, sz.w, sz.h, previewMaxSize, fitPreviewMax)
            sz to wanted { if (options.isNone) engine.renderPreview(size) else engine.renderPreview(size, options) }
        } catch (e: EngineException) {
            if (e.error != EngineError.CANCELLED) _state.update { it.copy(message = e.message) }
            Timeline.mark("darkroom: crop tool: not opened (${e.message})")
            return
        }
        val layer = PhotoLayer(img, 0, 0, img.width.toDouble() / sz.w.coerceAtLeast(1))
        val m2 = _state.value.modules.firstOrNull { it.key == key } ?: return
        _state.update {
            it.copy(cropTool = toolFromParams(m2, spec, sz.w, sz.h, layer).copy(options = options), preview = it.preview ?: layer,
                region = null, working = null, workingProgress = null)
        }
        Timeline.mark("darkroom: crop tool: open on ${sz.w}x${sz.h}, picture ${img.width}x${img.height} (photo ${info.width}x${info.height})")
    }

    /** Brief P1b part 2: the plain call without options (as before), the one with options otherwise. */
    private suspend fun sizeWith(scale: Double, options: RenderOptions) =
        if (options.isNone) engine.renderSize(scale) else engine.renderSize(scale, options)

    /** With the crop tool open on [key]: change its state with [edit] (marked as touched). False when not open. */
    private fun cropToolEdit(key: InstanceKey, edit: (CropToolState, app.dtandroid.schema.AspectSpec) -> CropToolState): Boolean {
        val t = _state.value.cropTool ?: return false
        if (t.key != key) return false
        val spec = schema.modules[key.op]?.aspect ?: return false
        _state.update { s -> s.cropTool?.let { cur -> s.copy(cropTool = edit(cur, spec).copy(touched = true)) } ?: s }
        return true
    }

    /** The margin parameter [binding] moves when the crop tool is open on [key], else null. */
    private fun cropMarginOf(key: InstanceKey, binding: Binding): String? {
        val t = _state.value.cropTool ?: return null
        if (t.key != key || binding !is Binding.Param) return null
        val spec = schema.modules[key.op]?.aspect ?: return null
        return binding.path.takeIf { it in listOf(spec.left, spec.top, spec.right, spec.bottom) }
    }

    /**
     * A margin slider moved while the tool is open: the frame follows as desktop's gui_changed moves it (that side,
     * then _aspect_apply from that side). True when handled here.
     */
    private fun cropMargin(key: InstanceKey, binding: Binding, value: Double): Boolean {
        val path = cropMarginOf(key, binding) ?: return false
        return cropToolEdit(key) { t, spec ->
            val g = t.clip
            val v = value.toFloat()
            val min = CropTool.MIN_CROP_SIZE
            val (c, grab) = when (path) {
                spec.left -> { val x = minOf(v, g.right - min); CropTool.Clip(x, g.y, g.x + g.w - x, g.h) to CropTool.GRAB_LEFT }
                spec.right -> { val r = maxOf(v, g.x + min); CropTool.Clip(g.x, g.y, r - g.x, g.h) to CropTool.GRAB_RIGHT }
                spec.top -> { val y = minOf(v, g.bottom - min); CropTool.Clip(g.x, y, g.w, g.y + g.h - y) to CropTool.GRAB_TOP }
                else -> { val b = maxOf(v, g.y + min); CropTool.Clip(g.x, g.y, g.w, b - g.y) to CropTool.GRAB_BOTTOM }
            }
            val (d, n) = CropAspect.effective(t.ratioD, t.ratioN)
            val a = CropTool.aspect(spec, d, n, t.inW.toDouble(), t.inH.toDouble())
            t.copy(clip = CropTool.aspectApply(c, a, grab, t.inW.toDouble(), t.inH.toDouble()))
        }
    }

    /** What a margin slider of crop shows while the tool is open (the frame on the photo), else null. */
    fun cropMarginValue(s: DarkroomState, key: InstanceKey, binding: Binding): Double? {
        val t = s.cropTool ?: return null
        if (t.key != key || binding !is Binding.Param) return null
        val spec = schema.modules[key.op]?.aspect ?: return null
        val g = t.clip
        return when (binding.path) {
            spec.left -> g.x.toDouble()
            spec.top -> g.y.toDouble()
            spec.right -> g.right.toDouble()
            spec.bottom -> g.bottom.toDouble()
            else -> null
        }
    }

    /** The ratio crop's aspect list shows: the tool's while it is open on [key], else the parameters'. */
    fun cropRatioShown(s: DarkroomState, key: InstanceKey, ratioD: Int, ratioN: Int): Pair<Int, Int> =
        s.cropTool?.takeIf { it.key == key }?.let { it.ratioD to it.ratioN } ?: (ratioD to ratioN)

    /** A finger went down on the photo at [px], [py] (fractions of the uncropped picture): which part it takes. */
    fun cropHit(px: Float, py: Float, touchX: Float, touchY: Float): Int =
        _state.value.cropTool?.let { CropTool.hitTest(it.clip, px, py, touchX, touchY) } ?: CropTool.GRAB_NONE

    /** A drag on the frame starts (crop.c button_pressed) with [grab] at [bx], [by]. */
    fun cropDragStart(grab: Int, bx: Float, by: Float) {
        val t = _state.value.cropTool ?: return
        if (grab == CropTool.GRAB_NONE) return
        val spec = schema.modules[t.key.op]?.aspect ?: return
        val (d, n) = CropAspect.effective(t.ratioD, t.ratioN)
        val a = CropTool.aspect(spec, d, n, t.inW.toDouble(), t.inH.toDouble())
        cropDrag = CropTool.Drag(t.clip, grab, bx, by, a, t.inW.toDouble(), t.inH.toDouble())
        cropDragFrom = t
        _state.update { s -> s.copy(cropTool = s.cropTool?.copy(dragging = true)) }
    }

    /** The tool as it was when the drag in progress started (to put it back when the drag becomes a pinch). */
    private var cropDragFrom: CropToolState? = null

    /** A second finger came down before the frame moved much: the touch is a pinch, the frame goes back. */
    fun cropDragCancel() {
        val from = cropDragFrom ?: return
        cropDrag = null
        cropDragFrom = null
        _state.update { s -> s.copy(cropTool = s.cropTool?.copy(clip = from.clip, touched = from.touched, dragging = false)) }
    }

    /** The finger moved to [px], [py] (crop.c mouse_moved with the button down). */
    fun cropDragMove(px: Float, py: Float) {
        val d = cropDrag ?: return
        val c = d.move(px, py)
        _state.update { s -> s.copy(cropTool = s.cropTool?.copy(clip = c, touched = true)) }
    }

    /** The finger lifted: the frame stays in the tool (written when the tool is left, as on desktop). */
    fun cropDragEnd() {
        cropDrag = null
        cropDragFrom = null
        _state.update { s -> s.copy(cropTool = s.cropTool?.copy(dragging = false)) }
    }

    // ------------------------------------------------------------------ brief P3a deliverable 4: rotation in the tool

    /** Rotate and perspective's rotation slider from the control schema (desktop: factor -1, soft range ±10°). */
    fun rotationSlider(): Control.Slider? = schema.modules[ASHIFT_OP]?.allControls()?.filterIsInstance<Control.Slider>()
        ?.firstOrNull { (it.binding as? Binding.Param)?.path == ROTATION_PARAM }

    /** The instance the tool's rotation changes: rotate and perspective's first instance. */
    fun rotationKey(s: DarkroomState): InstanceKey? =
        (s.modules.firstOrNull { it.op == ASHIFT_OP && it.priority == 0 } ?: s.modules.firstOrNull { it.op == ASHIFT_OP })?.key

    /** Its rotation parameter now (degrees). */
    fun rotationValue(s: DarkroomState): Double {
        val k = rotationKey(s) ?: return 0.0
        val m = s.modules.firstOrNull { it.key == k } ?: return 0.0
        return num(m.params, ROTATION_PARAM) ?: 0.0
    }

    /** The rotation slider moves: the value shows, the picture turns on the screen; the engine waits for the lift. */
    fun cropRotationDrag(value: Double) {
        _state.update { s -> s.copy(cropTool = s.cropTool?.copy(rotationLive = value)) }
    }

    /** The finger lifts from the rotation slider: the rotation is written (one history item). */
    fun cropRotationDragEnd() {
        val v = _state.value.cropTool?.rotationLive ?: return
        launch { cropRotate(v) }
    }

    /**
     * Rotate and perspective's rotation set to [value] while the crop tool is open: as any other change, the frame
     * is written first, then the rotation (switching the module on, as on desktop), then the tool opens again on
     * the new uncropped picture.
     */
    suspend fun cropRotate(value: Double) {
        val key = rotationKey(_state.value)
        val sl = rotationSlider()
        try {
            if (key == null || sl == null) { _state.update { it.copy(message = "rotate and perspective is not in this photo's pipe") }; return }
            setSliderNow(key, sl.binding, value.coerceIn(sl.hardMin, sl.hardMax), false, sl.id)
        } finally {
            _state.update { s -> s.copy(cropTool = s.cropTool?.copy(rotationLive = null, straighten = false, line = null)) }
        }
    }

    /** "straighten" on or off: a one-finger drag on the photo then draws a line along a horizon or a wall. */
    fun cropStraighten(on: Boolean) = _state.update { s -> s.copy(cropTool = s.cropTool?.copy(straighten = on, line = null)) }

    /** The straightening line from [x0], [y0] to [x1], [y1] (fractions of the picture), while it is drawn. */
    fun cropLine(x0: Float, y0: Float, x1: Float, y1: Float) =
        _state.update { s -> s.copy(cropTool = s.cropTool?.copy(line = listOf(x0, y0, x1, y1))) }

    /**
     * The line is drawn (the picture shown [wd] x [ht] pixels large; lines shorter than [minLength] pixels count
     * as a tap): the rotation changes by desktop's `_calculate_straightening` (ashift.c) angle, as desktop's
     * right-button drag does. Returns that angle (0: nothing changed).
     */
    suspend fun cropLineEnd(wd: Float, ht: Float, minLength: Float): Float {
        val t = _state.value.cropTool ?: return 0f
        val l = t.line
        if (l == null) { cropStraighten(false); return 0f }
        val flip = _state.value.modules.firstOrNull { it.op == FLIP_OP && it.enabled }
        val orientation = flip?.let { m ->
            val name = (m.params[ORIENTATION_PARAM] as? JsonPrimitive)?.content
            schema.modules[FLIP_OP]?.params?.get(ORIENTATION_PARAM)?.enumValues?.get(name) ?: name?.toIntOrNull()
        } ?: 0
        val a = CropTool.straighten(l[2], l[3], l[0], l[1], wd, ht, 1f, minLength, CropTool.mirrored(orientation))
        if (a == 0f) { cropStraighten(false); return 0f }
        Timeline.mark("darkroom: crop tool: straighten by $a°")
        cropRotate(rotationValue(_state.value) + a)
        return a
    }

    /** param_set switches a module on (engine-api.md 2.4); show that during a drag without re-reading modules(). */
    private fun markOn(key: InstanceKey) = _state.update { s ->
        if (s.modules.none { it.key == key && !it.enabled }) s
        else s.copy(modules = s.modules.map { if (it.key == key && it.canSwitch) it.copy(enabled = true) else it })
    }

    suspend fun instanceNew(key: InstanceKey) = change {
        val p = engine.instanceNew(key.op, key.priority)
        _state.update { it.copy(expanded = InstanceKey(key.op, p), overlay = null) }
    }

    suspend fun instanceDuplicate(key: InstanceKey) = change {
        val p = engine.instanceDuplicate(key.op, key.priority)
        _state.update { it.copy(expanded = InstanceKey(key.op, p), overlay = null) }
    }

    suspend fun instanceDelete(key: InstanceKey) = change {
        engine.instanceDelete(key.op, key.priority)
        _state.update { it.copy(expanded = null, overlay = null) }
    }

    /** [up] = up in the list = later in the pipe. */
    suspend fun instanceMove(key: InstanceKey, up: Boolean) = change {
        engine.instanceMove(key.op, key.priority, if (up) 1 else -1)
        _state.update { it.copy(overlay = null) }
    }

    suspend fun instanceRename(key: InstanceKey, name: String) = change {
        engine.instanceRename(key.op, key.priority, name)
        _state.update { it.copy(overlay = null) }
    }

    suspend fun historyGoto(end: Int) = change { engine.historyGoto(end) }
    suspend fun historyCompress() = change { engine.historyCompress() }
    // brief P3a: asked of the engine inside the change (an open crop tool's frame may have been written first)
    suspend fun undo() = change { if (engine.undoState().canUndo) engine.undo() }
    suspend fun redo() = change { if (engine.undoState().canRedo) engine.redo() }

    // ------------------------------------------------------------------ brief P3c: colour pickers on the photo
    //
    // As desktop's color_picker_proxy.c: a picker belongs to a control (the schema marks them, the override files say
    // what each does, [PickerSpec]); one is armed at a time, for the module whose panel is open. Arming switches the
    // module on and picks at once, with the picker's last selection or desktop's first one (a point in the middle,
    // or the area 2 %..98 %), as desktop does; a tap places a point, a drag draws an area, a drag on the selection
    // moves or resizes it; each lifted finger picks again. The selection is kept in original-image coordinates
    // (coords_transform), so a change of the edit's geometry keeps it on the same part of the picture.

    /** Each picker's last selection in original-image coordinates (corners of an area, or the point), by control. */
    private val pickerMemory = HashMap<Pair<InstanceKey, Int>, Pair<List<Pair<Double, Double>>, Boolean>>()

    /** The drag on the selection in progress: what it holds, where it started, the selection then. */
    private var pickerGrab = PickerTool.GRAB_NONE
    private var pickerFrom = 0.0 to 0.0
    private var pickerStart: PickSelection? = null
    private var pickerDrawing = false

    /** The picker of control [controlId] of [key]'s module, if the app has built it. */
    fun pickerSpec(key: InstanceKey, controlId: Int): PickerSpec? =
        // brief P3d stage 2b: the pickers of the parametric sliders are no schema controls: an area that sets a range
        if (controlId <= ParametricMasks.PICK_BASE) PickerSpec(controlId, "range", point = false, area = true, denoise = false, action = null)
        else schema.modules[key.op]?.pickers?.get(controlId)

    fun pickerArmed(s: DarkroomState, key: InstanceKey, controlId: Int): Boolean =
        s.picker?.let { it.key == key && it.controlId == controlId } == true

    /** Desktop's picker button: arms the picker (the previous one goes), or disarms it when it is the armed one. */
    suspend fun togglePicker(key: InstanceKey, controlId: Int) {
        if (pickerArmed(_state.value, key, controlId)) { disarmPicker(); return }
        val spec = pickerSpec(key, controlId) ?: return
        val s0 = _state.value
        if (s0.readOnly || s0.info == null || s0.modules.none { it.key == key }) return
        cancelRenders()
        fg {
            _state.update { it.copy(message = null, picker = null) }
            try {
                commitCropLocked(close = true)
                val m = _state.value.modules.firstOrNull { it.key == key } ?: return@fg
                // desktop: "if(module && module->off) gtk_toggle_button_set_active(module->off, TRUE)"
                if (!m.enabled) { engine.setEnabled(key.op, key.priority, true); engine.historySeal(); refreshLocked() }
                val sel = pickerRecallLocked(key, controlId, spec)
                _state.update { it.copy(picker = PickerState(key, controlId, sel, outside = sel == null)) }
                Timeline.mark("darkroom: picker armed on ${key.op} ${key.priority} control $controlId")
                applyPickerLocked()
            } catch (e: EngineException) {
                _state.update { it.copy(message = e.message) }
            }
        }
        requestRender()
    }

    /** The picker disarmed (its button tapped again, its module's panel left, another photo). */
    fun disarmPicker() {
        if (_state.value.picker == null) return
        pickerGrab = PickerTool.GRAB_NONE
        pickerStart = null
        pickerDrawing = false
        _state.update { it.copy(picker = null) }
        Timeline.mark("darkroom: picker disarmed")
    }

    /** Leaving the picker's module (another module opened, its panel closed, the module gone) disarms it. */
    private fun pickerCheck() {
        val p = _state.value.picker ?: return
        val s = _state.value
        val inMasks = p.controlId <= ParametricMasks.PICK_BASE && s.view == BottomView.MASKS // brief P3d stage 2b
        if ((s.expanded != p.key && !inMasks) || s.modules.none { it.key == p.key } || s.readOnly) disarmPicker()
    }

    /** The selection the picker starts with: its last one mapped onto the picture now, else desktop's first one. */
    private suspend fun pickerRecallLocked(key: InstanceKey, controlId: Int, spec: PickerSpec): PickSelection? {
        val mem = pickerMemory[key to controlId]
        if (mem != null) {
            val (pts, area) = mem
            val back = engine.coordsTransform(CoordsDirection.TO_PROCESSED, pts).points
            return PickSelection.fromCorners(back, area)
        }
        return if (spec.startsWithArea) PickSelection.DEFAULT_AREA else PickSelection.DEFAULT_POINT
    }

    /** A setting's value: the app's, else the default of the override. */
    fun settingValue(s: DarkroomState, op: String, key: String): JsonPrimitive? =
        s.pickerSettings[key] ?: schema.modules[op]?.pickerSettings?.values?.firstOrNull { it.key == key }?.default

    /** The page chosen in tabs control [tabsId] of [key]'s module (0 when none was chosen). */
    fun tabPage(s: DarkroomState, key: InstanceKey, tabsId: Int): Int = s.tabs[key to tabsId] ?: 0

    fun chooseTab(key: InstanceKey, tabsId: Int, page: Int) = _state.update { it.copy(tabs = it.tabs + ((key to tabsId) to page)) }

    /**
     * The picker's tab argument: the chosen page while the tabs are shown, else 0 (desktop goes back to the first
     * channel when the tabs are hidden, rgblevels.c / rgbcurve.c gui_changed).
     */
    private fun pickerTab(s: DarkroomState, key: InstanceKey, tabsId: Int): Int {
        val ms = schema.modules[key.op] ?: return 0
        val m = s.modules.firstOrNull { it.key == key } ?: return 0
        val inputs = ParamRuleInputs(ms, m.params)
        return if (ms.pathTo(tabsId).all { Rules.isVisible(ms, it, inputs) }) tabPage(s, key, tabsId) else 0
    }

    /**
     * Pick with the armed picker's selection and do what its control does: the module action (one history item),
     * the slider set from the values (deliverable 3), or nothing but showing them; then the readout.
     */
    private suspend fun applyPickerLocked() {
        val p = _state.value.picker ?: return
        val sel = p.selection ?: return
        val spec = pickerSpec(p.key, p.controlId) ?: return
        val key = p.key
        // the selection in original-image coordinates, for later geometry changes and the next arm
        val orig = engine.coordsTransform(CoordsDirection.TO_ORIGINAL, sel.corners()).points
        if (orig.none { it == null }) pickerMemory[key to p.controlId] = orig.filterNotNull() to sel.isArea
        var changed = false
        try {
            val action = spec.action
            val slider = spec.setSlider
            when {
                p.controlId <= ParametricMasks.PICK_BASE -> changed = parametric.pickLocked(engine, key, p.controlId, sel.area, sel.point)
                action != null -> {
                    val s = _state.value
                    val (name, args) = action.call(sel, { k -> settingValue(s, key.op, k) }, { t -> pickerTab(s, key, t) })
                    Timeline.mark("darkroom: picker ${key.op} $name ${args.toString().take(200)}")
                    val r = engine.moduleAction(key.op, key.priority, name, args) as? JsonObject
                    engine.historySeal()
                    changed = true
                    // desktop shows some results in the settings (exposure's measured lightness)
                    for ((field, settingKey) in action.results) {
                        (r?.get(field) as? JsonPrimitive)?.let { v -> _state.update { it.copy(pickerSettings = it.pickerSettings + (settingKey to v)) } }
                    }
                }
                slider != null -> {
                    val pick = engine.colorPick(key.op, key.priority, PickRequest(sel.area, sel.point, denoise = spec.denoise))
                    val m = _state.value.modules.firstOrNull { it.key == key } ?: return
                    val fields = schema.modules[key.op]?.params ?: emptyMap()
                    val writes = PickerFormulas.apply(slider, if (slider.buffer == "output") pick.output else pick.input, m.params) { name ->
                        fields[name]?.let { f -> val lo = f.min; val hi = f.max; if (lo != null && hi != null) lo to hi else null }
                    }
                    if (writes.isNotEmpty()) {
                        engine.paramsSet(key.op, key.priority, writes)
                        engine.historySeal()
                        changed = true
                    }
                }
            }
        } catch (e: EngineException) {
            _state.update { it.copy(message = e.message) }
        }
        if (changed) try { refreshLocked() } catch (e: EngineException) { _state.update { it.copy(message = e.message) } }
        pickerReadoutLocked()
    }

    /** Deliverable 4: the picked values as desktop's global colour picker shows them, for the line under the photo. */
    private suspend fun pickerReadoutLocked() {
        val p = _state.value.picker ?: return
        val sel = p.selection
        val lines = if (sel == null) emptyList() else try {
            PickerReadout.lines(engine.colorPick(null, 0, PickRequest(sel.area, sel.point)).output, sel.isArea)
        } catch (e: EngineException) {
            if (e.error == EngineError.CANCELLED) return
            emptyList()
        }
        // brief P3e-b: colour zones' graph marks the picked value on its axis: the module's own input in its picker space
        val moduleInput = if (sel != null && p.key.op == "colorzones") try {
            engine.colorPick(p.key.op, p.key.priority, PickRequest(sel.area, sel.point)).input
        } catch (e: EngineException) {
            if (e.error == EngineError.CANCELLED) return
            null
        } else null
        _state.update { s -> s.picker?.takeIf { it.key == p.key && it.controlId == p.controlId }?.let { s.copy(picker = it.copy(readout = lines, moduleInput = moduleInput)) } ?: s }
    }

    /**
     * After a change of the edit while a picker is armed: the selection mapped again from the original image when
     * another module changed (it may move pixels), and the readout taken again.
     */
    private suspend fun pickerAfterChangeLocked(before: List<ModuleInstance>) {
        pickerCheck()
        val p = _state.value.picker ?: return
        fun others(ms: List<ModuleInstance>) = ms.filter { it.key != p.key }.map { listOf(it.op, it.priority, it.iopOrder, it.enabled, it.params) }
        if (others(before) != others(_state.value.modules)) {
            pickerMemory[p.key to p.controlId]?.let { (pts, area) ->
                val back = try { engine.coordsTransform(CoordsDirection.TO_PROCESSED, pts).points } catch (e: EngineException) { null }
                if (back != null) {
                    val sel = PickSelection.fromCorners(back, area)
                    _state.update { s -> s.picker?.let { s.copy(picker = it.copy(selection = sel ?: it.selection, outside = sel == null)) } ?: s }
                }
            }
        }
        pickerReadoutLocked()
    }

    /** What a touch at ([px], [py]) (fractions of the picture) takes hold of; [tx], [ty]: a touch target in fractions. */
    fun pickerHit(px: Float, py: Float, tx: Float, ty: Float): Int {
        val p = _state.value.picker ?: return PickerTool.GRAB_NONE
        return PickerTool.hit(p.selection, px.toDouble(), py.toDouble(), tx.toDouble(), ty.toDouble())
    }

    /** A finger takes the selection ([grab] from [pickerHit]) or, away from it, starts a new one at ([px], [py]). */
    fun pickerDragStart(grab: Int, px: Float, py: Float) {
        val p = _state.value.picker ?: return
        val spec = pickerSpec(p.key, p.controlId) ?: return
        pickerFrom = px.toDouble() to py.toDouble()
        pickerGrab = grab
        pickerDrawing = grab == PickerTool.GRAB_NONE
        pickerStart = p.selection
        if (pickerDrawing && !spec.area) {
            // a picker that takes points only: the point follows the finger
            PickerTool.newPoint(px.toDouble().coerceIn(0.0, 1.0), py.toDouble().coerceIn(0.0, 1.0))?.let { np ->
                pickerStart = np
                pickerGrab = PickerTool.GRAB_POINT
                pickerDrawing = false
                _state.update { s -> s.picker?.let { s.copy(picker = it.copy(selection = np, dragging = true, outside = false)) } ?: s }
            }
            return
        }
        _state.update { s -> s.picker?.let { s.copy(picker = it.copy(dragging = true)) } ?: s }
    }

    fun pickerDragMove(px: Float, py: Float) {
        val (sx, sy) = pickerFrom
        val sel = if (pickerDrawing) PickerTool.newArea(sx, sy, px.toDouble(), py.toDouble())
            else pickerStart?.let { PickerTool.drag(it, pickerGrab, sx, sy, px.toDouble(), py.toDouble()) }
        if (sel != null) _state.update { s -> s.picker?.let { s.copy(picker = it.copy(selection = sel, outside = false)) } ?: s }
    }

    /** The finger lifts from the selection: pick with it. */
    fun pickerDragEnd() {
        val moved = _state.value.picker?.selection != pickerStart
        pickerGrab = PickerTool.GRAB_NONE
        pickerDrawing = false
        pickerStart = null
        _state.update { s -> s.picker?.let { s.copy(picker = it.copy(dragging = false)) } ?: s }
        if (moved) launch { pickerApply() }
    }

    /** A second finger before the selection moved: a pinch, the selection goes back. */
    fun pickerDragCancel() {
        val start = pickerStart
        pickerGrab = PickerTool.GRAB_NONE
        pickerDrawing = false
        pickerStart = null
        _state.update { s -> s.picker?.let { s.copy(picker = it.copy(dragging = false, selection = start ?: it.selection)) } ?: s }
    }

    /**
     * A tap at ([px], [py]) places a point; a picker that takes areas only gets a small area there, one touch target
     * ([tx] x [ty], fractions of the picture) wide.
     */
    fun pickerTap(px: Float, py: Float, tx: Float, ty: Float) {
        val p = _state.value.picker ?: return
        val spec = pickerSpec(p.key, p.controlId) ?: return
        val x = px.toDouble()
        val y = py.toDouble()
        if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) return
        val sel = if (spec.point) PickSelection.point(x, y)
            else PickerTool.newArea(x - tx / 2.0, y - ty / 2.0, x + tx / 2.0, y + ty / 2.0) ?: return
        _state.update { s -> s.picker?.let { s.copy(picker = it.copy(selection = sel, outside = false)) } ?: s }
        launch { pickerApply() }
    }

    /** Pick again with the armed picker's selection (as a change: the render follows). */
    suspend fun pickerApply() {
        if (_state.value.picker == null) return
        cancelRenders()
        fg {
            _state.update { it.copy(message = null) }
            try { applyPickerLocked() } catch (e: EngineException) { _state.update { it.copy(message = e.message) } }
        }
        requestRender()
    }

    /** A picker setting set (a slider, a list entry, a switch); desktop picks again when its condition holds. */
    suspend fun setSetting(key: InstanceKey, controlId: Int, value: JsonPrimitive) {
        val setting = schema.modules[key.op]?.pickerSettings?.get(controlId) ?: return
        _state.update { it.copy(pickerSettings = it.pickerSettings + (setting.key to value)) }
        reapplyAfterSetting(key, setting)
    }

    private suspend fun reapplyAfterSetting(key: InstanceKey, setting: PickerSetting) {
        val p = _state.value.picker ?: return
        val cond = setting.reapplyWhen ?: return
        if (p.key != key || pickerSpec(p.key, p.controlId)?.action == null) return
        val s = _state.value
        if (cond.all { (k, v) -> PickerSetting.same(settingValue(s, key.op, k), v) }) pickerApply()
    }

    /** A list setting's entry chosen. */
    suspend fun chooseSetting(key: InstanceKey, controlId: Int, index: Int) {
        val setting = schema.modules[key.op]?.pickerSettings?.get(controlId) ?: return
        val (label, v) = setting.entries.getOrNull(index) ?: return
        if (label in setting.unwritable) return
        setSetting(key, controlId, v)
    }

    // ------------------------------------------------------------------ rendering
    //
    // Brief P2i: at fit the darkroom shows the preview pipe's picture only, rendered at the size of the photo's
    // frame on screen ([previewSize]); the full pipe (the sharp region) runs only while the view is zoomed in
    // beyond fit. On zoom-in the preview is shown scaled up at once and the region follows when ready; zooming
    // back to fit stops a region render in flight and drops the region. Before P2i the full pipe rendered the
    // region at fit after every change; demosaic then works on the whole sensor at full resolution (4.7 s of
    // 5.8 s on the owner's phone with a heavy edit), and a change made meanwhile waited for it, because a render
    // stops only between modules (the P2i brief and the P2f report, section 3, option A).

    private fun cancelRenders() {
        renderJob?.cancel()
        regionJob?.cancel()
        engine.renderCancel()
    }

    /** Brief P2i: whether [vp] shows the photo larger than at fit (a pinch or a double tap). */
    private fun zoomedIn(vp: Viewport?) = vp != null && vp.zoom > ZOOM_FIT_LIMIT

    /**
     * Brief P2i: the preview's longer side: the longer side of the photo's frame on screen at fit, at least
     * [previewMaxSize] and at most [fitPreviewMax]. The bridge never scales the preview up (it returns at most
     * the preview pipe's own size, about 1350 px on the longer side of an uncropped 3:2 photo: darktable's
     * `DT_MIPMAP_F`, see the P2i report), so asking for more than that costs nothing. The same size is used when
     * zoomed in (the preview is then shown scaled up under the region), so a zoom does not make the preview pipe
     * render at another size.
     */
    fun previewSize(): Int {
        val vp = viewport ?: return previewMaxSize
        val info = _state.value.info ?: return previewMaxSize
        return previewSizeFor(vp.widthPx, vp.heightPx, info.width, info.height, previewMaxSize, fitPreviewMax)
    }

    /** Brief P2i: the photo view's size and zoom as last reported by the screen (null before the first). */
    val currentViewport: Viewport? get() = viewport

    /** Brief P2i: the size the last preview was asked at, to render it again when the frame's size changes. */
    @Volatile private var lastPreviewAsk: Int? = null

    /** Preview first, then, only when zoomed in beyond fit, the visible region; a newer request cancels this one. */
    fun requestRender() {
        // brief P3a: while the crop tool is open (or about to open) the photo shows its uncropped picture
        if (cropWanted(_state.value) != null) return
        cancelRenders()
        renderJob = scope.launch {
            try {
                renderPreview()
                // brief P2i: the full pipe only when zoomed in (read now: the user may have zoomed meanwhile)
                if (zoomedIn(viewport)) renderRegion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                if (e.error != EngineError.CANCELLED) _state.update { it.copy(message = e.message) }
            }
        }
    }

    /** The same as [requestRender], waiting for the result (tests and screenshots). */
    suspend fun renderNow() {
        if (cropWanted(_state.value) != null) { syncCropTool(); return } // brief P3a
        cancelRenders()
        renderPreview()
        if (zoomedIn(viewport)) renderRegion()
        if (_state.value.before) renderBefore()
    }

    /**
     * An interactive render that ends CANCELLED although its own coroutine is still wanted was stopped by a
     * cancel meant for something else: run it again (at most twice) rather than leave the screen without it.
     * Brief P2i: [still] says whether the picture is still wanted at all (a region is not, once the view is back
     * at fit: its cancel was meant for it).
     */
    private suspend fun <T> wanted(still: () -> Boolean = { true }, block: suspend () -> T): T {
        var n = 0
        while (true) {
            try {
                return block()
            } catch (e: EngineException) {
                if (e.error != EngineError.CANCELLED || !kotlinx.coroutines.currentCoroutineContext().isActive || !still() || ++n > 2) throw e
                Timeline.mark("darkroom: a render stopped by a cancel not meant for it, run again")
            }
        }
    }

    private suspend fun renderPreview() {
        val img = fg {
            if (_state.value.info == null) return@fg null
            val size = previewSize()
            lastPreviewAsk = size
            Timeline.mark("darkroom: preview render start ($size px)")
            val t0 = System.nanoTime()
            // brief P3d: with "show mask" on, the engine's mask display of that instance (engine-api.md 6.8)
            val opts = maskRenderOptions()
            wanted { if (opts.isNone) engine.renderPreview(size) else engine.renderPreview(size, opts) }.also {
                val ns = System.nanoTime() - t0
                dragPicture.record(it.width * it.height, ns)
                _times.update { t -> t.copy(previewMs = ns / 1e6, previewPx = max(it.width, it.height)) }
                Timeline.mark("darkroom: preview rendered ${it.width}x${it.height} in ${ns / 1_000_000} ms")
            }
        }
        if (img == null) return
        val info = _state.value.info ?: return
        _state.update { it.copy(preview = PhotoLayer(img, 0, 0, img.width.toDouble() / info.width), region = null, placeholder = null, working = null, workingProgress = null) }
        Timeline.mark("darkroom: preview shown")
        if (_state.value.showMask != null) return // brief P3d: the mask display is no picture for the filmstrip
        // the filmstrip keeps a small copy, not the preview itself (a 1024 px preview is about 3 MB)
        val thumb = Thumbnails.downscale(img, thumbnailSize)
        val i = _state.value.index
        _state.update { s -> s.copy(thumbnails = s.thumbnails + (i to thumb)) }
        _state.value.series.items.getOrNull(i)?.let { item -> thumbnailStore?.store(item, thumb) }
    }

    /** Times of the last renders, for the timings page and tests (ms, wherever measured); [previewPx] its longer side. */
    data class RenderTimes(val previewMs: Double? = null, val regionMs: Double? = null, val previewPx: Int? = null)

    private val _times = MutableStateFlow(RenderTimes())
    val renderTimes: StateFlow<RenderTimes> = _times.asStateFlow()

    /** Brief P2i: true while an interactive region render runs in the engine; set and cleared under [regionGuard]. */
    @Volatile private var regionRendering = false
    private val regionGuard = Any()
    private val regionsPending = java.util.concurrent.atomic.AtomicInteger(0)

    /** Brief P2i: stop the region render in flight (the view went back to fit); no other render is touched. */
    private fun cancelRegionRender() = synchronized(regionGuard) { if (regionRendering) engine.renderCancel() }

    /** Brief P2i: how many region renders the darkroom started, for tests (at fit it must start none). */
    val regionRenders = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun renderRegion() {
        if (!zoomedIn(viewport)) return
        // brief P1b part 2: with the crop tool open the sharp region is one of its uncropped picture (same options)
        _state.value.cropTool?.let { if (it.picture == null) return }
        // brief P2i: "rendering…" over the photo while the sharp region of a zoomed-in view is on its way
        if (regionsPending.incrementAndGet() == 1) _state.update { it.copy(regionPending = true) }
        try {
            val layer = fg {
                wanted({ zoomedIn(viewport) }) {
                    // read on every try: the view may be back at fit by now, and then nothing is rendered
                    val vp = viewport?.takeIf { zoomedIn(it) } ?: return@wanted null
                    val info = _state.value.info ?: return@wanted null
                    val tool = _state.value.cropTool
                    val options = tool?.options ?: maskRenderOptions()
                    val r = (if (tool != null) regionFor(vp, tool.inW, tool.inH) else regionFor(vp, info.width, info.height)) ?: return@wanted null
                    val size = sizeWith(r.scale, options)
                    val x = r.x.coerceIn(0, size.w - 1)
                    val y = r.y.coerceIn(0, size.h - 1)
                    val w = min(r.w, size.w - x)
                    val h = min(r.h, size.h - y)
                    if (w <= 0 || h <= 0) return@wanted null
                    regionRenders.incrementAndGet()
                    Timeline.mark("darkroom: region render start (${w}x$h at scale ${"%.3f".format(java.util.Locale.ROOT, r.scale)})")
                    val t0 = System.nanoTime()
                    val img = try {
                        synchronized(regionGuard) { regionRendering = true }
                        if (options.isNone) engine.renderRegion(x, y, w, h, r.scale) else engine.renderRegion(x, y, w, h, r.scale, options)
                    } catch (e: EngineException) {
                        if (e.error == EngineError.CANCELLED) Timeline.mark("darkroom: region render cancelled")
                        throw e
                    } catch (e: CancellationException) {
                        Timeline.mark("darkroom: region render cancelled")
                        throw e
                    } finally {
                        synchronized(regionGuard) { regionRendering = false }
                    }
                    _times.update { t -> t.copy(regionMs = (System.nanoTime() - t0) / 1e6) }
                    PhotoLayer(img, x, y, r.scale) to (tool?.picture)
                }
            } ?: return
            // shown only while still zoomed in: a zoom back to fit meanwhile drops it; a region of the crop tool's
            // picture only while the tool still shows that picture
            if (zoomedIn(viewport) && _state.value.cropTool?.picture === layer.second) {
                _state.update { it.copy(region = layer.first, regionOfCropTool = layer.second != null) }
                Timeline.mark("darkroom: region shown")
            }
        } finally {
            if (regionsPending.decrementAndGet() == 0) _state.update { it.copy(regionPending = false) }
        }
    }

    private data class Region(val x: Int, val y: Int, val w: Int, val h: Int, val scale: Double)

    private fun regionFor(vp: Viewport, iw: Int, ih: Int): Region? {
        if (vp.widthPx <= 0 || vp.heightPx <= 0) return null
        val fit = min(vp.widthPx.toDouble() / iw, vp.heightPx.toDouble() / ih)
        val display = fit * vp.zoom
        val scale = min(1.0, display)
        val halfW = vp.widthPx / (2 * display)
        val halfH = vp.heightPx / (2 * display)
        val x0 = max(0.0, vp.centerX * iw - halfW)
        val y0 = max(0.0, vp.centerY * ih - halfH)
        val x1 = min(iw.toDouble(), vp.centerX * iw + halfW)
        val y1 = min(ih.toDouble(), vp.centerY * ih + halfH)
        if (x1 <= x0 || y1 <= y0) return null
        val x = floor(x0 * scale).toInt()
        val y = floor(y0 * scale).toInt()
        return Region(x, y, ceil(x1 * scale).toInt() - x, ceil(y1 * scale).toInt() - y, scale)
    }

    /**
     * The screen's photo view changed size, zoom or position. Zoomed in: the visible region is rendered again
     * (the preview, scaled up, shows meanwhile). Brief P2i: at fit no region is rendered; coming back to fit
     * stops a region render in flight and drops the region; a frame of another size gets a preview of its size.
     */
    fun setViewport(vp: Viewport) {
        if (vp == viewport) return
        val before = viewport
        viewport = vp
        regionJob?.cancel()
        if (!zoomedIn(vp)) {
            if (zoomedIn(before) || _state.value.region != null) {
                cancelRegionRender()
                if (_state.value.region != null) _state.update { it.copy(region = null) }
                Timeline.mark("darkroom: back at fit, region render stopped and region dropped")
            }
            val asked = lastPreviewAsk
            if (_state.value.info != null && asked != null && previewSize() != asked && !dragging) {
                regionJob = scope.launch {
                    delay(VIEWPORT_SETTLE_MS) // wait for the frame to settle (a rotation sends several sizes)
                    // a render in flight may have asked the old size (run 44704862471): wait for it, then compare
                    renderJob?.join()
                    if (_state.value.info != null && !dragging && previewSize() != lastPreviewAsk) requestRender()
                }
            }
            return
        }
        regionJob = scope.launch {
            delay(VIEWPORT_SETTLE_MS) // wait for the gesture to settle
            if (renderJob?.isActive == true) return@launch // the running render ends with the region
            try {
                renderRegion()
            } catch (e: EngineException) {
                if (e.error != EngineError.CANCELLED) _state.update { it.copy(message = e.message) }
            }
        }
    }

    /** "before": the image at history position 0, while held or toggled (ui-spec.md 4). */
    suspend fun setBefore(on: Boolean) {
        _state.update { it.copy(before = on) }
        if (on && _state.value.beforePreview == null) {
            cancelRenders()
            try { renderBefore() } catch (e: EngineException) {
                if (e.error != EngineError.CANCELLED) _state.update { it.copy(message = e.message) }
            }
        }
    }

    private suspend fun renderBefore() {
        val layer = fg {
            val info = _state.value.info ?: return@fg null
            // since P1c end <= items always (unknown items of a read-only session are listed), so the
            // clamp P2b needed here is gone
            val end = engine.history().end
            try {
                engine.historyGoto(0)
                // brief P2i: at the preview's size, so "before" and "after" look alike on screen
                val img = engine.renderPreview(previewSize())
                PhotoLayer(img, 0, 0, img.width.toDouble() / info.width.coerceAtLeast(1))
            } finally {
                engine.historyGoto(end)
            }
        } ?: return
        _state.update { it.copy(beforePreview = layer) }
    }

    // ------------------------------------------------------------------ output and maintenance (brief P2b)

    /**
     * Run [block] with the engine to itself: renders are cancelled, the controller's lock is held,
     * and afterwards the state is re-read and the photo rendered again. For the timings page,
     * which closes and reopens the photo.
     */
    suspend fun <T> withEngine(block: suspend (DarktableEngine) -> T): T {
        cancelRenders()
        val r = fg {
            leaveCropLocked() // brief P3a: the block may close and reopen the photo
            try {
                block(engine)
            } finally {
                if (_state.value.info != null) {
                    try {
                        _state.update { it.copy(info = engine.imageInfo()) }
                        refreshLocked()
                    } catch (e: EngineException) {
                        _state.update { it.copy(message = e.message) }
                    }
                }
            }
        }
        requestRender()
        return r
    }

    /** Export the open photo through darktable's export (engine-api.md 2.8) to [path]. */
    suspend fun export(path: String, options: app.dtandroid.engine.api.ExportOptions) {
        cancelRenders()
        try {
            fg {
                if (_state.value.info == null) throw EngineException(EngineError.STATE, "no photo open")
                // brief P3a: the export shows the frame on the screen: the crop tool's frame is written first
                commitCropLocked(close = false)
                refreshLocked()
                engine.export(path, options)
            }
        } finally {
            requestRender()
        }
    }

    /** Write the open photo's edit as an XMP to [path]; refused for read-only photos (READ_ONLY). */
    suspend fun writeXmp(path: String) {
        cancelRenders()
        try {
            fg {
                if (_state.value.info == null) throw EngineException(EngineError.STATE, "no photo open")
                // brief P3a: as for the export, the crop tool's frame is written first
                commitCropLocked(close = false)
                refreshLocked()
                engine.xmpWrite(path)
            }
        } finally {
            requestRender()
        }
    }

    // ------------------------------------------------------------------ LightPhotoLibrary sync (brief P2e)

    /** The photo open in the darkroom, if any (its RAW path). */
    fun openRawPath(): String? = _state.value.takeIf { it.info != null }?.current?.rawPath

    /** If [rawPath] is open (and editable): write its edit to its sidecar now. */
    suspend fun saveIfOpen(rawPath: String) {
        if (openRawPath() != rawPath) return
        cancelRenders()
        fg { if (openRawPath() == rawPath) saveLocked() }
        requestRender()
    }

    /** If [rawPath] is open: re-read rating, labels and tags from its sidecar (LightPhotoLibrary merged them). */
    suspend fun reloadMetadataIfOpen(rawPath: String, xmpPath: String) {
        if (openRawPath() != rawPath) return
        cancelRenders()
        fg {
            if (openRawPath() == rawPath) {
                try {
                    engine.metadataReload(xmpPath)
                    _state.update { it.copy(info = engine.imageInfo()) }
                } catch (e: EngineException) {
                    _state.update { it.copy(message = e.message) }
                }
            }
        }
        requestRender()
    }

    /**
     * Run [block] with a session on the photo [rawPath]: the darkroom's own session when that photo is
     * open, or a session of its own (opened with [xmpPath] and closed again) when no photo is open.
     * Returns null without running [block] while another photo is open or being fetched: the engine
     * holds one photo at a time (engine-api.md 1), and the darkroom's photo is never closed for this.
     */
    suspend fun <T> withPhotoSession(rawPath: String, xmpPath: String, block: suspend (DarktableEngine) -> T): T? {
        // brief P2f: background work: it waits while the user works, and a cancelled render is run again later
        var usedOpen = false
        val r = bg("session on ${File(rawPath).name}") { e ->
            val s = _state.value
            when {
                s.info != null && s.current?.rawPath == rawPath -> { usedOpen = true; Result.success(block(e)) }
                s.info == null && !fetching -> {
                    // brief P2h: a file without a picture is not opened again until it changes, and a session
                    // that turns out to have none is closed before [block] could render it
                    if (knownUnreadable(rawPath)) {
                        Timeline.mark("darkroom: ${File(rawPath).name} known to have no picture, no session opened")
                        noPicture()
                    }
                    val stamp = stampOf(rawPath)
                    openOrRemember(e, rawPath, xmpPath.takeIf { File(it).isFile }, stamp)
                    var open = true
                    try {
                        if (pictureOrClose(e, rawPath, stamp) == null) { open = false; noPicture() }
                        Result.success(block(e))
                    } finally { if (open) runCatching { e.close() } }
                }
                else -> null
            }
        }
        // a region render on the darkroom's own session replaced its pipe's last region: show it again
        if (usedOpen) requestRender()
        return r?.getOrThrow()
    }

    // ------------------------------------------------------------------ interface state

    /**
     * A tab of the module groups bar. Tapping the selected group (or the active tab) again
     * shows all modules of all groups, as on desktop (`_lib_modulegroups_toggle`); choosing
     * a tab clears the search line.
     */
    fun selectGroup(g: GroupTab) = _state.update {
        val next = if (g == it.group && g != GroupTab.Quick && it.search.isEmpty() && it.forceShow == null) GroupTab.AllModules else g
        it.copy(group = next, search = "", forceShow = null, overlay = null)
    }

    fun setSearch(text: String) = _state.update { it.copy(search = text, forceShow = null) }

    // ------------------------------------------------------------------ module layouts

    /**
     * Read the current layout from [layouts] and show it. At the first start the selected
     * group is darktable's default (`plugins/darkroom/groups`); later the selected tab is kept
     * where it still exists, else the active modules are shown.
     */
    suspend fun loadLayout(initial: Boolean = false) {
        var list = emptyList<app.dtandroid.engine.api.StoredLayout>()
        val (name, text) = try {
            val n = layouts.current()
            list = layouts.list()
            n to (layouts.read(n)?.params ?: layouts.read(layouts.fallbackName)?.params ?: "")
        } catch (e: LayoutStoreException) {
            _state.update { it.copy(message = e.message) }
            layouts.fallbackName to (builtins.presets(builtins.defaultWorkflow).firstOrNull { it.name == builtins.fallbackLayout }?.params ?: "")
        }
        val l = LayoutCodec.parse(text)
        val quick = l.quickAccess.map { QuickAccess.resolve(schema, it) }
        _state.update { s ->
            val want = if (initial) when {
                builtins.defaultGroup <= 0 -> GroupTab.Active
                else -> GroupTab.Group(builtins.defaultGroup - 1)
            } else s.group
            val group = when (want) {
                is GroupTab.Group -> if (want.index < l.groups.size) want else GroupTab.Active
                GroupTab.Quick -> if (l.quickAccessShown) want else GroupTab.Active
                else -> want
            }
            s.copy(layoutName = name, layout = l, layoutList = list, quick = quick, group = group, search = if (l.showSearch) s.search else "")
        }
    }

    /** Make [name] the current layout (the layouts menu next to the tabs). */
    suspend fun chooseLayout(name: String) {
        try {
            layouts.setCurrent(name)
        } catch (e: LayoutStoreException) {
            _state.update { it.copy(message = e.message) }
        }
        _state.update { it.copy(overlay = null) }
        loadLayout()
    }

    /** "manage module layouts" (`manage_presets`), working on [layouts]. */
    val layoutEditor = LayoutEditor(layouts, builtins, schema)

    /** Open "manage module layouts" on the current layout. */
    suspend fun openLayoutEditor() {
        layoutEditor.open()
        _state.update { it.copy(layoutEditor = true, overlay = null) }
    }

    /** Close it: the edits are kept and the darkroom shows the (possibly changed) current layout. */
    suspend fun closeLayoutEditor() {
        layoutEditor.save()
        _state.update { it.copy(layoutEditor = false) }
        loadLayout()
    }

    /**
     * "go to the full version of the module" from the quick access panel (`_basics_goto_module`):
     * the first group that has the module, or the module alone when no group has it; expanded.
     */
    fun goToModule(key: InstanceKey) {
        _state.update { s ->
            val i = s.layout.groups.indexOfFirst { key.op in it.modules }
            if (i >= 0) s.copy(group = GroupTab.Group(i), expanded = key, search = "", forceShow = null, overlay = null)
            else s.copy(group = GroupTab.Active, forceShow = key, expanded = key, search = "", overlay = null)
        }
        pickerCheck() // brief P3c: another module's panel: the picker goes
        launch { syncCropTool() } // brief P3a: crop's panel opened or another module's
    }

    fun toggleExpanded(key: InstanceKey) {
        _state.update { it.copy(expanded = if (it.expanded == key) null else key, overlay = null) }
        pickerCheck() // brief P3c: leaving the picker's module disarms it
        launch { syncCropTool() } // brief P3a: opening crop's panel opens the tool; closing it or opening another leaves it
    }

    fun setView(v: BottomView) {
        val was = _state.value.view
        _state.update { it.copy(view = v, overlay = null) }
        // brief P3d: leaving the masks tab ends "show mask"; opening it reads the chosen module's mask again
        if (was == BottomView.MASKS && v != BottomView.MASKS) { setShowMask(null); if ((_state.value.picker?.controlId ?: 0) <= ParametricMasks.PICK_BASE) disarmPicker() }
        if (v == BottomView.MASKS && was != v) launch { masks.reload() }
    }
    fun toggleFilmstrip() = _state.update { it.copy(filmstrip = !it.filmstrip) }
    fun setOverlay(o: Overlay?) {
        val was = _state.value.overlay
        _state.update { it.copy(overlay = o) }
        // brief P3b: closing the presets or styles sheet drops its pages (an open import question is cancelled)
        if (was != o && (was is Overlay.Presets || was == Overlay.Styles)) stylesPresets.closed()
    }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Brief P2f: what the app knows about the open photo beyond the engine: its sync step and share done
     * (R16) and its last edit (R15). Only applied while [rawPath] is the open photo.
     */
    fun setPhotoNotes(rawPath: String, syncStep: String?, syncProgress: Float?, lastEdit: String?) = _state.update { s ->
        if (s.current?.rawPath != rawPath) s else s.copy(syncStep = syncStep, syncProgress = syncProgress, lastEdit = lastEdit)
    }
    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    // ------------------------------------------------------------------ brief P3b: presets and styles

    /** The presets and styles sheets (engine-api.md 5.2, 5.3). */
    val stylesPresets: StylesPresets by lazy { StylesPresets(this) }

    /** An engine call that does not change the edit (lists, darktable's database of presets and styles), under the lock. */
    internal suspend fun <T> engineRead(block: suspend (DarktableEngine) -> T): T = fg { block(engine) }

    /** A change of the edit through [block], as every change: render cancelled, state read again, rendered again. */
    internal suspend fun engineChange(block: suspend (DarktableEngine) -> Unit) = change { block(engine) }

    // ------------------------------------------------------------------ brief P3d: masks

    /** made once, whichever thread asks first (the screen, a launched reload, a test) */
    private val masksLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Masks(this) }
    private val masksOrNull: Masks? get() = if (masksLazy.isInitialized()) masksLazy.value else null

    /** The masks tab and the drawing tools (Masks.kt). */
    val masks: Masks get() = masksLazy.value

    /** Brief P3d stage 2b: parametric channels and mask refinement (ParametricMasks.kt). */
    val parametric: ParametricMasks by lazy { ParametricMasks(this) }

    /** The render options of "show mask": none unless it is on for an instance. */
    private fun maskRenderOptions(): RenderOptions =
        _state.value.showMask?.let { RenderOptions(showMask = app.dtandroid.engine.api.InstanceRef(it.op, it.priority)) } ?: RenderOptions.NONE

    /** Brief P3d: "show mask" on for [key] or off (null); renders again. */
    fun setShowMask(key: InstanceKey?) {
        if (_state.value.showMask == key) return
        _state.update { it.copy(showMask = key) }
        requestRender()
    }

    /** Brief P3d: the masks tab with [key]'s mask (from the mask indicator or a module's "mask…" row). */
    fun openMasks(key: InstanceKey) {
        _state.update { it.copy(view = BottomView.MASKS, overlay = null) }
        launch { masks.select(key) }
    }

    // ------------------------------------------------------------------ helpers for screens

    /**
     * Modules the list shows, in desktop's order (last in the pipe on top), following
     * `_lib_modulegroups_update_iop_visibility`: search text matches name, aliases and
     * instance name (deprecated modules only when on); "active" lists modules that are on,
     * or with "show all history modules" those in the history; a group lists its modules,
     * deprecated ones only when on; all modules lists every module of any group plus those on.
     */
    fun visibleModules(s: DarkroomState): List<ModuleInstance> {
        val listed = s.modules.filter { !it.hidden && schema.modules[it.op]?.hasPanel != false }
        s.forceShow?.let { k -> return listed.filter { it.key == k } }
        val text = s.search.takeIf { s.layout.showSearch }?.trim()?.lowercase().orEmpty()
        if (text.isNotEmpty()) {
            return listed.filter { m ->
                if (m.deprecated && !m.enabled) return@filter false
                val ms = schema.modules[m.op]
                (ms?.name ?: m.name).lowercase().contains(text) || (ms?.aliases ?: "").lowercase().contains(text) ||
                    m.instanceName.lowercase().contains(text)
            }.asReversed()
        }
        val inGroup = when (val g = s.group) {
            GroupTab.Active -> if (s.layout.fullActive) listed.filter { it.inHistory } else listed.filter { it.enabled }
            GroupTab.Quick -> emptyList()
            GroupTab.AllModules -> {
                val any = s.layout.groups.flatMap { it.modules }.toSet()
                listed.filter { (!it.deprecated && it.op in any) || it.enabled }
            }
            is GroupTab.Group -> {
                val ops = s.layout.groups.getOrNull(g.index)?.modules?.toSet() ?: emptySet()
                listed.filter { it.op in ops && (!it.deprecated || it.enabled) }
            }
        }
        return inGroup.asReversed()
    }

    /** One module's part of the quick access panel. */
    data class QuickBlock(
        val instance: ModuleInstance?,
        val moduleName: String,
        val onOff: QuickAccess.Entry?,
        val entries: List<QuickAccess.Entry>,
        /** desktop disables a module's quick access widgets when it has several instances */
        val severalInstances: Boolean,
    )

    /**
     * The quick access panel (`_basics_show`): per module, in the module list's order (last in
     * the pipe on top), its on-off button first, then its entries in the order of the module's
     * own panel; hidden and deprecated modules are left out on desktop. Entries the app cannot
     * use are kept (shown disabled with their name), and entries of modules the panel does not
     * show (deprecated, or not in the pipe) come last, disabled, with the reason.
     */
    fun quickPanel(s: DarkroomState): List<QuickBlock> {
        val byOp = s.quick.groupBy { it.op }
        val order = s.modules.asReversed().filter { !it.hidden && !it.deprecated }.map { it.op }.distinct()
        val blocks = ArrayList<QuickBlock>()
        for (op in order) {
            val es = byOp[op] ?: continue
            val instances = s.modules.filter { it.op == op }
            val top = instances.lastOrNull()
            val ms = schema.modules[op]
            blocks += QuickBlock(
                top, ms?.name ?: op, es.firstOrNull { it.kind == QuickAccess.Kind.ON_OFF },
                es.filter { it.kind != QuickAccess.Kind.ON_OFF }.sortedBy { it.treeIndex },
                instances.size > 1,
            )
        }
        val deprecated = s.modules.filter { it.deprecated }.map { it.op }.toSet()
        val rest = s.quick.filter { it.op !in order }.map { e ->
            if (e.reason != null) e
            else e.copy(reason = if (e.op in deprecated) "deprecated module: desktop leaves it out of the quick access panel"
                                 else "the module is not in this photo's pipe")
        }
        if (rest.isNotEmpty()) blocks += QuickBlock(null, "not available", null, rest, false)
        return blocks
    }

    /** Whether a quick access control is shown: desktop hides it when the module's own widget is hidden. */
    fun quickVisible(m: ModuleInstance, e: QuickAccess.Entry): Boolean {
        val ms = e.module ?: return true
        val c = e.control ?: return true
        val inputs = ParamRuleInputs(ms, m.params)
        return ms.pathTo(c.id).all { Rules.isVisible(ms, it, inputs) }
    }
    /** The value a control shows: the finger's value while dragging, else the engine's. */
    fun sliderValue(s: DarkroomState, key: InstanceKey, c: Control.Slider, live: Map<Pair<InstanceKey, Int>, Double>): Double? {
        cropMarginValue(s, key, c.binding)?.let { return it } // brief P3a: the frame in the crop tool
        live[key to c.id]?.let { return it }
        return when (val b = c.binding) {
            is Binding.Param -> {
                val m = s.modules.firstOrNull { it.key == key } ?: return null
                (paramValue(m, b.path) as? JsonPrimitive)?.doubleOrNull
            }
            is Binding.Action -> (s.actionValues[key]?.get(b.field) as? JsonPrimitive)?.doubleOrNull
            Binding.Computed -> computedValue(s, key, c.id)
            Binding.None, Binding.Aspect, Binding.ActionList -> c.default
            is Binding.Setting -> settingValue(s, key.op, b.key)?.settingNumber() ?: c.default
        }
    }

    fun paramValue(m: ModuleInstance, path: String) = m.params.paramAt(path)

    companion object {
        /** brief P3e-b: the control id of a band graph's drag (a drag of fields, not of one slider) */
        const val BAND_DRAG = -7

        /** background work starts only after the darkroom was quiet this long */
        const val QUIET_MS = 300L

        /**
         * Brief P2i: the largest preview asked for the view at fit. On the owner's phone, with a heavy edit, the
         * preview after an exposure change took 877 ms at 1024 px and grows a little faster than its pixels
         * (768 px 424 ms, 1024 px 877 ms); estimated 1080 px about 1.0 s, 1152 px about 1.2 s, 1350 px (the
         * preview pipe's limit for a 3:2 photo) about 1.75 s. Target: a change at fit shown within about 1.2 s
         * there, so 1152 px; a phone's landscape frame (1080 px) is below it and gets its own size.
         */
        const val FIT_PREVIEW_MAX = 1152

        /** Brief P2i: a zoom up to this counts as fit (the gesture code rounds near 1). */
        const val ZOOM_FIT_LIMIT = 1.001f

        /**
         * Brief P2i: the preview's longer side for a view of [viewW] x [viewH] pixels showing a photo of
         * [imageW] x [imageH] (processed) at fit: the longer side of the photo's frame, kept between [least] and
         * [most] ([least] wins if they cross); [least] when the view's size is not known.
         */
        fun previewSizeFor(viewW: Int, viewH: Int, imageW: Int, imageH: Int, least: Int, most: Int): Int {
            if (viewW <= 0 || viewH <= 0 || imageW <= 0 || imageH <= 0) return least
            val fit = min(viewW.toDouble() / imageW, viewH.toDouble() / imageH)
            val frame = ceil(max(imageW, imageH) * fit - 1e-6).toInt()
            return max(least, min(frame, most))
        }

        /** Brief P3a: crop's operation name, and the modules after it that change the geometry (the tool waits for them). */
        const val CROP_OP = "crop"
        val GEOMETRY_AFTER_CROP = setOf("borders")

        /** Brief P3a deliverable 4: rotate and perspective, its rotation; orientation (flip) for the straightening line. */
        const val ASHIFT_OP = "ashift"
        const val ROTATION_PARAM = "rotation"
        const val FLIP_OP = "flip"
        const val ORIENTATION_PARAM = "orientation"

        /** Brief P3a: what the darkroom says when the crop tool's bracket could not restore the edit (never expected). */

        /** "rendering…": the line over the photo while a picture is on its way. */
        const val RENDERING = "rendering…"

        private const val VIEWPORT_SETTLE_MS = 120L
        private const val BG_POLL_MS = 50L
        private const val CANCEL_REPEAT_MS = 20L

        /** Brief P2f/P2h: what the darkroom says about a file darktable found no picture in. */
        const val NO_PICTURE = "darktable found no picture in this file (not a RAW it can read, or a broken download)"

        /** Brief P2h: whether [e] is the app's "no picture" answer (EngineException puts the error's name first). */
        fun isNoPicture(e: EngineException) = e.error == EngineError.IO && e.message == "${EngineError.IO.name}: $NO_PICTURE"
    }
}

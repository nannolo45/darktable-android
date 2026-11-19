// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.paramAt
import app.dtandroid.engine.api.XmpCheck
import app.dtandroid.engine.api.XmpProblemReason
import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.engine.api.LayoutStore
import app.dtandroid.engine.api.LayoutStoreException
import app.dtandroid.schema.ParamRuleInputs
import app.dtandroid.schema.Rules
import app.dtandroid.schema.layout.BuiltinLayouts
import app.dtandroid.schema.layout.LayoutCodec
import app.dtandroid.schema.layout.QuickAccess
import app.dtandroid.schema.UiSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val thumbnailSize: Int = 128,
    /** brief P2b: the size of the picture that follows a finger, chosen from measured render times */
    val dragPicture: DragPicture = DragPicture(maxSize = previewMaxSize),
    /** brief P2b: thumbnails kept between starts (the real engine needs a RAW decode per thumbnail); null = render all */
    private val thumbnailStore: ThumbnailStore? = null,
) {
    private val _state = MutableStateFlow(DarkroomState())
    val state: StateFlow<DarkroomState> = _state.asStateFlow()

    /** Slider values while a finger is on them, by instance and control, ahead of the engine. */
    private val _live = MutableStateFlow<Map<Pair<InstanceKey, Int>, Double>>(emptyMap())
    val liveValues: StateFlow<Map<Pair<InstanceKey, Int>, Double>> = _live.asStateFlow()

    private val lock = Mutex()
    private var renderJob: Job? = null
    private var regionJob: Job? = null
    private var viewport: Viewport? = null

    private data class Drag(val key: InstanceKey, val controlId: Int, val binding: Binding, val value: Double, val integer: Boolean, val end: Boolean)

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
            val thumbs = HashMap<Int, app.dtandroid.engine.api.RenderedImage>()
            for ((i, item) in series.items.withIndex()) thumbnailStore?.load(item)?.let { thumbs[i] = it }
            lock.withLock {
                for ((i, item) in series.items.withIndex()) {
                    if (i in thumbs) continue
                    try {
                        engine.open(item.rawPath, item.xmpPath.takeIf { File(it).isFile })
                        val t = engine.renderPreview(thumbnailSize)
                        thumbs[i] = t
                        thumbnailStore?.store(item, t)
                        engine.close()
                    } catch (e: EngineException) {
                        // a photo that cannot be opened has no thumbnail
                    }
                }
            }
            _state.update { it.copy(thumbnails = thumbs) }
        }
        openPhoto(index, saveCurrent = false)
    }

    /** Switch to another photo of the series; the current edit is written to its sidecar first. */
    suspend fun openPhoto(index: Int, saveCurrent: Boolean = true) {
        val item = _state.value.series.items.getOrNull(index) ?: return
        cancelRenders()
        lock.withLock {
            if (saveCurrent) saveLocked()
            runCatching { engine.close() }
            _state.update {
                it.copy(index = index, loading = true, preview = null, region = null, beforePreview = null, before = false,
                    expanded = null, overlay = null, message = null, readOnlyReason = null)
            }
            try {
                val xmp = item.xmpPath.takeIf { File(it).isFile }
                val check = xmp?.let { engine.xmpCheck(it) }
                engine.open(item.rawPath, xmp)
                val info = engine.imageInfo()
                _state.update { it.copy(info = info, readOnlyReason = if (info.readOnly) readOnlyText(check) else null) }
                refreshLocked()
            } catch (e: EngineException) {
                _state.update { it.copy(message = "cannot open ${item.fileName}: ${e.message}") }
            }
            _state.update { it.copy(loading = false) }
        }
        requestRender()
    }

    /**
     * Leave the photo (brief P2b: back to the working folder): the edit is written to its
     * sidecar (not for read-only photos), the session closed and the pictures dropped.
     */
    suspend fun closePhoto() {
        cancelRenders()
        lock.withLock {
            saveLocked()
            runCatching { engine.close() }
            _state.update {
                it.copy(info = null, preview = null, region = null, beforePreview = null, before = false, modules = emptyList(),
                    expanded = null, overlay = null, readOnlyReason = null, loading = false)
            }
        }
    }

    /** Write the current edit (not for read-only photos). Called when leaving the darkroom. */
    suspend fun save() {
        cancelRenders()
        lock.withLock { saveLocked() }
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
        val info = engine.imageInfo()
        _state.update { s ->
            val expanded = s.expanded?.takeIf { k -> modules.any { it.key == k } }
            s.copy(modules = modules, history = history, undo = undo, actionValues = actions, info = info, expanded = expanded)
        }
    }

    /**
     * Run a change: cancel the render, call the engine, re-read its state, render again.
     * Engine errors become [DarkroomState.message].
     */
    private suspend fun change(block: suspend () -> Unit) {
        cancelRenders()
        lock.withLock {
            try {
                // the "before" picture is kept for the session (engine-api.md, clarifications after P2a)
                _state.update { it.copy(message = null) }
                block()
            } catch (e: EngineException) {
                _state.update { it.copy(message = e.message) }
            }
            try { refreshLocked() } catch (e: EngineException) { _state.update { it.copy(message = e.message) } }
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
    suspend fun setSlider(key: InstanceKey, binding: Binding, value: Double, integer: Boolean) = change {
        writeBinding(key, binding, value, integer)
        engine.historySeal()
    }

    private suspend fun writeBinding(key: InstanceKey, binding: Binding, value: Double, integer: Boolean) {
        when (binding) {
            is Binding.Param -> engine.paramSet(key.op, key.priority, binding.path,
                if (integer) JsonPrimitive(Math.round(value)) else JsonPrimitive(value))
            is Binding.Action -> {
                val cur = _state.value.actionValues[key] ?: (engine.moduleAction(key.op, key.priority, binding.get) as? JsonObject) ?: JsonObject(emptyMap())
                val args = binding.fields.associateWith { f -> cur[f] ?: JsonPrimitive(0.0) }.toMutableMap()
                args[binding.field] = JsonPrimitive(value)
                engine.moduleAction(key.op, key.priority, binding.set, JsonObject(args))
            }
            Binding.None -> throw EngineException(EngineError.ARG, "this control has no parameter")
        }
    }

    /** A finger moves a slider: the value shows at once; the engine follows with the latest value. */
    fun drag(key: InstanceKey, controlId: Int, binding: Binding, value: Double, integer: Boolean) {
        _live.update { it + ((key to controlId) to value) }
        drags.trySend(Drag(key, controlId, binding, value, integer, end = false))
    }

    /** The finger lifts: the last value is written and the history item sealed. */
    fun endDrag(key: InstanceKey, controlId: Int, binding: Binding, integer: Boolean) {
        val v = _live.value[key to controlId] ?: return
        drags.trySend(Drag(key, controlId, binding, v, integer, end = true))
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
            val layer = lock.withLock {
                try {
                    writeBinding(d.key, d.binding, d.value, d.integer)
                    markOn(d.key)
                    _state.update { it.copy(message = null) }
                    val info = _state.value.info ?: return@withLock null
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
        dragSize = null
        lock.withLock {
            try {
                writeBinding(d.key, d.binding, d.value, d.integer)
                engine.historySeal()
                _state.update { it.copy(message = null) }
            } catch (e: EngineException) {
                _state.update { it.copy(message = e.message) }
            }
            try { refreshLocked() } catch (e: EngineException) { _state.update { it.copy(message = e.message) } }
        }
        _live.update { it - (d.key to d.controlId) }
        requestRender()
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
    suspend fun undo() = change { if (_state.value.undo.canUndo) engine.undo() }
    suspend fun redo() = change { if (_state.value.undo.canRedo) engine.redo() }

    // ------------------------------------------------------------------ rendering

    private fun cancelRenders() {
        renderJob?.cancel()
        regionJob?.cancel()
        engine.renderCancel()
    }

    /** Preview first, then the visible region; a newer request cancels this one. */
    fun requestRender() {
        cancelRenders()
        renderJob = scope.launch {
            try {
                renderPreview()
                renderRegion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                if (e.error != EngineError.CANCELLED) _state.update { it.copy(message = e.message) }
            }
        }
    }

    /** The same as [requestRender], waiting for the result (tests and screenshots). */
    suspend fun renderNow() {
        cancelRenders()
        renderPreview()
        renderRegion()
        if (_state.value.before) renderBefore()
    }

    private suspend fun renderPreview() {
        val img = lock.withLock {
            if (_state.value.info == null) return
            val t0 = System.nanoTime()
            engine.renderPreview(previewMaxSize).also {
                val ns = System.nanoTime() - t0
                dragPicture.record(it.width * it.height, ns)
                _times.update { t -> t.copy(previewMs = ns / 1e6) }
            }
        }
        val info = _state.value.info ?: return
        _state.update { it.copy(preview = PhotoLayer(img, 0, 0, img.width.toDouble() / info.width), region = null) }
        // the filmstrip keeps a small copy, not the preview itself (a 1024 px preview is about 3 MB)
        val thumb = Thumbnails.downscale(img, thumbnailSize)
        val i = _state.value.index
        _state.update { s -> s.copy(thumbnails = s.thumbnails + (i to thumb)) }
        _state.value.series.items.getOrNull(i)?.let { item -> thumbnailStore?.store(item, thumb) }
    }

    /** Times of the last renders, for the timings page and tests (ms, wherever measured). */
    data class RenderTimes(val previewMs: Double? = null, val regionMs: Double? = null)

    private val _times = MutableStateFlow(RenderTimes())
    val renderTimes: StateFlow<RenderTimes> = _times.asStateFlow()

    private suspend fun renderRegion() {
        val vp = viewport ?: return
        val layer = lock.withLock {
            val info = _state.value.info ?: return
            val r = regionFor(vp, info.width, info.height) ?: return
            val size = engine.renderSize(r.scale)
            val x = r.x.coerceIn(0, size.w - 1)
            val y = r.y.coerceIn(0, size.h - 1)
            val w = min(r.w, size.w - x)
            val h = min(r.h, size.h - y)
            if (w <= 0 || h <= 0) return
            val t0 = System.nanoTime()
            PhotoLayer(engine.renderRegion(x, y, w, h, r.scale), x, y, r.scale).also {
                _times.update { t -> t.copy(regionMs = (System.nanoTime() - t0) / 1e6) }
            }
        }
        _state.update { it.copy(region = layer) }
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

    /** The screen's photo view changed size, zoom or position: render the visible region again. */
    fun setViewport(vp: Viewport) {
        if (vp == viewport) return
        viewport = vp
        regionJob?.cancel()
        regionJob = scope.launch {
            delay(120) // wait for the gesture to settle
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
        val layer = lock.withLock {
            val info = _state.value.info ?: return
            val h = engine.history()
            // P2b: in a read-only session whose XMP names a module the engine does not know, the bridge
            // reports an end counting that dropped item (end 10, 9 items) and history_goto(end) is then
            // refused; going back to the number of items it holds restores the same state.
            val end = minOf(h.end, h.items.size)
            try {
                engine.historyGoto(0)
                val img = engine.renderPreview(previewMaxSize)
                PhotoLayer(img, 0, 0, img.width.toDouble() / info.width.coerceAtLeast(1))
            } finally {
                engine.historyGoto(end)
            }
        }
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
        val r = lock.withLock {
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
            lock.withLock {
                if (_state.value.info == null) throw EngineException(EngineError.STATE, "no photo open")
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
            lock.withLock {
                if (_state.value.info == null) throw EngineException(EngineError.STATE, "no photo open")
                engine.xmpWrite(path)
            }
        } finally {
            requestRender()
        }
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
    fun goToModule(key: InstanceKey) = _state.update { s ->
        val i = s.layout.groups.indexOfFirst { key.op in it.modules }
        if (i >= 0) s.copy(group = GroupTab.Group(i), expanded = key, search = "", forceShow = null, overlay = null)
        else s.copy(group = GroupTab.Active, forceShow = key, expanded = key, search = "", overlay = null)
    }

    fun toggleExpanded(key: InstanceKey) = _state.update {
        it.copy(expanded = if (it.expanded == key) null else key, overlay = null)
    }

    fun setView(v: BottomView) = _state.update { it.copy(view = v, overlay = null) }
    fun toggleFilmstrip() = _state.update { it.copy(filmstrip = !it.filmstrip) }
    fun setOverlay(o: Overlay?) = _state.update { it.copy(overlay = o) }
    fun clearMessage() = _state.update { it.copy(message = null) }
    fun showMessage(text: String) = _state.update { it.copy(message = text) }

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
        live[key to c.id]?.let { return it }
        return when (val b = c.binding) {
            is Binding.Param -> {
                val m = s.modules.firstOrNull { it.key == key } ?: return null
                (paramValue(m, b.path) as? JsonPrimitive)?.doubleOrNull
            }
            is Binding.Action -> (s.actionValues[key]?.get(b.field) as? JsonPrimitive)?.doubleOrNull
            Binding.None -> c.default
        }
    }

    fun paramValue(m: ModuleInstance, path: String) = m.params.paramAt(path)
}

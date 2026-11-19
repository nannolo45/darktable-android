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
import app.dtandroid.schema.ModuleGroups
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
    val groups: ModuleGroups,
    private val scope: CoroutineScope,
    private val previewMaxSize: Int = 1024,
    private val thumbnailSize: Int = 128,
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
        if (thumbnails) {
            val thumbs = HashMap<Int, app.dtandroid.engine.api.RenderedImage>()
            lock.withLock {
                for ((i, item) in series.items.withIndex()) {
                    try {
                        engine.open(item.rawPath, item.xmpPath.takeIf { File(it).isFile })
                        thumbs[i] = engine.renderPreview(thumbnailSize)
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
        val what = when (p.reason) {
            XmpProblemReason.UNKNOWN_OPERATION -> "the module \"${p.operation}\", which this app does not know"
            XmpProblemReason.NEWER_MODULE_VERSION -> "${p.operation} version ${p.modversion}, newer than this app's version ${p.engineVersion}"
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
                _state.update { it.copy(message = null, beforePreview = null) }
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

    private suspend fun applyDrag(d: Drag) {
        cancelRenders()
        lock.withLock {
            try {
                writeBinding(d.key, d.binding, d.value, d.integer)
                if (d.end) engine.historySeal()
                _state.update { it.copy(message = null, beforePreview = null) }
            } catch (e: EngineException) {
                _state.update { it.copy(message = e.message) }
            }
            try { refreshLocked() } catch (e: EngineException) { _state.update { it.copy(message = e.message) } }
        }
        if (d.end) _live.update { it - (d.key to d.controlId) }
        requestRender()
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
            engine.renderPreview(previewMaxSize)
        }
        val info = _state.value.info ?: return
        _state.update { it.copy(preview = PhotoLayer(img, 0, 0, img.width.toDouble() / info.width), region = null) }
        _state.value.index.let { i -> _state.update { s -> s.copy(thumbnails = s.thumbnails + (i to img)) } }
    }

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
            PhotoLayer(engine.renderRegion(x, y, w, h, r.scale), x, y, r.scale)
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
            val end = engine.history().end
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

    // ------------------------------------------------------------------ interface state

    fun selectGroup(g: GroupTab) = _state.update { it.copy(group = g) }

    /** One module is expanded at a time; tapping the open one closes it. */
    fun toggleExpanded(key: InstanceKey) = _state.update {
        it.copy(expanded = if (it.expanded == key) null else key, overlay = null)
    }

    fun setView(v: BottomView) = _state.update { it.copy(view = v, overlay = null) }
    fun toggleFilmstrip() = _state.update { it.copy(filmstrip = !it.filmstrip) }
    fun setOverlay(o: Overlay?) = _state.update { it.copy(overlay = o) }
    fun clearMessage() = _state.update { it.copy(message = null) }
    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    // ------------------------------------------------------------------ helpers for screens

    /** Modules of the current group, in the order desktop lists them (last in the pipe on top). */
    fun visibleModules(s: DarkroomState): List<ModuleInstance> {
        val listed = s.modules.filter { !it.hidden && schema.modules[it.op]?.hasPanel != false }
        val inGroup = when (val g = s.group) {
            GroupTab.Active -> listed.filter { it.enabled }
            GroupTab.Quick -> emptyList()
            is GroupTab.Configured -> {
                val ops = groups.groups.firstOrNull { it.id == g.id }?.ops?.toSet() ?: emptySet()
                listed.filter { it.op in ops && !it.deprecated }
            }
        }
        return inGroup.asReversed()
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

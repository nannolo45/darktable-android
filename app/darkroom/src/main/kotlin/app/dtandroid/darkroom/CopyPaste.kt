// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.Clipboard
import app.dtandroid.engine.api.CopyItem
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.HistorySelection
import app.dtandroid.engine.api.PasteMode
import app.dtandroid.engine.api.PasteTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Brief P3j: copy and (selective) paste of the history between photos, as darktable's "history stack" module of the
 * lighttable (copy, selective copy…, paste, selective paste…, mode append / overwrite) and its darkroom shortcuts
 * (engine-api.md 9). The engine keeps what was copied; this keeps the sheets, the mode and a paste onto several
 * photos.
 */
class CopyPaste internal constructor(private val c: DarkroomController) {

    /** One line of the copy or paste sheet. */
    data class Line(val item: CopyItem, val checked: Boolean, val reset: Boolean = false)

    /** The "selective copy" ([paste] false) or "selective paste" sheet. */
    data class Sheet(val paste: Boolean, val lines: List<Line>, val moduleOrder: String, val copyModuleOrder: Boolean = true)

    /** A paste onto several photos under way or just finished: for [app.dtandroid.darkroom.ui.BatchProgress]. */
    data class Job(val total: Int, val done: Int = 0, val failed: Int = 0, val fileName: String = "", val step: String? = null,
                   val running: Boolean = true, val message: String? = null) {
        val label: String get() = if (running) "pasting the history · photo ${minOf(done + failed + 1, total)} of $total"
            else message ?: "history pasted onto ${Words.count(done, "photo")}"
        val fraction: Float get() = if (total == 0) 1f else (done + failed).toFloat() / total
    }

    data class State(
        val clipboard: Clipboard? = null,
        /** the remembered mode (desktop's plugins/lighttable/copy_history/pastemode; append by default) */
        val mode: PasteMode = PasteMode.APPEND,
        val sheet: Sheet? = null,
        /** the "paste onto photos…" sheet: the series' photos to choose from, by index */
        val choosing: Set<Int>? = null,
        val job: Job? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Where the mode is remembered between starts (the app sets it); null = for this run only. */
    @Volatile var modeFile: File? = null
        set(value) {
            field = value
            val m = value?.takeIf { it.isFile }?.let { f -> runCatching { PasteMode.valueOf(f.readText().trim()) }.getOrNull() }
            if (m != null) _state.update { it.copy(mode = m) }
        }

    @Volatile private var stop = false

    fun setMode(m: PasteMode) {
        _state.update { it.copy(mode = m) }
        modeFile?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(m.name) } }
    }

    // ------------------------------------------------------------------ copy

    /** "copy": the whole history, without the modules unsafe to copy (desktop's ctrl+c). */
    suspend fun copy() {
        try {
            c.engineRead { it.historyCopy(null) }
            refresh()
            c.showMessage("history copied")
        } catch (e: EngineException) {
            c.showMessage(problem(e))
        }
    }

    /**
     * "selective copy…": the sheet of the photo's items. Brief P3j: the modules a plain copy takes are ticked, those
     * unsafe to copy (white balance, raw black/white point, orientation…) are not; desktop's dialog at the pinned
     * commit starts with none ticked and has "select all" / "select none" (the sheet has both too).
     */
    suspend fun openCopySheet() {
        try {
            val items = c.engineRead { it.historyCopyItems() }
            _state.update { it.copy(sheet = Sheet(false, items.items.map { i -> Line(i, i.safe) }, items.moduleOrder)) }
        } catch (e: EngineException) {
            c.showMessage(problem(e))
        }
    }

    /** "selective paste…": the copied items, ticked as the last copy or paste chose them (all after a plain copy). */
    suspend fun openPasteSheet() {
        val cb = refresh() ?: run { c.showMessage(NOTHING_COPIED); return }
        _state.update { it.copy(sheet = Sheet(true, cb.items.map { i -> Line(i, i.selected ?: true) }, cb.moduleOrder, cb.copyModuleOrder)) }
    }

    fun toggle(num: Int) = _state.update { s ->
        s.copy(sheet = s.sheet?.let { sh -> sh.copy(lines = sh.lines.map { if (it.item.num == num) it.copy(checked = !it.checked) else it }) })
    }

    fun toggleReset(num: Int) = _state.update { s ->
        s.copy(sheet = s.sheet?.let { sh -> sh.copy(lines = sh.lines.map { if (it.item.num == num) it.copy(reset = !it.reset) else it }) })
    }

    fun selectAll(on: Boolean) = _state.update { s -> s.copy(sheet = s.sheet?.let { sh -> sh.copy(lines = sh.lines.map { it.copy(checked = on) }) }) }

    fun toggleModuleOrder() = _state.update { s -> s.copy(sheet = s.sheet?.let { it.copy(copyModuleOrder = !it.copyModuleOrder) }) }

    fun closeSheet() = _state.update { it.copy(sheet = null) }

    /** The sheet's choice as the engine takes it; null when nothing is ticked. */
    fun selectionOf(sheet: Sheet): HistorySelection? {
        val on = sheet.lines.filter { it.checked }
        if (on.isEmpty()) return null
        return HistorySelection(on.map { it.item.num }, on.filter { it.reset }.map { it.item.num }, sheet.copyModuleOrder)
    }

    /** "copy" in the selective copy sheet. */
    suspend fun copySelected() {
        val sh = _state.value.sheet ?: return
        val sel = selectionOf(sh) ?: run { c.showMessage("nothing chosen to copy"); return }
        try {
            c.engineRead { it.historyCopy(sel) }
            closeSheet()
            refresh()
            c.showMessage("${Words.count(sel.items.size, "module")} copied")
        } catch (e: EngineException) {
            c.showMessage(problem(e))
        }
    }

    // ------------------------------------------------------------------ paste onto the open photo

    /** The selective paste sheet's "append" / "overwrite". */
    fun closeSheetAndPaste(mode: PasteMode) { c.launch { paste(mode, fromSheet = true) } }

    /** The selective copy sheet's "copy". */
    fun copySelectedLater() { c.launch { copySelected() } }

    /** "paste" (with the remembered mode), or the selective paste sheet's choice with [mode]. */
    suspend fun paste(mode: PasteMode = _state.value.mode, fromSheet: Boolean = false) {
        val sel = if (fromSheet) (_state.value.sheet?.let { selectionOf(it) } ?: run { c.showMessage("nothing chosen to paste"); return }) else null
        if (_state.value.clipboard == null && refresh() == null) { c.showMessage(NOTHING_COPIED); return }
        if (fromSheet) { setMode(mode); closeSheet() }
        var failed: String? = null
        c.engineChange { e ->
            try {
                e.historyPaste(mode, sel)
            } catch (x: EngineException) {
                failed = problem(x)
            }
        }
        refresh()
        c.showMessage(failed ?: "history pasted (${mode.json})")
    }

    // ------------------------------------------------------------------ paste onto several photos

    /** "paste onto photos…": choose photos of the series (the open one is not offered: paste onto it with "paste"). */
    fun choosePhotos() = _state.update { it.copy(choosing = emptySet()) }

    fun togglePhoto(index: Int) = _state.update { s -> s.copy(choosing = s.choosing?.let { if (index in it) it - index else it + index }) }

    fun closeChoice() = _state.update { it.copy(choosing = null) }

    /** Paste onto the photos chosen in the sheet. */
    fun pasteOntoChosen() {
        val chosen = _state.value.choosing ?: return
        val items = c.state.value.series.items
        closeChoice()
        pasteOnto(chosen.sorted().mapNotNull { items.getOrNull(it) })
    }

    fun stop() { stop = true }

    /**
     * Paste the clipboard onto [items] (photos that may not be open), in the remembered mode and the clipboard's
     * selection: photos from LightPhotoLibrary are fetched first (outside the engine's lock, as P3i's series export),
     * then the darkroom's photo is saved and closed, each XMP is pasted on ([app.dtandroid.engine.api.DarktableEngine.historyPasteFiles])
     * and the photo source is told each photo was edited, so it is marked edited and synced like any edit.
     */
    fun pasteOnto(items: List<SeriesItem>, mode: PasteMode = _state.value.mode): Boolean {
        if (items.isEmpty() || _state.value.job?.running == true) return false
        stop = false
        _state.update { it.copy(job = Job(items.size)) }
        c.launch { runPaste(items, mode) }
        return true
    }

    private suspend fun runPaste(items: List<SeriesItem>, mode: PasteMode) {
        var done = 0
        var failed = 0
        var last: String? = null
        try {
            if (refresh() == null) {
                _state.update { it.copy(job = Job(items.size, running = false, message = NOTHING_COPIED)) }
                return
            }
            val ready = ArrayList<SeriesItem>()
            for (item in items) {
                if (stop) break
                if (!c.needsFetch(item)) { ready += item; continue }
                _state.update { it.copy(job = it.job?.copy(fileName = item.fileName, step = "fetching ${item.fileName}…")) }
                try {
                    ready += c.fetchForExport(item) { text, _ -> _state.update { s -> s.copy(job = s.job?.copy(step = text)) } }
                } catch (e: PhotoSourceException) {
                    failed++
                    last = "${item.fileName}: ${e.message}"
                    _state.update { it.copy(job = it.job?.copy(failed = failed)) }
                }
            }
            val pasted = ArrayList<SeriesItem>()
            if (ready.isNotEmpty() && !stop) {
                c.withPhotoClosed("pasting the history…") { engine ->
                    for (item in ready) {
                        if (stop) break
                        _state.update { it.copy(job = it.job?.copy(fileName = item.fileName, step = "pasting onto ${item.fileName}")) }
                        val r = try {
                            engine.historyPasteFiles(listOf(PasteTarget(item.rawPath, item.xmpPath)), mode).first()
                        } catch (e: EngineException) {
                            app.dtandroid.engine.api.PasteResult(item.rawPath, item.xmpPath, e.error, e.detail)
                        }
                        if (r.error == null) { done++; pasted += item } else {
                            failed++
                            last = "${item.fileName}: " + if (r.error == EngineError.READ_ONLY) READ_ONLY_TARGET else (r.message ?: r.error?.name ?: "failed")
                        }
                        _state.update { it.copy(job = it.job?.copy(done = done, failed = failed)) }
                    }
                }
            }
            // brief P3j deliverable 3: as a photo the darkroom left after saving it
            for (item in pasted) c.source?.left(item)
            // their thumbnails again (only while no photo is open, as every thumbnail pass)
            c.renderThumbnails(pasted) { true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e.message
        }
        val msg = buildString {
            append("history pasted onto ${Words.count(done, "photo")}")
            if (failed > 0) append(" · $failed not pasted")
            if (stop) append(" · stopped")
            last?.let { append(" · ").append(it) }
        }
        _state.update { it.copy(job = Job(items.size, done, failed, running = false, message = msg)) }
    }

    fun dismissJob() = _state.update { it.copy(job = null) }

    /** Read the engine's clipboard again; null = nothing copied. */
    suspend fun refresh(): Clipboard? {
        val cb = runCatching { c.engineRead { it.historyClipboard() } }.getOrNull()
        _state.update { it.copy(clipboard = cb) }
        return cb
    }

    private fun problem(e: EngineException): String = when (e.error) {
        EngineError.READ_ONLY -> "this photo was edited with a newer darktable: its history cannot be copied or changed here"
        EngineError.STATE -> NOTHING_COPIED
        else -> e.detail
    }

    companion object {
        const val NOTHING_COPIED = "nothing copied yet: open a photo and use \"copy\" first"
        const val READ_ONLY_TARGET = "edited with a newer darktable, left unchanged"
    }
}

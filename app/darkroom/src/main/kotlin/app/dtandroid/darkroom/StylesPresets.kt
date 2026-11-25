// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.ItemProblem
import app.dtandroid.engine.api.LayoutStoreException
import app.dtandroid.engine.api.Preset
import app.dtandroid.engine.api.Style
import app.dtandroid.engine.api.StyleMode
import app.dtandroid.schema.layout.DtPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/*
 * Brief P3b: styles and presets in the darkroom, over the engine's calls (engine-api.md section 5.2, 5.3).
 * Presets and styles live in darktable's own database (data.db in the engine's configuration folder); what
 * the app adds is kept in the app's settings ([StyleSettings]): the apply mode, favourite and recently used styles.
 */

/** Brief P3b deliverables 2 and 4: what the app remembers about styles, outside darktable's database. */
data class StylePrefs(
    /** desktop's preference plugins/lighttable/style/applymode (default 0, append), remembered when changed */
    val mode: StyleMode = StyleMode.APPEND,
    /** starred styles (stored names), kept on top of the list */
    val favorites: List<String> = emptyList(),
    /** the last styles applied, newest first */
    val recent: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("apply_mode", mode.json)
        put("favorites", JsonArray(favorites.map { JsonPrimitive(it) }))
        put("recent", JsonArray(recent.map { JsonPrimitive(it) }))
    }

    companion object {
        /** how many recently used styles are shown */
        const val RECENT = 5

        fun fromJson(text: String): StylePrefs {
            val o = Json.parseToJsonElement(text).jsonObject
            fun list(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            val mode = StyleMode.entries.firstOrNull { it.json == o["apply_mode"]?.jsonPrimitive?.content } ?: StyleMode.APPEND
            return StylePrefs(mode, list("favorites"), list("recent").take(RECENT))
        }
    }
}

/** Where [StylePrefs] are kept. */
interface StyleSettings {
    fun load(): StylePrefs
    fun save(p: StylePrefs)
}

/** For tests and the interface preview: kept for the process only. */
class MemoryStyleSettings(private var p: StylePrefs = StylePrefs()) : StyleSettings {
    override fun load() = p
    override fun save(p: StylePrefs) { this.p = p }
}

/** The app's settings file (JSON); an unreadable file counts as no settings. */
class FileStyleSettings(private val file: File) : StyleSettings {
    override fun load(): StylePrefs = try {
        if (file.isFile) StylePrefs.fromJson(file.readText()) else StylePrefs()
    } catch (e: Exception) {
        StylePrefs()
    }

    override fun save(p: StylePrefs) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(p.toJson().toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

/**
 * Names as desktop shows them (`dt_util_localize_segmented_name`, common/utility.c): darktable's own presets carry
 * a `_builtin_` prefix and its shipped styles an `_l10n_` prefix on each segment; `|` separates submenus. The app
 * has no translations, so the prefixes are only dropped.
 */
object DtNames {
    private const val BUILTIN = "_builtin_"
    private const val L10N = "_l10n_"

    fun segments(name: String): List<String> =
        name.removePrefix(BUILTIN).split('|').map { it.trim().removePrefix(L10N) }

    /** The whole name, segments joined as desktop's style list joins them. */
    fun display(name: String) = segments(name).joinToString(" | ")

    /** The last segment: what a row of the folded tree shows. */
    fun leaf(name: String) = segments(name).last()

    /**
     * Brief P4c: the stored name of the preset or style shown as [shown]: the same name, else the one that is shown
     * so. darktable stores its own presets as `_builtin_` + the name (gui/presets.c dt_gui_presets_add_generic), so
     * diffuse or sharpen's "lens deblur | medium" is "_builtin_lens deblur | medium" in the presets table; the engine
     * finds presets by the stored name, as desktop does.
     */
    fun storedName(names: List<String>, shown: String): String? = names.firstOrNull { it == shown } ?: names.firstOrNull { display(it) == shown }

    /** A style darktable ships (share/darktable/styles): imported again at every start when missing. */
    fun shipped(styleName: String) = styleName.startsWith(L10N)
}

/** A row of a folding list of `|`-separated names (styles, or the presets of a module). */
sealed interface TreeRow {
    val depth: Int

    /** A folder: [path] is the stored prefix (segments joined with `|`), [count] the names under it. */
    data class Group(val path: String, val label: String, override val depth: Int, val open: Boolean, val count: Int) : TreeRow

    /** A name: [name] as stored. */
    data class Leaf(val name: String, val label: String, override val depth: Int) : TreeRow
}

object NameTree {
    private class Node {
        val children = LinkedHashMap<String, Node>()
        var name: String? = null
        var count = 0
    }

    /**
     * The rows of [names] as a tree that folds: groups open when their path is in [open] (all of them when
     * [allOpen]). Siblings in name order (case ignored), as desktop's sorted lists.
     */
    fun rows(names: List<String>, open: Set<String>, allOpen: Boolean = false): List<TreeRow> {
        val root = Node()
        for (n in names) {
            val raw = n.split('|')
            var node = root
            node.count++
            for ((i, seg) in raw.withIndex()) {
                val child = node.children.getOrPut(seg) { Node() }
                // a name that is also the folder of other names is shown as a row and as a folder
                if (i == raw.lastIndex) child.name = n
                child.count++
                node = child
            }
        }
        val out = ArrayList<TreeRow>()
        fun walk(node: Node, prefix: List<String>, depth: Int) {
            val sorted = node.children.entries.sortedWith(compareBy({ label(it.key).lowercase() }, { it.key }))
            for ((seg, child) in sorted) {
                val path = prefix + seg
                val p = path.joinToString("|")
                child.name?.let { out += TreeRow.Leaf(it, DtNames.leaf(it), depth) }
                val sub = child.children
                if (sub.isNotEmpty()) {
                    val isOpen = allOpen || p in open
                    out += TreeRow.Group(p, label(seg), depth, isOpen, child.count - (if (child.name != null) 1 else 0))
                    if (isOpen) walk(child, path, depth + 1)
                }
            }
        }
        walk(root, emptyList(), 0)
        return out
    }

    private fun label(seg: String) = seg.trim().removePrefix("_builtin_").removePrefix("_l10n_")
}

/** One line of the import result: the file, what happened, and whether it went in. */
data class ImportLine(val file: String, val text: String, val imported: Boolean)

/** A question while importing: a style or preset of that name exists. */
data class ImportQuestion(
    val style: Boolean,
    val name: String,
    val file: String,
    /** files still waiting for an answer after this one */
    val more: Int,
    internal val answer: CompletableDeferred<Pair<ImportAnswer, Boolean>>,
)

/** desktop's import dialog: "_cancel", "_skip", "_overwrite" (src/libs/styles.c `_import_clicked`). */
enum class ImportAnswer { CANCEL, SKIP, OVERWRITE }

/** What the styles and presets sheets show beyond the list itself. */
sealed interface SpPage {
    data class StyleDetail(val name: String) : SpPage
    data object StyleCreate : SpPage
    data class StyleOverwrite(val name: String, val description: String, val items: List<Int>?) : SpPage
    data class StyleDelete(val name: String) : SpPage
    data class ImportAsk(val q: ImportQuestion) : SpPage
    data class ImportResult(val lines: List<ImportLine>) : SpPage
    data object PresetStore : SpPage
    data class PresetOverwrite(val name: String, val description: String) : SpPage
    data class PresetUpdate(val name: String) : SpPage
    data class PresetEdit(val name: String) : SpPage
    data class PresetRename(val name: String) : SpPage
    data class PresetDelete(val name: String) : SpPage
}

data class StylesPresetsState(
    /** the styles as last read from the engine; null until read (brief P3b: cached, read again after a change) */
    val styles: List<Style>? = null,
    /** how long the last styles() took, ms (shown nowhere; for the tests and the report) */
    val listMs: Long? = null,
    val loading: Boolean = false,
    val search: String = "",
    /** open folders of the style tree */
    val open: Set<String> = emptySet(),
    val prefs: StylePrefs = StylePrefs(),
    /** the presets of [presetsFor] as last read */
    val presets: List<Preset> = emptyList(),
    val presetsFor: InstanceKey? = null,
    val presetsOpen: Set<String> = emptySet(),
    /** the last preset applied per instance (desktop's darktable.gui->last_preset): "update preset" offers it */
    val lastPreset: Map<InstanceKey, String> = emptyMap(),
    val page: SpPage? = null,
    /** the answer of the last action in the sheet (an error, or what was done) */
    val note: String? = null,
)

/**
 * Brief P3b: the logic of the presets and styles sheets. Engine calls go through the controller's lock
 * ([DarkroomController.engineRead], [DarkroomController.engineChange]); changes to the edit are one history item
 * (a preset) or one undo step (a style), as the engine does them.
 */
class StylesPresets internal constructor(private val c: DarkroomController) {
    /** where favourites, recently used styles and the apply mode are kept; the app sets its settings file */
    @Volatile var settings: StyleSettings = MemoryStyleSettings()
        set(v) { field = v; _state.update { it.copy(prefs = v.load()) } }

    private val _state = MutableStateFlow(StylesPresetsState())
    val state: StateFlow<StylesPresetsState> = _state.asStateFlow()

    fun setPage(p: SpPage?) = _state.update { it.copy(page = p, note = null) }
    fun setNote(text: String?) = _state.update { it.copy(note = text) }
    fun setSearch(text: String) = _state.update { it.copy(search = text) }
    fun toggleOpen(path: String) = _state.update { it.copy(open = if (path in it.open) it.open - path else it.open + path) }
    fun togglePresetOpen(path: String) = _state.update { it.copy(presetsOpen = if (path in it.presetsOpen) it.presetsOpen - path else it.presetsOpen + path) }

    /** The sheets were closed: their pages and notes go, the list and its folding stay. */
    fun closed() {
        _state.value.page.let { p -> if (p is SpPage.ImportAsk) p.q.answer.complete(ImportAnswer.CANCEL to true) }
        _state.update { it.copy(page = null, note = null) }
    }

    /** Android's back: a page of the sheet goes back to its list (an import question is cancelled). False = nothing open. */
    fun back(): Boolean {
        val p = _state.value.page ?: return false
        if (p is SpPage.ImportAsk) answer(ImportAnswer.CANCEL, true) else setPage(null)
        return true
    }

    private fun message(e: EngineException) = e.detail

    // ------------------------------------------------------------------ styles

    /** Read the styles in the background when they are not read yet (the darkroom shows them without a wait). */
    suspend fun prefetch() {
        if (_state.value.styles != null) return
        try {
            c.bg("styles list") { e -> readStyles { e.styles() } }
        } catch (e: EngineException) {
            // not started or no engine: read again when the list is opened
        }
    }

    /** Read the styles now (the list is opened and was not read yet, or a change made it stale). */
    suspend fun loadStyles(force: Boolean = false) {
        if (!force && _state.value.styles != null) return
        _state.update { it.copy(loading = true) }
        try {
            c.engineRead { e -> readStyles { e.styles() } }
        } catch (e: EngineException) {
            _state.update { it.copy(note = message(e)) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun readStyles(read: suspend () -> List<Style>) {
        val t0 = System.nanoTime()
        val list = read()
        val ms = (System.nanoTime() - t0) / 1_000_000
        Timeline.mark("styles: ${list.size} read in $ms ms")
        _state.update { s ->
            // favourites and recent styles that no longer exist are dropped
            val names = list.map { it.name }.toHashSet()
            s.copy(styles = list, listMs = ms, prefs = s.prefs.copy(favorites = s.prefs.favorites.filter { it in names }, recent = s.prefs.recent.filter { it in names }))
        }
    }

    fun style(name: String): Style? = _state.value.styles?.firstOrNull { it.name == name }

    fun setMode(mode: StyleMode) = savePrefs { it.copy(mode = mode) }

    fun toggleFavorite(name: String) = savePrefs { p ->
        p.copy(favorites = if (name in p.favorites) p.favorites - name else p.favorites + name)
    }

    private fun savePrefs(f: (StylePrefs) -> StylePrefs) {
        val p = f(_state.value.prefs)
        _state.update { it.copy(prefs = p) }
        runCatching { settings.save(p) }
    }

    /**
     * Apply [name] to the open photo in [mode] (desktop's two modes; one undo step). The mode is remembered
     * and the style goes on top of the recently used ones. Returns true when it was applied.
     */
    suspend fun applyStyle(name: String, mode: StyleMode): Boolean {
        var error: String? = null
        c.engineChange { e ->
            try {
                e.styleApply(name, mode)
            } catch (x: EngineException) {
                error = message(x); throw x
            }
        }
        if (error != null) { _state.update { it.copy(note = error) }; return false }
        savePrefs { p -> p.copy(mode = mode, recent = (listOf(name) + (p.recent - name)).take(StylePrefs.RECENT)) }
        c.showMessage("applied style `${DtNames.display(name)}' on current image")
        _state.update { it.copy(note = "applied in ${if (mode == StyleMode.APPEND) "append" else "overwrite"} mode: undo takes it back in one step") }
        return true
    }

    /** desktop's "remove": the user's own styles (shipped ones come back at the next start). */
    suspend fun deleteStyle(name: String) {
        try {
            c.engineRead { e -> e.styleDelete(name) }
            savePrefs { p -> p.copy(favorites = p.favorites - name, recent = p.recent - name) }
            _state.update { it.copy(page = null, note = "removed style `${DtNames.display(name)}'") }
        } catch (e: EngineException) {
            _state.update { it.copy(note = message(e)) }
        }
        loadStyles(force = true)
    }

    /**
     * "create style from this edit…": [items] are history nums (null = the whole history up to its end). An
     * existing name is asked about first ([SpPage.StyleOverwrite]) unless [overwrite].
     */
    suspend fun createStyle(name: String, description: String, items: List<Int>?, overwrite: Boolean = false): Boolean {
        val n = name.trim()
        if (n.isEmpty()) { _state.update { it.copy(note = "please give style a name") }; return false }
        if (!overwrite && _state.value.styles?.any { it.name == n } == true) {
            _state.update { it.copy(page = SpPage.StyleOverwrite(n, description, items), note = null) }
            return false
        }
        return try {
            c.engineRead { e -> e.styleCreate(n, description, items, overwrite) }
            _state.update { it.copy(page = null, note = "style named '${DtNames.display(n)}' successfully created") }
            loadStyles(force = true)
            true
        } catch (e: EngineException) {
            val text = when {
                e.error == EngineError.READ_ONLY -> "this edit holds history items this app could not load: no style can be made from it"
                e.detail.contains("exists") -> "style `$n' already exists"
                else -> message(e)
            }
            _state.update { it.copy(note = text) }
            false
        }
    }

    // ------------------------------------------------------------------ presets

    /** Read the presets of [key] (its preset list is opened, or changed). */
    suspend fun loadPresets(key: InstanceKey) {
        try {
            val list = c.engineRead { e -> e.presets(key.op, key.priority) }
            _state.update { it.copy(presets = list, presetsFor = key) }
        } catch (e: EngineException) {
            _state.update { it.copy(presets = emptyList(), presetsFor = key, note = message(e)) }
        }
    }

    /** Tap on a preset: as desktop's menu, one history item. Returns true when applied. */
    suspend fun applyPreset(key: InstanceKey, name: String): Boolean {
        var error: String? = null
        c.engineChange { e ->
            try {
                e.presetApply(key.op, key.priority, name)
            } catch (x: EngineException) {
                error = message(x); throw x
            }
        }
        if (error != null) { _state.update { it.copy(note = error) }; loadPresets(key); return false }
        _state.update { it.copy(lastPreset = it.lastPreset + (key to name)) }
        loadPresets(key)
        return true
    }

    /** "store new preset…": an existing name of the user's is asked about first ([SpPage.PresetOverwrite]). */
    suspend fun storePreset(key: InstanceKey, name: String, description: String, overwrite: Boolean = false): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n == NEW_PRESET) { _state.update { it.copy(note = "please give preset a name") }; return false }
        val existing = _state.value.presets.firstOrNull { it.name == n }
        if (existing != null && existing.builtin) { _state.update { it.copy(note = "preset `$n' is write-protected, it cannot be replaced") }; return false }
        if (existing != null && !overwrite) { _state.update { it.copy(page = SpPage.PresetOverwrite(n, description), note = null) }; return false }
        return try {
            c.engineRead { e -> e.presetStore(key.op, key.priority, n, description, overwrite) }
            _state.update { it.copy(page = null, note = "preset `$n' stored", lastPreset = it.lastPreset + (key to n)) }
            loadPresets(key)
            true
        } catch (e: EngineException) {
            _state.update { it.copy(note = message(e)) }
            false
        }
    }

    /** "update preset": the instance's current settings into the user's preset [name] (its description kept). */
    suspend fun updatePreset(key: InstanceKey, name: String): Boolean {
        val description = _state.value.presets.firstOrNull { it.name == name }?.description ?: ""
        return try {
            c.engineRead { e -> e.presetStore(key.op, key.priority, name, description, overwrite = true) }
            _state.update { it.copy(page = null, note = "preset `$name' updated") }
            loadPresets(key)
            true
        } catch (e: EngineException) {
            _state.update { it.copy(note = message(e)) }
            false
        }
    }

    suspend fun renamePreset(key: InstanceKey, name: String, newName: String): Boolean {
        val n = newName.trim()
        if (n.isEmpty()) { _state.update { it.copy(note = "please give preset a name") }; return false }
        return try {
            c.engineRead { e -> e.presetRename(key.op, name, n) }
            _state.update { s -> s.copy(page = null, note = null, lastPreset = s.lastPreset.mapValues { (k, v) -> if (k.op == key.op && v == name) n else v }) }
            loadPresets(key)
            true
        } catch (e: EngineException) {
            _state.update { it.copy(note = if (e.detail.contains("exists")) "preset `$n' already exists" else message(e)) }
            false
        }
    }

    suspend fun deletePreset(key: InstanceKey, name: String) {
        try {
            c.engineRead { e -> e.presetDelete(key.op, name) }
            _state.update { s -> s.copy(page = null, note = null, lastPreset = s.lastPreset.filterNot { (k, v) -> k.op == key.op && v == name }) }
        } catch (e: EngineException) {
            _state.update { it.copy(note = message(e)) }
        }
        loadPresets(key)
    }

    // ------------------------------------------------------------------ import

    /**
     * Brief P3b deliverable 3: import `.dtstyle` and `.dtpreset` files ([files]: the file's name and its bytes, as
     * Android's file picker gives them; copied into [workDir] for the engine). A style or preset whose name exists
     * is asked about ([SpPage.ImportAsk]: overwrite, skip, cancel; "for all"). Ends on [SpPage.ImportResult], one
     * line per file; returns those lines.
     */
    suspend fun import(files: List<Pair<String, ByteArray>>, workDir: File): List<ImportLine> {
        val dir = File(workDir, "import-${System.nanoTime()}").apply { mkdirs() }
        val lines = ArrayList<ImportLine>()
        try {
            val styles = ArrayList<Pair<String, File>>()
            val presets = ArrayList<Pair<String, File>>()
            for ((i, f) in files.withIndex()) {
                val (shown, bytes) = f
                val text = bytes.decodeToString()
                val kind = when {
                    text.contains("<darktable_style") -> 1
                    text.contains("<darktable_preset") -> if (xmlField(text, "operation")?.trim() == LAYOUT_OPERATION) 3 else 2
                    else -> 0
                }
                if (kind == 0) { lines += ImportLine(shown, "not a darktable style or preset file", false); continue }
                if (kind == 3) { lines += importLayout(shown, bytes); continue }
                val safe = shown.map { if (it.isLetterOrDigit() || it in "._- ()") it else '_' }.joinToString("").take(MAX_FILE_NAME)
                val file = File(dir, "$i-$safe").apply { writeBytes(bytes) }
                if (kind == 1) styles += shown to file else presets += shown to file
            }
            val asked = ArrayList<Triple<Boolean, String, File>>() // (style, shown name, file)
            if (styles.isNotEmpty()) {
                val byPath = styles.associate { it.second.path to it.first }
                val r = c.engineRead { e -> e.stylesImport(styles.map { it.second.path }, overwrite = false) }
                for (ok in r.imported) lines += ImportLine(byPath[ok.path] ?: ok.path, importedStyle(ok.name, ok.usable), true)
                for (no in r.refused) {
                    val shown = byPath[no.path] ?: no.path
                    when (no.reason) {
                        "exists" -> asked += Triple(true, shown, File(no.path))
                        "not_a_style" -> lines += ImportLine(shown, "not a darktable style file", false)
                        else -> lines += ImportLine(shown, "could not be read", false)
                    }
                }
            }
            for ((shown, file) in presets) {
                val l = importPreset(shown, file, overwrite = false)
                if (l == null) asked += Triple(false, shown, file) else lines += l
            }
            // names that exist: desktop's question, one file at a time, "apply this option to all"
            var all: ImportAnswer? = null
            for ((i, a) in asked.withIndex()) {
                val (isStyle, shown, file) = a
                val name = xmlField(file.readText(), "name") ?: shown
                val answer = all ?: run {
                    val d = CompletableDeferred<Pair<ImportAnswer, Boolean>>()
                    _state.update { it.copy(page = SpPage.ImportAsk(ImportQuestion(isStyle, name, shown, asked.size - i - 1, d)), note = null) }
                    val (ans, forAll) = d.await()
                    if (forAll || ans == ImportAnswer.CANCEL) all = ans
                    ans
                }
                lines += when (answer) {
                    ImportAnswer.CANCEL -> ImportLine(shown, "not imported: ${what(isStyle)} `${DtNames.display(name)}' exists (import cancelled)", false)
                    ImportAnswer.SKIP -> ImportLine(shown, "skipped: ${what(isStyle)} `${DtNames.display(name)}' exists and was kept", false)
                    ImportAnswer.OVERWRITE -> if (isStyle) {
                        val r = c.engineRead { e -> e.stylesImport(listOf(file.path), overwrite = true) }
                        r.imported.firstOrNull()?.let { ImportLine(shown, importedStyle(it.name, it.usable, replaced = true), true) }
                            ?: ImportLine(shown, "could not be read", false)
                    } else importPreset(shown, file, overwrite = true) ?: ImportLine(shown, "not imported", false)
                }
            }
        } catch (e: EngineException) {
            lines += ImportLine("", message(e), false)
        } finally {
            dir.deleteRecursively()
        }
        // shown in the order of the files picked
        val order = files.map { it.first }
        val sorted = lines.sortedBy { l -> order.indexOf(l.file).let { if (it < 0) Int.MAX_VALUE else it } }
        if (lines.any { it.imported }) loadStyles(force = true)
        val withProblems = sorted.map { l -> describeUnusable(l) }
        _state.update { it.copy(page = SpPage.ImportResult(withProblems), note = null) }
        _state.value.presetsFor?.let { loadPresets(it) }
        return withProblems
    }

    /**
     * A module layout (desktop's preferences → presets → export writes them with the presets, as
     * `modulegroups_<name>.dtpreset`): stored as "manage module layouts → import…" does (a user layout of that name
     * is replaced, as on desktop; a built-in layout's name is refused).
     */
    private suspend fun importLayout(shown: String, bytes: ByteArray): ImportLine = try {
        val (name, text) = DtPreset.readLayout(bytes)
        if (c.layouts.list().any { it.writeProtected && it.name == name }) {
            ImportLine(shown, "refused: \"$name\" is the name of a built-in module layout", false)
        } else {
            c.layouts.write(name, text)
            c.loadLayout()
            ImportLine(shown, "imported module layout `$name' (choose it in the layouts menu next to the module groups)", true)
        }
    } catch (e: DtPreset.DtPresetException) {
        ImportLine(shown, "refused: ${e.message}", false)
    } catch (e: LayoutStoreException) {
        ImportLine(shown, "refused: ${e.message}", false)
    }

    /** Answer the open import question. */
    fun answer(a: ImportAnswer, forAll: Boolean) {
        (_state.value.page as? SpPage.ImportAsk)?.q?.answer?.complete(a to forAll)
    }

    private fun what(style: Boolean) = if (style) "style" else "preset"

    private fun importedStyle(name: String, usable: Boolean, replaced: Boolean = false): String {
        val verb = if (replaced) "replaced" else "imported"
        return if (usable) "$verb style `${DtNames.display(name)}'" else "$verb style `${DtNames.display(name)}'$UNUSABLE_MARK$name"
    }

    /** An imported style the engine cannot apply: say which module stops it (the list was read again). */
    private fun describeUnusable(l: ImportLine): ImportLine {
        val i = l.text.indexOf(UNUSABLE_MARK)
        if (i < 0) return l
        val name = l.text.substring(i + UNUSABLE_MARK.length)
        val why = style(name)?.items?.filter { it.problem != null }?.joinToString("; ") { problemText(it.op, it.moduleVersion, it.problem!!) }
        return l.copy(text = l.text.substring(0, i) + ", but it cannot be applied here: " + (why ?: "it uses modules this app cannot load"))
    }

    /** The engine's refusal reason in words. */
    fun problemText(op: String, version: Int, p: ItemProblem): String {
        val name = c.schema.modules[op]?.name ?: op
        return when (p) {
            ItemProblem.UNKNOWN_OPERATION -> "the module \"$op\" is unknown to this app"
            ItemProblem.NEWER_MODULE_VERSION -> "$name version $version is newer than this app's (${c.schema.modules[op]?.version ?: "?"})"
            ItemProblem.NEWER_BLEND_VERSION -> "$name uses blending settings newer than this app knows"
        }
    }

    /** One `.dtpreset`; null when a preset of that name exists (to be asked about). */
    private suspend fun importPreset(shown: String, file: File, overwrite: Boolean): ImportLine? = try {
        val r = c.engineRead { e -> e.presetImport(file.path, overwrite) }
        val module = c.schema.modules[r.op]?.name ?: r.op
        val verb = if (overwrite) "replaced" else "imported"
        ImportLine(shown, "$verb preset `${DtNames.display(r.name)}' of $module" +
            (if (r.usable) "" else " (stored for an older version of $module: shown greyed out, as on desktop)"), true)
    } catch (e: EngineException) {
        val d = e.detail
        val op = xmlField(file.readText(), "operation")?.trim() ?: "?"
        val ver = xmlField(file.readText(), "op_version")?.trim()?.toIntOrNull() ?: 0
        when {
            e.error == EngineError.ARG && d.trimEnd().removeSuffix(")").endsWith("exists") -> null
            e.error == EngineError.ARG && d.contains(ItemProblem.UNKNOWN_OPERATION.json) -> ImportLine(shown, "refused: ${problemText(op, ver, ItemProblem.UNKNOWN_OPERATION)}", false)
            e.error == EngineError.ARG && d.contains(ItemProblem.NEWER_MODULE_VERSION.json) -> ImportLine(shown, "refused: ${problemText(op, ver, ItemProblem.NEWER_MODULE_VERSION)}", false)
            e.error == EngineError.ARG && d.contains(ItemProblem.NEWER_BLEND_VERSION.json) -> ImportLine(shown, "refused: ${problemText(op, ver, ItemProblem.NEWER_BLEND_VERSION)}", false)
            e.error == EngineError.ARG && d.contains("built-in") -> ImportLine(shown, "refused: darktable has a built-in preset of that name", false)
            e.error == EngineError.IO -> ImportLine(shown, "not a darktable preset file", false)
            else -> ImportLine(shown, "refused: $d", false)
        }
    }

    companion object {
        /** desktop's placeholder name of a new preset, refused as a name (gui/presets.c) */
        const val NEW_PRESET = "new preset"
        private const val LAYOUT_OPERATION = "modulegroups"
        private const val MAX_FILE_NAME = 120
        private const val UNUSABLE_MARK = "\u0000unusable:"

        internal fun xmlField(text: String, tag: String): String? =
            Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
                ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")?.replace("&amp;", "&")
    }
}

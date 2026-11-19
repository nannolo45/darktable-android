// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.LayoutStore
import app.dtandroid.engine.api.LayoutStoreException
import app.dtandroid.engine.api.StoredLayout
import app.dtandroid.schema.UiSchema
import app.dtandroid.schema.layout.BuiltinLayouts
import app.dtandroid.schema.layout.DtPreset
import app.dtandroid.schema.layout.Layout
import app.dtandroid.schema.layout.LayoutCodec
import app.dtandroid.schema.layout.LayoutGroup
import app.dtandroid.schema.layout.QuickAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "manage module layouts" (darktable manual, darkroom → organization; `_manage_show_window`
 * and the `_manage_editor_*` functions in `src/libs/modulegroups.c`), for touch.
 *
 * As on desktop: one layout is edited at a time; changes are kept when another layout is
 * chosen or the editor is closed, and "reset" brings back the layout as it was when it was
 * chosen; built-in layouts are read-only and can only be duplicated; "new" starts from
 * darktable's minimal layout; removing the last group is refused while neither the search
 * line nor the quick access panel is shown, and those two cannot both be switched off without
 * a group. Group and module lists are edited with buttons instead of drag and drop.
 */
class LayoutEditor(
    private val store: LayoutStore,
    private val builtins: BuiltinLayouts,
    val schema: UiSchema,
) {
    /** A question the editor asks in a sheet. */
    sealed interface Dialog {
        /** name for "new", "duplicate" or "rename" (desktop's "new preset name" dialog) */
        data class LayoutName(val action: NameAction, val initial: String) : Dialog
        data class GroupName(val index: Int) : Dialog
        data class GroupIcon(val index: Int) : Dialog
        data class AddModule(val index: Int) : Dialog
        data object AddWidget : Dialog
        data object ConfirmDelete : Dialog
        data object Workflow : Dialog
        data object ChooseLayout : Dialog
    }

    enum class NameAction { NEW, DUPLICATE, RENAME }

    data class State(
        val layouts: List<StoredLayout> = emptyList(),
        val name: String = "",
        val layout: Layout = Layout(),
        /** the layout as it was when chosen: "reset" goes back to it */
        val original: Layout = Layout(),
        val readOnly: Boolean = true,
        val workflow: String = "",
        val dialog: Dialog? = null,
        /** the last message (refusals, import results) */
        val message: String? = null,
    ) {
        val changed get() = layout != original
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val workflows: List<String> get() = builtins.workflows

    /** Open on the current layout, as desktop does. */
    suspend fun open() {
        _state.value = State(workflow = store.workflow())
        load(store.current())
    }

    /** Keep the edits (desktop saves when another layout is chosen and when the window closes). */
    suspend fun save() {
        val s = _state.value
        if (s.readOnly || !s.changed || s.name.isEmpty()) return
        try {
            store.write(s.name, LayoutCodec.write(s.layout))
            _state.update { it.copy(original = it.layout) }
        } catch (e: LayoutStoreException) {
            message(e.message)
        }
    }

    /** Choose another layout to edit (desktop's preset list); the edits of this one are kept first. */
    suspend fun select(name: String) {
        save()
        load(name)
    }

    private suspend fun load(name: String?) {
        val all = store.list()
        val l = all.firstOrNull { it.name == name } ?: all.firstOrNull() ?: return
        val parsed = LayoutCodec.parse(l.params)
        _state.update { it.copy(layouts = all, name = l.name, layout = parsed, original = parsed, readOnly = l.writeProtected, dialog = null) }
    }

    fun ask(d: Dialog?) = _state.update { it.copy(dialog = d, message = null) }
    fun message(text: String?) = _state.update { it.copy(message = text) }

    /** The name a layout dialog starts with: "new", "<name>_1" or the name itself (`_manage_editor_preset_action`). */
    fun defaultName(action: NameAction) = when (action) {
        NameAction.NEW -> "new"
        NameAction.DUPLICATE -> "${_state.value.name}_1"
        NameAction.RENAME -> _state.value.name
    }

    /** Why [name] cannot be used, or null: not empty, not taken (desktop's check), no line break. */
    fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "enter a name"
        _state.value.layouts.any { it.name == name } -> "a preset with this name already exists!"
        '\n' in name -> "a name is one line"
        else -> null
    }

    /** new, duplicate, rename. Returns an error text or null when done. */
    suspend fun applyName(action: NameAction, name: String): String? {
        nameProblem(name)?.let { return it }
        try {
            when (action) {
                NameAction.NEW -> {
                    save()
                    store.write(name, builtins.newLayout(store.workflow()))
                }
                NameAction.DUPLICATE -> store.write(name, LayoutCodec.write(_state.value.layout))
                NameAction.RENAME -> {
                    save()
                    store.rename(_state.value.name, name)
                }
            }
        } catch (e: LayoutStoreException) {
            return e.message
        }
        if (action == NameAction.DUPLICATE) {
            // the duplicated layout keeps the edits; the one it came from keeps them too, as on desktop
            save()
        }
        load(name)
        return null
    }

    /** "remove": user layouts only; afterwards the first layout of the list is edited. */
    suspend fun delete() {
        val s = _state.value
        if (s.readOnly) return
        try {
            store.delete(s.name)
        } catch (e: LayoutStoreException) {
            message(e.message); return
        }
        _state.update { it.copy(original = it.layout) }
        load(null)
    }

    /** "reset": back to the layout as it was when chosen. */
    fun reset() = _state.update { it.copy(layout = it.original) }

    suspend fun setWorkflow(workflow: String) {
        save()
        store.setWorkflow(workflow)
        _state.update { it.copy(workflow = workflow) }
        load(_state.value.name)
    }

    // ------------------------------------------------------------------ options

    private fun edit(f: (Layout) -> Layout?) = _state.update { s ->
        if (s.readOnly) s else f(s.layout)?.let { s.copy(layout = it) } ?: s
    }

    fun setShowSearch(on: Boolean) = edit { l -> if (!on && l.groups.isEmpty() && !l.quickAccessShown) null else l.copy(showSearch = on) }
    fun setQuickAccessShown(on: Boolean) = edit { l -> if (!on && l.groups.isEmpty() && !l.showSearch) null else l.copy(quickAccessShown = on) }
    fun setFullActive(on: Boolean) = edit { it.copy(fullActive = on) }

    // ------------------------------------------------------------------ quick access

    fun addWidget(id: String) = edit { l -> if (id in l.quickAccess) null else l.copy(quickAccess = l.quickAccess + id) }
    fun removeWidget(id: String) = edit { l -> l.copy(quickAccess = l.quickAccess - id) }

    /** The entries as the panel shows them: resolved, in the layout's order. */
    fun quickEntries(l: Layout = _state.value.layout) = l.quickAccess.map { QuickAccess.resolve(schema, it) }

    fun widgetCandidates(): List<QuickAccess.Candidate> = QuickAccess.candidates(schema, builtins.recommendedQuickAccess)

    // ------------------------------------------------------------------ groups

    fun addGroup() = edit { it.copy(groups = it.groups + LayoutGroup("new", "basic", emptyList())) }

    /** Refused for the last group when neither search nor quick access is shown (`_manage_editor_group_remove`). */
    fun canRemoveGroup(l: Layout = _state.value.layout) = !(l.groups.size == 1 && !l.quickAccessShown && !l.showSearch)

    fun removeGroup(i: Int) = edit { l -> if (!canRemoveGroup(l)) null else l.copy(groups = l.groups.filterIndexed { j, _ -> j != i }) }

    /** Group names are stored between darktable's separators, so they cannot contain them. */
    fun groupNameProblem(name: String): String? = when {
        '|' in name || LayoutCodec.BLOCK in name -> "a group name cannot contain \"|\" or \"${LayoutCodec.BLOCK}\""
        '\n' in name -> "a name is one line"
        else -> null
    }

    fun renameGroup(i: Int, name: String): String? {
        groupNameProblem(name)?.let { return it }
        edit { l -> l.copy(groups = l.groups.mapIndexed { j, g -> if (j == i) g.copy(name = name) else g }) }
        return null
    }

    fun setGroupIcon(i: Int, icon: String) = edit { l -> l.copy(groups = l.groups.mapIndexed { j, g -> if (j == i) g.copy(icon = icon) else g }) }

    /** Move a group one place towards the start ([up]) or the end; desktop's left and right arrows. */
    fun moveGroup(i: Int, up: Boolean) = edit { l ->
        val j = if (up) i - 1 else i + 1
        if (i !in l.groups.indices || j !in l.groups.indices) null
        else l.copy(groups = l.groups.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t })
    }

    fun addModule(i: Int, op: String) = edit { l ->
        l.copy(groups = l.groups.mapIndexed { j, g -> if (j == i && op !in g.modules) g.copy(modules = g.modules + op) else g })
    }

    fun removeModule(i: Int, op: String) = edit { l ->
        l.copy(groups = l.groups.mapIndexed { j, g -> if (j == i) g.copy(modules = g.modules - op) else g })
    }

    /** A module "add module" offers. */
    data class ModuleCandidate(val op: String, val name: String, val recommended: Boolean)

    /**
     * What "add module" offers for group [i] (`_manage_module_add_popup`): modules that are not
     * hidden, not deprecated and not yet in the group, by name; "recommended" are those whose
     * default group matches the group's name (base, tone, color, correct, effect, technical,
     * grading, effects).
     */
    fun moduleCandidates(i: Int): List<ModuleCandidate> {
        val g = _state.value.layout.groups.getOrNull(i) ?: return emptyList()
        val bit = mapOf("base" to "basic", "color" to "color", "correct" to "correct", "tone" to "tone",
            "effect" to "effect", "technical" to "technical", "grading" to "grading", "effects" to "effects")[g.name]
        return schema.modules.values
            .filter { it.hasPanel && !it.deprecated && it.op !in g.modules }
            .sortedBy { it.name.lowercase() }
            .map { ModuleCandidate(it.op, it.name, bit != null && bit in it.defaultGroups) }
    }

    /**
     * The modules of group [i] as the editor lists them: desktop lists a group's modules in
     * pipeline order (last in the pipe first) and leaves out modules it does not know; this
     * list keeps them, by their stored name, at the end.
     */
    fun groupModules(i: Int): List<Pair<String, String?>> {
        val g = _state.value.layout.groups.getOrNull(i) ?: return emptyList()
        val known = g.modules.mapNotNull { op -> schema.modules[op]?.let { op to it } }
            .sortedByDescending { pipePosition(it.second.op) }.map { it.first to it.second.name }
        val unknown = g.modules.filter { it !in schema.modules }.map { it to null }
        return known + unknown
    }

    private fun pipePosition(op: String) = schema.modules[op]?.pipePosition ?: 0

    // ------------------------------------------------------------------ files

    /** The file to export the layout being edited as (edits kept first). */
    suspend fun export(): Pair<String, ByteArray> {
        save()
        val s = _state.value
        return DtPreset.fileName(s.name) to DtPreset.writeLayout(s.name, LayoutCodec.write(s.layout))
    }

    /**
     * Import `.dtpreset` files (desktop's preferences → presets → import). A layout replaces a
     * user layout of the same name, as on desktop; one named like a built-in layout is refused.
     * The last imported layout is then edited. Returns one line per file.
     */
    suspend fun import(files: List<Pair<String, ByteArray>>): List<String> {
        save()
        val out = ArrayList<String>()
        var last: String? = null
        val builtinNames = store.list().filter { it.writeProtected }.map { it.name }.toSet()
        for ((fileName, bytes) in files) {
            try {
                val (name, text) = DtPreset.readLayout(bytes)
                if (name in builtinNames) {
                    out += "$fileName: \"$name\" is the name of a built-in layout; rename the layout on the desktop and export it again"
                    continue
                }
                store.write(name, text)
                out += "$fileName: imported \"$name\""
                last = name
            } catch (e: DtPreset.DtPresetException) {
                out += "$fileName: ${e.message}"
            } catch (e: LayoutStoreException) {
                out += "$fileName: ${e.message}"
            }
        }
        load(last ?: _state.value.name)
        message(out.joinToString("\n"))
        return out
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.LayoutEditor
import app.dtandroid.darkroom.LayoutEditor.Dialog
import app.dtandroid.darkroom.LayoutEditor.NameAction
import app.dtandroid.schema.layout.GroupIcons
import app.dtandroid.schema.layout.QuickAccess
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme
import kotlinx.coroutines.launch

/** Picking files and saving one, through Android's storage access framework (the app shell provides it). */
interface LayoutFiles {
    /** Let the user pick one or more `.dtpreset` files; the shell passes them to [LayoutEditor.import]. */
    fun pickImport()

    /** Let the user choose where to save [bytes], suggesting [fileName]. */
    fun export(fileName: String, bytes: ByteArray)
}

/**
 * "manage module layouts" for phone and tablet: the layout chooser and its buttons, the
 * options, the quick access widgets and the groups, as on desktop, in one scrolling column.
 * Choices desktop makes in popups are sheets here; groups move up and down instead of
 * left and right; modules and widgets are picked from searchable lists.
 */
@Composable
fun LayoutEditorScreen(controller: DarkroomController, files: LayoutFiles?, modifier: Modifier = Modifier) {
    val ed = controller.layoutEditor
    val s by ed.state.collectAsState()
    val scope = rememberCoroutineScope()
    val c = DtTheme.colors
    val sp = DtTheme.space
    val ro = s.readOnly
    Box(modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(DtTheme.sizes.sheetHeader).padding(end = sp.panel), verticalAlignment = Alignment.CenterVertically) {
                DtIconButton(DtIcon.Back, "close manage module layouts", c.text, { controller.launch { closeLayoutEditor() } }, iconSize = DtTheme.sizes.iconLarge)
                DtText("manage module layouts", DtTheme.type.title, c.textStrong, Modifier.weight(1f))
            }
            Divider()
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = DtTheme.sizes.formMaxWidth).fillMaxWidth().padding(horizontal = sp.panel, vertical = sp.m)) {
                    // ---- preset
                    Heading("preset")
                    ListControl("layout", s.name, enabled = true) { ed.ask(Dialog.ChooseLayout) }
                    Row(Modifier.fillMaxWidth().padding(vertical = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                        DtTextButton("remove", { ed.ask(Dialog.ConfirmDelete) }, Modifier.weight(1f), enabled = !ro)
                        DtTextButton("duplicate", { ed.ask(Dialog.LayoutName(NameAction.DUPLICATE, ed.defaultName(NameAction.DUPLICATE))) }, Modifier.weight(1f))
                        DtTextButton("rename", { ed.ask(Dialog.LayoutName(NameAction.RENAME, ed.defaultName(NameAction.RENAME))) }, Modifier.weight(1f), enabled = !ro)
                        DtTextButton("new", { ed.ask(Dialog.LayoutName(NameAction.NEW, ed.defaultName(NameAction.NEW))) }, Modifier.weight(1f))
                    }
                    if (files != null) Row(Modifier.fillMaxWidth().padding(vertical = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                        DtTextButton("import…", { files.pickImport() }, Modifier.weight(1f))
                        DtTextButton("export…", { scope.launch { val (n, b) = ed.export(); files.export(n, b) } }, Modifier.weight(1f), enabled = !ro)
                    }
                    if (ro) Note("this is a built-in read-only preset. duplicate it if you want to make changes")
                    s.message?.let { Note(it) }

                    // ---- options
                    ToggleControl("show search line", s.layout.showSearch, !ro) { ed.setShowSearch(!s.layout.showSearch) }
                    ToggleControl("show quick access panel", s.layout.quickAccessShown, !ro) { ed.setQuickAccessShown(!s.layout.quickAccessShown) }
                    ToggleControl("show all history modules in active group", s.layout.fullActive, !ro) { ed.setFullActive(!s.layout.fullActive) }
                    ListControl("workflow", s.workflow, enabled = true) { ed.ask(Dialog.Workflow) }
                    Note("darktable's preference \"auto-apply pixel workflow defaults\" (processing): it chooses the variants of the built-in layouts.")

                    // ---- quick access
                    if (s.layout.quickAccessShown || s.layout.quickAccess.isNotEmpty()) {
                        HeadingWithButton("quick access", if (ro) null else ("add widget to the quick access panel" to { ed.ask(Dialog.AddWidget) }))
                        if (!s.layout.quickAccessShown) Note("the quick access panel is not shown in this layout; its widgets are kept")
                        for (e in ed.quickEntries(s.layout)) {
                            EntryRow(
                                "${e.module?.name ?: e.op} · ${e.label}",
                                if (e.mapped) null else "not available in the app: ${e.reason}",
                                if (ro) null else ({ ed.removeWidget(e.id) }), "remove this widget",
                            )
                        }
                    }

                    // ---- groups
                    HeadingWithButton("module groups", if (ro) null else ("add group" to { ed.addGroup() }))
                    s.layout.groups.forEachIndexed { i, g ->
                        Column(Modifier.fillMaxWidth().padding(top = sp.m).background(c.panel)) {
                            Row(Modifier.fillMaxWidth().background(c.header), verticalAlignment = Alignment.CenterVertically) {
                                DtIconButton(groupIcon(g.icon), "group icon: ${g.icon}", c.textStrong, { ed.ask(Dialog.GroupIcon(i)) }, enabled = !ro, keepTint = true)
                                Box(
                                    Modifier.weight(1f).height(DtTheme.sizes.touch)
                                        .clickable(enabled = !ro, role = Role.Button) { ed.ask(Dialog.GroupName(i)) }
                                        .semantics { contentDescription = "group name ${g.name}" },
                                    contentAlignment = Alignment.CenterStart,
                                ) { DtText(g.name.ifEmpty { "(no name)" }, DtTheme.type.body, c.textStrong) }
                                if (!ro) {
                                    DtIconButton(DtIcon.MoveUp, "move group up", c.text, { ed.moveGroup(i, up = true) }, enabled = i > 0)
                                    DtIconButton(DtIcon.MoveDown, "move group down", c.text, { ed.moveGroup(i, up = false) }, enabled = i < s.layout.groups.lastIndex)
                                    DtIconButton(DtIcon.Remove, "remove group", c.text, { ed.removeGroup(i) }, enabled = ed.canRemoveGroup(s.layout))
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = sp.m)) {
                                for ((op, name) in ed.groupModules(i)) {
                                    EntryRow(name ?: op, if (name == null) "not a module of this darktable version; kept" else null,
                                        if (ro) null else ({ ed.removeModule(i, op) }), "remove this module")
                                }
                                if (!ro) Box(
                                    Modifier.fillMaxWidth().height(DtTheme.sizes.touch).clickable(role = Role.Button) { ed.ask(Dialog.AddModule(i)) },
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        DtIconImage(DtIcon.Add, null, c.text, DtTheme.sizes.icon)
                                        DtText("add module to the group", DtTheme.type.label, c.text, Modifier.padding(start = sp.m))
                                    }
                                }
                            }
                        }
                    }
                    if (!ro) Row(Modifier.fillMaxWidth().padding(top = sp.xxl), horizontalArrangement = Arrangement.End) {
                        DtTextButton("reset", { ed.reset() }, enabled = s.changed)
                    }
                    Spacer(Modifier.height(DtTheme.space.xxxl))
                }
            }
        }
        EditorDialogs(controller, ed, s)
    }
}

@Composable
private fun Heading(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = DtTheme.space.xxl, bottom = DtTheme.space.xs)) {
        DtText(text, DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(bottom = DtTheme.space.xxs))
        Box(Modifier.fillMaxWidth().height(DtTheme.sizes.border).background(DtTheme.colors.textDisabled))
    }
}

@Composable
private fun HeadingWithButton(text: String, button: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(top = DtTheme.space.l), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { Heading(text) }
        if (button != null) DtIconButton(DtIcon.Add, button.first, DtTheme.colors.text, button.second)
    }
}

@Composable
private fun Note(text: String) {
    DtText(text, DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(vertical = DtTheme.space.xs), maxLines = 6)
}

@Composable
private fun EntryRow(label: String, note: String?, onRemove: (() -> Unit)?, removeLabel: String) {
    val c = DtTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.touch), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = DtTheme.space.xs)) {
            DtText(label, DtTheme.type.label, if (note == null) c.text else c.textDisabled, maxLines = 2)
            if (note != null) DtText(note, DtTheme.type.tiny, c.textDisabled, maxLines = 3)
        }
        if (onRemove != null) DtIconButton(DtIcon.Remove, "$removeLabel: $label", c.text, onRemove)
    }
}

// ----------------------------------------------------------------------- dialogs

@Composable
private fun BoxScope.EditorDialogs(controller: DarkroomController, ed: LayoutEditor, s: LayoutEditor.State) {
    val d = s.dialog ?: return
    val close = { ed.ask(null) }
    val maxW = DtTheme.sizes.formMaxWidth
    when (d) {
        Dialog.ChooseLayout -> Sheet("preset", close, maxW) {
            for (l in s.layouts) SheetItem(l.name, enabled = true, selected = l.name == s.name) { controller.launch { ed.select(l.name) } }
        }
        Dialog.Workflow -> Sheet("auto-apply pixel workflow defaults", close, maxW) {
            for (w in ed.workflows) SheetItem(w, enabled = true, selected = w == s.workflow) { controller.launch { ed.setWorkflow(w); ed.ask(null) } }
        }
        Dialog.ConfirmDelete -> Sheet("delete preset?", close, maxW) {
            DtText("do you really want to delete the preset `${s.name}'?", DtTheme.type.body, DtTheme.colors.text,
                Modifier.padding(vertical = DtTheme.space.m), maxLines = 3)
            SheetItem("delete", enabled = true) { controller.launch { ed.delete() } }
            SheetItem("cancel", enabled = true, onClick = close)
        }
        is Dialog.LayoutName -> TextEntrySheet(
            when (d.action) { NameAction.NEW -> "new layout"; NameAction.DUPLICATE -> "duplicate layout"; NameAction.RENAME -> "rename layout" },
            "new preset name", d.initial, number = false, onClose = close,
        ) { text ->
            val problem = ed.nameProblem(text)
            if (problem != null) problem else {
                controller.launch { ed.applyName(d.action, text)?.let { ed.message(it) } }
                null
            }
        }
        is Dialog.GroupName -> TextEntrySheet("group name", "group name", s.layout.groups.getOrNull(d.index)?.name ?: "", number = false, onClose = close) { text ->
            ed.renameGroup(d.index, text) ?: run { ed.ask(null); null }
        }
        is Dialog.GroupIcon -> Sheet("group icon", close, maxW) {
            for (icon in GroupIcons.all) {
                val sel = s.layout.groups.getOrNull(d.index)?.icon == icon
                Row(
                    Modifier.fillMaxWidth().height(DtTheme.sizes.sheetItem).clickable(role = Role.Button) { ed.setGroupIcon(d.index, icon); ed.ask(null) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DtIconImage(groupIcon(icon), null, if (sel) DtTheme.colors.textBright else DtTheme.colors.text, DtTheme.sizes.iconLarge)
                    DtText("$icon icon", DtTheme.type.body, if (sel) DtTheme.colors.textBright else DtTheme.colors.textStrong, Modifier.padding(start = DtTheme.space.xl))
                }
            }
        }
        is Dialog.AddModule -> {
            val all = ed.moduleCandidates(d.index)
            PickerSheet(
                "add module to ${s.layout.groups.getOrNull(d.index)?.name ?: "group"}", close,
                recommended = all.filter { it.recommended }.map { PickerItem(it.op, it.name, false) },
                all = all.map { PickerItem(it.op, it.name, false) },
            ) { ed.addModule(d.index, it); ed.ask(null) }
        }
        Dialog.AddWidget -> {
            val present = s.layout.quickAccess.toSet()
            val all = ed.widgetCandidates()
            PickerSheet(
                "quick access widgets", close,
                recommended = all.filter { it.recommended }.map { PickerItem(it.id, it.label, it.id in present) },
                all = all.map { PickerItem(it.id, it.label, it.id in present) },
            ) { id -> if (id in present) ed.removeWidget(id) else ed.addWidget(id) }
        }
    }
}

private data class PickerItem(val id: String, val label: String, val selected: Boolean)

/**
 * A long list to pick from, with a search field: desktop's popup menus ("add module",
 * "add widget") with their "recommended" part first and "all available modules" after.
 */
@Composable
private fun BoxScope.PickerSheet(title: String, onClose: () -> Unit, recommended: List<PickerItem>, all: List<PickerItem>, onPick: (String) -> Unit) {
    val c = DtTheme.colors
    var query by remember { mutableStateOf("") }
    Sheet(title, onClose, DtTheme.sizes.formMaxWidth, scroll = false) {
        Row(Modifier.fillMaxWidth().height(DtTheme.sizes.touch).background(c.sliderTrack).padding(horizontal = DtTheme.space.l),
            verticalAlignment = Alignment.CenterVertically) {
            DtIconImage(DtIcon.Search, null, c.textMuted, DtTheme.sizes.icon)
            Box(Modifier.weight(1f).padding(start = DtTheme.space.m), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) DtText("search", DtTheme.type.body, c.textMuted)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = DtTheme.type.body.copy(color = c.textBright), cursorBrush = SolidColor(c.textBright),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "search the list" },
                )
            }
        }
        val q = query.trim().lowercase()
        val rec = if (q.isEmpty()) recommended else emptyList()
        val rest = all.filter { q.isEmpty() || it.label.lowercase().contains(q) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = DtTheme.sizes.sheetMaxHeight)) {
            if (rec.isNotEmpty()) {
                item { Heading("recommended") }
                items(rec, key = { "r/" + it.id }) { SheetItem(it.label, enabled = true, selected = it.selected) { onPick(it.id) } }
                item { Heading("all available modules") }
            }
            items(rest, key = { "a/" + it.id }) { SheetItem(it.label, enabled = true, selected = it.selected) { onPick(it.id) } }
            if (rest.isEmpty()) item { Note("nothing matches \"$query\"") }
        }
    }
}

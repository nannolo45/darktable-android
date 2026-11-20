// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.DtNames
import app.dtandroid.darkroom.ImportAnswer
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.NameTree
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.SpPage
import app.dtandroid.darkroom.StylesPresetsState
import app.dtandroid.darkroom.TreeRow
import app.dtandroid.engine.api.Preset
import app.dtandroid.engine.api.Style
import app.dtandroid.engine.api.StyleMode
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P3b: the presets list of a module (desktop's presets menu, gui/presets.c), the styles list (lighttable's
 * styles module and the darkroom's quick styles button: a tree of `|`-separated names), a style's content, and the
 * import of .dtstyle and .dtpreset files. Phone: sheets over the screen. Tablet (deliverable 5): the lists and a
 * style's content take the right panel beside the photo; questions and forms are sheets.
 */

/** Brief P3b: the host's file picker for .dtstyle and .dtpreset files; null where there is none (tests). */
interface ImportFiles {
    /** Let the user pick files; the host passes them to [app.dtandroid.darkroom.StylesPresets.import]. */
    fun pickImport()
}

/** Tablet: whether the right panel shows the presets or styles instead of the module list. */
fun stylesPanelWanted(s: DarkroomState, sp: StylesPresetsState): Boolean = when (s.overlay) {
    Overlay.Styles -> sp.page == null || sp.page is SpPage.StyleDetail
    is Overlay.Presets -> true
    else -> false
}

/** Tablet: the lists in the right panel, beside the photo. */
@Composable
fun StylesPanel(controller: DarkroomController, s: DarkroomState, files: ImportFiles?, modifier: Modifier = Modifier) {
    val sp by controller.stylesPresets.state.collectAsState()
    val close = { controller.setOverlay(null) }
    Column(modifier.fillMaxSize().background(DtTheme.colors.bg)) {
        when (val o = s.overlay) {
            Overlay.Styles -> {
                val d = sp.page as? SpPage.StyleDetail
                if (d != null) {
                    PanelTitle(DtNames.leaf(d.name), onBack = { controller.stylesPresets.setPage(null) }, onClose = close)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = DtTheme.space.panel)) {
                        StyleDetail(controller, s, sp, d.name, tablet = true)
                    }
                } else {
                    PanelTitle("styles", onBack = null, onClose = close)
                    Column(Modifier.weight(1f).padding(horizontal = DtTheme.space.panel)) {
                        StylesList(controller, s, sp, files, Modifier.weight(1f))
                    }
                }
            }
            is Overlay.Presets -> {
                val m = s.modules.firstOrNull { it.op == o.key.op && it.priority == o.key.priority }
                PanelTitle("${m?.let { fullName(it) } ?: ""} · presets", onBack = null, onClose = close)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = DtTheme.space.panel)) {
                    PresetsList(controller, s, sp, o.key, files)
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun PanelTitle(title: String, onBack: (() -> Unit)?, onClose: () -> Unit) {
    val c = DtTheme.colors
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.sheetHeader).background(c.header).padding(end = DtTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) DtIconButton(DtIcon.Back, "back to the list", c.text, onBack) else Box(Modifier.width(DtTheme.space.panel))
        DtText(title, DtTheme.type.title, c.textStrong, Modifier.weight(1f))
        Box(
            Modifier.height(DtTheme.sizes.touch).clickable(role = Role.Button, onClick = onClose).padding(horizontal = DtTheme.space.xl),
            contentAlignment = Alignment.Center,
        ) { DtText("close", DtTheme.type.label, c.text) }
    }
}

/** The overlays of brief P3b (phone: everything; tablet: what the panel does not show). */
@Composable
fun BoxScope.StylesOverlays(controller: DarkroomController, s: DarkroomState, tablet: Boolean, files: ImportFiles?) {
    val sp by controller.stylesPresets.state.collectAsState()
    val o = s.overlay
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    val close = { controller.setOverlay(null) }
    val back = { controller.stylesPresets.setPage(null) }
    // the list is read when it opens (styles: once, then from the cache)
    when (o) {
        Overlay.Styles -> LaunchedEffect(Unit) { controller.stylesPresets.loadStyles() }
        is Overlay.Presets -> LaunchedEffect(o.key, s.history) { controller.stylesPresets.loadPresets(o.key) }
        else -> Unit
    }
    when (val p = sp.page) {
        null -> if (!tablet) when (o) {
            Overlay.Styles -> Sheet("styles", close, maxW, scroll = false) {
                StylesList(controller, s, sp, files, Modifier.heightIn(max = DtTheme.sizes.sheetMaxHeight))
            }
            is Overlay.Presets -> {
                val m = s.modules.firstOrNull { it.op == o.key.op && it.priority == o.key.priority } ?: return
                Sheet("${fullName(m)} · presets", close, maxW) { PresetsList(controller, s, sp, o.key, files) }
            }
            else -> Unit
        }
        is SpPage.StyleDetail -> if (!tablet) Sheet(DtNames.leaf(p.name), back, maxW) { StyleDetail(controller, s, sp, p.name, tablet = false) }
        SpPage.StyleCreate -> StyleCreateSheet(controller, s, sp, back, maxW)
        is SpPage.StyleOverwrite -> Confirm("overwrite style?", "style `${DtNames.display(p.name)}' already exists.\ndo you want to overwrite?", "overwrite", sp.note, back, maxW) {
            controller.launch { stylesPresets.createStyle(p.name, p.description, p.items, overwrite = true) }
        }
        is SpPage.StyleDelete -> Confirm("remove style?", "do you really want to remove the style `${DtNames.display(p.name)}'?", "remove", sp.note, back, maxW) {
            controller.launch { stylesPresets.deleteStyle(p.name) }
        }
        is SpPage.ImportAsk -> ImportAskSheet(controller, p, maxW)
        is SpPage.ImportResult -> Sheet("import", back, maxW) {
            for (l in p.lines) {
                Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.s)) {
                    Column(Modifier.weight(1f)) {
                        DtText(l.file, DtTheme.type.label, DtTheme.colors.textStrong)
                        DtText(l.text, DtTheme.type.small, if (l.imported) DtTheme.colors.text else DtTheme.colors.textMuted, maxLines = 4)
                    }
                }
                Divider()
            }
            SheetItem("done", enabled = true, onClick = back)
        }
        SpPage.PresetStore -> (o as? Overlay.Presets)?.let { pr ->
            FormSheet("store new preset", listOf("name" to "", "description" to ""), "store", sp.note, back, maxW) { v ->
                controller.launch { stylesPresets.storePreset(pr.key, v[0], v[1]) }
            }
        }
        is SpPage.PresetOverwrite -> (o as? Overlay.Presets)?.let { pr ->
            Confirm("overwrite preset?", "preset `${DtNames.display(p.name)}' already exists.\ndo you want to overwrite?", "overwrite", sp.note, back, maxW) {
                controller.launch { stylesPresets.storePreset(pr.key, p.name, p.description, overwrite = true) }
            }
        }
        is SpPage.PresetUpdate -> (o as? Overlay.Presets)?.let { pr ->
            Confirm("update preset?", "do you really want to update the preset `${DtNames.display(p.name)}'?", "update", sp.note, back, maxW) {
                controller.launch { stylesPresets.updatePreset(pr.key, p.name) }
            }
        }
        is SpPage.PresetEdit -> Sheet("edit `${DtNames.display(p.name)}'", back, maxW) {
            SheetItem("rename…", enabled = true) { controller.stylesPresets.setPage(SpPage.PresetRename(p.name)) }
            SheetItem("delete this preset", enabled = true) { controller.stylesPresets.setPage(SpPage.PresetDelete(p.name)) }
        }
        is SpPage.PresetRename -> (o as? Overlay.Presets)?.let { pr ->
            FormSheet("rename `${DtNames.display(p.name)}'", listOf("name" to p.name), "rename", sp.note, back, maxW) { v ->
                controller.launch { stylesPresets.renamePreset(pr.key, p.name, v[0]) }
            }
        }
        is SpPage.PresetDelete -> (o as? Overlay.Presets)?.let { pr ->
            Confirm("delete preset?", "do you really want to delete the preset `${DtNames.display(p.name)}'?", "delete", sp.note, back, maxW) {
                controller.launch { stylesPresets.deletePreset(pr.key, p.name) }
            }
        }
    }
}

// ----------------------------------------------------------------------- presets

/**
 * Desktop's presets menu of a module (gui/presets.c `dt_gui_presets_popup_menu_show_internal`): built-in presets,
 * then the user's; `|` makes folders; presets whose filter does not match the image are left out (desktop's query);
 * a preset stored for another module version is greyed out; the one equal to the current settings is checked.
 * The tail as desktop: the user's active preset gives "edit this preset…" and "delete this preset", otherwise
 * "store new preset…" and "update preset <last applied>".
 */
@Composable
fun PresetsList(controller: DarkroomController, s: DarkroomState, sp: StylesPresetsState, key: InstanceKey, files: ImportFiles?) {
    val list = if (sp.presetsFor == key) sp.presets.filter { it.fits != false } else emptyList()
    val byName = list.associateBy { it.name }
    val active = list.firstOrNull { it.active == true }
    if (s.readOnly) Note("this photo is read-only: presets cannot be applied")
    for (builtin in listOf(true, false)) {
        val names = list.filter { it.builtin == builtin }.map { it.name }
        if (names.isEmpty()) continue
        if (!builtin && list.any { it.builtin }) SheetSection("your presets")
        for (r in NameTree.rows(names, sp.presetsOpen)) when (r) {
            is TreeRow.Group -> FolderRow(r) { controller.stylesPresets.togglePresetOpen(r.path) }
            is TreeRow.Leaf -> {
                val p = byName.getValue(r.name)
                PresetRow(p, r, enabled = p.usable && !s.readOnly,
                    onApply = {
                        controller.launch {
                            if (stylesPresets.applyPreset(key, p.name)) setOverlay(null)
                        }
                    },
                    onEdit = if (p.builtin) null else ({ controller.stylesPresets.setPage(SpPage.PresetEdit(p.name)) }))
            }
        }
    }
    if (sp.presetsFor == key && list.isEmpty()) Note("no presets for this module")
    Box(Modifier.fillMaxWidth().height(DtTheme.space.m))
    if (active != null && !active.builtin) {
        SheetItem("edit this preset…", enabled = true) { controller.stylesPresets.setPage(SpPage.PresetEdit(active.name)) }
        SheetItem("delete this preset", enabled = true) { controller.stylesPresets.setPage(SpPage.PresetDelete(active.name)) }
    } else {
        SheetItem("store new preset…", enabled = true) { controller.stylesPresets.setPage(SpPage.PresetStore) }
        val last = sp.lastPreset[key]?.let { byName[it] }?.takeIf { !it.builtin }
        if (last != null) SheetItem("update preset ${DtNames.display(last.name)}", enabled = true) {
            controller.stylesPresets.setPage(SpPage.PresetUpdate(last.name))
        }
    }
    if (files != null) SheetItem("import styles and presets…", enabled = true) { files.pickImport() }
    sp.note?.let { Note(it) }
}

@Composable
private fun PresetRow(p: Preset, r: TreeRow.Leaf, enabled: Boolean, onApply: () -> Unit, onEdit: (() -> Unit)?) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(DtTheme.sizes.border).background(c.header))
        Row(
            Modifier.fillMaxWidth().height(DtTheme.sizes.sheetItem)
                .clickable(enabled = enabled, role = Role.Button, onClick = onApply)
                .semantics {
                    contentDescription = "preset ${DtNames.display(p.name)}"
                    stateDescription = when { p.active == true -> "current"; !p.usable -> "for another module version"; else -> "" }
                }
                .padding(start = DtTheme.space.xxl * r.depth + DtTheme.space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(DtTheme.sizes.iconLarge)) {
                if (p.active == true) DtIconImage(DtIcon.Check, null, c.textBright, DtTheme.sizes.icon)
            }
            Column(Modifier.weight(1f).padding(start = DtTheme.space.xs)) {
                DtText(r.label, DtTheme.type.body, when {
                    !p.usable -> c.textDisabled
                    p.active == true -> c.textBright
                    !enabled -> c.textDim
                    else -> c.textStrong
                })
                if (p.description.isNotEmpty()) DtText(p.description, DtTheme.type.small, c.textMuted)
            }
            if (onEdit != null) DtIconButton(DtIcon.Edit, "edit preset ${DtNames.display(p.name)}", c.text, onEdit)
        }
    }
}

// ----------------------------------------------------------------------- styles

/**
 * The styles: favourites and recently used on top (brief P3b deliverable 4), then every style as a tree that folds
 * (`|` = folder, as desktop's styles module and quick styles menu), with a search line over names and descriptions.
 * Lazy, from the cache: 534 shipped styles and the user's open without a wait.
 */
@Composable
fun StylesList(controller: DarkroomController, s: DarkroomState, sp: StylesPresetsState, files: ImportFiles?, listModifier: Modifier) {
    val styles = sp.styles
    StylesSearch(controller, sp.search)
    Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.s), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m)) {
        DtTextButton("create style from this edit…", { controller.stylesPresets.setPage(SpPage.StyleCreate) }, Modifier.weight(1f),
            enabled = s.info != null, compact = true)
        if (files != null) DtTextButton("import…", { files.pickImport() }, compact = true)
    }
    sp.note?.let { Note(it) }
    if (styles == null) { Note(if (sp.loading) "reading styles…" else "no styles yet"); return }
    val q = sp.search.trim().lowercase()
    val byName = remember(styles) { styles.associateBy { it.name } }
    val rows = remember(styles, sp.open, q) {
        val names = if (q.isEmpty()) styles.map { it.name }
        else styles.filter { DtNames.display(it.name).lowercase().contains(q) || it.description.lowercase().contains(q) }.map { it.name }
        NameTree.rows(names, sp.open, allOpen = q.isNotEmpty())
    }
    val fav = sp.prefs.favorites.filter { it in byName }
    val recent = sp.prefs.recent.filter { it in byName && it !in fav }
    val open = { name: String -> controller.stylesPresets.setPage(SpPage.StyleDetail(name)) }
    LazyColumn(listModifier.fillMaxWidth().semantics { contentDescription = "styles" }) {
        if (q.isEmpty() && fav.isNotEmpty()) {
            item(key = "h-fav") { SheetSection("favorites") }
            items(fav, key = { "f-$it" }) { n -> StyleRow(byName.getValue(n), DtNames.display(n), 0, true, open) { controller.stylesPresets.toggleFavorite(n) } }
        }
        if (q.isEmpty() && recent.isNotEmpty()) {
            item(key = "h-recent") { SheetSection("recently used") }
            items(recent, key = { "r-$it" }) { n -> StyleRow(byName.getValue(n), DtNames.display(n), 0, false, open) { controller.stylesPresets.toggleFavorite(n) } }
        }
        if (q.isEmpty() && (fav.isNotEmpty() || recent.isNotEmpty())) item(key = "h-all") { SheetSection("all styles · ${styles.size}") }
        items(rows, key = { r -> when (r) { is TreeRow.Group -> "g-${r.path}"; is TreeRow.Leaf -> "l-${r.name}" } }) { r ->
            when (r) {
                is TreeRow.Group -> FolderRow(r) { controller.stylesPresets.toggleOpen(r.path) }
                is TreeRow.Leaf -> StyleRow(byName.getValue(r.name), r.label, r.depth, r.name in sp.prefs.favorites, open) {
                    controller.stylesPresets.toggleFavorite(r.name)
                }
            }
        }
        if (rows.isEmpty()) item(key = "none") { Note(if (q.isEmpty()) "no styles yet" else "no style matches") }
    }
}

@Composable
private fun StylesSearch(controller: DarkroomController, text: String) {
    val c = DtTheme.colors
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.touch).background(c.sliderTrack).padding(start = DtTheme.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DtIconImage(DtIcon.Search, null, c.textMuted, DtTheme.sizes.icon)
        Box(Modifier.weight(1f).padding(horizontal = DtTheme.space.m), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) DtText("filter style names", DtTheme.type.label, c.textMuted)
            BasicTextField(
                value = text,
                onValueChange = { controller.stylesPresets.setSearch(it) },
                singleLine = true,
                textStyle = DtTheme.type.label.copy(color = c.textBright),
                cursorBrush = SolidColor(c.textBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "filter style names" },
            )
        }
        if (text.isNotEmpty()) DtIconButton(DtIcon.Close, "clear text", c.text, onClick = { controller.stylesPresets.setSearch("") })
    }
}

@Composable
private fun FolderRow(r: TreeRow.Group, onToggle: () -> Unit) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(DtTheme.sizes.border).background(c.header))
        Row(
            Modifier.fillMaxWidth().height(DtTheme.sizes.sheetItem).clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = "folder ${r.label}"; stateDescription = if (r.open) "expanded" else "collapsed" }
                .padding(start = DtTheme.space.xxl * r.depth + DtTheme.space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(DtTheme.sizes.iconLarge)) {
                DtIconImage(DtIcon.ChevronDown, null, c.textMuted, DtTheme.sizes.icon, Modifier.rotate(if (r.open) NO_TURN else QUARTER_TURN))
            }
            DtText(r.label, DtTheme.type.body, c.text, Modifier.weight(1f).padding(start = DtTheme.space.xs))
            DtText("${r.count}", DtTheme.type.small, c.textMuted, Modifier.padding(end = DtTheme.space.l))
        }
    }
}

@Composable
private fun StyleRow(st: Style, label: String, depth: Int, favorite: Boolean, onOpen: (String) -> Unit, onStar: () -> Unit) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(DtTheme.sizes.border).background(c.header))
        Row(
            Modifier.fillMaxWidth().height(DtTheme.sizes.sheetItem),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button) { onOpen(st.name) }
                    .semantics { contentDescription = "style ${DtNames.display(st.name)}" }
                    .padding(start = DtTheme.space.xxl * depth + DtTheme.space.xxs + DtTheme.sizes.iconLarge + DtTheme.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    DtText(label, DtTheme.type.body, if (st.usable) c.textStrong else c.textDisabled)
                    if (st.description.isNotEmpty()) DtText(st.description, DtTheme.type.small, c.textMuted)
                }
            }
            DtIconButton(if (favorite) DtIcon.FavoriteOn else DtIcon.Favorite,
                (if (favorite) "remove from favorites " else "add to favorites ") + DtNames.display(st.name),
                if (favorite) c.textBright else c.textMuted, onStar)
        }
    }
}

/** What a style holds (its modules, by display name), and "apply" in desktop's two modes. */
@Composable
fun StyleDetail(controller: DarkroomController, s: DarkroomState, sp: StylesPresetsState, name: String, tablet: Boolean) {
    val c = DtTheme.colors
    val st = sp.styles?.firstOrNull { it.name == name }
    if (st == null) { Note("this style no longer exists"); return }
    val names = s.modules.associate { it.op to it.name }
    DtText(DtNames.display(st.name), DtTheme.type.label, c.textMuted, Modifier.padding(top = DtTheme.space.s), maxLines = 3)
    if (st.description.isNotEmpty()) DtText(st.description, DtTheme.type.body, c.text, Modifier.padding(top = DtTheme.space.s), maxLines = 6)
    SheetSection("modules · ${st.items.size}")
    for (it in st.items.sortedBy { i -> i.num }) {
        val module = controller.schema.modules[it.op]?.name ?: names[it.op] ?: it.op
        Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DtText(if (it.instanceName.isEmpty()) module else "$module ${it.instanceName}", DtTheme.type.body,
                    if (it.problem == null) c.textStrong else c.textDisabled)
                it.problem?.let { p -> DtText(controller.stylesPresets.problemText(it.op, it.moduleVersion, p), DtTheme.type.small, c.textMuted, maxLines = 2) }
            }
            if (!it.enabled) DtText("off", DtTheme.type.small, c.textMuted)
        }
    }
    if (st.hasModuleOrder) DtText("includes a module order", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = DtTheme.space.xs))
    SheetSection("mode")
    val mode = sp.prefs.mode
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m)) {
        for ((m, label) in listOf(StyleMode.APPEND to "append", StyleMode.OVERWRITE to "overwrite")) {
            DtTextButton(label, { controller.stylesPresets.setMode(m) }, Modifier.weight(1f).semantics { stateDescription = if (m == mode) "selected" else "" },
                selected = m == mode)
        }
    }
    DtText(if (mode == StyleMode.APPEND) "the style's modules are added on top of this edit"
        else "this edit is replaced by darktable's default edit plus the style", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = DtTheme.space.xs), maxLines = 2)
    when {
        s.readOnly -> Note("this photo is read-only: styles cannot be applied")
        !st.usable -> Note("this style cannot be applied with this app (see above)")
    }
    DtTextButton("apply", {
        controller.launch {
            // phone: the sheet closes so the photo is seen; tablet: the list stays beside the photo
            if (stylesPresets.applyStyle(name, mode) && !tablet) setOverlay(null)
        }
    }, Modifier.fillMaxWidth().padding(vertical = DtTheme.space.s), enabled = st.usable && !s.readOnly && s.info != null)
    sp.note?.let { Note(it) }
    val fav = name in sp.prefs.favorites
    SheetItem(if (fav) "remove from favorites" else "add to favorites", enabled = true) { controller.stylesPresets.toggleFavorite(name) }
    if (!DtNames.shipped(name)) SheetItem("remove", enabled = true) { controller.stylesPresets.setPage(SpPage.StyleDelete(name)) }
    else Note("shipped with darktable: it cannot be removed (it comes back at the next start)")
}

/** "create style from this edit…": name, description, which history items (desktop's styles dialog). */
@Composable
private fun BoxScope.StyleCreateSheet(controller: DarkroomController, s: DarkroomState, sp: StylesPresetsState, onClose: () -> Unit, maxW: androidx.compose.ui.unit.Dp?) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    val items = s.history.items.take(s.history.end).filter { !it.unknown }
    var chosen by remember { mutableStateOf(items.map { it.num }.toSet()) }
    val names = s.modules.associate { (it.op to it.priority) to it.name }
    Sheet("create new style", onClose, maxW) {
        Field(name, "name", { name = it })
        Field(desc, "description", { desc = it })
        if (items.isEmpty()) Note("can't create style out of unaltered image")
        Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.s), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m)) {
            DtTextButton("select all", { chosen = items.map { it.num }.toSet() }, Modifier.weight(1f), compact = true)
            DtTextButton("select none", { chosen = emptySet() }, Modifier.weight(1f), compact = true)
        }
        for (it in items.reversed()) {
            val base = names[it.op to it.priority] ?: it.name
            val label = "${it.num + 1}  " + (if (it.instanceName.isEmpty()) base else "$base ${it.instanceName}") + (if (it.enabled) "" else " (off)")
            ToggleControl(label, it.num in chosen, enabled = true) { chosen = if (it.num in chosen) chosen - it.num else chosen + it.num }
        }
        sp.note?.let { Note(it) }
        SheetItem("save", enabled = items.isNotEmpty() && chosen.isNotEmpty()) {
            val all = chosen.size == items.size
            controller.launch { stylesPresets.createStyle(name, desc, if (all) null else chosen.sorted()) }
        }
    }
}

@Composable
private fun BoxScope.ImportAskSheet(controller: DarkroomController, p: SpPage.ImportAsk, maxW: androidx.compose.ui.unit.Dp?) {
    var forAll by remember(p.q) { mutableStateOf(false) }
    val q = p.q
    val what = if (q.style) "style" else "preset"
    Sheet("overwrite $what?", { controller.stylesPresets.answer(ImportAnswer.CANCEL, true) }, maxW) {
        DtText("$what `${DtNames.display(q.name)}' already exists.\ndo you want to overwrite existing $what?", DtTheme.type.body, DtTheme.colors.text,
            Modifier.padding(vertical = DtTheme.space.m), maxLines = 6)
        DtText(q.file, DtTheme.type.small, DtTheme.colors.textMuted)
        if (q.more > 0) ToggleControl("apply this option to all existing ${what}s (${q.more} more)", forAll, enabled = true) { forAll = !forAll }
        SheetItem("overwrite", enabled = true) { controller.stylesPresets.answer(ImportAnswer.OVERWRITE, forAll) }
        SheetItem("skip", enabled = true) { controller.stylesPresets.answer(ImportAnswer.SKIP, forAll) }
        SheetItem("cancel", enabled = true) { controller.stylesPresets.answer(ImportAnswer.CANCEL, true) }
    }
}

// ----------------------------------------------------------------------- small parts

@Composable
private fun Note(text: String) {
    DtText(text, DtTheme.type.small, DtTheme.colors.textMuted, Modifier.fillMaxWidth().padding(vertical = DtTheme.space.m), maxLines = 6)
}

@Composable
private fun Field(value: String, hint: String, onChange: (String) -> Unit) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = DtTheme.space.s)) {
        DtText(hint, DtTheme.type.small, c.textMuted, Modifier.padding(bottom = DtTheme.space.xxs))
        Box(
            Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow).background(c.sliderTrack).padding(horizontal = DtTheme.space.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = DtTheme.type.body.copy(color = c.textBright),
                cursorBrush = SolidColor(c.textBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
            )
        }
    }
}

/** A sheet with text fields ([fields]: label to initial value) and one action. */
@Composable
private fun BoxScope.FormSheet(title: String, fields: List<Pair<String, String>>, action: String, note: String?, onClose: () -> Unit,
                               maxW: androidx.compose.ui.unit.Dp?, onAction: (List<String>) -> Unit) {
    var values by remember(title) { mutableStateOf(fields.map { it.second }) }
    Sheet(title, onClose, maxW) {
        for ((i, f) in fields.withIndex()) Field(values[i], f.first, { v -> values = values.toMutableList().also { it[i] = v } })
        note?.let { Note(it) }
        SheetItem(action, enabled = true) { onAction(values) }
    }
}

/** desktop's yes/no dialogs (dt_gui_show_yes_no_dialog): a question, the action, "cancel". */
@Composable
private fun BoxScope.Confirm(title: String, text: String, action: String, note: String?, onClose: () -> Unit, maxW: androidx.compose.ui.unit.Dp?, onYes: () -> Unit) {
    Sheet(title, onClose, maxW) {
        DtText(text, DtTheme.type.body, DtTheme.colors.text, Modifier.padding(vertical = DtTheme.space.m), maxLines = 6)
        note?.let { Note(it) }
        SheetItem(action, enabled = true, onClick = onYes)
        SheetItem("cancel", enabled = true, onClick = onClose)
    }
}

private const val NO_TURN = 0f
private const val QUARTER_TURN = -90f

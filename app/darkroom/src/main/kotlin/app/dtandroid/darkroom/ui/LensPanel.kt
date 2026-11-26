// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.LensCamera
import app.dtandroid.darkroom.LensInfo
import app.dtandroid.darkroom.LensNodes
import app.dtandroid.darkroom.LensPick
import app.dtandroid.darkroom.LensPicker
import app.dtandroid.darkroom.LensState
import app.dtandroid.darkroom.LensValueKind
import app.dtandroid.darkroom.LensValues
import app.dtandroid.darkroom.MakerGroup
import app.dtandroid.darkroom.Overlay
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P6d: lens correction's camera and lens rows, the focal length, aperture and distance lists, and the pickers
 * (desktop: camera_menu_fill, _lens_menu_fill, _lens_set, gui_init of iop/lens.cc), laid out for a phone: a row
 * opens a full-screen list grouped by maker with a search field at the bottom (within the thumb's reach, above the
 * keyboard); "find camera" and "find lens" open the same list with desktop's auto-search result.
 */

/** The rows the schema's nodes stand for: camera model, lens model, and the three values. */
@Composable
fun LensRows(id: Int, ctx: PanelContext) {
    val lens = ctx.controller.lens
    val ui by lens.ui.collectAsState()
    // read again whenever the module's parameters change (a choice here, undo, a paste, another photo)
    LaunchedEffect(ctx.key, ctx.instance.params) { lens.refresh(ctx.key) }
    val st = ui.state?.takeIf { ui.stateKey == ctx.key }
    when (id) {
        LensNodes.CAMERA_ROW -> LensCameraRowView(st, ctx.readOnly,
            onOpen = { lens.open(ctx.key, LensPick.CAMERA, false) }, onFind = { lens.open(ctx.key, LensPick.CAMERA, true) })
        LensNodes.LENS_ROW -> LensLensRowView(st, ctx.readOnly,
            onOpen = { lens.open(ctx.key, LensPick.LENS, false) }, onFind = { lens.open(ctx.key, LensPick.LENS, true) })
        LensNodes.VALUES_ROW -> LensValuesRowView(st, ctx.readOnly) { lens.openValue(ctx.key, it) }
    }
}

@Composable
fun LensCameraRowView(state: LensState?, readOnly: Boolean, onOpen: () -> Unit, onFind: () -> Unit) =
    LensChooserRow("camera model", state?.cameraTitle ?: "…", state?.cameraDetails.orEmpty(), state?.cameraHint, "find camera", readOnly, onOpen, onFind)

@Composable
fun LensLensRowView(state: LensState?, readOnly: Boolean, onOpen: () -> Unit, onFind: () -> Unit) =
    LensChooserRow("lens model", state?.lensTitle ?: "…", state?.lens?.details.orEmpty(), state?.lensHint, "find lens", readOnly, onOpen, onFind)

@Composable
private fun LensChooserRow(
    label: String,
    title: String,
    details: List<String>,
    hint: String?,
    findLabel: String,
    readOnly: Boolean,
    onOpen: () -> Unit,
    onFind: () -> Unit,
) {
    val sp = DtTheme.space
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
        DtText(label, DtTheme.type.label, c.textMuted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.s)) {
            Box(
                Modifier.weight(1f).heightIn(min = DtTheme.sizes.touch)
                    .clickable(enabled = !readOnly, role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.CenterStart,
            ) { DtText(title, DtTheme.type.body, if (hint != null) c.text else c.textStrong, maxLines = 2) }
            DtTextButton(findLabel, onFind, compact = true, enabled = !readOnly)
        }
        if (hint != null) DtText(hint, DtTheme.type.small, c.textMuted, maxLines = 3)
        for (d in details) DtText(d, DtTheme.type.small, c.textMuted, maxLines = 2)
    }
}

@Composable
fun LensValuesRowView(state: LensState?, readOnly: Boolean, onOpen: (LensValueKind) -> Unit) {
    val sp = DtTheme.space
    val c = DtTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        for (kind in LensValueKind.entries) {
            val v = state?.let { when (kind) { LensValueKind.FOCAL -> it.focal; LensValueKind.APERTURE -> it.aperture; LensValueKind.DISTANCE -> it.distance } }
            Column(
                Modifier.weight(1f).heightIn(min = DtTheme.sizes.touch).background(c.raised)
                    .clickable(enabled = !readOnly, role = Role.Button) { onOpen(kind) }
                    .padding(horizontal = sp.s, vertical = sp.xs)
                    .semantics { contentDescription = kind.title },
                verticalArrangement = Arrangement.Center,
            ) {
                DtText(kind.title, DtTheme.type.small, c.textMuted)
                DtText(v?.let { kind.show(it) } ?: "…", DtTheme.type.body, c.textStrong)
            }
        }
    }
}

// ----------------------------------------------------------------------- the sheets and the pickers

/** The overlays of lens correction: the full-screen picker and the value list. */
@Composable
fun BoxScope.LensOverlays(controller: DarkroomController, s: DarkroomState, tablet: Boolean) {
    when (val o = s.overlay) {
        is Overlay.LensPicker -> LensPickerScreen(controller, o, tablet)
        is Overlay.LensValue -> LensValueSheet(controller, s, o, tablet)
        else -> Unit
    }
}

@Composable
private fun BoxScope.LensPickerScreen(controller: DarkroomController, o: Overlay.LensPicker, tablet: Boolean) {
    val lens = controller.lens
    val ui by lens.ui.collectAsState()
    var query by remember(o) { mutableStateOf("") }
    val close = { controller.setOverlay(null) }
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    val st = ui.state
    Box(
        Modifier.matchParentSize().background(DtTheme.colors.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.TopCenter,
    ) {
        var col = Modifier.fillMaxSize()
        if (maxW != null) col = col.widthIn(max = maxW)
        when (o.pick) {
            LensPick.CAMERA -> {
                val groups = remember(ui.cameras, query) { LensPicker.cameras(ui.cameras, query) }
                LensPickerView(
                    title = "camera model", searchLabel = "search cameras", hint = "search maker, model or variant",
                    suggestion = if (ui.found) "suggested for this photo" else null,
                    groups = groups, total = ui.cameras.size, query = query, onQuery = { query = it },
                    loading = ui.loading, error = ui.error, found = ui.found, showAllLabel = "show all cameras",
                    onShowAll = { lens.showAll(o.key, o.pick) },
                    label = { it.label }, sub = { it.mount }, current = { st?.camera == it },
                    onPick = { lens.chooseCamera(o.key, it) }, onClose = close, modifier = col,
                )
            }
            LensPick.LENS -> {
                val groups = remember(ui.lenses, query) { LensPicker.lenses(ui.lenses, query) }
                LensPickerView(
                    title = "lens model", searchLabel = "search lenses", hint = "search maker or model",
                    suggestion = if (ui.found) "suggested for this photo" else st?.camera?.let { "lenses that fit ${it.title}" } ?: "every lens in the database",
                    groups = groups, total = ui.lenses.size, query = query, onQuery = { query = it },
                    loading = ui.loading, error = ui.error, found = ui.found, showAllLabel = "show all lenses",
                    onShowAll = { lens.showAll(o.key, o.pick) },
                    label = { it.model }, sub = { l -> lensSub(l) }, current = { st?.lens?.model == it.model && st.lens.maker == it.maker },
                    onPick = { lens.chooseLens(o.key, it) }, onClose = close, modifier = col,
                )
            }
        }
    }
}

/** the line under a lens in the list: focal range and f-numbers */
fun lensSub(l: LensInfo): String = listOfNotNull(l.focalText, l.apertureText?.let { "f/$it" }).joinToString(" · ")

/**
 * A picker: the list grouped by maker (desktop's submenus) fills the screen, the search field and "show all" sit at the
 * bottom, where a thumb reaches and the keyboard pushes them up with the list shrinking above them. Stateless.
 */
@Composable
fun <T> LensPickerView(
    title: String,
    searchLabel: String,
    hint: String,
    suggestion: String?,
    groups: List<MakerGroup<T>>,
    total: Int,
    query: String,
    onQuery: (String) -> Unit,
    loading: Boolean,
    error: String?,
    found: Boolean,
    showAllLabel: String,
    onShowAll: () -> Unit,
    label: (T) -> String,
    sub: (T) -> String?,
    current: (T) -> Boolean,
    onPick: (T) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = DtTheme.space
    val c = DtTheme.colors
    val shown = groups.sumOf { it.items.size }
    Column(modifier.imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = sp.xs, end = sp.m, top = sp.s, bottom = sp.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
        ) {
            DtIconButton(DtIcon.Back, "Back", c.text, onClose, iconSize = DtTheme.sizes.iconLarge)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
                DtText(title, DtTheme.type.title, c.textStrong)
                val line = when {
                    loading -> "reading the list…"
                    query.isNotBlank() -> "$shown of $total match"
                    else -> listOfNotNull(suggestion, "$total in the list").joinToString(" · ")
                }
                DtText(line, DtTheme.type.small, c.textMuted)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown == 0) {
                Column(Modifier.fillMaxSize().padding(sp.panel), verticalArrangement = Arrangement.spacedBy(sp.s)) {
                    DtText(
                        when {
                            error != null -> error
                            loading -> "reading the list…"
                            query.isNotBlank() -> "Nothing matches \"${query.trim()}\"."
                            found -> "Lensfun finds nothing like the name in the file. Tap \"$showAllLabel\" to search the whole list."
                            else -> "Nothing in the list."
                        },
                        DtTheme.type.body, c.textMuted, maxLines = 4,
                    )
                }
            } else LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "$title list" }) {
                for (g in groups) {
                    item(key = "maker:${g.maker}") {
                        DtText(g.maker.ifBlank { "other" }, DtTheme.type.small, c.textMuted, Modifier.fillMaxWidth().background(c.header).padding(horizontal = sp.panel, vertical = sp.xs))
                    }
                    items(g.items.size, key = { "${g.maker}:$it:${label(g.items[it])}" }) { i ->
                        val item = g.items[i]
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.touch)
                                .clickable(role = Role.Button) { onPick(item) }
                                .padding(horizontal = sp.panel, vertical = sp.xs),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            DtText(label(item), DtTheme.type.body, if (current(item)) c.textBright else c.textStrong, maxLines = 2)
                            sub(item)?.takeIf { it.isNotBlank() }?.let { DtText(it, DtTheme.type.small, c.textMuted) }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(c.panel).padding(horizontal = sp.panel, vertical = sp.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.s),
        ) {
            DtIconImage(DtIcon.Search, null, c.textMuted, DtTheme.sizes.icon)
            Box(Modifier.weight(1f).heightIn(min = DtTheme.sizes.touch), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) DtText(hint, DtTheme.type.label, c.textMuted)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = DtTheme.type.label.copy(color = c.textBright),
                    cursorBrush = SolidColor(c.textBright),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = searchLabel },
                )
            }
            if (query.isNotEmpty()) DtTextButton("clear", { onQuery("") }, compact = true)
            if (found) DtTextButton(showAllLabel, onShowAll, compact = true)
        }
    }
}

/** A value list: the current value first, desktop's table clipped to the lens, and a field to type another value. */
@Composable
private fun BoxScope.LensValueSheet(controller: DarkroomController, s: DarkroomState, o: Overlay.LensValue, tablet: Boolean) {
    val ui by controller.lens.ui.collectAsState()
    val st = ui.state?.takeIf { ui.stateKey == o.key }
    val readOnly = s.readOnly
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    val close = { controller.setOverlay(null) }
    val entries = remember(o.kind, st) {
        when (o.kind) {
            LensValueKind.FOCAL -> LensValues.focalEntries(st?.lens, st?.focal ?: 0.0)
            LensValueKind.APERTURE -> LensValues.apertureEntries(st?.lens, st?.aperture ?: 0.0)
            LensValueKind.DISTANCE -> LensValues.distanceEntries(st?.distance ?: 0.0)
        }
    }
    Sheet("${o.kind.title} (${o.kind.unit})", close, maxW) {
        LensTypedValue(o.kind, readOnly) { controller.lens.setValue(o.key, o.kind, it) }
        for (e in entries) {
            SheetItem(if (e.current) "${e.text}  (current)" else e.text, !readOnly, selected = e.current) {
                controller.lens.setValue(o.key, o.kind, e.value)
            }
        }
    }
}

/** The field for a value that is not in the list (number keyboard); desktop's lists take typed text too. */
@Composable
fun LensTypedValue(kind: LensValueKind, readOnly: Boolean, onSet: (Double) -> Unit) {
    val c = DtTheme.colors
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val submit = {
        val v = LensValues.parseTyped(text)
        if (v == null) error = true else onSet(v)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
        Box(
            Modifier.weight(1f).height(DtTheme.sizes.sliderRow).background(c.sliderTrack).padding(horizontal = DtTheme.space.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) DtText("type a value (${kind.unit})", DtTheme.type.label, c.textMuted)
            BasicTextField(
                value = text,
                onValueChange = { text = it; error = false },
                enabled = !readOnly,
                singleLine = true,
                textStyle = DtTheme.type.body.copy(color = c.textBright),
                cursorBrush = SolidColor(c.textBright),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "type a value" },
            )
        }
        DtTextButton("set", { submit() }, enabled = !readOnly && text.isNotBlank(), compact = true)
    }
    if (error) DtText("Enter a number above 0, for example 17.5.", DtTheme.type.small, c.textStrong, Modifier.padding(bottom = DtTheme.space.xs))
}

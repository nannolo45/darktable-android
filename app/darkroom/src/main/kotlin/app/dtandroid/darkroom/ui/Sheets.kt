// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.HostAction
import app.dtandroid.darkroom.Overlay
import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.schema.ValueFormat
import app.dtandroid.ui.theme.DtTheme
import kotlinx.serialization.json.JsonPrimitive

/** The bottom sheet over the screen: a scrim that closes it, a title, "close", then content. */
@Composable
fun BoxScope.Sheet(title: String, onClose: () -> Unit, maxWidth: androidx.compose.ui.unit.Dp?, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = DtTheme.colors
    Box(
        Modifier.matchParentSize().background(c.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .semantics { contentDescription = "Close menu" },
    )
    var col = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
    if (maxWidth != null) col = col.widthIn(max = maxWidth)
    Column(
        col.clip(RoundedCornerShape(topStart = DtTheme.sizes.radiusSheet, topEnd = DtTheme.sizes.radiusSheet))
            .background(c.panel)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .imePadding()
            .padding(start = DtTheme.space.panel, end = DtTheme.space.panel, top = DtTheme.space.s, bottom = DtTheme.space.xxxl),
    ) {
        Row(Modifier.fillMaxWidth().height(DtTheme.sizes.sheetHeader), verticalAlignment = Alignment.CenterVertically) {
            DtText(title, DtTheme.type.title, c.textStrong, Modifier.weight(1f))
            Box(
                Modifier.height(DtTheme.sizes.touch).clickable(role = Role.Button, onClick = onClose).padding(horizontal = DtTheme.space.xl),
                contentAlignment = Alignment.Center,
            ) { DtText("close", DtTheme.type.label, c.text) }
        }
        if (scroll) Column(Modifier.heightIn(max = DtTheme.sizes.sheetMaxHeight).verticalScroll(rememberScrollState())) {
            content()
        } else Column { content() }
    }
}

/** A section heading inside a list (darktable's combobox sections, brief P2j). */
@Composable
fun SheetSection(label: String) {
    DtText(label, DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(top = DtTheme.space.m, bottom = DtTheme.space.xxs))
}

@Composable
fun SheetItem(label: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(DtTheme.sizes.border).background(c.header))
        Box(
            Modifier.fillMaxWidth().height(DtTheme.sizes.sheetItem).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = DtTheme.space.xxs),
            contentAlignment = Alignment.CenterStart,
        ) {
            DtText(label, DtTheme.type.body, when {
                !enabled -> c.textDisabled
                selected -> c.textBright
                else -> c.textStrong
            })
        }
    }
}

/** Whatever overlay the state asks for. On tablets the instances menu is inline in the list instead. */
@Composable
fun BoxScope.Overlays(controller: DarkroomController, s: DarkroomState, tablet: Boolean, actions: List<HostAction> = emptyList()) {
    val o = s.overlay ?: return
    val close = { controller.setOverlay(null) }
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    when (o) {
        is Overlay.Instances -> if (!tablet) {
            val m = s.modules.firstOrNull { it.op == o.key.op && it.priority == o.key.priority } ?: return
            Sheet("${fullName(m)} · instances", close, maxW) {
                for (it in instanceMenu(controller, s, m)) SheetItem(it.label, it.enabled, onClick = it.run)
            }
        }
        // brief P3b: the presets list and the styles are StylesOverlays' (StylesSheets.kt)
        is Overlay.Presets, Overlay.Styles -> Unit
        // brief P3g: the export screen is drawn over the whole darkroom (ExportScreen.kt)
        Overlay.Export -> Unit
        is Overlay.ListEntries -> {
            val m = s.modules.firstOrNull { it.op == o.key.op && it.priority == o.key.priority } ?: return
            val ms = controller.schema.modules[m.op] ?: return
            val c = ms.control(o.controlId) as? Control.ComboBox ?: return
            // brief P2f: crop's aspect list: darktable's entries in its order; an entry sets ratio and frame
            ms.aspect?.takeIf { c.binding == Binding.Aspect && it.controlId == c.id }?.let { spec ->
                fun int(n: String) = (m.params[n] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: -1
                val cur = app.dtandroid.schema.CropAspect.entryOf(spec, int(spec.ratioD), int(spec.ratioN))
                Sheet("${fullName(m)} · ${c.label ?: ""}", close, maxW) {
                    for ((i, e) in spec.entries.withIndex()) {
                        SheetItem(e.label, !s.readOnly, selected = i == cur) {
                            controller.setOverlay(null)
                            controller.launch { chooseAspect(o.key, i) }
                        }
                    }
                }
                return
            }
            // brief P2j: a computed list: its entries for these parameters; an entry writes what desktop's callback writes
            (ms.computed[c.id] as? app.dtandroid.schema.ComputedList)?.takeIf { c.binding == Binding.Computed }?.let { model ->
                val cur = model.current(m.params)
                Sheet("${fullName(m)} · ${c.label ?: ""}", close, maxW) {
                    for ((i, e) in model.entries(m.params).withIndex()) {
                        if (e.section) { SheetSection(e.label); continue }
                        SheetItem(e.label, e.enabled && !s.readOnly, selected = i == cur) {
                            controller.setOverlay(null)
                            controller.launch { chooseComputed(o.key, c.id, i) }
                        }
                    }
                }
                return
            }
            // brief P3c deliverable 5: a list the engine gives for this photo
            if (c.binding == Binding.ActionList) {
                val model = ms.actionLists[c.id] ?: return
                val entries = model.entries(s.actionLists[o.key to c.id])
                val cur = model.currentIndex(entries)
                Sheet("${fullName(m)} · ${c.label ?: ""}", close, maxW) {
                    for ((i, e) in entries.withIndex()) {
                        SheetItem(model.label(e), !s.readOnly, selected = i == cur) {
                            controller.setOverlay(null)
                            controller.launch { chooseActionList(o.key, c.id, i) }
                        }
                    }
                }
                return
            }
            // brief P3c: a picker setting (exposure's area mode, ...): the app's own value, nothing written to the engine
            (c.binding as? Binding.Setting)?.let { sb ->
                val setting = ms.pickerSettings[c.id] ?: return
                val cur = setting.entryOf(controller.settingValue(s, o.key.op, sb.key))
                Sheet("${fullName(m)} · ${c.label ?: ""}", close, maxW) {
                    for ((i, e) in setting.entries.withIndex()) {
                        val why = setting.unwritable[e.first]
                        SheetItem(if (why != null) "${e.first} ($why)" else e.first, why == null && !s.readOnly, selected = i == cur) {
                            controller.setOverlay(null)
                            controller.launch { chooseSetting(o.key, c.id, i) }
                        }
                    }
                }
                return
            }
            val b = c.binding as? Binding.Param ?: return
            val field = ms.params[b.path]
            val cur = app.dtandroid.schema.ParamRuleInputs(ms, m.params).param(b.path)
            Sheet("${fullName(m)} · ${c.label ?: ""}", close, maxW) {
                for (e in c.entries) {
                    SheetItem(e.label, e.sensitive && !s.readOnly, selected = e.value == cur) {
                        val v: JsonPrimitive? = when (c.paramType) {
                            "enum" -> field?.enumValues?.entries?.firstOrNull { it.value == e.value }?.key?.let { JsonPrimitive(it) }
                            "bool" -> JsonPrimitive(e.value != 0)
                            else -> JsonPrimitive(e.value)
                        }
                        controller.setOverlay(null)
                        if (v != null) controller.launch { setParam(o.key, b.path, v) }
                    }
                }
            }
        }
        is Overlay.NumberEntry -> {
            val ms = controller.schema.modules[o.key.op] ?: return
            val c = ms.control(o.controlId) as? Control.Slider ?: return
            val cur = controller.sliderValue(s, o.key, c, emptyMap()) ?: c.default
            val lo = ValueFormat.numberText(c, minOf(c.hardMin, c.hardMax))
            val hi = ValueFormat.numberText(c, maxOf(c.hardMin, c.hardMax))
            TextEntrySheet(
                title = "${c.label ?: ""}${c.format}",
                hint = "from $lo to $hi${c.format}",
                initial = ValueFormat.numberText(c, cur),
                number = true,
                onClose = close,
            ) { text ->
                val v = ValueFormat.parse(c, text)
                if (v == null) "enter a number from $lo to $hi" else {
                    controller.setOverlay(null)
                    controller.launch { setSlider(o.key, c.binding, v, c.integer, c.id) }
                    null
                }
            }
        }
        Overlay.Layouts -> Sheet("module layouts", close, maxW) {
            // desktop's presets menu next to the module groups: built-in layouts first, then the user's
            for (l in s.layoutList) {
                SheetItem(l.name, enabled = true, selected = l.name == s.layoutName) { controller.launch { chooseLayout(l.name) } }
            }
            SheetItem("manage module layouts…", enabled = true) { controller.launch { openLayoutEditor() } }
        }
        Overlay.HostMenu -> Sheet(s.current?.fileName ?: "photo", close, maxW) {
            // brief P3b: the styles, as desktop's quick styles button in the darkroom
            SheetItem("styles…", enabled = s.info != null) { controller.setOverlay(Overlay.Styles) }
            // brief P3g: the export screen (darktable's export module, with progress)
            SheetItem("export…", enabled = s.info != null) { controller.setOverlay(Overlay.Export) }
            for (a in actions) SheetItem(a.label, a.enabled) { controller.setOverlay(null); a.run() }
        }
        is Overlay.Rename -> {
            val m = s.modules.firstOrNull { it.op == o.key.op && it.priority == o.key.priority } ?: return
            TextEntrySheet("rename ${fullName(m)}", "instance name", m.instanceName, number = false, onClose = close) { text ->
                controller.launch { instanceRename(o.key, text.trim()) }
                null
            }
        }
    }
}

/** A sheet with one text field; [onSet] returns an error text or null when done. */
@Composable
internal fun BoxScope.TextEntrySheet(title: String, hint: String, initial: String, number: Boolean, onClose: () -> Unit, onSet: (String) -> String?) {
    val c = DtTheme.colors
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    Sheet(title, onClose, DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel) {
        Box(
            Modifier.fillMaxWidth().height(DtTheme.sizes.sliderRow).background(c.sliderTrack).padding(horizontal = DtTheme.space.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; error = null },
                singleLine = true,
                textStyle = DtTheme.type.body.copy(color = c.textBright),
                cursorBrush = SolidColor(c.textBright),
                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { error = onSet(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        DtText(error ?: hint, DtTheme.type.small, if (error != null) c.textStrong else c.textMuted, Modifier.padding(vertical = DtTheme.space.s))
        SheetItem("set", enabled = true) { error = onSet(text) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

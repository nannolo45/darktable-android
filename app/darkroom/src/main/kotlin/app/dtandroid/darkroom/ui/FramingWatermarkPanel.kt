// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import app.dtandroid.darkroom.BORDERS_OP
import app.dtandroid.darkroom.ColorTarget
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.FramingWatermark
import app.dtandroid.darkroom.FramingWatermarkNodes
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.Rgb
import app.dtandroid.schema.Rules
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P6e: framing's border and frame line colour rows, watermark's marker list, text, font, colour and its
 * 3 x 3 alignment, built by hand over the schema's nodes (FramingWatermarkNodes), as desktop's gui_init in
 * src/iop/borders.c and src/iop/watermark.c lays them out.
 */

/** The rows the schema's node [id] stands for (framing 11, 15; watermark 0, 18). */
@Composable
fun FramingWatermarkRows(id: Int, ctx: PanelContext) {
    if (ctx.module.op == BORDERS_OP) {
        val t = if (id == FramingWatermarkNodes.BORDER_ROW) FramingWatermarkNodes.BORDER else FramingWatermarkNodes.FRAME
        // desktop greys the frame line colour out while the frame line size is 0 (_frame_line_changed); the schema's rule says so
        val node = ctx.module.control(id)
        val sensitive = node == null || Rules.isSensitive(ctx.module, node, ctx.inputs)
        ModuleColorRow(t, ctx, sensitive)
        return
    }
    when (id) {
        FramingWatermarkNodes.WATERMARK_GRID -> WatermarkGrid(ctx)
        FramingWatermarkNodes.ALIGNMENT_GRID -> AlignmentGrid(ctx)
    }
}

/** A module's colour row: the swatch opens the colour dialog, the picker button picks from the photo. */
@Composable
fun ModuleColorRow(t: ColorTarget, ctx: PanelContext, sensitive: Boolean) {
    val fw = ctx.controller.framing
    val rgb = fw.colorOf(ctx.instance, t)
    ColorRow(t.label, rgb, sensitive && !ctx.readOnly, onOpen = { fw.open(ctx.key, t) }) {
        if (t.pickerControl != null) PickerButton(ctx, t.pickerControl, sensitive)
    }
}

@Composable
private fun WatermarkGrid(ctx: PanelContext) {
    val fw = ctx.controller.framing
    val lists by fw.lists.collectAsState()
    // desktop reads the folders when the panel is built and on its refresh button; here each time the panel opens
    LaunchedEffect(ctx.key) { fw.refresh(ctx.key) }
    val filename = fw.text(ctx.instance, "filename")
    val textOn = FramingWatermark.textApplies(filename) && !ctx.readOnly
    val entry = lists.files.firstOrNull { it.file == filename }
    val c = DtTheme.colors
    ChooserRow("marker", entry?.label ?: filename.ifEmpty { "…" }, !ctx.readOnly) {
        fw.refresh(ctx.key)
        ctx.controller.setOverlay(Overlay.WatermarkFiles(ctx.key))
    }
    if (lists.files.isNotEmpty() && entry == null && filename.isNotEmpty())
        DtText("$filename is not in darktable's watermarks folder on the phone.", DtTheme.type.small, c.textMuted, maxLines = 2)
    WatermarkText(ctx, fw.text(ctx.instance, "text"), textOn)
    ChooserRow("font", fw.text(ctx.instance, "font").ifEmpty { "…" }, textOn) {
        fw.refresh(ctx.key)
        ctx.controller.setOverlay(Overlay.WatermarkFonts(ctx.key))
    }
    ModuleColorRow(FramingWatermarkNodes.WATERMARK_COLOR, ctx, FramingWatermark.textApplies(filename))
    lists.error?.let { DtText(it, DtTheme.type.small, c.textMuted, maxLines = 2) }
}

/** label, the current choice (a tap opens its list) */
@Composable
private fun ChooserRow(label: String, value: String, enabled: Boolean, onOpen: () -> Unit) {
    val c = DtTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.sliderRow)
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = label; stateDescription = value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DtText(label, DtTheme.type.label, if (enabled) c.text else c.textDisabled, Modifier.weight(1f))
        DtText(value, DtTheme.type.value, if (enabled) c.textStrong else c.textDisabled, Modifier.padding(start = DtTheme.space.m), maxLines = 1, middle = true)
    }
}

/** The text of $(WATERMARK_TEXT): written when the keyboard's done key is pressed or the field is left (one history item). */
@Composable
private fun WatermarkText(ctx: PanelContext, current: String, enabled: Boolean) {
    val c = DtTheme.colors
    var text by remember(ctx.key, current) { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    val commit = { if (text != current) ctx.controller.framing.setText(ctx.key, "text", text.take(511)) }
    Row(Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.sliderRow), verticalAlignment = Alignment.CenterVertically) {
        DtText("text", DtTheme.type.label, if (enabled) c.text else c.textDisabled, Modifier.padding(end = DtTheme.space.m))
        Box(
            Modifier.weight(1f).height(DtTheme.sizes.sliderRow).background(c.sliderTrack).padding(horizontal = DtTheme.space.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) DtText("text for \$(WATERMARK_TEXT)", DtTheme.type.label, c.textMuted)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                singleLine = true,
                textStyle = DtTheme.type.body.copy(color = if (enabled) c.textBright else c.textDisabled),
                cursorBrush = SolidColor(c.textBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { f -> if (focused && !f.isFocused) commit(); focused = f.isFocused }
                    .semantics { contentDescription = "watermark text" },
            )
        }
    }
}

/** desktop's 3 x 3 alignment toggles: the cell's place is the alignment, the chosen one is highlighted */
@Composable
private fun AlignmentGrid(ctx: PanelContext) {
    val c = DtTheme.colors
    val fw = ctx.controller.framing
    val cur = fw.alignment(ctx.instance)
    Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs), verticalAlignment = Alignment.CenterVertically) {
        DtText("alignment", DtTheme.type.label, if (ctx.readOnly) c.textDisabled else c.text, Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(DtTheme.sizes.border)) {
            for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(DtTheme.sizes.border)) {
                for (col in 0..2) {
                    val i = row * 3 + col
                    val on = i == cur
                    Box(
                        Modifier.size(DtTheme.sizes.touch).background(if (on) c.selected else c.header)
                            .clickable(enabled = !ctx.readOnly, role = Role.Button) { fw.setAlignment(ctx.key, i) }
                            .semantics {
                                contentDescription = "alignment ${FramingWatermarkNodes.ALIGNMENT_NAMES[i]}"
                                stateDescription = if (on) "on" else "off"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        DtIconImage(DtIcon.AlignMark, null, if (on) c.textBright else c.textMuted, DtTheme.sizes.icon)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------- overlays

/** The colour dialog and watermark's two lists. */
@Composable
fun BoxScope.FramingWatermarkOverlays(controller: DarkroomController, s: DarkroomState, tablet: Boolean) {
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    when (val o = s.overlay) {
        is Overlay.ColorDialog -> {
            val fw = controller.framing
            val m = s.modules.firstOrNull { it.op == o.key.op && it.priority == o.key.priority } ?: return
            val initial = fw.colorOf(m, o.target) ?: Rgb(0.0, 0.0, 0.0)
            val recent by fw.recent.collectAsState()
            ColorDialogSheet(o.target.title, initial, recent, !s.readOnly, maxW,
                onOk = { fw.apply(o.key, o.target, it) }, onCancel = { fw.cancel() })
        }
        is Overlay.WatermarkFiles -> {
            val fw = controller.framing
            val lists by fw.lists.collectAsState()
            val m = s.modules.firstOrNull { it.op == o.key.op && it.priority == o.key.priority } ?: return
            val cur = fw.text(m, "filename")
            Sheet("marker", { controller.setOverlay(null) }, maxW) {
                if (lists.loading && lists.files.isEmpty()) DtText("reading the watermarks folder…", DtTheme.type.small, DtTheme.colors.textMuted)
                for (f in lists.files) SheetItem(f.label, !s.readOnly, selected = f.file == cur) {
                    controller.setOverlay(null)
                    fw.setText(o.key, "filename", f.file)
                }
            }
        }
        is Overlay.WatermarkFonts -> {
            val fw = controller.framing
            val lists by fw.lists.collectAsState()
            val m = s.modules.firstOrNull { it.op == o.key.op && it.priority == o.key.priority } ?: return
            val cur = fw.text(m, "font")
            Sheet("font", { controller.setOverlay(null) }, maxW) {
                if (!lists.loading && lists.fonts.isEmpty())
                    DtText("The engine finds no fonts that can draw text.", DtTheme.type.small, DtTheme.colors.textStrong, maxLines = 2)
                if (cur.isNotEmpty() && lists.fonts.none { it.font == cur })
                    DtText("current: $cur (not on the phone; another font draws the text)", DtTheme.type.small, DtTheme.colors.textMuted, maxLines = 2)
                for ((family, faces) in lists.fonts.groupBy { it.family }) {
                    SheetSection(family)
                    for (f in faces) SheetItem(if (faces.size == 1) family else "$family ${f.face}", !s.readOnly, selected = f.font == cur) {
                        controller.setOverlay(null)
                        fw.setText(o.key, "font", f.font)
                    }
                }
            }
        }
        else -> Unit
    }
}

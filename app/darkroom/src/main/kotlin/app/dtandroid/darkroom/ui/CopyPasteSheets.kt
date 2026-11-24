// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.dtandroid.darkroom.CopyPaste
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.engine.api.PasteMode
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P3j: copy and paste of the history (ui-spec.md "copy and paste of the history"), as darktable's "history
 * stack" module of the lighttable: copy, selective copy…, paste, selective paste…, mode (append / overwrite), and
 * "paste onto photos…" for photos of the series that are not open.
 */

/** The entries of the photo menu (darktable and modern skins) and of l1's `more` tool. [onDone] closes the menu. */
@Composable
fun ColumnScope.CopyPasteItems(controller: DarkroomController, s: DarkroomState, onDone: () -> Unit) {
    val cp by controller.copyPaste.state.collectAsState()
    LaunchedEffect(Unit) { controller.copyPaste.refresh() }
    val writable = s.info != null && s.readOnlyReason == null
    val has = cp.clipboard != null
    val p = controller.copyPaste
    SheetItem("copy history", enabled = writable) { onDone(); controller.launch { p.copy() } }
    SheetItem("selective copy…", enabled = writable) { onDone(); controller.launch { p.openCopySheet() } }
    SheetItem("paste history", enabled = writable && has) { onDone(); controller.launch { p.paste() } }
    SheetItem("selective paste…", enabled = writable && has) { onDone(); controller.launch { p.openPasteSheet() } }
    SheetItem("paste onto photos…", enabled = has && s.series.items.size > 1 && cp.job?.running != true) { onDone(); p.choosePhotos() }
    SheetItem("paste mode · ${cp.mode.json}", enabled = true) {
        p.setMode(if (cp.mode == PasteMode.APPEND) PasteMode.OVERWRITE else PasteMode.APPEND)
    }
}

/** The sheets: selective copy / paste, the photos to paste onto, and the progress of a paste onto several photos. */
@Composable
fun BoxScope.CopyPasteOverlays(controller: DarkroomController, s: DarkroomState, tablet: Boolean) {
    val cp by controller.copyPaste.state.collectAsState()
    val p = controller.copyPaste
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    cp.job?.let { j ->
        BatchProgress(j.label, if (j.running) j.fraction else null, Modifier.align(androidx.compose.ui.Alignment.TopCenter),
            step = if (j.running) j.step else null,
            onCancel = if (j.running) ({ p.stop() }) else ({ p.dismissJob() }), cancelLabel = if (j.running) "stop" else "close")
    }
    val sheet = cp.sheet
    if (sheet != null) CopySheet(p, sheet, maxW)
    else cp.choosing?.let { chosen -> ChoosePhotos(p, s, chosen, maxW) }
}

@Composable
private fun BoxScope.CopySheet(p: CopyPaste, sheet: CopyPaste.Sheet, maxW: androidx.compose.ui.unit.Dp?) {
    val c = DtTheme.colors
    Sheet(if (sheet.paste) "selective paste" else "selective copy", { p.closeSheet() }, maxW) {
        Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xs), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
            DtTextButton("select all", { p.selectAll(true) })
            DtTextButton("select none", { p.selectAll(false) })
        }
        for (l in sheet.lines) {
            val extras = listOfNotNull("off".takeIf { !l.item.enabled }, "mask".takeIf { l.item.mask }, "unsafe to copy".takeIf { !l.item.safe })
            val label = l.item.name + if (extras.isEmpty()) "" else " · " + extras.joinToString(" · ")
            ToggleControl(label, l.checked, enabled = true) { p.toggle(l.item.num) }
            // desktop's "reset" column: paste the module with its default parameters
            if (l.checked) ToggleControl("    reset its parameters", l.reset, enabled = true) { p.toggleReset(l.item.num) }
        }
        if (!sheet.paste) ToggleControl("module order (${sheet.moduleOrder})", sheet.copyModuleOrder, enabled = true) { p.toggleModuleOrder() }
        if (sheet.lines.isEmpty()) DtText("this photo's history is empty", DtTheme.type.label, c.textMuted)
        val any = sheet.lines.any { it.checked }
        Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.s), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
            DtTextButton("cancel", { p.closeSheet() })
            if (sheet.paste) {
                DtTextButton("overwrite", { p.closeSheetAndPaste(PasteMode.OVERWRITE) }, enabled = any)
                DtTextButton("append", { p.closeSheetAndPaste(PasteMode.APPEND) }, enabled = any)
            } else {
                DtTextButton("copy", { p.copySelectedLater() }, enabled = any)
            }
        }
    }
}

@Composable
private fun BoxScope.ChoosePhotos(p: CopyPaste, s: DarkroomState, chosen: Set<Int>, maxW: androidx.compose.ui.unit.Dp?) {
    val cp by p.state.collectAsState()
    Sheet("paste onto photos", { p.closeChoice() }, maxW) {
        DtText("mode: ${cp.mode.json}", DtTheme.type.label, DtTheme.colors.textMuted)
        for ((i, item) in s.series.items.withIndex()) {
            val open = i == s.index && s.info != null
            ToggleControl(if (open) "${item.fileName} · open: use \"paste history\"" else item.fileName, i in chosen, enabled = !open) { p.togglePhoto(i) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = DtTheme.space.s), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
            DtTextButton("cancel", { p.closeChoice() })
            DtTextButton("paste onto ${chosen.size}", { p.pasteOntoChosen() }, enabled = chosen.isNotEmpty())
        }
    }
}

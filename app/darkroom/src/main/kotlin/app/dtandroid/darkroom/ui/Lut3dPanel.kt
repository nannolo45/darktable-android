// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.dtandroid.darkroom.Lut3dUi
import app.dtandroid.lpl.LutFile
import app.dtandroid.ui.theme.DtTheme
import kotlinx.serialization.json.JsonPrimitive

/*
 * Brief P6b: lut 3D's file row (desktop: a file button and a list of the folder's files, iop/lut3d.c gui_init), with
 * the chooser of the LUT folder's tree opened in place. The rest of the module (application color space,
 * interpolation) stays generated from the schema.
 */

/** The relative path lut 3D's parameters hold ("" = none). */
fun lut3dPath(params: kotlinx.serialization.json.JsonObject): String =
    (params["filepath"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

@Composable
fun Lut3dFileRow(ctx: PanelContext) {
    val files = ctx.controller.lut3d
    val ui by files.ui.collectAsState()
    val path = lut3dPath(ctx.instance.params)
    @Suppress("UNUSED_VARIABLE") val tick = ui.tick  // read again when the folder changed
    Lut3dFileRowView(
        path = path,
        exists = path.isEmpty() || files.exists(path),
        onServer = path.isNotEmpty() && files.onServer(path),
        ui = ui,
        open = ui.open == ctx.key,
        readOnly = ctx.readOnly,
        onOpen = { files.open(ctx.key, path) },
        onClose = { files.close() },
        onEnter = { files.enter(it) },
        onUp = { files.up() },
        onChoose = { files.choose(it) },
        onAdd = { files.addFromPhone() },
        onFetch = { files.fetch(path) },
        onReplace = { files.answerReplace(it) },
    )
}

/** Stateless, for screenshot and Robolectric tests. */
@Composable
fun Lut3dFileRowView(
    path: String,
    exists: Boolean,
    onServer: Boolean,
    ui: Lut3dUi,
    open: Boolean,
    readOnly: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onEnter: (String) -> Unit,
    onUp: () -> Unit,
    onChoose: (String) -> Unit,
    onAdd: () -> Unit,
    onFetch: () -> Unit,
    onReplace: (Boolean) -> Unit,
) {
    val sp = DtTheme.space
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.s)) {
            DtText("LUT file", DtTheme.type.label, c.text)
            DtText(path.ifEmpty { "none" }, DtTheme.type.body, if (exists) c.textStrong else c.textMuted, Modifier.weight(1f), middle = true)
            if (!readOnly) DtTextButton(if (open) "close" else "choose…", { if (open) onClose() else onOpen() }, compact = true)
        }
        if (!exists) {
            // desktop's list shows "INVALID >> <path>" and the module passes the picture through unchanged
            DtText("LUT file not on this phone: $path", DtTheme.type.label, c.textBright, maxLines = 3)
            DtText("The module leaves the picture unchanged until the file is here, as darktable does.", DtTheme.type.label, c.textMuted, maxLines = 3)
            if (onServer && !readOnly) DtTextButton(if (ui.busy) "getting…" else "Get from LightPhotoLibrary", onFetch, enabled = !ui.busy)
        }
        ui.message?.let { DtText(it, DtTheme.type.label, c.textMuted, maxLines = 4) }
        ui.askReplace.firstOrNull()?.let { (name, sub) ->
            DtText("${if (sub.isEmpty()) "" else "$sub/"}$name is already in the LUT folder. Replace it?", DtTheme.type.label, c.textStrong, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                DtTextButton("replace", { onReplace(true) }, compact = true)
                DtTextButton("keep the one here", { onReplace(false) }, compact = true)
            }
        }
        if (open) Lut3dChooser(ui, onEnter, onUp, onChoose, onAdd)
    }
}

@Composable
private fun Lut3dChooser(ui: Lut3dUi, onEnter: (String) -> Unit, onUp: () -> Unit, onChoose: (String) -> Unit, onAdd: () -> Unit) {
    val sp = DtTheme.space
    val c = DtTheme.colors
    val lv = ui.level
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        Divider()
        DtText("LUT folder${if (lv.dir.isEmpty()) "" else " / ${lv.dir}"}", DtTheme.type.label, c.textMuted, middle = true)
        if (lv.dir.isNotEmpty()) ChooserLine("..  up one folder", c.text, enabled = true, onClick = onUp)
        for (d in lv.folders) ChooserLine(d.substringAfterLast('/') + "/", c.textStrong, enabled = true) { onEnter(d) }
        for (f in lv.files) FileLine(f, onChoose)
        if (lv.folders.isEmpty() && lv.files.isEmpty())
            DtText("No LUT files here yet. Add .cube, .3dl or .png (Hald CLUT) files from the phone, or from your PCs through LightPhotoLibrary.",
                DtTheme.type.label, c.textMuted, maxLines = 4)
        DtTextButton("Add from phone…", onAdd, Modifier.fillMaxWidth())
        Divider()
    }
}

@Composable
private fun FileLine(f: LutFile, onChoose: (String) -> Unit) {
    if (f.usable) ChooserLine(f.name, DtTheme.colors.textStrong, enabled = true) { onChoose(f.path) }
    else Column(Modifier.fillMaxWidth()) {
        ChooserLine(f.name, DtTheme.colors.textDisabled, enabled = false) {}
        DtText(f.reason ?: "", DtTheme.type.label, DtTheme.colors.textDisabled, Modifier.padding(start = DtTheme.space.m), maxLines = 2)
    }
}

@Composable
private fun ChooserLine(text: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.touch).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DtTheme.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) { DtText(text, DtTheme.type.body, color, Modifier.weight(1f), middle = true) }
}

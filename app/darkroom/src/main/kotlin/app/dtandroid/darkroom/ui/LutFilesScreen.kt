// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.dtandroid.lpl.LutConflict
import app.dtandroid.lpl.LutFile
import app.dtandroid.lpl.LutSyncState
import app.dtandroid.ui.theme.DtTheme
import java.util.Locale

/** What Settings › LUT files shows (brief P6b). [states]: per path, as LightPhotoLibrary sees it; empty = no server. */
data class LutFilesUi(
    val files: List<LutFile> = emptyList(),
    val totalBytes: Long = 0,
    val states: Map<String, LutSyncState> = emptyMap(),
    val conflicts: List<LutConflict> = emptyList(),
    /** a server lists `luts` */
    val synced: Boolean = false,
    val autoSync: Boolean = true,
    /** P6c: "Download LUT files from LightPhotoLibrary automatically" (off by default) */
    val autoDownload: Boolean = false,
    /** P6c: files LightPhotoLibrary has and the phone lacks (path, bytes), not removed here, not .gmz */
    val missing: List<Pair<String, Long>> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
)

private fun mb(b: Long) = String.format(Locale.ROOT, "%.1f MB", b / 1e6)

/**
 * Settings › LUT files: count and size, each file with its sync state and "Remove" (asks first; the phone only), the
 * conflicts with mine / theirs / keep both (as R11's dialog), "Add from phone…" and "sync now". Stateless but for
 * the question "remove?".
 */
@Composable
fun LutFilesScreen(
    ui: LutFilesUi,
    onRemove: (String) -> Unit,
    onResolve: (String, String) -> Unit,
    onAdd: () -> Unit,
    onSync: () -> Unit,
    onBack: () -> Unit,
    onGet: (String) -> Unit = {},
    onGetAll: () -> Unit = {},
    onAutoDownload: (Boolean) -> Unit = {},
) {
    val sp = DtTheme.space
    val c = DtTheme.colors
    var ask by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().background(c.header).padding(horizontal = sp.panel), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.s)) {
            DtText("LUT files", DtTheme.type.body, c.textStrong, Modifier.weight(1f))
            if (ui.synced) DtTextButton(if (ui.busy) "syncing…" else "sync now", onSync, enabled = !ui.busy, compact = true)
            DtTextButton("back", onBack, compact = true)
        }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = sp.panel, vertical = sp.m),
            verticalArrangement = Arrangement.spacedBy(sp.s)) {
            val usable = ui.files.count { it.usable }
            DtText("${ui.files.size} files, ${mb(ui.totalBytes)}" + (if (usable != ui.files.size) " · $usable usable here" else ""),
                DtTheme.type.body, c.textStrong)
            DtText(
                if (ui.synced) "Files added here go to LightPhotoLibrary, and files from your PCs' darktable LUT folder come here" +
                    (if (ui.autoSync) " with each sync." else " when you tap \"sync now\" (automatic sync is off).")
                else "LightPhotoLibrary does not carry LUT files yet (no server, or one without this feature): the files stay on this phone.",
                DtTheme.type.label, c.textMuted, maxLines = 4)
            if (ui.synced) {
                ToggleControl("Download LUT files from LightPhotoLibrary automatically", ui.autoDownload, enabled = true) { onAutoDownload(!ui.autoDownload) }
                DtText(
                    if (ui.autoDownload) "On: a sync downloads the files this phone lacks. A file the phone has that changed in LightPhotoLibrary is never replaced without asking."
                    else "Off: a sync downloads nothing. Take the files you want from the list below, or with \"Get from LightPhotoLibrary\" in the module.",
                    DtTheme.type.label, c.textMuted, maxLines = 4)
            }
            ui.error?.let { DtText("last sync: $it", DtTheme.type.label, c.textBright, maxLines = 3) }
            ui.message?.let { DtText(it, DtTheme.type.label, c.textMuted, maxLines = 3) }
            DtTextButton("Add from phone…", onAdd, Modifier.fillMaxWidth())
            for (cf in ui.conflicts) {
                Divider()
                DtText(cf.path, DtTheme.type.body, c.textStrong, middle = true)
                DtText("changed on this phone and${cf.serverDevice?.let { " on $it" } ?: " in LightPhotoLibrary"}: which one to keep?",
                    DtTheme.type.label, c.textBright, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                    DtTextButton("mine", { onResolve(cf.path, "mine") }, compact = true, enabled = !ui.busy)
                    DtTextButton("theirs", { onResolve(cf.path, "theirs") }, compact = true, enabled = !ui.busy)
                    DtTextButton("keep both", { onResolve(cf.path, "both") }, compact = true, enabled = !ui.busy)
                }
            }
            if (ui.missing.isNotEmpty()) {
                Divider()
                DtText("in LightPhotoLibrary, not on this phone", DtTheme.type.body, c.textStrong)
                for ((p, size) in ui.missing) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                        Column(Modifier.weight(1f)) {
                            DtText(p, DtTheme.type.body, c.textStrong, middle = true)
                            DtText(mb(size), DtTheme.type.label, c.textMuted)
                        }
                        DtTextButton("Get", { onGet(p) }, compact = true, enabled = !ui.busy)
                    }
                }
                DtTextButton("Get all (${ui.missing.size} files, ${mb(ui.missing.sumOf { it.second })})", onGetAll, Modifier.fillMaxWidth(), enabled = !ui.busy)
            }
            Divider()
            for (f in ui.files) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                    Column(Modifier.weight(1f)) {
                        DtText(f.path, DtTheme.type.body, if (f.usable) c.textStrong else c.textDisabled, middle = true)
                        val st = ui.states[f.path]
                        DtText(listOfNotNull(mb(f.size), f.reason, st?.words).joinToString(" · "), DtTheme.type.label, c.textMuted, maxLines = 2)
                    }
                    if (ui.states[f.path] == LutSyncState.NEWER_IN_LPL) DtTextButton("Update", { onGet(f.path) }, compact = true, enabled = !ui.busy)
                    DtTextButton("Remove", { ask = f.path }, compact = true)
                }
            }
            for ((p, st) in ui.states) if (ui.files.none { it.path == p } && ui.missing.none { it.first == p }) {
                Column(Modifier.fillMaxWidth()) {
                    DtText(p, DtTheme.type.body, c.textMuted, middle = true)
                    DtText(st.words, DtTheme.type.label, c.textMuted)
                }
            }
        }
        ask?.let { p ->
            Divider()
            Column(Modifier.fillMaxWidth().background(c.panel).padding(sp.panel), verticalArrangement = Arrangement.spacedBy(sp.s)) {
                DtText("Remove $p from this phone? LightPhotoLibrary and your other devices keep it; edits that use it show it as missing here.",
                    DtTheme.type.label, c.textStrong, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                    DtTextButton("remove", { ask = null; onRemove(p) }, compact = true)
                    DtTextButton("cancel", { ask = null }, compact = true)
                }
            }
        }
    }
}

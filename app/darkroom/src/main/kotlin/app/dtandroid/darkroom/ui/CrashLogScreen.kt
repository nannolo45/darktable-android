// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dtandroid.darkroom.robust.CrashEntry
import app.dtandroid.darkroom.robust.CrashLog
import app.dtandroid.ui.theme.DtTheme

/**
 * Brief P4a deliverable 4: Settings › About › crash log. The entries newest first (heading: time, kind, app version;
 * the first lines of the text, all of it after a tap), "copy" (the whole log as text to the clipboard), "clear" (asks
 * first). Stateless apart from which entries are opened and the clear question; the app reads the [CrashLog].
 */
@Composable
fun CrashLogScreen(entries: List<CrashEntry>, onCopy: () -> Unit, onClear: () -> Unit, onBack: () -> Unit) {
    val c = DtTheme.colors
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.l, vertical = DtTheme.space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s),
        ) {
            DtText("crash log", DtTheme.type.title, c.textStrong, Modifier.weight(1f))
            DtTextButton("copy", onCopy, enabled = entries.isNotEmpty(), compact = true)
            DtTextButton("clear", { asking = true }, enabled = entries.isNotEmpty(), compact = true)
            DtTextButton("back", onBack, compact = true)
        }
        Divider()
        if (asking) {
            Column(Modifier.fillMaxWidth().background(c.header).padding(DtTheme.space.panel).semantics { contentDescription = "clear question" }) {
                DtText("Delete all ${entries.size} entries of the crash log?", DtTheme.type.label, c.textStrong, maxLines = 3)
                Row(Modifier.padding(top = DtTheme.space.s), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                    DtTextButton("delete", { asking = false; onClear() })
                    DtTextButton("keep", { asking = false })
                }
            }
            Divider()
        }
        DtText(
            "Kept on this phone only; nothing is sent. Crashes of the app, of the engine, \"not responding\" stops, " +
                "stops by Android for low memory, and engine errors the app showed. \"copy\" puts the text on the clipboard.",
            DtTheme.type.small, c.textMuted, Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.s), maxLines = 6,
        )
        if (entries.isEmpty()) {
            DtText("no entries", DtTheme.type.label, c.text,
                Modifier.fillMaxWidth().padding(DtTheme.space.panel).semantics { contentDescription = "crash log empty" })
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "crash log entries: ${entries.size}" }) {
                items(entries.asReversed(), key = { "${it.time}-${it.kind}-${it.text.hashCode()}" }) { e -> Entry(e) }
            }
        }
    }
}

@Composable
private fun Entry(e: CrashEntry) {
    val c = DtTheme.colors
    var open by rememberSaveable(e.time, e.kind) { mutableStateOf(false) }
    val lines = e.text.trimEnd().lines()
    Column(
        Modifier.fillMaxWidth().clickable { open = !open }
            .padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.s),
    ) {
        DtText(CrashLog.heading(e), DtTheme.type.label, c.textStrong, maxLines = 2)
        DtText(
            (if (open) lines else lines.take(PREVIEW_LINES)).joinToString("\n"),
            DtTheme.type.small, c.text, Modifier.fillMaxWidth().padding(top = DtTheme.space.xxs),
            maxLines = if (open) Int.MAX_VALUE else PREVIEW_LINES,
        )
        if (!open && lines.size > PREVIEW_LINES) DtText("… ${lines.size - PREVIEW_LINES} more lines", DtTheme.type.small, c.textMuted)
    }
    Divider()
}

private const val PREVIEW_LINES = 6

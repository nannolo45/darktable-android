// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dtandroid.darkroom.HostAction
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.theme.DtTheme

/**
 * Brief P2f deliverable 5: the photo list, designed as one piece. Stateless: the app fills it from
 * LightPhotoLibrary's working set and the working folder; screenshot tests fill it with examples.
 */
object PhotoList {
    /** One photo: one row. Tapping the row opens it; "more" offers [actions]. */
    data class Row(
        val key: String,
        val name: String,
        /** one status line in plain words ("synced", "downloading · 40 %", "waiting to sync: …") */
        val status: String,
        /** a thin bar while it downloads or syncs (0..1), else null */
        val progress: Float? = null,
        /** R15: "last edited on Desktop · 18 Nov · exposure, color balance rgb", or null */
        val lastEdit: String? = null,
        /** what a request from LightPhotoLibrary did with the photo ("saved your open changes and sent them"), or null */
        val note: String? = null,
        val thumb: RenderedImage? = null,
        val actions: List<HostAction> = emptyList(),
        val onOpen: () -> Unit = {},
    )

    /** A part of the list with its own heading: a server, or the phone's own files. */
    data class Section(
        val key: String,
        val title: String,
        /** a line under the heading: for a server, this device's name, connected or not, what waits */
        val line: String? = null,
        /** one action for the whole section ("sync now"), or null */
        val action: HostAction? = null,
        /** shown only while the section has no photos */
        val empty: String? = null,
        /** the last request from the server and how it went, or null */
        val note: String? = null,
        val rows: List<Row> = emptyList(),
    )

    /** Brief P2i: the header's words while the "sync automatically" switch is off. */
    const val AUTO_SYNC_OFF = "automatic sync is off"

    /** Brief P2i: a photo's status when its edit stays on the phone because automatic sync is off. */
    const val NOT_SYNCED_HERE = "edited here, not synced yet"

    /**
     * The line under a server's heading: this device's name, the connection, brief P2i: whether automatic
     * sync is off, and how many photos have something not sent yet.
     */
    fun serverLine(deviceName: String, connection: String, unsent: Int, autoSync: Boolean): String =
        "this phone is \"$deviceName\" · $connection" +
            (if (!autoSync) " · $AUTO_SYNC_OFF" else "") +
            (if (unsent > 0) " · ${app.dtandroid.darkroom.Words.count(unsent, "photo")} ${if (autoSync) "waiting to sync" else "not synced"}" else "")
}

/**
 * The list screen: top bar (title, [topActions] as text buttons, [menu] behind a "more" icon), short
 * messages, then the sections. A "more" sheet per photo carries its actions.
 */
@Composable
fun PhotoListScreen(
    title: String,
    sections: List<PhotoList.Section>,
    modifier: Modifier = Modifier,
    topActions: List<HostAction> = emptyList(),
    menu: List<HostAction> = emptyList(),
    messages: List<String> = emptyList(),
    /** a question that needs an answer ("remove anyway?"), with its two answers, shown above the list */
    question: Pair<String, List<HostAction>>? = null,
) {
    val c = DtTheme.colors
    var sheet by remember { mutableStateOf<Pair<String, List<HostAction>>?>(null) }
    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = DtTheme.space.l, end = DtTheme.space.xs, top = DtTheme.space.s, bottom = DtTheme.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s),
            ) {
                DtText(title, DtTheme.type.title, c.textStrong, Modifier.weight(1f))
                for (a in topActions) DtTextButton(a.label, a.run, enabled = a.enabled)
                if (menu.isNotEmpty()) DtIconButton(DtIcon.More, "more", c.text, { sheet = title to menu }, iconSize = DtTheme.sizes.iconLarge)
            }
            Divider()
            for (m in messages) Note(m)
            question?.let { (text, answers) ->
                Note(text, strong = true)
                Row(Modifier.padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                    for (a in answers) DtTextButton(a.label, a.run, enabled = a.enabled)
                }
                Divider()
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                for (sec in sections) {
                    item(key = "section-${sec.key}") { SectionHeader(sec) }
                    items(sec.rows, key = { "row-${sec.key}-${it.key}" }) { r ->
                        PhotoRow(r) { sheet = r.name to r.actions }
                        Divider()
                    }
                }
            }
        }
        sheet?.let { (name, actions) ->
            Sheet(name, { sheet = null }, DtTheme.sizes.formMaxWidth) {
                for (a in actions) SheetItem(a.label, a.enabled) { sheet = null; a.run() }
            }
        }
    }
}

@Composable
private fun Note(text: String, strong: Boolean = false) {
    DtText(text, DtTheme.type.label, if (strong) DtTheme.colors.text else DtTheme.colors.textMuted,
        Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs), maxLines = 6)
}

@Composable
private fun SectionHeader(sec: PhotoList.Section) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth().background(c.header)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.panelHeader).padding(start = DtTheme.space.panel, end = DtTheme.space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s),
        ) {
            Column(Modifier.weight(1f).padding(vertical = DtTheme.space.xs)) {
                DtText(sec.title, DtTheme.type.body, c.textStrong)
                sec.line?.let { DtText(it, DtTheme.type.small, c.textMuted, maxLines = 2) }
                sec.note?.let { DtText(it, DtTheme.type.small, c.text, maxLines = 3) }
            }
            sec.action?.let { a -> DtTextButton(a.label, a.run, enabled = a.enabled) }
        }
    }
    if (sec.rows.isEmpty() && sec.empty != null) {
        DtText(sec.empty, DtTheme.type.label, c.textMuted,
            Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m), maxLines = 6)
    }
    Divider()
}

@Composable
private fun PhotoRow(r: PhotoList.Row, onMore: () -> Unit) {
    val c = DtTheme.colors
    val bmp = remember(r.thumb) { r.thumb?.toImageBitmap() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.listRow)
            .clickable(role = Role.Button, onClick = r.onOpen)
            .semantics { contentDescription = "${r.name}: ${r.status}" }
            .padding(start = DtTheme.space.l, end = DtTheme.space.xs, top = DtTheme.space.m, bottom = DtTheme.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.l),
    ) {
        Box(Modifier.size(DtTheme.sizes.listThumb).background(c.photoBackground)) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DtTheme.space.xxs)) {
            DtText(r.name, DtTheme.type.body, c.textStrong, middle = true)
            DtText(r.status, DtTheme.type.small, c.textMuted)
            r.note?.let { DtText(it, DtTheme.type.small, c.text, maxLines = 2) }
            r.lastEdit?.let { DtText(it, DtTheme.type.small, c.textMuted) }
            r.progress?.let { ProgressLine(it, Modifier.padding(top = DtTheme.space.xs)) }
        }
        if (r.actions.isNotEmpty()) DtIconButton(DtIcon.More, "more for ${r.name}", c.text, onMore, iconSize = DtTheme.sizes.iconLarge)
    }
}

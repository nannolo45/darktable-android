// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.VersionSync
import app.dtandroid.darkroom.VersionUiRow
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/**
 * Brief P3f (docs/design/mockups/P3f-Versions.html): the "versions" chip of the top bar. It names the version in use
 * ("v2") and opens the versions sheet. Only where the app supports versions for the open photo.
 */
@Composable
internal fun VersionsChip(controller: DarkroomController, s: DarkroomState) {
    if (!s.versionsAvailable || s.info == null) return
    val c = DtTheme.colors
    val label = "v${s.current?.version ?: 0}"
    Row(
        Modifier.height(DtTheme.sizes.touch).clip(RoundedCornerShape(DtTheme.sizes.radiusControl)).background(c.raised)
            .clickable(role = Role.Button) { controller.launch { openVersions() } }
            .padding(horizontal = DtTheme.space.m)
            .semantics { contentDescription = "versions, $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xs),
    ) {
        DtIconImage(DtIcon.Versions, null, c.textStrong, DtTheme.sizes.icon)
        DtText(label, DtTheme.type.label, c.textStrong)
    }
}

/** The versions sheet, its rename and delete questions, and the picture shown while a row is held. */
@Composable
fun BoxScope.VersionsOverlays(controller: DarkroomController, s: DarkroomState, tablet: Boolean) {
    val o = s.overlay
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    val name = s.current?.fileName ?: "photo"
    when (o) {
        Overlay.Versions -> {
            Sheet("Versions of $name", { controller.closeVersions() }, maxW) { VersionsSheetContent(controller, s) }
            // "hold to peek": the held version's picture over everything, for as long as the finger is down
            if (s.peek != null || s.peekNote != null) Peek(s)
        }
        is Overlay.VersionName -> {
            val cur = s.versionRows.firstOrNull { it.n == o.n }?.name ?: ""
            TextEntrySheet("name of version ${o.n}", "empty removes the name", cur, number = false, onClose = { controller.setOverlay(Overlay.Versions) }) { text ->
                controller.launch { renameVersion(o.n, text); setOverlay(Overlay.Versions) }
                null
            }
        }
        is Overlay.VersionDelete -> {
            val row = s.versionRows.firstOrNull { it.n == o.n }
            Sheet("delete version ${o.n}?", { controller.setOverlay(Overlay.Versions) }, maxW) {
                DtText(
                    if (row != null && row.sync != VersionSync.LOCAL) "Its file is deleted from the phone; LightPhotoLibrary keeps a copy in its backup folder."
                    else "Its file is deleted from the phone. This cannot be undone.",
                    DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(vertical = DtTheme.space.s), maxLines = 3,
                )
                SheetItem("delete", enabled = true) { controller.launch { deleteVersion(o.n); setOverlay(Overlay.Versions) } }
                SheetItem("keep it", enabled = true) { controller.setOverlay(Overlay.Versions) }
            }
        }
        else -> Unit
    }
}

@Composable
private fun BoxScope.Peek(s: DarkroomState) {
    val c = DtTheme.colors
    val bmp = remember(s.peek) { s.peek?.toImageBitmap() }
    Box(Modifier.matchParentSize().background(c.scrim), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize().padding(DtTheme.space.l), contentScale = ContentScale.Fit)
        s.peekNote?.let { DtText(it, DtTheme.type.label, c.textBright, Modifier.padding(DtTheme.space.xl), maxLines = 3) }
    }
}

@Composable
private fun VersionsSheetContent(controller: DarkroomController, s: DarkroomState) {
    val c = DtTheme.colors
    var menu by remember { mutableStateOf<Int?>(null) }
    val canCover = controller.versionHost?.let { h -> s.current?.let { h.canSetCover(it) } } == true
    for (row in s.versionRows) {
        VersionRow(controller, row, menu == row.n) { menu = if (menu == row.n) null else row.n }
        if (menu == row.n) {
            SheetItem("rename…", enabled = true) { menu = null; controller.setOverlay(Overlay.VersionName(row.n)) }
            if (canCover) SheetItem("set as cover", enabled = !row.cover) { menu = null; controller.launch { setVersionCover(row.n) } }
            SheetItem(if (row.n == 0) "delete (not for version 0)" else "delete…", enabled = row.n != 0) { menu = null; controller.setOverlay(Overlay.VersionDelete(row.n)) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = DtTheme.space.m), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m)) {
        val enabled = s.info != null && !s.readOnly
        IconTextButton(DtIcon.Duplicate, "Duplicate", enabled, Modifier.weight(1f)) { controller.launch { duplicate(virgin = false) } }
        IconTextButton(DtIcon.DuplicateVirgin, "Virgin duplicate", enabled, Modifier.weight(1f)) { controller.launch { duplicate(virgin = true) } }
    }
    DtText(s.versionNote ?: "Tap a row to open it. Hold a row to peek.", DtTheme.type.small, c.textMuted, Modifier.padding(vertical = DtTheme.space.s), maxLines = 3)
}

@Composable
private fun IconTextButton(icon: DtIcon, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = DtTheme.colors
    Row(
        modifier.height(DtTheme.sizes.touch).clip(RoundedCornerShape(DtTheme.sizes.radiusControl)).background(c.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s, Alignment.CenterHorizontally),
    ) {
        DtIconImage(icon, null, if (enabled) c.textStrong else c.textDisabled, DtTheme.sizes.icon)
        DtText(label, DtTheme.type.label, if (enabled) c.textStrong else c.textDisabled)
    }
}

/** A row: thumbnail, "v2 · name" (and the cover mark), "edited" or "no edits" with the sync dot, and a menu button. */
@Composable
private fun VersionRow(controller: DarkroomController, row: VersionUiRow, menuOpen: Boolean, toggleMenu: () -> Unit) {
    val c = DtTheme.colors
    val bmp = remember(row.thumb) { row.thumb?.toImageBitmap() }
    val title = "v${row.n}" + (if (row.name.isNotEmpty()) " · ${row.name}" else "") + (if (row.cover) " · cover" else "")
    val line = (if (row.edited) "edited" else "no edits") + " · " + row.sync.word
    Row(
        Modifier.fillMaxWidth().padding(vertical = DtTheme.space.xxs)
            .then(if (row.current) Modifier.border(DtTheme.sizes.border, c.textBright, RoundedCornerShape(DtTheme.sizes.radiusControl)) else Modifier)
            .pointerInput(row.n) {
                detectTapGestures(
                    onPress = { tryAwaitRelease(); controller.peekVersion(null) },
                    onLongPress = { controller.peekVersion(row.n) },
                    onTap = { controller.launch { if (row.n != currentVersionOf(controller)) switchVersion(row.n); closeVersions() } },
                )
            }
            .semantics { contentDescription = "$title, $line"; stateDescription = if (row.current) "in use" else "" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.m),
    ) {
        Box(Modifier.size(DtTheme.sizes.versionThumbWidth, DtTheme.sizes.versionThumbHeight).background(c.photoBackground)) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else DtText("v${row.n}", DtTheme.type.tiny, c.textMuted, Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f)) {
            DtText(title, DtTheme.type.body, c.textStrong)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xs)) {
                Box(Modifier.size(DtTheme.sizes.versionDot).clip(CircleShape).background(when (row.sync) {
                    VersionSync.SYNCED -> c.syncDone
                    VersionSync.WAITING -> c.syncWaiting
                    VersionSync.LOCAL -> c.textMuted
                }))
                DtText(line, DtTheme.type.small, c.textMuted)
            }
        }
        DtIconButton(DtIcon.More, "more for version ${row.n}", c.textStrong, toggleMenu, modifier = Modifier.semantics { stateDescription = if (menuOpen) "open" else "closed" })
    }
}

private fun currentVersionOf(controller: DarkroomController): Int = controller.state.value.current?.version ?: 0

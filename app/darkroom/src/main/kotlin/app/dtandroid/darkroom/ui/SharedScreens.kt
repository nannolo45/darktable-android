// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dtandroid.lpl.SharedKind
import app.dtandroid.lpl.SharedPolicy
import app.dtandroid.lpl.SharedRow
import app.dtandroid.lpl.SharedSettings
import app.dtandroid.lpl.SharedView
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Brief P3l (R11, PLAN.md 3.9 and 4.5): styles, presets and module layouts that came through LightPhotoLibrary.
 * Stateless: the app fills them from its [app.dtandroid.lpl.SharedSync] view; screenshot tests with examples.
 * Nothing changes on the phone before "Update" in the review sheet.
 */
object SharedUi {
    /** The review sheet's choices (the app keeps them until Update or Later). */
    data class Review(
        val rows: List<SharedRow>,
        /** new and changed items ticked for taking in */
        val take: Set<Long>,
        /** conflicts decided: `mine`, `theirs`, `both` */
        val conflicts: Map<Long, String> = emptyMap(),
        /** deletions decided: true = delete here too, false = keep on this phone */
        val deletions: Map<Long, Boolean> = emptyMap(),
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        /** what "Update n" counts */
        val count: Int get() = take.size + conflicts.size + deletions.size
    }

    /** "24 Nov 14:05" */
    fun time(t: Double): String = if (t <= 0) "" else SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(Date((t * 1000).toLong()))

    fun banner(v: SharedView): String? {
        val n = v.waiting.size
        if (n == 0) return v.lastUpdate?.let { "Styles, presets and layouts updated: $it" }
        val from = v.devices.let { d -> if (d.size <= 2) d.joinToString(" and ") else "${d[0]}, ${d[1]} and ${d.size - 2} more" }
        return "$n ${if (n == 1) "update" else "updates"} to styles, presets and layouts from $from"
    }

    fun group(k: SharedKind) = when (k) {
        SharedKind.STYLE -> "Styles"
        SharedKind.PRESET -> "Presets"
        SharedKind.LAYOUT -> "Module layouts"
    }

    fun conflictChoice(c: String?) = when (c) {
        "mine" -> "keep this phone's"
        "theirs" -> "take the other"
        "both" -> "keep both"
        else -> null
    }
}

/** The photo list's line about waiting updates, with Review; after an update, what was taken in and what kept waiting. */
@Composable
fun SharedBanner(text: String, onReview: (() -> Unit)?, onDismiss: (() -> Unit)?) {
    val c = DtTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.header).padding(start = DtTheme.space.panel, end = DtTheme.space.xs, top = DtTheme.space.xs, bottom = DtTheme.space.xs)
            .semantics { contentDescription = "shared banner: $text" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s),
    ) {
        Box(Modifier.size(DtTheme.sizes.versionDot).clip(CircleShape).background(if (onReview != null) c.syncWaiting else c.syncDone))
        DtText(text, DtTheme.type.label, c.text, Modifier.weight(1f), maxLines = 3)
        onReview?.let { DtTextButton("Review", it) }
        onDismiss?.let { DtTextButton("OK", it) }
    }
    Divider()
}

/** A dot on a button (the settings button while something waits). */
@Composable
fun BoxScope.WaitingDot() {
    Box(Modifier.align(Alignment.TopEnd).padding(DtTheme.space.xs).size(DtTheme.sizes.versionDot).clip(CircleShape)
        .background(DtTheme.colors.syncWaiting).semantics { contentDescription = "something waits" })
}

@Composable
private fun CheckBox(on: Boolean, enabled: Boolean = true) {
    val c = DtTheme.colors
    Box(Modifier.size(DtTheme.sizes.checkbox).border(DtTheme.sizes.border, if (enabled) c.text else c.textDim)
        .background(if (on) c.header else c.sliderTrack), contentAlignment = Alignment.Center) {
        if (on) DtIconImage(DtIcon.Check, null, c.textStrong, DtTheme.sizes.checkbox)
    }
}

@Composable
private fun ReviewLine(r: SharedRow, review: SharedUi.Review, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = DtTheme.colors
    val m = r.meta
    val device = m.deviceName.ifEmpty { "another device" }
    val (line, enabled, action) = when (r.change) {
        SharedRow.Change.NEW -> Triple("new · $device · ${SharedUi.time(m.changedAt)}", true, null)
        SharedRow.Change.CHANGED -> Triple("changed · $device · ${SharedUi.time(m.changedAt)}", true, null)
        SharedRow.Change.CONFLICT -> Triple("changed on $device · ${SharedUi.time(m.changedAt)}" +
            (SharedUi.conflictChoice(review.conflicts[m.id])?.let { " · $it" } ?: ""), true, "changed here too ›")
        SharedRow.Change.DELETED -> Triple("Deleted on $device · ${SharedUi.time(m.changedAt)}" +
            (review.deletions[m.id]?.let { if (it) " · delete here too" else " · keep on this phone" } ?: ""), true, "Decide ›")
        SharedRow.Change.UNUSABLE -> Triple("needs a newer darktable", false, null)
    }
    val ticked = when (r.change) {
        SharedRow.Change.NEW, SharedRow.Change.CHANGED -> m.id in review.take
        SharedRow.Change.CONFLICT -> m.id in review.conflicts
        SharedRow.Change.DELETED -> m.id in review.deletions
        SharedRow.Change.UNUSABLE -> false
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.listRow)
            .clickable(enabled = enabled && !review.busy, role = if (action == null) Role.Checkbox else Role.Button, onClick = if (action == null) onToggle else onOpen)
            .semantics { contentDescription = "shared ${m.key.name}: $line" + if (ticked) " · chosen" else "" }
            .padding(vertical = DtTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.l),
    ) {
        CheckBox(ticked, enabled)
        Column(Modifier.weight(1f)) {
            DtText(m.key.name, DtTheme.type.body, if (enabled) c.textStrong else c.textDim, middle = true)
            DtText(line, DtTheme.type.small, if (enabled) c.textMuted else c.textDim, maxLines = 2)
        }
        action?.let { DtText(it, DtTheme.type.label, c.text) }
    }
}

/**
 * The review sheet: grouped Styles / Presets / Module layouts / Deleted elsewhere; a conflict line opens the conflict
 * dialog, a deletion the deletion dialog; greyed lines need a newer darktable. Later closes, Update n applies.
 */
@Composable
fun BoxScope.SharedReviewSheet(
    review: SharedUi.Review,
    onToggle: (Long) -> Unit,
    onConflict: (SharedRow) -> Unit,
    onDeletion: (SharedRow) -> Unit,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
) {
    Sheet("Updates from LightPhotoLibrary", onLater, DtTheme.sizes.formMaxWidth) {
        val groups = review.rows.groupBy { if (it.change == SharedRow.Change.DELETED) null else it.meta.key.kind }
        for (k in listOf(SharedKind.STYLE, SharedKind.PRESET, SharedKind.LAYOUT, null)) {
            val rows = groups[k] ?: continue
            SheetSection(k?.let { SharedUi.group(it) } ?: "Deleted elsewhere")
            for (r in rows) ReviewLine(r, review, { onToggle(r.meta.id) }, { if (r.change == SharedRow.Change.DELETED) onDeletion(r) else onConflict(r) })
        }
        if (review.rows.isEmpty()) DtText("Nothing waits.", DtTheme.type.label, DtTheme.colors.textMuted, Modifier.padding(vertical = DtTheme.space.m))
        review.error?.let { DtText(it, DtTheme.type.label, DtTheme.colors.text, Modifier.padding(vertical = DtTheme.space.s), maxLines = 4) }
        DtText("Nothing changes on this phone before Update.", DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(top = DtTheme.space.m), maxLines = 2)
        Row(Modifier.fillMaxWidth().padding(top = DtTheme.space.m), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s, Alignment.End)) {
            DtTextButton("Later", onLater, enabled = !review.busy)
            DtTextButton(if (review.busy) "Updating…" else "Update ${review.count}", onUpdate, enabled = !review.busy && review.count > 0)
        }
    }
}

/**
 * The conflict dialog: the item, both devices with their change times, Keep this phone's / Take <device>'s / Keep both
 * (the other copy then arrives as "<name> (<device>)"), and "Same choice for the other conflicts in this update".
 * Parameter values are not shown (they are not decoded). Keep both is not offered when either side is a deletion.
 */
@Composable
fun BoxScope.SharedConflictDialog(
    row: SharedRow,
    phoneName: String,
    sameForOthers: Boolean,
    others: Int,
    onSameForOthers: () -> Unit,
    onChoose: (String) -> Unit,
    onClose: () -> Unit,
) {
    val m = row.meta
    val device = m.deviceName.ifEmpty { "the other device" }
    val phoneDeleted = row.conflict?.deleted == true
    Sheet(m.key.name, onClose, DtTheme.sizes.formMaxWidth) {
        DtText("Changed on two devices since the last update.", DtTheme.type.label, DtTheme.colors.text, maxLines = 2)
        SheetSection(phoneName)
        DtText((if (phoneDeleted) "deleted" else "changed") + " · " + SharedUi.time(row.conflict?.changedAt ?: 0.0), DtTheme.type.label, DtTheme.colors.text)
        SheetSection(device)
        DtText((if (m.deleted) "deleted" else "changed") + " · " + SharedUi.time(m.changedAt), DtTheme.type.label, DtTheme.colors.text)
        SheetSection("keep")
        SheetItem("Keep this phone's", true) { onChoose("mine") }
        SheetItem("Take $device's", true) { onChoose("theirs") }
        if (!phoneDeleted && !m.deleted) {
            SheetItem("Keep both", true) { onChoose("both") }
            DtText("The other copy arrives as \"${m.key.name} ($device)\".", DtTheme.type.small, DtTheme.colors.textMuted, maxLines = 2)
        }
        if (others > 0) Row(
            Modifier.fillMaxWidth().heightIn(min = DtTheme.sizes.touch).clickable(role = Role.Checkbox, onClick = onSameForOthers)
                .semantics { contentDescription = "same choice for the other conflicts" + if (sameForOthers) " · chosen" else "" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DtTheme.space.l),
        ) {
            CheckBox(sameForOthers)
            DtText("Same choice for the other conflicts in this update ($others)", DtTheme.type.label, DtTheme.colors.text, Modifier.weight(1f), maxLines = 2)
        }
    }
}

/** The deletion dialog: deleted on <device> · <when>; Delete here too / Keep on this phone / Decide later. */
@Composable
fun BoxScope.SharedDeletionDialog(row: SharedRow, onDelete: () -> Unit, onKeep: () -> Unit, onLater: () -> Unit) {
    val m = row.meta
    Sheet(m.key.name, onLater, DtTheme.sizes.formMaxWidth) {
        DtText("Deleted on ${m.deviceName.ifEmpty { "another device" }} · ${SharedUi.time(m.changedAt)}", DtTheme.type.label, DtTheme.colors.text, maxLines = 2)
        SheetItem("Delete here too", true, onClick = onDelete)
        SheetItem("Keep on this phone", true, onClick = onKeep)
        SheetItem("Decide later", true, onClick = onLater)
        DtText("A kept item stays on this phone only; it is not offered again. If you change it later, it comes back on the other devices.",
            DtTheme.type.small, DtTheme.colors.textMuted, Modifier.padding(top = DtTheme.space.s), maxLines = 4)
    }
}

/**
 * Settings › LightPhotoLibrary: "Share through LightPhotoLibrary", the three kinds, what happens when the same item
 * changed on two devices, the "Now" line with Review, and what was sent from this phone.
 */
@Composable
fun SharedSettingsSection(
    values: SharedSettings.Values,
    view: SharedView?,
    serverKnowsSharing: Boolean?,
    onChange: (SharedSettings.Values) -> Unit,
    onReview: () -> Unit,
) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs)) {
        PanelHeading("styles, presets and module layouts")
        ToggleControl("Share through LightPhotoLibrary", values.on, enabled = true) { onChange(values.copy(on = !values.on)) }
        ToggleControl("Styles", values.styles, enabled = values.on) { onChange(values.copy(styles = !values.styles)) }
        ToggleControl("Your presets", values.presets, enabled = values.on) { onChange(values.copy(presets = !values.presets)) }
        ToggleControl("Module layouts", values.layouts, enabled = values.on) { onChange(values.copy(layouts = !values.layouts)) }
        SheetSection("Same item changed on two devices")
        SheetItem("Ask me (default)", values.on, selected = values.policy == SharedPolicy.ASK) { onChange(values.copy(policy = SharedPolicy.ASK)) }
        SheetItem("Newest wins", values.on, selected = values.policy == SharedPolicy.NEWEST) { onChange(values.copy(policy = SharedPolicy.NEWEST)) }
        SheetSection("Now")
        val now = when {
            !values.on -> "Sharing is off: nothing is sent or offered. Import by hand still works."
            serverKnowsSharing == false -> "This LightPhotoLibrary does not share styles, presets and layouts yet."
            view == null || view.checkedAt <= 0 -> "Not checked yet."
            else -> "${view.waiting.size} waiting · checked ${SharedUi.time(view.checkedAt)}"
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
            DtText(now, DtTheme.type.label, c.text, Modifier.weight(1f), maxLines = 3)
            if (values.on && view != null && view.rows.isNotEmpty()) DtTextButton("Review", onReview)
        }
        view?.error?.let { DtText(it, DtTheme.type.small, c.textMuted, maxLines = 3) }
        SheetSection("Sent from this phone")
        val sent = view?.sent.orEmpty()
        if (sent.isEmpty()) DtText("Nothing yet.", DtTheme.type.small, c.textMuted)
        for (s in sent.take(8)) DtText("${s.name} · ${SharedUi.group(s.kind).lowercase()} · ${s.what} · ${SharedUi.time(s.at)}", DtTheme.type.small, c.textMuted)
        DtText("Styles (.dtstyle) and presets (.dtpreset) can still be imported by hand in the darkroom's styles and presets menus; " +
            "module layouts in the layout editor.", DtTheme.type.small, c.textMuted, Modifier.padding(top = DtTheme.space.s), maxLines = 4)
    }
}

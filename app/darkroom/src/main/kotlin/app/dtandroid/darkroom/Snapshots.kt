// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.History
import app.dtandroid.engine.api.HistoryItem
import app.dtandroid.engine.api.ModuleInstance

/**
 * Brief P3h: one snapshot of the open photo, as darktable's snapshots module (`src/libs/snapshots.c`,
 * `_take_snapshot`): the history end when it was taken, named by the history item at that end ("original" at 0)
 * with the instance's name after it. [prefix] is the history up to [historyEnd] as it was then: the engine renders
 * the snapshot as that history position ([app.dtandroid.engine.api.RenderOptions.historyEnd], engine-api.md 8),
 * so it is kept only while the history below that position is unchanged ([SnapshotRules.prune]).
 */
data class Snapshot(val id: Int, val historyEnd: Int, val name: String, val prefix: List<HistoryItem>)

/**
 * Brief P3h: the split view. [vertical]: a vertical divider, the snapshot left or right (desktop's default);
 * false: a horizontal one, the snapshot above or below. [snapshotFirst]: the snapshot on the left (or top) side.
 * [position]: the divider as a fraction of the photo view's width (or height).
 */
data class SplitState(val vertical: Boolean = true, val snapshotFirst: Boolean = true, val position: Float = 0.5f)

/** Brief P3h: the open photo's snapshots (memory only, as on desktop: they last for the session). */
data class SnapshotsState(
    val list: List<Snapshot> = emptyList(),
    /** the snapshot shown in the split view; null: no split */
    val selected: Int? = null,
    val split: SplitState = SplitState(),
    val nextId: Int = 1,
    /** the selected snapshot's pictures: its preview and, zoomed in, its sharp region (as the live edit's) */
    val preview: PhotoLayer? = null,
    val region: PhotoLayer? = null,
    /** what [preview] was rendered for: snapshot id and preview size */
    val previewKey: Pair<Int, Int>? = null,
) {
    val selectedSnapshot: Snapshot? get() = selected?.let { id -> list.firstOrNull { it.id == id } }
    val full: Boolean get() = list.size >= SnapshotRules.MAX
}

object SnapshotRules {
    /** desktop's MAX_SNAPSHOT (`src/libs/snapshots.c`) */
    const val MAX = 10
    const val MIN_POSITION = 0.04f
    const val MAX_POSITION = 0.96f

    /** desktop's name: the module of the item at [end] (its instance name after it), "original" at 0 */
    fun name(history: History, end: Int, modules: List<ModuleInstance>): String {
        if (end <= 0) return "original"
        val item = history.items.getOrNull(end - 1) ?: return "unknown"
        val base = modules.firstOrNull { it.op == item.op && it.priority == item.priority }?.name ?: item.name
        return if (item.instanceName.isEmpty()) base else "$base ${item.instanceName}"
    }

    /** a snapshot of the history end, selected at once (desktop shows it); null when all slots are used */
    fun take(s: SnapshotsState, history: History, modules: List<ModuleInstance>): SnapshotsState? {
        if (s.full) return null
        val end = history.end.coerceIn(0, history.items.size)
        val snap = Snapshot(s.nextId, end, "$end · ${name(history, end, modules)}", history.items.take(end))
        return s.copy(list = s.list + snap, nextId = s.nextId + 1, selected = snap.id, preview = null, region = null, previewKey = null)
    }

    /** a tap on a row: shows it, or, on the one shown, ends the split (desktop: a second click turns it off) */
    fun select(s: SnapshotsState, id: Int): SnapshotsState {
        if (s.list.none { it.id == id }) return s
        val sel = if (s.selected == id) null else id
        return s.copy(selected = sel, preview = null, region = null, previewKey = null)
    }

    fun delete(s: SnapshotsState, id: Int): SnapshotsState {
        val list = s.list.filter { it.id != id }
        return if (s.selected == id) s.copy(list = list, selected = null, preview = null, region = null, previewKey = null)
        else s.copy(list = list)
    }

    /** l1's compare button: the split with the newest snapshot, or off again */
    fun toggleLast(s: SnapshotsState): SnapshotsState {
        val last = s.list.lastOrNull() ?: return s
        return if (s.selected != null) s.copy(selected = null, preview = null, region = null, previewKey = null)
        else s.copy(selected = last.id, preview = null, region = null, previewKey = null)
    }

    fun moveDivider(s: SnapshotsState, position: Float): SnapshotsState =
        s.copy(split = s.split.copy(position = position.coerceIn(MIN_POSITION, MAX_POSITION)))

    /** desktop's rotate icon on the divider: vertical and horizontal in turn */
    fun rotate(s: SnapshotsState): SnapshotsState = s.copy(split = s.split.copy(vertical = !s.split.vertical))

    fun switchSides(s: SnapshotsState): SnapshotsState = s.copy(split = s.split.copy(snapshotFirst = !s.split.snapshotFirst))

    /**
     * After a change: keep the snapshots whose history up to their position is still the engine's. [before] is the
     * history before the change: when a change (not a [navigation], a tap on a history item) was made with the end
     * below the top, the items above that end were discarded, and a snapshot above it is gone even if the new items
     * look the same in history(). After a compress every position means something else: [compressed] drops them all.
     */
    fun prune(s: SnapshotsState, before: History?, after: History, compressed: Boolean = false, navigation: Boolean = false): SnapshotsState {
        val cut = before?.takeIf { !navigation && it.end < it.items.size && it != after }?.end
        val keep = if (compressed) emptyList() else s.list.filter { sn ->
            sn.historyEnd <= after.items.size && after.items.take(sn.historyEnd) == sn.prefix && (cut == null || sn.historyEnd <= cut)
        }
        if (keep.size == s.list.size) return s
        val sel = s.selected?.takeIf { id -> keep.any { it.id == id } }
        return if (sel == s.selected) s.copy(list = keep)
        else s.copy(list = keep, selected = null, preview = null, region = null, previewKey = null)
    }

    /** which side of the divider a point of the photo view lies on: true for the snapshot's side */
    fun onSnapshotSide(split: SplitState, fx: Float, fy: Float): Boolean {
        val f = if (split.vertical) fx else fy
        return if (split.snapshotFirst) f < split.position else f >= split.position
    }
}

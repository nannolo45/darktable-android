// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.History
import app.dtandroid.engine.api.ImageInfo
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.api.UndoState
import app.dtandroid.engine.api.StoredLayout
import app.dtandroid.schema.layout.Layout
import app.dtandroid.schema.layout.QuickAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One photo of the series the darkroom was opened with, in the caller's order. */
data class SeriesItem(
    val fileName: String,
    val rawPath: String,
    /** where this photo's sidecar lives; it may not exist yet */
    val xmpPath: String,
)

data class Series(val name: String, val items: List<SeriesItem>)

/**
 * Brief P2e: where photos of the series come from when they are not on the phone yet (LightPhotoLibrary).
 * The darkroom asks [prepare] before opening a photo whose RAW file does not exist, and tells the
 * source when it opened and left a photo. Without a source every photo is a local file (P2b).
 */
interface PhotoSource {
    /**
     * Make [item]'s files ready (download them) and return the item with its final paths. [progress]
     * takes a short text to show ("downloading NAME… 40 %") and the share done (0..1) when known (brief
     * P2f: a thin progress bar). Throws [PhotoSourceException] with a text for the user when the photo
     * cannot be opened now.
     */
    suspend fun prepare(item: SeriesItem, progress: (String, Float?) -> Unit): SeriesItem

    /** The darkroom opened [item]; [next] is the photo after it in the series (worth fetching ahead). */
    fun opened(item: SeriesItem, next: SeriesItem?) {}

    /** The darkroom left [item] after writing its edit to its sidecar. */
    fun left(item: SeriesItem) {}
}

class PhotoSourceException(message: String) : Exception(message)

/** A module instance as the list addresses it. */
data class InstanceKey(val op: String, val priority: Int)

val ModuleInstance.key get() = InstanceKey(op, priority)

/**
 * What the module list shows, as darktable's module groups bar
 * (`src/libs/modulegroups.c`): the active modules, the quick access panel, one
 * of the layout's groups (by position: names may repeat), or, after tapping the
 * selected tab again, all modules of all groups (`DT_MODULEGROUP_NONE`).
 */
sealed interface GroupTab {
    data object Active : GroupTab
    data object Quick : GroupTab
    data object AllModules : GroupTab
    data class Group(val index: Int) : GroupTab
}

enum class BottomView { MODULES, HISTORY }

/** A menu or sheet over the screen. */
sealed interface Overlay {
    data class Instances(val key: InstanceKey) : Overlay
    data class Presets(val key: InstanceKey) : Overlay
    /** the entries of a list control */
    data class ListEntries(val key: InstanceKey, val controlId: Int) : Overlay
    /** exact numeric entry for a slider */
    data class NumberEntry(val key: InstanceKey, val controlId: Int) : Overlay
    data class Rename(val key: InstanceKey) : Overlay
    /** the layouts menu next to the group tabs: choose a layout, "manage module layouts…" */
    data object Layouts : Overlay
    /** the app's own photo menu ("more" in the top bar): export, save XMP, timings (brief P2b) */
    data object HostMenu : Overlay
    /** brief P3b: the styles list ("styles…" in the photo menu); its pages are [StylesPresets]' */
    data object Styles : Overlay
}

/** An entry of the app's photo menu, given to the darkroom screen by the app (brief P2b). */
data class HostAction(val label: String, val enabled: Boolean = true, val run: () -> Unit)

/** A rendered picture and where it sits in the processed image at its scale. */
class PhotoLayer(val image: RenderedImage, val x: Int, val y: Int, val scale: Double)

/** Everything the darkroom screens show. */
data class DarkroomState(
    val loading: Boolean = true,
    val series: Series = Series("", emptyList()),
    val index: Int = 0,
    val info: ImageInfo? = null,
    val readOnlyReason: String? = null,
    val modules: List<ModuleInstance> = emptyList(),
    val history: History = History(0, emptyList()),
    val undo: UndoState = UndoState(false, false),
    /** values read through module_action for controls bound to an action, by instance */
    val actionValues: Map<InstanceKey, JsonObject> = emptyMap(),
    val preview: PhotoLayer? = null,
    val region: PhotoLayer? = null,
    /** Brief P1b part 2: [region] is a region of the crop tool's uncropped picture (drawn only while the tool shows it) */
    val regionOfCropTool: Boolean = false,
    /** Brief P2i: the sharp region of a zoomed-in view is being rendered (the screen says "rendering…") */
    val regionPending: Boolean = false,
    val beforePreview: PhotoLayer? = null,
    val before: Boolean = false,
    val thumbnails: Map<Int, RenderedImage> = emptyMap(),
    val filmstrip: Boolean = false,
    val group: GroupTab = GroupTab.Active,
    /** the current module layout's name and content (PLAN.md 3.9) */
    val layoutName: String = "",
    val layout: Layout = Layout(),
    /** every layout, for the layouts menu */
    val layoutList: List<StoredLayout> = emptyList(),
    /** the layout's quick access entries resolved against the control schema, in the layout's order */
    val quick: List<QuickAccess.Entry> = emptyList(),
    /** the search line's text (only when the layout shows the search line) */
    val search: String = "",
    /** a module shown alone because "go to the full module" found it in no group */
    val forceShow: InstanceKey? = null,
    /** "manage module layouts" is open */
    val layoutEditor: Boolean = false,
    val expanded: InstanceKey? = null,
    val view: BottomView = BottomView.MODULES,
    val overlay: Overlay? = null,
    /** last engine error, shown until the next action */
    val message: String? = null,
    /**
     * Brief P2f: the best picture already available while the photo opens or its preview renders (its
     * last picture or thumbnail, kept from an earlier visit), shown scaled instead of a black frame.
     */
    val placeholder: RenderedImage? = null,
    /** Brief P2f: what the darkroom is doing for the photo, in plain words ("downloading… 40 %", "opening", "rendering"). */
    val working: String? = null,
    /** Brief P2f: the share done of [working] (0..1) when known, for a thin progress bar. */
    val workingProgress: Float? = null,
    /** Brief P2f (R16): the open photo's sync step in plain words while it runs ("syncing: sending the edit"), set by the app */
    val syncStep: String? = null,
    val syncProgress: Float? = null,
    /** Brief P2f (R15): the open photo's last edit in one line, set by the app; null = nothing known */
    val lastEdit: String? = null,
    /** Brief P3a: the crop tool on the photo while crop's panel is open; null when it is not open */
    val cropTool: CropToolState? = null,
    /** Brief P3c: the colour picker armed on a control of the open module; null when none is */
    val picker: PickerState? = null,
    /**
     * Brief P3c: the pickers' settings by key (exposure's target lightness, ...), as desktop keeps them in its
     * configuration: for the app's run, every photo; a key not here has its default.
     */
    val pickerSettings: Map<String, JsonPrimitive> = emptyMap(),
    /** Brief P3c: the page chosen in a module's tabs, by instance and tabs control (a picker reads its channel there) */
    val tabs: Map<Pair<InstanceKey, Int>, Int> = emptyMap(),
) {
    val current: SeriesItem? get() = series.items.getOrNull(index)
    val readOnly: Boolean get() = info?.readOnly == true
}

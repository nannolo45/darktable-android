// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.History
import app.dtandroid.engine.api.ImageInfo
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.api.UndoState
import kotlinx.serialization.json.JsonObject

/** One photo of the series the darkroom was opened with, in the caller's order. */
data class SeriesItem(
    val fileName: String,
    val rawPath: String,
    /** where this photo's sidecar lives; it may not exist yet */
    val xmpPath: String,
)

data class Series(val name: String, val items: List<SeriesItem>)

/** A module instance as the list addresses it. */
data class InstanceKey(val op: String, val priority: Int)

val ModuleInstance.key get() = InstanceKey(op, priority)

sealed interface GroupTab {
    data object Active : GroupTab
    data object Quick : GroupTab
    data class Configured(val id: String) : GroupTab
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
}

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
    val beforePreview: PhotoLayer? = null,
    val before: Boolean = false,
    val thumbnails: Map<Int, RenderedImage> = emptyMap(),
    val filmstrip: Boolean = false,
    val group: GroupTab = GroupTab.Active,
    val expanded: InstanceKey? = null,
    val view: BottomView = BottomView.MODULES,
    val overlay: Overlay? = null,
    /** last engine error, shown until the next action */
    val message: String? = null,
) {
    val current: SeriesItem? get() = series.items.getOrNull(index)
    val readOnly: Boolean get() = info?.readOnly == true
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.engine.api.PickStats
import kotlinx.serialization.json.JsonPrimitive

/**
 * Brief P3c deliverable 3: pickers that only set sliders from the picked values (override `picker` with
 * `set_slider`). Each formula repeats one module's `color_picker_apply` branch on the statistics `color_pick` gives
 * for the module's input (or output), in the module's picker colour space; [roles] names the parameters it writes.
 */
object PickerFormulas {
    val roles: Map<String, Set<String>> = mapOf()

    /** The parameters [formula] writes for the picked [stats], by parameter name; empty when the pick gives nothing. */
    fun apply(spec: PickerSlider, stats: PickStats?): Map<String, JsonPrimitive> {
        stats ?: return emptyMap()
        return emptyMap()
    }
}

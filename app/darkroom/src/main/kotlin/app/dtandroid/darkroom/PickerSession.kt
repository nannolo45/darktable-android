// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.schema.PickSelection

/**
 * Brief P3c: the colour picker armed on a control ([key], [controlId]), as desktop's `dt_iop_color_picker_t` with
 * its sample: one at a time, for the module whose panel is open.
 *
 * [selection] is what the photo shows, in fractions of the picture (normalised processed-image coordinates). The
 * controller keeps the same selection in original-image coordinates (engine-api.md 6.5, desktop keeps its picker
 * box so too), so it stays on the same part of the picture when the edit changes the geometry; it maps it back
 * after such a change. [readout] holds the lines under the photo: the picked values as desktop's global colour
 * picker shows them (deliverable 4).
 */
data class PickerState(
    val key: InstanceKey,
    val controlId: Int,
    val selection: PickSelection?,
    /** a finger is on the selection (or drawing a new one) */
    val dragging: Boolean = false,
    val readout: List<String> = emptyList(),
    /** the selection lies outside the picture (the edit's geometry moved it away) */
    val outside: Boolean = false,
)

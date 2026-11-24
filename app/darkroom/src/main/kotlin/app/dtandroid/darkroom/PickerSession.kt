// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.PickStats
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
    /** brief P3e-b: the picked area's statistics at the module's input, in the module's picker space (colour zones: LCh) */
    val moduleInput: PickStats? = null,
    /** picks finished since the picker was armed (a gesture's pick has landed when this has grown; tests wait on it) */
    val picks: Int = 0,
)

/**
 * Where a finger is on the picture: the view of [viewW] x [viewH] pixels shows the picture of [iw] x [ih] (its size
 * at scale 1) at [fit] x [zoom] pixels per picture pixel, centred on ([cx], [cy]) (fractions of the picture). Returns
 * fractions of the picture (outside 0..1 beside it). The crop tool and the colour picker take touches through this,
 * so a selection lands on the same part of the picture at any zoom.
 */
object PhotoGeometry {
    fun picturePoint(x: Float, y: Float, viewW: Int, viewH: Int, iw: Int, ih: Int, fit: Float, zoom: Float, cx: Float, cy: Float): Pair<Float, Float> {
        val ds = fit * zoom
        val left = viewW / 2f - cx * iw * ds
        val top = viewH / 2f - cy * ih * ds
        return ((x - left) / (iw * ds)) to ((y - top) / (ih * ds))
    }

    /** The view pixel where picture fraction ([fx], [fy]) is shown: the inverse of [picturePoint]. */
    fun viewPoint(fx: Float, fy: Float, viewW: Int, viewH: Int, iw: Int, ih: Int, fit: Float, zoom: Float, cx: Float, cy: Float): Pair<Float, Float> {
        val ds = fit * zoom
        val left = viewW / 2f - cx * iw * ds
        val top = viewH / 2f - cy * ih * ds
        return (left + fx * iw * ds) to (top + fy * ih * ds)
    }
}

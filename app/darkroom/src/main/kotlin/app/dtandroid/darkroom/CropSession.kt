// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.schema.CropAspect
import app.dtandroid.schema.CropTool

/**
 * Brief P3a: the crop tool while it is open (crop's panel expanded on an editable photo).
 *
 * As on desktop while crop has the focus, the frame lives here and not in the edit: [clip] is desktop's
 * `g->clip_*`, [ratioD] / [ratioN] the ratio the aspect list or the flip button chose. The edit in the engine is
 * the one from before the tool until the tool is left ([DarkroomController] writes the frame then, one history
 * item, or none when nothing changed). [picture] is the uncropped picture (crop's input), rendered once when the
 * tool opens with crop left out of that render (brief P1b part 2: `bypass`, nothing in the edit changes); [inW] x
 * [inH] is its size at scale 1, the size the frame's fractions refer to.
 */
data class CropToolState(
    val key: InstanceKey,
    val picture: PhotoLayer?,
    val inW: Int,
    val inH: Int,
    val clip: CropTool.Clip,
    val ratioD: Int,
    val ratioN: Int,
    /** the frame or the ratio was changed by the user since the tool opened */
    val touched: Boolean = false,
    /** a finger is on the frame */
    val dragging: Boolean = false,
    /**
     * Brief P3a deliverable 4: the rotation slider's value while a finger moves it (rotate and perspective's
     * `rotation`, degrees); the picture is shown turned by the difference until the engine's picture arrives.
     */
    val rotationLive: Double? = null,
    /**
     * Brief P1b part 2: the render options of [picture] (crop left out when crop is on); the sharp region of a
     * zoomed-in view uses the same.
     */
    val options: RenderOptions = RenderOptions.NONE,
    /** "straighten" is on: a one-finger drag on the photo draws the line instead of moving the frame */
    val straighten: Boolean = false,
    /** the straightening line being drawn: x0, y0, x1, y1 as fractions of the picture */
    val line: List<Float>? = null,
) {
    /** The parameters the frame would be written as now (desktop's `_commit_box`). */
    fun committed(): CropAspect.Frame {
        val (d, n) = CropAspect.effective(ratioD, ratioN)
        return CropTool.commit(clip, d, n, inW, inH)
    }

    /** The cropped picture's size in pixels, as desktop shows it while dragging (`gui_post_expose`). */
    fun outputSize(): Pair<Int, Int> {
        val (d, n) = CropAspect.effective(ratioD, ratioN)
        return CropTool.shownSize(clip, d, n, inW, inH)
    }
}

/** Brief P3a: where the photo's view is in its window, in pixels (emulator tests touch the photo through it). */
data class PhotoArea(val left: Float, val top: Float, val width: Float, val height: Float)

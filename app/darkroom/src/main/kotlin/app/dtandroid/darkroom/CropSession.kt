// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.schema.CropAspect
import app.dtandroid.schema.CropTool

/**
 * Brief P3a: the crop tool while it is open (crop's panel expanded on an editable photo).
 *
 * As on desktop while crop has the focus, the frame lives here and not in the edit: [clip] is desktop's
 * `g->clip_*`, [ratioD] / [ratioN] the ratio the aspect list or the flip button chose. The edit in the engine is
 * the one from before the tool until the tool is left ([DarkroomController] writes the frame then, one history
 * item, or none when nothing changed). [picture] is the uncropped picture (crop's input), rendered once when the
 * tool opens; [inW] x [inH] is its size at scale 1, the size the frame's fractions refer to.
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
    /** a finger is on the frame (the screen shows the output size while it is) */
    val dragging: Boolean = false,
) {
    /** The parameters the frame would be written as now (desktop's `_commit_box`). */
    fun committed(): CropAspect.Frame {
        val (d, n) = CropAspect.effective(ratioD, ratioN)
        return CropTool.commit(clip, d, n, inW, inH)
    }

    /** The cropped picture's size in pixels if the tool were left now. */
    fun outputSize(): Pair<Int, Int> = CropTool.outputSize(committed(), inW, inH)
}

/** Brief P3a: where the photo's view is in its window, in pixels (emulator tests touch the photo through it). */
data class PhotoArea(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Brief P3a: the redo step the crop tool's bracket leaves behind is never offered.
 *
 * The bracket (switch crop off, render, `undo()`) puts the switched-off state on the engine's redo queue. That
 * entry is at the bottom of the queue: user undos push real states above it, user redos take them off again.
 * Redo is allowed while real states are above it; the engine empties the queue on any other change, and then
 * the guard has nothing left to guard ([engineRedoEmpty]).
 */
class RedoGuard {
    /** the bottom of the engine's redo queue is the bracket's state */
    var poisoned = false
        private set

    /** real states above it */
    var real = 0
        private set

    /** The bracket ran: the redo queue is exactly its one state. */
    fun bracketed() { poisoned = true; real = 0 }

    /** The user's undo pushed a real state onto the redo queue. */
    fun undone() { if (poisoned) real++ }

    /** The user's redo took one off. */
    fun redone() { if (poisoned && real > 0) real-- }

    /** The engine reports an empty redo queue (any change empties it). */
    fun engineRedoEmpty() { poisoned = false; real = 0 }

    /** Whether redo may run when the engine says it can. */
    fun mayRedo(): Boolean = !poisoned || real > 0
}

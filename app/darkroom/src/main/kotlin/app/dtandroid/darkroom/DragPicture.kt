// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The size of the picture that follows a finger on a slider (PLAN.md 3.9, brief P2b), chosen from
 * measurements on the device, not guessed.
 *
 * Every preview render reports its pixel count and time ([record]); the average cost per pixel is
 * kept (exponential average, so the estimate follows the phone's current speed: thermal limits,
 * other apps). At the start of a drag, [size] gives the longest side whose render is expected to
 * take [targetMs]: side = sqrt(targetMs / cost per pixel * aspect), rounded down to [step] pixels
 * and kept between [minSize] (sharp enough to judge a change on a phone screen) and [maxSize] (the
 * full preview). The size stays fixed for the whole drag, because the pipeline caches each size
 * separately and a size change in mid-drag would recompute the modules before the changed one.
 *
 * The cost per pixel includes the fixed part of a render, so small sizes are estimated a little
 * high; the error is on the safe side (a slightly smaller picture). The timings page shows what was
 * measured and chosen.
 */
class DragPicture(
    val targetMs: Double = 50.0,
    val minSize: Int = 320,
    val maxSize: Int = 1024,
    val step: Int = 32,
    /** longer side / shorter side of typical photos (3:2) */
    private val aspect: Double = 1.5,
    /** weight of a new measurement in the average */
    private val weight: Double = 0.3,
) {
    @Volatile var nsPerPixel: Double? = null
        private set

    @Volatile var lastSize: Int? = null
        private set

    @Volatile var lastDragMs: Double? = null
        private set

    /** Drag renders measured so far. */
    @Volatile var dragRenders = 0
        private set

    /** A preview render of [pixels] took [ns]; [drag] for the drag picture itself. */
    @Synchronized
    fun record(pixels: Int, ns: Long, drag: Boolean = false) {
        if (pixels <= 0 || ns <= 0) return
        val v = ns.toDouble() / pixels
        nsPerPixel = nsPerPixel?.let { it * (1 - weight) + v * weight } ?: v
        if (drag) {
            lastDragMs = ns / 1e6
            dragRenders++
        }
    }

    /** The drag picture's longest side for the next drag. */
    @Synchronized
    fun size(): Int {
        val c = nsPerPixel
        val s = if (c == null) minSize else {
            val pixels = targetMs * 1e6 / c
            val side = sqrt(pixels * aspect)
            (floor(side / step) * step).toInt().coerceIn(minSize, maxSize)
        }
        lastSize = s
        return s
    }

    fun describe(): String {
        val c = nsPerPixel ?: return "no render measured yet"
        return "%.1f ns per pixel; drag picture %s px for a %.0f ms target".format(c, lastSize?.toString() ?: "-", targetMs)
    }
}

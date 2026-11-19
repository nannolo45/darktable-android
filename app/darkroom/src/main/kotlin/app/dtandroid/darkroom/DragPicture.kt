// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The size of the picture that follows a finger on a slider (PLAN.md 3.9, brief P2b), chosen from
 * measurements on the device, not guessed.
 *
 * Every preview render reports its pixel count and time ([record]). The last [window] renders are
 * fitted with a straight line, time = fixed + pixels x cost (least squares), because a render has a
 * fixed part (the pipeline's set-up per run, about 70 ms on the CI emulator) and a part that grows with
 * the pixels. At the start of a drag, [size] gives the longest side whose render is expected to take
 * [targetMs]: pixels = (target - fixed) / cost, side = sqrt(pixels x aspect), rounded down to [step]
 * pixels and kept between [minSize] (sharp enough to judge a change on a phone screen) and [maxSize]
 * (the full preview). When the fixed part alone exceeds the target, the smallest size is taken. With
 * renders of one size only, the line cannot be fitted and the cost per pixel includes the fixed part
 * (a smaller picture: the safe side). The size stays fixed for the whole drag, because the pipeline
 * caches each size separately and a change in mid-drag would recompute the modules before the changed
 * one. The timings page shows what was measured and chosen.
 */
class DragPicture(
    val targetMs: Double = 50.0,
    val minSize: Int = 320,
    val maxSize: Int = 1024,
    val step: Int = 32,
    /** longer side / shorter side of typical photos (3:2) */
    private val aspect: Double = 1.5,
    /** how many recent renders the fit uses */
    private val window: Int = 12,
) {
    private val samples = ArrayDeque<Pair<Double, Double>>() // pixels, ns

    /** fitted cost per pixel (ns); null before the first render */
    @Volatile var nsPerPixel: Double? = null
        private set

    /** fitted fixed part of a render (ns) */
    @Volatile var fixedNs: Double = 0.0
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
        samples.addLast(pixels.toDouble() to ns.toDouble())
        while (samples.size > window) samples.removeFirst()
        fit()
        if (drag) {
            lastDragMs = ns / 1e6
            dragRenders++
        }
    }

    private fun fit() {
        val n = samples.size
        val mx = samples.sumOf { it.first } / n
        val my = samples.sumOf { it.second } / n
        val sxx = samples.sumOf { (it.first - mx) * (it.first - mx) }
        val sxy = samples.sumOf { (it.first - mx) * (it.second - my) }
        // a line needs renders of clearly different sizes, and a positive slope
        val slope = if (sxx > 1e-6 * mx * mx * n && sxy > 0) sxy / sxx else 0.0
        if (slope > 0) {
            nsPerPixel = slope
            fixedNs = maxOf(0.0, my - slope * mx)
        } else {
            nsPerPixel = my / mx
            fixedNs = 0.0
        }
    }

    /** The drag picture's longest side for the next drag. */
    @Synchronized
    fun size(): Int {
        val c = nsPerPixel
        val budget = targetMs * 1e6 - fixedNs
        val s = if (c == null || budget <= 0) minSize else {
            val side = sqrt(budget / c * aspect)
            (floor(side / step) * step).toInt().coerceIn(minSize, maxSize)
        }
        lastSize = s
        return s
    }

    fun describe(): String {
        val c = nsPerPixel ?: return "no render measured yet"
        return "fit: %.1f ms fixed + %.1f ns per pixel; drag picture %s px for a %.0f ms target".format(fixedNs / 1e6, c, lastSize?.toString() ?: "-", targetMs)
    }
}

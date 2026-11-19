// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A slider track's colour stops as desktop darktable paints them (src/bauhaus/bauhaus.c,
 * _draw_baseline): a linear gradient over the slider's width, each stop at its position in the hard
 * range mapped to the shown range ((position - offset) / zoom, zoom and offset from the shown and
 * the hard range), the colour as given at the alpha token "alpha.sliderGradient". Outside the first
 * and last stop the end colours continue, as cairo's default pad extend does.
 *
 * The colours are data from the control schema, not theme colours (CLAUDE.md rule 5): this is the
 * one place that turns them into a [Brush], so that screen code does not construct colours.
 */
object SliderGradient {
    /** One stop: position in 0..1 of the hard range, and 0..1 RGB. */
    class Stop(val position: Double, val red: Double, val green: Double, val blue: Double)

    /**
     * The brush for a track [widthPx] wide showing [shownMin]..[shownMax] of [hardMin]..[hardMax];
     * null without stops.
     */
    fun brush(
        stops: List<Stop>,
        shownMin: Double,
        shownMax: Double,
        hardMin: Double,
        hardMax: Double,
        widthPx: Float,
        alpha: Float,
    ): Brush? {
        if (stops.isEmpty() || widthPx <= 0f) return null
        fun color(s: Stop) = Color(s.red.toFloat().coerceIn(0f, 1f), s.green.toFloat().coerceIn(0f, 1f),
            s.blue.toFloat().coerceIn(0f, 1f), alpha)
        if (stops.size == 1) return Brush.horizontalGradient(listOf(color(stops[0]), color(stops[0])))
        val hard = hardMax - hardMin
        val zoom = if (hard != 0.0) (shownMax - shownMin) / hard else 1.0
        val offset = if (hard != 0.0) (shownMin - hardMin) / hard else 0.0
        // desktop adds the stops in the order set; cairo sorts them by offset (stable)
        val sorted = stops.sortedBy { it.position }
        val x = sorted.map { (it.position - offset) / (if (zoom != 0.0) zoom else 1.0) }
        val x0 = x.first()
        val x1 = x.last()
        if (x1 <= x0) return Brush.horizontalGradient(listOf(color(sorted.last()), color(sorted.last())))
        val colorStops = sorted.indices.map { i -> ((x[i] - x0) / (x1 - x0)).toFloat() to color(sorted[i]) }
        return Brush.horizontalGradient(
            *colorStops.toTypedArray(),
            startX = (x0 * widthPx).toFloat(),
            endX = (x1 * widthPx).toFloat(),
        )
    }
}

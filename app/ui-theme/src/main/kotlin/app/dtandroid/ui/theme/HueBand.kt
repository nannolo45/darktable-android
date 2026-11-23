// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Brief P3e-b: the strip under a band editor's axis. The colours are data (what an axis position looks like), not
 * theme colours, as the slider gradients are (CLAUDE.md rule 5): this is the one place that turns them into
 * a [Brush], so that screen code does not construct colours. The grey ramp of a lightness or chroma axis takes its
 * ends from the tokens.
 */
object HueBand {
    /** Lightness and chroma of the hue strip: a middle grey's lightness and a moderate chroma, as desktop's colour zones paints its hue background. */
    const val STRIP_L = 65.0
    const val STRIP_C = 40.0

    /** sRGB (0..1 each) of the CIE LCh(ab) colour [l], [c], [hDeg] with the D65 white point, clipped into gamut. */
    fun lchToSrgb(l: Double, c: Double, hDeg: Double): DoubleArray {
        val h = Math.toRadians(hDeg)
        val a = c * cos(h)
        val b = c * sin(h)
        val fy = (l + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0
        fun inv(t: Double) = if (t * t * t > 216.0 / 24389.0) t * t * t else (116.0 * t - 16.0) * 27.0 / 24389.0
        val x = 0.95047 * inv(fx)
        val y = inv(fy)
        val z = 1.08883 * inv(fz)
        val lr = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val lg = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val lb = 0.0557 * x - 0.2040 * y + 1.0570 * z
        fun gamma(u: Double): Double {
            val v = u.coerceIn(0.0, 1.0)
            return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
        }
        return doubleArrayOf(gamma(lr), gamma(lg), gamma(lb))
    }

    /** The colour at fraction [x] of a hue axis that runs once round the circle (colour zones' hue, LCh hue 0..1). */
    fun hueAt(x: Double): DoubleArray = lchToSrgb(STRIP_L, STRIP_C, x * 360.0)

    /** A strip of [steps] steps over a hue axis, as a brush over [widthPx]; [alpha] from the tokens. */
    fun hueBrush(widthPx: Float, alpha: Float, steps: Int = 24): Brush {
        val stops = (0..steps).map { i ->
            val x = i.toDouble() / steps
            val rgb = hueAt(x)
            x.toFloat() to Color(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat(), alpha)
        }
        return Brush.horizontalGradient(*stops.toTypedArray(), startX = 0f, endX = widthPx)
    }

    /**
     * A strip through colours given at positions (0..1 over the strip, in order), for example the colours the schema's
     * saturation sliders end in: color equalizer's nodes. Outside the first and last position the end colours continue.
     */
    fun stopsBrush(positions: List<Double>, rgb: List<DoubleArray>, widthPx: Float, alpha: Float): Brush? {
        if (positions.isEmpty() || positions.size != rgb.size || widthPx <= 0f) return null
        val colors = rgb.map { Color(it[0].toFloat().coerceIn(0f, 1f), it[1].toFloat().coerceIn(0f, 1f), it[2].toFloat().coerceIn(0f, 1f), alpha) }
        if (positions.size == 1) return Brush.horizontalGradient(listOf(colors[0], colors[0]), startX = 0f, endX = widthPx)
        val first = positions.first()
        val last = positions.last()
        if (last <= first) return Brush.horizontalGradient(listOf(colors.last(), colors.last()), startX = 0f, endX = widthPx)
        val stops = positions.indices.map { i -> ((positions[i] - first) / (last - first)).toFloat() to colors[i] }
        return Brush.horizontalGradient(*stops.toTypedArray(), startX = (first * widthPx).toFloat(), endX = (last * widthPx).toFloat())
    }

    /** The grey ramp of a lightness or chroma axis, from the band tokens. */
    fun rampBrush(band: DtBand, widthPx: Float): Brush =
        Brush.horizontalGradient(listOf(band.rampDark.copy(alpha = band.axisAlpha), band.rampLight.copy(alpha = band.axisAlpha)), startX = 0f, endX = widthPx)
}

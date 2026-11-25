// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Brief P3m: colours that are data, not theme (a colour look up table patch's Lab colour, retouch's fill colour), and
 * a filled outline for retouch's "display masks". As [HueBand], this is the one place that turns such data into
 * [Color]s and paths, so screen code constructs neither (CLAUDE.md rule 5).
 */
object DataDraw {
    /** An sRGB colour (each 0..1, clipped). */
    fun rgb(r: Double, g: Double, b: Double): Color =
        Color(r.coerceIn(0.0, 1.0).toFloat(), g.coerceIn(0.0, 1.0).toFloat(), b.coerceIn(0.0, 1.0).toFloat())

    /** The CIE Lab colour (D50 as darktable's patches, shown as sRGB as desktop's dt_Lab_to_XYZ / dt_XYZ_to_sRGB). */
    fun lab(l: Double, a: Double, b: Double): Color {
        val c = hypot(a, b)
        val h = Math.toDegrees(atan2(b, a))
        val v = HueBand.lchToSrgb(l, c, h)
        return rgb(v[0], v[1], v[2])
    }

    /** A closed polygon through [pts] filled with [color] at [alpha]. */
    fun fillPolygon(scope: DrawScope, pts: List<Offset>, color: Color, alpha: Float) {
        if (pts.size < 3) return
        val p = Path()
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
        p.close()
        scope.drawPath(p, color, alpha = alpha)
    }
}

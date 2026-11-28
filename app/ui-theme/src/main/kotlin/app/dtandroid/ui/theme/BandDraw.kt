// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate

/**
 * Brief P3e-b: what the band editor's graph draws. Coordinates are fractions of the graph (x to the right, y up,
 * both 0..1). Screen code builds a [BandFrame]; this file turns it into strokes with the band tokens (screens do not
 * draw lines themselves, CLAUDE.md rule 5).
 */
class BandLine(
    /** x, y, x, y, ... */
    val points: FloatArray,
    /** the second curve of a tab (drawn in the second colour) */
    val second: Boolean = false,
    /** brief P6f: a channel's own colour (a token the screen passes), else the band's curve colour */
    val color: androidx.compose.ui.graphics.Color? = null,
    /** brief P6f: another channel's curve, drawn faint behind (desktop: alpha 0.3) */
    val faded: Boolean = false,
)

class BandNodes(
    val xs: FloatArray,
    val ys: FloatArray,
    val second: Boolean = false,
    /** the node under the finger, or -1 */
    val selected: Int = -1,
    /** a short bar from the neutral line to the node (colour equalizer's) */
    val bars: Boolean = false,
    /** brief P6f: the channel's own colour, else the band's curve colour */
    val color: androidx.compose.ui.graphics.Color? = null,
)

/** The soft brush: its centre and radius (a fraction of the graph's width). */
class BandBrushCircle(val x: Float, val y: Float, val radius: Float)

/** A picked value: a line at [x], with a shaded range [from]..[to] when the pick was an area. */
class BandMark(val x: Float, val from: Float? = null, val to: Float? = null)

class BandFrame(
    val lines: List<BandLine>,
    val nodes: List<BandNodes>,
    val gridX: Int = 8,
    val gridY: Int = 4,
    /** y of the neutral line, or null */
    val baseY: Float? = 0.5f,
    val brush: BandBrushCircle? = null,
    val marks: List<BandMark> = emptyList(),
    /** where a hue band's nodes' x positions can be taken (contrast equalizer's strip): markers under the graph */
    val stripXs: FloatArray? = null,
    /** the node of [stripXs] under the finger, or -1 */
    val stripSelected: Int = -1,
)

object BandDraw {
    /** the opacity of another channel's curve (rawdenoise.c, denoiseprofile.c: 0.3) */
    const val FADED_ALPHA = 0.3f

    /**
     * Draws [f] into the area of [scope] inside an [insetPx] margin all round. [strip] is the height of the marker
     * strip under the graph (0 for none): the graph is shorter by that much.
     */
    fun draw(scope: DrawScope, band: DtBand, f: BandFrame, insetPx: Float, stripPx: Float) = with(scope) {
        val gw = size.width - 2 * insetPx
        val gh = size.height - 2 * insetPx - stripPx
        if (gw <= 0f || gh <= 0f) return@with
        fun px(x: Float) = insetPx + x * gw
        fun py(y: Float) = insetPx + (1f - y) * gh
        drawRect(band.bg, Offset(insetPx, insetPx), Size(gw, gh))
        val grid = band.gridLine.toPx()
        for (i in 1 until f.gridX) drawLineBetween(band.grid, Offset(px(i / f.gridX.toFloat()), py(0f)), Offset(px(i / f.gridX.toFloat()), py(1f)), grid)
        for (i in 1 until f.gridY) drawLineBetween(band.grid, Offset(px(0f), py(i / f.gridY.toFloat())), Offset(px(1f), py(i / f.gridY.toFloat())), grid)
        f.baseY?.let { drawLineBetween(band.base, Offset(px(0f), py(it)), Offset(px(1f), py(it)), band.curveLine.toPx() / 2f) }
        for (m in f.marks) {
            if (m.from != null && m.to != null) {
                val x0 = px(minOf(m.from, m.to))
                val x1 = px(maxOf(m.from, m.to))
                drawRect(band.markRange.copy(alpha = band.markRangeAlpha), Offset(x0, py(1f)), Size(x1 - x0, gh))
            }
            drawLineBetween(band.mark, Offset(px(m.x), py(0f)), Offset(px(m.x), py(1f)), band.markLine.toPx())
        }
        f.brush?.let { b ->
            // the circle's radius is a fraction of the graph's width
            drawCircleOutline(band.brush.copy(alpha = band.brushAlpha), Offset(px(b.x), py(b.y)), b.radius * gw, band.markLine.toPx())
        }
        for (l in f.lines.sortedBy { if (it.faded) 0 else 1 }) {
            if (l.points.size < 4) continue
            val path = Path()
            path.moveTo(px(l.points[0]), py(l.points[1]))
            var i = 2
            while (i + 1 < l.points.size) { path.lineTo(px(l.points[i]), py(l.points[i + 1])); i += 2 }
            val c = l.color ?: if (l.second) band.curveSecond else band.curve
            drawPath(path, if (l.faded) c.copy(alpha = FADED_ALPHA) else c, style = Stroke(width = band.curveLine.toPx() * (if (l.faded) 0.75f else 1f), cap = StrokeCap.Round))
        }
        for (n in f.nodes) {
            val color = n.color ?: if (n.second) band.curveSecond else band.curve
            for (k in n.xs.indices) {
                val cx = px(n.xs[k])
                val cy = py(n.ys[k])
                if (n.bars) drawLineBetween(color, Offset(cx, py(f.baseY ?: 0.5f)), Offset(cx, cy), band.node.toPx() * 1.2f)
                val sel = k == n.selected
                drawCircleFilled(if (sel) band.nodeActive else band.nodeFill, Offset(cx, cy), band.node.toPx() * (if (sel) 1.4f else 1f))
                drawCircleOutline(color, Offset(cx, cy), band.node.toPx() * (if (sel) 1.4f else 1f), band.curveLine.toPx() * 0.75f)
            }
        }
        f.stripXs?.let { xs ->
            if (stripPx > 0f) {
                val top = insetPx + gh + stripPx * 0.15f
                val h = stripPx * 0.6f
                for (k in xs.indices) {
                    val cx = px(xs[k])
                    val sel = k == f.stripSelected
                    // a small upward triangle, as desktop's x position markers
                    val t = Path()
                    t.moveTo(cx, top)
                    t.lineTo(cx - h * 0.6f, top + h)
                    t.lineTo(cx + h * 0.6f, top + h)
                    t.close()
                    drawPath(t, if (sel) band.nodeActive else band.base)
                }
            }
        }
    }

    /** The strip under the graph for a hue, lightness or chroma axis: [brush] over the graph's width, [heightPx] high, [insetPx] in from the sides. */
    fun drawAxis(scope: DrawScope, brush: Brush, insetPx: Float, heightPx: Float) = with(scope) {
        // translated, so that the brush's x positions count from the graph's left edge
        translate(left = insetPx, top = 0f) { drawRect(brush, Offset.Zero, Size(size.width - 2 * insetPx, heightPx)) }
    }

    private fun DrawScope.drawLineBetween(color: androidx.compose.ui.graphics.Color, a: Offset, b: Offset, width: Float) =
        drawLine(color, a, b, strokeWidth = width)

    private fun DrawScope.drawCircleFilled(color: androidx.compose.ui.graphics.Color, c: Offset, r: Float) =
        drawCircle(color, r, c)

    private fun DrawScope.drawCircleOutline(color: androidx.compose.ui.graphics.Color, c: Offset, r: Float, width: Float) =
        drawCircle(color, r, c, style = Stroke(width = width))
}

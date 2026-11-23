// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Brief P3e-b: the band editor's tokens (contrast equalizer, color zones, color equalizer): the graph and its
 * lines, the nodes, the soft brush circle, the picked-colour marks and the strip under a hue axis.
 */
class DtBand(t: TokenSource) {
    val bg: Color = t.color("color.bandBg")
    val grid: Color = t.color("color.bandGrid")
    /** the neutral line (no change) */
    val base: Color = t.color("color.bandBase")
    val curve: Color = t.color("color.bandCurve")
    /** the second curve of a tab (contrast equalizer's denoise spline) */
    val curveSecond: Color = t.color("color.bandCurveSecond")
    val nodeFill: Color = t.color("color.bandNodeFill")
    val nodeActive: Color = t.color("color.bandNodeActive")
    val brush: Color = t.color("color.bandBrush")
    val mark: Color = t.color("color.bandMark")
    val markRange: Color = t.color("color.bandMarkRange")
    /** the ends of the grey strip under a lightness or chroma axis */
    val rampDark: Color = t.color("color.bandRampDark")
    val rampLight: Color = t.color("color.bandRampLight")

    val height: Dp = t.dp("size.bandHeight")
    val curveLine: Dp = t.dp("size.bandCurveLine")
    val gridLine: Dp = t.dp("size.bandGridLine")
    /** the node's radius */
    val node: Dp = t.dp("size.bandNode")
    /** how near a finger has to land to take a node (radius) */
    val nodeHit: Dp = t.dp("size.bandNodeHit")
    /** the strip under a hue, lightness or chroma axis */
    val axis: Dp = t.dp("size.bandAxis")
    /** contrast equalizer's strip under the graph where the nodes' x positions are moved */
    val strip: Dp = t.dp("size.bandStrip")
    val markLine: Dp = t.dp("size.bandMarkLine")

    /** the brush circle's opacity */
    val brushAlpha: Float = t.number("alpha.bandBrush").toFloat()
    val axisAlpha: Float = t.number("alpha.bandAxis").toFloat()
    val markRangeAlpha: Float = t.number("alpha.bandMarkRange").toFloat()
}

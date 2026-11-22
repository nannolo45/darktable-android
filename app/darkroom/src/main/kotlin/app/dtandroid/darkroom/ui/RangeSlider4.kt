// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.RangeMath
import app.dtandroid.ui.theme.DtTheme
import kotlin.math.abs

/**
 * Brief P3d stage 2b: darktable's range slider with four markers. The two inner markers (filled) bound the full
 * opacity, the two outer ones (open) the zero opacity; the order is fixed: markers may touch, never cross. A drag
 * moves one marker and gives the markers at the finger's lift ([onCommit]: one history item); a double tap gives
 * the whole range ([RangeMath.FULL]). Touching markers: the direction of the first movement picks one of them (the one the finger moves toward).
 */
@Composable
fun RangeSlider4(
    values: List<Double>,
    enabled: Boolean,
    description: String,
    onCommit: (List<Double>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DtTheme.colors
    val z = DtTheme.sizes
    var moving by remember { mutableStateOf<List<Double>?>(null) }
    val current by rememberUpdatedState(values)
    val commit by rememberUpdatedState(onCommit)
    val shown = moving ?: values
    val fillColor = if (enabled) c.sliderFill else c.textDisabled
    val markerColor = if (enabled) c.textBright else c.textDisabled
    val rampAlpha = DtTheme.alpha.sliderGradient
    Box(
        modifier.fillMaxWidth().height(z.sliderRow)
            .semantics {
                contentDescription = description
                stateDescription = shown.joinToString(" ") { "${(it * 100).toInt()}" } + " percent"
            }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onDoubleTap = { commit(RangeMath.FULL) })
            }
            .pointerInput(enabled) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pad = z.rangeMarker.toPx() / 2f
                    val span = size.width - 2f * pad
                    fun valueAt(x: Float) = (((x - pad) / span).coerceIn(0f, 1f)).toDouble()
                    val tol = (z.rangeMarker.toPx() * 1.5f / span).toDouble()
                    val cands = RangeMath.candidates(current, valueAt(down.position.x), tol)
                    if (cands.isNotEmpty()) {
                        var chosen = if (cands.size == 1) cands[0] else -1
                        var working = current
                        var dragged = false
                        var live = true
                        while (live) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) {
                                live = false
                            } else {
                                val dx = ch.position.x - down.position.x
                                if (dragged || abs(dx) >= viewConfiguration.touchSlop) {
                                    dragged = true
                                    if (chosen < 0) chosen = if (dx > 0) cands.max() else cands.min()
                                    working = RangeMath.move(working, chosen, valueAt(ch.position.x))
                                    moving = working
                                    ch.consume()
                                }
                            }
                        }
                        if (dragged) { commit(working); moving = null }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = z.rangeMarker.toPx() / 2f
            val tw = size.width - 2f * pad
            val cy = size.height / 2f
            val th = z.sliderTrack.toPx()
            fun x(v: Double) = pad + (v * tw).toFloat()
            drawRect(c.sliderTrack, Offset(pad, cy - th / 2f), Size(tw, th))
            drawRect(fillColor, Offset(x(shown[0]), cy - th / 2f), Size(x(shown[1]) - x(shown[0]), th), alpha = rampAlpha)
            drawRect(fillColor, Offset(x(shown[1]), cy - th / 2f), Size(x(shown[2]) - x(shown[1]), th))
            drawRect(fillColor, Offset(x(shown[2]), cy - th / 2f), Size(x(shown[3]) - x(shown[2]), th), alpha = rampAlpha)
            val m = z.rangeMarker.toPx() * 0.7f
            for (i in 0..3) {
                val px = x(shown[i])
                rotate(45f, Offset(px, cy)) {
                    val tl = Offset(px - m / 2f, cy - m / 2f)
                    if (i == 1 || i == 2) {
                        drawRect(markerColor, tl, Size(m, m))
                    } else {
                        drawRect(c.panel, tl, Size(m, m))
                        drawRect(markerColor, tl, Size(m, m), style = Stroke(z.rangeMarkerLine.toPx()))
                    }
                }
            }
        }
    }
}

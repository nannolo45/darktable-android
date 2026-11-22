// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.ui.theme.DtTheme

/** Brief PUI3a: the slider a finger is dragging, with what the l1 layout's pill shows. */
data class SliderDrag(val key: InstanceKey, val controlId: Int, val module: String, val control: String, val value: String) {
    /** `module · control · value` */
    val pill: String get() = listOf(module, control, value).filter { it.isNotEmpty() }.joinToString(" · ")
}

/**
 * Brief PUI3a: where the generated sliders report a drag. Only the l1 layout provides one (through
 * [LocalSliderDrag]); elsewhere there is none and the controls behave and look as before.
 */
class SliderDragWatch {
    var drag by mutableStateOf<SliderDrag?>(null)
        private set

    fun moved(d: SliderDrag) { drag = d }
    fun ended() { drag = null }
}

val LocalSliderDrag = staticCompositionLocalOf<SliderDragWatch?> { null }

/**
 * Brief PUI3a: the opacity of a piece of the l1 layout while a slider is dragged: it fades out (token time) unless
 * [keep] says it is the dragged slider, and comes back (shorter token time) when the finger lifts. Null where no
 * layout watches drags, so the caller leaves its looks alone.
 */
@Composable
fun dragFade(keep: (SliderDrag) -> Boolean = { false }): Float? {
    val w = LocalSliderDrag.current
    val d = w?.drag
    val target = if (d != null && !keep(d)) 0f else 1f
    val l1 = DtTheme.l1
    val a by animateFloatAsState(target, tween(if (target == 0f) l1.fadeOutMs else l1.fadeInMs), label = "l1 drag fade")
    return if (w == null) null else a
}

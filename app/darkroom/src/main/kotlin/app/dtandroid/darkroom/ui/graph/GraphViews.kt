// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import androidx.compose.runtime.mutableStateMapOf
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control

/**
 * Brief P6f: how each curve graph is shown at the moment, display only (desktop keeps these in the module's GUI data
 * and writes nothing to its parameters): tone curve's and base curve's "scale for graph" (the slider's value, 0..40)
 * and rgb curve's zoom and offsets. Kept per module instance while the app runs.
 */
object GraphViews {
    val logBase = mutableStateMapOf<InstanceKey, Double>()
    val zoom = mutableStateMapOf<InstanceKey, CurveView>()

    /** The modules whose "scale for graph" slider the app drives (tonecurve.c, basecurve.c `logbase`). */
    private val SCALE_OPS = setOf("tonecurve", "basecurve")

    /** True for desktop's "scale for graph" slider of [op]: a slider with no parameter, drawn by [GraphScaleSlider]. */
    fun isScaleSlider(op: String, c: Control): Boolean =
        op in SCALE_OPS && c is Control.Slider && c.binding is Binding.None && c.label?.trim() == "scale for graph"

    /** The view of [key]'s graph on channel [ch]: the log scale (tone curve: L only), rgb curve's zoom. */
    fun view(key: InstanceKey, ch: Int): CurveView = when (key.op) {
        "tonecurve" -> CurveView(logBase = if (ch == 0) logBase[key] ?: 0.0 else 0.0)
        "basecurve" -> CurveView(logBase = logBase[key] ?: 0.0)
        "rgbcurve" -> zoom[key] ?: CurveView.IDENTITY
        else -> CurveView.IDENTITY
    }
}

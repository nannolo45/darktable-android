// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Slider value text and positions as darktable's bauhaus slider shows them:
 * the stored value times `factor` plus `offset`, `digits` decimals, a "+" sign
 * when the slider's range goes below zero, then the unit text (`format`).
 */
object ValueFormat {
    fun display(s: Control.Slider, value: Double): Double = value * s.factor + s.offset

    fun fromDisplay(s: Control.Slider, display: Double): Double = (display - s.offset) / s.factor

    private fun signed(s: Control.Slider): Boolean =
        listOf(s.hardMin, s.hardMax, s.softMin, s.softMax).any { display(s, it) < 0.0 }

    fun text(s: Control.Slider, value: Double): String {
        val d = display(s, value)
        val digits = if (s.digits < 0) 0 else s.digits
        var num = String.format(Locale.ROOT, "%.${digits}f", d)
        if (signed(s) && !num.startsWith("-")) num = "+$num"
        return num + s.format
    }

    /** Number only, for exact entry. */
    fun numberText(s: Control.Slider, value: Double): String =
        String.format(Locale.ROOT, "%.${maxOf(0, s.digits)}f", display(s, value))

    /** Position of [value] in the soft range, 0..1 (values outside the soft range sit at the end). */
    fun fraction(s: Control.Slider, value: Double): Float {
        val span = s.softMax - s.softMin
        if (span <= 0.0) return 0f
        return ((value - s.softMin) / span).coerceIn(0.0, 1.0).toFloat()
    }

    /** The value at a position in the soft range, rounded to what the slider displays. */
    fun valueAt(s: Control.Slider, fraction: Float): Double {
        val raw = s.softMin + (s.softMax - s.softMin) * fraction.coerceIn(0f, 1f)
        return quantize(s, raw).coerceIn(s.softMin, s.softMax)
    }

    /** Round to the precision the slider shows (and to an integer for integer parameters). */
    fun quantize(s: Control.Slider, value: Double): Double {
        if (s.integer) return value.roundToLong().toDouble()
        val q = 10.0.pow(maxOf(0, s.digits))
        val disp = (display(s, value) * q).roundToLong() / q
        val v = fromDisplay(s, disp)
        return if (abs(v) < 1e-12) 0.0 else v
    }

    /**
     * Parse exact entry in display units ("1.5", "+1,5", "-0.3 EV"); the result
     * must lie in the hard range. Null when the text is not a number or outside.
     */
    fun parse(s: Control.Slider, text: String): Double? {
        val t = text.trim().removeSuffix(s.format.trim()).trim().replace(',', '.').removePrefix("+")
        val d = t.toDoubleOrNull() ?: return null
        val v = fromDisplay(s, d)
        val lo = minOf(s.hardMin, s.hardMax)
        val hi = maxOf(s.hardMin, s.hardMax)
        val eps = 1e-9 * maxOf(1.0, abs(hi - lo))
        if (v < lo - eps || v > hi + eps) return null
        return if (s.integer) v.roundToLong().toDouble() else v.coerceIn(lo, hi)
    }
}

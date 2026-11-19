// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Brief P2f deliverable 10: crop's aspect list as desktop runs it (src/iop/crop.c), on parameters only.
 *
 * - [entryOf] / [label]: parameters to the list's entry, as `gui_update` does (an unknown ratio is shown as
 *   "d:n ratio", desktop's custom entry). `ratio_d = ratio_n = -1` (the defaults) mean "take the last choice
 *   from darktablerc", which is freehand unless the user chose otherwise on desktop; the app shows freehand.
 * - [choose]: `_event_aspect_presets_changed` (ratio from the table) then `_aspect_apply(GRAB_HORIZONTAL)`.
 * - [flip]: `_event_aspect_flip`: `ratio_d = -ratio_d`, then `_aspect_apply` with the grab desktop picks.
 * - [keep]: `gui_changed` after a margin moved: `_aspect_apply` with that side's grab.
 *
 * The frame is in the crop's input as fractions (left, top, right, bottom). The limits desktop takes from
 * the pipe (`clip_max_*`, smaller than 0..1 only when a module before crop such as rotate and perspective
 * leaves empty borders) are 0..1 here; `_commit_box`'s rounding of the frame to whole pixels of the ratio
 * is left out (a fraction of a pixel). [inW] x [inH] is the size of the crop's input.
 */
object CropAspect {
    data class Frame(val left: Double, val top: Double, val right: Double, val bottom: Double)

    /** One result: the parameter values to write as one history step. */
    data class Change(val ratioD: Int, val ratioN: Int, val frame: Frame)

    enum class Grab(val bits: Int) {
        LEFT(1), TOP(2), RIGHT(4), BOTTOM(8), HORIZONTAL(1 or 4), VERTICAL(2 or 8);
    }

    /** (d, n) as desktop reads the parameters: -1/-1 is the darktablerc default, freehand. */
    fun effective(ratioD: Int, ratioN: Int): Pair<Int, Int> = if (ratioD == -1 && ratioN == -1) 0 to 0 else ratioD to ratioN

    /** The list's entry for the parameters, or -1 for a ratio that is not in the list. */
    fun entryOf(spec: AspectSpec, ratioD: Int, ratioN: Int): Int {
        val (d, n) = effective(ratioD, ratioN)
        return spec.entries.indexOfFirst { it.d == abs(d) && it.n == n }
    }

    /** What the list shows: the entry's label, or desktop's text for a custom ratio ("7:5 1.40"). */
    fun label(spec: AspectSpec, ratioD: Int, ratioN: Int): String {
        val i = entryOf(spec, ratioD, ratioN)
        if (i >= 0) return spec.entries[i].label
        val (d, n) = effective(ratioD, ratioN)
        return String.format(Locale.ROOT, "%d:%d %2.2f", abs(d), abs(n), abs(d).toFloat() / abs(n).toFloat())
    }

    /** The ratio the frame is held to, as `_aspect_ratio_get`; 0 or negative = none (freehand). */
    fun aspect(spec: AspectSpec, ratioD: Int, ratioN: Int, inW: Double, inH: Double): Double {
        val (d0, n0) = effective(ratioD, ratioN)
        val i = entryOf(spec, d0, n0)
        if (i >= 0 && spec.entries[i].d == 1 && spec.entries[i].n == 0) {
            // "original image": the image's own ratio, landscape or portrait by the sign of ratio_d
            if (!(inW > 0 && inH > 0)) return 0.0
            val regular = (d0 > 0 && inW >= inH) || (d0 < 0 && inW < inH)
            return if (regular) inW / inH else inH / inW
        }
        if (d0 == 0 && n0 == 0) return -1.0
        val d: Double
        val n: Double
        if (n0 == 0) { d = (if (d0 < 0) -1.0 else 1.0) * inW; n = inH } else { d = d0.toDouble(); n = n0.toDouble() }
        val dn = max(abs(d), abs(n)) * (if (d < 0) -1.0 else 1.0)
        val nn = min(abs(d), abs(n)) * sign(n).let { if (it == 0.0) 1.0 else it }
        return if (dn < 0) -nn / dn else dn / nn
    }

    /** `_aspect_apply`: the frame reshaped to the ratio, from the side [grab] says was moved. */
    fun apply(f: Frame, aspect0: Double, grab: Grab, inW: Double, inH: Double): Frame {
        var aspect = aspect0
        if (inW < inH && aspect != 0.0) aspect = 1.0 / aspect
        if (aspect <= 0.0) return f
        var x = max(f.left, 0.0)
        var y = max(f.top, 0.0)
        var w = min(f.right - f.left, 1.0)
        var h = min(f.bottom - f.top, 1.0)
        val targetH = inW * w / (inH * aspect)
        val targetW = inH * h * aspect / inW
        when (grab) {
            Grab.LEFT, Grab.RIGHT, Grab.HORIZONTAL -> { val off = targetH - h; h += off; y -= 0.5 * off }
            Grab.TOP, Grab.BOTTOM, Grab.VERTICAL -> { val off = targetW - w; w += off; x -= 0.5 * off }
        }
        // the frame back inside the image (clip_max = 0..1)
        if (x < 0.0) { val ph = h; h *= (w + x) / w; w += x; x = 0.0; if (grab.bits and Grab.TOP.bits != 0) y += ph - h }
        if (y < 0.0) { val pw = w; w *= (h + y) / h; h += y; y = 0.0; if (grab.bits and Grab.LEFT.bits != 0) x += pw - w }
        if (x + w > 1.0) { val ph = h; h *= (1.0 - x) / w; w = 1.0 - x; if (grab.bits and Grab.TOP.bits != 0) y += ph - h }
        if (y + h > 1.0) { val pw = w; w *= (1.0 - y) / h; h = 1.0 - y; if (grab.bits and Grab.LEFT.bits != 0) x += pw - w }
        x = x.coerceIn(0.0, 1.0); y = y.coerceIn(0.0, 1.0)
        w = w.coerceIn(0.0, 1.0 - x); h = h.coerceIn(0.0, 1.0 - y)
        return Frame(x, y, x + w, y + h)
    }

    /** An entry chosen in the list: its ratio, and the frame reshaped as desktop does. */
    fun choose(spec: AspectSpec, entry: Int, ratioD: Int, frame: Frame, inW: Double, inH: Double): Change {
        val e = spec.entries[entry]
        // desktop keeps ratio_d positive when choosing (p->ratio_d = abs(d))
        val d = abs(e.d)
        val n = e.n
        val a = aspect(spec, d, n, inW, inH)
        return Change(d, n, apply(frame, a, Grab.HORIZONTAL, inW, inH))
    }

    /** The flip button: portrait and landscape swapped. */
    fun flip(spec: AspectSpec, ratioD: Int, ratioN: Int, frame: Frame, inW: Double, inH: Double): Change {
        val (d0, n0) = effective(ratioD, ratioN)
        val d = -d0
        val horizontal = (inW >= inH) == (d < 0)
        val a = aspect(spec, d, n0, inW, inH)
        return Change(d, n0, apply(frame, a, if (horizontal) Grab.HORIZONTAL else Grab.VERTICAL, inW, inH))
    }

    /** A margin moved ([param] is one of the four): the other side follows to keep the ratio. */
    fun keep(spec: AspectSpec, param: String, ratioD: Int, ratioN: Int, frame: Frame, inW: Double, inH: Double): Frame {
        val grab = when (param) {
            spec.left -> Grab.LEFT
            spec.right -> Grab.RIGHT
            spec.top -> Grab.TOP
            spec.bottom -> Grab.BOTTOM
            else -> return frame
        }
        return apply(frame, aspect(spec, ratioD, ratioN, inW, inH), grab, inW, inH)
    }

    /**
     * The crop's input size from what the engine reports: the processed (cropped) image is the frame's
     * share of it. Exact when no later module changes the size.
     */
    fun inputSize(processedW: Int, processedH: Int, frame: Frame, enabled: Boolean): Pair<Double, Double> {
        if (!enabled) return processedW.toDouble() to processedH.toDouble()
        val fw = (frame.right - frame.left).takeIf { it > 1e-6 } ?: 1.0
        val fh = (frame.bottom - frame.top).takeIf { it > 1e-6 } ?: 1.0
        return processedW / fw to processedH / fh
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.graph

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Brief P3e-b: the line the band editor draws through a curve's nodes. darktable's curve tools offer three
 * interpolations (cubic spline, Catmull-Rom, monotone Hermite; `curve_tools.h`); the picture the engine renders
 * uses its own, this line is the editor's drawing of it. A hue axis that wraps round (colour equalizer, colour
 * zones' hue) is drawn periodic.
 */
object BandInterp {
    const val CUBIC = 0
    const val CATMULL = 1
    const val MONOTONE = 2

    /** [samples] + 1 points x, y, x, y ... over x = 0..1; y is not clipped. */
    fun line(xs: List<Double>, ys: List<Double>, type: Int, periodic: Boolean, samples: Int = 96): FloatArray {
        val out = FloatArray((samples + 1) * 2)
        val f = fn(xs, ys, type, periodic)
        for (i in 0..samples) {
            val x = i.toDouble() / samples
            out[2 * i] = x.toFloat()
            out[2 * i + 1] = f(x).toFloat()
        }
        return out
    }

    /** The curve as a function of x. */
    fun fn(xs: List<Double>, ys: List<Double>, type: Int, periodic: Boolean): (Double) -> Double {
        val n = xs.size
        if (n == 0) return { 0.5 }
        if (n == 1) return { ys[0] }
        val ex: List<Double>
        val ey: List<Double>
        if (periodic) {
            ex = xs.map { it - 1.0 } + xs + xs.map { it + 1.0 }
            ey = ys + ys + ys
        } else {
            ex = xs
            ey = ys
        }
        val seg = Spline(ex, ey, type)
        return { x ->
            when {
                periodic -> seg.at(x)
                x <= ex.first() -> ey.first()
                x >= ex.last() -> ey.last()
                else -> seg.at(x)
            }
        }
    }

    private class Spline(val x: List<Double>, val y: List<Double>, val type: Int) {
        private val n = x.size
        // Hermite tangents (catmull, monotone) or second derivatives (cubic)
        private val m: DoubleArray = if (type == CUBIC) second() else tangents()

        private fun tangents(): DoubleArray {
            val d = DoubleArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]).coerceAtLeast(1e-9) }
            val t = DoubleArray(n)
            if (type == MONOTONE) {
                t[0] = d[0]
                t[n - 1] = d[n - 2]
                for (k in 1 until n - 1) t[k] = if (d[k - 1] * d[k] <= 0.0) 0.0 else (d[k - 1] + d[k]) / 2.0
                for (k in 0 until n - 1) {
                    if (d[k] == 0.0) { t[k] = 0.0; t[k + 1] = 0.0; continue }
                    val a = t[k] / d[k]
                    val b = t[k + 1] / d[k]
                    val s = a * a + b * b
                    if (s > 9.0) { val c = 3.0 / sqrt(s); t[k] = c * a * d[k]; t[k + 1] = c * b * d[k] }
                }
            } else {
                t[0] = d[0]
                t[n - 1] = d[n - 2]
                for (k in 1 until n - 1) t[k] = (y[k + 1] - y[k - 1]) / (x[k + 1] - x[k - 1]).coerceAtLeast(1e-9)
            }
            return t
        }

        /** natural cubic spline: the second derivative at each node */
        private fun second(): DoubleArray {
            val mm = DoubleArray(n)
            if (n < 3) return mm
            val h = DoubleArray(n - 1) { (x[it + 1] - x[it]).coerceAtLeast(1e-9) }
            val a = DoubleArray(n)
            val b = DoubleArray(n)
            val c = DoubleArray(n)
            val r = DoubleArray(n)
            b[0] = 1.0
            b[n - 1] = 1.0
            for (i in 1 until n - 1) {
                a[i] = h[i - 1]
                b[i] = 2.0 * (h[i - 1] + h[i])
                c[i] = h[i]
                r[i] = 6.0 * ((y[i + 1] - y[i]) / h[i] - (y[i] - y[i - 1]) / h[i - 1])
            }
            // Thomas algorithm
            for (i in 1 until n) {
                val w = a[i] / b[i - 1]
                b[i] -= w * c[i - 1]
                r[i] -= w * r[i - 1]
            }
            mm[n - 1] = r[n - 1] / b[n - 1]
            for (i in n - 2 downTo 0) mm[i] = (r[i] - c[i] * mm[i + 1]) / b[i]
            return mm
        }

        fun at(q: Double): Double {
            var lo = 0
            var hi = n - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (x[mid] <= q) lo = mid else hi = mid
            }
            val h = (x[hi] - x[lo]).coerceAtLeast(1e-9)
            if (type == CUBIC) {
                val a = (x[hi] - q) / h
                val b = (q - x[lo]) / h
                return a * y[lo] + b * y[hi] + ((a * a * a - a) * m[lo] + (b * b * b - b) * m[hi]) * h * h / 6.0
            }
            val t = ((q - x[lo]) / h)
            val t2 = t * t
            val t3 = t2 * t
            return (2 * t3 - 3 * t2 + 1) * y[lo] + (t3 - 2 * t2 + t) * h * m[lo] + (-2 * t3 + 3 * t2) * y[hi] + (t3 - t2) * h * m[hi]
        }
    }

    /** the value used for "is this about equal" in tests */
    fun near(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) <= eps
}

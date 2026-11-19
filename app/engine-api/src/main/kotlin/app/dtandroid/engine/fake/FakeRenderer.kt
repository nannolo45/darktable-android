// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A plausible stand-in for darktable's pipeline: a generated landscape in
 * "camera" colours, then a handful of modules approximated per pixel so that
 * edits are visible. It is not darktable's maths and does not try to be.
 *
 * Supported: white balance, highlight clipping, exposure, tone equalizer
 * (simple tab), color balance rgb (vibrance, contrast, chroma, saturation,
 * brilliance), velvia, vibrance, contrast brightness saturation, monochrome,
 * sigmoid, filmic rgb and AgX (as one tone curve), vignetting, grain, and
 * orientation (rotate and flip). Everything else passes pixels through.
 */
internal class FakeRenderer(private val variant: Int) {

    companion object {
        const val BASE_W = 3000
        const val BASE_H = 2000
        private const val GREY = 0.1845
        /** white balance coefficients the scene is "shot" with; the schema's defaults for the sample raw */
        val CAMERA_WB = doubleArrayOf(2.115234375, 1.0, 1.3984375)
    }

    /** One step of the pipeline, applied in place to linear RGB triplets. */
    fun interface PixelOp {
        fun apply(px: FloatArray, n: Int, coords: Coords)
    }

    /** Base-image coordinates (0..1) of each pixel, for position-dependent modules. */
    class Coords(val u: FloatArray, val v: FloatArray)

    class Stage(val op: String, val pixel: PixelOp?)

    /** Orientation bits as darktable's flip module stores them (FLIP_Y 1, FLIP_X 2, SWAP_XY 4). */
    fun processedSize(orientation: Int, scale: Double): Pair<Int, Int> {
        val swap = orientation >= 0 && orientation and 4 != 0
        val w = if (swap) BASE_H else BASE_W
        val h = if (swap) BASE_W else BASE_H
        return Pair(maxOf(1, Math.round(w * scale).toInt()), maxOf(1, Math.round(h * scale).toInt()))
    }

    /** Map processed-image pixel centres to base-image coordinates (0..1). */
    fun coordsFor(x: Int, y: Int, w: Int, h: Int, scale: Double, orientation: Int): Coords {
        val o = if (orientation < 0) 0 else orientation
        val (pw, ph) = processedSize(o, 1.0)
        val u = FloatArray(w * h)
        val v = FloatArray(w * h)
        var i = 0
        for (j in 0 until h) {
            val py = ((y + j + 0.5) / scale) / ph
            for (k in 0 until w) {
                val px = ((x + k + 0.5) / scale) / pw
                // undo the orientation: darktable applies flips, then the transpose
                var a = px
                var b = py
                if (o and 4 != 0) { val t = a; a = b; b = t }
                if (o and 2 != 0) a = 1.0 - a
                if (o and 1 != 0) b = 1.0 - b
                u[i] = a.toFloat(); v[i] = b.toFloat(); i++
            }
        }
        return Coords(u, v)
    }

    /** The scene in camera RGB (linear), as if read from the raw before white balance. */
    fun scene(c: Coords): FloatArray {
        val n = c.u.size
        val out = FloatArray(n * 3)
        val rgb = DoubleArray(3)
        for (i in 0 until n) {
            sceneLinear(c.u[i].toDouble(), c.v[i].toDouble(), rgb)
            out[i * 3] = (rgb[0] / CAMERA_WB[0]).toFloat()
            out[i * 3 + 1] = (rgb[1] / CAMERA_WB[1]).toFloat()
            out[i * 3 + 2] = (rgb[2] / CAMERA_WB[2]).toFloat()
        }
        return out
    }

    private fun smooth(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

    /** Linear sRGB of the generated landscape at (u, v); variant shifts time of day and layout. */
    private fun sceneLinear(u: Double, v: Double, out: DoubleArray) {
        val warmth = listOf(0.0, 0.35, 0.7, 0.15, 0.5, 0.9)[variant % 6]
        val sunX = 0.25 + 0.5 * ((variant * 37) % 10) / 10.0
        val sunY = 0.22 + 0.1 * warmth
        val horizon = 0.55 + 0.03 * ((variant * 13) % 5 - 2)
        // sky: deep blue at the top to pale, warmer near the horizon
        val tSky = (v / horizon).coerceIn(0.0, 1.0)
        var r = mix(0.06, 0.55 + 0.35 * warmth, tSky.pow(1.6))
        var g = mix(0.16, 0.55 + 0.12 * warmth, tSky.pow(1.6))
        var b = mix(0.55, 0.62 - 0.25 * warmth, tSky.pow(1.6))
        // sun and glow (goes above 1: something for highlight clipping and exposure)
        val dx = (u - sunX) * 1.5
        val dy = v - sunY
        val d = sqrt(dx * dx + dy * dy)
        val glow = exp(-d * 9.0) * 0.9 + (if (d < 0.025) 6.0 else 0.0) + exp(-d * 40.0) * 2.0
        r += glow * (1.0 + 0.3 * warmth); g += glow * (0.9 + 0.1 * warmth); b += glow * (0.75 - 0.2 * warmth)
        // clouds
        val cloud = (sin(u * 11.0 + variant) * sin(v * 23.0 + u * 5.0) * 0.5 + 0.5).pow(6.0) * smooth(0.05, 0.3, v) * (1 - smooth(0.35, horizon, v))
        r = mix(r, 0.85, cloud * 0.6); g = mix(g, 0.85, cloud * 0.6); b = mix(b, 0.9, cloud * 0.6)
        // distant mountains, two ridges
        val ridge1 = horizon - 0.12 - 0.07 * sin(u * 7.0 + variant) - 0.03 * sin(u * 23.0)
        val ridge2 = horizon - 0.05 - 0.04 * sin(u * 11.0 + 2.0 + variant) - 0.015 * sin(u * 41.0)
        if (v > ridge1) { val haze = 0.55; r = mix(0.10, r, haze); g = mix(0.13, g, haze); b = mix(0.22, b, haze) }
        if (v > ridge2) { r = mix(0.05, r, 0.3); g = mix(0.08, g, 0.3); b = mix(0.12, b, 0.3) }
        if (v > horizon) {
            // foreground: meadow with a lake on the left
            val t = ((v - horizon) / (1 - horizon)).coerceIn(0.0, 1.0)
            val lake = u < 0.45 + 0.1 * sin(v * 9.0) && v < horizon + 0.18
            if (lake) {
                // reflection of the sky, darker
                r *= 0.45; g *= 0.5; b *= 0.6
                val ripple = sin(v * 400.0 + u * 30.0) * 0.012
                r += ripple * 0.3; g += ripple * 0.3; b += ripple * 0.3
            } else {
                val grass = 0.5 + 0.5 * sin(u * 300.0 + v * 70.0) * sin(v * 210.0)
                r = mix(0.06, 0.20 + 0.15 * warmth, t) * (0.8 + 0.3 * grass)
                g = mix(0.11, 0.24 + 0.05 * warmth, t) * (0.8 + 0.3 * grass)
                b = mix(0.04, 0.06, t) * (0.8 + 0.3 * grass)
            }
            // three small houses: red, yellow and a neutral grey one (useful for white balance)
            val houses = listOf(Triple(0.62, 0.55, 0.32), Triple(0.72, 0.62, 0.48), Triple(0.84, 0.10, 0.18))
            for ((idx, hc) in houses.withIndex()) {
                val hx = 0.55 + idx * 0.1 + 0.02 * ((variant + idx) % 3)
                val top = horizon + 0.04 + idx * 0.03
                val bottom = top + 0.09
                if (u in hx..(hx + 0.07) && v in top..bottom) {
                    val shade = if (u < hx + 0.025) 0.55 else 1.0
                    when (idx) {
                        0 -> { r = 0.42 * shade; g = 0.05 * shade; b = 0.04 * shade }
                        1 -> { r = 0.55 * shade; g = 0.42 * shade; b = 0.06 * shade }
                        else -> { r = 0.18 * shade; g = 0.18 * shade; b = 0.18 * shade }
                    }
                }
                // roof
                val roofTop = top - 0.04
                if (v in roofTop..top && abs(u - (hx + 0.035)) < (v - roofTop) * 1.1) {
                    r = 0.08 + 0.02 * hc.third; g = 0.05; b = 0.05
                }
            }
        }
        out[0] = max(0.0, r); out[1] = max(0.0, g); out[2] = max(0.0, b)
    }

    // ------------------------------------------------------------------ modules

    private fun JsonObject.d(key: String, def: Double): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: def
    private fun JsonObject.b(key: String, def: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: def

    /** The pixel operation for one enabled module instance, or null when the fake passes it through. */
    fun stageFor(op: String, p: JsonObject): Stage = Stage(op, when (op) {
        "temperature" -> {
            val c = floatArrayOf(p.d("red", 1.0).toFloat(), p.d("green", 1.0).toFloat(), p.d("blue", 1.0).toFloat())
            PixelOp { px, n, _ -> for (i in 0 until n) { px[i * 3] *= c[0]; px[i * 3 + 1] *= c[1]; px[i * 3 + 2] *= c[2] } }
        }
        "highlights" -> PixelOp { px, n, _ ->
            val clip = p.d("clip", 1.0).toFloat()
            for (i in 0 until n * 3) px[i] = min(px[i], clip)
        }
        "exposure" -> {
            val m = 2.0.pow(p.d("exposure", 0.0)).toFloat()
            val black = p.d("black", 0.0).toFloat()
            PixelOp { px, n, _ -> for (i in 0 until n * 3) px[i] = (px[i] - black) * m }
        }
        "toneequal" -> {
            val keys = listOf("noise", "ultra_deep_blacks", "deep_blacks", "blacks", "shadows", "midtones", "highlights", "whites", "speculars")
            val ev = keys.map { p.d(it, 0.0) }.toDoubleArray()
            if (ev.all { it == 0.0 }) null else PixelOp { px, n, _ ->
                for (i in 0 until n) {
                    val y = lum(px, i).coerceAtLeast(1e-6)
                    val e = (ln(y) / ln(2.0)).coerceIn(-8.0, 0.0) + 8.0
                    val k = e.toInt().coerceIn(0, 7)
                    val g = 2.0.pow(ev[k] + (ev[k + 1] - ev[k]) * (e - k)).toFloat()
                    px[i * 3] *= g; px[i * 3 + 1] *= g; px[i * 3 + 2] *= g
                }
            }
        }
        "colorbalancergb" -> {
            val vib = p.d("vibrance", 0.0); val con = p.d("contrast", 0.0)
            val chroma = p.d("chroma_global", 0.0); val sat = p.d("saturation_global", 0.0)
            val bri = p.d("brilliance_global", 0.0)
            PixelOp { px, n, _ ->
                for (i in 0 until n) {
                    val y = lum(px, i).coerceAtLeast(1e-6)
                    val yn = GREY * (y / GREY).pow(1.0 + con) * (1.0 + bri)
                    val s = saturationOf(px, i)
                    val k = (1.0 + chroma) * (1.0 + sat * 0.8) * (1.0 + vib * (1.0 - s))
                    setLumChroma(px, i, y, yn, k)
                }
            }
        }
        "velvia" -> {
            val s = p.d("strength", 25.0) / 100.0
            PixelOp { px, n, _ -> for (i in 0 until n) { val y = lum(px, i); setLumChroma(px, i, y, y, 1.0 + s * (1.0 - saturationOf(px, i))) } }
        }
        "vibrance" -> {
            val s = p.d("amount", 25.0) / 100.0
            PixelOp { px, n, _ -> for (i in 0 until n) { val y = lum(px, i); setLumChroma(px, i, y, y, 1.0 + s * 0.6 * (1.0 - saturationOf(px, i))) } }
        }
        "colisa" -> {
            val c = p.d("contrast", 0.0); val b = p.d("brightness", 0.0); val s = p.d("saturation", 0.0)
            PixelOp { px, n, _ ->
                for (i in 0 until n) {
                    val y = lum(px, i).coerceAtLeast(1e-6)
                    setLumChroma(px, i, y, GREY * (y / GREY).pow(1.0 + c) * 2.0.pow(b * 2.0), 1.0 + s)
                }
            }
        }
        "monochrome" -> PixelOp { px, n, _ -> for (i in 0 until n) { val y = lum(px, i); setLumChroma(px, i, y, y, 0.0) } }
        "sigmoid" -> toneCurve(p.d("middle_grey_contrast", 1.5), p.d("contrast_skewness", 0.0))
        "filmicrgb" -> toneCurve(1.0 + 0.5 * p.d("contrast", 1.0), 0.0)
        "agx" -> toneCurve(1.2 + 0.25 * p.d("curve_contrast_around_pivot", 2.8), 0.0)
        "vignette" -> {
            val br = p.d("brightness", -0.5); val sc = p.d("scale", 80.0) / 100.0
            PixelOp { px, n, co ->
                for (i in 0 until n) {
                    val du = co.u[i] - 0.5; val dv = co.v[i] - 0.5
                    val r = sqrt(du * du + dv * dv) / 0.7071
                    val f = (1.0 + br * smooth(sc * 0.6, 1.0, r)).toFloat().coerceAtLeast(0f)
                    px[i * 3] *= f; px[i * 3 + 1] *= f; px[i * 3 + 2] *= f
                }
            }
        }
        "grain" -> {
            val s = p.d("strength", 25.0) / 100.0 * 0.12
            PixelOp { px, n, co ->
                for (i in 0 until n) {
                    val h = hash(co.u[i], co.v[i])
                    val f = (1.0 + s * (h - 0.5) * 2.0).toFloat()
                    px[i * 3] *= f; px[i * 3 + 1] *= f; px[i * 3 + 2] *= f
                }
            }
        }
        else -> null
    })

    private fun hash(u: Float, v: Float): Double {
        val x = sin(u * 12.9898 * 997 + v * 78.233 * 991) * 43758.5453
        return x - kotlin.math.floor(x)
    }

    private fun toneCurve(contrast: Double, skew: Double): PixelOp {
        val c = contrast.coerceAtLeast(0.1)
        val k = GREY.pow(c) * (1.0 / GREY - 1.0)
        return PixelOp { px, n, _ ->
            for (i in 0 until n * 3) {
                val x = px[i].toDouble().coerceAtLeast(0.0)
                val ce = c * (1.0 + skew * 0.4 * (if (x > GREY) 1.0 else -1.0))
                val xc = x.pow(ce)
                px[i] = (xc / (xc + k * GREY.pow(ce - c))).toFloat()
            }
        }
    }

    private fun lum(px: FloatArray, i: Int): Double = 0.2126 * px[i * 3] + 0.7152 * px[i * 3 + 1] + 0.0722 * px[i * 3 + 2]

    private fun saturationOf(px: FloatArray, i: Int): Double {
        val mx = max(px[i * 3], max(px[i * 3 + 1], px[i * 3 + 2])).toDouble()
        val mn = min(px[i * 3], min(px[i * 3 + 1], px[i * 3 + 2])).toDouble()
        return if (mx <= 1e-6) 0.0 else ((mx - mn) / mx).coerceIn(0.0, 1.0)
    }

    /** Replace luminance y by yNew and scale the colour difference by k. */
    private fun setLumChroma(px: FloatArray, i: Int, y: Double, yNew: Double, k: Double) {
        val ratio = if (y > 1e-9) yNew / y else 1.0
        for (c in 0..2) {
            val d = px[i * 3 + c] - y
            px[i * 3 + c] = ((y + d * k) * ratio).toFloat().coerceAtLeast(0f)
        }
    }

    /** Final output: clip and sRGB encode into RGBA8. */
    fun toRgba8(px: FloatArray, n: Int, tonemapped: Boolean): ByteArray {
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            for (c in 0..2) {
                var x = px[i * 3 + c].toDouble()
                if (!tonemapped) x = x.coerceIn(0.0, 1.0)
                val s = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
                out[i * 4 + c] = (s * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
            }
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }
}

internal fun JsonArray.isNumberArray(): Boolean = all { it is JsonPrimitive && !it.isString }

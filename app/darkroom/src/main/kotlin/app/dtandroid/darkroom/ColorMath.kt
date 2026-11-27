// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Brief P6e: a colour as desktop's colour buttons hold it (GdkRGBA without alpha): red, green and blue as display
 * sRGB values 0..1, written unchanged into the module's float parameters (`_colorpick_color_set` in
 * src/iop/borders.c and src/iop/watermark.c: `p->color[0] = c.red`). A value may lie between the 8-bit steps (a
 * picked colour); the dialog keeps it as it is until the user changes the colour, so opening the dialog and pressing
 * OK never moves it.
 */
data class Rgb(val r: Double, val g: Double, val b: Double) {
    val channels: List<Double> get() = listOf(r, g, b)

    /** the 8-bit value of each channel (clipped to 0..255), as the dialog's sliders and hex field show it */
    val bytes: List<Int> get() = channels.map { ColorMath.toByte(it) }

    /** the colour as the parameters hold it (floats): two colours that write the same parameters are the same */
    fun asFloats(): List<Float> = channels.map { it.toFloat() }

    companion object {
        fun ofBytes(r: Int, g: Int, b: Int) = Rgb(r / 255.0, g / 255.0, b / 255.0)
        fun of(l: List<Double>) = Rgb(l[0], l[1], l[2])
    }
}

/** Hue in degrees 0..360 (360 excluded), saturation and value 0..1. */
data class Hsv(val h: Double, val s: Double, val v: Double)

object ColorMath {
    /** 0..1 to 0..255, rounded, clipped (what the sliders and the hex field show) */
    fun toByte(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255.0).roundToInt()

    /** "#RRGGBB", upper case */
    fun hex(c: Rgb): String = "#" + c.bytes.joinToString("") { "%02X".format(it) }

    /**
     * A typed hex colour: optional "#", then 3 (each digit doubled: "f80" = "ff8800") or 6 hex digits; spaces around
     * are ignored. Anything else: null.
     */
    fun parseHex(text: String): Rgb? {
        var t = text.trim()
        if (t.startsWith("#")) t = t.substring(1)
        if (t.isEmpty() || t.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
        val six = when (t.length) {
            3 -> t.map { "$it$it" }.joinToString("")
            6 -> t
            else -> return null
        }
        return Rgb.ofBytes(six.substring(0, 2).toInt(16), six.substring(2, 4).toInt(16), six.substring(4, 6).toInt(16))
    }

    /** RGB (each clipped to 0..1) to HSV; a grey has hue 0 and saturation 0 */
    fun toHsv(c: Rgb): Hsv {
        val r = c.r.coerceIn(0.0, 1.0)
        val g = c.g.coerceIn(0.0, 1.0)
        val b = c.b.coerceIn(0.0, 1.0)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0.0 -> 0.0
            max == r -> 60.0 * (((g - b) / d) % 6.0)
            max == g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }.let { if (it < 0) it + 360.0 else it }.let { if (it >= 360.0) it - 360.0 else it }
        return Hsv(h, if (max == 0.0) 0.0 else d / max, max)
    }

    fun fromHsv(hsv: Hsv): Rgb {
        val h = ((hsv.h % 360.0) + 360.0) % 360.0
        val s = hsv.s.coerceIn(0.0, 1.0)
        val v = hsv.v.coerceIn(0.0, 1.0)
        val c = v * s
        val hp = h / 60.0
        val x = c * (1.0 - abs(hp % 2.0 - 1.0))
        val (r, g, b) = when (floor(hp).toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = v - c
        return Rgb(r + m, g + m, b + m)
    }

    /** one channel (0 red, 1 green, 2 blue) set to an 8-bit value; the other two stay exactly as they were */
    fun withByte(c: Rgb, channel: Int, value: Int): Rgb {
        val v = value.coerceIn(0, 255) / 255.0
        return when (channel) {
            0 -> c.copy(r = v)
            1 -> c.copy(g = v)
            else -> c.copy(b = v)
        }
    }

    /** 8-bit RGB snapped from an HSV position (the square and the hue slider move in 8-bit steps, as the hex field shows) */
    fun fromHsvBytes(hsv: Hsv): Rgb {
        val c = fromHsv(hsv)
        return Rgb.ofBytes(toByte(c.r), toByte(c.g), toByte(c.b))
    }
}

/**
 * Brief P6e: the recently chosen colours of the colour dialog, kept per app (not per photo): newest first, no
 * repeats (by hex), at most [max]. Stored as one "#RRGGBB" per line.
 */
class RecentColors(val max: Int = 8) {
    private val list = ArrayList<String>()

    val colors: List<String> get() = list.toList()

    /** [hex] first; an equal entry further down goes; the oldest beyond [max] go */
    fun add(hex: String) {
        val h = ColorMath.parseHex(hex)?.let { ColorMath.hex(it) } ?: return
        list.remove(h)
        list.add(0, h)
        while (list.size > max) list.removeAt(list.size - 1)
    }

    fun text(): String = list.joinToString("\n")

    companion object {
        /** the stored text; lines that are no colour are skipped */
        fun parse(text: String, max: Int = 8): RecentColors {
            val r = RecentColors(max)
            for (line in text.lines().asReversed()) if (line.isNotBlank()) r.add(line)
            return r
        }
    }
}

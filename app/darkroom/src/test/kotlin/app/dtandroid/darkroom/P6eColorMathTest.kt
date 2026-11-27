// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Brief P6e (JVM): the colour dialog's conversions. Parameter (float) to dialog (0..1 double, 0..255, hex, HSV) and
 * back without drift; hex parsing with and without "#", 3 and 6 digits, invalid text refused; the recent colours.
 */
class P6eColorMathTest {
    @Test fun everyByteSurvivesParameterAndBack() {
        for (n in 0..255) {
            // the dialog writes n / 255 (GdkRGBA from an 8-bit choice); the parameter is a float
            val f = (n / 255.0).toFloat()
            assertEquals(n, ColorMath.toByte(f.toDouble()))
            val back = Rgb.ofBytes(n, n, n)
            assertEquals(f, back.asFloats()[0])
        }
    }

    @Test fun aParameterOffTheByteGridIsKeptUntilChanged() {
        val r = Random(6)
        repeat(2000) {
            val f = listOf(r.nextFloat(), r.nextFloat(), r.nextFloat())
            val dialog = Rgb.of(f.map { it.toDouble() })
            // OK without a change writes the same floats (no drift)
            assertEquals(f, dialog.asFloats())
            // one channel moved by a slider: the other two stay exactly as they were
            val moved = ColorMath.withByte(dialog, 1, 200)
            assertEquals(f[0], moved.asFloats()[0])
            assertEquals(f[2], moved.asFloats()[2])
            assertEquals(200, moved.bytes[1])
        }
    }

    @Test fun hexParsing() {
        val orange = Rgb.ofBytes(255, 136, 0)
        for (t in listOf("#FF8800", "ff8800", " #ff8800 ", "#f80", "F80", "#F80")) assertEquals(t, orange, ColorMath.parseHex(t))
        for (t in listOf("", "#", "ff88", "ff880", "#ff88001", "gg8800", "#12 345", "12345g", "##ff8800", "0x ff8800"))
            assertNull(t, ColorMath.parseHex(t))
        assertEquals("#FF8800", ColorMath.hex(orange))
        assertEquals("#000000", ColorMath.hex(Rgb(-0.5, 0.0, 0.0)))
        assertEquals("#FFFFFF", ColorMath.hex(Rgb(1.0, 2.0, 1.0)))
    }

    @Test fun hexRoundTripsForEveryColourOnAGrid() {
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val c = Rgb.ofBytes(r, g, b)
            assertEquals(c, ColorMath.parseHex(ColorMath.hex(c)))
            assertEquals(c.asFloats(), ColorMath.parseHex(ColorMath.hex(c))!!.asFloats())
        }
    }

    @Test fun hsvRoundTripsWithoutDrift() {
        for (r in 0..255 step 5) for (g in 0..255 step 5) for (b in 0..255 step 5) {
            val c = Rgb.ofBytes(r, g, b)
            val hsv = ColorMath.toHsv(c)
            assertTrue(hsv.h >= 0.0 && hsv.h < 360.0 && hsv.s in 0.0..1.0 && hsv.v in 0.0..1.0)
            val back = ColorMath.fromHsv(hsv)
            assertTrue("$c -> $hsv -> $back", abs(back.r - c.r) < 1e-9 && abs(back.g - c.g) < 1e-9 && abs(back.b - c.b) < 1e-9)
            // the field and the hue strip snap to bytes: the same bytes come back
            assertEquals(c.bytes, ColorMath.fromHsvBytes(hsv).bytes)
        }
        assertEquals(Hsv(0.0, 0.0, 0.5019607843137255), ColorMath.toHsv(Rgb.ofBytes(128, 128, 128)))
        assertEquals(Rgb.ofBytes(255, 0, 0).bytes, ColorMath.fromHsvBytes(Hsv(360.0, 1.0, 1.0)).bytes)
        assertEquals(Rgb.ofBytes(0, 255, 255).bytes, ColorMath.fromHsvBytes(Hsv(180.0, 1.0, 1.0)).bytes)
    }

    @Test fun recentColours() {
        val r = RecentColors(max = 3)
        r.add("#112233"); r.add("445566"); r.add("#f80"); r.add("nonsense")
        assertEquals(listOf("#FF8800", "#445566", "#112233"), r.colors)
        r.add("#112233")
        assertEquals("a repeat moves to the front", listOf("#112233", "#FF8800", "#445566"), r.colors)
        r.add("#000000")
        assertEquals("the oldest goes beyond the limit", listOf("#000000", "#112233", "#FF8800"), r.colors)
        val again = RecentColors.parse(r.text() + "\nnot a colour\n", max = 3)
        assertEquals(r.colors, again.colors)
        assertEquals(emptyList<String>(), RecentColors.parse("").colors)
    }
}

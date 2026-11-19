// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.RenderedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brief P2b: the drag picture's size comes from measured render times. */
class DragPictureTest {
    @Test
    fun noMeasurementGivesTheSmallestSize() {
        assertEquals(320, DragPicture().size())
    }

    @Test
    fun sizeFollowsTheMeasuredCost() {
        val d = DragPicture(targetMs = 50.0, minSize = 256, maxSize = 1024, step = 32)
        // 1024 x 683 in 90 ms (about what gate G0 measured on the phone for a preview after an exposure change)
        d.record(1024 * 683, 90_000_000)
        val s = d.size()
        // 50 ms buys about 50/90 of the pixels: about 0.39 MP, 768 px long side at 3:2
        assertTrue("size $s", s in 704..800)
        assertEquals(0, s % 32)
        // a much faster engine: the full preview size
        val fast = DragPicture(maxSize = 1024)
        fast.record(1024 * 683, 5_000_000)
        assertEquals(1024, fast.size())
        // a very slow one: never below the minimum
        val slow = DragPicture(minSize = 320)
        slow.record(1024 * 683, 2_000_000_000)
        assertEquals(320, slow.size())
    }

    @Test
    fun fixedPartIsFittedFromDifferentSizes() {
        // 10 ms fixed + 50 ns per pixel, measured at two sizes
        val d = DragPicture(targetMs = 50.0, minSize = 128, maxSize = 2048, step = 32)
        d.record(1024 * 683, (10e6 + 50.0 * 1024 * 683).toLong())
        d.record(400 * 267, (10e6 + 50.0 * 400 * 267).toLong())
        assertEquals(10.0, d.fixedNs / 1e6, 0.01)
        assertEquals(50.0, d.nsPerPixel!!, 0.01)
        // (50 - 10) ms / 50 ns = 800 000 px -> sqrt(800 000 x 1.5) = 1095 -> 1088
        assertEquals(1088, d.size())
        // a fixed part above the target (as on the CI emulator, about 70 ms): the smallest size
        val e = DragPicture(targetMs = 50.0, minSize = 320)
        e.record(256 * 171, 72_000_000); e.record(512 * 341, 112_000_000)
        assertEquals(320, e.size())
    }

    @Test
    fun dragRendersAreCountedSeparately() {
        val d = DragPicture()
        d.record(1000, 1_000_000)
        assertEquals(null, d.lastDragMs)
        d.record(1000, 2_000_000, drag = true)
        assertEquals(2.0, d.lastDragMs!!, 1e-9)
        assertEquals(1, d.dragRenders)
    }

    @Test
    fun thumbnailsAreSmallAndAverage() {
        val px = ByteArray(4 * 4 * 4) { i -> if (i % 4 == 3) -1 else if ((i / 4) % 2 == 0) 0 else 100.toByte() }
        val t = Thumbnails.downscale(RenderedImage(4, 4, px), 2)
        assertEquals(2, t.width); assertEquals(2, t.height)
        assertEquals(50, t.rgba[0].toInt() and 0xff)
        assertEquals(255, t.rgba[3].toInt() and 0xff)
    }
}

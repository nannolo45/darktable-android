// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brief P2f deliverable 10: crop's aspect list, both directions, against src/iop/crop.c's arithmetic. */
class CropAspectTest {
    private val schema by lazy { SchemaLoader.fromResources() }
    private val spec by lazy { schema.modules.getValue("crop").aspect!! }
    private val eps = 1e-9

    private fun near(a: CropAspect.Frame, l: Double, t: Double, r: Double, b: Double) {
        assertEquals(l, a.left, 1e-6); assertEquals(t, a.top, 1e-6); assertEquals(r, a.right, 1e-6); assertEquals(b, a.bottom, 1e-6)
    }

    @Test fun theOverrideMakesTheListUsable() {
        val c = schema.modules.getValue("crop").control(spec.controlId) as Control.ComboBox
        assertEquals(Binding.Aspect, c.binding)
        assertTrue(c.isEditable)
        assertEquals(23, spec.entries.size)
        assertEquals(listOf("freehand", "original image", "square  1.00"), spec.entries.take(3).map { it.label })
        assertEquals("3:1, panorama  3.00", spec.entries.last().label)
        assertEquals(listOf("cx", "cy", "cw", "ch", "ratio_d", "ratio_n"),
            listOf(spec.left, spec.top, spec.right, spec.bottom, spec.ratioD, spec.ratioN))
    }

    @Test fun parametersToEntryAndBack() {
        // every entry: chosen -> parameters -> the same entry
        for ((i, e) in spec.entries.withIndex()) {
            val c = CropAspect.choose(spec, i, 0, CropAspect.Frame(0.0, 0.0, 1.0, 1.0), 6000.0, 4000.0)
            assertEquals(e.label, i, CropAspect.entryOf(spec, c.ratioD, c.ratioN))
            assertEquals(e.label, CropAspect.label(spec, c.ratioD, c.ratioN))
        }
        // a desktop edit: 3:2 flipped to portrait (ratio_d negative) is still the 3:2 entry
        assertEquals("3:2, 4x6, 35mm  1.50", CropAspect.label(spec, -3, 2))
        // the defaults (-1, -1) take darktablerc's last choice on desktop: freehand
        assertEquals("freehand", CropAspect.label(spec, -1, -1))
        // a ratio typed on desktop: shown as desktop shows a custom ratio
        assertEquals(-1, CropAspect.entryOf(spec, 7, 3))
        assertEquals("7:3 2.33", CropAspect.label(spec, 7, 3))
    }

    @Test fun aspectAsDesktopComputesIt() {
        assertEquals(1.5, CropAspect.aspect(spec, 3, 2, 6000.0, 4000.0), eps)
        assertEquals(2.0 / 3.0, CropAspect.aspect(spec, -3, 2, 6000.0, 4000.0), eps)
        assertEquals(-1.0, CropAspect.aspect(spec, 0, 0, 6000.0, 4000.0), eps)
        // original image: the input's own ratio, portrait when ratio_d is negative on a landscape image
        assertEquals(1.5, CropAspect.aspect(spec, 1, 0, 6000.0, 4000.0), eps)
        assertEquals(2.0 / 3.0, CropAspect.aspect(spec, -1, 0, 6000.0, 4000.0), eps)
    }

    @Test fun choosingReshapesTheFrameAsDesktop() {
        // square on a 3:2 landscape, uncropped: desktop grows the height from the width, then fits it in
        val sq = CropAspect.choose(spec, spec.entries.indexOfFirst { it.label.startsWith("square") }, 0, CropAspect.Frame(0.0, 0.0, 1.0, 1.0), 6000.0, 4000.0)
        assertEquals(1 to 1, sq.ratioD to sq.ratioN)
        // (desktop moves the left side with GRAB_HORIZONTAL while fitting it in: _aspect_apply)
        near(sq.frame, 1.0 / 3.0, 0.0, 1.0, 1.0)
        assertEquals("square in pixels", 4000.0, (sq.frame.right - sq.frame.left) * 6000.0, 1e-6)
        // 16:9 on the same: the height shrinks around the centre
        val wide = CropAspect.choose(spec, spec.entries.indexOfFirst { it.label.startsWith("16:9") }, 0, CropAspect.Frame(0.0, 0.0, 1.0, 1.0), 6000.0, 4000.0)
        val h = 6000.0 / (16.0 / 9.0) / 4000.0
        near(wide.frame, 0.0, (1 - h) / 2, 1.0, (1 + h) / 2)
        // freehand changes nothing
        val free = CropAspect.choose(spec, 0, 3, CropAspect.Frame(0.1, 0.2, 0.7, 0.9), 6000.0, 4000.0)
        near(free.frame, 0.1, 0.2, 0.7, 0.9)
    }

    @Test fun flipSwapsPortraitAndLandscape() {
        val c = CropAspect.flip(spec, 3, 2, CropAspect.Frame(0.0, 0.0, 1.0, 1.0), 6000.0, 4000.0)
        assertEquals(-3 to 2, c.ratioD to c.ratioN)
        val w = (c.frame.right - c.frame.left) * 6000.0
        val hh = (c.frame.bottom - c.frame.top) * 4000.0
        assertEquals(2.0 / 3.0, w / hh, 1e-6)
        assertEquals("flipped back", 3, CropAspect.flip(spec, -3, 2, c.frame, 6000.0, 4000.0).ratioD)
    }

    @Test fun movingAMarginKeepsTheRatio() {
        // 3:2 chosen; the left margin moves in: the height follows, centred
        val f = CropAspect.keep(spec, "cx", 3, 2, CropAspect.Frame(0.25, 0.0, 1.0, 1.0), 6000.0, 4000.0)
        assertEquals(1.5, (f.right - f.left) * 6000.0 / ((f.bottom - f.top) * 4000.0), 1e-6)
        assertEquals(0.25, f.left, 1e-9)
        // freehand: nothing follows
        near(CropAspect.keep(spec, "cx", 0, 0, CropAspect.Frame(0.25, 0.0, 1.0, 1.0), 6000.0, 4000.0), 0.25, 0.0, 1.0, 1.0)
    }

    @Test fun inputSizeFromTheCroppedImage() {
        assertEquals(6000.0 to 4000.0, CropAspect.inputSize(3000, 2000, CropAspect.Frame(0.25, 0.25, 0.75, 0.75), true))
        assertEquals(3000.0 to 2000.0, CropAspect.inputSize(3000, 2000, CropAspect.Frame(0.25, 0.25, 0.75, 0.75), false))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.schema.Binding
import app.dtandroid.schema.CropTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.min

/**
 * Brief P3a deliverable 6: the crop tool on phone and tablet (the uncropped picture, the dimming outside the frame,
 * the frame, the rule-of-thirds grid, the handles on the corners and sides, the size of the cropped picture
 * under the photo), and drags with Compose's touch input on the photo: a corner resizes, inside moves the frame.
 */
abstract class P3aCropScreens : ScreenshotBase() {
    private val crop = InstanceKey("crop", 0)

    /** Crop on with a 3:2 frame narrowed from the left, then crop's panel opened: the tool. */
    protected fun showTool(): DarkroomController {
        val spec = TestSetup.schema.modules.getValue("crop").aspect!!
        val c = show(filmstrip = layout == DarkroomLayout.TABLET) {
            chooseAspect(crop, spec.entries.indexOfFirst { it.label.startsWith("3:2") })
            setSlider(crop, Binding.Param("cx"), 0.2, false)
            toggleExpanded(crop)
            syncCropTool()
        }
        settle(c)
        assertNotNull("the tool is open", c.state.value.cropTool?.picture)
        return c
    }

    /** Where a point of the picture (fractions) is on the photo node, in its own pixels. */
    protected fun onPhoto(c: DarkroomController, px: Float, py: Float): Offset {
        val node = compose.onNodeWithContentDescription("photo").fetchSemanticsNode()
        val vp = c.currentViewport!!
        val t = c.state.value.cropTool!!
        val insetX = (node.size.width - vp.widthPx) / 2f
        val insetY = (node.size.height - vp.heightPx) / 2f
        val fit = min(vp.widthPx.toFloat() / t.inW, vp.heightPx.toFloat() / t.inH)
        val left = insetX + vp.widthPx / 2f - 0.5f * t.inW * fit
        val top = insetY + vp.heightPx / 2f - 0.5f * t.inH * fit
        return Offset(left + px * t.inW * fit, top + py * t.inH * fit)
    }

    protected fun drag(from: Offset, to: Offset) {
        compose.onNodeWithContentDescription("photo").performTouchInput {
            down(from)
            val steps = 8
            for (i in 1..steps) moveTo(from + (to - from) * (i.toFloat() / steps))
            up()
        }
        compose.waitForIdle()
    }

    protected fun toolShows(name: String) {
        val c = showTool()
        val t = c.state.value.cropTool!!
        assertEquals(0.2f, t.clip.x, 1e-4f)
        capture("$name-p3a-01-crop-tool")
    }

    protected fun touchResizesAndMoves(name: String) {
        val c = showTool()
        val g0 = c.state.value.cropTool!!.clip
        // the bottom right corner, a little inside the frame, pulled up and left
        drag(onPhoto(c, g0.right - 0.01f, g0.bottom - 0.01f), onPhoto(c, g0.right - 0.21f, g0.bottom - 0.21f))
        val g1 = c.state.value.cropTool!!.clip
        assertTrue("corner resized: $g0 -> $g1", g1.w < g0.w - 0.1f && g1.h < g0.h - 0.05f)
        assertEquals("left side stays", g0.x, g1.x, 1e-3f)
        assertEquals("3:2 kept", 1.5, g1.w.toDouble() * c.state.value.cropTool!!.inW / (g1.h.toDouble() * c.state.value.cropTool!!.inH), 2e-3)
        // inside the frame: the whole frame moves, its size stays
        val mx = g1.x + g1.w / 2
        val my = g1.y + g1.h / 2
        drag(onPhoto(c, mx, my), onPhoto(c, mx + 0.1f, my + 0.05f))
        val g2 = c.state.value.cropTool!!.clip
        assertEquals("moved right", g1.x + 0.1f, g2.x, 0.01f)
        assertEquals("same width", g1.w, g2.w, 1e-4f)
        // dragged far beyond the picture: the frame stays inside
        drag(onPhoto(c, g2.x + g2.w / 2, g2.y + g2.h / 2), onPhoto(c, 3f, 3f))
        val g3 = c.state.value.cropTool!!.clip
        assertTrue("inside: $g3", g3.right <= 1f + 1e-5f && g3.bottom <= 1f + 1e-5f && g3.x >= 0f && g3.y >= 0f)
        assertTrue(c.state.value.cropTool!!.touched)
        // the edit is untouched until the tool is left
        val cx = (c.state.value.modules.first { it.op == "crop" }.params["cx"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
        assertEquals(0.2, cx, 1e-6)
        capture("$name-p3a-02-crop-dragged")
        // a drag away from the frame (the frame is not everywhere) does not move it
        val g = c.state.value.cropTool!!.clip
        if (g.x > 0.1f) {
            drag(onPhoto(c, g.x / 3, 0.5f), onPhoto(c, g.x / 3 + 0.05f, 0.5f))
            assertEquals(g, c.state.value.cropTool!!.clip)
        }
        assertEquals(CropTool.GRAB_NONE, c.cropHit(-0.5f, -0.5f, 0.05f, 0.05f))
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCropScreenshots : P3aCropScreens() {
    override val layout = DarkroomLayout.PHONE

    @Test fun cropTool() = toolShows("phone")
    @Test fun cropToolByTouch() = touchResizesAndMoves("phone")
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletCropScreenshots : P3aCropScreens() {
    override val layout = DarkroomLayout.TABLET

    @Test fun cropTool() = toolShows("tablet")
    @Test fun cropToolByTouch() = touchResizesAndMoves("tablet")
}

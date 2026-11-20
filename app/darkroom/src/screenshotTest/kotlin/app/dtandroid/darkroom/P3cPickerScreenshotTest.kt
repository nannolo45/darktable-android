// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.min

/**
 * Brief P3c deliverable 7: the colour picker on phone and tablet: an armed point (rgb curve's "show color") and an
 * armed area drawn by touch (white balance's "from image area"), with the picker button shown on and the picked
 * values under the photo; the picker armed by tapping its button, and the area drawn and moved with Compose's touch
 * input on the photo.
 */
abstract class P3cPickerScreens : ScreenshotBase() {
    /** Where a point of the picture (fractions) is on the photo node, in its own pixels, at fit. */
    private fun onPhoto(c: DarkroomController, px: Float, py: Float): Offset {
        val node = compose.onNodeWithContentDescription("photo").fetchSemanticsNode()
        val vp = c.currentViewport!!
        val info = c.state.value.info!!
        val insetX = (node.size.width - vp.widthPx) / 2f
        val insetY = (node.size.height - vp.heightPx) / 2f
        val fit = min(vp.widthPx.toFloat() / info.width, vp.heightPx.toFloat() / info.height)
        val (x, y) = PhotoGeometry.viewPoint(px, py, vp.widthPx, vp.heightPx, info.width, info.height, fit, 1f, 0.5f, 0.5f)
        return Offset(insetX + x, insetY + y)
    }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", cond())
    }

    protected fun armedPoint(name: String) {
        val curve = InstanceKey("rgbcurve", 0)
        val c = show(filmstrip = layout == DarkroomLayout.TABLET) {
            setEnabled(curve, true)
            toggleExpanded(curve)
        }
        compose.onNodeWithContentDescription("colour picker: show color").performClick()
        waitUntil("armed") { c.state.value.picker?.readout?.isNotEmpty() == true }
        // a tap on the photo: the point moves there
        val at = onPhoto(c, 0.3f, 0.4f)
        compose.onNodeWithContentDescription("photo").performTouchInput { down(at); up() }
        waitUntil("point placed") { c.state.value.picker?.selection?.point?.let { kotlin.math.abs(it[0] - 0.3) < 0.01 } == true }
        waitUntil("readout") { c.state.value.picker?.readout?.size == 1 }
        settle(c)
        capture("$name-p3c-01-picker-point")
    }

    protected fun armedArea(name: String) {
        val wb = InstanceKey("temperature", 0)
        val c = show(filmstrip = layout == DarkroomLayout.TABLET) { toggleExpanded(wb) }
        compose.onNodeWithContentDescription("colour picker: from image area").performClick()
        waitUntil("armed") { c.state.value.picker?.readout?.size == 3 }
        // a drag on the photo away from the area's handles draws a new area... the first area covers the picture,
        // so a drag inside it moves it; first make it smaller from its top left corner
        val p0 = c.state.value.picker!!.selection!!.area!!
        val from = onPhoto(c, p0[0].toFloat() + 0.005f, p0[1].toFloat() + 0.005f)
        val to = onPhoto(c, 0.55f, 0.45f)
        compose.onNodeWithContentDescription("photo").performTouchInput {
            down(from)
            for (i in 1..8) moveTo(from + (to - from) * (i / 8f))
            up()
        }
        waitUntil("resized") { c.state.value.picker?.selection?.area?.let { kotlin.math.abs(it[0] - 0.55) < 0.02 } == true }
        // inside the area: it moves
        val a1 = c.state.value.picker!!.selection!!.area!!
        val mid = onPhoto(c, ((a1[0] + a1[2]) / 2).toFloat(), ((a1[1] + a1[3]) / 2).toFloat())
        val by = onPhoto(c, ((a1[0] + a1[2]) / 2).toFloat() - 0.1f, ((a1[1] + a1[3]) / 2).toFloat())
        compose.onNodeWithContentDescription("photo").performTouchInput {
            down(mid)
            for (i in 1..8) moveTo(mid + (by - mid) * (i / 8f))
            up()
        }
        waitUntil("moved") { c.state.value.picker?.selection?.area?.let { kotlin.math.abs(it[0] - (a1[0] - 0.1)) < 0.02 } == true }
        val a2 = c.state.value.picker!!.selection!!.area!!
        assertEquals("same width", a1[2] - a1[0], a2[2] - a2[0], 1e-3)
        waitUntil("readout") { c.state.value.picker?.readout?.size == 3 }
        settle(c)
        assertNotNull(c.state.value.picker)
        capture("$name-p3c-02-picker-area")
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePickerScreenshots : P3cPickerScreens() {
    override val layout = DarkroomLayout.PHONE

    @Test fun pickerPoint() = armedPoint("phone")
    @Test fun pickerArea() = armedArea("phone")
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletPickerScreenshots : P3cPickerScreens() {
    override val layout = DarkroomLayout.TABLET

    @Test fun pickerPoint() = armedPoint("tablet")
    @Test fun pickerArea() = armedArea("tablet")
}

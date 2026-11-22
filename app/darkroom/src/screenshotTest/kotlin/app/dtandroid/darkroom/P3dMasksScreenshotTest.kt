// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.min

/**
 * Brief P3d stage 1: the masks tab, drawing mode and the mask indicator in a module header, in both skins on the
 * phone and once on the tablet. The brush stroke and the indicator's tap go through Compose's touch input.
 */
abstract class P3dMasksScreens : ScreenshotBase() {
    private val exposure = InstanceKey("exposure", 0)

    private fun tokens(modern: Boolean) = if (modern) DtTokens.load(Skin.MODERN) else DtTokens.load()
    private fun skin(modern: Boolean) = if (modern) "modern" else "darktable"

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

    /** exposure with a drawn mask of a circle and a gradient; the masks tab open on it */
    private suspend fun DarkroomController.withMask() {
        setView(BottomView.MASKS)
        masks.select(exposure)
        masks.setMode(MaskMode.DRAWN)
        masks.startDraw(ShapeType.CIRCLE)
        masks.touchDown(0.35, 0.45); masks.touchUp()
        masks.finishDraw()
        masks.startDraw(ShapeType.GRADIENT)
        masks.setDrawValue("rotation", 10.0)
        masks.touchDown(0.5, 0.3); masks.touchUp()
        masks.finishDraw()
    }

    protected fun masksTab(name: String, modern: Boolean) {
        val c = show(tokens = tokens(modern)) { withMask() }
        waitUntil("two shapes") { c.masks.state.value.module?.members?.size == 2 }
        // the circle tapped in the list: its outline on the photo
        val circle = c.masks.state.value.forms.first { it.type == "circle" }
        kotlinx.coroutines.runBlocking { c.masks.selectShape(circle.id) }
        waitUntil("outline") { c.masks.state.value.outline != null }
        settle(c)
        capture("$name-p3d-01-masks-tab-${skin(modern)}")
    }

    protected fun drawing(name: String, modern: Boolean) {
        val c = show(tokens = tokens(modern)) {
            setView(BottomView.MASKS)
            masks.select(exposure)
            masks.setMode(MaskMode.DRAWN)
            masks.startDraw(ShapeType.BRUSH)
        }
        waitUntil("tool panel") { c.masks.state.value.draw != null }
        // one finger paints a stroke on the photo
        val from = onPhoto(c, 0.2f, 0.6f)
        val to = onPhoto(c, 0.7f, 0.4f)
        compose.onNodeWithContentDescription("photo").performTouchInput {
            down(from)
            for (i in 1..10) moveTo(from + (to - from) * (i / 10f))
            up()
        }
        waitUntil("stroke") { c.masks.state.value.draw?.strokes?.size == 1 }
        assertTrue(c.masks.state.value.draw!!.strokes[0].points.size > 5)
        settle(c)
        capture("$name-p3d-02-drawing-brush-${skin(modern)}")
    }

    protected fun indicator(name: String, modern: Boolean) {
        val c = show(tokens = tokens(modern)) {
            withMask()
            setView(BottomView.MODULES)
        }
        waitUntil("indicator") { c.state.value.maskModes[exposure] == "flexi" || c.state.value.maskModes[exposure] == "drawn" }
        settle(c)
        capture("$name-p3d-03-indicator-${skin(modern)}")
        compose.onNodeWithContentDescription("mask of exposure: drawn. Open the masks tab").performClick()
        waitUntil("masks tab") { c.state.value.view == BottomView.MASKS }
        assertEquals(exposure, c.masks.state.value.key)
    }

    protected fun showMask(name: String) {
        val c = show { withMask() }
        c.masks.toggleShowMask()
        assertNotNull(c.state.value.showMask)
        settle(c)
        capture("$name-p3d-04-show-mask")
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneMasksScreenshots : P3dMasksScreens() {
    override val layout = DarkroomLayout.PHONE

    @Test fun masksTab() = masksTab("phone", modern = false)
    @Test fun masksTabModern() = masksTab("phone", modern = true)
    @Test fun drawing() = drawing("phone", modern = false)
    @Test fun drawingModern() = drawing("phone", modern = true)
    @Test fun indicator() = indicator("phone", modern = false)
    @Test fun indicatorModern() = indicator("phone", modern = true)
    @Test fun showMask() = showMask("phone")
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletMasksScreenshots : P3dMasksScreens() {
    override val layout = DarkroomLayout.TABLET

    @Test fun masksTab() = masksTab("tablet", modern = false)
}

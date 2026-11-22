// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3d stage 2b: the parametric section and the refinement of the masks tab, in both skins on the phone and once
 * on the tablet. The exposure module is in parametric mode with two channels set, the output range of one shown.
 */
abstract class P3dParametricScreens : ScreenshotBase() {
    private val exposure = InstanceKey("exposure", 0)

    private fun tokens(modern: Boolean) = if (modern) DtTokens.load(Skin.MODERN) else DtTokens.load()
    private fun skin(modern: Boolean) = if (modern) "modern" else "darktable"

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", cond())
    }

    private suspend fun DarkroomController.withRanges() {
        setView(BottomView.MASKS)
        masks.select(exposure)
        masks.setMode(MaskMode.PARAMETRIC)
        val specs = ParamChannels.of(parametric.state.value.space)
        parametric.addChannel(specs.first { it.base == "g" })
        parametric.setRange(parametric.state.value.elements.first { it.base == "g" }.id, false, listOf(0.2, 0.3, 0.7, 0.85))
        parametric.addChannel(specs.first { it.base == "R" })
        val r = parametric.state.value.elements.first { it.base == "R" }.id
        parametric.setRange(r, false, listOf(0.0, 0.0, 0.5, 0.6))
        parametric.setInvert(r, true)
        val g = parametric.state.value.elements.first { it.base == "g" }.id
        parametric.setRange(g, true, listOf(0.1, 0.1, 0.9, 1.0))
        parametric.setShowOutput(g, true)
        parametric.toggleRefine()
        parametric.setRefine("feathering_radius", 20.0)
        parametric.setRefine("blur_radius", 5.0)
    }

    protected fun parametricSection(name: String, modern: Boolean) {
        val c = show(tokens = tokens(modern)) { withRanges() }
        waitUntil("ranges") { c.parametric.state.value.elements.any { it.base == "R" && it.inverse } }
        compose.onNodeWithContentDescription("R input range").performScrollTo()
        settle(c)
        capture("$name-p3d2b-01-parametric-${skin(modern)}")
    }

    protected fun refinement(name: String, modern: Boolean) {
        val c = show(tokens = tokens(modern)) { withRanges() }
        waitUntil("refinement") { c.parametric.state.value.blend?.blurRadius == 5.0 }
        compose.onNodeWithText("reset refinement").performScrollTo()
        settle(c)
        capture("$name-p3d2b-02-refinement-${skin(modern)}")
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneParametricScreenshots : P3dParametricScreens() {
    override val layout = app.dtandroid.darkroom.ui.DarkroomLayout.PHONE

    @Test fun parametric() = parametricSection("phone", modern = false)
    @Test fun parametricModern() = parametricSection("phone", modern = true)
    @Test fun refinement() = refinement("phone", modern = false)
    @Test fun refinementModern() = refinement("phone", modern = true)
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletParametricScreenshots : P3dParametricScreens() {
    override val layout = app.dtandroid.darkroom.ui.DarkroomLayout.TABLET

    @Test fun parametric() = parametricSection("tablet", modern = false)
}

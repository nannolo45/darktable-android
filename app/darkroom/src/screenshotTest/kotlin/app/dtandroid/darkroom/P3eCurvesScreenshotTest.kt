// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.darkroom.ui.graph.CurveEdit
import app.dtandroid.darkroom.ui.graph.CurveLayout
import app.dtandroid.darkroom.ui.graph.CurveNode
import app.dtandroid.darkroom.ui.graph.LevelsEdit
import app.dtandroid.darkroom.ui.graph.ToneEqMath
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Brief P3e: the graph editors on phone (darktable and modern skins) and tablet: tone curve, rgb curve (independent
 * channels), rgb levels and the tone equalizer, each with a curve set through the controller; and touch on the
 * graph (Compose's touch input): a node dragged, a node added by a tap, a double tap, levels, a band.
 */
abstract class P3eCurveScreens : ScreenshotBase() {
    private val tone = InstanceKey("tonecurve", 0)
    private val rgb = InstanceKey("rgbcurve", 0)
    private val levels = InstanceKey("rgblevels", 0)
    private val eq = InstanceKey("toneequal", 0)
    private val toneL = CurveLayout.TONECURVE
    private val rgbL = CurveLayout.RGBCURVE
    private fun n(x: Double, y: Double) = CurveNode(x, y)
    private val three = listOf(n(0.0, 0.0), n(0.5, 0.5), n(1.0, 1.0))
    private val s = listOf(n(0.0, 0.0), n(0.25, 0.16), n(0.7, 0.82), n(1.0, 1.0))
    protected open val tokens: DtTokens = DtTokens.load()

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!runCatching(cond).getOrDefault(false) && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", runCatching(cond).getOrDefault(false))
    }

    private fun params(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }.params

    /** The histogram behind the graph has arrived (the editor reads it when the controller is quiet). */
    private fun histogram(c: DarkroomController) {
        waitUntil("histogram") { compose.mainClock.advanceTimeBy(100); c.histogramCache != null }
        settle(c)
    }

    private fun open(setup: suspend DarkroomController.() -> Unit) = show(filmstrip = layout == DarkroomLayout.TABLET, tokens = tokens, setup = setup)

    protected fun toneCurve(name: String) {
        val c = open { curveSet(tone, toneL.writes(0, s, 2) + (toneL.typePath(0) to JsonPrimitive(2L))); toggleExpanded(tone) }
        histogram(c)
        capture("$name-p3e-01-tonecurve")
    }

    protected fun rgbCurve(name: String) {
        val c = open {
            setParam(rgb, "curve_autoscale", JsonPrimitive(1L))
            curveSet(rgb, rgbL.writes(1, s, 2))
            toggleExpanded(rgb)
            chooseTab(rgb, 2, 1)
        }
        histogram(c)
        capture("$name-p3e-02-rgbcurve")
    }

    protected fun rgbLevels(name: String) {
        val c = open {
            setParam(levels, "autoscale", JsonPrimitive(1L))
            curveSet(levels, LevelsEdit.writes(0, doubleArrayOf(0.08, 0.4, 0.92)))
            toggleExpanded(levels)
        }
        histogram(c)
        capture("$name-p3e-03-rgblevels")
    }

    protected fun toneEqualizer(name: String) {
        val c = open {
            curveSet(eq, ToneEqMath.writes(doubleArrayOf(0.6, 0.5, 0.3, 0.1, 0.0, 0.0, -0.2, -0.4, -0.5)))
            toggleExpanded(eq)
            chooseTab(eq, 0, 1)
        }
        settle(c)
        capture("$name-p3e-04-toneeq")
    }

    // ------------------------------------------------------------------ touch

    /** A point of the graph (fractions, y up) in the graph node's own pixels. */
    private fun at(desc: String, fx: Double, fy: Double): Offset {
        val size = compose.onNodeWithContentDescription(desc).fetchSemanticsNode().size
        val density = org.robolectric.RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val inset = tokens.space.l.value * density
        return Offset((inset + fx * (size.width - 2 * inset)).toFloat(), (inset + (1 - fy) * (size.height - 2 * inset)).toFloat())
    }

    private fun drag(desc: String, from: Offset, to: Offset) {
        compose.onNodeWithContentDescription(desc).performTouchInput {
            down(from)
            for (i in 1..8) moveTo(from + (to - from) * (i / 8f))
            up()
        }
    }

    protected fun curveTouch(name: String) {
        val c = open { curveSet(tone, toneL.writes(0, three, 2)); toggleExpanded(tone) }
        histogram(c)
        val items0 = c.state.value.history.items.size
        // a node dragged: one history item, the nodes in order
        drag("curve graph", at("curve graph", 0.5, 0.5), at("curve graph", 0.55, 0.8))
        waitUntil("node moved") { abs(toneL.read(params(c, tone), 0)[1].y - 0.8) < 0.03 }
        waitUntil("one item") { c.state.value.history.items.size == items0 + 1 }
        assertTrue(CurveEdit.valid(toneL.read(params(c, tone), 0)))
        assertEquals(3, toneL.countOf(params(c, tone), 0))
        // a tap on empty space adds a node there
        compose.onNodeWithContentDescription("curve graph").performTouchInput { down(at("curve graph", 0.2, 0.7)); up() }
        waitUntil("node added") { toneL.countOf(params(c, tone), 0) == 4 }
        waitUntil("an item more") { c.state.value.history.items.size == items0 + 2 }
        val nodes = toneL.read(params(c, tone), 0)
        assertTrue(CurveEdit.valid(nodes))
        assertEquals(0.2, nodes[1].x, 0.03)
        settle(c)
        capture("$name-p3e-05-tonecurve-touched")
        // a node dragged off the graph is removed
        drag("curve graph", at("curve graph", nodes[1].x, nodes[1].y), Offset(-200f, at("curve graph", 0.5, 0.5).y))
        waitUntil("node removed") { toneL.countOf(params(c, tone), 0) == 3 }
        // a double tap puts the curve back
        compose.onNodeWithContentDescription("curve graph").performTouchInput { doubleClick(at("curve graph", 0.8, 0.3)) }
        waitUntil("reset") { toneL.read(params(c, tone), 0) == CurveEdit.IDENTITY }
    }

    protected fun levelsTouch() {
        val c = open { setParam(levels, "autoscale", JsonPrimitive(0L)); curveSet(levels, LevelsEdit.writes(0, doubleArrayOf(0.0, 0.5, 1.0))); toggleExpanded(levels) }
        histogram(c)
        val items0 = c.state.value.history.items.size
        // the black handle (in the strip under the histogram) to the right
        val size = compose.onNodeWithContentDescription("levels graph").fetchSemanticsNode().size
        val density = org.robolectric.RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val inset = tokens.space.l.value * density
        val pw = size.width - 2 * inset
        val y = size.height - tokens.sizes.touch.value * density / 2
        drag("levels graph", Offset(inset, y), Offset((inset + 0.25 * pw).toFloat(), y))
        waitUntil("black moved") { abs(LevelsEdit.read(params(c, levels), 0)[0] - 0.25) < 0.03 }
        waitUntil("one item") { c.state.value.history.items.size == items0 + 1 }
        val l = LevelsEdit.read(params(c, levels), 0)
        assertTrue("grey and white keep their order: ${l.toList()}", l[0] < l[1] && l[1] < l[2])
    }

    protected fun toneEqTouch() {
        val c = open { curveSet(eq, ToneEqMath.writes(DoubleArray(9))); toggleExpanded(eq); chooseTab(eq, 0, 1) }
        settle(c)
        val items0 = c.state.value.history.items.size
        // the fifth band (-4 EV) dragged up: it goes up and its neighbours follow
        val size = compose.onNodeWithContentDescription("tone equalizer graph").fetchSemanticsNode().size
        val x = size.width * 4.5f / 9
        val y0 = size.height / 2f
        drag("tone equalizer graph", Offset(x, y0), Offset(x, y0 - size.height / 4f))
        waitUntil("band raised") { ToneEqMath.read(params(c, eq))[4] > 0.2 }
        waitUntil("one item") { c.state.value.history.items.size == items0 + 1 }
        val g = ToneEqMath.read(params(c, eq))
        assertTrue("the neighbours follow: ${g.toList()}", g[3] > 0.05 && g[5] > 0.05)
        assertTrue("the far band hardly moves", abs(g[0]) < 0.1)
        // a double tap puts the bands back
        compose.onNodeWithContentDescription("tone equalizer graph").performTouchInput { doubleClick(Offset(x, y0)) }
        waitUntil("reset") { ToneEqMath.read(params(c, eq)).all { it == 0.0 } }
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCurveScreenshots : P3eCurveScreens() {
    override val layout = DarkroomLayout.PHONE
    @Test fun toneCurveShot() = toneCurve("phone")
    @Test fun rgbCurveShot() = rgbCurve("phone")
    @Test fun rgbLevelsShot() = rgbLevels("phone")
    @Test fun toneEqualizerShot() = toneEqualizer("phone")
    @Test fun curveTouchTest() = curveTouch("phone")
    @Test fun levelsTouchTest() = levelsTouch()
    @Test fun toneEqTouchTest() = toneEqTouch()
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModernPhoneCurveScreenshots : P3eCurveScreens() {
    override val layout = DarkroomLayout.PHONE
    override val tokens: DtTokens = DtTokens.load(Skin.MODERN)
    @Test fun toneCurveShot() = toneCurve("phone-modern")
    @Test fun rgbCurveShot() = rgbCurve("phone-modern")
    @Test fun rgbLevelsShot() = rgbLevels("phone-modern")
    @Test fun toneEqualizerShot() = toneEqualizer("phone-modern")
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletCurveScreenshots : P3eCurveScreens() {
    override val layout = DarkroomLayout.TABLET
    @Test fun toneCurveShot() = toneCurve("tablet")
    @Test fun rgbCurveShot() = rgbCurve("tablet")
    @Test fun rgbLevelsShot() = rgbLevels("tablet")
    @Test fun toneEqualizerShot() = toneEqualizer("tablet")
}

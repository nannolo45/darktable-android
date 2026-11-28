// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.darkroom.ui.graph.BandHost
import app.dtandroid.darkroom.ui.graph.CurveLayout
import app.dtandroid.darkroom.ui.graph.CurveNode
import app.dtandroid.darkroom.ui.graph.CurveView
import app.dtandroid.darkroom.ui.graph.GraphViews
import app.dtandroid.engine.api.paramAt
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Brief P6f: the graph editors of base curve, saturation curve, lowlight vision, raw denoise and denoise (profiled), each
 * with a non-default curve, denoise's wavelet tabs in both colour modes, tone curve with "scale for graph" and rgb curve
 * zoomed, in the three skins on the phone (stand-in engine). Touch: a base curve node dragged (one history item), a
 * pinch on rgb curve (the graph zooms, nothing is written).
 */
abstract class P6fGraphScreens : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    protected abstract val skin: Skin
    private val tokens get() = DtTokens.load(skin)
    private fun n(x: Double, y: Double) = CurveNode(x, y)

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!runCatching(cond).getOrDefault(false) && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", runCatching(cond).getOrDefault(false))
    }

    private fun params(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }.params

    /** The panel scrolled until [target] is composed (the module list is lazy: a control far down is not there yet). */
    private fun scrollTo(target: SemanticsMatcher) {
        val n = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
        for (i in 0 until n) {
            if (compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty()) break
            runCatching { compose.onAllNodes(hasScrollAction())[i].performScrollToNode(target) }
            compose.waitForIdle()
        }
        runCatching { compose.onNode(hasScrollAction() and hasAnyDescendant(target)).performScrollToNode(target) }
        compose.waitForIdle()
    }

    private fun shot(name: String, op: String, graph: String, setup: suspend DarkroomController.(InstanceKey) -> Unit) {
        val key = InstanceKey(op, 0)
        val c = show(tokens = tokens) { setEnabled(key, true); setup(key); toggleExpanded(key) }
        c.goToModule(key)
        settle(c)
        scrollTo(hasContentDescription(graph, substring = true))
        settle(c)
        try { compose.onNodeWithContentDescription(graph, substring = true).assertExists() } catch (e: AssertionError) {
            throw AssertionError(e.message + compose.onRoot().printToString(60).take(6000), e)
        }
        capture("phone-${skin.key}-p6f-$name")
    }

    private val bandXs = { bands: Int -> (0 until bands).map { it / (bands - 1.0) } }

    @Test fun shot01() {
        GraphViews.logBase.clear(); GraphViews.zoom.clear()
        shot("01-basecurve", "basecurve", "curve graph") { k ->
            curveSet(k, CurveLayout.BASECURVE.writes(0, listOf(n(0.0, 0.0), n(0.12, 0.22), n(0.45, 0.72), n(1.0, 1.0)), 0))
        }
    }

    @Test fun shot02() {
        GraphViews.logBase.clear(); GraphViews.zoom.clear()
        shot("02-satcurve-brilliance", "satcurvergb", "curve graph") { k ->
            curveSet(k, CurveLayout.SATCURVE.writes(0, listOf(n(0.0, 0.5), n(0.3, 0.68), n(1.0, 0.55)), 0))
            curveSet(k, CurveLayout.SATCURVE.writes(1, listOf(n(0.0, 0.5), n(0.5, 0.35), n(1.0, 0.5)), 0))
            chooseTab(k, TestSetup.schema.modules.getValue("satcurvergb").allControls().first { it is app.dtandroid.schema.Control.Tabs }.id, 1)
        }
    }

    @Test fun shot03() {
        GraphViews.logBase.clear(); GraphViews.zoom.clear()
        shot("03-lowlight", "lowlight", "band graph") { k ->
            bandApply(k, bandXs(6).mapIndexed { i, x -> "transition_x[$i]" to JsonPrimitive(x) } +
                listOf(0.1, 0.25, 0.55, 0.85, 1.0, 1.0).mapIndexed { i, y -> "transition_y[$i]" to JsonPrimitive(y) })
        }
    }

    @Test fun shot04() {
        GraphViews.logBase.clear(); GraphViews.zoom.clear()
        shot("04-rawdenoise-R", "rawdenoise", "band graph") { k ->
            bandApply(k, (0..3).flatMap { ch -> bandXs(5).mapIndexed { i, x -> "x[$ch][$i]" to JsonPrimitive(x) } } +
                listOf(0.5, 0.6, 0.75, 0.8, 0.7).mapIndexed { i, y -> "y[1][$i]" to JsonPrimitive(y) } +
                listOf(0.4, 0.45, 0.5, 0.6, 0.65).mapIndexed { i, y -> "y[3][$i]" to JsonPrimitive(y) })
            chooseTab(k, BandHost.tabsId(TestSetup.schema.modules.getValue("rawdenoise"))!!, 1)
        }
    }

    @Test fun shot05() {
        GraphViews.logBase.clear(); GraphViews.zoom.clear()
        shot("05-denoise-luma", "denoiseprofile", "band graph") { k ->
            bandApply(k, (0..5).flatMap { ch -> bandXs(7).mapIndexed { i, x -> "x[$ch][$i]" to JsonPrimitive(x) } } +
                listOf(0.3, 0.4, 0.55, 0.7, 0.8, 0.85, 0.9).mapIndexed { i, y -> "y[4][$i]" to JsonPrimitive(y) } +
                listOf(0.6, 0.6, 0.65, 0.7, 0.6, 0.5, 0.5).mapIndexed { i, y -> "y[5][$i]" to JsonPrimitive(y) })
        }
    }

    @Test fun shot06() {
        GraphViews.logBase.clear(); GraphViews.zoom.clear()
        shot("06-denoise-rgb-B", "denoiseprofile", "band graph") { k ->
            bandApply(k, listOf("wavelet_color_mode" to JsonPrimitive("MODE_RGB")) +
                (0..5).flatMap { ch -> bandXs(7).mapIndexed { i, x -> "x[$ch][$i]" to JsonPrimitive(x) } } +
                listOf(0.7, 0.6, 0.55, 0.5, 0.45, 0.4, 0.4).mapIndexed { i, y -> "y[0][$i]" to JsonPrimitive(y) } +
                listOf(0.5, 0.55, 0.6, 0.7, 0.75, 0.8, 0.8).mapIndexed { i, y -> "y[3][$i]" to JsonPrimitive(y) })
            chooseTab(k, BandHost.tabsId(TestSetup.schema.modules.getValue("denoiseprofile"), params(this, k))!!, 3)
        }
    }

    @Test fun shot07() {
        GraphViews.logBase.clear(); GraphViews.zoom.clear()
        shot("07-tonecurve-scale", "tonecurve", "curve graph") { k ->
            curveSet(k, CurveLayout.TONECURVE.writes(0, listOf(n(0.0, 0.0), n(0.05, 0.09), n(0.3, 0.42), n(1.0, 1.0)), 2))
            GraphViews.logBase[k] = 20.0
        }
    }

    @Test fun shot08() {
        GraphViews.logBase.clear(); GraphViews.zoom.clear()
        shot("08-rgbcurve-zoom", "rgbcurve", "curve graph") { k ->
            curveSet(k, CurveLayout.RGBCURVE.writes(0, listOf(n(0.0, 0.0), n(0.1, 0.16), n(0.25, 0.3), n(1.0, 1.0)), 2))
            GraphViews.zoom[k] = CurveView(zoom = 3.0, offsetX = 0.0, offsetY = 0.0)
        }
    }

    /** A point of a curve graph (fractions, y up) in the node's own pixels. */
    private fun at(fx: Double, fy: Double): Offset {
        val size = compose.onNodeWithContentDescription("curve graph").fetchSemanticsNode().size
        val density = org.robolectric.RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val inset = tokens.space.l.value * density
        return Offset((inset + fx * (size.width - 2 * inset)).toFloat(), (inset + (1 - fy) * (size.height - 2 * inset)).toFloat())
    }

    @Test fun touchBaseCurve() {
        if (skin != Skin.DARKTABLE) return
        GraphViews.zoom.clear()
        // base curve: a node dragged, one history item
        val base = InstanceKey("basecurve", 0)
        val three = listOf(n(0.0, 0.0), n(0.5, 0.7), n(1.0, 1.0))
        val c = show(tokens = tokens) { setEnabled(base, true); curveSet(base, CurveLayout.BASECURVE.writes(0, three, 0)); toggleExpanded(base) }
        settle(c)
        val items0 = c.state.value.history.items.size
        val from = at(0.5, 0.7)
        val to = at(0.5, 0.85)
        compose.onNodeWithContentDescription("curve graph").performTouchInput {
            down(from); for (i in 1..8) moveTo(from + (to - from) * (i / 8f)); up()
        }
        waitUntil("node moved") { abs(CurveLayout.BASECURVE.read(params(c, base), 0)[1].y - 0.85) < 0.03 }
        waitUntil("one item") { c.state.value.history.items.size == items0 + 1 }
    }

    @Test fun touchRgbCurvePinch() {
        if (skin != Skin.DARKTABLE) return
        GraphViews.zoom.clear()
        // rgb curve: two fingers spread: the graph zooms, nothing is written
        val rgb = InstanceKey("rgbcurve", 0)
        val c2 = show(tokens = tokens) { setEnabled(rgb, true); toggleExpanded(rgb) }
        settle(c2)
        val items1 = c2.state.value.history.items.size
        compose.onNodeWithContentDescription("curve graph").performTouchInput {
            down(0, at(0.45, 0.5)); down(1, at(0.55, 0.5))
            for (i in 1..8) { moveTo(0, at(0.45 - 0.03 * i, 0.5)); moveTo(1, at(0.55 + 0.03 * i, 0.5)) }
            up(0); up(1)
        }
        waitUntil("zoomed") { (GraphViews.zoom[rgb]?.zoom ?: 1.0) > 2.0 }
        settle(c2)
        assertEquals("a pinch writes nothing", items1, c2.state.value.history.items.size)
        assertEquals(2.0, (params(c2, rgb).paramAt("curve_num_nodes[0]") as JsonPrimitive).doubleOrNull ?: 0.0, 0.0)
        capture("phone-${skin.key}-p6f-09-rgbcurve-pinched")
        GraphViews.zoom.clear()
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP6fDarktableScreenshots : P6fGraphScreens() { override val skin = Skin.DARKTABLE }

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP6fModernScreenshots : P6fGraphScreens() { override val skin = Skin.MODERN }

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP6fL1Screenshots : P6fGraphScreens() { override val skin = Skin.L1 }

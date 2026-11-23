// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.test.core.app.ApplicationProvider
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.darkroom.ui.graph.BandHost
import app.dtandroid.darkroom.ui.graph.BandModels
import app.dtandroid.engine.api.paramAt
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3e-b: the band editor in contrast equalizer, colour zones and colour equalizer, in the darktable and modern
 * skins on the phone and one tablet shot (and the l1 layout's open module), on the stand-in engine. The editor is also
 * driven by touch: a node dragged (one history item), a double tap (the tab's curve reset), the tabs.
 */
abstract class P3eBandsScreens : ScreenshotBase() {
    private fun tokens(skin: Skin) = DtTokens.load(skin)
    private fun skinName(skin: Skin) = skin.key

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", cond())
    }

    private fun num(c: DarkroomController, key: InstanceKey, path: String) =
        (c.state.value.modules.first { it.key == key }.params.paramAt(path) as JsonPrimitive).doubleOrNull!!

    /** values worth looking at: the stand-in engine starts every curve flat */
    private suspend fun DarkroomController.dress(op: String) {
        val key = InstanceKey(op, 0)
        when (op) {
            "atrous" -> bandApply(key, listOf("0.7", "0.66", "0.6", "0.52", "0.45", "0.5").mapIndexed { i, v -> "y[0][$i]" to JsonPrimitive(v.toDouble()) } +
                listOf("0.5", "0.55", "0.62", "0.58", "0.5", "0.46").mapIndexed { i, v -> "y[1][$i]" to JsonPrimitive(v.toDouble()) } +
                listOf(0.0, 0.1, 0.25, 0.3, 0.2, 0.0).mapIndexed { i, v -> "y[3][$i]" to JsonPrimitive(v) } +
                (0 until 6).flatMap { k -> listOf(0, 1, 2, 3, 4).map { ch -> "x[$ch][$k]" to JsonPrimitive(k / 5.0) } })
            "colorzones" -> {
                val xs = listOf(0.0, 0.18, 0.36, 0.55, 0.8, 1.0)
                val ys = listOf(0.5, 0.5, 0.78, 0.5, 0.3, 0.5)
                bandApply(key, listOf("curve_num_nodes[2]" to JsonPrimitive(6L), "curve_type[2]" to JsonPrimitive(1L), "splines_version" to JsonPrimitive(1L)) +
                    xs.indices.flatMap { i -> listOf("curve[2][$i].x" to JsonPrimitive(xs[i]), "curve[2][$i].y" to JsonPrimitive(ys[i])) })
            }
            else -> bandApply(key, listOf("sat_red" to 1.5, "sat_orange" to 1.3, "sat_yellow" to 1.0, "sat_green" to 0.7, "sat_cyan" to 0.6, "sat_blue" to 1.0, "sat_lavender" to 1.2, "sat_magenta" to 1.4)
                .map { (k, v) -> k to JsonPrimitive(v) })
        }
    }

    protected fun band(name: String, op: String, tab: Int, skin: Skin, group: GroupTab = GroupTab.Active) {
        val key = InstanceKey(op, 0)
        val c = show(filmstrip = layout == DarkroomLayout.TABLET, tokens = tokens(skin), group = group) {
            setEnabled(key, true)
            dress(op)
            toggleExpanded(key)
            chooseTab(key, BandHost.tabsId(TestSetup.schema.modules[op]!!)!!, tab)
        }
        compose.onNodeWithContentDescription("band graph", substring = true).assertExists()
        settle(c)
        capture("$name-p3eb-$op-${skinName(skin)}")
    }

    /** a node of contrast equalizer dragged with a finger: one history item, the node follows */
    protected fun dragByTouch(name: String) {
        val key = InstanceKey("atrous", 0)
        val c = show(filmstrip = layout == DarkroomLayout.TABLET, tokens = tokens(Skin.DARKTABLE)) {
            setEnabled(key, true); dress("atrous"); toggleExpanded(key)
        }
        val before = c.state.value.history.items.size
        val y0 = num(c, key, "y[0][2]")
        val node = compose.onNodeWithContentDescription("band graph: luma").fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        val density = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
        val band = DtTokens.load().band
        val inset = band.node.value * 2f * density
        val strip = band.strip.value * density
        val gw = w - 2 * inset
        val gh = h - 2 * inset - strip
        fun at(fx: Float, fy: Float) = Offset(inset + fx * gw, inset + (1f - fy) * gh)
        val from = at(0.4f, y0.toFloat())
        val to = at(0.4f, 0.9f)
        compose.onNodeWithContentDescription("band graph: luma").performTouchInput {
            down(from)
            for (i in 1..8) moveTo(from + (to - from) * (i / 8f))
            up()
        }
        waitUntil("the node written") { num(c, key, "y[0][2]") > 0.8 }
        settle(c)
        assertEquals("one history item for the drag", before + 1, c.state.value.history.items.size)
        // a double tap on the graph: this tab's curves are back to the default, one item
        compose.onNodeWithContentDescription("band graph: luma").performTouchInput { doubleClick(at(0.2f, 0.5f)) }
        waitUntil("reset") { num(c, key, "y[0][2]") == 0.5 }
        assertEquals("one history item for the reset", before + 2, c.state.value.history.items.size)
        assertEquals(0.5, num(c, key, "y[0][0]"), 0.0)
        // the other tab's curve is not touched
        assertNotEquals(0.5, num(c, key, "y[1][2]"), 0.0)
        capture("$name-p3eb-drag-and-reset")
    }

    /** colour zones: a drag on empty graph adds a node, a long press on a node takes it away */
    protected fun zonesNodes(name: String) {
        val key = InstanceKey("colorzones", 0)
        val c = show(filmstrip = layout == DarkroomLayout.TABLET, tokens = tokens(Skin.DARKTABLE)) {
            setEnabled(key, true); dress("colorzones"); toggleExpanded(key); chooseTab(key, BandHost.tabsId(TestSetup.schema.modules["colorzones"]!!)!!, 2)
        }
        val before = c.state.value.history.items.size
        assertEquals(6.0, num(c, key, "curve_num_nodes[2]"), 0.0)
        val node = compose.onNodeWithContentDescription("band graph: hue").fetchSemanticsNode()
        val density = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
        val band = DtTokens.load().band
        val inset = band.node.value * 2f * density
        val gw = node.size.width - 2 * inset
        val gh = node.size.height - 2 * inset
        fun at(fx: Float, fy: Float) = Offset(inset + fx * gw, inset + (1f - fy) * gh)
        val from = at(0.68f, 0.2f)
        val to = at(0.68f, 0.35f)
        compose.onNodeWithContentDescription("band graph: hue").performTouchInput {
            down(from)
            for (i in 1..6) moveTo(from + (to - from) * (i / 6f))
            up()
        }
        waitUntil("a node added") { num(c, key, "curve_num_nodes[2]") == 7.0 }
        settle(c)
        assertEquals(before + 1, c.state.value.history.items.size)
        capture("$name-p3eb-colorzones-node-added")
        compose.onNodeWithContentDescription("band graph: hue").performTouchInput {
            down(at(0.68f, 0.35f)); advanceEventTime(900); up()
        }
        waitUntil("the node taken away") { num(c, key, "curve_num_nodes[2]") == 6.0 }
        assertEquals(before + 2, c.state.value.history.items.size)
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneBandsScreenshots : P3eBandsScreens() {
    override val layout = DarkroomLayout.PHONE

    @Test fun contrastEqualizerDarktable() = band("phone", "atrous", 0, Skin.DARKTABLE)
    @Test fun contrastEqualizerModern() = band("phone", "atrous", 0, Skin.MODERN)
    @Test fun colorZonesDarktable() = band("phone", "colorzones", 2, Skin.DARKTABLE)
    @Test fun colorZonesModern() = band("phone", "colorzones", 2, Skin.MODERN)
    @Test fun colorEqualizerDarktable() = band("phone", "colorequal", 1, Skin.DARKTABLE)
    @Test fun colorEqualizerModern() = band("phone", "colorequal", 1, Skin.MODERN)
    @Test fun contrastEqualizerL1() = band("phone-l1", "atrous", 0, Skin.L1)
    @Test fun colorZonesL1() = band("phone-l1", "colorzones", 2, Skin.L1)
    @Test fun dragAndReset() = dragByTouch("phone")
    @Test fun colorZonesNodes() = zonesNodes("phone")
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletBandsScreenshots : P3eBandsScreens() {
    override val layout = DarkroomLayout.TABLET

    @Test fun colorEqualizerDarktable() = band("tablet", "colorequal", 0, Skin.DARKTABLE)
    @Test fun contrastEqualizerModern() = band("tablet", "atrous", 1, Skin.MODERN)
}

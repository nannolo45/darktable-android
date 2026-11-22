// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.schema.Control
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief PUI3a: the darkroom in the `l1` skin on the phone, upright and sideways, on the stand-in engine. Each state
 * the brief lists is captured; the taps go through Compose's touch input, so they test the drawn layout.
 */
abstract class PUI3aL1Screens : ScreenshotBase() {
    protected val l1 get() = DtTokens.load(Skin.L1)
    protected val exposure = InstanceKey("exposure", 0)

    protected fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", cond())
    }

    /** the first group of the layout that holds [op] */
    protected fun groupOf(c: DarkroomController, op: String): GroupTab.Group =
        GroupTab.Group(c.state.value.layout.groups.indexOfFirst { op in it.modules }.also { assertTrue("no group holds $op", it >= 0) })

    /** a module row's name (the first node with the module's name: the row comes before its controls) */
    protected fun row(name: String) = compose.onAllNodesWithContentDescription(name)[0]
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneL1Screenshots : PUI3aL1Screens() {
    override val layout = DarkroomLayout.PHONE

    @Test
    fun rest() {
        val c = show(tokens = l1, group = null)
        compose.onNodeWithContentDescription("tool modules").assertExists()
        compose.onNodeWithContentDescription("active modules").assertExists()
        compose.onNodeWithContentDescription("compare").assertExists()
        settle(c)
        capture("phone-l1-01-rest")
    }

    @Test
    fun groupWithOneOpenModule() {
        val c = show(tokens = l1, group = null)
        val g = groupOf(c, "exposure")
        compose.onNodeWithContentDescription("group ${c.state.value.layout.groups[g.index].name}", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(g, c.state.value.group)
        // a tap on a row opens it under its row
        row("exposure").performClick()
        compose.waitForIdle()
        assertEquals(exposure, c.state.value.expanded)
        compose.onNodeWithContentDescription("Instances of exposure").assertExists()
        settle(c)
        capture("phone-l1-02-group-open-module")
        // one module open at a time: another row closes it
        val other = c.visibleModules(c.state.value).first { it.key != exposure }
        row(other.name).performClick()
        compose.waitForIdle()
        assertEquals(other.key, c.state.value.expanded)
        compose.onNodeWithContentDescription("Instances of exposure").assertDoesNotExist()
        // a tap on the open row closes it
        row(other.name).performClick()
        compose.waitForIdle()
        assertNull(c.state.value.expanded)
    }

    @Test
    fun powerOnAClosedRow() {
        val c = show(tokens = l1, group = null)
        compose.onNodeWithContentDescription("group ${c.state.value.layout.groups[groupOf(c, "exposure").index].name}", substring = true).performClick()
        compose.waitForIdle()
        val m = c.visibleModules(c.state.value).first { it.canSwitch }
        assertNull(c.state.value.expanded)
        compose.onNodeWithContentDescription((if (m.enabled) "Turn off " else "Turn on ") + m.name).performClick()
        waitUntil("${m.name} switched") { c.state.value.modules.first { it.key == m.key }.enabled != m.enabled }
        assertNull("the row stays closed", c.state.value.expanded)
    }

    @Test
    fun quick() {
        val c = show(tokens = l1, group = GroupTab.Quick)
        settle(c)
        capture("phone-l1-03-quick")
    }

    @Test
    fun active() {
        val c = show(tokens = l1, group = GroupTab.Active)
        compose.onNodeWithContentDescription("active modules").assertExists()
        settle(c)
        capture("phone-l1-04-active")
        // a row in "active" opens its module in its group
        row("exposure").performClick()
        compose.waitForIdle()
        assertEquals(groupOf(c, "exposure"), c.state.value.group)
        assertEquals(exposure, c.state.value.expanded)
    }

    @Test
    fun styles() {
        val c = show(tokens = l1)
        compose.onNodeWithContentDescription("tool styles").performClick()
        compose.waitForIdle()
        assertEquals(Overlay.Styles, c.state.value.overlay)
        settle(c)
        capture("phone-l1-05-styles")
    }

    @Test
    fun masks() {
        val c = show(tokens = l1) { masks.select(exposure) }
        compose.onNodeWithContentDescription("tool masks").performClick()
        compose.waitForIdle()
        assertEquals(BottomView.MASKS, c.state.value.view)
        settle(c)
        capture("phone-l1-06-masks")
    }

    @Test
    fun more() {
        val c = show(tokens = l1) {
            val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
            setSlider(exposure, e.binding, 1.2, false)
        }
        compose.onNodeWithContentDescription("tool more").performClick()
        compose.waitForIdle()
        assertEquals(BottomView.HISTORY, c.state.value.view)
        settle(c)
        capture("phone-l1-07-more")
    }

    @Test
    fun dragging() {
        val c = show(tokens = l1, group = null) {
            goToModule(exposure)
        }
        assertEquals(exposure, c.state.value.expanded)
        // a finger on exposure's slider, moved and still down
        compose.onNodeWithContentDescription("exposure bar").performTouchInput {
            down(center)
            moveBy(Offset(width / 8f, 0f))
            moveBy(Offset(width / 8f, 0f))
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNode(hasContentDescription("dragging exposure · exposure · ", substring = true)).assertExists()
        capture("phone-l1-08-dragging")
        compose.onNodeWithContentDescription("exposure bar").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNode(hasContentDescription("dragging", substring = true)).assertDoesNotExist()
        // the drag went to the engine as a slider drag does
        waitUntil("exposure changed") { c.state.value.history.items.any { it.op == "exposure" } }
    }

    @Test
    fun rowsLeftOutWhileCropIsOpen() {
        val crop = InstanceKey("crop", 0)
        val c = show(tokens = l1, group = null) {
            goToModule(crop)
            syncCropTool()
        }
        waitUntil("crop tool") { c.state.value.cropTool != null }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("tool modules").assertDoesNotExist()
        settle(c)
        capture("phone-l1-09-crop-open")
        // closing crop's panel ("done" for the tool) brings the rows back
        row("crop").performClick()
        waitUntil("crop tool closed") { c.state.value.cropTool == null }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("tool modules").assertExists()
        assertNotEquals(crop, c.state.value.expanded)
    }
}

@Config(sdk = [35], qualifiers = "w844dp-h390dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidewaysPhoneL1Screenshots : PUI3aL1Screens() {
    override val layout = DarkroomLayout.PHONE

    @Test
    fun sideways() {
        val c = show(tokens = l1, group = null) { goToModule(exposure) }
        compose.onNodeWithContentDescription("tool modules").assertExists()
        compose.onNodeWithContentDescription("Instances of exposure").assertExists()
        settle(c)
        capture("phone-l1-10-sideways")
    }
}

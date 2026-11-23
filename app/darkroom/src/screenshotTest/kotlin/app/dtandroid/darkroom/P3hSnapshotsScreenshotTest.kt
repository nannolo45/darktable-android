// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.schema.Control
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3h (mockup B3-Snapshots): the snapshots panel with the split view, vertical and horizontal, in the darktable
 * and modern skins (phone: the history tab; tablet: the left panel), and in l1's "more" tool; the divider dragged by
 * touch. On the stand-in engine: the snapshot is taken at the opened edit, then exposure is raised by 1.5 EV.
 */
abstract class P3hSnapshotScreens : ScreenshotBase() {
    private val exposure = InstanceKey("exposure", 0)

    private fun layoutName() = if (layout == DarkroomLayout.TABLET) "tablet" else "phone"

    protected fun setUp(skin: Skin, horizontal: Boolean): DarkroomController {
        val c = show(tokens = DtTokens.load(skin), filmstrip = layout == DarkroomLayout.TABLET) {
            val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
            takeSnapshot()
            setSlider(exposure, e.binding, 1.5, false)
            takeSnapshot()
            selectSnapshot(state.value.snapshots.list.first().id)
            if (horizontal) rotateSplit()
            if (skin != Skin.L1) setView(BottomView.HISTORY)
        }
        settle(c)
        return c
    }

    protected fun panel(skin: Skin, horizontal: Boolean) {
        val c = setUp(skin, horizontal)
        compose.onNodeWithText("take snapshot").assertExists()
        assertTrue("the snapshot's picture is there", c.state.value.snapshots.preview != null)
        compose.onNodeWithContentDescription("current").assertExists()
        capture("${layoutName()}-p3h-${if (horizontal) "split-horizontal" else "split-vertical"}-${skin.key}")
    }

    /** a finger on the divider moves it */
    protected fun dragDivider(skin: Skin) {
        val c = setUp(skin, horizontal = false)
        assertEquals(0.5f, c.state.value.snapshots.split.position)
        compose.onNodeWithContentDescription("photo").performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.1f, 0f))
            moveTo(center + Offset(width * 0.2f, 0f))
            up()
        }
        settle(c)
        val p = c.state.value.snapshots.split.position
        assertTrue("the divider followed the finger: $p", p > 0.6f)
        capture("${layoutName()}-p3h-divider-dragged-${skin.key}")
    }

    protected fun l1More() {
        val c = setUp(Skin.L1, horizontal = false)
        compose.onNodeWithContentDescription("tool more").performClick()
        compose.waitForIdle()
        settle(c)
        compose.onNodeWithText("take snapshot").assertExists()
        capture("${layoutName()}-p3h-l1-more")
        // compare: off and on again with the newest snapshot
        compose.onNodeWithContentDescription("compare").performClick()
        compose.waitForIdle()
        assertEquals(null, c.state.value.snapshots.selected)
        compose.onNodeWithContentDescription("compare").performClick()
        compose.waitForIdle()
        settle(c)
        assertEquals(c.state.value.snapshots.list.last().id, c.state.value.snapshots.selected)
        capture("${layoutName()}-p3h-l1-compare")
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSnapshotScreenshots : P3hSnapshotScreens() {
    override val layout = DarkroomLayout.PHONE

    @Test fun verticalDarktable() = panel(Skin.DARKTABLE, false)
    @Test fun verticalModern() = panel(Skin.MODERN, false)
    @Test fun horizontalDarktable() = panel(Skin.DARKTABLE, true)
    @Test fun horizontalModern() = panel(Skin.MODERN, true)
    @Test fun dividerDarktable() = dragDivider(Skin.DARKTABLE)
    @Test fun l1() = l1More()
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletSnapshotScreenshots : P3hSnapshotScreens() {
    override val layout = DarkroomLayout.TABLET

    @Test fun verticalDarktable() = panel(Skin.DARKTABLE, false)
    @Test fun horizontalModern() = panel(Skin.MODERN, true)
}

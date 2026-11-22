// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3d stage 2a: the module's mask as a tree on the phone, both skins: the module's group with its operator
 * (difference), a wrapped circle in an open nested group, two shapes grouped together (closed), one row selected
 * with its opacity.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneMaskTreeScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val exposure = InstanceKey("exposure", 0)

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", cond())
    }

    private suspend fun DarkroomController.circleAt(x: Double, y: Double) {
        masks.startDraw(ShapeType.CIRCLE)
        masks.touchDown(x, y); masks.touchUp()
        masks.finishDraw()
    }

    private fun tree(modern: Boolean) {
        val c = show(tokens = if (modern) DtTokens.load(Skin.MODERN) else DtTokens.load()) {
            setView(BottomView.MASKS)
            masks.select(exposure)
            masks.setMode(MaskMode.DRAWN)
            circleAt(0.3, 0.4); circleAt(0.5, 0.5); circleAt(0.7, 0.6)
            val root = masks.state.value.module!!.rootId!!
            val ids = masks.state.value.forms.first { it.id == root }.members.map { it.id }
            masks.setGroupOperator(root, "difference")
            masks.groupTogether(root, listOf(ids[1], ids[2]))
            val together = masks.state.value.forms.first { it.id == root }.members.last().id
            masks.toggleExpanded(together)
            masks.wrapInGroup(root, ids[0])
            masks.selectShape(ids[0])
        }
        waitUntil("tree") { c.masks.state.value.forms.count { it.isGroup } >= 3 }
        // the tab scrolls: its last tree row into view
        compose.onAllNodesWithContentDescription("member ", substring = true).onLast().performScrollTo()
        settle(c)
        capture("phone-p3d-05-mask-tree-${if (modern) "modern" else "darktable"}")
    }

    @Test fun maskTree() = tree(modern = false)
    @Test fun maskTreeModern() = tree(modern = true)
}

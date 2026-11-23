// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3d stage 2c on the phone, both skins: the tree with a chosen group ("new shapes go into"), a parametric
 * element in it with its controls open; and the tool panel while a circle is edited after "done" (used by two modules).
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneEditShapesScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val exposure = InstanceKey("exposure", 0)

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", cond())
    }

    private fun tokens(modern: Boolean) = if (modern) DtTokens.load(Skin.MODERN) else DtTokens.load()

    private suspend fun DarkroomController.circleAt(x: Double, y: Double) {
        masks.startDraw(ShapeType.CIRCLE)
        masks.touchDown(x, y); masks.touchUp()
        masks.finishDraw()
    }

    private fun chosenGroup(modern: Boolean) {
        val c = show(tokens = tokens(modern)) {
            setView(BottomView.MASKS)
            masks.select(exposure)
            masks.setMode(MaskMode.DRAWN)
            circleAt(0.3, 0.4)
            val root = masks.state.value.module!!.rootId!!
            masks.wrapInGroup(root, masks.state.value.selected!!)
            val nested = masks.state.value.forms.first { it.id == root }.members.last().id
            masks.chooseGroup(nested)
            circleAt(0.6, 0.5)
            val spec = ParamChannels.of(parametric.state.value.space).first { it.base == "g" }
            parametric.addChannel(spec, masks.targetGroup(), select = true)
            val el = parametric.state.value.all.first { it.base == "g" }
            parametric.setRange(el.id, false, listOf(0.2, 0.3, 0.7, 0.85))
        }
        waitUntil("element in the nested group") { c.parametric.state.value.all.any { it.base == "g" && it.inRange[0] == 0.2 } }
        compose.onNodeWithContentDescription("g input range").performScrollTo()
        settle(c)
        capture("phone-p3d2c-01-chosen-group-${if (modern) "modern" else "darktable"}")
    }

    private fun editing(modern: Boolean) {
        val c = show(tokens = tokens(modern)) {
            setView(BottomView.MASKS)
            masks.select(exposure)
            masks.setMode(MaskMode.DRAWN)
            circleAt(0.4, 0.5)
            val id = masks.state.value.selected!!
            masks.attach(id, masks.blendingModules().map { it.key }.first { it != exposure })
            masks.startEdit(id)
        }
        waitUntil("editing") { c.masks.state.value.draw?.editing != null && c.masks.state.value.draw?.usedBy == 2 }
        settle(c)
        capture("phone-p3d2c-02-edit-circle-${if (modern) "modern" else "darktable"}")
    }

    @Test fun chosenGroup() = chosenGroup(modern = false)
    @Test fun chosenGroupModern() = chosenGroup(modern = true)
    @Test fun editing() = editing(modern = false)
    @Test fun editingModern() = editing(modern = true)
}

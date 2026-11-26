// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P6a: white balance's settings list in the three skins on the phone: the panel with a camera preset chosen
 * (its finetune slider shown with the preset's limits) and the list itself (the modes, "user modified" greyed out
 * with the reason, the camera's name as a heading, its presets). The stand-in engine's camera has daylight (fine
 * tuning -2..2), cloudy and shade.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP6aScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val wb = InstanceKey("temperature", 0)

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!runCatching(cond).getOrDefault(false) && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", runCatching(cond).getOrDefault(false))
    }

    private fun shot(tokens: DtTokens, name: String) {
        val c = show(tokens = tokens) {
            goToModule(wb)
            val l = schema.modules.getValue("temperature").actionLists.getValue(9)
            val e = l.entries(state.value.actionLists[wb to 9])
            chooseActionList(wb, 9, e.indexOfFirst { l.label(it) == "daylight" })
        }
        waitUntil("finetune shown") { c.state.value.actionValues[wb]?.get("finetune_min") is kotlinx.serialization.json.JsonPrimitive }
        settle(c)
        capture("$name-p6a-01-wb-preset")
        c.setOverlay(Overlay.ListEntries(wb, 9))
        settle(c)
        capture("$name-p6a-02-wb-settings-list")
    }

    @Test fun darktable() = shot(DtTokens.load(), "phone-darktable")
    @Test fun modern() = shot(DtTokens.load(Skin.MODERN), "phone-modern")
    @Test fun l1() = shot(DtTokens.load(Skin.L1), "phone-l1")
}

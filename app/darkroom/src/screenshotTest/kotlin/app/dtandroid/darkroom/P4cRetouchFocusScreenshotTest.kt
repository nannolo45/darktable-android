// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P4c item 1: retouch's panel open on a cropped photo: the whole picture with crop's frame as a read-only guide,
 * and a heal spot outside the frame with its source, in the three skins on the phone.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP4cScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val rtKey = InstanceKey(RETOUCH_OP, 0)

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!runCatching(cond).getOrDefault(false) && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", runCatching(cond).getOrDefault(false))
    }

    private fun xy(x: Double, y: Double) = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)))

    private fun shot(tokens: DtTokens, name: String) {
        val c = show(tokens = tokens) {
            engineChange { e ->
                e.paramsSet("crop", 0, mapOf("cx" to JsonPrimitive(0.2), "cy" to JsonPrimitive(0.25), "cw" to JsonPrimitive(0.85), "ch" to JsonPrimitive(0.8)))
                e.setEnabled("crop", 0, true)
                e.historySeal()
                // outside the frame: left of it
                e.retouchAdd(RETOUCH_OP, 0, "circle", buildJsonObject { put("center", xy(0.1, 0.5)); put("radius", 0.05); put("border", 0.02) }, 0.1 to 0.35)
                e.historySeal()
            }
            toggleExpanded(rtKey)
        }
        waitUntil("retouch focused") { c.retouch.state.value.focus?.frame != null && c.retouch.state.value.outlines.size == 1 }
        settle(c)
        capture("$name-p4c-01-retouch-uncropped")
    }

    @Test fun darktable() = shot(DtTokens.load(), "phone-darktable")
    @Test fun modern() = shot(DtTokens.load(Skin.MODERN), "phone-modern")
    @Test fun l1() = shot(DtTokens.load(Skin.L1), "phone-l1")
}

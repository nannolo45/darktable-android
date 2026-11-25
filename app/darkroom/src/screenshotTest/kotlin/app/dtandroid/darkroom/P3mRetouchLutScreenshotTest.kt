// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3m: retouch's panel and its shapes over the photo (a heal circle selected with its source, a fill brush,
 * "display masks"), the colour look up table's patch grid (an altered patch, the selected one, its long-press menu),
 * in the three skins on the phone; and touch: a tap on a shape button arms it, a long press on a patch opens its menu.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP3mScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val rtKey = InstanceKey(RETOUCH_OP, 0)
    private val lutKey = InstanceKey(LUT_OP, 0)

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!runCatching(cond).getOrDefault(false) && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", runCatching(cond).getOrDefault(false))
    }

    private fun xy(x: Double, y: Double) = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)))

    private fun retouchShot(tokens: DtTokens, name: String) {
        var heal = 0
        val c = show(tokens = tokens) {
            engineChange { e ->
                heal = e.retouchAdd(RETOUCH_OP, 0, "circle", buildJsonObject { put("center", xy(0.35, 0.45)); put("radius", 0.06); put("border", 0.02) }, 0.47 to 0.3)
                e.retouchAdd(RETOUCH_OP, 0, "brush", buildJsonObject { put("stroke", JsonArray(listOf(xy(0.6, 0.6), xy(0.68, 0.63), xy(0.75, 0.6)))); put("size", 0.02) },
                    null, algorithm = "fill")
                e.historySeal()
            }
            toggleExpanded(rtKey)
        }
        waitUntil("retouch read") { c.retouch.state.value.st?.shapes?.size == 2 && c.retouch.state.value.outlines.size == 2 }
        runBlocking { c.retouch.select(heal) }
        c.retouch.toggleDisplayMasks()
        settle(c)
        compose.onNodeWithContentDescription("shapes: 2").assertExists()
        capture("$name-p3m-01-retouch")
        // a tap on the circle button arms it
        compose.onNodeWithContentDescription("add circle").performClick()
        waitUntil("circle armed") { c.retouch.state.value.tool == RetouchTool.CIRCLE }
        settle(c)
        capture("$name-p3m-02-retouch-tool")
    }

    private fun lutShot(tokens: DtTokens, name: String) {
        val c = show(tokens = tokens) {
            engineChange { e -> e.paramsSet(LUT_OP, 0, mapOf("target_L[3]" to JsonPrimitive(60.0), "target_a[3]" to JsonPrimitive(-30.0))); e.historySeal() }
            toggleExpanded(lutKey)
        }
        waitUntil("lut open") { c.lut.state.value.key == lutKey }
        c.lut.select(3)
        settle(c)
        compose.onNodeWithContentDescription("patch #3").assertExists()
        capture("$name-p3m-03-lut")
        // a long press on patch 9 (third row... desktop's grid 6 x 4: column 3, row 1) opens its menu
        compose.onNodeWithContentDescription("patches").performTouchInput {
            longClick(androidx.compose.ui.geometry.Offset(width * (3.5f / 6f), height * (1.5f / 4f)))
        }
        waitUntil("menu") { c.lut.state.value.menu == 9 }
        assertEquals(9, c.lut.state.value.patch)
        settle(c)
        capture("$name-p3m-04-lut-menu")
    }

    @Test fun retouchDarktable() = retouchShot(DtTokens.load(), "phone-darktable")
    @Test fun retouchModern() = retouchShot(DtTokens.load(Skin.MODERN), "phone-modern")
    @Test fun retouchL1() = retouchShot(DtTokens.load(Skin.L1), "phone-l1")
    @Test fun lutDarktable() = lutShot(DtTokens.load(), "phone-darktable")
    @Test fun lutModern() = lutShot(DtTokens.load(Skin.MODERN), "phone-modern")
    @Test fun lutL1() = lutShot(DtTokens.load(Skin.L1), "phone-l1")
}

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3n: rotate and perspective's panel (structure and fit buttons, the find lines long-press row) and the
 * structure over the photo (lines found in desktop's colours; the rectangle with its corners; the crop outline with
 * the guides) in the three skins on the phone; touch: a tap on "rectangle" turns it on, a long press on "find lines"
 * opens its enhancements.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP3nScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val key = InstanceKey(ASHIFT_OP, 0)

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!runCatching(cond).getOrDefault(false) && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertTrue("timed out: $what", runCatching(cond).getOrDefault(false))
    }

    private fun shot(tokens: DtTokens, name: String) {
        val c = show(tokens = tokens) { goToModule(key) }
        waitUntil("focused") { c.perspective.state.value.st?.focused == true }
        settle(c)
        capture("$name-p3n-01-panel")
        // a long press on "find lines": its enhancements
        compose.onNodeWithContentDescription("find lines").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("find lines, enhance edges").performClick()
        waitUntil("lines") { c.perspective.state.value.st?.method == "lines_found" }
        settle(c)
        capture("$name-p3n-02-lines")
        // a tap on "rectangle"
        compose.onNodeWithContentDescription("rectangle").performClick()
        waitUntil("rectangle") { c.perspective.state.value.st?.method == "rectangle" }
        runBlocking { c.perspective.fit("both") }
        c.perspective.toggleGuides()
        waitUntil("fitted") { c.perspective.state.value.st?.clip != null }
        settle(c)
        capture("$name-p3n-03-rectangle-fit")
    }

    @Test fun darktable() = shot(DtTokens.load(), "phone-darktable")
    @Test fun modern() = shot(DtTokens.load(Skin.MODERN), "phone-modern")
    @Test fun l1() = shot(DtTokens.load(Skin.L1), "phone-l1")
}

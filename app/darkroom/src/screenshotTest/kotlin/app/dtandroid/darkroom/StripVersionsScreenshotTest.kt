// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filmstrip's version badge in the three skins: collapsed (badge "v1·2" on the photo whose second version is open),
 * a tap on it shows the versions, a tap on a version tile opens it.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneStripVersionsScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val badge = "2 versions of ${InterfacePreview.fileName(0)}"

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(10) }
        assertEquals("timed out: $what", true, cond())
    }

    private fun strip(tokens: DtTokens, name: String) {
        val c = show(filmstrip = true, tokens = tokens) {
            versionHost = object : VersionHost {}
            duplicate(virgin = false)        // photo 0: versions 0 and 1, version 1 open
        }
        settle(c)
        compose.onNodeWithContentDescription(badge).assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "collapsed"))
        capture("$name-badge")

        compose.onNodeWithContentDescription(badge).performClick()
        waitUntil("expanded") { c.state.value.stripExpanded == 0 }
        settle(c)
        compose.onNodeWithContentDescription(badge).assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "expanded"))
        compose.onNodeWithContentDescription("Version 0 of ${InterfacePreview.fileName(0)}").assertExists()
        compose.onNodeWithContentDescription("Version 1 of ${InterfacePreview.fileName(0)}").assertExists()
        capture("$name-expanded")

        compose.onNodeWithContentDescription("Version 0 of ${InterfacePreview.fileName(0)}").performClick()
        waitUntil("version 0 open") { c.state.value.current?.version == 0 && c.state.value.info != null }
        settle(c)
        capture("$name-opened-orig")

        compose.onNodeWithContentDescription(badge).performClick()
        waitUntil("collapsed") { c.state.value.stripExpanded == null }
        assertNull(c.state.value.stripExpanded)
    }

    @Test fun darktableSkin() = strip(DtTokens.load(), "phone-strip-versions-darktable")

    @Test fun modernSkin() = strip(DtTokens.load(Skin.MODERN), "phone-strip-versions-modern")

    @Test fun l1Skin() = strip(DtTokens.load(Skin.L1), "phone-strip-versions-l1")
}

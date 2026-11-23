// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.schema.Control
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Brief P3f: the versions chip and sheet in the darktable and modern skins, and l1's `more` with `duplicate`. */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneVersionsScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE

    private fun withVersions(tokens: DtTokens): DarkroomController = show(tokens = tokens) {
        versionHost = object : VersionHost {}
        val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        setSlider(InstanceKey("exposure", 0), e.binding, 1.2, false)
        duplicate(virgin = false)
        duplicate(virgin = true)
        switchVersion(1)
        renameVersion(1, "warm")
    }

    private fun sheet(tokens: DtTokens, name: String) {
        val c = withVersions(tokens)
        settle(c)
        capture("$name-chip")
        compose.onNodeWithContentDescription("versions, v1").performClick()
        compose.waitForIdle()
        assertEquals(Overlay.Versions, c.state.value.overlay)
        settle(c)
        capture("$name-sheet")
    }

    @Test fun darktableSkin() = sheet(DtTokens.load(), "phone-versions-darktable")

    @Test fun modernSkin() = sheet(DtTokens.load(Skin.MODERN), "phone-versions-modern")

    @Test fun l1MoreHasDuplicate() {
        val c = show(tokens = DtTokens.load(Skin.L1), group = null) { versionHost = object : VersionHost {} }
        compose.onNodeWithContentDescription("more").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("duplicate…").assertExists()
        settle(c)
        capture("phone-versions-l1-more")
    }
}

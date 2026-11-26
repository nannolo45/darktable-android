// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P6d: lens correction by hand in the three skins on the phone: the module with a camera and a lens chosen, the
 * module before a choice (the file's camera and lens, which the stand-in's Lensfun does not know), the camera picker
 * without and with a search, the lens picker without and with a search, and the list of focal lengths.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP6dScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val key = InstanceKey(LENS_OP, 0)

    private fun shots(tokens: DtTokens, name: String) {
        // the file's camera and lens are not in the stand-in's Lensfun: desktop's "pick one" state
        val c = show(tokens = tokens) { toggleExpanded(key) }
        c.goToModule(key)
        runBlocking { c.lens.refresh(key).join() }
        assertTrue(c.lens.ui.value.state != null)
        settle(c)
        compose.onNodeWithContentDescription("camera model").assertExists()
        capture("$name-p6d-01-unknown")

        // a camera and a lens chosen
        runBlocking { c.lens.chooseCamera(key, LensCamera("Canon", "Canon EOS 40D", null, "Canon EF-S", 1.6)).join() }
        runBlocking { c.lens.chooseLens(key, LensInfo("Canon", "Canon EF-S 18-55mm f/3.5-5.6 IS", 18.0, 55.0, 3.5, 5.6)).join() }
        assertTrue(c.lens.ui.value.state?.lens != null)
        settle(c)
        c.goToModule(key)
        settle(c)
        compose.onNodeWithContentDescription("focal length").assertExists()
        capture("$name-p6d-02-module")

        // the camera picker, then with a search
        runBlocking { c.lens.open(key, LensPick.CAMERA, found = false).join() }
        assertEquals(6, c.lens.ui.value.cameras.size)
        settle(c)
        compose.onNodeWithContentDescription("search cameras").assertExists()
        capture("$name-p6d-03-camera-picker")
        compose.onNodeWithContentDescription("search cameras").performTextInput("sony aps")
        settle(c)
        capture("$name-p6d-04-camera-picker-search")
        c.setOverlay(null)

        // the lens picker, then with a search
        runBlocking { c.lens.open(key, LensPick.LENS, found = false).join() }
        assertEquals(4, c.lens.ui.value.lenses.size)
        settle(c)
        capture("$name-p6d-05-lens-picker")
        compose.onNodeWithContentDescription("search lenses").performTextInput("fish")
        settle(c)
        capture("$name-p6d-06-lens-picker-search")
        c.setOverlay(null)

        // the focal lengths of the chosen lens
        c.lens.openValue(key, LensValueKind.FOCAL)
        settle(c)
        assertEquals(Overlay.LensValue(key, LensValueKind.FOCAL), c.state.value.overlay)
        capture("$name-p6d-07-focal-list")
    }

    @Test fun darktable() = shots(DtTokens.load(), "phone-darktable")
    @Test fun modern() = shots(DtTokens.load(Skin.MODERN), "phone-modern")
    @Test fun l1() = shots(DtTokens.load(Skin.L1), "phone-l1")
}

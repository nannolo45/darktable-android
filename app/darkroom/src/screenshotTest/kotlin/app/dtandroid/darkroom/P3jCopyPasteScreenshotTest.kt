// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.onNodeWithText
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.schema.Control
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3j: the photo menu's copy and paste entries in the darktable and modern skins, the selective copy sheet,
 * the selective paste sheet, and l1's `more` tool with the same entries.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCopyPasteScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE

    private fun edited(tokens: DtTokens, copy: Boolean = true): DarkroomController = show(tokens = tokens) {
        val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        setSlider(InstanceKey("exposure", 0), e.binding, 0.8, false)
        if (copy) copyPaste.copy()
    }

    private fun menuAndSheets(tokens: DtTokens, name: String) {
        val c = edited(tokens)
        c.setOverlay(Overlay.HostMenu)
        settle(c)
        compose.onNodeWithText("selective copy…").assertExists()
        capture("$name-menu")
        c.setOverlay(null)
        kotlinx.coroutines.runBlocking { c.copyPaste.openCopySheet() }
        assertNotNull(c.copyPaste.state.value.sheet)
        settle(c)
        capture("$name-copy-sheet")
        c.copyPaste.closeSheet()
        kotlinx.coroutines.runBlocking { c.copyPaste.openPasteSheet() }
        settle(c)
        capture("$name-paste-sheet")
    }

    @Test fun darktableSkin() = menuAndSheets(DtTokens.load(), "phone-p3j-darktable")

    @Test fun modernSkin() = menuAndSheets(DtTokens.load(Skin.MODERN), "phone-p3j-modern")

    @Test fun l1More() {
        val c = show(tokens = DtTokens.load(Skin.L1), group = null) {
            val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        setSlider(InstanceKey("exposure", 0), e.binding, 0.8, false)
            copyPaste.copy()
        }
        c.setView(BottomView.HISTORY)
        settle(c)
        compose.onNodeWithText("selective paste…").assertExists()
        capture("phone-p3j-l1-more")
    }

    @Test fun pasteOntoPhotos() {
        val c = edited(DtTokens.load())
        c.copyPaste.choosePhotos()
        c.copyPaste.togglePhoto(1)
        settle(c)
        capture("phone-p3j-paste-onto")
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P6e: the colour dialog, framing with its two colour rows and pickers, and watermark with its marker list,
 * text, font, colour and alignment, in the three skins on the phone.
 */
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP6eScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE
    private val borders = InstanceKey(BORDERS_OP, 0)
    private val watermark = InstanceKey(WATERMARK_OP, 0)

    private fun color(f: String, r: Int, g: Int, b: Int) =
        Rgb.ofBytes(r, g, b).channels.mapIndexed { i, v -> "$f[$i]" to JsonPrimitive(v) }.toMap()

    private fun shots(tokens: DtTokens, name: String) {
        val c = show(tokens = tokens) {
            setParams(borders, color("color", 51, 102, 204))
            setParams(borders, color("frame_color", 255, 136, 0))
            setParam(borders, "frame_size", JsonPrimitive(0.3))
            toggleExpanded(borders)
        }
        c.goToModule(borders)
        settle(c)
        try { compose.onNodeWithContentDescription("border color").assertExists() } catch (e: AssertionError) {
            throw AssertionError(e.message + compose.onRoot().printToString(60), e)
        }
        compose.onNodeWithContentDescription("frame line color").assertExists()
        capture("$name-p6e-01-framing")

        // the dialog on the border colour, then a colour typed in hex
        c.framing.open(borders, FramingWatermarkNodes.BORDER)
        settle(c)
        compose.onNodeWithContentDescription("OK colour").assertExists()
        capture("$name-p6e-02-dialog")
        compose.onNodeWithContentDescription("hex colour").performTextReplacement("#f80")
        settle(c)
        capture("$name-p6e-03-dialog-hex")
        c.setOverlay(null)

        // watermark: a text marker, its text, font, colour and alignment
        runBlocking {
            c.setParam(watermark, "filename", JsonPrimitive("simple-text.svg"))
            c.setParam(watermark, "text", JsonPrimitive("© darktable-android"))
            c.setParam(watermark, "font", JsonPrimitive("Roboto Bold 10"))
            c.setParams(watermark, color("color", 255, 255, 0))
            c.setParam(watermark, "alignment", JsonPrimitive(2))
            c.toggleExpanded(watermark)
            c.framing.refresh(watermark).join()
        }
        assertEquals(7, c.framing.lists.value.files.size)
        c.goToModule(watermark)
        settle(c)
        compose.onNodeWithContentDescription("watermark text").assertExists()
        capture("$name-p6e-04-watermark")
        c.setOverlay(Overlay.WatermarkFiles(watermark))
        settle(c)
        capture("$name-p6e-05-markers")
        c.setOverlay(Overlay.WatermarkFonts(watermark))
        settle(c)
        assertTrue(c.framing.lists.value.fonts.isNotEmpty())
        capture("$name-p6e-06-fonts")
        c.setOverlay(null)
    }

    @Test fun darktable() = shots(DtTokens.load(), "phone-darktable")
    @Test fun modern() = shots(DtTokens.load(Skin.MODERN), "phone-modern")
    @Test fun l1() = shots(DtTokens.load(Skin.L1), "phone-l1")
}

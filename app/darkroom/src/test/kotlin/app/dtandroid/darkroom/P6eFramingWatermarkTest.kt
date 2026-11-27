// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.paramAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P6e (JVM): the colour dialog's writes on the stand-in engine (one history item on OK, none on Cancel or an
 * unchanged OK, undo), the recent colours kept per app, framing's and watermark's pickers from the photo, and
 * watermark's marker list, text, font and alignment.
 */
class P6eFramingWatermarkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val borders = InstanceKey(BORDERS_OP, 0)
    private val watermark = InstanceKey(WATERMARK_OP, 0)

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun mod(c: DarkroomController, k: InstanceKey) = c.state.value.modules.first { it.key == k }
    private fun color(c: DarkroomController, k: InstanceKey, f: String) =
        (0..2).map { (mod(c, k).params.paramAt("$f[$it]") as JsonPrimitive).doubleOrNull!!.toFloat() }

    @Test fun theSchemaNodesAndPickersAreTheDesktopOnes() {
        val b = TestSetup.schema.modules.getValue(BORDERS_OP)
        assertEquals("border color", b.control(FramingWatermarkNodes.BORDER_ROW)!!.children.first().label)
        assertEquals("frame line color", b.control(FramingWatermarkNodes.FRAME_ROW)!!.children.first().label)
        for (id in listOf(14, 18)) assertNotNull("framing picker $id is built", b.pickers[id])
        assertEquals("border", b.pickers[14]!!.action!!.args["which"]!!.let { (it as JsonPrimitive).content })
        assertEquals("frame", b.pickers[18]!!.action!!.args["which"]!!.let { (it as JsonPrimitive).content })
        val w = TestSetup.schema.modules.getValue(WATERMARK_OP)
        assertNotNull("watermark picker is built", w.pickers[10])
        assertEquals(9, w.control(FramingWatermarkNodes.ALIGNMENT_GRID)!!.children.count { it.label == null })
        assertTrue("no control of watermark says it is not available", w.notes.values.none { it.contains("not available") })
    }

    @Test fun okIsOneHistoryItemCancelAndUnchangedOkAreNone() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        c.setViewport(Viewport(400, 300))
        c.framing.recentFile = File(tmp.root, "settings/recent-colours.txt")
        val n0 = items(c)
        // cancel
        c.framing.open(borders, FramingWatermarkNodes.BORDER)
        assertTrue(c.state.value.overlay is Overlay.ColorDialog)
        c.framing.cancel()
        assertNull(c.state.value.overlay)
        assertEquals(n0, items(c))
        // OK with a colour
        c.framing.open(borders, FramingWatermarkNodes.BORDER)
        c.framing.apply(borders, FramingWatermarkNodes.BORDER, Rgb.ofBytes(51, 102, 204)).join()
        waitFor("border colour written") { color(c, borders, "color") == Rgb.ofBytes(51, 102, 204).asFloats() }
        assertEquals(n0 + 1, items(c))
        assertNull(c.state.value.overlay)
        assertEquals("#3366CC", c.framing.recent.value.first())
        // OK with what is there: nothing written
        c.framing.apply(borders, FramingWatermarkNodes.BORDER, c.framing.colorOf(mod(c, borders), FramingWatermarkNodes.BORDER)!!).join()
        assertEquals(n0 + 1, items(c))
        // the frame line colour is its own field
        val fb = color(c, borders, "frame_color")
        c.framing.apply(borders, FramingWatermarkNodes.FRAME, Rgb.ofBytes(255, 136, 0)).join()
        waitFor("frame colour") { color(c, borders, "frame_color") == Rgb.ofBytes(255, 136, 0).asFloats() }
        assertEquals(Rgb.ofBytes(51, 102, 204).asFloats(), color(c, borders, "color"))
        assertEquals(n0 + 2, items(c))
        // undo takes the frame colour back
        c.undo()
        waitFor("undo") { color(c, borders, "frame_color") == fb }
        assertEquals(Rgb.ofBytes(51, 102, 204).asFloats(), color(c, borders, "color"))
        assertEquals(listOf("#FF8800", "#3366CC"), c.framing.recent.value.take(2))
        // the recent colours are per app: read again from the file
        val again = FramingWatermark(c).also { it.recentFile = File(tmp.root, "settings/recent-colours.txt") }
        assertEquals(c.framing.recent.value, again.recent.value)
    }

    @Test fun pickersWriteTheColourFromThePhoto() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        c.setViewport(Viewport(400, 300))
        val before = color(c, borders, "frame_color")
        c.togglePicker(borders, 18)
        waitFor("frame pick") { (c.state.value.picker?.picks ?: 0) > 0 }
        waitFor("frame colour from the photo") { color(c, borders, "frame_color") != before }
        // the dialog's OK turns the picker off, as desktop's _colorpick_color_set
        c.framing.apply(borders, FramingWatermarkNodes.BORDER, Rgb.ofBytes(1, 2, 3)).join()
        assertNull(c.state.value.picker)
        val wb = color(c, watermark, "color")
        c.togglePicker(watermark, 10)
        waitFor("watermark pick") { (c.state.value.picker?.picks ?: 0) > 0 }
        waitFor("watermark colour from the photo") { color(c, watermark, "color") != wb }
    }

    @Test fun watermarkListsTextFontAndAlignment() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        c.setViewport(Viewport(400, 300))
        c.framing.refresh(watermark).join()
        waitFor("lists") { c.framing.lists.value.files.isNotEmpty() && c.framing.lists.value.fonts.isNotEmpty() }
        val l = c.framing.lists.value
        assertEquals(7, l.files.size)
        assertEquals("simple-text (svg)", l.files.first { it.file == "simple-text.svg" }.label)
        assertEquals("DejaVu Sans", FramingWatermark.familyOf("DejaVu Sans 10", l.fonts))
        assertEquals("Roboto", FramingWatermark.familyOf("Roboto Bold 10", l.fonts))
        val n0 = items(c)
        c.framing.setText(watermark, "filename", "simple-text.svg").join()
        waitFor("file") { c.framing.text(mod(c, watermark), "filename") == "simple-text.svg" }
        c.framing.setText(watermark, "text", "hello").join()
        c.framing.setText(watermark, "font", "Roboto Bold 10").join()
        c.framing.setAlignment(watermark, 8).join()
        waitFor("alignment") { c.framing.alignment(mod(c, watermark)) == 8 }
        assertEquals("hello", c.framing.text(mod(c, watermark), "text"))
        assertEquals("Roboto Bold 10", c.framing.text(mod(c, watermark), "font"))
        assertEquals("one history item each", n0 + 4, items(c))
        // the same values again write nothing
        c.framing.setText(watermark, "text", "hello").join()
        c.framing.setAlignment(watermark, 8).join()
        assertEquals(n0 + 4, items(c))
        assertTrue(FramingWatermark.textApplies("a.svg") && FramingWatermark.textApplies("A.SVG") && !FramingWatermark.textApplies("logo.png"))
    }
}

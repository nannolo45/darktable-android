// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.engine.api.PickStats
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brief P3c: the pickers' schema data, the override files, the selection on the photo and the readout. */
class P3cPickerTest {
    private val schema = SchemaLoader.fromResources()
    private fun m(op: String) = schema.modules.getValue(op)

    @Test fun theExtractorsMarksAreRead() {
        // a slider with desktop's quad picker, a picker toggle button, a list with a quad picker
        assertTrue(5 in m("exposure").pickerMarks)
        assertTrue(4 in m("temperature").pickerMarks)
        assertTrue(7 in m("filmicrgb").pickerMarks)
        assertFalse(12 in m("exposure").pickerMarks)
        // every module with color_picker_apply has at least one mark, and no mark is outside such a module
        val total = schema.modules.values.sumOf { it.pickerMarks.size }
        assertEquals("marks in the schema (the extractor's colorpicker_buttons + colorpicker_quads)", 78, total)
    }

    @Test fun theFiveModulesWithEngineActionsHaveTheirPickers() {
        val wb = m("temperature").pickers.getValue(4)
        assertEquals("wb_from_area", wb.action!!.name)
        assertTrue(wb.area && !wb.point)
        val ex = m("exposure").pickers.getValue(5)
        assertEquals("spot", ex.action!!.name)
        assertEquals(mapOf("mode" to "exposure/area mode", "lightness" to "exposure/lightness"), ex.action!!.settings)
        for ((id, which) in listOf(7 to "black", 8 to "grey", 9 to "white")) {
            val p = m("rgblevels").pickers.getValue(id)
            assertEquals("pick", p.action!!.name)
            assertEquals(which, p.action!!.args["which"]!!.jsonPrimitive.content)
            assertTrue(p.point && !p.area)
        }
        assertEquals("set_nodes_from_area", m("rgbcurve").pickers.getValue(7).action!!.name)
        assertNull("show color only shows the values", m("rgbcurve").pickers.getValue(6).action)
        assertEquals("spot", m("channelmixerrgb").pickers.getValue(6).action!!.name)
        // pickers without an engine action are not built (shown greyed out)
        assertNull(m("colorzones").pickers[7])
        assertNull(m("rgblevels").pickers[12])
    }

    @Test fun settingsAreBoundAndNoLongerWait() {
        val mode = m("exposure").control(14) as Control.ComboBox
        assertEquals(Binding.Setting("exposure/area mode"), mode.binding)
        val target = m("exposure").control(22) as Control.Slider
        assertEquals(Binding.Setting("exposure/lightness"), target.binding)
        assertEquals(50.0, target.default, 0.0)
        assertNull(m("exposure").notes[22])
        val mix = m("channelmixerrgb").control(17) as Control.Toggle
        assertTrue("desktop's default without a stored value", mix.default)
        val area = m("channelmixerrgb").pickerSettings.getValue(16)
        assertTrue("measure" in area.unwritable)
        assertEquals(0, area.entryOf(JsonPrimitive("correction")))
        assertEquals(-1, area.entryOf(JsonPrimitive("nonsense")))
    }

    @Test fun actionArgumentsCarryTheSelectionTheSettingsAndTheTab() {
        val ex = m("exposure").pickers.getValue(5).action!!
        val settings = mapOf("exposure/area mode" to JsonPrimitive("measure"), "exposure/lightness" to JsonPrimitive(42.0))
        val (name, args) = ex.call(PickSelection.area(0.1, 0.2, 0.3, 0.4), { settings[it] }, { 0 })
        assertEquals("spot", name)
        assertEquals("measure", args["mode"]!!.jsonPrimitive.content)
        assertEquals(42.0, args["lightness"]!!.jsonPrimitive.double, 0.0)
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), (args["area"] as JsonArray).map { it.jsonPrimitive.double })

        val cm = m("channelmixerrgb").pickers.getValue(6).action!!
        val neutral = mapOf("channelmixerrgb/area mode" to JsonPrimitive("correction"), "channelmixerrgb/use mixing" to JsonPrimitive(true),
            "channelmixerrgb/lightness" to JsonPrimitive(50.0), "channelmixerrgb/hue" to JsonPrimitive(0.0), "channelmixerrgb/chroma" to JsonPrimitive(0.0))
        assertEquals("a neutral target is the CAT tab's own picker", "illuminant_from_area", cm.call(PickSelection.DEFAULT_AREA, { neutral[it] }, { 0 }).first)
        val warm = neutral + ("channelmixerrgb/chroma" to JsonPrimitive(12.0)) + ("channelmixerrgb/hue" to JsonPrimitive(70.0))
        val (n2, a2) = cm.call(PickSelection.DEFAULT_AREA, { warm[it] }, { 0 })
        assertEquals("spot", n2)
        val t = a2["target"] as JsonObject
        assertEquals(12.0, t["chroma"]!!.jsonPrimitive.double, 0.0)
        assertEquals(70.0, t["hue"]!!.jsonPrimitive.double, 0.0)
        assertEquals(true, a2["use_mixing"]!!.jsonPrimitive.content.toBoolean())

        val lv = m("rgblevels").pickers.getValue(9).action!!
        val (n3, a3) = lv.call(PickSelection.point(0.5, 0.25), { null }, { tabs -> assertEquals(1, tabs); 2 })
        assertEquals("pick", n3)
        assertEquals(2, a3["channel"]!!.jsonPrimitive.content.toInt())
        assertEquals("white", a3["which"]!!.jsonPrimitive.content)
        assertEquals(listOf(0.5, 0.25), (a3["point"] as JsonArray).map { it.jsonPrimitive.double })
        assertNull(a3["area"])
    }

    @Test fun theChannelTabsHideWithLinkedChannels() {
        val ms = m("rgblevels")
        fun visible(mode: String) = Rules.isVisible(ms, ms.control(1)!!, ParamRuleInputs(ms, JsonObject(mapOf("autoscale" to JsonPrimitive(mode)))))
        assertFalse(visible("DT_IOP_RGBLEVELS_LINKED_CHANNELS"))
        assertTrue(visible("DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS"))
    }

    @Test fun aTouchOnTheAreaMovesOrResizesIt() {
        val a = PickSelection.area(0.2, 0.2, 0.6, 0.6)
        val t = 0.05
        assertEquals(PickerTool.GRAB_MOVE, PickerTool.hit(a, 0.4, 0.4, t, t))
        assertEquals(PickerTool.GRAB_LEFT or PickerTool.GRAB_TOP, PickerTool.hit(a, 0.21, 0.19, t, t))
        assertEquals(PickerTool.GRAB_RIGHT, PickerTool.hit(a, 0.61, 0.4, t, t))
        assertEquals(PickerTool.GRAB_NONE, PickerTool.hit(a, 0.8, 0.8, t, t))
        assertEquals(PickerTool.GRAB_NONE, PickerTool.hit(null, 0.4, 0.4, t, t))
        // moved: kept on the picture
        val moved = PickerTool.drag(a, PickerTool.GRAB_MOVE, 0.4, 0.4, 0.9, 0.5)
        assertEquals(listOf(0.6, 0.3, 1.0, 0.7), moved.area!!.map { Math.round(it * 1e9) / 1e9 })
        // resized from a corner past the opposite side: never smaller than MIN_SIZE
        val r = PickerTool.drag(a, PickerTool.GRAB_RIGHT or PickerTool.GRAB_BOTTOM, 0.6, 0.6, 0.0, 0.0)
        assertEquals(0.2 + PickerTool.MIN_SIZE, r.area!![2], 1e-12)
        assertEquals(0.2 + PickerTool.MIN_SIZE, r.area!![3], 1e-12)
        // a point
        val p = PickSelection.point(0.5, 0.5)
        assertEquals(PickerTool.GRAB_POINT, PickerTool.hit(p, 0.52, 0.49, t, t))
        assertEquals(listOf(1.0, 0.3), PickerTool.drag(p, PickerTool.GRAB_POINT, 0.5, 0.5, 1.2, 0.3).point!!.map { Math.round(it * 1e9) / 1e9 })
        // a new area from any two corners; none without a size
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), PickerTool.newArea(0.3, 0.4, 0.1, 0.2)!!.area)
        assertNull(PickerTool.newArea(0.3, 0.4, 0.3, 0.4))
        assertNull(PickerTool.newPoint(1.2, 0.5))
    }

    @Test fun cornersFromTheOriginalImageGiveTheirBoundingBox() {
        val s = PickSelection.area(0.1, 0.2, 0.3, 0.4)
        assertEquals(listOf(0.1 to 0.2, 0.3 to 0.2, 0.3 to 0.4, 0.1 to 0.4), s.corners())
        // turned corners (a rotation in the edit): their bounding box, clipped to the picture
        val turned = listOf(0.5 to -0.1, 0.9 to 0.3, 0.5 to 0.7, 0.1 to 0.3)
        assertEquals(listOf(0.1, 0.0, 0.9, 0.7), PickSelection.fromCorners(turned, area = true)!!.area)
        assertNull("outside the picture", PickSelection.fromCorners(listOf(1.2 to 0.1, 1.5 to 0.1, 1.5 to 0.3, 1.2 to 0.3), area = true))
        assertNull("a point no module could map", PickSelection.fromCorners(listOf(null), area = false))
        assertEquals(listOf(0.25, 0.75), PickSelection.fromCorners(listOf(0.25 to 0.75), area = false)!!.point)
    }

    @Test fun theReadoutIsDesktopsGlobalPickersLine() {
        // sRGB white and middle grey: Lab of the D50 white and of 0.5 encoded
        val white = PickerReadout.labOfSrgb(1.0, 1.0, 1.0)
        assertEquals(100.0, white[0], 0.01)
        assertEquals(0.0, white[1], 0.01)
        assertEquals(0.0, white[2], 0.01)
        assertEquals(53.39, PickerReadout.labOfSrgb(0.5, 0.5, 0.5)[0], 0.01)
        // pure sRGB red in D50 Lab (ICC sRGB, Bradford): 54.29 80.80 69.89 within 0.05
        val red = PickerReadout.labOfSrgb(1.0, 0.0, 0.0)
        assertEquals(54.29, red[0], 0.05)
        assertEquals(80.80, red[1], 0.1)
        assertEquals(69.89, red[2], 0.1)
        val st = PickStats("rgb", listOf(0.5, 0.5, 0.5, 0.0), listOf(0.0, 0.0, 0.0, 0.0), listOf(1.0, 1.0, 1.0, 0.0), listOf(0, 0, 1, 1))
        assertEquals(listOf("mean  RGB 128 128 128  Lab 53.39 0.00 0.00"), PickerReadout.lines(st, area = false))
        val lines = PickerReadout.lines(st, area = true)
        assertEquals(3, lines.size)
        assertEquals("min  RGB 0 0 0  Lab 0.00 0.00 0.00", lines[1])
        assertTrue(lines[2].startsWith("max  RGB 255 255 255  Lab 100.00"))
        assertEquals(emptyList<String>(), PickerReadout.lines(null, area = true))
    }

    @Test fun aBadOverrideFailsLoudly() {
        val text = SchemaLoader.schemaTextFromResources()
        fun load(o: String) = runCatching { SchemaLoader.load(text, mapOf("x.json" to o)) }.exceptionOrNull()?.message
        assertNotNull(load("""{"op":"exposure","picker":[{"node":12,"expect_label":"black level correction","kinds":["area"]}]}"""))
        assertNotNull(load("""{"op":"exposure","picker":[{"node":5,"expect_label":"exposure","kinds":["area"],"action":{"name":"spot","settings":{"mode":"nope"}}}]}"""))
        assertNotNull(load("""{"op":"exposure","picker_setting":[{"node":14,"expect_label":"area mode","key":"k","entries":{"a":"a"},"default":"a"}]}"""))
        assertNull(load("""{"op":"exposure","picker":[{"node":5,"expect_label":"exposure","kinds":["area","point"]}]}"""))
    }
}

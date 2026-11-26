// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brief P6a: the override data of white balance's settings list, graduated density's offset and the new pickers. */
class P6aModuleGapsTest {
    private val schema = SchemaLoader.fromResources()
    private fun m(op: String) = schema.modules.getValue(op)

    /** an answer of list_settings as the bridge gives it (engine/src/actions.c) */
    private val settings = Json.parseToJsonElement(
        """[{"name":"as shot","kind":"mode","current":false,"choosable":true},
            {"name":"from image area","kind":"picker","current":false,"choosable":true},
            {"name":"user modified","kind":"mode","current":false,"choosable":false,"reason":"set by the temperature, tint or channel sliders"},
            {"name":"camera reference","kind":"mode","current":false,"choosable":true},
            {"name":"Sony DSC-RX100M3","kind":"section","current":false,"choosable":false},
            {"name":"daylight","kind":"camera","current":true,"choosable":true,"tuning":1,"tuning_min":-7,"tuning_max":7,"tuning_default":0},
            {"name":"cloudy","kind":"camera","current":false,"choosable":true,"tuning":0,"tuning_min":0,"tuning_max":0,"tuning_default":0}]""")

    @Test fun whiteBalanceSettingsIsDesktopsListFromTheEngine() {
        val wb = m("temperature")
        val c = wb.control(9) as Control.ComboBox
        assertEquals(Binding.ActionList, c.binding)
        assertNull("no note any more", wb.notes[9])
        val l = wb.actionLists.getValue(9)
        assertEquals("list_settings", l.list)
        assertEquals("apply_preset", l.action)
        val e = l.entries(settings)
        assertEquals(7, e.size)
        assertEquals("daylight", l.label(e[l.currentIndex(e)]))
        // the camera's name is a heading, "user modified" cannot be chosen (with desktop's reason), "from image area" arms the picker
        assertTrue(l.isSection(e[4]))
        assertFalse(l.isEnabled(e[4]))
        assertFalse(l.isEnabled(e[2]))
        assertEquals("set by the temperature, tint or channel sliders", l.whyDisabled(e[2]))
        assertEquals(4, l.armsPicker(e[1]))
        assertNull(l.armsPicker(e[0]))
        assertNotNull(wb.pickers[4])
        // a mode is applied by its name only, a camera preset with the tuning desktop's callback takes
        assertEquals(JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("as shot"))), l.args(e[0]))
        assertEquals("1", l.args(e[5])["tuning"].toString())
        assertEquals("\"daylight\"", l.args(e[5])["name"].toString())
    }

    @Test fun finetuneIsShownOnlyForAPresetWithFineTuningWithItsLimits() {
        val f = m("temperature").control(10) as Control.Slider
        val b = f.binding as Binding.Action
        assertEquals("finetune_min", b.needs)
        assertTrue(b.hide)
        assertEquals(mapOf("min" to "finetune_min", "max" to "finetune_max", "default" to "finetune_default"), b.rangeFrom)
        assertEquals("apply_preset", b.set)
        assertTrue(b.round)
    }

    @Test fun entriesOfTheListOverrideAreChecked() {
        val text = SchemaLoader.schemaTextFromResources()
        fun load(o: String) = runCatching { SchemaLoader.load(text, mapOf("x.json" to o)) }.exceptionOrNull()?.message
        val bad = """{"op":"denoiseprofile","action_list":[{"node":0,"expect_label":"profile","list":"list_profiles","action":"set_profile",
            "action_args":{"index":"index"},"arm_picker":{"when":{"kind":"picker"}}}]}"""
        assertNotNull(load(bad))
        val badRange = """{"op":"temperature","bind_action":[{"node":12,"expect_label":"temperature","get":"get_wb","set":"set_wb",
            "field":"temperature","range_from":{"top":"x"}}]}"""
        assertNotNull(load(badRange))
    }

    @Test fun graduatedDensityHasAnOffsetSliderAfterRotation() {
        val g = m("graduatednd")
        val ids = g.controls.map { it.id }
        val off = g.controls.filterIsInstance<Control.Slider>().single { it.binding == Binding.Param("offset") }
        assertEquals("right after rotation", ids.indexOf(2) + 1, ids.indexOf(off.id))
        assertEquals(5, off.id)
        assertEquals(0.0, off.hardMin, 0.0)
        assertEquals(100.0, off.hardMax, 0.0)
        assertEquals(50.0, off.default, 0.0)
        assertEquals("%", off.format)
        // the other sliders keep their ids
        assertEquals("rotation", g.control(2)!!.label)
        assertEquals("hue", g.control(3)!!.label)
    }

    @Test fun addSliderIsChecked() {
        val text = SchemaLoader.schemaTextFromResources()
        fun load(o: String) = runCatching { SchemaLoader.load(text, mapOf("x.json" to o)) }.exceptionOrNull()?.message
        fun grad(extra: String) = """{"op":"graduatednd","add_slider":[{"after":2,"expect_label":"rotation","hard_min":0,"hard_max":100,"default":50$extra}]}"""
        assertNull(load(grad(""","param":"offset"""")))
        assertNotNull("no such parameter", load(grad(""","param":"nope"""")))
        assertNotNull("already has a slider", load(grad(""","param":"density"""")))
        assertNotNull("wrong label", load("""{"op":"graduatednd","add_slider":[{"after":2,"expect_label":"hue","param":"offset","hard_min":0,"hard_max":100,"default":50}]}"""))
    }

    @Test fun theSettingsAnswerParses() {
        assertEquals(7, settings.jsonArray.size)
        assertEquals("section", settings.jsonArray[4].jsonObject["kind"].toString().trim('"'))
    }
}

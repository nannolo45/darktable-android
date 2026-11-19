// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Loader, rules, overrides and groups against real entries of schema/ui-schema.json. */
class SchemaTest {
    private val schema by lazy { SchemaLoader.fromResources() }

    private fun m(op: String) = schema.modules.getValue(op)

    private fun inputs(op: String, vararg params: Pair<String, Any>): RuleInputs =
        ParamRuleInputs(m(op), JsonObject(params.associate { (k, v) ->
            k to when (v) {
                is String -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> error("bad value")
            }
        }))

    private fun visible(op: String, id: Int, i: RuleInputs) = Rules.isVisible(m(op), m(op).control(id)!!, i)

    @Test
    fun loadsEveryModuleFromThePackedFile() {
        assertEquals("04477d3", schema.darktableVersion)
        assertEquals(97, schema.modules.size)
        assertEquals(90, schema.modules.values.count { it.hasPanel })
        assertFalse(m("gamma").hasPanel)
        assertTrue(m("demosaic").controls.isNotEmpty())
    }

    /** P1c: colour stops as desktop sets them, and the sliders whose stops desktop recomputes. */
    @Test
    fun sliderColourStops() {
        val all = schema.modules.values.flatMap { it.allControls() }.filterIsInstance<Control.Slider>()
        assertEquals(114, all.count { it.gradient.isNotEmpty() })
        val cb = m("colorbalancergb").allControls().filterIsInstance<Control.Slider>()
        val hue = cb.first { it.label == "hue" && it.gradient.isNotEmpty() }
        assertEquals(20, hue.gradient.size)
        // positions run over 0..1 of the hard range; the first hue stop is a red
        assertEquals(0.0, hue.gradient.first().position, 1e-9)
        assertEquals(1.0, hue.gradient.last().position, 1e-9)
        assertEquals(1.0, hue.gradient.first().red, 1e-9)
        // color balance rgb repaints its chroma sliders from the hue (gui_changed): recomputed
        assertTrue(cb.filter { it.label == "chroma" && it.gradient.isNotEmpty() }.any { it.gradientRecomputed })
        assertFalse(hue.gradientRecomputed)
        // a plain slider has none
        assertTrue(m("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }.gradient.isEmpty())
    }

    @Test
    fun exposureSliderAndItsStackRule() {
        val e = m("exposure")
        val slider = e.allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        assertEquals(Binding.Param("exposure"), slider.binding)
        assertEquals(-3.0, slider.softMin, 0.0); assertEquals(4.0, slider.softMax, 0.0)
        assertEquals(-18.0, slider.hardMin, 0.0); assertEquals(18.0, slider.hardMax, 0.0)
        assertEquals(" EV", slider.format)
        assertEquals("+0.700 EV", ValueFormat.text(slider, 0.7))
        assertEquals("-1.250 EV", ValueFormat.text(slider, -1.25))
        // the manual page (2) and the deflicker page (6) follow "mode"
        val manual = inputs("exposure", "mode" to "EXPOSURE_MODE_MANUAL")
        val deflicker = inputs("exposure", "mode" to "EXPOSURE_MODE_DEFLICKER")
        assertTrue(visible("exposure", 2, manual)); assertFalse(visible("exposure", 6, manual))
        assertFalse(visible("exposure", 2, deflicker)); assertTrue(visible("exposure", 6, deflicker))
        // hidden in every observed state on this image class: the highlight preservation switch
        assertFalse(visible("exposure", 4, manual))
        // the mode list itself has no rule
        assertTrue(visible("exposure", 0, deflicker))
    }

    @Test
    fun moduleWithTabs() {
        val tabs = m("colorbalancergb").controls.filterIsInstance<Control.Tabs>().single()
        assertEquals(listOf("master", "4 ways", "masks"), tabs.pages.map { it.label })
        val master = tabs.pages[0].children
        assertTrue(master.any { it is Control.Section && it.label == "linear chroma grading" })
        assertEquals(Binding.Param("vibrance"), (master.first { it.label == "global vibrance" } as Control.Slider).binding)
        // tone equalizer: tabs, and rules inside the masking tab driven by "preserve details"
        val te = m("toneequal")
        assertEquals(listOf("simple", "advanced", "masking"), te.controls.filterIsInstance<Control.Tabs>().single().pages.map { it.label })
        val off = inputs("toneequal", "details" to "DT_TONEEQ_NONE")
        val guided = inputs("toneequal", "details" to "DT_TONEEQ_GUIDED")
        assertFalse(visible("toneequal", 19, off))   // filter diffusion
        assertTrue(visible("toneequal", 19, guided))
        assertTrue(visible("toneequal", 15, guided)) // mask contrast compensation: guided filter and EIGF only
        assertFalse(visible("toneequal", 15, inputs("toneequal", "details" to "DT_TONEEQ_AVG_GUIDED")))
    }

    @Test
    fun nestedAndCombinedConditions() {
        // lens correction: TCA red (21) sits on the Lensfun page (6, rule: method = Lensfun)
        // and needs method = Lensfun AND TCA override on
        val lensfunOverride = inputs("lens", "method" to "DT_IOP_LENS_METHOD_LENSFUN", "tca_override" to true)
        val lensfunNoOverride = inputs("lens", "method" to "DT_IOP_LENS_METHOD_LENSFUN", "tca_override" to false)
        val onlyVignette = inputs("lens", "method" to "DT_IOP_LENS_METHOD_ONLYVIGNETTE", "tca_override" to true)
        assertTrue(visible("lens", 6, lensfunOverride))
        assertTrue(visible("lens", 21, lensfunOverride))
        assertFalse(visible("lens", 21, lensfunNoOverride))
        assertFalse(visible("lens", 6, onlyVignette))
        assertFalse(visible("lens", 21, onlyVignette))
        assertTrue(visible("lens", 31, onlyVignette))
        // a value the extractor never saw (embedded metadata, an X-Trans case) hides nothing
        val metadata = inputs("lens", "method" to "DT_IOP_LENS_METHOD_EMBEDDED_METADATA", "tca_override" to false)
        assertTrue(visible("lens", 6, metadata))
        assertTrue(visible("lens", 31, metadata))
    }

    @Test
    fun tableRuleOfHighlightReconstruction() {
        val hl = m("highlights")
        val rule = hl.visibility.getValue(10)
        assertNotNull(rule.table)
        fun at(mode: String, recovery: String) = visible("highlights", 10, inputs("highlights", "mode" to mode, "recovery" to recovery))
        assertTrue(at("DT_IOP_HIGHLIGHTS_LAPLACIAN", "DT_RECOVERY_MODE_OFF"))
        assertFalse(at("DT_IOP_HIGHLIGHTS_SEGMENTS", "DT_RECOVERY_MODE_OFF"))
        assertTrue(at("DT_IOP_HIGHLIGHTS_SEGMENTS", "DT_RECOVERY_MODE_ADAPT"))
        assertFalse(at("DT_IOP_HIGHLIGHTS_OPPOSED", "DT_RECOVERY_MODE_OFF"))
        // a combination the table does not list is shown
        assertTrue(at("DT_IOP_HIGHLIGHTS_INPAINT", "DT_RECOVERY_MODE_OFF"))
    }

    @Test
    fun unverifiedRulesNeverHide() {
        val sp = m("spektrafilm")
        val bad = sp.visibility.values.single { !it.verified }
        assertTrue(Rules.holds(bad, inputs("spektrafilm")))
    }

    @Test
    fun overridesAreApplied() {
        val row = m("flip").controls.single() as Control.ButtonRow
        assertEquals(listOf("rotateLeft", "rotateRight", "flipHorizontal", "flipVertical"), row.buttons.map { it.icon })
        assertEquals("ORIENTATION_ROTATE_CW_90_DEG", row.buttons[1].map["ORIENTATION_NONE"])
        assertEquals("ORIENTATION_NONE", row.buttons[0].map["ORIENTATION_ROTATE_CW_90_DEG"])
        // four rotations in one direction come back to the start, for every constant
        for (start in row.buttons[1].map.keys.filter { it != "ORIENTATION_NULL" }) {
            var o = start
            repeat(4) { o = row.buttons[1].map.getValue(o) }
            assertEquals(m("flip").params["orientation"]!!.enumValues[start], m("flip").params["orientation"]!!.enumValues[o])
        }
        assertEquals(listOf("flip.json"), m("flip").overrides)
        val temp = m("temperature").control(12) as Control.Slider
        assertEquals(Binding.Action("get_wb", "set_wb", "temperature", listOf("temperature", "tint")), temp.binding)
    }

    @Test
    fun overridesThatNoLongerFitFailLoudly() {
        val text = SchemaLoader.schemaTextFromResources()
        try {
            SchemaLoader.load(text, mapOf("flip.json" to """{"op":"flip","replace_with_buttons":[{"node":0,"expect_action_paths":["rotate"],"buttons":[]}]}"""))
            fail("expected a SchemaException")
        } catch (e: SchemaLoader.SchemaException) {
            assertTrue(e.message!!.contains("expected"))
        }
        try {
            SchemaLoader.load(text, mapOf("t.json" to """{"op":"temperature","bind_action":[{"node":15,"get":"g","set":"s","field":"f"}]}"""))
            fail("binding a bound slider must fail")
        } catch (e: SchemaLoader.SchemaException) {
            assertTrue(e.message!!.contains("already bound"))
        }
    }

    @Test
    fun sliderTextMatchesWhatDesktopShowed() {
        // every slider recorded the text desktop showed for its default value
        val sliders = schema.modules.values.flatMap { mod -> mod.allControls().filterIsInstance<Control.Slider>().map { mod.op to it } }
            .filter { it.second.textDefault != null }
        val mismatches = sliders.filter { (_, s) -> ValueFormat.text(s, s.default) != s.textDefault }
        val report = mismatches.joinToString("\n") { (op, s) -> "$op/${s.label}: ${ValueFormat.text(s, s.default)} vs ${s.textDefault}" }
        // known: darktable formats a few sliders in code (see the P2a report); everything else must match
        assertTrue("${mismatches.size} of ${sliders.size} differ:\n$report", mismatches.size <= KNOWN_FORMAT_DIFFERENCES)
    }

    @Test
    fun valueMappingAndExactEntry() {
        val s = m("sigmoid").allControls().filterIsInstance<Control.Slider>().first { it.label == "red attenuation" }
        assertEquals(100.0, s.factor, 0.0)
        assertEquals("25.0%", ValueFormat.text(s, 0.25))
        assertEquals(0.25, ValueFormat.parse(s, "25")!!, 1e-9)
        assertEquals(0.25, ValueFormat.parse(s, "25 %")!!, 1e-9)
        assertEquals(null, ValueFormat.parse(s, "120")) // hard max 0.99
        assertEquals(0.0, ValueFormat.valueAt(s, 0f), 0.0)
        assertEquals(0.5, ValueFormat.valueAt(s, 1f), 0.0)
        assertEquals(0.5f, ValueFormat.fraction(s, 0.25), 1e-6f)
        val e = m("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        assertEquals(1.234, ValueFormat.quantize(e, 1.23449), 1e-9)
        assertEquals(1.0f, ValueFormat.fraction(e, 10.0), 0f) // beyond the soft range: at the end
        assertEquals(10.0, ValueFormat.parse(e, "+10 EV")!!, 1e-9) // exact entry reaches the hard range
    }

    @Test
    fun groupsAndQuickPanelOfTheDefaultLayout() {
        // P2a checked an invented module-groups.json here (groups by default group, a quick panel of
        // exposure, sigmoid contrast, white balance temperature, vibrance). The groups and the quick
        // panel now come from darktable's default layout, "workflow: scene-referred" for the default
        // workflow "scene-referred (sigmoid)", at the pinned commit.
        val b = app.dtandroid.schema.layout.BuiltinLayouts.fromResources()
        val l = app.dtandroid.schema.layout.LayoutCodec.parse(b.presets(b.defaultWorkflow).first { it.name == b.fallbackLayout }.params)
        assertEquals(listOf("base", "color", "correct", "effect"), l.groups.map { it.name })
        assertTrue("exposure" in l.groups.first().modules)
        assertTrue(l.quickAccessShown)
        val quick = l.quickAccess.map { app.dtandroid.schema.layout.QuickAccess.resolve(schema, it) }
        assertEquals("sigmoid · contrast", quick.first().let { "${it.module!!.name} · ${it.label}" })
        assertTrue("no white balance control", quick.none { it.op == "temperature" })
        assertTrue("color calibration temperature", quick.any { it.id == "channelmixerrgb/temperature" && it.mapped })
    }

    @Test
    fun generatedPanelCounts() {
        val withPanel = schema.modules.values.filter { it.hasPanel }
        val generated = withPanel.filter { it.editableCount > 0 }
        // printed for the report; the numbers are checked loosely so a schema update does not break the build
        println("modules with a panel: ${withPanel.size}, with generated controls: ${generated.size}")
        println("without: " + withPanel.filter { it.editableCount == 0 }.joinToString { it.op })
        assertTrue(generated.size >= 75)
    }

    companion object {
        const val KNOWN_FORMAT_DIFFERENCES = 0
    }
}

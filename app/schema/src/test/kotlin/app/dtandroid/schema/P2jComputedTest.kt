// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Brief P2j deliverable 6 (JVM): every control built through `schema/overrides/` goes control -> parameters ->
 * control, against values computed by upstream's own C functions (`tools/p2j-reference-vectors.py`, output in
 * `src/test/resources/p2j-reference.json`) or upstream's tables; plus the enable and visibility rules.
 */
class P2jComputedTest {
    private val schema by lazy { SchemaLoader.fromResources() }
    private val ref by lazy {
        Json.parseToJsonElement(javaClass.classLoader.getResourceAsStream("p2j-reference.json")!!.readBytes().decodeToString()).jsonObject
    }

    private fun m(op: String) = schema.modules.getValue(op)
    private fun cases(k: String) = (ref[k] as JsonArray).map { it.jsonObject }
    private fun JsonObject.d(k: String) = getValue(k).jsonPrimitive.double
    private fun params(vararg kv: Pair<String, Any>) = JsonObject(kv.associate { (k, v) ->
        k to when (v) {
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })
    private fun slider(op: String, id: Int) = m(op).computed.getValue(id) as ComputedSlider
    private fun list(op: String, id: Int) = m(op).computed.getValue(id) as ComputedList
    private fun near(what: String, want: Double, got: Double, rel: Double) =
        assertTrue("$what: want $want, got $got", abs(want - got) <= rel * maxOf(1.0, abs(want)))
    private fun JsonObject.with(w: Map<String, JsonPrimitive>) = JsonObject(this + w)
    private val noRecall: (Int) -> Double? = { null }

    // ------------------------------------------------------------------ color calibration: hue and chroma

    @Test fun colorCalibrationHueAndChromaAreBound() {
        for (id in listOf(11, 12)) {
            val c = m("channelmixerrgb").control(id) as Control.Slider
            assertEquals(Binding.Computed, c.binding)
            assertTrue(c.isEditable)
        }
    }

    @Test fun illuminantHueChromaWriteAndShowAsUpstream() {
        val hue = slider("channelmixerrgb", 11)
        val chroma = slider("channelmixerrgb", 12)
        for (k in cases("illuminant")) {
            val h = k.d("hue"); val c = k.d("chroma")
            // a module whose illuminant already has this chroma: setting the hue writes upstream's x, y, temperature
            val p0 = params("x" to k.d("x"), "y" to k.d("y"), "temperature" to 5000.0)
            val w = hue.write(h, p0, noRecall)
            near("x of h=$h c=$c", k.d("x"), w.getValue("x").double, 1e-5)
            near("y of h=$h c=$c", k.d("y"), w.getValue("y").double, 1e-5)
            val t = k.d("temperature").coerceIn(1667.0, 25000.0)
            near("temperature of h=$h c=$c", t, w.getValue("temperature").double, 2e-3)
            // and chroma from a neutral illuminant, with the hue the user set before (desktop keeps it on screen)
            val neutral = JsonObject(chroma.write(0.0, p0, noRecall))
            val w2 = chroma.write(c, neutral) { id -> if (id == 11) h else null }
            near("x via chroma h=$h c=$c", k.d("x"), w2.getValue("x").double, 1e-4)
            near("y via chroma h=$h c=$c", k.d("y"), w2.getValue("y").double, 1e-4)
            // parameters -> control: what gui_changed shows
            val p = p0.with(w)
            val hb = hue.value(p, noRecall)!!
            val dh = abs(hb - k.d("hue_back")).let { minOf(it, 360 - it) }
            assertTrue("hue back h=$h c=$c: want ${k.d("hue_back")} got $hb", dh < 1e-2)
            near("chroma back h=$h c=$c", k.d("chroma_back"), chroma.value(p, noRecall)!!, 1e-4)
        }
        for (k in cases("illuminant_show")) {
            val p = params("x" to k.d("x"), "y" to k.d("y"))
            near("chroma of ${k.d("x")},${k.d("y")}", k.d("chroma"), chroma.value(p, noRecall)!!, 1e-4)
        }
    }

    @Test fun hueAtChromaZeroIsTheLastValueSet() {
        val hue = slider("channelmixerrgb", 11)
        // x, y exactly at D50 in single precision give chroma 0 only where upstream's D50 u'v' is met; a zero chroma
        // written by the chroma slider is: x, y of chroma 0
        val zero = slider("channelmixerrgb", 12).write(0.0, params("x" to 0.4, "y" to 0.35, "temperature" to 3000.0), noRecall)
        val p = JsonObject(zero)
        assertNull("no hue to show at chroma 0", hue.value(p, noRecall))
        // setting the hue at chroma 0 keeps the illuminant white (x, y unchanged)
        val w = hue.write(120.0, p, noRecall)
        assertEquals(p.getValue("x").jsonPrimitive.double, w.getValue("x").double, 1e-7)
    }

    @Test fun hueAndChromaShowOnlyForACustomIlluminant() {
        val mod = m("channelmixerrgb")
        fun visible(illuminant: String) = Rules.isVisible(mod, mod.control(11)!!,
            ParamRuleInputs(mod, params("illuminant" to illuminant, "adaptation" to "DT_ADAPTATION_CAT16")))
        assertTrue(visible("DT_ILLUMINANT_CUSTOM"))
        assertFalse(visible("DT_ILLUMINANT_D"))
    }

    // ------------------------------------------------------------------ saturation curve and tone equalizer

    @Test fun protectCenterAndWidthAsUpstream() {
        val center = slider("satcurvergb", 12)
        val width = slider("satcurvergb", 13)
        for (k in cases("protect")) {
            val c = k.d("center"); val wd = k.d("width")
            // centre set on a range of width w (from 0 to w)
            val wc = center.write(c, params("gf_protect_from" to 0.0, "gf_protect_to" to wd.toFloat().toDouble()), noRecall)
            near("from c=$c w=$wd", k.d("from"), wc.getValue("gf_protect_from").double, 1e-6)
            near("to c=$c w=$wd", k.d("to"), wc.getValue("gf_protect_to").double, 1e-6)
            // width set on a range centred on c
            val ww = width.write(wd, params("gf_protect_from" to c, "gf_protect_to" to c), noRecall)
            near("from (width) c=$c w=$wd", k.d("from"), ww.getValue("gf_protect_from").double, 1e-6)
            val p = JsonObject(wc)
            near("centre back", k.d("center_back"), center.value(p, noRecall)!!, 1e-6)
            near("width back", k.d("width_back"), width.value(p, noRecall)!!, 1e-6)
        }
        val mod = m("satcurvergb")
        assertFalse(Rules.isSensitive(mod, mod.control(12)!!, ParamRuleInputs(mod, params("use_guided_filter" to false))))
        assertTrue(Rules.isSensitive(mod, mod.control(12)!!, ParamRuleInputs(mod, params("use_guided_filter" to true))))
    }

    @Test fun curveSmoothingAsUpstream() {
        val s = slider("toneequal", 13)
        for (k in cases("smoothing")) {
            val w = s.write(k.d("slider"), JsonObject(emptyMap()), noRecall)
            near("smoothing of ${k.d("slider")}", k.d("smoothing"), w.getValue("smoothing").double, 1e-6)
            near("slider back", k.d("slider_back"), s.value(JsonObject(w), noRecall)!!, 1e-5)
        }
    }

    // ------------------------------------------------------------------ fixed lists

    @Test fun framingListsChooseAndShowEveryEntry() {
        val aspect = list("borders", 2)
        val e = aspect.entries(JsonObject(emptyMap()))
        assertEquals(21, e.size)
        for ((i, entry) in e.withIndex()) {
            if (!entry.enabled) continue
            val w = aspect.choose(i, JsonObject(emptyMap()))
            if (entry.label == "custom...") { assertTrue(w.isEmpty()); continue }
            // DIN (sqrt 2) is within 0.01 of A4 (297/210), which comes first: desktop's gui_changed shows A4 for both
            assertEquals(entry.label, if (entry.label == "DIN") i - 1 else i, aspect.current(JsonObject(w)))
        }
        // upstream's table: 16:9 writes 16/9 in single precision; 1.5 shows 3:2; 2.2 shows custom
        assertEquals((16f / 9f).toDouble(), aspect.choose(5, JsonObject(emptyMap())).getValue("aspect").double, 0.0)
        assertEquals("3:2 (4x6, 10x15cm)", e[aspect.current(params("aspect" to 1.5))!!].label)
        assertEquals("custom...", e[aspect.current(params("aspect" to 2.2))!!].label)
        // the defaults (constant border, -1) show. Brief P3b (P2j section 6): image (0) and constant border (-1), outside the
        // field's limits, are written through the engine's borders set_aspect (before P3b they were greyed out)
        assertEquals("constant border", e[aspect.current(params("aspect" to -1.0))!!].label)
        assertTrue(e.first { it.label == "image" }.enabled)
        assertTrue(e.first { it.label == "constant border" }.enabled)
        assertEquals(ModuleWrite("set_aspect", JsonObject(mapOf("aspect" to JsonPrimitive(0)))), aspect.chooseAction(e.indexOfFirst { it.label == "image" }, JsonObject(emptyMap())))
        assertEquals(ModuleWrite("set_aspect", JsonObject(mapOf("aspect" to JsonPrimitive(-1)))), aspect.chooseAction(e.indexOfFirst { it.label == "constant border" }, JsonObject(emptyMap())))
        assertEquals("the other entries write parameters", null, aspect.chooseAction(5, JsonObject(emptyMap())))
        val pos = list("borders", 5)
        assertEquals("1/3", pos.entries(JsonObject(emptyMap()))[pos.current(params("pos_h" to 0.335))!!].label)
        assertEquals((3f / 8f).toDouble(), pos.choose(2, JsonObject(emptyMap())).getValue("pos_h").double, 0.0)
    }

    @Test fun interpolationWritesAllThreeCurves() {
        for ((op, node, name) in listOf(Triple("rgbcurve", 9, "curve_type"), Triple("tonecurve", 8, "tonecurve_type"))) {
            val l = list(op, node)
            for (i in 0..2) {
                val w = l.choose(i, JsonObject(emptyMap()))
                assertEquals(setOf("$name[0]", "$name[1]", "$name[2]"), w.keys)
                assertTrue(w.values.all { it.content == "$i" })
                val p = params(name to JsonArray(List(3) { JsonPrimitive(i) }))
                assertEquals(i, l.current(p))
            }
        }
    }

    @Test fun colourProfilesBuiltInEntries() {
        val out = list("colorout", 1)
        val w = out.choose(4, JsonObject(emptyMap()))
        assertEquals("DT_COLORSPACE_LIN_REC2020", w.getValue("type").content)
        assertEquals("", w.getValue("filename").content)
        assertEquals(4, out.current(JsonObject(w)))
        assertNull("an ICC file matches no built-in entry", out.current(params("type" to "DT_COLORSPACE_FILE", "filename" to "x.icc")))
        val work = list("colorin", 1)
        assertEquals("linear Rec2020 RGB", work.entries(JsonObject(emptyMap()))[work.current(params("type_work" to "DT_COLORSPACE_LIN_REC2020", "filename_work" to ""))!!].label)
        val intent = m("colorout").control(0) as Control.ComboBox
        assertEquals(Binding.Param("intent"), intent.binding)
        assertEquals("enum", intent.paramType)
    }

    // ------------------------------------------------------------------ spektrafilm

    private val sf by lazy { m("spektrafilm") }
    private fun label(l: ComputedList, p: JsonObject) = l.entries(p)[l.current(p)!!].label
    private fun defaults() = params("film_hash" to 0L, "paper_hash" to 0L, "lut_hash" to 0L, "pack_hash" to 0L, "upsampling_hash" to 0L,
        "scan_film" to false, "development_min" to 0.0, "print_development_min" to 0.0, "film_format_mm" to 36.0)

    @Test fun stockHashesAreUpstreams() {
        for (k in cases("name_hash")) assertEquals(k.getValue("stock").jsonPrimitive.content, k.getValue("hash").jsonPrimitive.long,
            SpektrafilmStocks.nameHash(k.getValue("stock").jsonPrimitive.content))
    }

    @Test fun spektrafilmFilmStock() {
        val film = list("spektrafilm", 2)
        val p0 = defaults()
        assertEquals("film_hash 0 shows the fallback", "Kodak Gold 200", label(film, p0))
        val portra = film.entries(p0).indexOfFirst { it.label == "Kodak Portra 400" }
        val w = film.choose(portra, p0)
        assertEquals(SpektrafilmStocks.nameHash("kodak_portra_400"), w.getValue("film_hash").long)
        assertEquals(0.0, w.getValue("development_min").double, 0.0)
        assertEquals(1449086660L, w.getValue("lut_hash").long)
        assertFalse("between two negatives scan_film stays", "scan_film" in w)
        assertEquals("Kodak Portra 400", label(film, p0.with(w)))
        // Double-X: a development family (4, 5, 6.5, 9, 12 -> 6.5); its auto paper is the monochrome print film 2302 (5 min)
        val dx = film.choose(film.entries(p0).indexOfFirst { it.label == "Kodak Double-X 5222" }, p0)
        assertEquals(6.5, dx.getValue("development_min").double, 1e-6)
        assertEquals(5.0, dx.getValue("print_development_min").double, 1e-6)
        assertEquals("auto (Kodak Print Film 2302)", label(list("spektrafilm", 3), p0.with(dx)))
        // a positive scans the film, with black and white point correction, and greys the paper out
        val pv = film.choose(film.entries(p0).indexOfFirst { it.label == "Fujifilm Provia 100F" }, p0)
        assertEquals(true, pv.getValue("scan_film").content.toBoolean())
        assertEquals(true, pv.getValue("scan_black_correction").content.toBoolean())
        val p1 = p0.with(pv)
        assertEquals("none (scan the film)", label(list("spektrafilm", 3), p1))
        assertFalse(list("spektrafilm", 3).enabled(p1))
        // sections are not choices
        assertTrue(film.entries(p0).filter { it.section }.none { it.enabled })
        assertTrue(film.choose(0, p0).isEmpty())
    }

    @Test fun spektrafilmPrintPaper() {
        val paper = list("spektrafilm", 3)
        val p0 = defaults()
        assertEquals("auto (Kodak Portra Endura)", label(paper, p0))
        val ep = paper.entries(p0).indexOfFirst { it.label == "Kodak Endura Premier" }
        val w = paper.choose(ep, p0)
        assertEquals(SpektrafilmStocks.nameHash("kodak_endura_premier"), w.getValue("paper_hash").long)
        assertEquals("Kodak Endura Premier", label(paper, p0.with(w)))
        val scan = paper.choose(0, p0)
        assertEquals("true", scan.getValue("scan_film").content)
        assertEquals(0L, scan.getValue("paper_hash").long)
        assertEquals("none (scan the film)", label(paper, p0.with(scan)))
        val auto = paper.choose(1, p0.with(scan))
        assertEquals("false", auto.getValue("scan_film").content)
        // the rule value is the schema's entry position (development time's sensitivity table uses it)
        assertEquals(1, ParamRuleInputs(sf, p0).control(3))
        assertEquals(5, ParamRuleInputs(sf, p0).control(2))
    }

    @Test fun spektrafilmFormatAndUpsampling() {
        val fmt = list("spektrafilm", 4)
        val p0 = defaults()
        assertEquals("35mm", label(fmt, p0))
        val s8 = fmt.choose(fmt.entries(p0).indexOfFirst { it.label == "Super 8" }, p0)
        assertEquals(5.79f.toDouble(), s8.getValue("film_format_mm").double, 0.0)
        val custom = p0.with(mapOf("film_format_mm" to JsonPrimitive(40.0)))
        assertEquals(17, fmt.current(custom))
        // the millimetre slider shows only on custom (_format_changed)
        val slider = sf.control(5)!!
        assertTrue(Rules.isVisible(sf, slider, ParamRuleInputs(sf, custom)))
        assertFalse(Rules.isVisible(sf, slider, ParamRuleInputs(sf, p0)))
        val up = list("spektrafilm", 33)
        assertEquals("hanatos2025", label(up, p0))
        assertFalse("one table: greyed out as on desktop", up.enabled(p0))
    }

    // ------------------------------------------------------------------ color harmonizer, notes, hidden

    @Test fun harmonizerSaturationSlidersFollowTheRule() {
        val mod = m("colorharmonizer")
        for (i in 0..3) assertEquals(Binding.Param("node_saturation[$i]"), (mod.control(30 + i) as Control.Slider).binding)
        fun visible(i: Int, rule: String, n: Int) = Rules.isVisible(mod, mod.control(30 + i)!!,
            ParamRuleInputs(mod, params("rule" to rule, "num_custom_nodes" to n)))
        assertTrue(visible(2, "DT_COLORHARMONIZER_TRIAD", 4))
        assertFalse(visible(3, "DT_COLORHARMONIZER_TRIAD", 4))
        assertTrue(visible(2, "DT_COLORHARMONIZER_CUSTOM", 3))
        assertFalse(visible(3, "DT_COLORHARMONIZER_CUSTOM", 3))
        assertFalse(visible(1, "DT_COLORHARMONIZER_MONOCHROMATIC", 4))
        assertFalse("the vectorscope sync is not shown", Rules.isVisible(mod, mod.control(23)!!, ParamRuleInputs(mod, params())))
    }

    @Test fun harmonizerHuesAsUpstream() {
        val samples = Json.parseToJsonElement(javaClass.classLoader.getResourceAsStream("p2j-harmonizer-samples.json")!!.readBytes().decodeToString()) as JsonArray
        val anchor = slider("colorharmonizer", 3)
        for (e in samples.map { it.jsonObject }) {
            val x = e.d("x")
            // parameter (UCS) -> slider (RYB degrees), as gui_update
            near("ucs->ryb $x", e.d("ucs_to_ryb") * 360, anchor.value(params("anchor_hue" to x), noRecall)!!, 1e-5)
            // slider -> parameter, as _anchor_hue_slider_changed
            near("ryb->ucs $x", e.d("ryb_to_ucs"), anchor.write(x * 360, JsonObject(emptyMap()), noRecall).getValue("anchor_hue").double, 1e-5)
        }
        // control -> parameters -> control within the tables' step (720 entries, 0.5 degree)
        for (deg in listOf(10.0, 95.5, 180.0, 300.0)) {
            val w = anchor.write(deg, JsonObject(emptyMap()), noRecall)
            val back = anchor.value(JsonObject(w), noRecall)!!
            assertTrue("anchor $deg -> $back", abs(back - deg) < 1.0)
        }
        val mod = m("colorharmonizer")
        val custom3 = mod.control(18)!!
        assertEquals(Binding.Computed, (custom3 as Control.Slider).binding)
        assertEquals("custom_hue[2]", slider("colorharmonizer", 18).write(90.0, JsonObject(emptyMap()), noRecall).keys.single())
        fun visible(c: Control, rule: String, n: Int) = Rules.isVisible(mod, c, ParamRuleInputs(mod, params("rule" to rule, "num_custom_nodes" to n)))
        assertTrue(visible(custom3, "DT_COLORHARMONIZER_CUSTOM", 3))
        assertFalse(visible(custom3, "DT_COLORHARMONIZER_CUSTOM", 2))
        assertFalse(visible(custom3, "DT_COLORHARMONIZER_TRIAD", 4))
        assertTrue(visible(mod.control(3)!!, "DT_COLORHARMONIZER_TRIAD", 4))
        assertFalse(visible(mod.control(3)!!, "DT_COLORHARMONIZER_CUSTOM", 4))
    }

    @Test fun controlsThatWaitSayWhat() {
        assertEquals("needs the colour picker (a later package)", m("exposure").notes[14])
        assertNotNull(m("channelmixerrgb").notes[16])
        assertNotNull(m("colorzones").notes[16])
        // a control with a note is still not editable
        assertFalse(m("exposure").control(14)!!.isEditable)
    }

    /** Brief P3b (P2j section 6): lens' "use latest algorithm" through the engine's lens use_latest_md, shown while md_version is 1. */
    @Test fun lensUseLatestAlgorithmIsAnAction() {
        val t = m("lens").computed.getValue(24) as ComputedToggle
        assertFalse("desktop shows it off", t.on(params("md_version" to "DT_IOP_LENS_EMBEDDED_METADATA_VERSION_1")))
        assertEquals(ModuleWrite("use_latest_md"), t.action(true, params()))
        assertNull(t.action(false, params()))
        assertTrue(m("lens").control(24)!!.isEditable)
        val rule = m("lens").visibility.getValue(24)
        assertTrue(Rules.holds(rule, ParamRuleInputs(m("lens"), params("md_version" to "DT_IOP_LENS_EMBEDDED_METADATA_VERSION_1"))))
        assertFalse(Rules.holds(rule, ParamRuleInputs(m("lens"), params("md_version" to "DT_IOP_LENS_EMBEDDED_METADATA_VERSION_2"))))
        assertNull("no longer a note", m("lens").notes[24])
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema.layout

import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.schema.SchemaLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The layout string, darktable's built-in layouts, quick access mapping and preset files. */
class LayoutTest {
    private val builtins by lazy { BuiltinLayouts.fromResources() }
    private val schema by lazy { SchemaLoader.fromResources() }

    private fun res(name: String) = javaClass.classLoader.getResourceAsStream(name)!!.readBytes()

    // ------------------------------------------------------------------ string format

    @Test
    fun everyBuiltinLayoutRoundTripsByteExact() {
        var n = 0
        for (wf in builtins.workflows) for (p in builtins.presets(wf)) {
            val back = LayoutCodec.write(LayoutCodec.parse(p.params))
            assertArrayEquals("$wf / ${p.name}", p.params.encodeToByteArray(), back.encodeToByteArray())
            n++
        }
        assertEquals(30, n)
        assertEquals("04477d32a00cc5abaea36d5bee084eb40c6eabb2", builtins.darktableCommit)
    }

    @Test
    fun builtinNamesAndDefaults() {
        assertEquals(
            listOf("modules: all", "workflow: beginner", "workflow: display-referred", "workflow: scene-referred", "search only"),
            builtins.presets(builtins.defaultWorkflow).map { it.name },
        )
        assertEquals("scene-referred (sigmoid)", builtins.defaultWorkflow)
        assertEquals("workflow: scene-referred", builtins.fallbackLayout)
        assertEquals(6, builtins.workflows.size)
        val search = LayoutCodec.parse(builtins.presets(builtins.defaultWorkflow).first { it.name == "search only" }.params)
        assertEquals(Layout(showSearch = true, fullActive = false, quickAccessShown = false), search)
    }

    @Test
    fun newLayoutKeepsDarktablesRepeatedEntryAndReadsItOnce() {
        // _presets_get_minimal starts with the full quick access list (SQA) and then adds
        // exposure/exposure and colorbalancergb/contrast again; darktable reads repeated entries once.
        val raw = builtins.newLayout(builtins.defaultWorkflow)
        assertEquals(2, raw.split("|exposure/exposure").size - 1)
        val l = LayoutCodec.parse(raw)
        assertEquals(1, l.quickAccess.count { it == "exposure/exposure" })
        assertEquals("colorbalancergb/contrast", l.quickAccess.last())
        assertEquals(listOf(LayoutGroup("base", "basic", listOf("sigmoid", "exposure", "colorbalancergb"))), l.groups)
        assertEquals(raw.replaceFirst("|exposure/exposure|colorbalancergb/contrastꬹ", "|colorbalancergb/contrastꬹ"), LayoutCodec.write(l))
    }

    @Test
    fun parseFollowsDarktablesRules() {
        val l = LayoutCodec.parse("0|1ꬹ1|||a/b|c|a/bꬹone|tone||x|nosuchmoduleꬹbad|iconꬹ|||")
        assertFalse(l.showSearch)
        assertTrue(l.fullActive)
        assertTrue(l.quickAccessShown)
        assertEquals(listOf("a/b", "c"), l.quickAccess)
        // "bad|icon" has two fields and is dropped; "|||" is a group with an empty name and icon
        assertEquals(listOf(LayoutGroup("one", "tone", listOf("x", "nosuchmodule")), LayoutGroup("", "", listOf(""))), l.groups)
        // quick access switched on but empty reads as off (darktable tests for more than three fields)
        assertFalse(LayoutCodec.parse("1|0ꬹ1||").quickAccessShown)
        // only exactly "0" hides search, only exactly "1" shows history
        assertEquals(Layout(true, false), LayoutCodec.parse("x|y"))
        assertEquals(Layout(true, false), LayoutCodec.parse(""))
        assertEquals(Layout(true, false), LayoutCodec.parse("ꬹ"))
        // unknown icon names are kept as written; they draw as "basic"
        assertEquals("sparkles", LayoutCodec.parse("1|0ꬹ0||ꬹg|sparkles||exposure").groups[0].icon)
        assertEquals("basic", GroupIcons.drawn("sparkles"))
    }

    @Test
    fun truncatedAndHostileStringsNeverThrowAndSettle() {
        val full = builtins.presets(builtins.defaultWorkflow).first { it.name == "modules: all" }.params
        val hostile = listOf("|", "||||||", "ꬹꬹꬹꬹ", "1", "\u0000", "1|0ꬹ1|||\u0000", "ꬹ|ꬹ|ꬹ", "1|0ꬹ1|||x".repeat(500),
            "1|0ꬹ0||ꬹ" + "a|b||m".repeat(2000), "\uD800", "1|0\nꬹ1||\n|e")
        val inputs = (0..full.length).map { full.substring(0, it) } + hostile
        for (s in inputs) {
            val once = LayoutCodec.write(LayoutCodec.parse(s))
            val twice = LayoutCodec.write(LayoutCodec.parse(once))
            assertEquals("written form of a written layout is stable: ${s.take(40)}", once, twice)
        }
    }

    // ------------------------------------------------------------------ quick access

    @Test
    fun quickAccessMappingTable() {
        val ids = LinkedHashSet<String>()
        for (wf in builtins.workflows) {
            for (p in builtins.presets(wf)) ids += LayoutCodec.parse(p.params).quickAccess
            ids += LayoutCodec.parse(builtins.newLayout(wf)).quickAccess
        }
        ids += builtins.recommendedQuickAccess
        val rows = ids.map { QuickAccess.resolve(schema, it) }
        // the table for the report
        val out = File(System.getProperty("dta.reportDir") ?: "build/reports/p2d").apply { mkdirs() }
        File(out, "quick-access-mapping.md").writeText(buildString {
            append("| entry | result | control | parameter | desktop shows it | why not mapped |\n|---|---|---|---|---|---|\n")
            for (r in rows) {
                val param = (r.control?.let { c -> (c as? Control.Slider)?.binding ?: (c as? Control.ComboBox)?.binding ?: (c as? Control.Toggle)?.binding } as? Binding.Param)?.path ?: ""
                append("| `${r.id}` | ${r.kind.name.lowercase()} | ${r.module?.name ?: ""} · ${r.label} | $param | ${when (r.shownOnDesktop) { true -> "yes"; false -> "no"; null -> "see report" }} | ${r.reason ?: ""} |\n")
            }
            append("\n${rows.count { it.mapped }} of ${rows.size} mapped\n")
        })
        fun r(id: String) = rows.first { it.id == id }
        assertEquals(QuickAccess.Kind.CONTROL, r("exposure/exposure").kind)
        assertEquals(Binding.Param("exposure"), (r("exposure/exposure").control as Control.Slider).binding)
        assertEquals(Binding.Param("curve_contrast_around_pivot"), (r("agx/curve/contrast").control as Control.Slider).binding)
        assertEquals("curve - contrast", r("agx/curve/contrast").label)
        assertEquals(Binding.Param("temperature"), (r("channelmixerrgb/temperature").control as Control.Slider).binding)
        assertEquals(Binding.Param("illum_fluo"), (r("channelmixerrgb/F source").control as Control.ComboBox).binding)
        assertEquals(QuickAccess.Kind.ON_OFF, r("lens").kind)
        assertEquals(QuickAccess.Kind.ON_OFF, r("bilat").kind)
        // derived on desktop, no parameter: until brief P2j shown disabled; now computed from x and y
        // (schema/overrides/channelmixerrgb.json) and usable
        for (id in listOf("channelmixerrgb/hue", "channelmixerrgb/chroma")) {
            assertEquals(id, QuickAccess.Kind.CONTROL, r(id).kind)
            assertEquals(true, r(id).shownOnDesktop)
            assertEquals(Binding.Computed, (r(id).control as Control.Slider).binding)
        }
        // white balance's temperature and tint go through module_action (schema/overrides/temperature.json, P2a)
        assertTrue((r("temperature/temperature").control as Control.Slider).binding is Binding.Action)
        assertEquals("hue", r("channelmixerrgb/hue").label)
        // graphs and tabs: not in the app yet
        for (id in listOf("colorequal/graph", "colorequal/page")) assertEquals(QuickAccess.Kind.UNAVAILABLE, r(id).kind)
        // no widget has this action path at the pinned commit (the picker is "exposure range/auto tune levels")
        assertFalse(r("agx/auto tune levels").mapped)
        assertFalse(r("toneequal/graph").mapped)
        // every entry resolves to something with a name; nothing is dropped
        assertTrue(rows.all { it.label.isNotEmpty() })
    }

    @Test
    fun unknownEntriesAreKeptAndExplained() {
        val e = QuickAccess.resolve(schema, "nosuchmodule/some slider")
        assertEquals(QuickAccess.Kind.UNAVAILABLE, e.kind)
        assertNull(e.module)
        val d = QuickAccess.resolve(schema, "clipping/angle")
        assertEquals(QuickAccess.Kind.UNAVAILABLE, d.kind)
        assertEquals("deprecated module: not shown on desktop", false, d.shownOnDesktop)
    }

    @Test
    fun addWidgetCandidates() {
        val c = QuickAccess.candidates(schema, builtins.recommendedQuickAccess)
        assertTrue(c.any { it.id == "exposure/exposure" && it.recommended })
        assertTrue(c.any { it.id == "lens" && it.label == "lens correction - on-off" && it.recommended })
        assertTrue(c.any { it.id == "agx/curve/contrast" && it.label == "AgX - curve - contrast" })
        assertFalse("pickers are buttons: not offered", c.any { it.id == "agx/exposure range/auto tune levels" })
        assertFalse("deprecated modules are not offered", c.any { it.op == "clipping" })
        assertEquals(c.size, c.map { it.id }.toSet().size)
    }

    // ------------------------------------------------------------------ preset files

    @Test
    fun readsTheFileDesktopExported() {
        // written by darktable 04477d3's dt_presets_save_to_file (tools/layouts/verify-with-darktable.sh)
        val bytes = res("dtpreset/desktop-export.dtpreset")
        val (name, text) = DtPreset.readLayout(bytes)
        assertEquals("probe layout", name)
        assertEquals(res("dtpreset/desktop-export.layout.txt").decodeToString(), text)
        assertEquals("modulegroups_probe layout.dtpreset", DtPreset.fileName(name))
    }

    @Test
    fun writesWhatDesktopWritesExceptTheCompressedBytes() {
        val desktop = res("dtpreset/desktop-export.dtpreset").decodeToString()
        val text = res("dtpreset/desktop-export.layout.txt").decodeToString()
        val ours = DtPreset.writeLayout("probe layout", text).decodeToString()
        val re = Regex("<op_params>[^<]*</op_params>")
        assertEquals(desktop.replace(re, "<op_params/>"), ours.replace(re, "<op_params/>"))
        assertTrue(re.find(ours)!!.value.startsWith("<op_params>gz"))
        assertEquals("probe layout" to text, DtPreset.readLayout(ours.encodeToByteArray()))
    }

    @Test
    fun shortLayoutsAreHexAndSpecialCharactersSurvive() {
        val l = "1|0ꬹ0||ꬹa<b>&\"c|basic||exposure"
        assertTrue(l.encodeToByteArray().size <= 100)
        val f = DtPreset.writeLayout("<my & layout>", l)
        assertTrue(f.decodeToString().contains("<op_params>${l.encodeToByteArray().joinToString("") { "%02x".format(it) }}</op_params>"))
        assertEquals("<my & layout>" to l, DtPreset.readLayout(f))
        assertEquals("modulegroups__my & layout_.dtpreset", DtPreset.fileName("<my & layout>"))
    }

    @Test
    fun refusesWhatIsNotALayout() {
        val good = res("dtpreset/desktop-export.dtpreset").decodeToString()
        val cases = mapOf(
            "not xml" to "hello",
            "other root" to "<?xml version=\"1.0\"?><x/>",
            "other module" to good.replace("<operation>modulegroups</operation>", "<operation>exposure</operation>"),
            "newer version" to good.replace("<op_version>1</op_version>", "<op_version>2</op_version>"),
            "damaged gz" to good.replace("<op_params>gz02eJ", "<op_params>gz02xx"),
            "bad hex" to good.replace(Regex("<op_params>[^<]*</op_params>"), "<op_params>zz</op_params>"),
            "truncated" to good.substring(0, good.length / 2),
            "doctype" to "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><darktable_preset><preset><name>&e;</name></preset></darktable_preset>",
        )
        for ((what, text) in cases) {
            try {
                DtPreset.readLayout(text.encodeToByteArray())
                fail("$what was accepted")
            } catch (e: DtPreset.DtPresetException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
        // a blob with a terminating zero (some writers add one) reads as the string before it
        val withZero = good.replace(Regex("<op_params>[^<]*</op_params>"),
            "<op_params>${DtPreset.encode("1|0ꬹ0||ꬹg|basic||exposure\u0000".encodeToByteArray())}</op_params>")
        assertEquals("1|0ꬹ0||ꬹg|basic||exposure", DtPreset.readLayout(withZero.encodeToByteArray()).second)
    }

    @Test
    fun appExportForDesktopImport() {
        // a file for tools/layouts/verify-with-darktable.sh to import into a real darktable
        val text = res("dtpreset/desktop-export.layout.txt").decodeToString()
        val out = File(System.getProperty("dta.reportDir") ?: "build/reports/p2d").apply { mkdirs() }
        File(out, "app-export.dtpreset").writeBytes(DtPreset.writeLayout("app export", text))
        File(out, "app-export-short.dtpreset").writeBytes(DtPreset.writeLayout("app export short", "1|0ꬹ0||ꬹg|basic||exposure"))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.SpPage
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.StyleMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief P3b on the emulator, with the real engine and the public test RAW. Results go to files/p2b-test/p3b-results.txt;
 * the XMPs and the app's exports as p3b-append.xmp/.jpg and p3b-overwrite.xmp/.jpg, which the workflow renders with
 * darktable-cli and compares (size and PSNR, as every comparison of the app's XMPs).
 *
 * s1: how long the engine's styles list takes (darktable's 534 shipped styles and whatever earlier tests left), the
 * first time and again, and through the darkroom's cache.
 * s2: a public .dtstyle (darktable's own example "day for twilight", data/styles, GPL-3.0, renamed by the test) and a
 * small pack made of darktable's example styles, one with a module no darktable knows, imported through the app's
 * import (one call, one result line per file); applied in append mode (one undo step back, checked), XMP written and
 * exported; the photo reopened without its XMP, applied in overwrite mode, XMP written and exported.
 * s3: a preset stored from exposure and applied to a second exposure instance: one history item, the same parameters.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3bScenarioTest {
    private val res = "p3b-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        runCatching { AppHost.controller.setOverlay(null) }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(10)
        }
    }

    private fun open(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3b-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        if (scenario == null) {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        }
        val c = AppHost.controller
        c.start(Series("p3b $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        c to item
    }

    /** darktable's shipped example styles, as the engine unpacked them (share/darktable/styles). */
    private fun shipped(file: String): String =
        File(TestFiles.ctx.filesDir, "engine/share/darktable/styles/$file").readText()

    private fun renamed(text: String, name: String) = text.replace(Regex("<name>.*?</name>"), "<name>$name</name>")

    /** Export the XMP at [xmp] of [item] at full size (JPEG 95), as the other comparisons. */
    private suspend fun export(c: DarkroomController, item: SeriesItem, xmp: File, jpg: File) {
        jpg.delete()
        c.withEngine { e ->
            runCatching { e.close() }
            e.open(item.rawPath, xmp.path)
            try { e.export(jpg.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
        }
    }

    @Test
    fun s1_stylesListTime() = runBlocking<Unit> {
        val e = TestFiles.engine()
        val times = ArrayList<Long>()
        var n = 0
        repeat(3) {
            val t0 = System.nanoTime()
            n = e.styles().size
            times += (System.nanoTime() - t0) / 1_000_000
        }
        TestFiles.record(res, "s1_styles_list", "$n styles; engine styles() ${times.joinToString(" / ")} ms (emulator, x86_64)")
        assertTrue("darktable's shipped styles are there: $n", n >= 534)
        val (c, _) = open("s1")
        val t0 = System.nanoTime()
        c.stylesPresets.loadStyles(force = true)
        val viaDarkroom = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        c.stylesPresets.loadStyles()
        val cached = (System.nanoTime() - t1) / 1_000
        TestFiles.record(res, "s1_darkroom", "read through the darkroom ${viaDarkroom} ms (styles() itself ${c.stylesPresets.state.value.listMs} ms); " +
            "opening the list again from the cache ${cached} µs")
        // the sheet opens on the cache: the list is there at once
        c.setOverlay(Overlay.Styles)
        Thread.sleep(500)
        assertNotNull(c.stylesPresets.state.value.styles)
        TestFiles.record(res, "s1", "passed")
    }

    @Test
    fun s2_importApplyBothModesWriteXmp() = runBlocking<Unit> {
        val (c, item) = open("s2")
        val sp = c.stylesPresets
        val twilight = renamed(shipped("examples_effects_day for twilight.dtstyle"), "p3b test|day for twilight")
        val pack = listOf("examples_colors_sepia.dtstyle", "examples_colors_faded.dtstyle", "examples_effects_fog.dtstyle")
        val files = ArrayList<Pair<String, ByteArray>>()
        files += "day for twilight.dtstyle" to twilight.toByteArray()
        for (f in pack) files += f to renamed(shipped(f), "p3b test|pack|" + f.removeSuffix(".dtstyle")).toByteArray()
        // a copy whose module no darktable knows: imported (as desktop does) but refused at apply
        files += "unknown module.dtstyle" to renamed(twilight.replace("<operation>lowlight</operation>", "<operation>p3bnosuchmodule</operation>"),
            "p3b test|unknown").toByteArray()
        files += "notes.txt" to "not a style".toByteArray()
        // earlier runs of this test on the same device: replace without asking
        for (n in listOf("p3b test|day for twilight", "p3b test|unknown") + pack.map { "p3b test|pack|" + it.removeSuffix(".dtstyle") })
            runCatching { c.withEngine { e -> e.styleDelete(n) } }
        val t0 = System.nanoTime()
        val lines = sp.import(files, TestFiles.ctx.cacheDir)
        val importMs = (System.nanoTime() - t0) / 1_000_000
        for (l in lines) TestFiles.record(res, "s2_import_line", "${l.file}: ${l.text}")
        TestFiles.record(res, "s2_import_ms", importMs)
        assertEquals(files.size, lines.size)
        assertTrue(lines.take(4).all { it.imported })
        assertTrue(lines[4].text, lines[4].imported && lines[4].text.contains("cannot be applied here"))
        assertFalse(lines[5].imported)
        assertTrue(sp.state.value.page is SpPage.ImportResult)
        val st = sp.style("p3b test|day for twilight")!!
        TestFiles.record(res, "s2_style", "${st.items.size} items: ${st.items.joinToString { it.op }}")
        assertTrue(st.usable)
        // refused
        val h0 = c.state.value.history
        assertFalse(sp.applyStyle("p3b test|unknown", StyleMode.APPEND))
        assertEquals(h0, c.state.value.history)
        // append, one undo step back, append again, written
        assertTrue(sp.applyStyle(st.name, StyleMode.APPEND))
        waitFor("history") { c.state.value.history.items.size == h0.items.size + st.items.size }
        c.undo()
        TestFiles.record(res, "s2_undo", "history ${h0.items.size} -> ${h0.items.size + st.items.size} -> ${c.state.value.history.items.size} after one undo")
        assertEquals(h0, c.state.value.history)
        assertTrue(sp.applyStyle(st.name, StyleMode.APPEND))
        c.save()
        val xa = File(TestFiles.out, "p3b-append.xmp")
        File(item.xmpPath).copyTo(xa, overwrite = true)
        assertTrue(xa.readText().contains("lowlight"))
        // overwrite on the edit with a hand change (a module switched on that is in neither the default edit nor the
        // style): the change goes, darktable's default edit and the style stay; one undo step brings it back
        val extra = c.state.value.modules.first { m -> m.canSwitch && !m.enabled && !m.hidden && !m.deprecated &&
            c.state.value.history.items.none { it.op == m.op } && st.items.none { it.op == m.op } }
        c.setEnabled(InstanceKey(extra.op, extra.priority), true)
        val n1 = c.state.value.history.items.size
        assertTrue(c.state.value.history.items.any { it.op == extra.op })
        assertTrue(sp.applyStyle(st.name, StyleMode.OVERWRITE))
        val h2 = c.state.value.history
        TestFiles.record(res, "s2_overwrite", "hand change ${extra.op}; history $n1 -> ${h2.items.size}: ${h2.items.joinToString { it.op }}")
        assertTrue("the hand change is gone", h2.items.none { it.op == extra.op })
        assertTrue("the style is there", h2.items.takeLast(st.items.size).map { it.op }.toSet() == st.items.map { it.op }.toSet())
        c.undo()
        assertTrue("one undo step back", c.state.value.history.items.any { it.op == extra.op })
        assertTrue(sp.applyStyle(st.name, StyleMode.OVERWRITE))
        assertTrue(c.state.value.history.items.none { it.op == extra.op })
        c.save()
        val xo = File(TestFiles.out, "p3b-overwrite.xmp")
        File(item.xmpPath).copyTo(xo, overwrite = true)
        c.closePhoto()
        export(c, item, xa, File(TestFiles.out, "p3b-append.jpg"))
        export(c, item, xo, File(TestFiles.out, "p3b-overwrite.jpg"))
        assertTrue(File(TestFiles.out, "p3b-append.jpg").length() > 0 && File(TestFiles.out, "p3b-overwrite.jpg").length() > 0)
        TestFiles.record(res, "s2", "passed")
    }

    @Test
    fun s3_presetOnASecondInstance() = runBlocking<Unit> {
        val (c, _) = open("s3")
        val sp = c.stylesPresets
        val exposure = InstanceKey("exposure", 0)
        c.setParam(exposure, "compensate_exposure_bias", JsonPrimitive(true))
        sp.loadPresets(exposure)
        runCatching { c.withEngine { e -> e.presetDelete("exposure", "p3b test preset") } }
        sp.loadPresets(exposure)
        assertTrue(sp.storePreset(exposure, "p3b test preset", "made by the emulator test"))
        val p = sp.state.value.presets.first { it.name == "p3b test preset" }
        assertEquals(true, p.active)
        val params0 = c.state.value.modules.first { it.op == "exposure" && it.priority == 0 }.params
        c.instanceNew(exposure)
        val second = c.state.value.modules.first { it.op == "exposure" && it.priority != 0 }.let { InstanceKey(it.op, it.priority) }
        val n = c.state.value.history.items.size
        assertTrue(sp.applyPreset(second, "p3b test preset"))
        val m2 = c.state.value.modules.first { it.op == second.op && it.priority == second.priority }
        TestFiles.record(res, "s3", "second instance ${second.priority}: history $n -> ${c.state.value.history.items.size}, same parameters ${m2.params == params0}")
        assertEquals("one history item", n + 1, c.state.value.history.items.size)
        assertEquals(params0, m2.params)
        assertEquals(true, sp.state.value.presets.first { it.name == "p3b test preset" }.active)
        sp.deletePreset(exposure, "p3b test preset")
        assertTrue(sp.state.value.presets.none { it.name == "p3b test preset" })
        TestFiles.record(res, "s3_passed", "true")
    }

    /**
     * s4 (deliverable 6, P2j section 6): framing's "image" and "constant border" and lens' "use latest algorithm"
     * through the darkroom on the real engine (module actions borders set_aspect and lens use_latest_md).
     */
    @Test
    fun s4_framingAndLensValuesOutsideTheLimits() = runBlocking<Unit> {
        val (c, _) = open("s4")
        val borders = InstanceKey("borders", 0)
        val list = AppHost.schema.modules.getValue("borders").computed.getValue(2) as app.dtandroid.schema.ComputedList
        fun aspect() = (c.state.value.modules.first { it.op == "borders" && it.priority == 0 }.params["aspect"] as JsonPrimitive).content.toDouble()
        val entries = list.entries(c.state.value.modules.first { it.op == "borders" }.params)
        val n = c.state.value.history.items.size
        c.chooseComputed(borders, 2, entries.indexOfFirst { it.label == "image" })
        val a0 = aspect()
        c.chooseComputed(borders, 2, entries.indexOfFirst { it.label == "constant border" })
        val a1 = aspect()
        val lens = InstanceKey("lens", 0)
        c.toggleComputed(lens, 24, true)
        val md = c.state.value.modules.first { it.op == "lens" && it.priority == 0 }.params["md_version"]
        TestFiles.record(res, "s4", "framing aspect image $a0, constant border $a1, history $n -> ${c.state.value.history.items.size}; lens md_version $md; message ${c.state.value.message}")
        assertEquals(0.0, a0, 0.0)
        assertEquals(-1.0, a1, 0.0)
        assertEquals(n + 3, c.state.value.history.items.size)
        assertTrue("$md", md.toString().contains("VERSION_2") || md.toString() == "1")
        TestFiles.record(res, "s4_passed", "true")
    }
}

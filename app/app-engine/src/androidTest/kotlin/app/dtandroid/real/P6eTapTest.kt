// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.key
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import android.graphics.BitmapFactory
import app.dtandroid.darkroom.BORDERS_OP
import app.dtandroid.darkroom.Rgb
import app.dtandroid.darkroom.WATERMARK_OP
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.engine.api.paramAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File

/**
 * Brief P6e: taps on the real screens over the real engine, as P6dTapTest (module by its search field, real conditions):
 *  t1 framing: the border colour row opens the colour dialog, "#3366CC" typed in its hex field, OK: one history item,
 *     the three floats are 51/255, 102/255, 204/255, and the preview changes (the frame appears);
 *  t2 watermark: the marker row opens the list, a tap on "simple-text (svg)", then the bottom-right alignment cell:
 *     one history item each;
 *  t3 fonts: what the engine's fontconfig sees (list_fonts, with the count of fonts fontconfig's built-in
 *     configuration found), and a text watermark (simple-text.svg, DejaVu Sans) exported with and without the
 *     module: the pixels it changes are counted here; the XMPs and JPEGs (p6e-watermark*.xmp/.jpg) go to the
 *     compare job, which renders the same XMPs with darktable-cli on Linux.
 * Results go to files/p2b-test/p6e-results.txt; a missing node leaves the semantic tree in files/p2b-test/p6e-tree-<test>.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P6eTapTest {
    private val res = "p6e-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:Rule(order = 0) val guard = HangGuard.rule(12)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun dump(tag: String) {
        runCatching { File(TestFiles.out, "p6e-tree-$tag.txt").writeText(compose.onRoot().printToString()) }
    }

    private fun until(tag: String, what: String, ms: Long = 90_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!runCatching(cond).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) { dump(tag); throw AssertionError("timed out: $what") }
            compose.waitForIdle()
            Thread.sleep(50)
        }
    }

    private fun has(desc: String) = compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
    private fun hasTextNode(text: String) = compose.onAllNodes(hasText(text, substring = false)).fetchSemanticsNodes().isNotEmpty()

    private fun tapNode(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun tapDesc(tag: String, desc: String) {
        until(tag, "a node described \"$desc\"") { has(desc) }
        tapNode(compose.onAllNodesWithContentDescription(desc)[0])
    }

    private fun tapText(tag: String, text: String) {
        until(tag, "a node with the text \"$text\"") { hasTextNode(text) }
        tapNode(compose.onAllNodes(hasText(text, substring = false))[0])
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun mod(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }
    private fun floats(c: DarkroomController, key: InstanceKey, f: String) =
        (0..2).map { (mod(c, key).params.paramAt("$f[$it]") as? JsonPrimitive)?.doubleOrNull?.toFloat() }

    private fun openPhoto(name: String): DarkroomController = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p6e-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p6e $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        val end = System.currentTimeMillis() + 180_000
        while (c.state.value.preview == null) {
            if (System.currentTimeMillis() > end) throw AssertionError("no preview; message ${c.state.value.message}")
            Thread.sleep(20)
        }
        c
    }

    private fun hideKeyboard() {
        scenario?.onActivity {
            it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
        }
    }

    /** P6dTapTest's way: the "modules" tab, the search field, the keyboard closed, a tap on the module's header. */
    private fun openModule(tag: String, c: DarkroomController, name: String, key: InstanceKey) {
        until(tag, "the darkroom") { has("photo") }
        val modulesTab = hasText("modules") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        until(tag, "the modules tab") { compose.onAllNodes(modulesTab).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(modulesTab)[0].performClick()
        val end = System.currentTimeMillis() + 10_000
        while (!has("search modules") && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(50) }
        if (has("search modules")) {
            compose.onAllNodesWithContentDescription("search modules")[0].performTextReplacement(name)
            TestFiles.record(res, "${tag}_module", "found by search")
            val shown = System.currentTimeMillis() + 90_000
            while (!has(name) && System.currentTimeMillis() < shown) { hideKeyboard(); compose.waitForIdle(); Thread.sleep(200) }
            tapDesc(tag, name)
        } else {
            TestFiles.record(res, "${tag}_module", "no search field in this layout: controller.goToModule")
            c.goToModule(key)
        }
        until(tag, "$name expanded") { c.state.value.expanded == key }
    }

    @Test fun t1_framingBorderColourByHex() {
        val c = openPhoto("framing")
        val key = InstanceKey(BORDERS_OP, 0)
        openModule("t1", c, "framing", key)
        val n0 = items(c)
        val p0 = c.state.value.preview!!.image
        tapDesc("t1", "border color")
        until("t1", "the colour dialog") { has("OK colour") && has("hex colour") }
        compose.onAllNodesWithContentDescription("hex colour")[0].performTextReplacement("#3366CC")
        until("t1", "the dialog shows the typed colour") {
            compose.onAllNodesWithContentDescription("colour after")[0].fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.StateDescription) == "#3366CC"
        }
        hideKeyboard()
        assertTrue("nothing written before OK", items(c) == n0)
        tapDesc("t1", "OK colour")
        val want = Rgb.ofBytes(51, 102, 204).asFloats()
        until("t1", "border colour written") { floats(c, key, "color") == want && items(c) == n0 + 1 }
        assertTrue("the dialog is closed", c.state.value.overlay == null)
        until("t1", "the preview changes") {
            val p = c.state.value.preview?.image
            p != null && p !== p0 && (p.width != p0.width || p.height != p0.height || !p.rgba.contentEquals(p0.rgba))
        }
        val p = c.state.value.preview!!.image
        TestFiles.record(res, "t1", "items $n0 -> ${items(c)}; color ${floats(c, key, "color")}; preview ${p0.width}x${p0.height} -> ${p.width}x${p.height}; enabled ${mod(c, key).enabled}")
    }

    @Test fun t2_watermarkMarkerAndAlignmentByTap() {
        val c = openPhoto("watermark")
        val key = InstanceKey(WATERMARK_OP, 0)
        openModule("t2", c, "watermark", key)
        val n0 = items(c)
        val fw = c.framing
        TestFiles.record(res, "t2_before", "filename '${fw.text(mod(c, key), "filename")}', alignment ${fw.alignment(mod(c, key))}, items $n0")
        tapDesc("t2", "marker")
        until("t2", "the marker list") { hasTextNode("simple-text (svg)") }
        TestFiles.record(res, "t2_files", fw.lists.value.files.joinToString { it.file })
        tapText("t2", "simple-text (svg)")
        until("t2", "marker written") { fw.text(mod(c, key), "filename") == "simple-text.svg" && items(c) == n0 + 1 }
        tapDesc("t2", "alignment bottom right")
        until("t2", "alignment written") { fw.alignment(mod(c, key)) == 8 && items(c) == n0 + 2 }
        TestFiles.record(res, "t2", "items $n0 -> ${items(c)}; filename ${fw.text(mod(c, key), "filename")}, alignment ${fw.alignment(mod(c, key))}")
    }

    /** pixels whose largest channel difference is above 24 (JPEGs of the same size) */
    private fun changedPixels(a: File, b: File): Pair<Int, Int> {
        val x = BitmapFactory.decodeFile(a.path)
        val y = BitmapFactory.decodeFile(b.path)
        if (x.width != y.width || x.height != y.height) return -1 to x.width * x.height
        val pa = IntArray(x.width * x.height).also { x.getPixels(it, 0, x.width, 0, 0, x.width, x.height) }
        val pb = IntArray(y.width * y.height).also { y.getPixels(it, 0, y.width, 0, 0, y.width, y.height) }
        var n = 0
        for (i in pa.indices) {
            val d = maxOf(kotlin.math.abs((pa[i] shr 16 and 255) - (pb[i] shr 16 and 255)),
                kotlin.math.abs((pa[i] shr 8 and 255) - (pb[i] shr 8 and 255)), kotlin.math.abs((pa[i] and 255) - (pb[i] and 255)))
            if (d > 24) n++
        }
        return n to pa.size
    }

    @Test fun t3_fontsAndATextWatermarkRender() = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p6e-text").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        val c = AppHost.controller
        c.start(Series("p6e text", listOf(item)), 0, filmstrip = false, thumbnails = false)
        val key = InstanceKey(WATERMARK_OP, 0)
        val fonts: JsonObject
        try {
            fonts = c.withEngine { e -> e.moduleAction(WATERMARK_OP, 0, "list_fonts", JsonObject(emptyMap())) } as JsonObject
            c.framing.refresh(key).join()
            val faces = c.framing.lists.value.fonts
            TestFiles.record(res, "t3_fontconfig", "config ${fonts["config"]}, fonts found by the built-in configuration ${fonts["default_config_fonts"]}")
            TestFiles.record(res, "t3_families", "${faces.map { it.family }.distinct().size} families, ${faces.size} faces: ${faces.map { it.family }.distinct().joinToString()}")
            assertTrue("the font list is not empty", faces.isNotEmpty())
            assertTrue("DejaVu Sans is listed (the APK's font)", faces.any { it.font == "DejaVu Sans 10" || it.family == "DejaVu Sans" })
            c.setParam(key, "filename", JsonPrimitive("simple-text.svg"))
            c.setParam(key, "text", JsonPrimitive("P6e watermark 0123456789"))
            c.setParam(key, "font", JsonPrimitive("DejaVu Sans 10"))
            c.setParams(key, Rgb.ofBytes(255, 255, 0).channels.mapIndexed { i, v -> "color[$i]" to JsonPrimitive(v) }.toMap())
            c.setParam(key, "scale", JsonPrimitive(60.0))
            c.setParam(key, "alignment", JsonPrimitive(0))
            assertTrue("no error: ${c.state.value.message}", c.state.value.message == null)
            c.save()
        } finally {
            c.closePhoto()
        }
        val xmp = File(TestFiles.out, "p6e-watermark.xmp")
        File(item.xmpPath).copyTo(xmp, overwrite = true)
        // the same edit without the watermark: its <rdf:li> switched off (darktable:enabled="0")
        val off = File(TestFiles.out, "p6e-watermark-off.xmp")
        off.writeText(Regex("""(darktable:operation="watermark"\s+darktable:enabled=")1""").replace(xmp.readText(), "$10"))
        assertTrue("the off XMP differs", off.readText() != xmp.readText())
        val jpg = File(TestFiles.out, "p6e-watermark.jpg").apply { delete() }
        val jpgOff = File(TestFiles.out, "p6e-watermark-off.jpg").apply { delete() }
        c.withEngine { e ->
            for ((x, j) in listOf(xmp to jpg, off to jpgOff)) {
                runCatching { e.close() }
                e.open(raw.path, x.path)
                try { e.export(j.path, ExportOptions(quality = 95, profile = ExportProfile.IMAGE)) } finally { runCatching { e.close() } }
            }
        }
        val (n, total) = changedPixels(jpg, jpgOff)
        TestFiles.record(res, "t3_text_pixels", "$n of $total pixels changed by the text watermark (${"%.3f".format(100.0 * n / total)}%)")
        assertTrue("the text watermark draws pixels ($n)", n > 500)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
import app.dtandroid.darkroom.ui.lut3dPath
import app.dtandroid.lpl.LutImport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Base64
import java.util.zip.Inflater

/**
 * Brief P6b: taps on the real screens, as P6aTapTest (module found by its search field, the keyboard closed on the
 * 320 x 640 emulator screen). A small warm .cube made here is put into the app's LUT folder under film/; then
 *  t1 lut 3D: "choose…", the folder "film/", the file "warm.cube": the module's filepath is the relative path
 *     "film/warm.cube", one history item, the module on; the XMP written for the photo holds the
 *     relative path in lut 3D's parameters (decoded here from darktable's gz/hex encoding);
 *  t2 the same file removed from the phone: the row says "LUT file not on this phone: film/warm.cube".
 * Results go to files/p2b-test/p6b-results.txt; a missing node leaves the semantic tree in p6b-tree-<test>.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P6bTapTest {
    private val res = "p6b-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null
    private val key = InstanceKey("lut3d", 0)

    @get:Rule(order = 0) val guard = HangGuard.rule(10)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun dump(tag: String) {
        runCatching { File(TestFiles.out, "p6b-tree-$tag.txt").writeText(compose.onRoot().printToString()) }
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
    private fun hasTextNode(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

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
    private fun module(c: DarkroomController) = c.state.value.modules.first { it.key == key }

    /** warm, size 2, red fastest (iop/lut3d.c's .cube reader) */
    private fun warmCube(): ByteArray {
        val sb = StringBuilder("TITLE \"p6b warm\"\nLUT_3D_SIZE 2\n")
        for (b in 0..1) for (g in 0..1) for (r in 0..1)
            sb.append(String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f\n", if (r == 1) 1.0 else 0.06, if (g == 1) 0.96 else 0.03, if (b == 1) 0.85 else 0.0))
        return sb.toString().toByteArray()
    }

    private fun openPhoto(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p6b-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p6b $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        val end = System.currentTimeMillis() + 180_000
        while (c.state.value.preview == null) {
            if (System.currentTimeMillis() > end) throw AssertionError("no preview; message ${c.state.value.message}")
            Thread.sleep(20)
        }
        c to item
    }

    private fun openModule(tag: String, c: DarkroomController, name: String) {
        until(tag, "the darkroom") { has("photo") }
        val modulesTab = hasText("modules") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        until(tag, "the modules tab") { compose.onAllNodes(modulesTab).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(modulesTab)[0].performClick()
        val end = System.currentTimeMillis() + 10_000
        while (!has("search modules") && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(50) }
        if (has("search modules")) {
            compose.onAllNodesWithContentDescription("search modules")[0].performTextReplacement(name)
            val shown = System.currentTimeMillis() + 90_000
            var attempt = 0
            while (!has(name) && System.currentTimeMillis() < shown) {
                TestFiles.closeKeyboard(scenario, attempt++)
                compose.waitForIdle(); Thread.sleep(200)
            }
            if (!has(name)) TestFiles.record(res, "${tag}_module_wait", "no row after 90 s; keyboard shown ${TestFiles.imeShown()}, attempts $attempt; ${TestFiles.imeDiagnosis()}")
            tapDesc(tag, name)
        } else {
            TestFiles.record(res, "${tag}_module", "no search field in this layout: controller.goToModule")
            c.goToModule(key)
        }
        TestFiles.closeKeyboard(scenario, 0)
        until(tag, "$name expanded") { c.state.value.expanded == key }
    }

    /** lut 3D's parameters in an XMP darktable wrote: hex, or "gz" + two digits + base64 of zlib data (common/exif.cc). */
    private fun lutParams(xmp: String): ByteArray {
        val m = Regex("darktable:operation=\"lut3d\"[^>]*?darktable:params=\"([^\"]+)\"").find(xmp)
            ?: throw AssertionError("no lut3d entry in the XMP")
        val v = m.groupValues[1]
        if (!v.startsWith("gz")) return ByteArray(v.length / 2) { i -> v.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        val z = Base64.getDecoder().decode(v.substring(4))
        val inf = Inflater().apply { setInput(z) }
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    @Test fun t1_chooseALutFromTheFolder() {
        val folder = AppHost.agent.luts.folder
        folder.remove("film/warm.cube")
        val imp = folder.import("warm.cube", "film", { warmCube().inputStream() }, replace = true)
        assertTrue("imported: $imp", imp is LutImport.Added)
        val (c, item) = openPhoto("choose")
        openModule("t1", c, "LUT 3D")
        val n0 = items(c)
        tapText("t1", "choose…")
        tapText("t1", "film/")
        tapText("t1", "warm.cube")
        until("t1", "filepath set") { lut3dPath(module(c).params) == "film/warm.cube" && items(c) > n0 }
        assertTrue("lut 3D on", module(c).enabled)
        val last = c.state.value.history.items.last()
        TestFiles.record(res, "t1_history", "items $n0 -> ${items(c)}; last ${last}")
        assertEquals(n0 + 1, items(c))
        runBlocking { c.writeXmp(item.xmpPath) }
        val p = lutParams(File(item.xmpPath).readText())
        val path = String(p, 0, p.indexOf(0.toByte()).let { if (it < 0) p.size else it })
        TestFiles.record(res, "t1_xmp", "lut3d filepath in the XMP: '$path' (${p.size} bytes of parameters)")
        assertEquals("film/warm.cube", path)
        TestFiles.record(res, "t1", "passed")
    }

    @Test fun t2_aMissingFileSaysSo() {
        val folder = AppHost.agent.luts.folder
        folder.import("warm.cube", "film", { warmCube().inputStream() }, replace = true)
        val (c, _) = openPhoto("missing")
        openModule("t2", c, "LUT 3D")
        runBlocking { c.chooseLutFile(key, "film/warm.cube") }
        until("t2", "filepath set") { lut3dPath(module(c).params) == "film/warm.cube" }
        assertTrue(AppHost.agent.luts.remove("film/warm.cube"))
        until("t2", "the missing line") { hasTextNode("LUT file not on this phone: film/warm.cube") }
        TestFiles.record(res, "t2", "passed")
        folder.unmark("film/warm.cube")
    }
}

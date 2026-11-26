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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief P6d: taps on the real screens, as P5cTapTest and P6aTapTest (the module found by its search field, the keyboard
 * closed after typing: TestFiles.waitRowAndKeyboardGone), on the real engine's Lensfun database:
 *  t1 lens correction: the camera row opens the full-screen list, a search finds "Canon EOS 50D" and a tap chooses it;
 *     the lens row, a search, a tap on the "Canon EF-S 18-55mm f/3.5-5.6 IS"; the focal length row and a tap on
 *     35 in the list; the history gains one item each (parameters and history read from the engine);
 *  t2 "find lens" opens the short list for the file's lens, "show all lenses" the whole one.
 * Results go to files/p2b-test/p6d-results.txt; a missing node leaves the semantic tree in files/p2b-test/p6d-tree-<test>.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P6dTapTest {
    private val res = "p6d-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:Rule(order = 0) val guard = HangGuard.rule(10)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun dump(tag: String) {
        runCatching { File(TestFiles.out, "p6d-tree-$tag.txt").writeText(compose.onRoot().printToString()) }
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
    private fun params(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }.params

    private fun openPhoto(name: String): DarkroomController = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p6d-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p6d $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
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

    /** P5cTapTest's way: the "modules" tab, the search field, the keyboard closed, a tap on the module's header. */
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

    private fun text(c: DarkroomController, key: InstanceKey, name: String) = (params(c, key)[name] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    private fun number(c: DarkroomController, key: InstanceKey, name: String) = (params(c, key)[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: Double.NaN

    /** Type [query] into the picker's search field and wait for the real condition: [row] shown and the keyboard gone. */
    private fun search(tag: String, field: String, query: String, row: String) {
        until(tag, "the $field field") { has(field) }
        compose.onAllNodesWithContentDescription(field)[0].performTextReplacement(query)
        val why = TestFiles.waitRowAndKeyboardGone(scenario, { hasTextNode(row) }, { compose.waitForIdle() })
        if (why != null) { dump(tag); throw AssertionError("row \"$row\" not shown with the keyboard gone: $why") }
    }

    @Test fun t1_cameraLensAndFocalLengthByTap() {
        val c = openPhoto("choose")
        val key = InstanceKey("lens", 0)
        openModule("t1", c, "lens correction", key)
        val n0 = items(c)
        TestFiles.record(res, "t1_before", "camera '${text(c, key, "camera")}', lens '${text(c, key, "lens")}', focal ${number(c, key, "focal")}, history items $n0")

        // the camera: the row, the list, a search, a tap
        tapDesc("t1", "camera model")
        until("t1", "the camera list") { has("search cameras") && c.lens.ui.value.cameras.size > 500 }
        TestFiles.record(res, "t1_cameras", "${c.lens.ui.value.cameras.size} cameras in the list")
        search("t1", "search cameras", "50d", "Canon EOS 50D")
        tapText("t1", "Canon EOS 50D")
        until("t1", "camera written") { text(c, key, "camera") == "Canon EOS 50D" && items(c) == n0 + 1 }
        assertTrue("the picker is closed", c.state.value.overlay == null)
        assertTrue("has_been_set", params(c, key)["has_been_set"].toString() == "true")

        // the lens: only lenses that fit the camera
        tapDesc("t1", "lens model")
        until("t1", "the lens list") { has("search lenses") && c.lens.ui.value.lenses.size > 50 }
        TestFiles.record(res, "t1_lenses", "${c.lens.ui.value.lenses.size} lenses fit the camera")
        search("t1", "search lenses", "18-55mm f/3.5-5.6 is", "Canon EF-S 18-55mm f/3.5-5.6 IS")
        tapText("t1", "Canon EF-S 18-55mm f/3.5-5.6 IS")
        until("t1", "lens written") { text(c, key, "lens") == "Canon EF-S 18-55mm f/3.5-5.6 IS" && items(c) == n0 + 2 }

        // the focal length: the list of the lens's range, a tap on 35
        tapDesc("t1", "focal length")
        until("t1", "the focal length list") { hasTextNode("35") }
        tapText("t1", "35")
        until("t1", "focal length written") { number(c, key, "focal") == 35.0 && items(c) == n0 + 3 }
        val last = c.state.value.history.items.last()
        TestFiles.record(res, "t1_history", "items $n0 -> ${items(c)}; camera '${text(c, key, "camera")}', lens '${text(c, key, "lens")}', focal ${number(c, key, "focal")}; last $last")
        assertTrue("lens correction on", c.state.value.modules.first { it.key == key }.enabled)
        TestFiles.record(res, "t1", "passed")
    }

    @Test fun t2_findLensOpensTheShortListAndShowAllTheWholeOne() {
        val c = openPhoto("find")
        val key = InstanceKey("lens", 0)
        openModule("t2", c, "lens correction", key)
        val n0 = items(c)
        tapText("t2", "find lens")
        until("t2", "the short list") { has("search lenses") && c.lens.ui.value.found && !c.lens.ui.value.loading }
        val few = c.lens.ui.value.lenses.size
        TestFiles.record(res, "t2_found", "find lens: $few lenses")
        tapText("t2", "show all lenses")
        until("t2", "the whole list") { !c.lens.ui.value.found && !c.lens.ui.value.loading && c.lens.ui.value.lenses.size > few }
        TestFiles.record(res, "t2_all", "show all lenses: ${c.lens.ui.value.lenses.size} lenses")
        assertTrue("nothing was written by looking", items(c) == n0)
        TestFiles.record(res, "t2", "passed")
    }
}

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
 * Brief P6a item 3: taps on the real screens, as P5cTapTest: the module found by its search field (the keyboard closed
 * after typing, as P5c learned on the 320 x 640 emulator screen) and opened by a tap, then
 *  t1 rgb levels: a tap on "auto": the levels change (desktop's _auto_levels on the module's input), history grows;
 *  t2 AgX: a tap on the picker of "black relative exposure": desktop's first area is picked at once, the black
 *     relative exposure leaves its default, history grows.
 * The result is read from the engine (parameters, history), not from pixels. Results go to
 * files/p2b-test/p6a-results.txt; a missing node leaves the semantic tree in files/p2b-test/p6a-tree-<test>.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P6aTapTest {
    private val res = "p6a-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:Rule(order = 0) val guard = HangGuard.rule(10)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun dump(tag: String) {
        runCatching { File(TestFiles.out, "p6a-tree-$tag.txt").writeText(compose.onRoot().printToString()) }
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
        val dir = File(TestFiles.ctx.filesDir, "p6a-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p6a $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
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

    @Test fun t1_rgbLevelsAutoByTap() {
        val c = openPhoto("levels")
        val key = InstanceKey("rgblevels", 0)
        openModule("t1", c, "rgb levels", key)
        val n0 = items(c)
        val p0 = params(c, key).toString()
        tapText("t1", "auto")
        until("t1", "levels changed by auto") { params(c, key).toString() != p0 && items(c) > n0 }
        TestFiles.record(res, "t1_levels", "history items $n0 -> ${items(c)}; levels ${params(c, key)["levels"]}")
        assertTrue("rgb levels on", c.state.value.modules.first { it.key == key }.enabled)
        TestFiles.record(res, "t1", "passed")
    }

    @Test fun t2_agxBlackExposurePickerByTap() {
        val c = openPhoto("agx")
        val key = InstanceKey("agx", 0)
        openModule("t2", c, "AgX", key)
        val n0 = items(c)
        val b0 = params(c, key)["range_black_relative_ev"].toString()
        tapDesc("t2", "colour picker: black relative exposure from an area")
        until("t2", "armed") { c.pickerArmed(c.state.value, key, 4) }
        until("t2", "black relative exposure picked") { params(c, key)["range_black_relative_ev"].toString() != b0 && items(c) > n0 }
        TestFiles.record(res, "t2_black", "range_black_relative_ev $b0 -> ${params(c, key)["range_black_relative_ev"]}, " +
            "pivot ${params(c, key)["curve_pivot_x"]}, history items $n0 -> ${items(c)}")
        TestFiles.record(res, "t2", "passed")
    }
}

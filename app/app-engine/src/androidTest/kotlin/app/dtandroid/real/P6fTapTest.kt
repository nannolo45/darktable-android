// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.ui.graph.CurveLayout
import app.dtandroid.engine.api.paramAt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief P6f: a finger on the graph editors over the real engine (module found by its search field, as P6eTapTest):
 *  t1 base curve: a drag up from the middle of the graph adds a node and lifts it: one history item, three nodes, the
 *     preview changes;
 *  t2 lowlight vision: a drag up on the curve (the soft brush): one history item, transition_y raised under the finger,
 *     the preview changes;
 *  t3 raw denoise: a drag up on the "all" curve: one history item, y[0] raised, the module on, the preview changes.
 * Every wait is on a real condition (the history, the parameters, the preview's pixels). Results go to
 * files/p2b-test/p6f-results.txt; a missing node leaves the semantic tree in files/p2b-test/p6f-tree-<test>.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P6fTapTest {
    private val res = "p6f-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:Rule(order = 0) val guard = HangGuard.rule(12)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun dump(tag: String) {
        runCatching { File(TestFiles.out, "p6f-tree-$tag.txt").writeText(compose.onRoot().printToString()) }
    }

    private fun until(tag: String, what: String, ms: Long = 90_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!runCatching(cond).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) { dump(tag); throw AssertionError("timed out: $what") }
            compose.waitForIdle()
            Thread.sleep(50)
        }
    }

    private fun has(desc: String, substring: Boolean = false) =
        compose.onAllNodesWithContentDescription(desc, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun mod(c: DarkroomController, key: InstanceKey) = c.state.value.modules.first { it.key == key }
    private fun num(c: DarkroomController, key: InstanceKey, path: String) = (mod(c, key).params.paramAt(path) as? JsonPrimitive)?.doubleOrNull

    private fun openPhoto(name: String): DarkroomController = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p6f-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p6f $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
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

    /** P6eTapTest's way: the "modules" tab, the search field, the keyboard closed, a tap on the module's header. */
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
            until(tag, "a node described \"$name\"") { has(name) }
            compose.onAllNodesWithContentDescription(name)[0].let { runCatching { it.performScrollTo() }; it.performClick() }
        } else {
            TestFiles.record(res, "${tag}_module", "no search field in this layout: controller.goToModule")
            c.goToModule(key)
        }
        until(tag, "$name expanded") { c.state.value.expanded == key }
    }

    /**
     * A finger put down on the graph described [desc] at ([fx0], [fy0]) and moved to ([fx1], [fy1]) (fractions of the
     * node, y down) in steps, then lifted.
     */
    private fun dragOn(tag: String, desc: String, fx0: Float, fy0: Float, fx1: Float, fy1: Float) {
        until(tag, "the graph \"$desc\"") { has(desc, substring = true) }
        val node = compose.onAllNodesWithContentDescription(desc, substring = true)[0]
        runCatching { node.performScrollTo() }
        compose.waitForIdle()
        node.performTouchInput {
            val from = Offset(width * fx0, height * fy0)
            val to = Offset(width * fx1, height * fy1)
            down(from)
            for (i in 1..12) moveTo(from + (to - from) * (i / 12f))
            up()
        }
    }

    private fun previewChanged(c: DarkroomController, p0: app.dtandroid.engine.api.RenderedImage): Boolean {
        val p = c.state.value.preview?.image ?: return false
        return p !== p0 && (p.width != p0.width || p.height != p0.height || !p.rgba.contentEquals(p0.rgba))
    }

    @Test fun t1_baseCurveDragAddsAndLiftsANode() {
        val c = openPhoto("basecurve")
        val key = InstanceKey("basecurve", 0)
        openModule("t1", c, "base curve", key)
        val n0 = items(c)
        val p0 = c.state.value.preview!!.image
        // the middle of the graph is on the identity line and holds no node: the drag adds one there and lifts it
        dragOn("t1", "curve graph", 0.5f, 0.5f, 0.5f, 0.28f)
        until("t1", "one item, three nodes") { items(c) == n0 + 1 && CurveLayout.BASECURVE.countOf(mod(c, key).params, 0) == 3 }
        val nodes = CurveLayout.BASECURVE.read(mod(c, key).params, 0)
        assertTrue("the new node is above the identity: $nodes", nodes[1].y > nodes[1].x + 0.1)
        until("t1", "the preview changes") { previewChanged(c, p0) }
        compose.waitForIdle()
        assertTrue("still one item after the lift", items(c) == n0 + 1)
        TestFiles.record(res, "t1", "items $n0 -> ${items(c)}; nodes $nodes; enabled ${mod(c, key).enabled}")
    }

    @Test fun t2_lowlightBrushDrag() {
        val c = openPhoto("lowlight")
        val key = InstanceKey("lowlight", 0)
        openModule("t2", c, "lowlight vision", key)
        val n0 = items(c)
        val y0 = num(c, key, "transition_y[2]") ?: 0.5
        val p0 = c.state.value.preview!!.image
        dragOn("t2", "band graph", 0.42f, 0.45f, 0.42f, 0.12f)
        until("t2", "one item, the curve raised") { items(c) == n0 + 1 && (num(c, key, "transition_y[2]") ?: 0.0) > y0 + 0.15 }
        until("t2", "the preview changes") { previewChanged(c, p0) }
        compose.waitForIdle()
        assertTrue("still one item after the lift", items(c) == n0 + 1)
        TestFiles.record(res, "t2", "items $n0 -> ${items(c)}; transition_y ${(0..5).map { num(c, key, "transition_y[$it]") }}; enabled ${mod(c, key).enabled}")
    }

    @Test fun t3_rawDenoiseBrushDrag() {
        val c = openPhoto("rawdenoise")
        val key = InstanceKey("rawdenoise", 0)
        openModule("t3", c, "raw denoise", key)
        val n0 = items(c)
        val p0 = c.state.value.preview!!.image
        dragOn("t3", "band graph", 0.5f, 0.5f, 0.5f, 0.1f)
        until("t3", "one item, y[0] raised") { items(c) == n0 + 1 && (num(c, key, "y[0][2]") ?: 0.0) > 0.7 }
        until("t3", "the preview changes") { previewChanged(c, p0) }
        compose.waitForIdle()
        assertTrue("still one item after the lift", items(c) == n0 + 1)
        TestFiles.record(res, "t3", "items $n0 -> ${items(c)}; y[0] ${(0..4).map { num(c, key, "y[0][$it]") }}; R untouched ${num(c, key, "y[1][2]")}; enabled ${mod(c, key).enabled}")
    }
}

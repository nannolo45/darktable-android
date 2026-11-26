// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.ASHIFT_OP
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.LUT_OP
import app.dtandroid.darkroom.LutPatches
import app.dtandroid.darkroom.RETOUCH_OP
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.key
import app.dtandroid.lpl.SharedHash
import app.dtandroid.lpl.SharedKey
import app.dtandroid.lpl.StubLpl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Brief P5c item 5: taps on the real screens, for dialogs that had controller-level tests only (Robolectric and the
 * P3l, P3m and P3n scenarios call the controller). Here the finger's path is used: Compose's test library finds the
 * nodes by their text and descriptions and injects touch events; the result is read from the engine (styles, history,
 * parameters, the saved XMP), not from pixels. Setup (the stub server's items, opening the photo) is code, the action
 * is taps. When a node is missing, the semantic tree is kept in files/p2b-test/p5c-tree-<test>.txt.
 *  t1 R11 (P3l): the library's banner, Review, Update: the style a PC shared is in the engine's styles;
 *  t2 retouch (P3m): the module found by search and opened by a tap, "add circle", a tap on the photo: one heal circle,
 *     one history item, retouch in the saved XMP;
 *  t3 colour look up table (P3m): long press on the patches, "delete": 23 patches, one history item;
 *  t4 perspective guides (P3n): "draw lines", three lines dragged on the photo, "fit vertical": one history item.
 * Results go to files/p2b-test/p5c-results.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P5cTapTest {
    private val res = "p5c-results.txt"
    private var scenario: ActivityScenario<MainActivity>? = null
    private var stub: StubLpl? = null

    @get:Rule(order = 0) val guard = HangGuard.rule(10)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
        stub?.let { s -> runCatching { AppHost.servers.remove(s.origin) }; s.close() }
    }

    // ---- helpers

    private fun dump(tag: String) {
        runCatching { File(TestFiles.out, "p5c-tree-$tag.txt").writeText(compose.onRoot().printToString()) }
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

    /** The node is brought into view first where a scrolling list holds it (the phone's panel is small), then tapped. */
    private fun tapNode(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun tapDesc(tag: String, desc: String) {
        until(tag, "a node described \"$desc\"") { has(desc) }
        tapNode(compose.onAllNodesWithContentDescription(desc)[0])
    }

    private fun tapText(tag: String, text: String, substring: Boolean = false) {
        until(tag, "a node with the text \"$text\"") { hasTextNode(text, substring) }
        tapNode(compose.onAllNodes(hasText(text, substring = substring))[0])
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size

    /** The photo in the darkroom (code, as the other scenario tests do); the module work after it is by taps. */
    private fun openPhoto(name: String): Pair<DarkroomController, SeriesItem> = runBlocking {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p5c-$name").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.start(Series("p5c $name", listOf(item)), 0, filmstrip = false, thumbnails = false)
        val end = System.currentTimeMillis() + 180_000
        while (c.state.value.preview == null) {
            if (System.currentTimeMillis() > end) throw AssertionError("no preview; message ${c.state.value.message}")
            Thread.sleep(20)
        }
        c to item
    }

    /**
     * The "modules" tab (the darkroom keeps the tab the last photo left, here and in the other scenarios), the module
     * list's search field and a tap on the module's header; where the layout has no search field, the controller's own jump.
     */
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
            // The search field takes the focus and the keyboard comes up; on the emulator's 320 x 640 screen the panel
            // then has no height and the list composes no row (run 45713442504: search text right, no module row).
            // The keyboard is closed, as a user does before tapping the result.
            // waits for the real condition: the module's row exists, which needs the keyboard gone (TestFiles.closeKeyboard)
            val shown = System.currentTimeMillis() + 90_000
            var attempt = 0
            while (!has(name) && System.currentTimeMillis() < shown) {
                TestFiles.closeKeyboard(scenario, attempt++)
                compose.waitForIdle(); Thread.sleep(200)
            }
            if (!has(name)) TestFiles.record(res, "${tag}_module_wait", "no row after 90 s; keyboard shown ${TestFiles.imeShown()}, attempts $attempt; ${TestFiles.imeDiagnosis()}")
            else if (attempt > 0) TestFiles.record(res, "${tag}_module_wait", "row appeared after $attempt keyboard closing attempts")
            tapDesc(tag, name)
        } else {
            TestFiles.record(res, "${tag}_module", "no search field in this layout: controller.goToModule")
            c.goToModule(key)
        }
    }

    private fun photoSwipe(tag: String, x0: Float, y0: Float, x1: Float, y1: Float) {
        until(tag, "the photo") { has("photo") }
        compose.onAllNodesWithContentDescription("photo")[0].performTouchInput {
            swipe(Offset(width * x0, height * y0), Offset(width * x1, height * y1), 500)
        }
    }

    // ---- t1: R11 (P3l)

    private fun style(name: String, exposureParams: String) = buildJsonObject {
        put("kind", "style")
        putJsonObject("style") { put("name", name); put("description", "shared by the P5c test"); put("iop_list", JsonNull) }
        putJsonArray("items") {
            add(buildJsonObject {
                put("num", 0); put("module", 1); put("operation", "exposure"); put("op_params", exposureParams); put("enabled", 1)
                put("blendop_params", JsonNull); put("blendop_version", 14); put("multi_priority", 0); put("multi_name", "")
                put("multi_name_hand_edited", 0)
            })
        }
    }

    @Test fun t1_sharedStyleTakenInByReviewAndUpdate() {
        val e = TestFiles.engine()
        val s = StubLpl().also { it.r11 = true; it.agentNames["pc-1"] = "Desktop" }
        stub = s
        AppHost.servers.put(s.origin, "Phone", null)
        val agent = AppHost.agent
        val name = "p5c tapped style"
        runBlocking {
            agent.heartbeat(); agent.heartbeat()
            val body = style(name, "0000003f")
            val k = SharedKey.of(body)!!
            val r = s.shared.put("pc-1", buildJsonObject {
                put("kind", k.kind.json); put("operation", k.operation); put("op_version", k.opVersion); put("name", k.name)
                put("hash", SharedHash.hash(body)!!); put("changed_at", System.currentTimeMillis() / 1000.0); put("base_rev", 0); put("body", body)
            })
            assertEquals("PC put: ${r.second}", 200, r.first)
            agent.shared.pass(s.origin, throwing = true)
        }
        assertTrue("the style is not on the phone yet", runBlocking { e.styles() }.none { it.name == name })
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.onActivity { it.vm.show(Screen.LIBRARY) }
        // the banner of the photo list, its Review button, the sheet's Update button
        tapText("t1", "Review")
        tapText("t1", "Update ", substring = true)
        var vm: AppViewModel? = null
        scenario!!.onActivity { vm = it.vm }
        until("t1", "the sheet closed after the update") { vm!!.sharedReview.value == null }
        val styles = runBlocking { e.styles() }.map { it.name }
        TestFiles.record(res, "t1_styles", styles.filter { it.startsWith("p5c") })
        assertTrue("the shared style is in the engine: $styles", name in styles)
        runCatching { runBlocking { e.styleDelete(name) } }
        TestFiles.record(res, "t1", "passed")
    }

    // ---- t2: retouch (P3m)

    @Test fun t2_healCircleOnThePhotoByTaps() {
        val (c, item) = openPhoto("heal")
        val key = InstanceKey(RETOUCH_OP, 0)
        openModule("t2", c, "retouch", key)
        until("t2", "retouch's panel (tools)") { has("add circle") }
        val n0 = items(c)
        tapDesc("t2", "add circle")
        until("t2", "the circle tool on") { c.retouch.state.value.tool != null }
        until("t2", "the photo") { has("photo") }
        compose.onAllNodesWithContentDescription("photo")[0].performTouchInput { click(Offset(width * 0.45f, height * 0.5f)) }
        until("t2", "a heal circle placed") { c.retouch.state.value.st?.shapes?.size == 1 }
        val sh = c.retouch.state.value.st!!.shapes[0]
        TestFiles.record(res, "t2_shape", "${sh.type}/${sh.algorithm}, history items ${n0} -> ${items(c)}")
        assertEquals("heal", sh.algorithm)
        until("t2", "one history item") { items(c) == n0 + 1 }
        runBlocking { c.save() }
        val xmp = File(item.xmpPath).readText()
        assertTrue("retouch is in the saved XMP", xmp.contains("darktable:operation=\"retouch\""))
        TestFiles.record(res, "t2", "passed")
    }

    // ---- t3: colour look up table (P3m)

    @Test fun t3_lookUpTablePatchDeletedByLongPressAndMenu() {
        val (c, _) = openPhoto("lut")
        val key = InstanceKey(LUT_OP, 0)
        openModule("t3", c, "color look up table", key)
        until("t3", "the patch grid") { has("patches") }
        fun n() = LutPatches.of(c.state.value.modules.first { it.key == key }.params)!!.n
        assertEquals(24, n())
        val n0 = items(c)
        val grid = compose.onAllNodesWithContentDescription("patches")[0]
        runCatching { grid.performScrollTo() }
        grid.performTouchInput { longClick(center) }
        tapText("t3", "delete")
        until("t3", "23 patches") { n() == 23 }
        until("t3", "one history item") { items(c) == n0 + 1 }
        TestFiles.record(res, "t3", "patches 24 -> ${n()}, history items $n0 -> ${items(c)}")
    }

    // ---- t4: perspective guides (P3n)

    @Test fun t4_drawnLinesAndFitByTaps() {
        val (c, _) = openPhoto("persp")
        val key = InstanceKey(ASHIFT_OP, 0)
        openModule("t4", c, "rotate and perspective", key)
        until("t4", "perspective focused") { c.perspective.state.value.st?.focused == true }
        tapDesc("t4", "draw lines")
        until("t4", "the drawn-lines structure") { c.perspective.state.value.st?.method == "drawn" }
        // three near-vertical lines, a little converging at the top
        photoSwipe("t4", 0.30f, 0.80f, 0.32f, 0.20f)
        photoSwipe("t4", 0.50f, 0.80f, 0.50f, 0.20f)
        photoSwipe("t4", 0.70f, 0.80f, 0.68f, 0.20f)
        val st = c.perspective.state.value.st!!
        TestFiles.record(res, "t4_lines", "lines ${st.lines.size}, vertical ${st.vertical}, horizontal ${st.horizontal}, message ${st.message}")
        until("t4", "three lines drawn (lines ${st.lines.size}, vertical ${st.vertical})") { (c.perspective.state.value.st?.lines?.size ?: 0) >= 3 }
        val n0 = items(c)
        tapDesc("t4", "fit vertical")
        until("t4", "the fit written as one history item (message ${c.perspective.state.value.st?.message})") { items(c) == n0 + 1 }
        val s = c.perspective.state.value.st!!
        TestFiles.record(res, "t4_fit", "rotation %.4f lensshift_v %.4f lensshift_h %.4f, items $n0 -> ${items(c)}".format(s.rotation, s.lensshiftV, s.lensshiftH))
        assertTrue("the fit changed the parameters", s.rotation != 0.0 || s.lensshiftV != 0.0)
    }
}

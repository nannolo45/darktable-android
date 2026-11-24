// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.lpl.SharedChoices
import app.dtandroid.lpl.SharedHash
import app.dtandroid.lpl.SharedKey
import app.dtandroid.lpl.SharedKind
import app.dtandroid.lpl.SharedLayout
import app.dtandroid.lpl.SharedRow
import app.dtandroid.lpl.StubLpl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Brief P3l on the emulator (API 33 x86_64) with the real engine and the stub server's R11 (written from PLAN.md 4.5):
 * l1: a style, a preset and a module layout shared by a PC, and a preset for a newer darktable, taken in with Update:
 *     the engine lists the style and the preset, the app has the layout (not made active), the newer one is reported
 *     unusable; nothing goes back (the bridge's hash of the stored rows equals the Kotlin hash the server checks: every
 *     upload of the phone's own items is accepted, none is a hash_mismatch); a style deleted on the phone is deleted on
 *     the server.
 * l2: a conflict resolved from the app's review sheet with "keep both" while the activity shows the list (banner):
 *     the phone keeps its own and receives "<name> (Desktop)".
 * Results go to files/p2b-test/p3l-results.txt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class P3lScenarioTest {
    private val res = "p3l-results.txt"
    private var stub: StubLpl? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:org.junit.Rule val guard = HangGuard.rule(10)

    @After fun tearDown() {
        scenario?.close()
        stub?.let { s -> runCatching { AppHost.servers.remove(s.origin) }; s.close() }
    }

    private fun waitFor(what: String, ms: Long = 120_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(20)
        }
    }

    private fun style(name: String, exposureParams: String) = buildJsonObject {
        put("kind", "style")
        putJsonObject("style") { put("name", name); put("description", "shared by the P3l test"); put("iop_list", JsonNull) }
        putJsonArray("items") {
            add(buildJsonObject {
                put("num", 0); put("module", 1); put("operation", "exposure"); put("op_params", exposureParams); put("enabled", 1)
                put("blendop_params", JsonNull); put("blendop_version", 14); put("multi_priority", 0); put("multi_name", "")
                put("multi_name_hand_edited", 0)
            })
        }
    }

    private fun preset(name: String, opv: Int) = buildJsonObject {
        put("kind", "preset")
        putJsonObject("row") {
            put("name", name); put("description", "é and ∞"); put("operation", "channelmixerrgb"); put("op_version", opv)
            put("op_params", "00000000cdcc4c3e0000803f"); put("enabled", 1); put("blendop_params", JsonNull); put("blendop_version", 14)
            put("multi_priority", 0); put("multi_name", ""); put("multi_name_hand_edited", 0); put("model", "%"); put("maker", "%"); put("lens", "%")
            put("iso_min", SharedHash.real(0.0)); put("iso_max", SharedHash.real(Float.MAX_VALUE.toDouble()))
            put("exposure_min", SharedHash.real(0.0)); put("exposure_max", SharedHash.real(1e8)); put("aperture_min", SharedHash.real(0.0))
            put("aperture_max", SharedHash.real(1e8)); put("focal_length_min", SharedHash.real(0.0)); put("focal_length_max", SharedHash.real(1000.0))
            put("autoapply", 0); put("filter", 0); put("def", 0); put("format", 0)
        }
    }

    private fun pcPut(s: StubLpl, body: JsonObject, baseRev: Int = 0) {
        val k = SharedKey.of(body)!!
        val r = s.shared.put("pc-1", buildJsonObject {
            put("kind", k.kind.json); put("operation", k.operation); put("op_version", k.opVersion); put("name", k.name)
            put("hash", SharedHash.hash(body)!!); put("changed_at", System.currentTimeMillis() / 1000.0); put("base_rev", baseRev); put("body", body)
        })
        assertEquals("PC put ${k.name}: ${r.second}", 200, r.first)
    }

    private fun newStub(): StubLpl {
        val s = StubLpl().also { it.r11 = true; it.agentNames["pc-1"] = "Desktop" }
        stub = s
        AppHost.servers.put(s.origin, "Phone", null)
        return s
    }

    private fun puts(s: StubLpl) = s.seen("PUT", "/api/shared").filter { !it.path.startsWith("/api/shared/state") }

    @Test
    fun l1_takeInFromAPcAndNothingGoesBack() = runBlocking<Unit> {
        val e = TestFiles.engine()
        val s = newStub()
        val agent = AppHost.agent
        // supervisor's question: which user presets (writeprotect 0) of processing modules a fresh library has
        val presets0 = AppHost.SharedItems.snapshot().filter { it.key.kind == SharedKind.PRESET }
        TestFiles.record(res, "l1_fresh_presets", presets0.joinToString(" | ") { "${it.key.operation} v${it.key.opVersion} '${it.key.name}' ${it.body["row"]}" })
        agent.heartbeat(); agent.heartbeat()
        assertTrue("shared in caps: ${s.heartbeats[AppHost.servers.installId]}", s.heartbeats[AppHost.servers.installId]!!.contains("\"shared\""))
        val layoutText = "1|0ꬹ1|||exposure/exposureꬹp3l group|basic||exposure|colorbalancergb"
        pcPut(s, style("p3l shared style", "0000003f"))
        pcPut(s, preset("p3l shared preset", 3))
        pcPut(s, SharedLayout.body("p3l shared layout", layoutText))
        pcPut(s, preset("p3l future preset", 999))
        val t0 = System.currentTimeMillis()
        agent.shared.pass(s.origin, throwing = true)
        val firstPass = System.currentTimeMillis() - t0
        val v = agent.shared.views.value[s.origin]!!
        TestFiles.record(res, "l1_waiting", v.rows.joinToString { "${it.meta.key.name}:${it.change}" })
        val own = puts(s)
        TestFiles.record(res, "l1_own_uploads", "${own.size} items of this phone sent in ${firstPass} ms; statuses ${own.map { it.status }.toSet()}")
        assertTrue("every upload of the phone's own items accepted (bridge hash = Kotlin hash): ${own.map { it.status }}", own.all { it.status == 200 })
        val names = setOf("p3l shared style", "p3l shared preset", "p3l shared layout", "p3l future preset")
        val ids = v.rows.filter { it.meta.key.name in names }.map { it.meta.id }.toSet()
        assertEquals(4, ids.size)
        val currentBefore = AppHost.controller.state.value.layoutName
        val summary = agent.shared.update(s.origin, SharedChoices(take = ids))
        TestFiles.record(res, "l1_update", summary)
        assertEquals("3 taken in · 1 need a newer darktable", summary)
        assertTrue(e.styles().any { it.name == "p3l shared style" })
        assertTrue(e.presets("channelmixerrgb", 0).any { it.name == "p3l shared preset" && !it.builtin })
        val mine = AppHost.SharedItems.snapshot()
        assertNotNull(mine.firstOrNull { it.key == SharedKey(SharedKind.LAYOUT, "modulegroups", 1, "p3l shared layout") })
        assertEquals("a received layout is not made active", currentBefore, AppHost.controller.state.value.layoutName)
        assertNull(mine.firstOrNull { it.key.name == "p3l future preset" })
        assertEquals(SharedRow.Change.UNUSABLE, agent.shared.views.value[s.origin]!!.rows.first { it.meta.key.name == "p3l future preset" }.change)
        // the bridge's hash of the stored rows is the hash they came with
        for (n in names - "p3l future preset") {
            val local = mine.first { it.key.name == n }
            assertEquals("hash of $n after the round trip through data.db", s.shared.itemNamed(n)!!.hash, local.hash)
        }
        val before = puts(s).size
        agent.shared.pass(s.origin, throwing = true)
        assertEquals("nothing goes back", before, puts(s).size)
        assertTrue(agent.shared.views.value[s.origin]!!.waiting.isEmpty())
        // deleted on the phone: deleted on the server
        e.styleDelete("p3l shared style")
        agent.shared.pass(s.origin, throwing = true)
        assertTrue(s.shared.itemNamed("p3l shared style")!!.deleted)
        TestFiles.record(res, "l1", "passed")
        // leave nothing behind for the next scenario
        runCatching { e.sharedDelete("preset", "channelmixerrgb", 3, "p3l shared preset") }
        runCatching { AppHost.SharedItems.delete(SharedKey(SharedKind.LAYOUT, "modulegroups", 1, "p3l shared layout")) }
    }

    @Test
    fun l2_conflictKeepBothFromTheReviewSheet() {
        val e = TestFiles.engine()
        val s = newStub()
        val agent = AppHost.agent
        runBlocking {
            agent.heartbeat(); agent.heartbeat()
            e.sharedApply(style("p3l both", "0000803f"))
            agent.shared.pass(s.origin, throwing = true)
            val item = s.shared.itemNamed("p3l both")!!
            pcPut(s, style("p3l both", "00000040"), item.rev)
            e.sharedApply(style("p3l both", "00004040"))
            agent.shared.pass(s.origin, throwing = true)
        }
        val row = agent.shared.views.value[s.origin]!!.rows.first { it.meta.key.name == "p3l both" }
        assertEquals(SharedRow.Change.CONFLICT, row.change)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        scenario!!.onActivity { a ->
            a.vm.show(Screen.LIBRARY)
            a.vm.openSharedReview(s.origin)
            a.vm.openSharedDialog(row)
        }
        Thread.sleep(1_000) // the list, its banner, the sheet and the dialog are drawn
        scenario!!.onActivity { a ->
            assertNotNull(a.vm.sharedReview.value?.dialog)
            a.vm.chooseConflict("both")
            assertEquals("both", a.vm.sharedReview.value!!.review.conflicts[row.meta.id])
            a.vm.runSharedUpdate()
        }
        var vm: AppViewModel? = null
        scenario!!.onActivity { vm = it.vm }
        waitFor("update done") { vm!!.sharedReview.value == null }
        runBlocking {
            val styles = e.styles().map { it.name }
            TestFiles.record(res, "l2_styles", styles.filter { it.startsWith("p3l both") })
            assertTrue(styles.contains("p3l both"))
            assertTrue("the other copy: $styles", styles.contains("p3l both (Desktop)"))
            assertEquals(SharedHash.hash(style("p3l both", "00004040")), s.shared.itemNamed("p3l both")!!.hash)
            assertFalse(agent.shared.views.value[s.origin]!!.waiting.any { it.meta.key.name.startsWith("p3l both") })
            runCatching { e.styleDelete("p3l both") }; runCatching { e.styleDelete("p3l both (Desktop)") }
        }
        TestFiles.record(res, "l2", "passed")
    }
}

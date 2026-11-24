// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * Brief P3l, interoperability: the client scenarios of deliverable 5 driven only over HTTP (the PC is played by an
 * agent "pc-1" that heartbeats and calls the R11 API like LPL's PC agent), so they run against the stub server (default,
 * CI) or against a real LightPhotoLibrary server: `gradle :lpl:test -Pdta.r11Url=http://127.0.0.1:18791 --tests
 * app.dtandroid.lpl.P3lInteropTest`. Item names carry a suffix per test, so a real server's earlier items do not count.
 * Every request of the phones and the PC is kept with its status for the report ([calls]).
 */
class P3lInteropTest {
    @get:Rule val tmp = TemporaryFolder()
    private val realUrl = System.getProperty("dta.r11Url")?.takeIf { it.isNotBlank() }
    private val stub = if (realUrl == null) StubLpl().also { it.r11 = true } else null
    private val o: ServerOrigin = stub?.origin ?: ServerOrigin.parseExact(realUrl!!)!!
    private val sfx = " ·" + java.lang.Long.toString(System.nanoTime() % 1_000_000_000L, 36)
    private var now = System.currentTimeMillis() / 1000.0
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun client(who: String) = LplClient(o, { null }, settings = ClientSettings(backoffMs = listOf(1, 1), connectTimeoutMs = 1_000, readTimeoutMs = 5_000),
        sleep = {}, log = { calls += "$who $it" })

    // ------------------------------------------------------------------ the PC, over HTTP
    private val pcId = "pc-interop-" + sfx.trim().removePrefix("·")
    private val pc = client("pc")

    private fun pcBeat() = pc.heartbeat(pcId, "Studio PC", AgentState(emptyList(), false, true, "test", ""), extraCaps = listOf("shared"))

    private fun pcMeta(name: String): SharedMeta? = pc.sharedList(pcId, 0).items.lastOrNull { it.key.name == name }

    private fun pcPut(body: JsonObject, baseRev: Int, at: Double = now): SharedWrite =
        pc.sharedPut(pcId, SharedLocal(SharedKey.of(body)!!, SharedHash.hash(body)!!, body), at, baseRev)

    private fun pcDelete(name: String, at: Double = now): SharedWrite {
        val m = pcMeta(name)!!
        return pc.sharedDelete(pcId, m.id, m.rev, at)
    }

    // ------------------------------------------------------------------ phones

    class MemPort : SharedPort {
        val items = LinkedHashMap<SharedKey, JsonObject>()
        override suspend fun snapshot() = items.map { (k, b) -> SharedLocal(k, SharedHash.hash(b)!!, b) }
        override suspend fun apply(body: JsonObject): Boolean {
            val k = SharedKey.of(body)!!
            if (k.opVersion > 50) return false
            items[k] = body
            return true
        }
        override suspend fun delete(key: SharedKey) { items.remove(key) }
        fun put(body: JsonObject) { items[SharedKey.of(body)!!] = body }
        fun hashOf(name: String) = items.entries.firstOrNull { it.key.name == name }?.let { SharedHash.hash(it.value) }
    }

    inner class Device(val name: String, policy: SharedPolicy = SharedPolicy.ASK) {
        val servers = ServerStore(File(tmp.root, "$name/lpl"), MemorySecretBox()).also { it.put(o, name, null) }
        val agent = LplAgent(servers, WorkStore(File(tmp.root, "$name/lpl/work")), FakeEditor(), { "test" },
            clientFor = { client(name) }, clock = { now },
            sharedSettings = SharedSettings(null).also { it.set(SharedSettings.Values(policy = policy)) })
        val port = MemPort().also { agent.shared.port = it }
        suspend fun start() { agent.heartbeat(); agent.heartbeat() }
        suspend fun pass() = agent.shared.pass(o, throwing = true)
        fun rows() = agent.shared.views.value[o]!!.rows.filter { it.meta.key.name.endsWith(sfx) }
        fun row(n: String) = rows().firstOrNull { it.meta.key.name == n + sfx }
        suspend fun update(c: SharedChoices) = agent.shared.update(o, c)
        fun sent(method: String) = calls.count { it.startsWith("$name $method /api/shared") && !it.contains("/state") && !it.contains("/resolve") }
    }

    private fun n(s: String) = s + sfx

    private fun preset(name: String, exposure: String = "0000803f", opv: Int = 4) = buildJsonObject {
        put("kind", "preset")
        putJsonObject("row") {
            put("name", n(name)); put("description", "interop é ∞"); put("operation", "exposure"); put("op_version", opv)
            put("op_params", exposure); put("enabled", 1); put("blendop_params", JsonNull); put("blendop_version", 14)
            put("multi_priority", 0); put("multi_name", ""); put("multi_name_hand_edited", 0); put("model", "%"); put("maker", "%"); put("lens", "%")
            put("iso_min", SharedHash.real(0.0)); put("iso_max", SharedHash.real(Float.MAX_VALUE.toDouble()))
            put("exposure_min", SharedHash.real(0.0)); put("exposure_max", SharedHash.real(1e8)); put("aperture_min", SharedHash.real(0.0))
            put("aperture_max", SharedHash.real(1e8)); put("focal_length_min", SharedHash.real(0.0)); put("focal_length_max", SharedHash.real(1000.0))
            put("autoapply", 0); put("filter", 0); put("def", 0); put("format", 0)
        }
    }

    private fun style(name: String, vararg ops: String) = buildJsonObject {
        put("kind", "style")
        putJsonObject("style") { put("name", n(name)); put("description", ""); put("iop_list", JsonNull) }
        putJsonArray("items") {
            ops.forEachIndexed { i, op ->
                add(buildJsonObject {
                    put("num", i); put("module", 1); put("operation", op); put("op_params", "00"); put("enabled", 1); put("blendop_params", JsonNull)
                    put("blendop_version", 14); put("multi_priority", 0); put("multi_name", ""); put("multi_name_hand_edited", 0)
                })
            }
        }
    }

    @After fun stop() {
        stub?.close()
        System.getProperty("dta.lplLog")?.let { f -> File(f).appendText(calls.joinToString("\n", postfix = "\n")) }
    }

    private fun setUpPc() { pcBeat(); pcBeat() }

    // ------------------------------------------------------------------ scenarios

    @Test fun i01_firstSyncIdenticalNoConflict() = runBlocking<Unit> {
        setUpPc()
        assertTrue(pcPut(preset("warm"), 0) is SharedWrite.Stored)
        assertTrue(pcPut(style("bw", "colorbalancergb", "sigmoid"), 0) is SharedWrite.Stored)
        val a = Device("Phone A").apply { port.put(preset("warm")); port.put(style("bw", "colorbalancergb", "sigmoid")); port.put(preset("mine only")) }
        a.start(); a.pass()
        assertEquals("only the new item is sent: $calls", 1, a.sent("PUT"))
        assertTrue("nothing waits: ${a.rows()}", a.rows().none { it.waits })
    }

    @Test fun i02_oneSideChangeTakenOnUpdate() = runBlocking<Unit> {
        setUpPc()
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        val b = Device("Phone B")
        a.start(); b.start(); a.pass(); b.pass()
        assertEquals(SharedRow.Change.NEW, b.row("warm")?.change)
        b.update(SharedChoices(take = setOf(b.row("warm")!!.meta.id)))
        a.port.put(preset("warm", exposure = "00000040")); now += 10
        a.pass()
        b.pass()
        val r = b.row("warm")!!
        assertEquals(SharedRow.Change.CHANGED, r.change)
        assertEquals("Phone A", r.meta.deviceName)
        // a real server also lists other tests' items: only the start of the summary is this test's
        assertTrue(b.update(SharedChoices(take = setOf(r.meta.id))).startsWith("1 taken in"))
        assertEquals(a.port.hashOf(n("warm")), b.port.hashOf(n("warm")))
        val before = b.sent("PUT")
        b.pass()
        assertEquals("B sends nothing back", before, b.sent("PUT"))
        assertTrue(b.rows().none { it.waits })
    }

    private suspend fun conflict(choice: String): Device {
        setUpPc()
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        a.start(); a.pass()
        val m = pcMeta(n("warm"))!!
        assertTrue(pcPut(preset("warm", exposure = "11111111"), m.rev) is SharedWrite.Stored)
        a.port.put(preset("warm", exposure = "22222222")); now += 5
        a.pass()
        assertEquals(SharedRow.Change.CONFLICT, a.row("warm")?.change)
        assertEquals("Studio PC", a.row("warm")!!.meta.deviceName)
        val puts = a.sent("PUT")
        a.pass()
        assertEquals("not uploaded again while in conflict", puts, a.sent("PUT"))
        a.update(SharedChoices(conflicts = mapOf(m.id to choice)))
        return a
    }

    @Test fun i03_conflictMine() = runBlocking<Unit> {
        val a = conflict("mine")
        assertEquals(SharedHash.hash(preset("warm", exposure = "22222222")), pcMeta(n("warm"))!!.hash)
        a.pass()
        assertTrue(a.rows().none { it.waits })
    }

    @Test fun i04_conflictTheirs() = runBlocking<Unit> {
        val a = conflict("theirs")
        assertEquals(SharedHash.hash(preset("warm", exposure = "11111111")), a.port.hashOf(n("warm")))
        val puts = a.sent("PUT")
        a.pass()
        assertTrue(a.rows().none { it.waits })
        assertEquals(puts, a.sent("PUT"))
    }

    @Test fun i05_conflictBoth() = runBlocking<Unit> {
        val a = conflict("both")
        assertEquals(SharedHash.hash(preset("warm", exposure = "22222222")), a.port.hashOf(n("warm")))
        val copy = a.port.items.keys.firstOrNull { it.name.startsWith(n("warm") + " (") }
        assertNotNull("the other copy arrived: ${a.port.items.keys}", copy)
        assertEquals("${n("warm")} (Studio PC)", copy!!.name)
        val puts = a.sent("PUT")
        a.pass()
        assertTrue("nothing waits: ${a.rows()}", a.rows().none { it.waits })
        assertEquals(puts, a.sent("PUT"))
    }

    @Test fun i06_newestWins() = runBlocking<Unit> {
        setUpPc()
        val a = Device("Phone A", SharedPolicy.NEWEST).apply { port.put(preset("warm")) }
        a.start(); a.pass()
        pcPut(preset("warm", exposure = "11111111"), pcMeta(n("warm"))!!.rev, at = now - 100)
        a.port.put(preset("warm", exposure = "22222222"))
        a.pass()
        assertEquals(SharedHash.hash(preset("warm", exposure = "22222222")), pcMeta(n("warm"))!!.hash)
        assertTrue(a.rows().none { it.waits })
        pcPut(preset("warm", exposure = "33333333"), pcMeta(n("warm"))!!.rev, at = now + 100)
        a.port.put(preset("warm", exposure = "44444444"))
        a.pass()
        assertEquals(SharedHash.hash(preset("warm", exposure = "33333333")), pcMeta(n("warm"))!!.hash)
        assertEquals(SharedRow.Change.CHANGED, a.row("warm")?.change)
        a.update(SharedChoices(take = setOf(a.row("warm")!!.meta.id)))
        assertEquals(SharedHash.hash(preset("warm", exposure = "33333333")), a.port.hashOf(n("warm")))
    }

    @Test fun i07_deletionDeleteAndKeep() = runBlocking<Unit> {
        setUpPc()
        val a = Device("Phone A").apply { port.put(preset("warm")); port.put(style("bw", "sigmoid")) }
        a.start(); a.pass()
        pcDelete(n("warm")); pcDelete(n("bw"))
        a.pass()
        assertEquals(SharedRow.Change.DELETED, a.row("warm")?.change)
        assertEquals(SharedRow.Change.DELETED, a.row("bw")?.change)
        val s = a.update(SharedChoices(deletions = mapOf(a.row("warm")!!.meta.id to true, a.row("bw")!!.meta.id to false)))
        assertTrue(s, s.startsWith("1 deleted here · 1 kept on this phone"))
        assertNull(a.port.hashOf(n("warm")))
        assertNotNull(a.port.hashOf(n("bw")))
        val sent = a.sent("PUT") + a.sent("DELETE")
        a.pass()
        assertTrue("not offered again: ${a.rows()}", a.rows().none { it.waits })
        assertEquals(sent, a.sent("PUT") + a.sent("DELETE"))
    }

    @Test fun i08_keptChangedAgainComesBack() = runBlocking<Unit> {
        setUpPc()
        val a = Device("Phone A").apply { port.put(style("bw", "sigmoid")) }
        a.start(); a.pass()
        pcDelete(n("bw"))
        a.pass()
        a.update(SharedChoices(deletions = mapOf(a.row("bw")!!.meta.id to false)))
        val delRev = pcMeta(n("bw"))!!.rev
        a.port.put(style("bw", "sigmoid", "colorbalancergb"))
        a.pass()
        val back = pcMeta(n("bw"))!!
        assertFalse(back.deleted)
        assertEquals(delRev + 1, back.rev)
        assertEquals(SharedHash.hash(style("bw", "sigmoid", "colorbalancergb")), back.hash)
    }

    @Test fun i09_rename() = runBlocking<Unit> {
        setUpPc()
        val a = Device("Phone A").apply { port.put(style("bw", "sigmoid")) }
        val b = Device("Phone B")
        a.start(); b.start(); a.pass(); b.pass()
        b.update(SharedChoices(take = setOf(b.row("bw")!!.meta.id)))
        a.port.items.remove(SharedKey.style(n("bw"))); a.port.put(style("bw toned", "sigmoid"))
        a.pass()
        assertTrue(pcMeta(n("bw"))!!.deleted)
        assertFalse(pcMeta(n("bw toned"))!!.deleted)
        b.pass()
        assertEquals(SharedRow.Change.DELETED, b.row("bw")?.change)
        assertEquals(SharedRow.Change.NEW, b.row("bw toned")?.change)
    }

    @Test fun i10_unusable() = runBlocking<Unit> {
        setUpPc()
        pcPut(preset("future", opv = 77), 0)
        val a = Device("Phone A")
        a.start(); a.pass()
        val r = a.row("future")!!
        a.update(SharedChoices(take = setOf(r.meta.id)))
        assertEquals(SharedRow.Change.UNUSABLE, a.row("future")?.change)
        a.pass()
        assertEquals(SharedRow.Change.UNUSABLE, a.row("future")?.change)
        assertEquals(0, a.sent("DELETE"))
    }
}

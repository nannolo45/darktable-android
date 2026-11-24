// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3l (R11, PLAN.md 4.5), the phone's client against the stub server's R11 ([StubShared], written from the
 * contract): the shared hash vectors, and the cases of deliverable 5 with two phones ("Phone A", "Phone B") running the
 * real client and a PC played through the stub's own calls.
 */
class P3lSharedTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl().also { it.r11 = true; it.agentNames["pc-1"] = "Studio PC" }
    private val o get() = stub.origin
    private var now = 1_000_000.0

    /** A device's shared items, as the engine and the layout store keep them. Versions above 50 need a "newer darktable". */
    class MemPort : SharedPort {
        val items = LinkedHashMap<SharedKey, JsonObject>()
        var applied = 0
        override suspend fun snapshot() = items.map { (k, b) -> SharedLocal(k, SharedHash.hash(b)!!, b) }
        override suspend fun apply(body: JsonObject): Boolean {
            val k = SharedKey.of(body)!!
            if (k.opVersion > 50) return false
            items[k] = body; applied++
            return true
        }
        override suspend fun delete(key: SharedKey) { items.remove(key) }
        fun put(body: JsonObject) { items[SharedKey.of(body)!!] = body }
        fun hashOf(name: String) = items.entries.firstOrNull { it.key.name == name }?.let { SharedHash.hash(it.value) }
    }

    inner class Device(name: String, val policy: SharedPolicy = SharedPolicy.ASK) {
        val servers = ServerStore(File(tmp.root, "$name/lpl"), MemorySecretBox()).also { it.put(o, name, null) }
        val settings = SyncSettings.inMemory()
        val agent = LplAgent(
            servers, WorkStore(File(tmp.root, "$name/lpl/work")), FakeEditor(), { "test" },
            clientFor = { origin -> LplClient(origin, { null }, settings = ClientSettings(backoffMs = listOf(1, 1), connectTimeoutMs = 500, readTimeoutMs = 3_000), sleep = {}) },
            clock = { now }, settings = settings,
            sharedSettings = SharedSettings(null).also { it.set(SharedSettings.Values(policy = policy)) },
        )
        val port = MemPort().also { agent.shared.port = it }
        val id get() = servers.installId
        suspend fun start() { agent.heartbeat(); agent.heartbeat() }
        suspend fun pass() = agent.shared.pass(o, throwing = true)
        val view get() = agent.shared.views.value[o]!!
        fun row(name: String) = view.rows.firstOrNull { it.meta.key.name == name }
    }

    private fun preset(name: String, exposure: String = "0000803f", opv: Int = 4, description: String = "") = buildJsonObject {
        put("kind", "preset")
        putJsonObject("row") {
            put("name", name); put("description", description); put("operation", "exposure"); put("op_version", opv)
            put("op_params", exposure); put("enabled", 1); put("blendop_params", "67676f64"); put("blendop_version", 14)
            put("iso_min", SharedHash.real(0.0)); put("iso_max", SharedHash.real(3.4028234663852886e38)); put("autoapply", 0)
            put("writeprotect_never_sent", 1)
        }
    }

    private fun style(name: String, vararg ops: String) = buildJsonObject {
        put("kind", "style")
        putJsonObject("style") { put("name", name); put("description", ""); put("iop_list", kotlinx.serialization.json.JsonNull) }
        putJsonArray("items") {
            ops.forEachIndexed { i, op -> add(buildJsonObject { put("num", i); put("module", 1); put("operation", op); put("op_params", "00"); put("enabled", 1) }) }
        }
    }

    private fun pcPut(body: JsonObject, baseRev: Int, at: Double = now) {
        val k = SharedKey.of(body)!!
        val r = stub.shared.put("pc-1", buildJsonObject {
            put("kind", k.kind.json); put("operation", k.operation); put("op_version", k.opVersion); put("name", k.name)
            put("hash", SharedHash.hash(body)!!); put("changed_at", at); put("base_rev", baseRev); put("body", body)
        })
        assertTrue("PC put: $r", r.first == 200 || r.first == 409)
    }

    @After fun stop() = stub.close()

    // ------------------------------------------------------------------ hash

    @Test fun hashVectorsAreReproduced() {
        val f = File("../../docs/design/R11-hash-vectors.json")
        val root = Json.parseToJsonElement(f.readText()) as JsonObject
        val vectors = root["vectors"] as JsonArray
        assertEquals(3, vectors.size)
        for (v in vectors) {
            v as JsonObject
            val body = v["body"] as JsonObject
            assertEquals((v["canonical"] as JsonPrimitive).content, SharedHash.canonicalCore(body))
            assertEquals((v["hash"] as JsonPrimitive).content, SharedHash.hash(body))
        }
    }

    @Test fun realIsFormattedAsCPercent17g() {
        val cases = mapOf(0.0 to "0", 1e8 to "100000000", 1e7 to "10000000", 1000.0 to "1000", 3.4028234663852886e38 to "3.4028234663852886e+38",
            0.1 to "0.10000000000000001", -2.5 to "-2.5", 1e17 to "1e+17", 1.5e-5 to "1.5e-05", 0.0001 to "0.0001",
            123456789012345678.0 to "1.2345678901234568e+17", 1.0 / 3 to "0.33333333333333331", -0.0 to "-0")
        for ((x, s) in cases) assertEquals("$x", s, SharedHash.real(x))
        // the float darktable stores for "any ISO" (FLT_MAX), as a REAL column hands it back
        assertEquals("3.4028234663852886e+38", SharedHash.real(Float.MAX_VALUE.toDouble()))
    }

    @Test fun aLayoutBodyKeepsTheStringByteForByte() {
        val text = "1|0ꬹ1|||exposure/exposure|colorbalancergb/global vibranceꬹmy edits|favorites||exposure"
        val b = SharedLayout.body("my · layout", text)
        assertEquals(text, SharedLayout.params(b))
        val row = b["row"] as JsonObject
        assertEquals(SharedHash.hex(text.toByteArray()), (row["op_params"] as JsonPrimitive).content)
        assertEquals(SharedKey(SharedKind.LAYOUT, "modulegroups", 1, "my · layout"), SharedKey.of(b))
        // darktable reads op_params as a C string: up to the first zero byte
        val withZero = JsonObject(b + ("row" to JsonObject(row + ("op_params" to JsonPrimitive(SharedHash.hex(text.toByteArray()) + "00ff")))))
        assertEquals(text, SharedLayout.params(withZero))
        assertNull("another module's preset is no layout", SharedLayout.params(preset("x")))
    }

    // ------------------------------------------------------------------ the cases of deliverable 5

    @Test fun firstSyncWithIdenticalItemsOnBothSidesGivesNoConflict() = runBlocking<Unit> {
        pcPut(preset("warm"), 0); pcPut(style("bw", "colorbalancergb", "sigmoid"), 0)
        stub.shared.log.clear()
        val a = Device("Phone A").apply { port.put(preset("warm")); port.put(style("bw", "colorbalancergb", "sigmoid")); port.put(preset("mine only")) }
        a.start(); a.pass()
        assertTrue("caps: ${stub.heartbeats[a.id]}", stub.heartbeats[a.id]!!.contains("\"shared\""))
        assertEquals("only the new item is sent: ${stub.shared.log}", listOf("PUT mine only"), stub.shared.log.filter { it.startsWith("PUT") })
        assertTrue("nothing waits: ${a.view.rows}", a.view.waiting.isEmpty())
        assertTrue(stub.shared.candidates.isEmpty())
        val st = stub.shared.states[a.id]!!["items"] as JsonObject
        assertEquals("three items reported: $st", 3, st.size)
    }

    @Test fun aChangeOnOneSideIsTakenInOnlyOnUpdate() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        val b = Device("Phone B")
        a.start(); b.start()
        a.pass(); b.pass()
        assertEquals(SharedRow.Change.NEW, b.row("warm")?.change)
        b.agent.shared.update(o, SharedChoices(take = setOf(b.row("warm")!!.meta.id)))
        assertEquals(a.port.hashOf("warm"), b.port.hashOf("warm"))
        // A changes it
        a.port.put(preset("warm", exposure = "00000040")); now += 10
        a.pass()
        assertEquals(2, stub.shared.itemNamed("warm")!!.rev)
        b.pass()
        val r = b.row("warm")!!
        assertEquals(SharedRow.Change.CHANGED, r.change)
        assertEquals("Phone A", r.meta.deviceName)
        assertEquals(listOf("Phone A"), b.view.devices)
        assertTrue("nothing changes on the phone before Update", b.port.hashOf("warm") != a.port.hashOf("warm"))
        val summary = b.agent.shared.update(o, SharedChoices(take = setOf(r.meta.id)))
        assertEquals("1 taken in", summary)
        assertEquals(a.port.hashOf("warm"), b.port.hashOf("warm"))
        stub.shared.log.clear()
        b.pass()
        assertTrue("B sends nothing back: ${stub.shared.log}", stub.shared.log.none { it.startsWith("PUT") || it.startsWith("DELETE") })
        assertTrue(b.view.waiting.isEmpty())
    }

    /** A and the PC both change "warm"; A's upload is a conflict, resolved with [choice]. */
    private suspend fun conflict(choice: String): Device {
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        a.start(); a.pass()
        val item = stub.shared.itemNamed("warm")!!
        pcPut(preset("warm", exposure = "11111111"), item.rev)
        stub.shared.log.clear()
        a.port.put(preset("warm", exposure = "22222222")); now += 5
        a.pass()
        assertEquals(SharedRow.Change.CONFLICT, a.row("warm")?.change)
        assertEquals("Studio PC", a.row("warm")!!.meta.deviceName)
        assertEquals(1, stub.shared.log.count { it == "PUT warm" })
        a.pass()
        assertEquals("the conflict is not uploaded again", 1, stub.shared.log.count { it == "PUT warm" })
        a.agent.shared.update(o, SharedChoices(conflicts = mapOf(item.id to choice)))
        return a
    }

    @Test fun conflictKeepThisPhones() = runBlocking<Unit> {
        val a = conflict("mine")
        assertEquals(SharedHash.hash(preset("warm", exposure = "22222222")), stub.shared.itemNamed("warm")!!.hash)
        assertEquals(SharedHash.hash(preset("warm", exposure = "22222222")), a.port.hashOf("warm"))
        a.pass()
        assertTrue(a.view.waiting.isEmpty())
        assertEquals(1, stub.shared.log.count { it == "PUT warm" })
    }

    @Test fun conflictTakeTheOtherDevices() = runBlocking<Unit> {
        val a = conflict("theirs")
        assertEquals(SharedHash.hash(preset("warm", exposure = "11111111")), a.port.hashOf("warm"))
        a.pass()
        assertTrue(a.view.waiting.isEmpty())
        assertEquals("not uploaded again", 1, stub.shared.log.count { it == "PUT warm" })
    }

    @Test fun conflictKeepBoth() = runBlocking<Unit> {
        val a = conflict("both")
        assertEquals(SharedHash.hash(preset("warm", exposure = "22222222")), a.port.hashOf("warm"))
        assertEquals("the other copy arrives as '<name> (<device>)'",
            SharedHash.hash(StubShared.renamed(preset("warm", exposure = "11111111"), "warm (Studio PC)")), a.port.hashOf("warm (Studio PC)"))
        stub.shared.log.clear()
        a.pass()
        assertTrue("nothing waits: ${a.view.rows}", a.view.waiting.isEmpty())
        assertTrue("nothing uploaded: ${stub.shared.log}", stub.shared.log.none { it.startsWith("PUT") })
    }

    @Test fun aConflictResolvedTheirsElsewhereWaitsAndIsNotALocalChange() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        a.start(); a.pass()
        val item = stub.shared.itemNamed("warm")!!
        pcPut(preset("warm", exposure = "11111111"), item.rev)
        a.port.put(preset("warm", exposure = "22222222")); a.pass()
        stub.shared.resolve(item.id, a.id, "theirs") // as LPL's web UI would for this agent
        stub.shared.log.clear()
        a.pass()
        assertEquals(SharedRow.Change.CHANGED, a.row("warm")?.change)
        assertTrue("not uploaded: ${stub.shared.log}", stub.shared.log.none { it.startsWith("PUT") })
    }

    @Test fun newestWinsDecidesAtOnce() = runBlocking<Unit> {
        val a = Device("Phone A", SharedPolicy.NEWEST).apply { port.put(preset("warm")) }
        a.start(); a.pass()
        assertEquals("newest", (stub.shared.states[a.id]!!["policy"] as JsonPrimitive).content)
        val item = stub.shared.itemNamed("warm")!!
        // the PC's change is older than the phone's: the phone's wins
        pcPut(preset("warm", exposure = "11111111"), item.rev, at = now - 100)
        a.port.put(preset("warm", exposure = "22222222"))
        a.pass()
        assertTrue(stub.shared.candidates.isEmpty())
        assertEquals(SharedHash.hash(preset("warm", exposure = "22222222")), stub.shared.itemNamed("warm")!!.hash)
        assertTrue(a.view.waiting.isEmpty())
        // the PC's change is newer: the server keeps it, the phone's copy waits to be replaced
        pcPut(preset("warm", exposure = "33333333"), stub.shared.itemNamed("warm")!!.rev, at = now + 100)
        a.port.put(preset("warm", exposure = "44444444"))
        a.pass()
        assertEquals(SharedHash.hash(preset("warm", exposure = "33333333")), stub.shared.itemNamed("warm")!!.hash)
        assertEquals(SharedRow.Change.CHANGED, a.row("warm")?.change)
        stub.shared.log.clear()
        a.pass()
        assertTrue("not uploaded again: ${stub.shared.log}", stub.shared.log.none { it.startsWith("PUT") })
        a.agent.shared.update(o, SharedChoices(take = setOf(item.id)))
        assertEquals(SharedHash.hash(preset("warm", exposure = "33333333")), a.port.hashOf("warm"))
    }

    @Test fun aDeletionIsOfferedAndDeletedOrKept() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(preset("warm")); port.put(style("bw", "sigmoid")) }
        a.start(); a.pass()
        val warm = stub.shared.itemNamed("warm")!!; val bw = stub.shared.itemNamed("bw")!!
        stub.shared.delete("pc-1", warm.id, warm.rev); stub.shared.delete("pc-1", bw.id, bw.rev)
        a.pass()
        assertEquals(SharedRow.Change.DELETED, a.row("warm")?.change)
        assertEquals(SharedRow.Change.DELETED, a.row("bw")?.change)
        assertNotNull("never applied silently", a.port.hashOf("warm"))
        val s = a.agent.shared.update(o, SharedChoices(deletions = mapOf(warm.id to true, bw.id to false)))
        assertEquals("1 deleted here · 1 kept on this phone", s)
        assertNull(a.port.hashOf("warm"))
        assertNotNull(a.port.hashOf("bw"))
        val st = stub.shared.states[a.id]!!["items"] as JsonObject
        assertEquals("kept", ((st[bw.id.toString()] as JsonObject)["state"] as JsonPrimitive).content)
        assertEquals("ok", ((st[warm.id.toString()] as JsonObject)["state"] as JsonPrimitive).content)
        stub.shared.log.clear()
        a.pass()
        assertTrue("not offered again: ${a.view.rows}", a.view.waiting.isEmpty())
        assertTrue("nothing sent: ${stub.shared.log}", stub.shared.log.none { it.startsWith("PUT") || it.startsWith("DELETE") })
    }

    @Test fun aKeptItemChangedAgainComesBackForEveryone() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(style("bw", "sigmoid")) }
        a.start(); a.pass()
        val bw = stub.shared.itemNamed("bw")!!
        stub.shared.delete("pc-1", bw.id, bw.rev)
        a.pass()
        a.agent.shared.update(o, SharedChoices(deletions = mapOf(bw.id to false)))
        val deletionRev = stub.shared.item(bw.id)!!.rev
        a.port.put(style("bw", "sigmoid", "colorbalancergb"))
        a.pass()
        val back = stub.shared.item(bw.id)!!
        assertFalse("back for everyone", back.deleted)
        assertEquals(deletionRev + 1, back.rev)
        assertEquals(SharedHash.hash(style("bw", "sigmoid", "colorbalancergb")), back.hash)
    }

    @Test fun aRenameTravelsAsANewItemAndADeletion() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(style("bw", "sigmoid")) }
        val b = Device("Phone B")
        a.start(); b.start(); a.pass(); b.pass()
        b.agent.shared.update(o, SharedChoices(take = setOf(b.row("bw")!!.meta.id)))
        a.port.items.remove(SharedKey.style("bw")); a.port.put(style("bw toned", "sigmoid"))
        a.pass()
        assertTrue(stub.shared.itemNamed("bw")!!.deleted)
        assertFalse(stub.shared.itemNamed("bw toned")!!.deleted)
        b.pass()
        assertEquals(SharedRow.Change.DELETED, b.row("bw")?.change)
        assertEquals(SharedRow.Change.NEW, b.row("bw toned")?.change)
    }

    @Test fun anItemForANewerDarktableIsGreyedAndReportedUnusable() = runBlocking<Unit> {
        pcPut(preset("future", opv = 77), 0)
        val a = Device("Phone A")
        a.start(); a.pass()
        val r = a.row("future")!!
        assertEquals(SharedRow.Change.NEW, r.change)
        val s = a.agent.shared.update(o, SharedChoices(take = setOf(r.meta.id)))
        assertEquals("1 need a newer darktable", s.substringAfter(" · "))
        assertEquals(SharedRow.Change.UNUSABLE, a.row("future")?.change)
        assertTrue(a.view.waiting.isEmpty())
        val st = stub.shared.states[a.id]!!["items"] as JsonObject
        assertEquals("unusable", ((st[r.meta.id.toString()] as JsonObject)["state"] as JsonPrimitive).content)
        stub.shared.log.clear(); a.pass()
        assertTrue("no deletion for an item never written: ${stub.shared.log}", stub.shared.log.none { it.startsWith("DELETE") })
    }

    @Test fun nothingIsUploadedWhileAutomaticSyncIsOffOrSharingIsOff() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        a.settings.setAutoSync(false)
        a.start(); a.pass()
        assertTrue(stub.shared.log.none { it.startsWith("PUT") })
        a.settings.setAutoSync(true)
        a.agent.shared.settings.set(SharedSettings.Values(on = false))
        a.agent.heartbeat()
        assertFalse("no shared cap while off", stub.heartbeats[a.id]!!.contains("\"shared\""))
        a.pass()
        assertTrue(stub.shared.log.none { it.startsWith("PUT") })
        a.agent.shared.settings.set(SharedSettings.Values(presets = false))
        a.pass()
        assertTrue("presets switched off: ${stub.shared.log}", stub.shared.log.none { it.startsWith("PUT") })
        a.agent.shared.settings.set(SharedSettings.Values())
        a.pass()
        assertEquals(listOf("PUT warm"), stub.shared.log.filter { it.startsWith("PUT") })
    }

    @Test fun aHashMismatchIsLoggedNotAConflictAndTheOtherItemsGoOn() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(preset("good")) }
        val bad = object : SharedPort by a.port {
            override suspend fun snapshot() = a.port.snapshot().map { if (it.key.name == "good") it else it.copy(hash = "0".repeat(64)) }
        }
        a.port.put(preset("broken"))
        a.agent.shared.port = bad
        a.start(); a.pass()
        assertNotNull("the good one is stored", stub.shared.itemNamed("good"))
        assertNull(stub.shared.itemNamed("broken"))
        assertTrue("no conflict", stub.shared.candidates.isEmpty() && a.view.rows.none { it.change == SharedRow.Change.CONFLICT })
    }

    @Test fun aDeletionCarriesTheTimeItWasSeen() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        a.start(); a.pass()
        now = 2_000_000.5
        a.port.items.clear(); a.pass()
        assertTrue(stub.seen("DELETE", "/api/shared/").single().path.contains("changed_at=2000000.5"))
        assertEquals(2_000_000.5, stub.shared.itemNamed("warm")!!.changedAt, 0.0)
    }

    @Test fun anOlderServerIsLeftAlone() = runBlocking<Unit> {
        stub.r11 = false
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        a.start(); a.pass()
        assertFalse(stub.heartbeats[a.id]!!.contains("\"shared\""))
        assertTrue(stub.seen("GET", "/api/shared").isEmpty())
    }

    @Test fun theBookSurvivesARestart() = runBlocking<Unit> {
        val a = Device("Phone A").apply { port.put(preset("warm")) }
        a.start(); a.pass()
        val again = LplAgent(a.servers, WorkStore(File(tmp.root, "Phone A/lpl/work")), FakeEditor(), { "test" },
            clientFor = { origin -> LplClient(origin, { null }, sleep = {}) }, clock = { now })
        again.shared.port = a.port
        again.heartbeat(); again.heartbeat()
        stub.shared.log.clear()
        again.shared.pass(o, throwing = true)
        assertTrue("nothing sent again: ${stub.shared.log}", stub.shared.log.none { it.startsWith("PUT") })
    }
}

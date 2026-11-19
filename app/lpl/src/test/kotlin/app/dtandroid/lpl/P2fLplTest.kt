// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Brief P2f deliverables 6 and 7 and the reviewer's notes on R13 to R16, against the stub server: once as
 * a server that has them, once as an older one that has not (404, fields left out). Nothing may fail
 * against the older one.
 */
class P2fLplTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl()
    private val editor = FakeEditor()
    private lateinit var servers: ServerStore
    private lateinit var agent: LplAgent
    private val o get() = stub.origin

    private fun newAgent() = LplAgent(
        servers, WorkStore(File(tmp.root, "lpl/work")), editor, { "test" },
        clientFor = { origin -> LplClient(origin, { servers.password(origin) }, settings = ClientSettings(backoffMs = listOf(1, 1)), sleep = {}) },
    )

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        servers.put(o, "Phone", null)
        agent = newAgent()
        stub.add(StubLpl.Photo(11, "IMG_0011.CR2", fakeRaw(200_000, 11), rating = 2))
    }

    @After fun stop() = stub.close()

    private suspend fun editAndSync() {
        val r = agent.take(o, 11)
        editor.openRaw = agent.work.raw(r); agent.opened(o, 11)
        editor.edit(agent.work.xmp(r))
        editor.openRaw = null; agent.left(o, 11)
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 11))
    }

    @Test fun xmpPostNamesThisDevice() = runBlocking {
        editAndSync()
        val post = stub.seen("POST", "/api/photos/11/xmp").single()
        assertTrue(post.path, post.path.contains("&agent=${servers.installId}"))
        assertEquals(servers.installId, stub.photos[11]!!.lastAgent)
    }

    @Test fun lastEditFromAServerWithR15() = runBlocking {
        stub.r15 = true
        agent.heartbeat() // the stub learns this device's name from it
        editAndSync()
        val e = agent.record(o, 11)!!.lastEdit!!
        assertEquals("Phone", e.device)
        assertEquals("agent", e.source)
        assertEquals(servers.installId, e.agentId)
        assertTrue(e.line().startsWith("last edited on Phone · today "))
        assertTrue(e.line(thisDevice = servers.installId).startsWith("last edited on this phone · "))
        assertTrue(e.line().endsWith(" · exposure"))
        // kept with the photo across a restart
        assertEquals(e, newAgent().record(o, 11)!!.lastEdit)
        assertEquals(1, agent.client(o).edits(11).size)
    }

    @Test fun anOlderServerGivesNoLineAndNothingFails() = runBlocking {
        editAndSync()
        assertNull(agent.record(o, 11)!!.lastEdit)
        assertEquals(emptyList<LastEdit>(), agent.client(o).edits(11))
        assertEquals(PhotoStatus.SYNCED, agent.record(o, 11)!!.status)
    }

    @Test fun eventParsingTakesOnlyTimeAsRequired() {
        assertNull(LastEdit.fromJson(kotlinx.serialization.json.JsonNull))
        assertNull(LastEdit.fromJson(kotlinx.serialization.json.Json.parseToJsonElement("""{"device":"Desktop"}""")))
        val e = LastEdit.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"time":1795070000.5,"source":"outside","device":null,"new_field":[1,2],"summary":"crop"}"""))!!
        val now = Instant.ofEpochSecond(1_795_300_000)
        assertEquals("changed outside LightPhotoLibrary · 19 Nov · crop", e.line(ZoneOffset.UTC, now))
        val d = LastEdit(1795070000.5, "agent", "pc-1", "Desktop", "linux", "exposure, color balance rgb")
        assertEquals("last edited on Desktop · 19 Nov · exposure, color balance rgb", d.line(ZoneOffset.UTC, now))
        assertEquals("last edited · 19 Nov 2026", LastEdit(1795070000.5).line(ZoneOffset.UTC, Instant.ofEpochSecond(1_830_000_000)))
    }

    @Test fun heartbeatCarriesTheStepWhileItRunsAndNotAfter() = runBlocking {
        val r = agent.take(o, 11)
        editor.openRaw = agent.work.raw(r); agent.opened(o, 11)
        editor.edit(agent.work.xmp(r))
        editor.openRaw = null; agent.left(o, 11)
        var during: String? = null
        editor.duringRender = { agent.heartbeat(); during = stub.heartbeats.values.last() }
        agent.sync(o, 11)
        assertTrue(during!!, during!!.contains("\"step\":\"rendering preview\""))
        agent.heartbeat()
        val after = stub.heartbeats.values.last()
        assertFalse(after, after.contains("\"step\""))
        assertFalse(after, after.contains("\"progress\""))
        assertTrue(agent.steps.value.isEmpty())
    }

    @Test fun downloadShowsItsShareDone() = runBlocking {
        val shares = java.util.Collections.synchronizedList(ArrayList<Double?>())
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            agent.steps.collect { m -> m[o to 11L]?.takeIf { it.step == Step.DOWNLOADING }?.let { shares += it.progress } }
        }
        agent.take(o, 11)
        watcher.cancel()
        assertTrue("download step seen: $shares", shares.any { it != null && it > 0.0 })
        assertTrue(agent.steps.value.isEmpty())
    }

    @Test fun aJobRunOnceIsReportedAgainNotRunTwice() = runBlocking {
        val job = Job(77, "sync", listOf(11), false)
        assertNull(agent.ranBefore(o, job))
        agent.jobDone(o, job, true)
        assertEquals(true, agent.ranBefore(o, job))
        assertEquals("kept across a restart", true, newAgent().ranBefore(o, job))
    }

    @Test fun passwordCheckUsesR14AndFallsBack() {
        stub.password = "secret"
        // older server: /api/auth/token answers 404 (behind the password check: 401 without it), /api/status decides
        assertEquals(ServerCheckResult.Lpl(true, true, 1), LplClient(o, { null }).check("secret"))
        assertEquals(ServerCheckResult.Lpl(true, false, null), LplClient(o, { null }).check("wrong"))
        stub.r14 = true
        val before = stub.seen("GET", "/api/status").size
        assertEquals(ServerCheckResult.Lpl(true, true, null), LplClient(o, { null }).check("secret"))
        assertEquals(ServerCheckResult.Lpl(true, false, null), LplClient(o, { null }).check("wrong"))
        assertEquals("R14 answered: no status call", before, stub.seen("GET", "/api/status").size)
    }
}

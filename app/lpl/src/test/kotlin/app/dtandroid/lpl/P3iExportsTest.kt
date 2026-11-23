// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3i (request R18, app side) against the stub server: `server_caps` from the heartbeat answer (kept across a
 * restart), the upload with the photo's id and version and its `size`, the step `exporting` in the heartbeat while it
 * goes, a repeated upload after a cut-off, an export waiting for an unreachable server (also across a restart) and
 * sent with the next sync, and an older server (no `server_caps`: the export waits).
 */
class P3iExportsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl().also { it.r17 = true; it.r18 = true }
    private val editor = FakeEditor()
    private lateinit var servers: ServerStore
    private lateinit var agent: LplAgent
    private val o get() = stub.origin
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + fakeRaw(300_000, 5)

    private fun newAgent() = LplAgent(
        servers, WorkStore(File(tmp.root, "lpl/work")), editor, { "test" },
        clientFor = { origin -> LplClient(origin, { servers.password(origin) }, settings = ClientSettings(backoffMs = listOf(1, 1), connectTimeoutMs = 500, readTimeoutMs = 2_000), sleep = {}) },
    )

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        servers.put(o, "Phone", null)
        agent = newAgent()
        stub.add(StubLpl.Photo(21, "IMG_01.RAF", fakeRaw(50_000, 21)))
    }

    @After fun stop() = stub.close()

    private fun file(name: String = "IMG_01.jpg", bytes: ByteArray = jpeg) = File(tmp.root, "out/$name").also { it.parentFile.mkdirs(); it.writeBytes(bytes) }

    @Test fun serverCapsComeFromTheHeartbeatAndSurviveARestart() = runBlocking<Unit> {
        assertNull("not known before the first answer", agent.receivesExports(o))
        agent.heartbeat()
        assertEquals(true, agent.receivesExports(o))
        val sent = stub.heartbeats.values.first()
        assertTrue("the app announces exports: $sent", sent.contains("\"exports\""))
        assertEquals(true, newAgent().receivesExports(o))
    }

    @Test fun anOlderServerHasNoServerCapsAndSoDoesNotReceiveExports() = runBlocking<Unit> {
        stub.r18 = false
        agent.heartbeat()
        assertEquals(false, agent.receivesExports(o))
    }

    @Test fun uploadCarriesTheIdVersionNameAndSize() = runBlocking<Unit> {
        val seenFractions = ArrayList<Float>()
        val r = agent.sendExport(o, 21, 3, "IMG_01_03.jpg", file()) { seenFractions += it }
        assertTrue(r is LplAgent.ExportOutcome.Sent)
        val e = stub.photos[21]!!.exports.single()
        assertEquals(3, e.version)
        assertEquals("IMG_01_03.jpg", e.name)
        assertEquals(servers.installId, e.agent)
        assertArrayEquals(jpeg, e.bytes)
        val post = stub.seen("POST", "/api/photos/21/exports").single()
        assertTrue(post.path, post.path.contains("size=${jpeg.size}"))
        assertTrue("progress ends at 1: $seenFractions", seenFractions.isNotEmpty() && seenFractions.last() == 1f)
        for (k in 1 until seenFractions.size) assertTrue(seenFractions[k] >= seenFractions[k - 1])
        assertEquals("nothing waits", 0, agent.queuedExports())
        assertTrue("the step is gone after it", agent.steps.value.isEmpty())
    }

    @Test fun theHeartbeatSaysExportingWhileTheFileGoes() = runBlocking<Unit> {
        agent.take(o, 21)
        val st = agent.steps
        agent.heartbeat()
        // the step is set during the upload: a slow server (the stub waits) lets a heartbeat see it
        stub.delayMs = 400
        val t = Thread { runBlocking { agent.sendExport(o, 21, 0, "IMG_01.jpg", file()) } }
        t.start()
        val deadline = System.currentTimeMillis() + 3_000
        while (st.value.values.none { it.step == Step.EXPORTING } && System.currentTimeMillis() < deadline) Thread.sleep(5)
        stub.delayMs = 0
        agent.heartbeat()
        t.join()
        val seen = stub.heartbeatLog.last { it.contains("\"step\":\"exporting\"") }
        assertTrue(seen, seen.contains("\"progress\""))
    }

    @Test fun aCutOffUploadIsRepeatedWhole() = runBlocking<Unit> {
        stub.dropNext = 1 // the server closes the first connection without an answer
        val r = agent.sendExport(o, 21, 0, "IMG_01.jpg", file())
        assertTrue(r is LplAgent.ExportOutcome.Sent)
        assertEquals("two posts, one stored export", 2, stub.seen("POST", "/api/photos/21/exports").size)
        assertEquals(1, stub.photos[21]!!.exports.size)
        assertArrayEquals(jpeg, stub.photos[21]!!.exports.single().bytes)
    }

    @Test fun anUnreachableServerKeepsTheExportAndTheNextSyncSendsIt() = runBlocking<Unit> {
        agent.heartbeat() // caps known
        stub.stop()
        val r = agent.sendExport(o, 21, 2, "IMG_01_02.jpg", file())
        assertEquals(LplAgent.ExportOutcome.Queued, r)
        assertEquals(1, agent.queuedExports())
        assertEquals("a queued export is not sent while the server is away", 0, agent.sendQueuedExports())
        assertEquals(1, agent.queuedExports())
        // the app is restarted: the queue is on disk
        agent = newAgent()
        assertEquals(1, agent.queuedExports())
        stub.start()
        assertEquals(1, agent.sendQueuedExports())
        assertEquals(0, agent.queuedExports())
        val e = stub.photos[21]!!.exports.single()
        assertEquals(2, e.version)
        assertEquals("IMG_01_02.jpg", e.name)
        assertArrayEquals(jpeg, e.bytes)
    }

    @Test fun aQueuedExportWaitsUntilTheServerSaysItReceivesExports() = runBlocking<Unit> {
        agent.heartbeat()
        stub.stop()
        agent.sendExport(o, 21, 0, "IMG_01.jpg", file())
        stub.r18 = false
        stub.start()
        agent.heartbeat() // an older server now: no server_caps
        assertEquals(0, agent.sendQueuedExports())
        assertEquals(1, agent.queuedExports())
        stub.r18 = true
        agent.heartbeat()
        assertEquals(1, agent.sendQueuedExports())
    }

    @Test fun aRefusedExportIsAnErrorNotAQueueEntry() = runBlocking<Unit> {
        try {
            agent.sendExport(o, 999, 0, "IMG_01.jpg", file()) // the photo is unknown to the server
            throw AssertionError("expected a refusal")
        } catch (e: LplException) {
            assertEquals(LplFailure.NOT_FOUND, e.failure)
        }
        assertEquals(0, agent.queuedExports())
        assertFalse(agent.steps.value.containsKey(o to 999L))
    }
}

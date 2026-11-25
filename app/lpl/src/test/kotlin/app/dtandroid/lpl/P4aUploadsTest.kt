// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P4a deliverables 2 and 5: every upload is safe to repeat after an interruption. The stub carries out a request
 * and then loses its answer ([StubLpl.loseAnswers]), as a connection that drops after the server stored the data; or the
 * app is killed and a new agent goes on. Each time the server ends up with one result, and nothing is reported synced
 * or sent before the server confirmed it.
 */
class P4aUploadsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl().also { it.r17 = true; it.r18 = true; it.r11 = true }
    private val editor = FakeEditor()
    private lateinit var servers: ServerStore
    private lateinit var agent: LplAgent
    private val o get() = stub.origin
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(30_000) { (it % 251).toByte() }

    private fun newAgent(retries: Int = 2) = LplAgent(
        servers, WorkStore(File(tmp.root, "lpl/work")), editor, { "test" },
        clientFor = { origin -> LplClient(origin, { null }, settings = ClientSettings(backoffMs = listOf(1, 1), retries = retries), sleep = {}) },
    )

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        servers.put(o, "Phone", null)
        agent = newAgent()
        stub.add(StubLpl.Photo(21, "IMG_01.RAF", fakeRaw(100_000, 21), rating = 3))
    }

    @After fun stop() = stub.close()

    private fun file(name: String = "x.jpg") = File(tmp.root, name).apply { writeBytes(jpeg) }
    private fun lose(method: String, path: String, times: Int = 1) = repeat(times) { stub.loseAnswers += method to path }
    private fun versionXmp(n: Int) = VersionFiles.file(agent.work.folder(o, 21), "IMG_01.RAF", n)
    private fun putVersion(n: Int, body: String) = versionXmp(n).writeText(
        StubLpl.lplXmp(1).decodeToString().replace("</rdf:RDF>", "<darktable:history><rdf:Seq><rdf:li>$body</rdf:li></rdf:Seq></darktable:history></rdf:RDF>"))

    // ------------------------------------------------------------------ exports (R18)

    @Test fun anExportWhoseAnswerWasLostIsNotSentTwice() = runBlocking<Unit> {
        agent.heartbeat()
        lose("POST", "/api/photos/21/exports")
        val r = agent.sendExport(o, 21, 0, "IMG_01.jpg", file())
        assertTrue(r is LplAgent.ExportOutcome.Sent)
        val ex = stub.photos[21]!!.exports
        assertEquals("one export on the server", 1, ex.size)
        assertEquals((r as LplAgent.ExportOutcome.Sent).exportId, ex.single().id)
        assertEquals("the file was sent once", 1, stub.seen("POST", "/api/photos/21/exports").size)
        assertEquals(0, agent.queuedExports())
    }

    @Test fun anExportOfAnAppKilledAfterTheUploadIsFoundOnTheServerNotSentAgain() = runBlocking<Unit> {
        agent.heartbeat()
        // what sendExport does before the file goes: read the list, queue the file with it
        val c = LplClient(o, { null }, settings = ClientSettings(backoffMs = listOf(1, 1), retries = 0), sleep = {})
        val known = c.listExports(21).map { it.id }.toSet()
        val q = agent.exports.queue(o, 21, 2, "IMG_01_02.jpg", file(), known)
        // the upload reaches the server, and the app is killed before the answer arrives
        lose("POST", "/api/photos/21/exports")
        try { c.postExport(21, servers.installId, 2, "IMG_01_02.jpg", q.file); throw AssertionError("expected no answer") } catch (e: LplException) { assertTrue(e.transient) }
        assertEquals("the server has it already", 1, stub.photos[21]!!.exports.size)

        // the next start: the queue is on disk; the sync finds the export on the server and sends nothing
        agent = newAgent()
        agent.heartbeat()
        assertEquals(1, agent.queuedExports())
        assertEquals(1, agent.sendQueuedExports())
        assertEquals(0, agent.queuedExports())
        assertEquals(1, stub.photos[21]!!.exports.size)
        assertEquals("the file went once", 1, stub.seen("POST", "/api/photos/21/exports").size)
        // a queued export that never reached the server is sent normally
        val q2 = agent.exports.queue(o, 21, 0, "IMG_01.jpg", file(), c.listExports(21).map { it.id }.toSet())
        assertEquals(1, agent.sendQueuedExports())
        assertEquals(2, stub.photos[21]!!.exports.size)
        assertFalse(q2.file.exists())
    }

    @Test fun twoExportsTheUserAskedForStayTwoEvenWhenAnAnswerIsLost() = runBlocking<Unit> {
        agent.heartbeat()
        agent.sendExport(o, 21, 0, "IMG_01.jpg", file("a.jpg"))
        lose("POST", "/api/photos/21/exports")
        val second = agent.sendExport(o, 21, 0, "IMG_01.jpg", file("b.jpg"))
        val ex = stub.photos[21]!!.exports
        assertEquals(listOf("IMG_01.jpg", "IMG_01 (2).jpg"), ex.map { it.name })
        assertEquals(ex[1].id, (second as LplAgent.ExportOutcome.Sent).exportId)
        assertEquals(2, stub.seen("POST", "/api/photos/21/exports").size)
    }

    @Test fun theMatchRuleForALostExportAnswer() {
        val e = ServerExport(7, 2, "IMG_01 (3).jpg", "exports/x", 100, "android-a")
        assertTrue(e.isUpload("android-a", 2, "IMG_01.jpg", 100, emptySet(), emptySet()))
        assertTrue(e.copy(name = "IMG_01.jpg").isUpload("android-a", 2, "IMG_01.jpg", 100, emptySet(), emptySet()))
        assertFalse("known before", e.isUpload("android-a", 2, "IMG_01.jpg", 100, setOf(7), emptySet()))
        assertFalse("claimed", e.isUpload("android-a", 2, "IMG_01.jpg", 100, emptySet(), setOf(7)))
        assertFalse("other device", e.isUpload("android-b", 2, "IMG_01.jpg", 100, emptySet(), emptySet()))
        assertFalse("other version", e.isUpload("android-a", 0, "IMG_01.jpg", 100, emptySet(), emptySet()))
        assertFalse("other size", e.isUpload("android-a", 2, "IMG_01.jpg", 101, emptySet(), emptySet()))
        assertFalse("other name", e.isUpload("android-a", 2, "IMG_011.jpg", 100, emptySet(), emptySet()))
        assertFalse("regex characters in the name are plain", e.copy(name = "IMGx01 (3).jpg").isUpload("android-a", 2, "IMG.01.jpg", 100, emptySet(), emptySet()))
    }

    // ------------------------------------------------------------------ edits (XMP), version 0 and versions (R17)

    @Test fun anEditIsNotSyncedUntilTheServerConfirmedAndARepeatStoresTheSameFile() = runBlocking<Unit> {
        agent.take(o, 21)
        val r = agent.record(o, 21)!!
        agent.work.xmp(r).writeText(StubLpl.lplXmp(5).decodeToString().replace("</rdf:RDF>", "<!-- edited --></rdf:RDF>"))
        lose("POST", "/api/photos/21/xmp", times = 3) // stored three times, never answered
        assertNotEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        val after = agent.record(o, 21)!!
        assertTrue("still waiting", agent.isDirty(after))
        assertNotEquals(PhotoStatus.SYNCED, after.status)
        assertEquals(3, stub.photos[21]!!.xmpPosts)
        // the connection is back: sent again, confirmed, synced; the server holds the one edit
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        assertFalse(agent.isDirty(agent.record(o, 21)!!))
        assertTrue(stub.photos[21]!!.xmp!!.decodeToString().contains("<!-- edited -->"))
    }

    @Test fun aNewVersionWhoseAnswerWasLostStaysOneVersion() = runBlocking<Unit> {
        agent.take(o, 21)
        putVersion(1, "mine")
        agent.versionMade(o, 21, 1)
        lose("POST", "/api/photos/21/xmp?version=1")
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        val ph = stub.photos[21]!!
        assertEquals("version 1 only, no copy as version 2", listOf(1), ph.versions.keys.toList())
        assertTrue("the repeat met 409 version_exists (its own stored post)", stub.seen("POST", "/api/photos/21/xmp").any { it.status == 409 })
        assertTrue(ph.versions[1]!!.xmp.decodeToString().contains("mine"))
        assertTrue(versionXmp(1).isFile)
        assertEquals(listOf(1), agent.record(o, 21)!!.versions.map { it.n })
        assertFalse(agent.record(o, 21)!!.versionState(1)!!.posting)
    }

    @Test fun aNewVersionOfAnAppKilledDuringItsPostStaysOneVersionAfterTheRestart() = runBlocking<Unit> {
        agent = newAgent(retries = 0)
        agent.take(o, 21)
        putVersion(1, "mine")
        agent.versionMade(o, 21, 1)
        lose("POST", "/api/photos/21/xmp?version=1")
        assertNotEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        assertTrue("the attempt is on disk", agent.record(o, 21)!!.versionState(1)!!.posting)
        agent = newAgent()
        assertTrue(agent.record(o, 21)!!.versionState(1)!!.posting)
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        assertEquals(listOf(1), stub.photos[21]!!.versions.keys.toList())
        assertTrue(stub.seen("POST", "/api/photos/21/xmp").any { it.status == 409 })
        assertTrue(versionXmp(1).isFile)
    }

    @Test fun aVersionAnotherDeviceMadeMeanwhileIsStillRespected() = runBlocking<Unit> {
        // the post never reached the server, and a PC made version 1: the phone's goes to 2 as before (P3f)
        agent = newAgent(retries = 0)
        agent.take(o, 21)
        putVersion(1, "mine")
        agent.versionMade(o, 21, 1)
        stub.dropNext = 1
        assertNotEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        assertTrue(agent.record(o, 21)!!.versionState(1)!!.posting)
        stub.photos[21]!!.versions[1] = StubLpl.Ver(StubLpl.lplXmp(9), System.currentTimeMillis() / 1000.0 + 60)
        agent = newAgent()
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        val ph = stub.photos[21]!!
        assertFalse(ph.versions[1]!!.xmp.decodeToString().contains("mine"))
        assertTrue(ph.versions[2]!!.xmp.decodeToString().contains("mine"))
    }

    @Test fun aPreviewSentTwiceIsOnePreview() = runBlocking<Unit> {
        agent.take(o, 21)
        lose("POST", "/api/photos/21/preview")
        val c = LplClient(o, { null }, settings = ClientSettings(backoffMs = listOf(1, 1)), sleep = {})
        try { c.postPreview(21, jpeg) } catch (e: LplException) { assertTrue(e.transient) }
        c.postPreview(21, jpeg)
        assertArrayEquals("the server keeps one preview, the last", jpeg, stub.photos[21]!!.preview)
    }

    // ------------------------------------------------------------------ shared items (R11)

    private fun preset(name: String) = buildJsonObject {
        put("kind", "preset")
        putJsonObject("row") {
            put("name", name); put("description", ""); put("operation", "exposure"); put("op_version", 6)
            put("op_params", "0000803f"); put("enabled", 1); put("blendop_params", JsonNull); put("blendop_version", 14)
            put("multi_priority", 0); put("multi_name", ""); put("multi_name_hand_edited", 0); put("model", "%"); put("maker", "%"); put("lens", "%")
            put("iso_min", SharedHash.real(0.0)); put("iso_max", SharedHash.real(Float.MAX_VALUE.toDouble()))
            put("exposure_min", SharedHash.real(0.0)); put("exposure_max", SharedHash.real(1e8)); put("aperture_min", SharedHash.real(0.0))
            put("aperture_max", SharedHash.real(1e8)); put("focal_length_min", SharedHash.real(0.0)); put("focal_length_max", SharedHash.real(1000.0))
            put("autoapply", 0); put("filter", 0); put("def", 0); put("format", 0)
        }
    }

    @Test fun aSharedItemPutAndDeleteRepeatedAfterLostAnswersLeaveOneRevisionEach() {
        val c = LplClient(o, { null }, settings = ClientSettings(backoffMs = listOf(1, 1)), sleep = {})
        val agentId = "android-p4a"
        c.heartbeat(agentId, "Phone", AgentState(emptyList(), false, true, "test", ""), extraCaps = listOf("shared"))
        val body = preset("p4a repeat")
        val local = SharedLocal(SharedKey.of(body)!!, SharedHash.hash(body)!!, body)
        lose("PUT", "/api/shared")
        try { c.sharedPut(agentId, local, 1_000.0, 0); throw AssertionError("expected no answer") } catch (e: LplException) { assertTrue(e.transient) }
        val again = c.sharedPut(agentId, local, 1_000.0, 0) as SharedWrite.Stored
        assertEquals("the repeat stored nothing new", 1, again.rev)
        // twice: the JDK's HttpURLConnection repeats a DELETE by itself once when the connection closes without an answer
        lose("DELETE", "/api/shared/${again.id}", times = 2)
        try { c.sharedDelete(agentId, again.id, 1, 1_001.0); throw AssertionError("expected no answer") } catch (e: LplException) { assertTrue(e.transient) }
        val del = c.sharedDelete(agentId, again.id, 1, 1_001.0)
        assertTrue("a repeated delete is not a conflict: $del", del is SharedWrite.Stored)
        assertEquals(2, (del as SharedWrite.Stored).rev)
    }
}

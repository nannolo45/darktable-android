// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.CountDownLatch

/** Brief P2k: batches and Stop (PLAN.md 4.1, "Batches and Stop") against the stub server, with a fake darkroom. */
class P2kBatchesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl().also { it.batch = true }
    private val editor = FakeEditor()
    private lateinit var servers: ServerStore
    private lateinit var agent: LplAgent
    private val o get() = stub.origin
    private val id get() = servers.installId

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        servers.put(o, "Phone", null)
        agent = LplAgent(servers, WorkStore(File(tmp.root, "work")), editor, { "test / darktable test" },
            clientFor = { origin -> LplClient(origin, { servers.password(origin) }, settings = ClientSettings(backoffMs = listOf(1, 1)), sleep = {}) })
        for (n in 11L..13L) stub.add(StubLpl.Photo(n, "DSCF00$n.RAF", fakeRaw(250_000, n.toInt())))
    }

    @After fun stop() = stub.close()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun ids(a: JsonArray) = a.map { it.jsonPrimitive.content.toLong() }
    private fun lastHb(): JsonObject = json(stub.heartbeats[id]!!)
    private fun transfer(hb: JsonObject, job: Long) = hb["transfers"]!!.jsonArray.map { it.jsonObject }.first { it["job"]!!.jsonPrimitive.content.toLong() == job }

    private fun waitFor(what: String, ms: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out: $what" }; Thread.sleep(5) }
    }

    /** the job as the heartbeat hands it out */
    private suspend fun receive(kind: String = "edit", vararg photos: Long, open: Boolean? = null): Job {
        val j = stub.queueJob(id, kind, photos.toList(), open = open)
        return agent.heartbeat().first { it.second.id == j.id }.second
    }

    @Test fun heartbeatsCarryCapsAndTransfersAndTheJobsOpenFlag() = runBlocking {
        agent.heartbeat()
        val hb0 = lastHb()
        assertEquals(listOf("batch", "cancel", "open", "versions"), hb0["caps"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("no job held: an empty list", 0, hb0["transfers"]!!.jsonArray.size)
        val job = receive("edit", 11, 12, open = false)
        assertFalse(job.open)
        assertTrue(agent.isBatchServer(o))
        agent.heartbeat()
        val t = transfer(lastHb(), job.id)
        assertEquals("queued", t["state"]!!.jsonPrimitive.content)
        assertEquals(0, t["arrived"]!!.jsonArray.size)
        assertEquals("0", t["bytes"]!!.jsonPrimitive.content)
        assertTrue(t["photo"] is kotlinx.serialization.json.JsonNull)
        assertTrue("open defaults to true", receive("edit", 13).open)
    }

    @Test fun aJobCopiesEachPhotoOnceReportsTheEndInAHeartbeatThenDoneWithTheLists() = runBlocking {
        agent.take(o, 12)                              // already on the phone: `present`
        val job = receive("edit", 11, 12, 11)          // 11 twice: copied once
        val r = agent.runEditJob(o, job)
        assertEquals(TransferState.DONE, r.state)
        assertEquals(listOf(11L, 12L), r.toOpen.sorted())
        assertEquals(PhotoStatus.READY, agent.record(o, 11)!!.status)
        assertTrue("a photo that was there is left as it is", agent.record(o, 12)!!.status != PhotoStatus.READY)
        assertEquals(1, stub.seen("GET", "/api/photos/11/original").size)
        // the last heartbeat before /done has the final state, with the lists
        val ev = stub.events.toList()
        assertEquals("done ${job.id} done", ev.last())
        assertEquals("hb", ev[ev.size - 2])
        val t = transfer(lastHb(), job.id)
        assertEquals("done", t["state"]!!.jsonPrimitive.content)
        assertEquals(listOf(11L), ids(t["arrived"]!!.jsonArray))
        assertEquals(listOf(12L), ids(t["present"]!!.jsonArray))
        val done = json(stub.jobs.first { it.id == job.id }.doneBody!!)
        assertEquals("done", done["status"]!!.jsonPrimitive.content)
        assertEquals(listOf(11L), ids(done["arrived"]!!.jsonArray))
        // /done answered: the job is no longer listed
        agent.heartbeat()
        assertEquals(0, lastHb()["transfers"]!!.jsonArray.size)
        assertEquals("a photo already held counts for the bar", 2, agent.batches.value[o]!!.onPhone)
    }

    @Test fun aStopBetweenFilesKeepsWhatArrivedAndStartsNothingMore() = runBlocking {
        val job = receive("edit", 11, 12, 13)
        stub.delayMs = 120
        val run = async(Dispatchers.IO) { agent.runEditJob(o, job) }
        waitFor("11 arrived") { agent.record(o, 11)?.status == PhotoStatus.READY }
        stub.cancelJobs += job.id
        stub.delayMs = 0
        agent.heartbeat()
        val r = run.await()
        assertEquals(TransferState.STOPPED, r.state)
        assertTrue("the darkroom is not opened", r.toOpen.isEmpty())
        assertNull(editor.openRaw)
        assertEquals("stopped", stub.jobs.first { it.id == job.id }.status)
        assertTrue("11 arrived and is kept", agent.work.raw(agent.record(o, 11)!!).isFile)
        assertNull("13 never started", agent.record(o, 13))
        assertTrue(agent.photos.value.none { it.status == PhotoStatus.DOWNLOADING })
        val b = agent.batches.value[o]!!
        assertEquals("stopped · ${b.onPhone} of 3 arrived", b.stoppedLine)
    }

    @Test fun aStopInTheMiddleOfAFileDeletesThePartialFileAndTheHalfMadeEntry() = runBlocking {
        agent.take(o, 12)
        val gate = CountDownLatch(1)
        stub.stallPhoto = 11; stub.stallAfter = 100_000; stub.stallGate = gate
        val job = receive("edit", 12, 11, 13)           // 12 is on the phone (present), 11 stalls, 13 is never reached
        val run = async(Dispatchers.IO) { agent.runEditJob(o, job) }
        waitFor("11 under way") { transfer(json(agent.transfers.report(o).let { """{"transfers":$it}""" }), job.id)["photo"]?.jsonPrimitive?.content == "11" && agent.work.part(PhotoRecordFor(11)).length() > 0 }
        stub.cancelJobs += job.id
        agent.heartbeat()                               // carries the Stop
        gate.countDown()
        val r = run.await()
        assertEquals(TransferState.STOPPED, r.state)
        assertTrue(r.toOpen.isEmpty())
        assertNull(agent.record(o, 11))
        assertFalse("partial file and its folder are gone", agent.work.folder(o, 11).exists())
        assertNull(agent.record(o, 13))
        assertTrue(agent.record(o, 12) != null)
        val done = json(stub.jobs.first { it.id == job.id }.doneBody!!)
        assertEquals("stopped", done["status"]!!.jsonPrimitive.content)
        assertEquals(listOf(12L), ids(done["present"]!!.jsonArray))
        assertEquals(0, done["arrived"]!!.jsonArray.size)
        assertEquals("stopped", transfer(lastHb(), job.id)["state"]!!.jsonPrimitive.content)
    }

    @Test fun cancelPhotosLeavesOutAWaitingPhoto() = runBlocking {
        val job = receive("edit", 11, 12)
        stub.cancelPhotos += 12
        agent.heartbeat()
        val r = agent.runEditJob(o, job)
        assertEquals(TransferState.DONE, r.state)
        assertEquals(listOf(11L), r.toOpen)
        assertNull(agent.record(o, 12))
        assertEquals(0, stub.seen("GET", "/api/photos/12/original").size)
        val done = json(stub.jobs.first { it.id == job.id }.doneBody!!)
        assertEquals(listOf(12L), ids(done["skipped"]!!.jsonArray))
        assertEquals(listOf(11L), ids(done["arrived"]!!.jsonArray))
    }

    @Test fun cancelPhotosGivesUpTheCurrentFileAndCarriesOn() = runBlocking {
        val gate = CountDownLatch(1)
        stub.stallPhoto = 11; stub.stallAfter = 100_000; stub.stallGate = gate
        val job = receive("edit", 11, 12)
        val run = async(Dispatchers.IO) { agent.runEditJob(o, job) }
        waitFor("11 under way") { agent.work.part(PhotoRecordFor(11)).length() > 0 }
        stub.cancelPhotos += 11
        agent.heartbeat()
        gate.countDown()
        val r = run.await()
        assertEquals(TransferState.DONE, r.state)
        assertNull(agent.record(o, 11))
        assertFalse(agent.work.folder(o, 11).exists())
        assertEquals(PhotoStatus.READY, agent.record(o, 12)!!.status)
        val done = json(stub.jobs.first { it.id == job.id }.doneBody!!)
        assertEquals(listOf(11L), ids(done["skipped"]!!.jsonArray))
        assertEquals(listOf(12L), ids(done["arrived"]!!.jsonArray))
    }

    @Test fun aJobWaitingOnTheDeviceThatIsStoppedNeverStarts() = runBlocking {
        val job = receive("edit", 11, 12)
        stub.cancelJobs += job.id
        agent.heartbeat()
        assertEquals("stopped", transfer(lastHb().let { agent.heartbeat(); lastHb() }, job.id)["state"]!!.jsonPrimitive.content)
        val r = agent.runEditJob(o, job)
        assertEquals(TransferState.STOPPED, r.state)
        assertEquals(0, stub.seen("GET", "/api/photos/").count { it.path.endsWith("/original") || it.path.endsWith("/checkout") })
        assertEquals("stopped", stub.jobs.first { it.id == job.id }.status)
    }

    @Test fun anUnknownJobIdInCancelIsIgnored() = runBlocking {
        stub.cancelJobs += 999
        val job = receive("edit", 11)
        agent.heartbeat()
        val r = agent.runEditJob(o, job)
        assertEquals(TransferState.DONE, r.state)
        assertEquals("done", stub.jobs.first { it.id == job.id }.status)
    }

    @Test fun cancelIsTheWholeListEachTimeAndOnlyWhatWasActedOnStays() {
        val book = TransferBook()
        book.receive(o, 1, listOf(1)); book.receive(o, 2, listOf(2)); book.receive(o, 3, listOf(3))
        assertTrue(book.begin(o, 1))
        book.applyCancel(o, Cancel(setOf(1, 2), setOf(7)))
        assertEquals("a waiting job in the list ends as stopped at once", TransferState.STOPPED, book.state(o, 2))
        // the next list no longer names 1 (not acted on: it runs on) nor 2 (acted on: stays stopped)
        book.applyCancel(o, Cancel(emptySet(), emptySet()))
        assertFalse(book.shouldStop(o, 1))
        assertTrue(book.shouldStop(o, 2))
        assertFalse(book.shouldSkip(o, 1, 7))
        // a stop that was asked for and noticed stays, whatever the list says afterwards
        book.applyCancel(o, Cancel(setOf(1), setOf(7)))
        assertTrue(book.shouldStop(o, 1)); assertTrue(book.shouldSkip(o, 1, 7))
        book.applyCancel(o, Cancel(emptySet(), emptySet()))
        assertTrue(book.shouldStop(o, 1)); assertTrue(book.shouldSkip(o, 1, 7))
        // an old server: no list, nothing changes
        book.applyCancel(o, null)
        assertTrue(book.shouldStop(o, 1))
        assertFalse(book.shouldStop(o, 3))
    }

    @Test fun anOldServerWithoutCancelGetsTheExchangeAsToday() = runBlocking {
        stub.batch = false
        stub.cancelJobs += 1
        val j = stub.queueJob(id, "edit", listOf(11))
        val jobs = agent.heartbeat()
        assertEquals(listOf(j.id), jobs.map { it.second.id })
        assertFalse(agent.isBatchServer(o))
        assertTrue(agent.batches.value.isEmpty())
        agent.heartbeat()
        assertEquals("nothing is held as a batch", 0, lastHb()["transfers"]!!.jsonArray.size)
        // the job's end is reported the old way
        agent.jobDone(o, jobs[0].second, true)
        assertEquals("done", j.status)
        assertEquals("""{"status":"done"}""", j.doneBody)
    }

    @Test fun anOldServerThatSendsOpenFalseStillGetsNoDarkroom() = runBlocking {
        stub.batch = false
        val job = receive("edit", 11, 12, open = false)
        assertFalse(agent.isBatchServer(o))
        assertTrue("copied, not opened", agent.runsAsBatch(o, job))
        val r = agent.runEditJob(o, job)
        assertEquals(TransferState.DONE, r.state)
        assertEquals(PhotoStatus.READY, agent.record(o, 11)!!.status)
        assertEquals("done", stub.jobs.first { it.id == job.id }.status)
        // an edit job that opens, from an old server, keeps the old path
        assertFalse(agent.runsAsBatch(o, receive("edit", 13)))
    }

    @Test fun aBatchCutShortByLeavingTheAppIsListedAgainAndNothingIsReportedDone() = runBlocking {
        val job = receive("edit", 11, 12)               // received; the app leaves the foreground before it runs
        agent.heartbeat(); agent.heartbeat()            // heartbeats of the next foreground
        assertEquals("queued", transfer(lastHb(), job.id)["state"]!!.jsonPrimitive.content)
        assertEquals("taken", stub.jobs.first { it.id == job.id }.status)
        assertTrue(agent.batches.value[o]!!.running)
        // running again: it ends with the truth
        val r = agent.runEditJob(o, job)
        assertEquals(TransferState.DONE, r.state)
        assertEquals("done", stub.jobs.first { it.id == job.id }.status)
    }

    @Test fun theStopButtonEndsRunningAndWaitingJobsAsStopped() = runBlocking {
        val a = receive("edit", 11)
        val b = receive("edit", 12)
        agent.stopBatches()
        assertEquals(TransferState.STOPPED, agent.runEditJob(o, a).state)
        assertEquals(TransferState.STOPPED, agent.runEditJob(o, b).state)
        assertEquals("stopped", stub.jobs.first { it.id == a.id }.status)
        assertEquals("stopped", stub.jobs.first { it.id == b.id }.status)
        assertEquals("stopped · 0 of 2 arrived", agent.batches.value[o]!!.stoppedLine)
        assertNull(agent.batches.value[o]!!.label)
    }

    @Test fun theBarCountsPhotosOfTheJobsHeld() = runBlocking {
        receive("edit", 11, 12); receive("edit", 13)
        val b = agent.batches.value[o]!!
        assertEquals(3, b.total)
        assertEquals("photo 1 of 3", b.label)
        assertEquals(0f, b.fraction, 0f)
    }

    // the folder of a photo that may not be on the phone (for the partial file)
    @Suppress("FunctionName")
    private fun PhotoRecordFor(id: Long) = PhotoRecord(o, id, "DSCF00$id.RAF", 250_000, 0.0, 0.0, PhotoStatus.DOWNLOADING)
}

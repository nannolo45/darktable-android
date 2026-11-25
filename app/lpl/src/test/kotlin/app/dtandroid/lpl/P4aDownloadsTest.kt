// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Brief P4a deliverables 1 and 5: downloads of originals cut at random points resume with `Range` where they stopped,
 * within one call (a dropped connection, a stalled phone) and across calls and agents (the app killed); the result is
 * checked (size, and the checksum when the server gives one) before it becomes the photo's RAW; a server that ignores
 * `Range` gets a whole download again.
 */
class P4aDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl()
    private val box = MemorySecretBox()
    private lateinit var servers: ServerStore
    private val o get() = stub.origin
    private val lines = ArrayList<String>()
    private val traces = ArrayList<String>()

    private fun client(resumes: Int = 5) = LplClient(o, { null }, settings = ClientSettings(backoffMs = listOf(1, 1), downloadResumes = resumes),
        sleep = {}, log = { synchronized(lines) { lines += it } })

    private fun agent(resumes: Int = 5) = LplAgent(
        servers, WorkStore(File(tmp.root, "work")), FakeEditor(), { "test" },
        clientFor = { origin -> LplClient(origin, { null }, settings = ClientSettings(backoffMs = listOf(1, 1), downloadResumes = resumes), sleep = {}) },
        trace = { synchronized(traces) { traces += it } },
    )

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), box)
        servers.put(o, "Phone", null)
    }

    @After fun stop() = stub.close()

    /** 40 downloads, each cut 1 to 4 times at random points, within one call: every resume starts at the bytes on disk. */
    @Test fun downloadsCutAtRandomPointsResumeWhereTheyStopped() {
        val rnd = Random(4041)
        repeat(40) { round ->
            val size = rnd.nextInt(50_000, 2_500_000)
            val raw = fakeRaw(size, round)
            val id = 1000L + round
            stub.add(StubLpl.Photo(id, "R$round.RAF", raw))
            val cuts = List(rnd.nextInt(1, 5)) { rnd.nextLong(0, size / 3L) }
            synchronized(stub.dropPlan) { stub.dropPlan.clear(); cuts.forEach { stub.dropPlan.add(it) } }
            val part = File(tmp.root, "r$round.part")
            client().downloadOriginal(id, part, size.toLong())
            assertArrayEquals("round $round", raw, part.readBytes())
            val gets = stub.seen("GET", "/api/photos/$id/original")
            assertEquals("round $round: one request per cut and one to finish", cuts.size + 1, gets.size)
            assertNull(gets[0].range)
            // each request asks for the rest after what arrived: never from the start again
            var at = 0L
            for ((k, cut) in cuts.withIndex()) {
                at += cut
                assertEquals("round $round, resume $k", "bytes=$at-", gets[k + 1].range)
                assertTrue(gets[k + 1].ifRange!!.isNotEmpty())
            }
        }
    }

    /** The app is killed in the middle (no resume inside the call); the next run of the app resumes from the partial file. */
    @Test fun aDownloadOfAKilledAppResumesOnTheNextStartAndNothingPartialIsOpened() = runBlocking {
        val raw = fakeRaw(1_800_000, 7)
        stub.add(StubLpl.Photo(21, "DSCF0021.RAF", raw))
        val a1 = agent(resumes = 0)
        stub.dropOriginalAfter = 700_001
        try { a1.take(o, 21); fail("the download should have broken off") } catch (e: LplException) { assertTrue(e.transient) }
        val r1 = a1.record(o, 21)!!
        assertEquals(PhotoStatus.DOWNLOADING, r1.status)
        assertFalse("no RAW from a partial download", a1.work.raw(r1).exists())
        assertEquals(700_001L, a1.work.part(r1).length())

        // a new process: the record is read back, and taking the photo resumes from the bytes on disk
        val a2 = agent()
        assertEquals(PhotoStatus.DOWNLOADING, a2.record(o, 21)!!.status)
        val r2 = a2.take(o, 21)
        assertArrayEquals(raw, a2.work.raw(r2).readBytes())
        assertFalse(a2.work.part(r2).exists())
        assertFalse(File(a2.work.part(r2).path + ".etag").exists())
        assertEquals("bytes=700001-", stub.seen("GET", "/api/photos/21/original").last().range)
    }

    @Test fun aServerThatIgnoresRangeGetsAWholeDownloadAgain() {
        val raw = fakeRaw(900_000, 8)
        stub.add(StubLpl.Photo(22, "IMG_22.CR2", raw))
        stub.ignoreRange = true
        stub.dropOriginalAfter = 300_000
        val part = File(tmp.root, "i.part")
        client().downloadOriginal(22, part, raw.size.toLong())
        assertArrayEquals(raw, part.readBytes())
        val gets = stub.seen("GET", "/api/photos/22/original")
        assertEquals(listOf(200, 200), gets.map { it.status })
        assertEquals("bytes=300000-", gets[1].range)
        assertTrue(lines.joinToString("\n"), lines.any { "Range not answered" in it })
    }

    @Test fun withoutAKnownSizeTheAnswersLengthDecides() {
        val raw = fakeRaw(600_000, 9)
        stub.add(StubLpl.Photo(23, "IMG_23.CR2", raw))
        stub.dropOriginalAfter = 250_000
        val part = File(tmp.root, "u.part")
        client().downloadOriginal(23, part, -1)
        assertArrayEquals(raw, part.readBytes())
        assertEquals("bytes=250000-", stub.seen("GET", "/api/photos/23/original").last().range)
    }

    @Test fun aChecksumThatDoesNotMatchIsDownloadedAgainOnceThenRefused() = runBlocking {
        val raw = fakeRaw(400_000, 10)
        stub.add(StubLpl.Photo(24, "IMG_24.CR2", raw))
        stub.add(StubLpl.Photo(25, "IMG_25.CR2", fakeRaw(300_000, 11)))
        stub.checksums = true
        val a = agent()
        // one bad transfer: noticed, downloaded again, fine
        stub.corruptNext = 1
        val r = a.take(o, 24)
        assertArrayEquals(raw, a.work.raw(r).readBytes())
        assertTrue(traces.joinToString(), traces.any { "failed its check (checksum" in it && "downloading it again" in it })
        assertEquals(2, stub.seen("GET", "/api/photos/24/original").size)
        // two bad transfers in a row: refused, nothing becomes the RAW
        stub.corruptNext = 2
        try { a.take(o, 25); fail("expected the check to refuse it") } catch (e: LplException) { assertEquals(LplFailure.BAD_ANSWER, e.failure) }
        val r25 = a.record(o, 25)!!
        assertFalse(a.work.raw(r25).exists())
        assertFalse(a.work.part(r25).exists())
    }

    @Test fun theCheckReadsSizeAndChecksum() {
        val f = File(tmp.root, "c.bin").apply { writeBytes(fakeRaw(1000, 1)) }
        assertNull(DownloadCheck.problem(f, 1000, null))
        assertNull(DownloadCheck.problem(f, 1000, DownloadCheck.sha256(f)))
        assertEquals("1000 bytes instead of 1001", DownloadCheck.problem(f, 1001, null))
        assertTrue(DownloadCheck.problem(f, 1000, "0".repeat(64))!!.startsWith("checksum"))
        assertEquals("the file is missing", DownloadCheck.problem(File(tmp.root, "none"), 10, null))
    }
}

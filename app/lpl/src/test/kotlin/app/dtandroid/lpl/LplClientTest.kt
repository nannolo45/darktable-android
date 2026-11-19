// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The client against the stub server: every call of PLAN.md 4.1 and the failure cases of brief P2e item 9. */
class LplClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl()
    private val pw = "pw-for-tests-only"
    private val lines = ArrayList<String>()
    private fun client(token: String? = null, settings: ClientSettings = ClientSettings(backoffMs = listOf(1, 1))) =
        LplClient(stub.origin, { token }, settings = settings, sleep = {}, log = { synchronized(lines) { lines += it } })

    @After fun stop() = stub.close()

    @Test fun heartbeatSendsTheAgentStateAndReturnsJobsAddressedToThisDevice() {
        stub.queueJob("android-x", "sync", listOf(7))
        stub.queueJob(null, "edit", listOf(8))           // unaddressed: for a PC, never for the phone
        stub.queueJob("pc-1", "edit", listOf(9))          // another device
        val state = AgentState(listOf(WorkingSetLine(7, "A.RAF", PhotoStatus.DIRTY, 1795070000.5, 0.0, "")), true, true, "0.1 / dt 5.7", "")
        val a = client().heartbeat("android-x", "Phone", state)
        assertEquals(listOf(Job(101, "sync", listOf(7), false)), a.jobs)
        assertTrue(a.serverTime > 1.7e9)
        val body = stub.heartbeats["android-x"]!!
        for (part in listOf("\"os\":\"android\"", "\"name\":\"Phone\"", "\"status\":\"dirty\"", "\"checkout_ts\":1795070000.5",
                "\"darktable_running\":true", "\"bridge_alive\":true", "\"version\":\"0.1 / dt 5.7\"")) {
            assertTrue("$part in $body", body.contains(part))
        }
        // handed out once
        assertEquals(emptyList<Job>(), client().heartbeat("android-x", "Phone", state).jobs)
        client().jobDone(101, ok = false)
        assertEquals("failed", stub.jobs.first { it.id == 101L }.status)
    }

    @Test fun checkoutOriginalXmpAndPreview() {
        val raw = fakeRaw(300_000)
        stub.add(StubLpl.Photo(5, "DSCF0005.RAF", raw, rating = 3))
        val c = client()
        val co = c.checkout(5)
        assertEquals("DSCF0005.RAF", co.filename)
        assertEquals(300_000L, co.size)
        val part = tmp.newFile("o.part").also { it.delete() }
        var last = Progress(0, 0)
        c.downloadOriginal(5, part, co.size, progress = { d, t -> last = Progress(d, t) })
        assertArrayEquals(raw, part.readBytes())
        assertEquals(Progress(300_000, 300_000), last)
        assertTrue(c.getXmp(5).decodeToString().contains("xmp:Rating=\"3\""))
        val ans = c.postXmp(5, StubLpl.lplXmp(4), co.serverTime)
        assertFalse(ans.merged)
        assertEquals(LplClient.formatTs(co.serverTime), stub.photos[5]!!.lastCheckoutTs)
        c.postPreview(5, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2))
        assertEquals(1, stub.photos[5]!!.previewPosts)
    }

    @Test fun anInterruptedDownloadResumesWithRangeAndIfRange() {
        val raw = fakeRaw(1_000_000, 3)
        stub.add(StubLpl.Photo(6, "IMG_6.CR2", raw))
        stub.dropOriginalAfter = 400_000
        val part = tmp.newFile("p.part").also { it.delete() }
        client().downloadOriginal(6, part, raw.size.toLong())
        assertArrayEquals(raw, part.readBytes())
        val gets = stub.seen("GET", "/api/photos/6/original")
        assertEquals(2, gets.size)
        assertNull(gets[0].range)
        assertEquals("bytes=400000-", gets[1].range)
        assertTrue("If-Range sent: ${gets[1].ifRange}", gets[1].ifRange!!.isNotEmpty())
    }

    @Test fun aDownloadInterruptedAcrossCallsResumesFromTheBytesOnDisk() {
        val raw = fakeRaw(500_000, 4)
        stub.add(StubLpl.Photo(7, "IMG_7.CR2", raw))
        val part = tmp.newFile("q.part").also { it.delete() }
        // first call: the connection breaks and no resume is allowed (as if the app was killed)
        stub.dropOriginalAfter = 123_456
        try {
            client(settings = ClientSettings(downloadResumes = 0, backoffMs = listOf(1))).downloadOriginal(7, part, raw.size.toLong())
            fail("expected a broken download")
        } catch (e: LplException) {
            assertTrue(e.transient)
        }
        assertEquals(123_456L, part.length())
        client().downloadOriginal(7, part, raw.size.toLong())
        assertArrayEquals(raw, part.readBytes())
        assertEquals("bytes=123456-", stub.seen("GET", "/api/photos/7/original").last().range)
    }

    @Test fun aChangedFileOnTheServerIsNotGluedOntoOldBytes() {
        val old = fakeRaw(200_000, 5)
        val ph = stub.add(StubLpl.Photo(8, "IMG_8.CR2", old))
        val part = tmp.newFile("r.part").also { it.delete() }
        stub.dropOriginalAfter = 50_000
        try { client(settings = ClientSettings(downloadResumes = 0)).downloadOriginal(8, part, old.size.toLong()) } catch (e: LplException) {}
        // the NAS file changes (same size): If-Range no longer matches, the server sends the whole new file
        val new = fakeRaw(200_000, 6)
        ph.bytes = new; ph.mtime += 10
        client().downloadOriginal(8, part, new.size.toLong())
        assertArrayEquals(new, part.readBytes())
        assertEquals(200, stub.seen.last { it.path.endsWith("/original") }.status)
    }

    @Test fun mergedAnswerIsReturned() {
        val ph = stub.add(StubLpl.Photo(9, "A.RAF", fakeRaw(10), rating = 5))
        val co = client().checkout(9)
        ph.metaMtime = co.serverTime + 5   // rating changed in LPL after the checkout
        val ans = client().postXmp(9, StubLpl.withRating(StubLpl.lplXmp(1), 1), co.serverTime)
        assertTrue(ans.merged)
        assertTrue(ans.xmp.decodeToString().contains("xmp:Rating=\"5\""))
    }

    @Test fun wrongPasswordIsUnauthorizedAndThePasswordAppearsNowhere() {
        stub.password = pw
        stub.add(StubLpl.Photo(1, "A.RAF", fakeRaw(10)))
        val wrong = "not-the-password"
        try {
            client(wrong).checkout(1); fail()
        } catch (e: LplException) {
            assertEquals(LplFailure.UNAUTHORIZED, e.failure)
            assertEquals("wrong password for LightPhotoLibrary", e.userText)
            assertFalse(e.message!!.contains(wrong))
            assertFalse(e.stackTraceToString().contains(wrong))
        }
        assertEquals("A.RAF", client(pw).checkout(1).filename)
        assertEquals(pw, stub.seen.last().token)
        synchronized(lines) { for (l in lines) { assertFalse(l.contains(pw)); assertFalse(l.contains(wrong)) } }
        // the check when adding the server
        assertEquals(ServerCheckResult.Lpl(true, false, null), client().check(wrong))
        assertEquals(ServerCheckResult.Lpl(true, true, 1), client().check(pw))
    }

    @Test fun serverGoneIsTransientAndRetried() {
        stub.add(StubLpl.Photo(1, "A.RAF", fakeRaw(10)))
        stub.stop()
        val t0 = System.nanoTime()
        try {
            client().checkout(1); fail()
        } catch (e: LplException) {
            assertEquals(LplFailure.UNREACHABLE, e.failure)
            assertTrue(e.transient)
            assertEquals("LightPhotoLibrary is not reachable right now", e.userText)
        }
        assertTrue((System.nanoTime() - t0) < 10_000_000_000L)
        assertEquals(3, synchronized(lines) { lines.count { it.startsWith("GET /api/photos/1/checkout") } })
        assertTrue(client().check(null) is ServerCheckResult.Failed)
        // one dropped connection: the GET is repeated and succeeds
        stub.start()
        stub.dropNext = 1
        assertEquals("A.RAF", client().checkout(1).filename)
    }

    @Test fun photoGoneIsNotFound() {
        try { client().checkout(404); fail() } catch (e: LplException) {
            assertEquals(LplFailure.NOT_FOUND, e.failure)
            assertFalse(e.transient)
            assertEquals("this photo is gone from the library", e.userText)
        }
    }

    @Test fun redirectsAreNeverFollowedSoTheTokenStaysWithTheServer() {
        stub.redirect = true
        try { client(pw).checkout(1); fail() } catch (e: LplException) {
            assertEquals(LplFailure.REDIRECT, e.failure)
        }
        assertEquals(1, stub.seen.size)
        assertEquals(ServerCheckResult.NotLpl, client().check(null))
    }

    @Test fun notLplIsRecognised() {
        // a server that answers but has no /api/auth: the stub's 404 for an unknown path
        val other = object : HttpTransport {
            override fun <T> exchange(req: HttpRequest, handle: (HttpResponse) -> T): T =
                handle(HttpResponse(200, mapOf("content-type" to listOf("text/html")), "<html></html>".byteInputStream()))
        }
        assertEquals(ServerCheckResult.NotLpl, LplClient(stub.origin, { null }, other).check(null))
        assertEquals(ServerCheckResult.Lpl(false, true, 0), client().check(null))
    }

    @Test fun checkoutTsIsSentWithFullPrecision() {
        assertEquals("1795070000.5", LplClient.formatTs(1795070000.5))
        assertEquals("1795070000.123456", LplClient.formatTs(1795070000.123456))
        assertEquals("0.0", LplClient.formatTs(0.0))
    }
}

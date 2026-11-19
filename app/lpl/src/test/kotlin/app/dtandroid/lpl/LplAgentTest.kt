// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The agent's rules (brief P2e items 2 to 6) against the stub server, with a fake darkroom. */
class LplAgentTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl()
    private val editor = FakeEditor()
    private val box = MemorySecretBox()
    private lateinit var servers: ServerStore
    private lateinit var agent: LplAgent
    private val o get() = stub.origin

    private fun newAgent(): LplAgent = LplAgent(
        servers, WorkStore(File(tmp.root, "work")), editor, { "test / darktable test" },
        clientFor = { origin -> LplClient(origin, { servers.password(origin) }, settings = ClientSettings(backoffMs = listOf(1, 1)), sleep = {}) },
    )

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), box)
        servers.put(o, "Phone", null)
        agent = newAgent()
        stub.add(StubLpl.Photo(11, "DSCF0011.RAF", fakeRaw(250_000, 11), rating = 2))
        stub.add(StubLpl.Photo(12, "DSCF0012.RAF", fakeRaw(260_000, 12)))
    }

    @After fun stop() = stub.close()

    @Test fun takeDownloadsIntoThePhotosOwnFolderWithItsName() = runBlocking {
        val r = agent.take(o, 11)
        val dir = File(tmp.root, "work/${o.key}/11")
        assertEquals(File(dir, "DSCF0011.RAF"), agent.work.raw(r))
        assertArrayEquals(stub.photos[11]!!.bytes, agent.work.raw(r).readBytes())
        // (checked on the permission bits: the build machine runs as root, for whom canWrite() is always true)
        assertFalse("the RAW is read-only on disk", java.nio.file.Files.getPosixFilePermissions(agent.work.raw(r).toPath())
            .contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
        assertTrue(File(dir, "DSCF0011.RAF.xmp").readText().contains("xmp:Rating=\"2\""))
        assertTrue(File(dir, "downloaded.xmp").isFile)
        assertTrue(File(dir, "state.json").isFile)
        assertEquals(PhotoStatus.OPENING, r.status)
        assertTrue(r.checkoutTs > 1.7e9)
        assertFalse(agent.isDirty(r))
    }

    @Test fun anUnknownServerIsNeverContacted() = runBlocking {
        val other = StubLpl()
        try {
            agent.take(other.origin, 11); fail()
        } catch (e: ServerNotAddedException) {
            assertEquals(other.origin, e.origin)
        }
        assertEquals(0, other.seen.size)
        other.close()
    }

    @Test fun editSyncPreviewInThatOrder() = runBlocking {
        var r = agent.take(o, 11)
        val raw = agent.work.raw(r); val xmp = agent.work.xmp(r)
        editor.openRaw = raw; agent.opened(o, 11)
        editor.edit(xmp)
        editor.openRaw = null; agent.left(o, 11)
        assertEquals(PhotoStatus.DIRTY, agent.record(o, 11)!!.status)
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 11))
        r = agent.record(o, 11)!!
        assertEquals(PhotoStatus.SYNCED, r.status)
        assertArrayEquals(xmp.readBytes(), stub.photos[11]!!.xmp)
        assertEquals(LplClient.formatTs(r.checkoutTs), stub.photos[11]!!.lastCheckoutTs)
        assertEquals(1, stub.photos[11]!!.previewPosts)
        assertFalse(r.previewPending)
        assertFalse("backup kept only until synced", File(agent.work.folder(o, 11), "downloaded.xmp").exists())
        val calls = stub.seen.filter { it.method == "POST" }.map { it.path.substringBefore('?') }
        assertEquals(listOf("/api/photos/11/xmp", "/api/photos/11/preview"), calls)
        // nothing changed: a second sync sends nothing
        agent.sync(o, 11)
        assertEquals(1, stub.photos[11]!!.xmpPosts)
        // the RAW never went up
        assertTrue(stub.seen.none { it.method == "POST" && it.bodyBytes >= 250_000 })
    }

    @Test fun mergedAnswerReplacesTheLocalXmpAndReloadsBeforeThePreview() = runBlocking {
        val r = agent.take(o, 11)
        val raw = agent.work.raw(r); val xmp = agent.work.xmp(r)
        editor.openRaw = raw
        editor.edit(xmp)
        stub.photos[11]!!.rating = 5
        stub.photos[11]!!.metaMtime = r.checkoutTs + 1
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 11))
        assertTrue(xmp.readText().contains("xmp:Rating=\"5\""))
        assertEquals(listOf(xmp), editor.reloads.toList())
        assertEquals(PhotoStatus.EDITING, agent.record(o, 11)!!.status)
        assertFalse(agent.isDirty(agent.record(o, 11)!!))
        assertEquals(listOf(raw), editor.renders.toList())
    }

    @Test fun offlineEditIsDirtyAndSyncsWhenTheServerIsBack() = runBlocking {
        val r = agent.take(o, 11)
        editor.edit(agent.work.xmp(r))
        stub.stop()
        assertEquals(SyncOutcome.WAITING, agent.sync(o, 11))
        val d = agent.record(o, 11)!!
        assertEquals(PhotoStatus.DIRTY, d.status)
        assertEquals("LightPhotoLibrary is not reachable right now", d.error)
        assertFalse(agent.syncPending())
        stub.start()
        assertTrue(agent.syncPending())
        assertEquals(PhotoStatus.SYNCED, agent.record(o, 11)!!.status)
        assertEquals(1, stub.photos[11]!!.xmpPosts)
    }

    @Test fun previewWaitsWhileTheEngineIsBusyAndFollowsLater() = runBlocking {
        val r = agent.take(o, 11)
        editor.edit(agent.work.xmp(r))
        editor.busy = true
        agent.sync(o, 11)
        assertTrue(agent.record(o, 11)!!.previewPending)
        assertEquals(PhotoStatus.SYNCED, agent.record(o, 11)!!.status)
        assertEquals(0, stub.photos[11]!!.previewPosts)
        editor.busy = false
        assertTrue(agent.syncPending())
        assertEquals(1, stub.photos[11]!!.previewPosts)
        assertEquals(1, stub.photos[11]!!.xmpPosts)
    }

    @Test fun readOnlyPhotoIsNeverPostedBack() = runBlocking {
        stub.photos[12]!!.xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="r"><!-- newer-module --></rdf:RDF></x:xmpmeta>""".toByteArray()
        val r = agent.take(o, 12)
        assertTrue(r.readOnly)
        // even if the file changed somehow, nothing goes back
        agent.work.xmp(r).appendText(" ")
        assertEquals(SyncOutcome.READ_ONLY, agent.sync(o, 12, force = true))
        assertTrue(agent.syncPending())
        assertTrue(stub.seen.none { it.method == "POST" && it.path.startsWith("/api/photos/12") })
    }

    @Test fun processDeathAtAnyPointIsSurvived() = runBlocking {
        // 1. killed during the download: the next take resumes from the bytes on disk
        stub.dropOriginalAfter = 100_000
        val strict = LplAgent(servers, WorkStore(File(tmp.root, "work")), editor, { "v" },
            clientFor = { origin -> LplClient(origin, { null }, settings = ClientSettings(downloadResumes = 0, backoffMs = listOf(1)), sleep = {}) })
        try { strict.take(o, 12); fail() } catch (e: LplException) { assertTrue(e.transient) }
        assertEquals(PhotoStatus.DOWNLOADING, strict.record(o, 12)!!.status)
        val restarted = newAgent()
        assertEquals(PhotoStatus.DOWNLOADING, restarted.record(o, 12)!!.status)
        val r = restarted.take(o, 12)
        assertArrayEquals(stub.photos[12]!!.bytes, restarted.work.raw(r).readBytes())
        assertEquals("bytes=100000-", stub.seen("GET", "/api/photos/12/original").last().range)
        // 2. killed after the edit was written, before it was posted: dirty after the restart
        restarted.opened(o, 12)
        editor.edit(restarted.work.xmp(r))
        val again = newAgent()
        assertEquals(PhotoStatus.DIRTY, again.record(o, 12)!!.status)
        // 3. killed while syncing: the state file says syncing; after the restart it is dirty and syncs
        again.work.save(again.record(o, 12)!!.copy(status = PhotoStatus.SYNCING))
        val third = newAgent()
        assertEquals(PhotoStatus.DIRTY, third.record(o, 12)!!.status)
        assertTrue(third.syncPending())
        assertEquals(PhotoStatus.SYNCED, third.record(o, 12)!!.status)
    }

    @Test fun aHeldCleanPhotoIsRefreshedButAnUnsyncedEditIsNeverReplaced() = runBlocking {
        val r = agent.take(o, 11)
        agent.left(o, 11)
        // edited on a PC meanwhile
        stub.photos[11]!!.xmp = StubLpl.withRating(StubLpl.lplXmp(4), 4)
        agent.take(o, 11)
        assertTrue(agent.work.xmp(r).readText().contains("xmp:Rating=\"4\""))
        // now an edit on the phone that LPL does not have: a new hand-off keeps it
        editor.edit(agent.work.xmp(r))
        val mine = agent.work.xmp(r).readText()
        stub.photos[11]!!.xmp = StubLpl.lplXmp(1)
        agent.take(o, 11)
        assertEquals(mine, agent.work.xmp(r).readText())
        // offline: the local copy opens
        stub.stop()
        assertEquals(11L, agent.take(o, 11).id)
    }

    @Test fun removeAsksFirstWhenAnEditIsUnsynced() = runBlocking {
        val r = agent.take(o, 11)
        editor.edit(agent.work.xmp(r))
        assertEquals(LplAgent.RemoveResult.Unsynced, agent.remove(o, 11))
        editor.openRaw = agent.work.raw(r)
        assertEquals(LplAgent.RemoveResult.OpenInDarkroom, agent.remove(o, 11, evenUnsynced = true))
        editor.openRaw = null
        assertEquals(LplAgent.RemoveResult.Removed, agent.remove(o, 11, evenUnsynced = true))
        assertFalse(agent.work.folder(o, 11).exists())
        assertTrue(agent.photos.value.isEmpty())
    }

    @Test fun heartbeatReportsTheWorkingSetAndJobsAreCarriedOut() = runBlocking {
        val a = agent.take(o, 11)
        agent.take(o, 12)
        editor.edit(agent.work.xmp(a))
        agent.left(o, 11)
        editor.openRaw = agent.work.raw(agent.record(o, 12)!!); agent.opened(o, 12)
        val sync = stub.queueJob(servers.installId, "sync", listOf(11))
        val release = stub.queueJob(servers.installId, "release", emptyList(), all = true)
        stub.queueJob(null, "edit", listOf(11))       // for a PC
        val jobs = agent.heartbeat()
        val hb = stub.heartbeats[servers.installId]!!
        assertTrue(hb, hb.contains("\"os\":\"android\"") && hb.contains("\"name\":\"Phone\""))
        assertTrue(hb, hb.contains("\"id\":11,\"filename\":\"DSCF0011.RAF\",\"status\":\"dirty\""))
        assertTrue(hb, hb.contains("\"id\":12,\"filename\":\"DSCF0012.RAF\",\"status\":\"editing\""))
        assertTrue(hb, hb.contains("\"darktable_running\":true") && hb.contains("\"version\":\"test / darktable test\""))
        assertEquals(listOf(sync.id, release.id), jobs.map { it.second.id })
        for ((origin, j) in jobs) agent.jobDone(origin, j, agent.runSyncOrRelease(origin, j))
        assertEquals("done", sync.status)
        // release of everything: 11 is synced and removed; 12 is open in the darkroom, so the job failed
        assertEquals("failed", release.status)
        assertEquals(listOf(12L), agent.photos.value.map { it.id })
        assertTrue(agent.heartbeat().isEmpty())
    }

    @Test fun wrongPasswordLeavesTheEditWaiting() = runBlocking {
        stub.password = "right-one"
        servers.put(o, "Phone", "right-one")
        agent.resetClients()
        val r = agent.take(o, 11)
        editor.edit(agent.work.xmp(r))
        servers.put(o, "Phone", "wrong-one")
        agent.resetClients()
        assertEquals(SyncOutcome.WAITING, agent.sync(o, 11))
        assertEquals("wrong password for LightPhotoLibrary", agent.record(o, 11)!!.error)
        assertEquals(LplFailure.UNAUTHORIZED, agent.lastError.value[o]!!.failure)
    }

    @Test fun photoGoneFromTheLibraryIsAnError() = runBlocking {
        val r = agent.take(o, 11)
        editor.edit(agent.work.xmp(r))
        stub.photos.remove(11L)
        assertEquals(SyncOutcome.FAILED, agent.sync(o, 11))
        assertEquals(PhotoStatus.ERROR, agent.record(o, 11)!!.status)
        assertEquals("this photo is gone from the library", agent.record(o, 11)!!.error)
        try { agent.take(o, 99); fail() } catch (e: LplException) { assertEquals(LplFailure.NOT_FOUND, e.failure) }
    }

    @Test fun namesForPlaceholdersComeFromCheckoutWithoutDownloading() = runBlocking {
        val n = agent.names(o, listOf(12, 11, 77))
        assertEquals(mapOf(12L to "DSCF0012.RAF", 11L to "DSCF0011.RAF"), n)
        assertTrue(stub.seen.none { it.path.endsWith("/original") })
        assertTrue(agent.photos.value.isEmpty())
    }

    @Test fun serverStoreKeepsThePasswordSealedAndTheInstallIdOnce() {
        servers.put(o, "  Phone\u0000 ", "secret-for-test")
        val text = File(tmp.root, "lpl/servers.json").readText()
        assertFalse(text.contains("secret-for-test"))
        assertTrue(text.contains("\"device_name\":\"Phone\""))
        val id = servers.installId
        assertTrue(id.matches(Regex("android-[0-9a-f-]{36}")))
        val reread = ServerStore(File(tmp.root, "lpl"), box)
        assertEquals(id, reread.installId)
        assertEquals("secret-for-test", reread.password(o))
        assertEquals(listOf(ServerEntry(o, "Phone", true)), reread.list())
        // another key (a restore onto another phone): the password must be entered again
        val other = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        try { other.password(o); fail() } catch (e: IllegalStateException) { assertFalse(e.message!!.contains("secret")) }
        assertNotEquals(null, other.get(o))
    }
}

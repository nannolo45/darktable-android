// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brief P2i against the stub server: deliverable 5 (the "sync automatically" switch) and deliverable 7, second
 * point (a late write into a photo's folder never brings back a photo a `release` job removed).
 */
class P2iLplTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl()
    private val editor = FakeEditor()
    private lateinit var servers: ServerStore
    private lateinit var settings: SyncSettings
    private lateinit var agent: LplAgent
    private val traced = java.util.Collections.synchronizedList(ArrayList<String>())
    private val o get() = stub.origin

    private fun newAgent() = LplAgent(
        servers, WorkStore(File(tmp.root, "lpl/work")), editor, { "test" },
        clientFor = { origin -> LplClient(origin, { servers.password(origin) }, settings = ClientSettings(backoffMs = listOf(1, 1)), sleep = {}) },
        settings = settings, trace = { traced += it },
    )

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        servers.put(o, "Phone", null)
        settings = SyncSettings(File(tmp.root, "lpl/settings.json"))
        agent = newAgent()
        stub.add(StubLpl.Photo(11, "IMG_0011.CR2", fakeRaw(200_000, 11), rating = 2))
        stub.add(StubLpl.Photo(12, "IMG_0012.CR2", fakeRaw(200_000, 12), rating = 1))
    }

    @After fun stop() = stub.close()

    /** Open, edit and leave the photo in the darkroom, as the app does ("done"). */
    private fun editAndLeave(id: Long) {
        val r = agent.record(o, id)!!
        editor.openRaw = agent.work.raw(r); agent.opened(o, id)
        editor.edit(agent.work.xmp(r))
        editor.openRaw = null; agent.left(o, id)
    }

    // ------------------------------------------------------------------ deliverable 5

    @Test fun theSwitchIsOnByDefaultAndKept() {
        assertTrue(settings.autoSync.value)
        assertTrue(agent.autoSync)
        settings.setAutoSync(false)
        assertFalse(SyncSettings(File(tmp.root, "lpl/settings.json")).autoSync.value)
        settings.setAutoSync(true)
        assertTrue(SyncSettings(File(tmp.root, "lpl/settings.json")).autoSync.value)
        // an agent built without a settings file syncs automatically, as before P2i
        assertTrue(SyncSettings.inMemory().autoSync.value)
    }

    @Test fun offNothingIsSentByItself() = runBlocking {
        settings.setAutoSync(false)
        // opening a photo that is not on the phone still downloads it (the user asked for it)
        agent.take(o, 11)
        assertTrue(agent.work.hasRaw(agent.record(o, 11)!!))
        assertFalse("nothing downloaded ahead", agent.fetchAhead)
        editAndLeave(11)
        // "done": kept on the phone
        assertNull(agent.syncAfterDone(o, 11))
        // the background job, the app coming to the front: nothing sent
        assertNull(agent.syncPendingAutomatically())
        assertEquals(0, stub.photos[11]!!.xmpPosts)
        assertTrue(stub.seen("POST", "/api/photos/11/").isEmpty())
        assertEquals(PhotoStatus.DIRTY, agent.record(o, 11)!!.status)
        assertEquals(listOf(11L), agent.pending().map { it.id })
        // the heartbeat goes on: the photo is reported edited and not synced, and the device says why
        agent.heartbeat()
        val hb = stub.heartbeats[servers.installId]!!
        assertTrue(hb, hb.contains("\"status\":\"dirty\""))
        assertTrue(hb, hb.contains(SyncSettings.OFF_TEXT))
        // "sync now" works as before
        assertTrue(agent.syncPending())
        assertEquals(1, stub.photos[11]!!.xmpPosts)
        assertEquals(PhotoStatus.SYNCED, agent.record(o, 11)!!.status)
        assertTrue(stub.photos[11]!!.xmp!!.decodeToString().contains("<!-- edit 1 -->"))
        // one photo's "sync now" too
        editAndLeave(11)
        assertNull(agent.syncAfterDone(o, 11))
        assertEquals(1, stub.photos[11]!!.xmpPosts)
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 11))
        assertEquals(2, stub.photos[11]!!.xmpPosts)
    }

    @Test fun onTheEditIsSentAfterDoneAsBefore() = runBlocking {
        agent.take(o, 11)
        editAndLeave(11)
        assertEquals(SyncOutcome.SYNCED, agent.syncAfterDone(o, 11))
        assertEquals(1, stub.photos[11]!!.xmpPosts)
        assertEquals(true, agent.syncPendingAutomatically())
        assertTrue(agent.fetchAhead)
        agent.heartbeat()
        assertFalse(stub.heartbeats[servers.installId]!!.contains(SyncSettings.OFF_TEXT))
    }

    @Test fun offJobsFromLightPhotoLibraryAreStillCarriedOut() = runBlocking {
        settings.setAutoSync(false)
        agent.take(o, 11); agent.take(o, 12)
        editAndLeave(11); editAndLeave(12)
        val id = servers.installId
        val sync = stub.queueJob(id, "sync", listOf(12))
        val release = stub.queueJob(id, "release", listOf(11))
        val jobs = agent.heartbeat()
        assertEquals(2, jobs.size)
        for ((origin, job) in jobs) agent.jobDone(origin, job, agent.runSyncOrRelease(origin, job))
        assertEquals("done", sync.status)
        assertEquals("done", release.status)
        assertEquals(1, stub.photos[11]!!.xmpPosts)
        assertEquals(1, stub.photos[12]!!.xmpPosts)
        assertNull("released: gone from the phone", agent.record(o, 11))
        assertFalse(agent.work.folder(o, 11).exists())
        assertEquals(PhotoStatus.SYNCED, agent.record(o, 12)!!.status)
    }

    // ------------------------------------------------------------------ deliverable 7, second point

    /**
     * What happened in P2h's l4 failure, as far as it can be shown here: the background thumbnail pass rendered
     * a photo while a `release` job removed it, then stored the thumbnail. A plain atomic write (what the app did
     * before P2i) makes the photo's folder again; [LplAgent.writeIfHeld] drops the write.
     */
    @Test fun aLateThumbnailDoesNotBringARemovedPhotoBack() = runBlocking {
        agent.take(o, 11)
        val dir = agent.work.folder(o, 11)
        val thumb = File(dir, "thumb.rgba")
        // while held: written
        assertTrue(agent.writeIfHeld(o, 11, "thumbnail") { d -> atomicWrite(File(d, "thumb.rgba"), byteArrayOf(1, 2, 3)) })
        assertTrue(thumb.isFile)

        val release = stub.queueJob(servers.installId, "release", listOf(11))
        val (origin, job) = agent.heartbeat().single()
        assertTrue(agent.runSyncOrRelease(origin, job))
        agent.jobDone(origin, job, true)
        assertEquals("done", release.status)
        assertFalse(dir.exists())

        // the mechanism: the write as before P2i brings the folder back
        atomicWrite(thumb, byteArrayOf(4, 5, 6))
        assertTrue("a plain atomic write makes the removed photo's folder again", dir.isDirectory)
        dir.deleteRecursively()

        // with the guard: dropped, the removal wins
        assertFalse(agent.writeIfHeld(o, 11, "thumbnail") { d -> atomicWrite(File(d, "thumb.rgba"), byteArrayOf(4, 5, 6)) })
        assertFalse("released: gone from the phone", dir.exists())
        assertNull(agent.record(o, 11))
        assertTrue(traced.toString(), traced.any { it == "lpl: thumbnail of photo 11 not written: the photo is no longer on the phone" })
    }

    /** The race itself: thumbnails stored over and over from another thread while the photo is removed. */
    @Test fun thumbnailsStoredDuringARemovalNeverBringTheFolderBack() = runBlocking {
        repeat(20) { round ->
            val id = 11L
            agent.take(o, id)
            val dir = agent.work.folder(o, id)
            val stop = AtomicBoolean(false)
            val written = AtomicInteger()
            val writer = Thread {
                while (!stop.get()) {
                    if (agent.writeIfHeld(o, id, "thumbnail") { d -> atomicWrite(File(d, "thumb.rgba"), ByteArray(4096)) }) written.incrementAndGet()
                }
            }
            writer.start()
            Thread.sleep(2)
            assertEquals(LplAgent.RemoveResult.Removed, agent.remove(o, id))
            Thread.sleep(5)
            stop.set(true)
            writer.join()
            assertFalse("round $round: the folder came back (${written.get()} thumbnails written)", dir.exists())
            assertNull(agent.record(o, id))
        }
        assertNotNull(traced.firstOrNull { it.startsWith("lpl: 11/IMG_0011.CR2 removed from the phone") })
    }
}

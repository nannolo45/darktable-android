// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Several things writing the same file at once (P2e follow-up: emulator run 44558499134 crashed with
 * "cannot replace state.json" when the darkroom left a photo while its sync was writing the same state).
 */
class ConcurrencyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl()
    private val editor = FakeEditor()
    private lateinit var servers: ServerStore
    private lateinit var agent: LplAgent
    private val o get() = stub.origin

    private fun newAgent() = LplAgent(servers, WorkStore(File(tmp.root, "work")), editor, { "v" },
        clientFor = { origin -> LplClient(origin, { servers.password(origin) }, settings = ClientSettings(backoffMs = listOf(1, 1)), sleep = {}) })

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        servers.put(o, "Phone", null)
        agent = newAgent()
        stub.add(StubLpl.Photo(11, "DSCF0011.RAF", fakeRaw(50_000, 11)))
    }

    @After fun stop() = stub.close()

    /** Run [n] threads that each call [body] [times] times, all starting together; returns what they threw. */
    private fun hammer(n: Int, times: Int, body: (thread: Int, i: Int) -> Unit): List<Throwable> {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val go = CountDownLatch(1)
        val ts = (0 until n).map { t -> thread { go.await(); repeat(times) { i -> try { body(t, i) } catch (e: Throwable) { errors += e } } } }
        go.countDown()
        ts.forEach { it.join() }
        return errors.toList()
    }

    @Test fun onePhotosStateFromManyThreads() = runBlocking {
        val r = agent.take(o, 11)
        val raw = agent.work.raw(r); val xmp = agent.work.xmp(r)
        // the darkroom opening and leaving the photo, edits being written, syncs and their results, all at once
        val errors = hammer(8, 150) { t, i ->
            when ((t + i) % 5) {
                0 -> agent.opened(o, 11)
                1 -> agent.left(o, 11)
                2 -> runBlocking { agent.sync(o, 11) }
                3 -> synchronized(xmp) { editor.edit(xmp) }
                else -> agent.state(o)
            }
        }
        assertEquals("nothing thrown: ${errors.take(3).map { it.toString() }}", emptyList<Throwable>(), errors)
        // what is on disk is a complete state, the same as in memory, and no temporary file is left over
        val inMemory = agent.record(o, 11)!!
        val onDisk = WorkStore(File(tmp.root, "work")).loadAll().single()
        assertEquals(inMemory, onDisk)
        assertEquals(listOf("DSCF0011.RAF", "DSCF0011.RAF.xmp", "state.json"),
            agent.work.folder(o, 11).list()!!.filter { !it.endsWith(".xmp") || it == "DSCF0011.RAF.xmp" }.filter { it != "downloaded.xmp" && it != "preview.jpg" }.sorted())
        assertTrue(raw.isFile)
        // and a fresh start reads the same photo
        assertEquals(11L, newAgent().record(o, 11)!!.id)
    }

    @Test fun aLateLeftDoesNotBringBackARemovedPhoto() = runBlocking {
        agent.take(o, 11)
        agent.left(o, 11)
        assertEquals(LplAgent.RemoveResult.Removed, agent.remove(o, 11))
        agent.opened(o, 11)
        agent.left(o, 11)
        assertNull(agent.record(o, 11))
        assertFalse(agent.work.folder(o, 11).exists())
        assertTrue(newAgent().photos.value.isEmpty())
    }

    @Test fun aStateThatCannotBeWrittenDoesNotThrowAndIsWrittenLater() = runBlocking {
        val r = agent.take(o, 11)
        val dir = agent.work.folder(o, 11)
        // the folder cannot take new files (as when the storage is full): status changes must not throw
        val blocker = File(dir, "state.json")
        blocker.delete(); blocker.mkdir()        // a directory where the file should go: every replace fails
        agent.opened(o, 11)
        agent.left(o, 11)
        assertEquals(PhotoStatus.SYNCED, agent.record(o, 11)!!.status)
        assertTrue(agent.unsavedStates() > 0)
        blocker.delete()
        editor.edit(agent.work.xmp(r))
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 11))
        assertEquals(0, agent.unsavedStates())
        assertEquals(agent.record(o, 11), WorkStore(File(tmp.root, "work")).loadAll().single())
    }

    @Test fun oneFileWrittenFromManyThreadsIsAlwaysWhole() {
        val f = File(tmp.root, "x/state.json")
        val texts = (0 until 8).map { t -> ("{\"writer\":$t,\"pad\":\"" + "y".repeat(5000 + t) + "\"}").toByteArray() }
        val errors = hammer(8, 300) { t, _ -> atomicWrite(f, texts[t]) }
        assertEquals(emptyList<Throwable>(), errors.map { it.toString() })
        assertTrue(texts.any { it.contentEquals(f.readBytes()) })
        assertEquals(listOf("state.json"), f.parentFile.list()!!.toList())
    }

    @Test fun readersNeverSeeAHalfWrittenFile() {
        val f = File(tmp.root, "y/state.json")
        val texts = (0 until 4).map { t -> ByteArray(20_000 + t) { (t + 1).toByte() } }
        atomicWrite(f, texts[0])
        val bad = ConcurrentLinkedQueue<String>()
        val errors = hammer(6, 300) { t, i ->
            if (t < 3) atomicWrite(f, texts[(t + i) % 4])
            else {
                val b = readWhole(f)
                if (b == null) bad += "missing" else if (texts.none { it.contentEquals(b) }) bad += "torn (${b.size} bytes)"
            }
        }
        assertEquals(emptyList<String>(), errors.map { it.toString() })
        assertEquals(emptyList<String>(), bad.toList().distinct())
    }

    @Test fun serverListAndInstallIdFromManyThreads() {
        val errors = hammer(6, 100) { t, i ->
            when (i % 3) {
                0 -> servers.put(o, "Phone $t", "pw-$t")
                1 -> servers.rename(o, "Phone $t $i")
                else -> servers.installId
            }
        }
        assertEquals(emptyList<String>(), errors.map { it.toString() })
        val reread = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        assertEquals(servers.installId, reread.installId)
        assertEquals(servers.list().map { it.origin to it.deviceName }, reread.list().map { it.origin to it.deviceName })
        assertEquals(listOf("install-id", "servers.json"), File(tmp.root, "lpl").list()!!.sorted())
    }

    // ---------------------------------------------------------------- two things acting on one photo

    @Test fun backgroundAndForegroundSyncingTheSamePhotoSendItOnce() = runBlocking {
        val r = agent.take(o, 11)
        editor.edit(agent.work.xmp(r))
        stub.delayMs = 200
        val a = async(Dispatchers.IO) { agent.sync(o, 11) }
        val b = async(Dispatchers.IO) { agent.syncPending() }
        assertEquals(SyncOutcome.SYNCED, a.await()); b.await()
        assertEquals(1, stub.photos[11]!!.xmpPosts)
        assertEquals(1, stub.photos[11]!!.previewPosts)
        assertEquals(PhotoStatus.SYNCED, agent.record(o, 11)!!.status)
    }

    @Test fun theDarkroomClosingDuringASyncLeavesTheRightStatus() = runBlocking {
        val r = agent.take(o, 11)
        val raw = agent.work.raw(r)
        editor.openRaw = raw; agent.opened(o, 11)
        editor.edit(agent.work.xmp(r))
        stub.delayMs = 300
        val s = async(Dispatchers.IO) { agent.sync(o, 11) }
        Thread.sleep(100)
        // "done": the darkroom closes the photo while its XMP is on the way
        editor.openRaw = null; agent.left(o, 11)
        assertEquals(SyncOutcome.SYNCED, s.await())
        assertEquals("not left as editing", PhotoStatus.SYNCED, agent.record(o, 11)!!.status)
        assertEquals(agent.record(o, 11), WorkStore(File(tmp.root, "work")).loadAll().single())
    }

    @Test fun removeWaitsForADownloadAndNothingComesBack() = runBlocking {
        stub.delayMs = 300
        val t = async(Dispatchers.IO) { agent.take(o, 11) }
        Thread.sleep(150)   // the take is under way (checkout answered after 300 ms)
        val rm = async(Dispatchers.IO) { agent.remove(o, 11) }
        t.await()
        assertEquals(LplAgent.RemoveResult.Removed, rm.await())
        assertNull(agent.record(o, 11))
        assertFalse(agent.work.folder(o, 11).exists())
        assertTrue(newAgent().photos.value.isEmpty())
    }

    @Test fun aStateFileLostIsRecoveredAsAnEditToSendNotDropped() = runBlocking {
        val r = agent.take(o, 11)
        editor.edit(agent.work.xmp(r))
        File(agent.work.folder(o, 11), "state.json").delete()
        val again = newAgent()
        val rec = again.record(o, 11)!!
        assertEquals(PhotoStatus.DIRTY, rec.status)
        assertTrue(rec.recovered)
        assertEquals(SyncOutcome.SYNCED, again.sync(o, 11))
        assertFalse(again.record(o, 11)!!.recovered)
        assertEquals(1, stub.photos[11]!!.xmpPosts)
        // a recovered photo whose XMP fails the version guard is never sent
        stub.add(StubLpl.Photo(12, "DSCF0012.RAF", fakeRaw(1000, 12)))
        val r12 = again.take(o, 12)
        again.work.xmp(r12).writeText("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><!-- newer-module --></x:xmpmeta>")
        File(again.work.folder(o, 12), "state.json").delete()
        val third = newAgent()
        assertEquals(SyncOutcome.READ_ONLY, third.sync(o, 12))
        assertEquals(0, stub.photos[12]!!.xmpPosts)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import app.dtandroid.lpl.BackgroundSync.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brief P3k: when the sync service starts and stops, and what it does with the jobs of a heartbeat sent while the app
 * is not on the screen. Pure decisions ([BackgroundSync]); the service in the app only gathers the inputs.
 */
class P3kBackgroundSyncTest {
    private val a = ServerOrigin("http", "127.0.0.1", 1)
    private val b = ServerOrigin("http", "127.0.0.1", 2)

    // ------------------------------------------------------------------ starting

    @Test fun startsForEditsWaitingWithAutomaticSyncOn() {
        assertTrue(BackgroundSync.shouldStart(SyncWork(autoSync = true, editsClosed = 1)))
    }

    @Test fun noWorkNoService() {
        assertFalse(BackgroundSync.shouldStart(SyncWork(autoSync = true, editsClosed = 0)))
        assertFalse(BackgroundSync.shouldStart(SyncWork(autoSync = false, editsClosed = 0)))
    }

    @Test fun automaticSyncOffDoesNotStartForEdits() {
        assertFalse(BackgroundSync.shouldStart(SyncWork(autoSync = false, editsClosed = 3)))
    }

    @Test fun automaticSyncOffStillCarriesDownloadsAndExportsTheUserStarted() {
        assertTrue(BackgroundSync.shouldStart(SyncWork(autoSync = false, editsClosed = 3, downloading = true)))
        assertTrue(BackgroundSync.shouldStart(SyncWork(autoSync = false, editsClosed = 0, exports = 1)))
    }

    @Test fun thePhotoOpenInTheDarkroomDoesNotStartIt() {
        assertFalse(BackgroundSync.shouldStart(SyncWork(autoSync = true, editsClosed = 0, editsOpen = 1)))
        // with another photo waiting it starts, for that one
        assertTrue(BackgroundSync.shouldStart(SyncWork(autoSync = true, editsClosed = 1, editsOpen = 1)))
    }

    @Test fun aBatchDownloadStartsIt() {
        assertTrue(BackgroundSync.shouldStart(SyncWork(autoSync = true, editsClosed = 0, downloading = true)))
    }

    @Test fun doneHandingItsPhotoToTheSyncStartsIt() {
        // "done": the photo is not waiting yet (the darkroom is closing it), the hand-over is in flight
        assertTrue(BackgroundSync.shouldStart(SyncWork(autoSync = true, editsClosed = 0, inFlight = true)))
    }

    // ------------------------------------------------------------------ stopping

    @Test fun stopsWhenNothingIsLeft() {
        assertEquals(Next.STOP_DONE, BackgroundSync.next(SyncWork(autoSync = true, editsClosed = 0)))
        // only the open photo left: done (the darkroom syncs it when it leaves the photo)
        assertEquals(Next.STOP_DONE, BackgroundSync.next(SyncWork(autoSync = true, editsClosed = 0, editsOpen = 1)))
    }

    @Test fun automaticSyncSwitchedOffWhileRunningStopsIt() {
        assertEquals(Next.STOP_DONE, BackgroundSync.next(SyncWork(autoSync = false, editsClosed = 2)))
    }

    @Test fun unreachableServerHandsOverToTheBackgroundJob() {
        assertEquals(Next.STOP_HANDOVER, BackgroundSync.next(SyncWork(autoSync = true, editsClosed = 1, unreachable = true)))
        assertEquals(Next.STOP_HANDOVER, BackgroundSync.next(SyncWork(autoSync = false, editsClosed = 0, exports = 2, unreachable = true)))
    }

    @Test fun noProgressHandsOverAfterAFewPasses() {
        assertEquals(Next.RUN, BackgroundSync.next(SyncWork(autoSync = true, editsClosed = 1, stalledPasses = BackgroundSync.MAX_STALLED - 1)))
        assertEquals(Next.STOP_HANDOVER, BackgroundSync.next(SyncWork(autoSync = true, editsClosed = 1, stalledPasses = BackgroundSync.MAX_STALLED)))
    }

    @Test fun aRunningDownloadOrStepKeepsItEvenWhenAServerFailed() {
        assertEquals(Next.RUN, BackgroundSync.next(SyncWork(autoSync = true, editsClosed = 1, downloading = true, unreachable = true)))
        assertEquals(Next.RUN, BackgroundSync.next(SyncWork(autoSync = true, editsClosed = 1, inFlight = true, unreachable = true)))
        assertEquals(Next.RUN, BackgroundSync.next(SyncWork(autoSync = false, editsClosed = 0, downloading = true)))
    }

    @Test fun keepsRunningWhileEditsMove() {
        assertEquals(Next.RUN, BackgroundSync.next(SyncWork(autoSync = true, editsClosed = 2)))
    }

    // ------------------------------------------------------------------ jobs from heartbeats in the background

    @Test fun editAndOpenJobsAreKeptForTheApp() {
        val jobs = listOf(
            a to Job(1, "edit", listOf(10), false),
            a to Job(2, "sync", listOf(11), false),
            b to Job(3, "open", listOf(12), false),
            b to Job(4, "release", listOf(13), false),
            a to Job(5, "edit", listOf(14, 15), false, open = false),
        )
        val (now, kept) = BackgroundSync.splitJobs(jobs, darkroomOpen = false) { _, _ -> false }
        assertEquals(listOf(2L, 4L), now.map { it.second.id })
        assertEquals(listOf(1L, 3L, 5L), kept.map { it.second.id })
        // the origin stays with its job
        assertEquals(b, kept[1].first)
    }

    @Test fun syncOrReleaseOfThePhotoOpenInTheDarkroomIsKept() {
        val jobs = listOf(a to Job(1, "sync", listOf(10, 11), false), a to Job(2, "release", listOf(12), false), b to Job(3, "sync", listOf(10), false))
        val (now, kept) = BackgroundSync.splitJobs(jobs, darkroomOpen = true) { o, id -> o == a && id == 11L }
        assertEquals(listOf(2L, 3L), now.map { it.second.id })
        assertEquals(listOf(1L), kept.map { it.second.id })
    }

    @Test fun anAllJobIsKeptWhileTheDarkroomHasAPhotoOpen() {
        val jobs = listOf(a to Job(1, "sync", emptyList(), true))
        assertEquals(1, BackgroundSync.splitJobs(jobs, darkroomOpen = true) { _, _ -> false }.second.size)
        assertEquals(1, BackgroundSync.splitJobs(jobs, darkroomOpen = false) { _, _ -> false }.first.size)
    }

    // ------------------------------------------------------------------ the notification's words

    @Test fun noticeSendingEdits() {
        val c = SendCounter()
        c.update(listOf("a", "b", "c"))
        c.update(listOf("b", "c"))
        val n = SyncNotice.of(null, c.total, c.done, 0, 0.5)
        assertEquals("Sending edits · 2 of 3", n.title)
        assertEquals(0.5f, n.fraction!!, 1e-6f)
        assertFalse(n.canStop)
        c.update(listOf("c", "d"))  // a new photo waiting joins N
        assertEquals(4, c.total); assertEquals(2, c.done)
    }

    @Test fun noticeDownloadingWithStop() {
        val v = SyncNotice.sum(listOf(BatchView(3, 1, 1, true, false), BatchView(2, 0, 0, true, false)))!!
        val n = SyncNotice.of(v, 4, 1, 0, null)
        assertEquals("Downloading · 2 of 5", n.title)
        assertEquals(0.2f, n.fraction!!, 1e-6f)
        assertTrue(n.canStop)
        assertNull(SyncNotice.sum(emptyList()))
    }

    @Test fun noticeExportsOnly() {
        assertEquals("Sending exports · 2 waiting", SyncNotice.of(null, 0, 0, 2, null).title)
    }
}

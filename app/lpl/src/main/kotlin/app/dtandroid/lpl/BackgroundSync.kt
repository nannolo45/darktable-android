// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

/**
 * Brief P3k: the decisions of the LightPhotoLibrary sync service, in pure Kotlin (no Android, no network), so they are
 * tested on the JVM. The service (`SyncService` in the app) gathers a [SyncWork] every heartbeat and does what
 * [BackgroundSync] says.
 */
data class SyncWork(
    /** the "sync automatically" switch (brief P2i) */
    val autoSync: Boolean,
    /** photos with something to send (edit, preview, versions) that are not open in the darkroom */
    val editsClosed: Int,
    /** photos with something to send that are open in the darkroom: never synced behind the user's back */
    val editsOpen: Int = 0,
    /** exports the user asked for that wait for their server (brief P3i) */
    val exports: Int = 0,
    /** a batch download (brief P2k) is held: queued or running */
    val downloading: Boolean = false,
    /** something is under way now: a sync or download step (R16), or "done" handing its photo to the sync */
    val inFlight: Boolean = false,
    /** the service tried and a server with waiting work did not answer (its last call failed) */
    val unreachable: Boolean = false,
    /** passes in a row in which nothing moved although the server answered */
    val stalledPasses: Int = 0,
)

object BackgroundSync {
    /** After this many passes without progress (and nothing in flight) the work is handed to the background job. */
    const val MAX_STALLED = 3

    enum class Next {
        /** keep running */
        RUN,
        /** nothing left: last heartbeat, stop */
        STOP_DONE,
        /** something is left but cannot progress now (server unreachable): stop, the background job retries */
        STOP_HANDOVER,
    }

    /**
     * Whether there is LPL work the service exists for. Edits count only with automatic syncing on (with it off the app
     * sends nothing by itself); exports the user asked for and downloads already started count either way. A photo open
     * in the darkroom does not count: it is synced when the darkroom leaves it.
     */
    fun hasWork(w: SyncWork): Boolean = w.downloading || w.inFlight || w.exports > 0 || (w.autoSync && w.editsClosed > 0)

    /** Whether to start the service (the activity is leaving the screen, "done", a batch starts). */
    fun shouldStart(w: SyncWork): Boolean = hasWork(w)

    /** What the running service does after a pass. */
    fun next(w: SyncWork): Next = when {
        w.downloading || w.inFlight -> Next.RUN
        !hasWork(w) -> Next.STOP_DONE
        w.unreachable || w.stalledPasses >= MAX_STALLED -> Next.STOP_HANDOVER
        else -> Next.RUN
    }

    /**
     * The jobs of a heartbeat sent while the app is not on the screen: `sync` and `release` are carried out now when
     * none of their photos is open in the darkroom; everything else (`edit`, `open`, and a `sync`/`release` touching the
     * open photo) is kept for the app. Returns (now, kept), each in LPL's order.
     */
    fun <O> splitJobs(jobs: List<Pair<O, Job>>, darkroomOpen: Boolean, isOpen: (O, Long) -> Boolean): Pair<List<Pair<O, Job>>, List<Pair<O, Job>>> {
        val now = ArrayList<Pair<O, Job>>()
        val keep = ArrayList<Pair<O, Job>>()
        for (p in jobs) {
            val (o, j) = p
            val background = j.kind == "sync" || j.kind == "release"
            val touchesOpen = if (j.all) darkroomOpen else j.ids.any { isOpen(o, it) }
            if (background && !touchesOpen) now += p else keep += p
        }
        return now to keep
    }
}

/** What the sync notification says: "Sending edits · n of N" or "Downloading · n of N", a bar, and whether "stop" is offered. */
data class SyncNotice(val title: String, val text: String, val fraction: Float?, val canStop: Boolean) {
    companion object {
        /**
         * [batch]: the batch downloads of every server added up, or null; [sendTotal]/[sendDone]: the edits counted by
         * [SendCounter]; [stepProgress]: the progress (0..1) of the step under way now (R16), if it has one.
         */
        fun of(batch: BatchView?, sendTotal: Int, sendDone: Int, exports: Int, stepProgress: Double?, link: String? = null): SyncNotice {
            // brief P4a: [link] (LinkWords.notice) says "waiting for connection" or "retrying" when that is what happens
            val text = if (link != null) "LightPhotoLibrary · $link" else "LightPhotoLibrary"
            if (batch != null && batch.running) {
                val n = minOf(batch.processed + 1, batch.total)
                return SyncNotice("Downloading · $n of ${batch.total}", text, batch.fraction, canStop = true)
            }
            if (sendTotal > 0) {
                val n = minOf(sendDone + 1, sendTotal)
                val f = ((sendDone + (stepProgress ?: 0.0).coerceIn(0.0, 1.0)) / sendTotal).toFloat().coerceIn(0f, 1f)
                return SyncNotice("Sending edits · $n of $sendTotal", text, f, canStop = false)
            }
            if (exports > 0) return SyncNotice("Sending exports · $exports waiting", text, null, canStop = false)
            return SyncNotice("LightPhotoLibrary", link ?: "syncing", null, canStop = false)
        }

        /**
         * The running batches of all servers as one: "n of N" over the jobs held. A server whose batch ended (its view
         * stays for the list's "stopped · n of N") is left out.
         */
        fun sum(all: Collection<BatchView>): BatchView? = all.filter { it.running }.let { views -> if (views.isEmpty()) null else BatchView(
            total = views.sumOf { it.total }, onPhone = views.sumOf { it.onPhone }, processed = views.sumOf { it.processed },
            running = true, stopped = views.any { it.stopped },
        ) }
    }
}

/**
 * Counts the edits a service run has sent: every photo seen waiting during the run is part of N; the ones no longer
 * waiting are done. A photo that waits again later (edited again) is counted once.
 */
class SendCounter {
    private val seen = LinkedHashSet<Any>()
    private var waiting: Set<Any> = emptySet()

    @Synchronized fun update(nowWaiting: Collection<Any>) {
        seen.addAll(nowWaiting)
        waiting = nowWaiting.toSet()
    }

    val total: Int @Synchronized get() = seen.size
    val done: Int @Synchronized get() = seen.size - waiting.size
}

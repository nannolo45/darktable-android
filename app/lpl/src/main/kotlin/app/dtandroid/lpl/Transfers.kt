// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a heartbeat answer asks the device to stop (PLAN.md 4.1, "Batches and Stop"); always the whole list. */
data class Cancel(val jobs: Set<Long>, val photos: Set<Long>)

/** The words of `transfers[].state`. */
object TransferState {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val STOPPED = "stopped"
    const val DONE = "done"
    const val FAILED = "failed"
}

/** What the list shows for the jobs of one server: "photo n of N", or after a stop "stopped · n of N arrived". */
data class BatchView(
    /** the ids of all jobs held (each job's ids once) */
    val total: Int,
    /** copied by the jobs, or already on the phone: the photos the batch has put on the phone */
    val onPhone: Int,
    /** copied, already there, failed or left out: how far the batch has come */
    val processed: Int,
    /** some job is still held (queued or running) */
    val running: Boolean,
    /** some job ended as stopped */
    val stopped: Boolean,
) {
    /** "photo 3 of 12" while running; null when nothing runs */
    val label: String? get() = if (running) "photo ${minOf(processed + 1, total)} of $total" else null

    /** "stopped · 5 of 12 arrived", or null when the batch was not stopped */
    val stoppedLine: String? get() = if (!running && stopped) "stopped · $onPhone of $total arrived" else null

    val fraction: Float get() = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Every transfer state the device holds for LPL's batch jobs, in one place: pure Kotlin, no Android, no
 * network. [LplAgent] feeds it (a job received, a photo copied, a Stop) and sends [report] with every
 * heartbeat. Rules (PLAN.md 4.1): an entry exists from the heartbeat after the job was received until
 * `/done` for it was answered ([forget]); lists are cumulative; a Stop that was acted on stays, whatever
 * the next `cancel` list says (the list is the whole truth each time, but only for what was not acted on yet).
 */
class TransferBook {
    private class Entry(val job: Long, val ids: List<Long>) {
        var state = TransferState.QUEUED
        /** acted on: the job was ended or is being ended as stopped; sticky */
        var stopped = false
        val arrived = LinkedHashSet<Long>()
        val present = LinkedHashSet<Long>()
        val failed = LinkedHashSet<Long>()
        val skipped = LinkedHashSet<Long>()
        var photo: Long? = null
        var bytes: Long = 0
        var held = true
        val processed get() = arrived.size + present.size + failed.size + skipped.size
    }

    private val entries = LinkedHashMap<Pair<ServerOrigin, Long>, Entry>()
    private val cancelJobs = HashMap<ServerOrigin, Set<Long>>()
    private val cancelPhotos = HashMap<ServerOrigin, Set<Long>>()

    private val _views = MutableStateFlow<Map<ServerOrigin, BatchView>>(emptyMap())
    /** For the list: one entry per server that has a batch running or stopped just now. */
    val views: StateFlow<Map<ServerOrigin, BatchView>> = _views.asStateFlow()

    private fun refresh() {
        val out = HashMap<ServerOrigin, BatchView>()
        for (o in entries.keys.map { it.first }.toSet()) {
            val es = entries.filterKeys { it.first == o }.values
            out[o] = BatchView(
                total = es.sumOf { it.ids.size }, onPhone = es.sumOf { it.arrived.size + it.present.size },
                processed = es.sumOf { it.processed }, running = es.any { it.held }, stopped = es.any { it.state == TransferState.STOPPED },
            )
        }
        _views.value = out
    }

    /** A job was received. Idempotent. A new batch replaces what an earlier, ended one left for the list. */
    @Synchronized fun receive(o: ServerOrigin, job: Long, ids: List<Long>) {
        if ((o to job) in entries) return
        if (entries.none { it.key.first == o && it.value.held }) entries.keys.removeAll { it.first == o }
        entries[o to job] = Entry(job, ids.distinct())
        refresh()
    }

    /** True when the job is held (received, not ended). */
    @Synchronized fun holds(o: ServerOrigin, job: Long) = entries[o to job]?.held == true

    /** The `transfers` array of the heartbeat for [o]: held jobs only; `[]` when none. */
    @Synchronized fun report(o: ServerOrigin): JsonArray = buildJsonArray {
        for ((k, e) in entries) if (k.first == o && e.held) add(buildJsonObject {
            put("job", e.job)
            put("state", e.state)
            put("arrived", buildJsonArray { e.arrived.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("present", buildJsonArray { e.present.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("failed", buildJsonArray { e.failed.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("skipped", buildJsonArray { e.skipped.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            val p = e.photo
            if (p != null) put("photo", p) else put("photo", JsonNull)
            put("bytes", if (p != null) e.bytes else 0L)
        })
    }

    /** The four lists of a held or ended job, for `POST /api/jobs/{id}/done`; null when the job is unknown. */
    @Synchronized fun lists(o: ServerOrigin, job: Long): Map<String, List<Long>>? = entries[o to job]?.let {
        mapOf("arrived" to it.arrived.toList(), "present" to it.present.toList(), "failed" to it.failed.toList(), "skipped" to it.skipped.toList())
    }

    /**
     * The whole `cancel` of an answer (replace, never add up); null = an old server, nothing changes.
     * A queued job in the list ends as stopped at once; a running one is told by [shouldStop].
     */
    @Synchronized fun applyCancel(o: ServerOrigin, c: Cancel?) {
        if (c == null) return
        cancelJobs[o] = c.jobs
        cancelPhotos[o] = c.photos
        for ((k, e) in entries) if (k.first == o && e.held && e.state == TransferState.QUEUED && e.job in c.jobs) { e.stopped = true; e.state = TransferState.STOPPED }
        refresh()
    }

    /** The user's Stop on this phone: every held job of [o] (or of all servers) ends as stopped. */
    @Synchronized fun stopAll(o: ServerOrigin? = null) {
        for ((k, e) in entries) if ((o == null || k.first == o) && e.held) {
            e.stopped = true
            if (e.state == TransferState.QUEUED) e.state = TransferState.STOPPED
        }
        refresh()
    }

    /** Whether to stop copying for [job] now (asked between files and between chunks). Once true, it stays true. */
    @Synchronized fun shouldStop(o: ServerOrigin, job: Long): Boolean {
        val e = entries[o to job] ?: return false
        if (!e.stopped && job in (cancelJobs[o] ?: emptySet())) e.stopped = true
        return e.stopped
    }

    /** Whether [photo] must not be copied for [job]; a photo given up is listed under `skipped` (sticky). */
    @Synchronized fun shouldSkip(o: ServerOrigin, job: Long, photo: Long): Boolean {
        val e = entries[o to job] ?: return false
        if (photo in e.skipped) return true
        if (photo in (cancelPhotos[o] ?: emptySet())) { e.skipped += photo; refresh(); return true }
        return false
    }

    /** The job starts copying. False when it was stopped while it waited: it never starts. */
    @Synchronized fun begin(o: ServerOrigin, job: Long): Boolean {
        val e = entries[o to job] ?: return false
        if (shouldStop(o, job)) return false
        e.state = TransferState.RUNNING
        return true
    }

    /** The photo being copied now and the bytes of its original on the device. */
    @Synchronized fun current(o: ServerOrigin, job: Long, photo: Long?, bytes: Long = 0) {
        val e = entries[o to job] ?: return
        e.photo = photo; e.bytes = if (photo == null) 0 else bytes
    }

    enum class Outcome { ARRIVED, PRESENT, FAILED, SKIPPED }

    @Synchronized fun mark(o: ServerOrigin, job: Long, photo: Long, outcome: Outcome) {
        val e = entries[o to job] ?: return
        when (outcome) {
            Outcome.ARRIVED -> e.arrived += photo
            Outcome.PRESENT -> e.present += photo
            Outcome.FAILED -> e.failed += photo
            Outcome.SKIPPED -> e.skipped += photo
        }
        if (e.photo == photo) { e.photo = null; e.bytes = 0 }
        refresh()
    }

    /** The job ended: [TransferState.STOPPED], [TransferState.DONE] or [TransferState.FAILED]. It is still reported until [forget]. */
    @Synchronized fun finish(o: ServerOrigin, job: Long, state: String) {
        val e = entries[o to job] ?: return
        e.state = state; e.photo = null; e.bytes = 0
        if (state == TransferState.STOPPED) e.stopped = true
        refresh()
    }

    /** `/done` was answered: the job is no longer reported. Its counts stay for the list until a new batch starts. */
    @Synchronized fun forget(o: ServerOrigin, job: Long) {
        entries[o to job]?.held = false
        refresh()
    }

    @Synchronized fun state(o: ServerOrigin, job: Long): String? = entries[o to job]?.state
}

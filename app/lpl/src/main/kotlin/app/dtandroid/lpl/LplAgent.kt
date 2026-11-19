// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What the agent needs from darktable's engine and the darkroom. The app implements it on the darkroom
 * controller; tests use a fake. Files are the photo's RAW and its XMP in the photo's folder.
 */
interface EditorPort {
    /** The engine's version guard on [xmp] fails (newer darktable or unknown module): the photo is view-only. */
    suspend fun xmpReadOnly(xmp: File): Boolean

    /** If the darkroom has [raw] open (and it is not read-only), write its edit to [xmp] now. */
    suspend fun writeIfOpen(raw: File, xmp: File)

    /** If the darkroom has [raw] open, re-read rating, labels and tags from [xmp] (after LPL merged them). */
    suspend fun reloadIfOpen(raw: File, xmp: File)

    /**
     * Render the photo as a JPEG of at most 1920 px into [out]. False when the engine cannot do it now
     * (another photo is open in the darkroom, or the engine is not running): the preview stays pending.
     */
    suspend fun renderPreview(raw: File, xmp: File, out: File): Boolean

    /** The darkroom has [raw] open. */
    fun isOpen(raw: File): Boolean

    /** The darkroom has a photo open (`darktable_running` in the heartbeat). */
    fun darkroomOpen(): Boolean
}

/** A server is not in the user's list: the app never contacts it (PLAN.md 4.3, brief P2e item 4). */
class ServerNotAddedException(val origin: ServerOrigin) : Exception("server not added: $origin")

/** Download progress of a photo. [total] is -1 when unknown. */
data class Progress(val done: Long, val total: Long)

/** Result of a sync of one photo. */
enum class SyncOutcome {
    /** nothing to send, or sent: LPL has the edit (a preview may still be pending) */
    SYNCED,
    /** LPL could not be reached (or the password is wrong): `dirty`, retried later */
    WAITING,
    /** the photo opens read-only: never posted back */
    READ_ONLY,
    /** LPL refused, or the photo is gone from the library: `error` */
    FAILED,
    /** the photo is not held (not downloaded) */
    NOT_HELD,
}

/**
 * The app as an LPL agent (PLAN.md 4.1, brief P2e): takes photos (checkout, XMP, original with resume),
 * keeps them in [work], syncs edits back (XMP with `checkout_ts`, the merged answer, then the preview),
 * reports the working set in heartbeats and carries out jobs. Pure Kotlin; network calls run on
 * [Dispatchers.IO]. Every photo has its own lock, so a sync and a download of one photo never overlap.
 */
class LplAgent(
    val servers: ServerStore,
    val work: WorkStore,
    private val editor: EditorPort,
    /** "app version / darktable version" for `state.version` */
    private val version: () -> String,
    private val clientFor: (ServerOrigin) -> LplClient = { o -> LplClient(o, { servers.password(o) }) },
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private val _photos = MutableStateFlow(load())
    /** Every photo held, in the order taken. */
    val photos: StateFlow<List<PhotoRecord>> = _photos.asStateFlow()

    private val _progress = MutableStateFlow<Map<Pair<ServerOrigin, Long>, Progress>>(emptyMap())
    val progress: StateFlow<Map<Pair<ServerOrigin, Long>, Progress>> = _progress.asStateFlow()

    /** Whether the last call to each server got an answer; null = not tried yet. */
    private val _reachable = MutableStateFlow<Map<ServerOrigin, LplException?>>(emptyMap())
    val lastError: StateFlow<Map<ServerOrigin, LplException?>> = _reachable.asStateFlow()

    /** Shown in LPL as the device's message (`state.message`). */
    @Volatile var message: String = ""

    private val locks = ConcurrentHashMap<Pair<ServerOrigin, Long>, Mutex>()
    private val names = ConcurrentHashMap<Pair<ServerOrigin, Long>, String>()
    private val clients = ConcurrentHashMap<ServerOrigin, LplClient>()

    private fun lockOf(o: ServerOrigin, id: Long) = locks.getOrPut(o to id) { Mutex() }
    fun client(o: ServerOrigin): LplClient = clients.getOrPut(o) { clientFor(o) }

    /** Forget cached clients (after a server's password changed). */
    fun resetClients() = clients.clear()

    fun record(o: ServerOrigin, id: Long): PhotoRecord? = _photos.value.firstOrNull { it.server == o && it.id == id }

    /** After a restart nothing is in progress: a photo that was being edited, opened or synced is `dirty` or `synced`. */
    private fun load(): List<PhotoRecord> = work.loadAll().map { r ->
        when (r.status) {
            PhotoStatus.EDITING, PhotoStatus.OPENING, PhotoStatus.SYNCING -> r.copy(status = settledStatus(r))
            PhotoStatus.DOWNLOADING -> if (work.hasRaw(r)) r.copy(status = settledStatus(r)) else r
            else -> r
        }.also { if (it != r) runCatching { work.save(it) } }
    }

    private fun settledStatus(r: PhotoRecord) = if (isDirty(r)) PhotoStatus.DIRTY else PhotoStatus.SYNCED

    /** The local XMP differs from what LPL last got (read-only photos never count: they are never sent). */
    fun isDirty(r: PhotoRecord): Boolean {
        if (r.readOnly) return false
        val x = work.xmp(r)
        return x.isFile && WorkStore.sha256(x) != r.syncedHash
    }

    private fun put(r: PhotoRecord) {
        work.save(r)
        _photos.update { list -> if (list.any { it.key == r.key }) list.map { if (it.key == r.key) r else it } else list + r }
    }

    private fun requireAdded(o: ServerOrigin) {
        if (servers.get(o) == null) throw ServerNotAddedException(o)
    }

    private fun noteReachable(o: ServerOrigin, e: LplException?) {
        _reachable.update { it + (o to e) }
    }

    private fun <T> net(o: ServerOrigin, block: (LplClient) -> T): T {
        try {
            return block(client(o)).also { noteReachable(o, null) }
        } catch (e: LplException) {
            if (e.transient || e.failure == LplFailure.UNAUTHORIZED || e.failure == LplFailure.TLS) noteReachable(o, e)
            throw e
        }
    }

    // ------------------------------------------------------------------ taking photos

    /**
     * Make the photo [id] of [origin] ready to open: checkout, XMP and original (resumed when part of it is
     * already on the phone). A photo already held with no unsynced edit is refreshed first (a newer XMP
     * from LPL replaces the local one; a changed original is downloaded again); without a connection the
     * local copy is used. Throws [LplException] when the photo cannot be made ready, and
     * [ServerNotAddedException] for a server not in the list.
     */
    suspend fun take(origin: ServerOrigin, id: Long, refresh: Boolean = true): PhotoRecord = withContext(Dispatchers.IO) {
        requireAdded(origin)
        lockOf(origin, id).withLock {
            val held = record(origin, id)
            if (held != null && work.hasRaw(held)) {
                if (refresh && !isDirty(held) && held.status != PhotoStatus.ERROR) {
                    try {
                        return@withLock refreshHeld(held)
                    } catch (e: LplException) {
                        if (e.failure == LplFailure.NOT_FOUND) {
                            put(held.copy(status = PhotoStatus.ERROR, error = e.userText)); throw e
                        }
                        // offline or refused: open what the phone has
                    }
                }
                return@withLock record(origin, id) ?: held
            }
            download(origin, id, held)
        }
    }

    private suspend fun refreshHeld(r: PhotoRecord): PhotoRecord {
        val co = net(r.server) { it.checkout(r.id) }
        if ((co.size > 0 && co.size != r.size) || co.mtime != r.mtime) {
            // the original changed on the NAS: fetch it again (the edit is synced, nothing is lost)
            work.raw(r).let { it.setWritable(true); it.delete() }
            work.part(r).delete()
            return download(r.server, r.id, r)
        }
        val x = net(r.server) { it.getXmp(r.id) }
        val f = work.xmp(r)
        var n = r.copy(checkoutTs = co.serverTime, error = "")
        if (WorkStore.sha256(x) != WorkStore.sha256(f)) {
            writeLocal(f, x)
            writeLocal(work.backup(r), x)
            n = n.copy(syncedHash = WorkStore.sha256(x), previewPending = false)
        }
        n = n.copy(status = settledStatus(n))
        put(n)
        return n
    }

    private suspend fun download(origin: ServerOrigin, id: Long, held: PhotoRecord?): PhotoRecord {
        val co = try {
            net(origin) { it.checkout(id) }
        } catch (e: LplException) {
            if (held != null) put(held.copy(status = if (e.failure == LplFailure.NOT_FOUND) PhotoStatus.ERROR else held.status, error = e.userText))
            throw e
        }
        val name = WorkStore.safeFileName(co.filename, id)
        var r = held
        if (r == null || r.filename != name || r.size != co.size || r.mtime != co.mtime) {
            // new photo, or the original on the NAS changed: a partial file of the old one is useless
            r?.let { old -> work.part(old).delete(); File(work.part(old).path + ".etag").delete() }
            r = PhotoRecord(origin, id, name, co.size, co.mtime, co.serverTime, PhotoStatus.DOWNLOADING,
                added = held?.added ?: nextAdded())
        } else {
            r = r.copy(status = PhotoStatus.DOWNLOADING, error = "")
        }
        names[origin to id] = name
        put(r)
        try {
            val xmp = work.xmp(r)
            // an edit made on the phone and not yet synced is never replaced
            if (!xmp.isFile || !isDirty(r)) {
                val x = net(origin) { it.getXmp(id) }
                writeLocal(xmp, x)
                writeLocal(work.backup(r), x)
                r = r.copy(syncedHash = WorkStore.sha256(x))
                put(r)
            }
            val part = work.part(r)
            _progress.update { it + ((origin to id) to Progress(if (part.isFile) part.length() else 0, co.size)) }
            net(origin) { c ->
                c.downloadOriginal(id, part, co.size, progress = { d, t -> _progress.update { it + ((origin to id) to Progress(d, t)) } })
            }
            val raw = work.raw(r)
            if (!part.renameTo(raw)) throw LplException(LplFailure.LOCAL, "cannot move the download into place")
            File(part.path + ".etag").delete()
            raw.setWritable(false, false)
        } catch (e: LplException) {
            val status = if (e.failure == LplFailure.NOT_FOUND) PhotoStatus.ERROR else PhotoStatus.DOWNLOADING
            put(r.copy(status = status, error = e.userText))
            throw e
        } finally {
            _progress.update { it - (origin to id) }
        }
        val ro = runCatching { editor.xmpReadOnly(work.xmp(r)) }.getOrDefault(false)
        r = r.copy(status = PhotoStatus.OPENING, readOnly = ro, error = if (ro) UserText.NEWER_DARKTABLE else "")
        put(r)
        return r
    }

    private fun writeLocal(f: File, bytes: ByteArray) {
        try {
            f.parentFile?.mkdirs()
            atomicWrite(f, bytes)
        } catch (e: java.io.IOException) {
            throw LplException(LplFailure.LOCAL, "cannot write ${f.name}", cause = e)
        }
    }

    private fun nextAdded(): Long = (_photos.value.maxOfOrNull { it.added } ?: 0) + 1

    /**
     * File names for photos of a hand-off list that are not on the phone yet (the filmstrip shows them as
     * placeholders). Asked with `checkout`, which changes nothing on the server; the answers are kept for
     * the session. Stops at the first failure and returns what it has.
     */
    suspend fun names(origin: ServerOrigin, ids: List<Long>, onName: (Long, String) -> Unit = { _, _ -> }): Map<Long, String> = withContext(Dispatchers.IO) {
        requireAdded(origin)
        val out = HashMap<Long, String>()
        for (id in ids) {
            val known = record(origin, id)?.filename ?: names[origin to id]
            if (known != null) { out[id] = known; continue }
            val n = try {
                WorkStore.safeFileName(net(origin) { it.checkout(id) }.filename, id)
            } catch (e: LplException) {
                if (e.failure == LplFailure.NOT_FOUND) continue
                break
            }
            names[origin to id] = n
            out[id] = n
            onName(id, n)
        }
        out
    }

    fun knownName(origin: ServerOrigin, id: Long): String? = record(origin, id)?.filename ?: names[origin to id]

    // ------------------------------------------------------------------ darkroom

    /** The darkroom opened the photo: `editing`. */
    fun opened(origin: ServerOrigin, id: Long) {
        val r = record(origin, id) ?: return
        if (r.status != PhotoStatus.DOWNLOADING) put(r.copy(status = PhotoStatus.EDITING))
    }

    /** The darkroom left the photo after writing its edit: `dirty` or `synced` (the caller then syncs). */
    fun left(origin: ServerOrigin, id: Long) {
        val r = record(origin, id) ?: return
        if (r.status == PhotoStatus.EDITING || r.status == PhotoStatus.OPENING) put(r.copy(status = settledStatus(r)))
    }

    // ------------------------------------------------------------------ syncing

    /**
     * Send the photo's edit to LPL (PLAN.md 4.1, brief P2e item 5), in this order: the darkroom writes
     * the XMP if the photo is open; the XMP is posted with `checkout_ts`; a merged answer replaces the
     * local XMP and the open photo re-reads its metadata; then the engine renders the preview and it is
     * posted. A read-only photo is never posted. Without a connection the photo is `dirty`.
     * [force] posts the XMP even when LPL already has it (the `sync` job).
     */
    suspend fun sync(origin: ServerOrigin, id: Long, force: Boolean = false): SyncOutcome = withContext(Dispatchers.IO) {
        lockOf(origin, id).withLock { syncLocked(origin, id, force) }
    }

    private suspend fun syncLocked(origin: ServerOrigin, id: Long, force: Boolean): SyncOutcome {
        var r = record(origin, id) ?: return SyncOutcome.NOT_HELD
        if (!work.hasRaw(r)) return SyncOutcome.NOT_HELD
        if (servers.get(origin) == null) return SyncOutcome.WAITING
        val raw = work.raw(r)
        val xmp = work.xmp(r)
        if (r.readOnly) {
            // PLAN.md 2.1: an edit made with a newer darktable is never rewritten or sent back
            return SyncOutcome.READ_ONLY
        }
        editor.writeIfOpen(raw, xmp)
        if (!xmp.isFile) return SyncOutcome.SYNCED
        val bytes = xmp.readBytes()
        val hash = WorkStore.sha256(bytes)
        val open = editor.isOpen(raw)
        if (hash != r.syncedHash || force) {
            put(r.copy(status = PhotoStatus.SYNCING))
            val answer = try {
                net(origin) { it.postXmp(id, bytes, r.checkoutTs) }
            } catch (e: LplException) {
                return failed(r, e)
            }
            var syncedHash = hash
            if (answer.merged) {
                // LPL kept its newer rating, labels and tags: take its file, unless the user changed the
                // edit meanwhile (then the next sync merges again, because the checkout time is unchanged)
                if (WorkStore.sha256(xmp) == hash) {
                    writeLocal(xmp, answer.xmp)
                    syncedHash = WorkStore.sha256(answer.xmp)
                    editor.reloadIfOpen(raw, xmp)
                }
            }
            r = (record(origin, id) ?: r).copy(syncedHash = syncedHash, syncedTs = clock(), error = "", previewPending = true)
            r = r.copy(status = if (open) PhotoStatus.EDITING else settledStatus(r))
            put(r)
            work.backup(r).delete()
        } else if (!open && (r.status == PhotoStatus.DIRTY || r.status == PhotoStatus.SYNCING)) {
            r = r.copy(status = PhotoStatus.SYNCED, error = "")
            put(r)
        }
        if (r.previewPending) postPreview(r)
        return SyncOutcome.SYNCED
    }

    private fun failed(r: PhotoRecord, e: LplException): SyncOutcome {
        val now = record(r.server, r.id) ?: r
        return when {
            e.transient || e.failure == LplFailure.UNAUTHORIZED || e.failure == LplFailure.TLS -> {
                put(now.copy(status = PhotoStatus.DIRTY, error = e.userText)); SyncOutcome.WAITING
            }
            else -> {
                put(now.copy(status = PhotoStatus.ERROR, error = e.userText)); SyncOutcome.FAILED
            }
        }
    }

    /** Render (unless a preview newer than the XMP is waiting) and post the preview; it stays pending on failure. */
    private suspend fun postPreview(r: PhotoRecord) {
        val out = work.preview(r)
        val xmp = work.xmp(r)
        if (!out.isFile || out.lastModified() < xmp.lastModified()) {
            out.delete()
            val ok = runCatching { editor.renderPreview(work.raw(r), xmp, out) }.getOrDefault(false)
            if (!ok || !out.isFile || out.length() == 0L) { out.delete(); return }
        }
        try {
            net(r.server) { it.postPreview(r.id, out.readBytes()) }
        } catch (e: LplException) {
            return
        }
        out.delete()
        val now = record(r.server, r.id) ?: return
        put(now.copy(previewPending = false))
    }

    /** Photos with something to send: an unsynced edit, an edit stuck in `syncing`, or a pending preview. */
    fun pending(): List<PhotoRecord> = _photos.value.filter { r ->
        work.hasRaw(r) && !r.readOnly && (isDirty(r) || r.previewPending || r.status == PhotoStatus.SYNCING || r.status == PhotoStatus.DIRTY)
    }

    /**
     * Sync everything pending (the background job, "sync now" on the list, the darkroom closing). True
     * when nothing is left to send afterwards. Photos open in the darkroom are left for when it closes.
     */
    suspend fun syncPending(includeOpen: Boolean = false): Boolean {
        for (r in pending()) {
            if (!includeOpen && editor.isOpen(work.raw(r))) continue
            sync(r.server, r.id)
        }
        return pending().none { includeOpen || !editor.isOpen(work.raw(it)) }
    }

    // ------------------------------------------------------------------ removing

    sealed interface RemoveResult {
        data object Removed : RemoveResult
        /** the photo has an edit LPL does not have yet: ask the user first */
        data object Unsynced : RemoveResult
        data object OpenInDarkroom : RemoveResult
    }

    /** Remove the photo from the phone. Unless [evenUnsynced], refused while it has an unsynced edit. */
    suspend fun remove(origin: ServerOrigin, id: Long, evenUnsynced: Boolean = false): RemoveResult = withContext(Dispatchers.IO) {
        lockOf(origin, id).withLock {
            val r = record(origin, id) ?: return@withLock RemoveResult.Removed
            if (editor.isOpen(work.raw(r))) return@withLock RemoveResult.OpenInDarkroom
            if (!evenUnsynced && (isDirty(r) || r.status == PhotoStatus.DIRTY || r.status == PhotoStatus.SYNCING)) return@withLock RemoveResult.Unsynced
            work.raw(r).setWritable(true)
            work.delete(origin, id)
            _photos.update { list -> list.filter { it.key != r.key } }
            RemoveResult.Removed
        }
    }

    // ------------------------------------------------------------------ heartbeat and jobs

    /** The agent state for [origin]'s heartbeat (same item shape and statuses as the PC agent). */
    fun state(origin: ServerOrigin): AgentState {
        val open = editor.darkroomOpen()
        val lines = _photos.value.filter { it.server == origin }.sortedBy { it.checkoutTs }.map { r ->
            val status = when {
                r.status == PhotoStatus.EDITING && !editor.isOpen(work.raw(r)) -> settledStatus(r)
                else -> r.status
            }
            WorkingSetLine(r.id, r.filename, status, r.checkoutTs, r.syncedTs, r.error)
        }
        return AgentState(lines, open, true, version(), message)
    }

    /**
     * One heartbeat to every added server; returns the jobs LPL handed out (each is handed out once: the
     * caller must carry it out and report it with [jobDone]). A server that does not answer gives no jobs.
     */
    suspend fun heartbeat(): List<Pair<ServerOrigin, Job>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<ServerOrigin, Job>>()
        for (s in servers.list()) {
            try {
                val a = net(s.origin) { it.heartbeat(servers.installId, s.deviceName, state(s.origin)) }
                for (j in a.jobs) out += s.origin to j
            } catch (e: LplException) {
                // shown on the list screen; the next heartbeat tries again
            } catch (e: IllegalStateException) {
                // the saved password cannot be read: the list screen asks for it
            }
        }
        out
    }

    suspend fun jobDone(origin: ServerOrigin, job: Job, ok: Boolean) = withContext(Dispatchers.IO) {
        try { net(origin) { it.jobDone(job.id, ok) } } catch (e: LplException) { /* LPL keeps it as taken */ }
    }

    /** The ids a job applies to: its list, or every photo held from that server when it says `all`. */
    fun jobIds(origin: ServerOrigin, job: Job): List<Long> =
        if (job.all) _photos.value.filter { it.server == origin }.map { it.id } else job.ids

    /**
     * Carry out a `sync` or `release` job (PLAN.md 4.1). `edit` and `open` need the darkroom and are
     * done by the app. Returns whether the job succeeded.
     */
    suspend fun runSyncOrRelease(origin: ServerOrigin, job: Job): Boolean {
        var ok = true
        for (id in jobIds(origin, job)) {
            val o = sync(origin, id, force = job.kind == "sync")
            if (o == SyncOutcome.WAITING || o == SyncOutcome.FAILED) { ok = false; continue }
            if (job.kind == "release") {
                if (remove(origin, id) != RemoveResult.Removed) ok = false
            }
        }
        return ok
    }
}

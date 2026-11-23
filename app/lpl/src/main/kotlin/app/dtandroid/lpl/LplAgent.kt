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

    /** R17: the version of [raw] the darkroom has open (0 when it is the photo's own edit or [raw] is not open). */
    fun openVersion(raw: File): Int = 0
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
    /** Brief P2i: the "sync automatically" switch (deliverable 5); on unless the user switched it off */
    val settings: SyncSettings = SyncSettings.inMemory(),
    /** Brief P2i: a line for the app's timeline (removals, refused writes); nothing by default */
    private val trace: (String) -> Unit = {},
) {
    private val _photos = MutableStateFlow<List<PhotoRecord>>(emptyList())
    /** Every photo held, in the order taken. */
    val photos: StateFlow<List<PhotoRecord>> = _photos.asStateFlow()

    private val _progress = MutableStateFlow<Map<Pair<ServerOrigin, Long>, Progress>>(emptyMap())
    val progress: StateFlow<Map<Pair<ServerOrigin, Long>, Progress>> = _progress.asStateFlow()

    private val _steps = MutableStateFlow<Map<Pair<ServerOrigin, Long>, Step>>(emptyMap())
    /** R16 (brief P2f deliverable 6): what is being done with each photo now, for the list, the darkroom and the heartbeat. */
    val steps: StateFlow<Map<Pair<ServerOrigin, Long>, Step>> = _steps.asStateFlow()

    private fun step(o: ServerOrigin, id: Long, s: Step?) =
        _steps.update { if (s == null) it - (o to id) else it + ((o to id) to s) }

    /** Whether the last call to each server got an answer; null = not tried yet. */
    private val _reachable = MutableStateFlow<Map<ServerOrigin, LplException?>>(emptyMap())
    val lastError: StateFlow<Map<ServerOrigin, LplException?>> = _reachable.asStateFlow()

    /** Shown in LPL as the device's message (`state.message`). */
    @Volatile var message: String = ""

    /**
     * Two locks per photo. [lockOf] (a coroutine mutex) is held for whole operations that may wait on the
     * network or the engine: take, sync, remove. [recordLock] (a plain monitor, held only for a moment, never
     * across a suspension) guards every change of the photo's record and the write of its state file, so
     * changes made outside the mutex (the darkroom opening and leaving the photo) can never interleave with
     * them. The darkroom calls [opened] and [left] while holding its own lock, and a sync holding the mutex
     * may wait for the darkroom's lock; so [opened] and [left] must not take the mutex.
     */
    private val locks = ConcurrentHashMap<Pair<ServerOrigin, Long>, Mutex>()
    private val recordLocks = ConcurrentHashMap<Pair<ServerOrigin, Long>, Any>()
    /** photos whose state could not be written; written again at the next change or sync */
    private val unsaved: MutableSet<Pair<ServerOrigin, Long>> = ConcurrentHashMap.newKeySet()
    private val names = ConcurrentHashMap<Pair<ServerOrigin, Long>, String>()
    private val clients = ConcurrentHashMap<ServerOrigin, LplClient>()

    private fun lockOf(o: ServerOrigin, id: Long) = locks.getOrPut(o to id) { Mutex() }
    fun client(o: ServerOrigin): LplClient = clients.getOrPut(o) { clientFor(o) }

    init {
        _photos.value = load()
    }

    /** Forget cached clients (after a server's password changed). */
    fun resetClients() = clients.clear()

    /** How many photos have a state that could not be written yet (storage full, for example). */
    fun unsavedStates(): Int = unsaved.size

    private fun recordLock(k: Pair<ServerOrigin, Long>) = recordLocks.getOrPut(k) { Any() }

    /**
     * Change one photo's record: [change] gets the current record (null when the phone does not hold the
     * photo) and returns the new one, or null to leave it as it is. The new record is shown at once and
     * written to its state file; a write that fails is kept in memory and tried again later, it never throws.
     */
    private fun mutate(o: ServerOrigin, id: Long, change: (PhotoRecord?) -> PhotoRecord?): PhotoRecord? {
        val k = o to id
        synchronized(recordLock(k)) {
            val cur = record(o, id)
            val n = change(cur) ?: return cur
            _photos.update { list -> if (list.any { it.key == k }) list.map { if (it.key == k) n else it } else list + n }
            persist(n)
            return n
        }
    }

    /** Write the state; on failure remember it (the user's edit is in the XMP, not here: nothing is lost). */
    private fun persist(r: PhotoRecord) {
        try {
            work.save(r)
            unsaved.remove(r.key)
        } catch (e: java.io.IOException) {
            unsaved.add(r.key)
        }
    }

    /** Write the states that could not be written before. */
    fun retryUnsaved() {
        for (k in unsaved.toList()) synchronized(recordLock(k)) {
            val r = record(k.first, k.second)
            if (r == null) unsaved.remove(k) else persist(r)
        }
    }

    fun record(o: ServerOrigin, id: Long): PhotoRecord? = _photos.value.firstOrNull { it.server == o && it.id == id }

    /**
     * After a restart nothing is in progress: a photo that was being edited, opened or synced is `dirty` or
     * `synced`. A photo folder whose state cannot be read but which holds a RAW is taken back as `dirty`
     * (its edit is sent; the version guard is checked again first, see [sync]).
     */
    private fun load(): List<PhotoRecord> {
        val loaded = work.load()
        val out = loaded.records.map { r ->
            when (r.status) {
                PhotoStatus.EDITING, PhotoStatus.OPENING, PhotoStatus.SYNCING -> r.copy(status = settledStatus(r))
                PhotoStatus.DOWNLOADING -> if (work.hasRaw(r)) r.copy(status = settledStatus(r)) else r
                else -> r
            }.also { if (it != r) persist(it) }
        }.toMutableList()
        for (pd in loaded.withoutState) recover(pd, (out.maxOfOrNull { it.added } ?: 0) + 1)?.let { out += it; persist(it) }
        return out
    }

    private fun recover(dir: File, added: Long): PhotoRecord? {
        val id = dir.name.toLongOrNull() ?: return null
        val origin = servers.list().firstOrNull { it.origin.key == dir.parentFile?.name }?.origin ?: return null
        val known = setOf("state.json", "downloaded.xmp", "preview.jpg", "preview-tmp.jpg", "thumb.rgba", "picture.rgba")
        val name = dir.list().orEmpty().filter { n ->
            n !in known && !n.endsWith(".xmp") && !n.endsWith(".part") && !n.endsWith(".etag") && !n.endsWith(".tmp") && !n.startsWith(".") && !n.startsWith("preview-v")
        }.singleOrNull() ?: return null
        return PhotoRecord(origin, id, name, -1, 0.0, 0.0, PhotoStatus.DIRTY, added = added, recovered = true,
            error = "the app's notes on this photo were lost; its edit is sent as it is")
    }

    private fun settledStatus(r: PhotoRecord) = if (isDirty(r)) PhotoStatus.DIRTY else PhotoStatus.SYNCED

    /** The local XMP differs from what LPL last got (read-only photos never count: they are never sent). */
    fun isDirty(r: PhotoRecord): Boolean = zeroDirty(r) || versionsDirty(r)

    private fun zeroDirty(r: PhotoRecord): Boolean {
        if (r.readOnly) return false
        val x = work.xmp(r)
        return x.isFile && WorkStore.sha256(x) != r.syncedHash
    }

    /** R17: the numbers (>= 1) of the version files of [r] on the phone. */
    fun versionNumbers(r: PhotoRecord): List<Int> = VersionFiles.numbers(work.folder(r.server, r.id), r.filename).filter { it > 0 }

    /**
     * R17: a version file differs from what LPL has (or was never sent). Versions of a server without the
     * `versions` capability are local only and never count, and neither does a version that fails the version guard.
     */
    private fun versionsDirty(r: PhotoRecord): Boolean {
        if (!r.versionsCap) return false
        return versionNumbers(r).any { n -> versionDirty(r, n) }
    }

    private fun versionDirty(r: PhotoRecord, n: Int): Boolean {
        val st = r.versionState(n)
        if (st?.readOnly == true) return false
        return WorkStore.sha256(work.versionXmp(r, n)) != (st?.syncedHash ?: "")
    }

    private fun versionsPending(r: PhotoRecord): Boolean =
        r.versionsCap && (r.deletedVersions.isNotEmpty() || r.versions.any { it.previewPending && !it.readOnly && work.versionXmp(r, it.n).isFile })

    /** Set the record (under the photo's mutex: take and sync). */
    private fun put(r: PhotoRecord) { mutate(r.server, r.id) { r } }

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
    suspend fun take(
        origin: ServerOrigin, id: Long, refresh: Boolean = true,
        /** brief P2k: a batch copy: the photo is `ready` (not opened yet), and [cancelled] is asked between chunks */
        batch: Boolean = false, cancelled: () -> Boolean = { false }, onBytes: (Long) -> Unit = {},
    ): PhotoRecord = withContext(Dispatchers.IO) {
        requireAdded(origin)
        lockOf(origin, id).withLock {
            val held = record(origin, id)
            if (held != null && work.hasRaw(held)) {
                // not while the darkroom has it open: its edit there is not written yet, and LPL's XMP must not
                // be put under it (it would be overwritten, or counted as synced when it is not)
                if (refresh && !isDirty(held) && held.status != PhotoStatus.ERROR && !editor.isOpen(work.raw(held))) {
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
            download(origin, id, held, batch, cancelled, onBytes)
        }
    }

    private suspend fun refreshHeld(r: PhotoRecord): PhotoRecord {
        val co = net(r.server) { it.checkout(r.id, servers.installId) }
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
        n = pullVersions(n, co)
        n = n.copy(status = settledStatus(n))
        put(n)
        refreshLastEdit(r.server, r.id)
        return record(r.server, r.id) ?: n
    }

    private suspend fun download(origin: ServerOrigin, id: Long, held: PhotoRecord?, batch: Boolean = false, cancelled: () -> Boolean = { false }, onBytes: (Long) -> Unit = {}): PhotoRecord {
        val co = try {
            net(origin) { it.checkout(id, servers.installId) }
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
            r = pullVersions(r, co)
            put(r)
            val part = work.part(r)
            _progress.update { it + ((origin to id) to Progress(if (part.isFile) part.length() else 0, co.size)) }
            step(origin, id, Step(Step.DOWNLOADING, if (co.size > 0 && part.isFile) part.length().toDouble() / co.size else null))
            net(origin) { c ->
                c.downloadOriginal(id, part, co.size, progress = { d, t ->
                    _progress.update { it + ((origin to id) to Progress(d, t)) }
                    step(origin, id, Step(Step.DOWNLOADING, if (t > 0) d.toDouble() / t else null))
                    onBytes(d)
                }, cancelled = cancelled)
            }
            val raw = work.raw(r)
            if (!part.renameTo(raw)) throw LplException(LplFailure.LOCAL, "cannot move the download into place")
            File(part.path + ".etag").delete()
            raw.setWritable(false, false)
        } catch (e: LplException) {
            if (e.failure == LplFailure.CANCELLED) {
                // brief P2k: a stopped file is not resumed: its partial data goes, and so does the half-made
                // entry (unless an unsynced edit of the photo is in the folder: that is never deleted)
                val part = work.part(r)
                part.delete(); File(part.path + ".etag").delete()
                if (isDirty(r) && work.xmp(r).isFile) put(r.copy(error = "")) else {
                    synchronized(recordLock(r.key)) { _photos.update { l -> l.filterNot { it.key == r.key } }; unsaved.remove(r.key) }
                    work.delete(origin, id)
                    names.remove(origin to id)
                }
                throw e
            }
            val status = if (e.failure == LplFailure.NOT_FOUND) PhotoStatus.ERROR else PhotoStatus.DOWNLOADING
            put(r.copy(status = status, error = e.userText))
            throw e
        } finally {
            _progress.update { it - (origin to id) }
            step(origin, id, null)
        }
        val ro = runCatching { editor.xmpReadOnly(work.xmp(r)) }.getOrDefault(false)
        r = r.copy(status = if (batch) PhotoStatus.READY else PhotoStatus.OPENING, readOnly = ro, error = if (ro) UserText.NEWER_DARKTABLE else "")
        put(r)
        refreshLastEdit(origin, id)
        return record(origin, id) ?: r
    }

    /**
     * R17: bring the versions LPL lists (`co.versions`, n >= 1) onto the phone: a version the phone does not have, or has
     * unchanged since it was synced, is downloaded; a version with an unsynced local edit is never replaced; a version
     * deleted on the phone and not yet deleted on LPL is not brought back. A server that lists no versions has not the
     * capability: nothing is fetched and the versions the phone made stay local. Returns the record to keep.
     */
    private suspend fun pullVersions(r0: PhotoRecord, co: Checkout): PhotoRecord {
        val list = co.versions ?: return if (r0.versionsCap) r0.copy(versionsCap = false) else r0
        var r = r0.copy(versionsCap = true, cover = co.cover)
        val dir = work.folder(r.server, r.id)
        for (sv in list.filter { it.n >= 1 && it.hasFile && it.n !in r.deletedVersions }) {
            val f = work.versionXmp(r, sv.n)
            val st = r.versionState(sv.n)
            val localEdit = f.isFile && st?.readOnly != true && WorkStore.sha256(f) != (st?.syncedHash ?: "")
            if (!localEdit) {
                val x = net(r.server) { it.getXmp(r.id, sv.n) }
                if (!f.isFile || WorkStore.sha256(x) != WorkStore.sha256(f)) writeLocal(f, x)
                val ro = runCatching { editor.xmpReadOnly(f) }.getOrDefault(false)
                r = withVersion(r, VersionState(sv.n, WorkStore.sha256(x), ro, st?.previewPending ?: false))
            }
            VersionFiles.remember(dir, r.filename, sv.n)
        }
        return r
    }

    private fun withVersion(r: PhotoRecord, v: VersionState) = r.copy(versions = (r.versions.filter { it.n != v.n } + v).sortedBy { it.n })

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
            if (known != null) { out[id] = known; onName(id, known); continue }
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
        mutate(origin, id) { r -> r?.takeIf { it.status != PhotoStatus.DOWNLOADING && it.status != PhotoStatus.EDITING }?.copy(status = PhotoStatus.EDITING) }
    }

    /** The darkroom left the photo after writing its edit: `dirty` or `synced` (the caller then syncs). */
    fun left(origin: ServerOrigin, id: Long) {
        mutate(origin, id) { r ->
            r?.takeIf { it.status == PhotoStatus.EDITING || it.status == PhotoStatus.OPENING || it.status == PhotoStatus.READY }?.let { it.copy(status = settledStatus(it)) }
        }
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
        lockOf(origin, id).withLock {
            try {
                syncLocked(origin, id, force)
            } catch (e: LplException) {
                // a local file that could not be written (the merged XMP): the edit stays and waits
                record(origin, id)?.let { failed(it, e) } ?: SyncOutcome.NOT_HELD
            }
        }
    }

    private suspend fun syncLocked(origin: ServerOrigin, id: Long, force: Boolean): SyncOutcome {
        val first = syncZero(origin, id, force)
        if (first == SyncOutcome.NOT_HELD || first == SyncOutcome.WAITING || first == SyncOutcome.FAILED) return first
        val rec = record(origin, id) ?: return first
        if (!rec.versionsCap) return first
        val v = syncVersions(origin, id, force)
        return if (v == SyncOutcome.WAITING || v == SyncOutcome.FAILED) v else first
    }

    /** The photo's own edit (version 0): the sync as it was before R17. */
    private suspend fun syncZero(origin: ServerOrigin, id: Long, force: Boolean): SyncOutcome {
        var r = record(origin, id) ?: return SyncOutcome.NOT_HELD
        if (!work.hasRaw(r)) return SyncOutcome.NOT_HELD
        if (servers.get(origin) == null) return SyncOutcome.WAITING
        val raw = work.raw(r)
        val xmp = work.xmp(r)
        retryUnsaved()
        if (r.recovered) {
            // its state was lost: check the version guard again before anything is sent
            val ro = editor.xmpReadOnly(xmp)
            r = mutate(origin, id) { it?.copy(readOnly = ro, recovered = false, error = if (ro) UserText.NEWER_DARKTABLE else it.error) } ?: return SyncOutcome.NOT_HELD
        }
        if (r.readOnly) {
            // PLAN.md 2.1: an edit made with a newer darktable is never rewritten or sent back
            return SyncOutcome.READ_ONLY
        }
        val before = if (xmp.isFile) WorkStore.sha256(xmp) else ""
        editor.writeIfOpen(raw, xmp)
        // brief P2f: whether the darkroom's open changes were written just now (for what the user is told)
        lastWroteOpen[r.key] = editor.isOpen(raw) && xmp.isFile && WorkStore.sha256(xmp) != before
        if (!xmp.isFile) return SyncOutcome.SYNCED
        val bytes = xmp.readBytes()
        val hash = WorkStore.sha256(bytes)
        r = record(origin, id) ?: return SyncOutcome.NOT_HELD
        if (hash != r.syncedHash || force) {
            mutate(origin, id) { it?.copy(status = PhotoStatus.SYNCING) }
            step(origin, id, Step(Step.SENDING_EDIT))
            val answer = try {
                net(origin) { it.postXmp(id, bytes, r.checkoutTs, servers.installId) }
            } catch (e: LplException) {
                step(origin, id, null)
                return failed(r, e)
            }
            step(origin, id, null)
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
            // the status is decided with the darkroom's state at this moment (it may have closed meanwhile)
            r = mutate(origin, id) { cur ->
                cur?.copy(syncedHash = syncedHash, syncedTs = clock(), error = "", previewPending = true)
                    ?.let { n -> n.copy(status = if (editor.isOpen(raw)) PhotoStatus.EDITING else settledStatus(n)) }
            } ?: return SyncOutcome.NOT_HELD
            work.backup(r).delete()
            refreshLastEdit(origin, id)
        } else {
            r = mutate(origin, id) { cur ->
                cur?.takeIf { !editor.isOpen(raw) && (it.status == PhotoStatus.DIRTY || it.status == PhotoStatus.SYNCING) }
                    ?.copy(status = PhotoStatus.SYNCED, error = "")
            } ?: return SyncOutcome.NOT_HELD
        }
        if (r.previewPending) postPreview(r)
        return SyncOutcome.SYNCED
    }

    /**
     * R17: send the versions n >= 1 of a photo whose server has the `versions` capability: first the deletions made on the
     * phone, then each version file that differs from what LPL has, `POST /xmp?version=n`; on `409 version_exists` the
     * local file is renamed to the number LPL names (`next`) and posted again (never while the darkroom has the photo
     * open: its file would come back under the old number); then the previews. A version that fails the version guard
     * is never sent. Without the capability nothing is sent: those versions stay local.
     */
    private suspend fun syncVersions(origin: ServerOrigin, id: Long, force: Boolean): SyncOutcome {
        var r = record(origin, id) ?: return SyncOutcome.NOT_HELD
        if (!r.versionsCap) return SyncOutcome.SYNCED
        val raw = work.raw(r)
        val dir = work.folder(origin, id)
        for (n in r.deletedVersions) {
            try {
                net(origin) { it.deleteVersion(id, n) }
            } catch (e: LplException) {
                if (e.failure != LplFailure.NOT_FOUND) return failed(r, e)
            }
            r = mutate(origin, id) { cur -> cur?.copy(deletedVersions = cur.deletedVersions - n, versions = cur.versions.filter { it.n != n }) } ?: return SyncOutcome.NOT_HELD
        }
        // the open version's changes first (the darkroom writes its own file whatever the path asked for)
        editor.writeIfOpen(raw, work.xmp(r))
        for (n in versionNumbers(r)) {
            r = record(origin, id) ?: return SyncOutcome.NOT_HELD
            var number = n
            var file = work.versionXmp(r, number)
            val st = r.versionState(number)
            if (st?.readOnly == true) continue
            if (!file.isFile) continue
            if (WorkStore.sha256(file) == (st?.syncedHash ?: "") && !force) continue
            var bytes = file.readBytes()
            var answer: XmpAnswer? = null
            var retried = false
            var refreshed = false
            while (answer == null) {
                mutate(origin, id) { it?.copy(status = PhotoStatus.SYNCING) }
                step(origin, id, Step(Step.SENDING_EDIT))
                try {
                    answer = net(origin) { it.postXmp(id, bytes, r.checkoutTs, servers.installId, number) }
                } catch (e: LplException) {
                    step(origin, id, null)
                    if (e.failure == LplFailure.VERSION_EXISTS && !refreshed && st?.syncedHash?.isNotEmpty() == true) {
                        // a version this phone sent before: LPL notes the numbers a device was told about at checkout, and
                        // not the ones it sent itself; asking again tells it, then the same number is posted again
                        refreshed = true
                        runCatching { net(origin) { it.checkout(id, servers.installId) } }
                        continue
                    }
                    if (e.failure == LplFailure.VERSION_EXISTS && !retried) {
                        if (editor.isOpen(raw)) { mutate(origin, id) { it?.copy(status = PhotoStatus.DIRTY) }; return SyncOutcome.WAITING }
                        val to = e.next?.takeIf { it > 0 && !work.versionXmp(r, it).exists() } ?: VersionFiles.next(dir, r.filename)
                        if (!VersionFiles.renumber(dir, r.filename, number, to)) return failed(r, LplException(LplFailure.LOCAL, "cannot rename version $number to $to"))
                        val old = number
                        number = to
                        file = work.versionXmp(r, number)
                        bytes = file.readBytes()
                        retried = true
                        r = mutate(origin, id) { cur -> cur?.copy(versions = cur.versions.filter { it.n != old }) } ?: return SyncOutcome.NOT_HELD
                        trace("lpl: photo $id version $old taken on LPL meanwhile, sent as version $number")
                        continue
                    }
                    return failed(r, e)
                }
            }
            step(origin, id, null)
            val hash = WorkStore.sha256(bytes)
            var syncedHash = hash
            if (answer.merged && WorkStore.sha256(file) == hash) {
                writeLocal(file, answer.xmp)
                syncedHash = WorkStore.sha256(answer.xmp)
                editor.reloadIfOpen(raw, file)
            }
            val sent = number
            r = mutate(origin, id) { cur -> cur?.let { withVersion(it, VersionState(sent, syncedHash, false, true)).copy(syncedTs = clock(), error = "") } } ?: return SyncOutcome.NOT_HELD
        }
        for (v in r.versions.filter { it.previewPending && !it.readOnly }) {
            r = record(origin, id) ?: return SyncOutcome.NOT_HELD
            if (work.versionXmp(r, v.n).isFile) postVersionPreview(r, v.n)
        }
        mutate(origin, id) { cur ->
            cur?.takeIf { it.status == PhotoStatus.SYNCING || it.status == PhotoStatus.DIRTY || it.status == PhotoStatus.SYNCED }
                ?.let { c -> c.copy(status = if (editor.isOpen(raw)) PhotoStatus.EDITING else settledStatus(c)) }
        }
        return SyncOutcome.SYNCED
    }

    private suspend fun postVersionPreview(r: PhotoRecord, n: Int) {
        val out = work.versionPreview(r, n)
        val xmp = work.versionXmp(r, n)
        if (!out.isFile || out.lastModified() < xmp.lastModified()) {
            out.delete()
            step(r.server, r.id, Step(Step.RENDERING_PREVIEW))
            val ok = try { runCatching { editor.renderPreview(work.raw(r), xmp, out) }.getOrDefault(false) } finally { step(r.server, r.id, null) }
            if (!ok || !out.isFile || out.length() == 0L) { out.delete(); return }
        }
        step(r.server, r.id, Step(Step.SENDING_PREVIEW))
        try {
            net(r.server) { it.postPreview(r.id, out.readBytes(), n) }
        } catch (e: LplException) {
            return
        } finally {
            step(r.server, r.id, null)
        }
        out.delete()
        mutate(r.server, r.id) { cur -> cur?.let { c -> c.versionState(n)?.let { withVersion(c, it.copy(previewPending = false)) } } }
    }

    // ------------------------------------------------------------------ R17: making and deleting versions

    /**
     * Whether a version may be made of this photo now (design P3f section 3): the server lists versions, or the phone
     * cannot reach it (the version then waits; with a server that turns out not to know versions it stays local).
     * A server seen without the capability while it can be reached: no.
     */
    fun canMakeVersion(origin: ServerOrigin, id: Long): Boolean {
        val r = record(origin, id) ?: return false
        return r.versionsCap || _reachable.value[origin] != null
    }

    /** R17: version [n]'s file differs from what LPL has (for the versions sheet's dot); false for a version of a server without versions. */
    fun isVersionDirty(origin: ServerOrigin, id: Long, n: Int): Boolean {
        val r = record(origin, id) ?: return false
        return if (n == 0) zeroDirty(r) else r.versionsCap && versionDirty(r, n)
    }

    /** R17: ask LPL to show version [n] for the photo (the cover). An error text for the user, null when done. */
    suspend fun setCover(origin: ServerOrigin, id: Long, n: Int): String? = withContext(Dispatchers.IO) {
        try {
            val c = net(origin) { it.setCover(id, n) }
            mutate(origin, id) { cur -> cur?.copy(cover = c) }
            null
        } catch (e: LplException) {
            e.userText
        }
    }

    /** Whether the versions of this photo are sent to LPL (false: they stay on the phone). */
    fun versionsSynced(origin: ServerOrigin, id: Long): Boolean = record(origin, id)?.versionsCap == true

    /** A version file was made on the phone: it is an unsynced edit until it is sent. */
    fun versionMade(origin: ServerOrigin, id: Long, n: Int) {
        mutate(origin, id) { cur ->
            cur?.takeIf { it.versionState(n) == null }?.let { c ->
                val n2 = withVersion(c, VersionState(n))
                n2.copy(status = if (c.status == PhotoStatus.SYNCED || c.status == PhotoStatus.EDITING) c.status else PhotoStatus.DIRTY)
            }
        }
    }

    /**
     * Delete version [n] (never 0) from the phone. LPL's copy is deleted by the next sync when the version was ever sent
     * (LPL keeps the file in its backup folder). False when the darkroom has this photo open (close it first).
     */
    suspend fun deleteVersion(origin: ServerOrigin, id: Long, n: Int): Boolean = withContext(Dispatchers.IO) {
        if (n <= 0) return@withContext false
        lockOf(origin, id).withLock {
            val r = record(origin, id) ?: return@withLock false
            if (editor.isOpen(work.raw(r)) && editor.openVersion(work.raw(r)) == n) return@withLock false
            val dir = work.folder(origin, id)
            VersionFiles.remember(dir, r.filename, n)
            work.versionXmp(r, n).delete()
            work.versionPreview(r, n).delete()
            val sent = r.versionState(n)?.syncedHash?.isNotEmpty() == true
            mutate(origin, id) { cur ->
                cur?.copy(versions = cur.versions.filter { it.n != n },
                    deletedVersions = if (sent && cur.versionsCap && n !in cur.deletedVersions) cur.deletedVersions + n else cur.deletedVersions)
            }
            true
        }
    }

    /**
     * R15: ask LightPhotoLibrary for the photo's last edit and keep it with the photo. A server without R15
     * gives nothing and nothing changes; a failure is ignored (the line is only information).
     */
    fun refreshLastEdit(origin: ServerOrigin, id: Long) {
        val e = try { client(origin).lastEdit(id) } catch (e: LplException) { return } catch (e: IllegalStateException) { return }
        mutate(origin, id) { cur -> cur?.takeIf { it.lastEdit != e }?.copy(lastEdit = e) }
    }

    /** R15 for every photo of [origin] the phone holds (the list asks when it opens). */
    suspend fun refreshLastEdits(origin: ServerOrigin) = withContext(Dispatchers.IO) {
        for (r in _photos.value.filter { it.server == origin }) refreshLastEdit(origin, r.id)
    }

    private fun failed(r: PhotoRecord, e: LplException): SyncOutcome {
        val waiting = e.transient || e.failure == LplFailure.UNAUTHORIZED || e.failure == LplFailure.TLS || e.failure == LplFailure.LOCAL
        mutate(r.server, r.id) { it?.copy(status = if (waiting) PhotoStatus.DIRTY else PhotoStatus.ERROR, error = e.userText) }
        return if (waiting) SyncOutcome.WAITING else SyncOutcome.FAILED
    }

    /** Render (unless a preview newer than the XMP is waiting) and post the preview; it stays pending on failure. */
    private suspend fun postPreview(r: PhotoRecord) {
        val out = work.preview(r)
        val xmp = work.xmp(r)
        if (!out.isFile || out.lastModified() < xmp.lastModified()) {
            out.delete()
            step(r.server, r.id, Step(Step.RENDERING_PREVIEW))
            val ok = try { runCatching { editor.renderPreview(work.raw(r), xmp, out) }.getOrDefault(false) } finally { step(r.server, r.id, null) }
            if (!ok || !out.isFile || out.length() == 0L) { out.delete(); return }
        }
        step(r.server, r.id, Step(Step.SENDING_PREVIEW))
        try {
            net(r.server) { it.postPreview(r.id, out.readBytes()) }
        } catch (e: LplException) {
            return
        } finally {
            step(r.server, r.id, null)
        }
        out.delete()
        mutate(r.server, r.id) { it?.copy(previewPending = false) }
    }

    /** Photos with something to send: an unsynced edit, an edit stuck in `syncing`, or a pending preview. */
    fun pending(): List<PhotoRecord> = _photos.value.filter { r ->
        work.hasRaw(r) && ((!r.readOnly && (isDirty(r) || r.previewPending || r.status == PhotoStatus.SYNCING || r.status == PhotoStatus.DIRTY)) || (versionsDirty(r) || versionsPending(r)))
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
            // under the record lock too: a late opened/left/state write cannot bring the folder back
            synchronized(recordLock(r.key)) {
                work.raw(r).setWritable(true)
                work.delete(origin, id)
                _photos.update { list -> list.filter { it.key != r.key } }
                unsaved.remove(r.key)
                trace("lpl: ${r.id}/${r.filename} removed from the phone (folder deleted)")
            }
            RemoveResult.Removed
        }
    }

    /**
     * Brief P2i (P2h's l4 failure): write into a held photo's folder (its thumbnail, its last picture) only
     * while the phone still holds the photo. [write] runs under the photo's record lock, which [remove] holds
     * while it deletes the folder, and only when the record and the folder are there: a write that comes after
     * the removal is dropped, so the removal wins. Before P2i such a write (the background thumbnail pass storing
     * a thumbnail it had rendered while a `release` job removed the photo) made the folder again
     * (`AtomicFiles.write` creates missing folders). Returns whether [write] ran.
     */
    fun writeIfHeld(origin: ServerOrigin, id: Long, what: String, write: (File) -> Unit): Boolean {
        synchronized(recordLock(origin to id)) {
            val r = record(origin, id)
            val dir = work.folder(origin, id)
            if (r == null || !dir.isDirectory) {
                trace("lpl: $what of photo $id not written: the photo is no longer on the phone")
                return false
            }
            write(dir)
            trace("lpl: $what of ${r.id}/${r.filename} written")
            return true
        }
    }

    // ------------------------------------------------------------------ brief P2i: "sync automatically"

    /** Whether the user lets the app sync by itself (the switch in the app's LightPhotoLibrary settings). */
    val autoSync: Boolean get() = settings.autoSync.value

    /**
     * The darkroom left the photo ("done"): its edit is sent now when automatic syncing is on; null when it is
     * off (the edit stays on the phone, the row says it is not synced yet). The caller calls [left] first.
     */
    suspend fun syncAfterDone(origin: ServerOrigin, id: Long): SyncOutcome? {
        if (!autoSync) { trace("lpl: photo $id kept on the phone: automatic sync is off"); return null }
        return sync(origin, id)
    }

    /**
     * The sync the app starts by itself (the background job, the app coming to the front, previews that waited
     * for the engine): [syncPending] when automatic syncing is on; null, nothing sent, when it is off.
     */
    suspend fun syncPendingAutomatically(includeOpen: Boolean = false): Boolean? {
        if (!autoSync) return null
        return syncPending(includeOpen)
    }

    /** Whether the app may download a photo nobody asked for yet (the next photo of the filmstrip). */
    val fetchAhead: Boolean get() = autoSync

    // ------------------------------------------------------------------ heartbeat and jobs

    /** The agent state for [origin]'s heartbeat (same item shape and statuses as the PC agent). */
    fun state(origin: ServerOrigin): AgentState {
        val open = editor.darkroomOpen()
        val lines = _photos.value.filter { it.server == origin }.sortedBy { it.checkoutTs }.map { r ->
            val status = when {
                r.status == PhotoStatus.EDITING && !editor.isOpen(work.raw(r)) -> settledStatus(r)
                else -> r.status
            }
            val st = _steps.value[r.key]
            val ver = editor.openVersion(work.raw(r)).takeIf { it > 0 && r.versionsCap }
            WorkingSetLine(r.id, r.filename, status, r.checkoutTs, r.syncedTs, r.error, st?.step, st?.progress, ver)
        }
        // brief P2i: while automatic syncing is off, LightPhotoLibrary is told so in the device's message
        val msg = if (autoSync) message else listOf(message, SyncSettings.OFF_TEXT).filter { it.isNotEmpty() }.joinToString(" · ")
        return AgentState(lines, open, true, version(), msg)
    }

    /**
     * One heartbeat to every added server; returns the jobs LPL handed out (each is handed out once: the
     * caller must carry it out and report it with [jobDone]). A server that does not answer gives no jobs.
     */
    suspend fun heartbeat(): List<Pair<ServerOrigin, Job>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<ServerOrigin, Job>>()
        synchronized(extraJobs) { out += extraJobs; extraJobs.clear() }
        for (s in servers.list()) heartbeatTo(s.origin, s.deviceName)?.let { a -> for (j in a.jobs) out += s.origin to j }
        out
    }

    /** One heartbeat to one server; null when it did not answer. Reports the transfers and acts on `cancel` (brief P2k). */
    private fun heartbeatTo(origin: ServerOrigin, deviceName: String): HeartbeatAnswer? = try {
        val a = net(origin) { it.heartbeat(servers.installId, deviceName, state(origin), transfers.report(origin)) }
        // brief P3i (R18): what the server can receive; no `server_caps` = an older server, exports wait
        if (exports.capsOf(origin) != (a.serverCaps ?: emptySet<String>())) { exports.setCaps(origin, a.serverCaps); _capsTick.value = _capsTick.value + 1 }
        // `cancel` comes only from a server that knows `caps`: only then are edit jobs run as batches
        if (a.cancel != null) batchServers += origin
        transfers.applyCancel(origin, a.cancel)
        if (a.cancel != null) for (j in a.jobs) if (j.kind == "edit") transfers.receive(origin, j.id, j.ids)
        a
    } catch (e: LplException) {
        null // shown on the list screen; the next heartbeat tries again
    } catch (e: IllegalStateException) {
        null // the saved password cannot be read: the list screen asks for it
    }

    // ------------------------------------------------------------------ exports to LightPhotoLibrary (brief P3i, request R18)

    /** What the app keeps about exports: the servers' `server_caps` and the exports waiting for a server. */
    val exports = ExportBook(File(work.root.parentFile ?: work.root, "exports"))

    private val _capsTick = MutableStateFlow<Int>(0)
    /** Counts the changes of any server's `server_caps`: the export screen reads the offer again. */
    val capsChanged: StateFlow<Int> = _capsTick.asStateFlow()

    /** Whether [origin] said, in its last heartbeat answer, that it receives exports; null = it has not answered yet. */
    fun receivesExports(origin: ServerOrigin): Boolean? = exports.capsOf(origin)?.contains("exports")

    /** How many exports wait for their server. */
    fun queuedExports(): Int = exports.count()

    /** What became of an export handed to [sendExport]. */
    sealed interface ExportOutcome {
        /** stored by LPL: [path] as LPL answered */
        data class Sent(val exportId: Long, val path: String) : ExportOutcome
        /** the server did not answer: kept, and sent with the next sync */
        data object Queued : ExportOutcome
    }

    /**
     * Send the export [file] of photo [id] (version [version]; 0 = the photo's own edit) under [name]. The photo shows the
     * step `exporting` with the share sent while it goes ([onProgress] gets it too). A server that cannot be reached
     * leaves the file in the queue ([ExportOutcome.Queued]); any other refusal is thrown as [LplException].
     */
    suspend fun sendExport(origin: ServerOrigin, id: Long, version: Int, name: String, file: File, onProgress: (Float) -> Unit = {}): ExportOutcome =
        withContext(Dispatchers.IO) {
            requireAdded(origin)
            step(origin, id, Step(Step.EXPORTING, 0.0))
            try {
                val total = file.length().coerceAtLeast(1)
                val a = net(origin) { c ->
                    c.postExport(id, servers.installId, version, name, file) { sent ->
                        val f = (sent.toDouble() / total).coerceIn(0.0, 1.0)
                        step(origin, id, Step(Step.EXPORTING, f))
                        onProgress(f.toFloat())
                    }
                }
                ExportOutcome.Sent(a.exportId, a.path)
            } catch (e: LplException) {
                if (!e.transient) throw e
                exports.queue(origin, id, version, name, file)
                trace("export of $name kept for the next sync: ${e.message}")
                ExportOutcome.Queued
            } finally {
                step(origin, id, null)
            }
        }

    /**
     * Send the exports that waited for their server, in the order they were made. Stops at the first server that does
     * not answer (the rest wait); one the server refuses (an unknown photo, a bad file) is dropped and said in the trace.
     * Only to a server that said it receives exports. Returns how many were sent.
     */
    suspend fun sendQueuedExports(): Int = withContext(Dispatchers.IO) {
        var sent = 0
        val down = HashSet<ServerOrigin>()
        for (q in exports.list()) {
            if (q.server in down || servers.get(q.server) == null || receivesExports(q.server) != true) continue
            step(q.server, q.photo, Step(Step.EXPORTING, 0.0))
            try {
                val total = q.file.length().coerceAtLeast(1)
                net(q.server) { c -> c.postExport(q.photo, servers.installId, q.version, q.name, q.file) { b -> step(q.server, q.photo, Step(Step.EXPORTING, b.toDouble() / total)) } }
                exports.remove(q)
                sent++
            } catch (e: LplException) {
                if (e.transient || e.failure == LplFailure.UNAUTHORIZED) down += q.server
                else { exports.remove(q); trace("queued export ${q.name} dropped: ${e.message}") }
            } catch (e: IllegalStateException) {
                down += q.server // the saved password cannot be read: the list screen asks for it
            } finally {
                step(q.server, q.photo, null)
            }
        }
        sent
    }

    // ------------------------------------------------------------------ batches (brief P2k)

    /** Every transfer the device holds for LPL's edit jobs; see [TransferBook]. */
    val transfers = TransferBook()

    /** For the list: how far each server's batch is, or how it ended when stopped. */
    val batches: StateFlow<Map<ServerOrigin, BatchView>> get() = transfers.views

    private val batchServers: MutableSet<ServerOrigin> = ConcurrentHashMap.newKeySet()
    private val extraJobs = ArrayList<Pair<ServerOrigin, Job>>()

    /** Whether the server has shown it knows batches (it sent a `cancel`); an old one gets the old edit job. */
    fun isBatchServer(origin: ServerOrigin) = origin in batchServers

    /**
     * Whether an `edit` job is carried out by [runEditJob]: from a batch server, and from any server when the job
     * says `open: false` (the darkroom must never open for it, whatever the server knows).
     */
    fun runsAsBatch(origin: ServerOrigin, job: Job) = job.kind == "edit" && (isBatchServer(origin) || !job.open)

    /** The "stop" button: this phone's running and waiting batch jobs end as `stopped` (what arrived is kept). */
    fun stopBatches() = transfers.stopAll()

    /** How an edit job ended on this phone: the photos to open (copied or already there), unless stopped. */
    class EditOutcome(val state: String, val toOpen: List<Long>)

    /**
     * Carry out an `edit` job from a batch server: copy the photos one by one (each id once), checking for
     * Stop between files and between chunks; then report the end (a heartbeat at once, then `/done`).
     * The caller opens the darkroom on [EditOutcome.toOpen] when [Job.open] and the job was not stopped.
     */
    suspend fun runEditJob(origin: ServerOrigin, job: Job): EditOutcome = withContext(Dispatchers.IO) {
        val ids = jobIds(origin, job).distinct()
        transfers.receive(origin, job.id, ids)
        if (!transfers.begin(origin, job.id)) { finishJob(origin, job, TransferState.STOPPED); return@withContext EditOutcome(TransferState.STOPPED, emptyList()) }
        val stop = { transfers.shouldStop(origin, job.id) }
        for (id in ids) {
            if (stop()) break
            if (transfers.shouldSkip(origin, job.id, id)) continue
            val held = record(origin, id)
            if (held != null && work.hasRaw(held)) { transfers.mark(origin, job.id, id, TransferBook.Outcome.PRESENT); continue }
            transfers.current(origin, job.id, id, 0)
            try {
                take(origin, id, refresh = false, batch = true,
                    cancelled = { stop() || transfers.shouldSkip(origin, job.id, id) },
                    onBytes = { transfers.current(origin, job.id, id, it) })
                transfers.mark(origin, job.id, id, TransferBook.Outcome.ARRIVED)
            } catch (e: LplException) {
                val skipped = e.failure == LplFailure.CANCELLED && !stop() && transfers.shouldSkip(origin, job.id, id)
                transfers.current(origin, job.id, null)
                when {
                    stop() -> break
                    skipped -> transfers.mark(origin, job.id, id, TransferBook.Outcome.SKIPPED)
                    else -> transfers.mark(origin, job.id, id, TransferBook.Outcome.FAILED)
                }
            } catch (e: ServerNotAddedException) {
                transfers.mark(origin, job.id, id, TransferBook.Outcome.FAILED)
            }
        }
        val lists = transfers.lists(origin, job.id).orEmpty()
        val there = lists["arrived"].orEmpty() + lists["present"].orEmpty()
        val state = when {
            stop() -> TransferState.STOPPED
            there.isEmpty() && lists["failed"].orEmpty().isNotEmpty() -> TransferState.FAILED
            else -> TransferState.DONE
        }
        finishJob(origin, job, state)
        EditOutcome(state, if (state == TransferState.STOPPED) emptyList() else there)
    }

    /** A job ended: the final state in a heartbeat at once, then `/done` with the lists; held until `/done` is answered. */
    suspend fun finishJob(origin: ServerOrigin, job: Job, state: String) = withContext(Dispatchers.IO) {
        transfers.finish(origin, job.id, state)
        ranJobs.remember(origin, job.id, state == TransferState.DONE)
        val s = servers.list().firstOrNull { it.origin == origin }
        if (s != null) heartbeatTo(origin, s.deviceName)?.let { a -> synchronized(extraJobs) { for (j in a.jobs) extraJobs += origin to j } }
        try {
            net(origin) { it.jobDone(job.id, state, transfers.lists(origin, job.id)) }
            transfers.forget(origin, job.id)
        } catch (e: LplException) { /* still held: the next heartbeat reports the end again; LPL hands it out again (R13) and it is reported, not run */ }
    }

    suspend fun jobDone(origin: ServerOrigin, job: Job, ok: Boolean) = withContext(Dispatchers.IO) {
        // a batch job whose end could not be reported before: report it again with its lists
        val held = transfers.state(origin, job.id)?.takeIf { transfers.holds(origin, job.id) && it != TransferState.QUEUED && it != TransferState.RUNNING }
        if (held != null) { finishJob(origin, job, held); return@withContext }
        ranJobs.remember(origin, job.id, ok)
        try { net(origin) { it.jobDone(job.id, ok) } } catch (e: LplException) { /* LPL hands it out again (R13); it is then reported, not run */ }
    }

    /**
     * R13: LightPhotoLibrary hands a job out again when it was never reported done. A job this app already
     * ran is not run twice: [ranBefore] gives its result, and the caller reports it again.
     */
    fun ranBefore(origin: ServerOrigin, job: Job): Boolean? = ranJobs.result(origin, job.id)

    private val ranJobs = RanJobs(File(work.root.parentFile ?: work.root, "ran-jobs.txt"))

    /** The ids a job applies to: its list, or every photo held from that server when it says `all`. */
    fun jobIds(origin: ServerOrigin, job: Job): List<Long> =
        if (job.all) _photos.value.filter { it.server == origin }.map { it.id } else job.ids

    /**
     * Carry out a `sync` or `release` job (PLAN.md 4.1). `edit` and `open` need the darkroom and are
     * done by the app. Returns whether the job succeeded.
     */
    suspend fun runSyncOrRelease(origin: ServerOrigin, job: Job): Boolean {
        var ok = true
        val ids = jobIds(origin, job)
        val what = if (job.kind == "release") "save and remove" else "save"
        var sent = 0; var waiting = 0; var kept = 0; var removed = 0
        serverNote(origin, "LightPhotoLibrary asked to $what ${count(ids.size)}…")
        for (id in ids) {
            val name = record(origin, id)?.filename ?: continue
            val wasOpen = record(origin, id)?.let { editor.isOpen(work.raw(it)) } == true
            val hadChanges = record(origin, id)?.let { isDirty(it) } == true
            note(origin, id, "LightPhotoLibrary asked to $what this photo: sending…")
            val o = sync(origin, id, force = job.kind == "sync")
            val wroteOpen = lastWroteOpen.remove(origin to id) == true
            val sentText = when {
                wroteOpen -> "saved your open changes and sent them"
                wasOpen -> "sent the edit as of your last \"done\" (no changes since)"
                hadChanges -> "sent the edit"
                else -> "sent the edit again (LightPhotoLibrary had it already)"
            }
            when (o) {
                SyncOutcome.WAITING, SyncOutcome.FAILED -> {
                    ok = false; waiting++
                    note(origin, id, "LightPhotoLibrary asked for this photo; not sent: ${record(origin, id)?.error?.ifEmpty { null } ?: "the server did not answer"}" +
                        if (o == SyncOutcome.WAITING) "; tried again later" else "")
                    continue
                }
                SyncOutcome.READ_ONLY -> { note(origin, id, "LightPhotoLibrary asked for this photo; not sent: ${UserText.NEWER_DARKTABLE}"); continue }
                SyncOutcome.NOT_HELD -> continue
                SyncOutcome.SYNCED -> { sent++; note(origin, id, "$sentText · as LightPhotoLibrary asked") }
            }
            if (job.kind == "release") {
                when (remove(origin, id)) {
                    RemoveResult.Removed -> { removed++; note(origin, id, null); serverNote(origin, "$name: $sentText, then removed from this phone, as LightPhotoLibrary asked") }
                    RemoveResult.OpenInDarkroom -> { ok = false; kept++; note(origin, id, "$sentText; LightPhotoLibrary asked to remove it from this phone: it stays while it is open in the darkroom") }
                    RemoveResult.Unsynced -> { ok = false; kept++; note(origin, id, "LightPhotoLibrary asked to remove it from this phone: it stays, it has changes LightPhotoLibrary does not have yet") }
                }
            }
        }
        val parts = listOfNotNull(
            if (sent > 0) "${count(sent)} sent" else null,
            if (removed > 0) "$removed removed from this phone" else null,
            if (kept > 0) "$kept kept on this phone" else null,
            if (waiting > 0) "$waiting waiting to sync" else null,
        )
        serverNote(origin, "LightPhotoLibrary asked to $what ${count(ids.size)}: " + (parts.joinToString(", ").ifEmpty { "nothing to do" }))
        return ok
    }

    private fun count(n: Int) = if (n == 1) "1 photo" else "$n photos"

    private val lastWroteOpen = ConcurrentHashMap<Pair<ServerOrigin, Long>, Boolean>()

    private val _notes = MutableStateFlow<Map<Pair<ServerOrigin, Long>, String>>(emptyMap())
    /** Brief P2f: what a job from LightPhotoLibrary did with a photo, in plain words, for its row and the darkroom. */
    val notes: StateFlow<Map<Pair<ServerOrigin, Long>, String>> = _notes.asStateFlow()

    private val _serverNotes = MutableStateFlow<Map<ServerOrigin, String>>(emptyMap())
    /** Brief P2f: the last job from each server and how it went, for the list's header. */
    val serverNotes: StateFlow<Map<ServerOrigin, String>> = _serverNotes.asStateFlow()

    private fun note(o: ServerOrigin, id: Long, text: String?) = _notes.update { if (text == null) it - (o to id) else it + ((o to id) to text) }
    private fun serverNote(o: ServerOrigin, text: String) = _serverNotes.update { it + (o to text) }

    /** The user saw the note (opened the photo, synced it by hand): drop it. */
    fun clearNote(o: ServerOrigin, id: Long) = note(o, id, null)
}

/** The ids of jobs this app ran, with their result, kept on disk (the last [MAX] of them). */
internal class RanJobs(private val file: File) {
    private val lines = LinkedHashMap<String, Boolean>()

    init {
        runCatching { file.readLines() }.getOrNull()?.forEach { l ->
            val p = l.split(' ')
            if (p.size == 3) lines[p[0] + " " + p[1]] = p[2] == "ok"
        }
    }

    @Synchronized fun result(origin: ServerOrigin, id: Long): Boolean? = lines["${origin.key} $id"]

    @Synchronized fun remember(origin: ServerOrigin, id: Long, ok: Boolean) {
        lines["${origin.key} $id"] = ok
        while (lines.size > MAX) lines.remove(lines.keys.first())
        runCatching { file.parentFile?.mkdirs(); atomicWrite(file, lines.entries.joinToString("") { "${it.key} ${if (it.value) "ok" else "failed"}\n" }.toByteArray()) }
    }

    companion object { const val MAX = 500 }
}

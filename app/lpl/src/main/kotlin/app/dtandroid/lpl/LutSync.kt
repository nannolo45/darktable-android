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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/*
 * Brief P6b, PLAN.md 4.6 (R19): the phone's side of the LUT folder through LightPhotoLibrary. Per server the phone
 * keeps, per path, `base` = the hash it last uploaded or took in; a local change = the file's hash differs from base,
 * a server change = the server's hash differs from base. Uploads with x-base-sha256 = base; a 409 or a change on
 * both sides is a conflict the user settles: mine / theirs / keep both.
 */

/** A path's entry in `GET /api/luts`. */
data class LutEntry(val path: String, val size: Long, val sha256: String, val seq: Long, val changedAt: Double,
                    val deviceName: String?, val deleted: Boolean) {
    companion object {
        fun of(e: kotlinx.serialization.json.JsonElement): LutEntry? {
            val o = e as? JsonObject ?: return null
            return LutEntry(o["path"].str() ?: return null, o["size"].long() ?: 0, o["sha256"].str() ?: return null, o["seq"].long() ?: 0,
                o["changed_at"].num() ?: 0.0, o["device_name"].str(), (o["deleted"] as? JsonPrimitive)?.content == "true")
        }
    }

    fun toJson() = buildJsonObject {
        put("path", path); put("size", size); put("sha256", sha256); put("seq", seq); put("changed_at", changedAt)
        put("device_name", deviceName?.let { JsonPrimitive(it) } ?: JsonNull); put("deleted", deleted)
    }
}

data class LutList(val seq: Long, val files: List<LutEntry>)

/** The answer to an upload: stored, or 409 with the server's current entry. */
sealed interface LutPut {
    data class Stored(val sha256: String, val seq: Long) : LutPut
    data class ChangedElsewhere(val current: LutEntry?) : LutPut
}

private fun enc(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

/** `GET /api/luts?since=`. */
fun LplClient.lutList(since: Long): LutList {
    val o = callJson("GET", "/api/luts?since=$since")
    return LutList(o["seq"].long() ?: 0, (o["files"] as? JsonArray).orEmpty().mapNotNull { LutEntry.of(it) })
}

/** `PUT /api/luts/file`: [file]'s bytes, its [sha256], and [base] ("" = none, null = no check). */
fun LplClient.lutPut(path: String, agent: String, file: File, sha256: String, base: String?): LutPut {
    val h = HashMap<String, String>()
    h["x-sha256"] = sha256
    if (base != null) h["x-base-sha256"] = base
    return callRaw("PUT", "/api/luts/file?path=${enc(path)}&agent=${enc(agent)}", h, file, allow = setOf(409)) { r ->
        val o = parseAnswer("PUT /api/luts/file", r.body.readBytes())
        if (r.status == 409) LutPut.ChangedElsewhere(o["file"]?.let { LutEntry.of(it) })
        else LutPut.Stored(o["sha256"].str() ?: sha256, o["seq"].long() ?: 0)
    }
}

/** `GET /api/luts/file` into [into] (a temporary file); the bytes' SHA-256. 404 -> [LplFailure.NOT_FOUND]. */
fun LplClient.lutGet(path: String, into: File): String = callRaw("GET", "/api/luts/file?path=${enc(path)}") { r ->
    into.outputStream().use { out -> r.body.copyLimited(out) }
    LutPaths.sha256(into)
}

/** `PUT /api/luts/state`: the phone's whole list. */
fun LplClient.lutState(agent: String, files: Map<String, Pair<String?, String>>) {
    val body = buildJsonObject {
        put("folder", "on this phone"); put("reason", JsonNull)
        put("files", buildJsonObject {
            for ((p, v) in files) put(p, buildJsonObject {
                put("sha256", v.first?.let { JsonPrimitive(it) } ?: JsonNull); put("state", v.second)
            })
        })
    }
    callJson("PUT", "/api/luts/state?agent=${enc(agent)}", body.toString().toByteArray())
}

/** A path changed on the phone and in LPL; the server's side as last seen. */
data class LutConflict(val path: String, val serverSha: String, val serverDevice: String?, val serverChangedAt: Double)

/** A path's sync state for the LUT files screen. */
enum class LutSyncState(val json: String, val words: String) {
    SYNCED("ok", "in LightPhotoLibrary"),
    LOCAL("ok", "not sent yet"),
    CONFLICT("conflict", "changed here and in LightPhotoLibrary"),
    REMOVED("removed", "removed from this phone"),
    ONLY_LPL("ok", "in LightPhotoLibrary, not on this phone"),
    /** P6c: the phone's copy is unchanged and LightPhotoLibrary has another one; never replaced without asking */
    NEWER_IN_LPL("ok", "newer version in LightPhotoLibrary"),
    UNUSABLE("unusable", "not usable here"),
}

/** What the screens show per server. */
data class LutView(
    val active: Boolean = false,
    val states: Map<String, LutSyncState> = emptyMap(),
    val conflicts: List<LutConflict> = emptyList(),
    /** paths LPL has (not removed) */
    val onServer: Set<String> = emptySet(),
    /** P6c: LPL's entry (not removed) per path, for the sizes in the "missing here" list */
    val entries: Map<String, LutEntry> = emptyMap(),
    val error: String? = null,
    val checkedAt: Double = 0.0,
)

/**
 * Brief P6b: the phone's LUT folder exchanged with every added server that lists `luts` in `server_caps`. Every call
 * is blocking network work on [Dispatchers.IO]; one server at a time (a mutex per server).
 */
class LutSync(
    private val servers: ServerStore,
    val folder: LutFolder,
    private val dir: File?,
    private val clientOf: (ServerOrigin) -> LplClient,
    /** the server's last `server_caps`, null = not known yet */
    private val serverCaps: (ServerOrigin) -> Set<String>?,
    private val autoSync: () -> Boolean,
    /** P6c: "Download LUT files from LightPhotoLibrary automatically" (off by default) */
    private val autoDownload: () -> Boolean = { false },
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val trace: (String) -> Unit = {},
) {
    private class Book(
        var since: Long = 0,
        val base: MutableMap<String, String> = LinkedHashMap(),
        val server: MutableMap<String, LutEntry> = LinkedHashMap(),
        val conflicts: MutableMap<String, LutConflict> = LinkedHashMap(),
        var checkedAt: Double = 0.0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val books = ConcurrentHashMap<ServerOrigin, Book>()
    private val locks = ConcurrentHashMap<ServerOrigin, Mutex>()

    private val _views = MutableStateFlow<Map<ServerOrigin, LutView>>(emptyMap())
    val views: StateFlow<Map<ServerOrigin, LutView>> = _views.asStateFlow()

    fun active(o: ServerOrigin): Boolean = serverCaps(o)?.contains("luts") == true

    /** What the heartbeat to [o] adds to `caps`: `luts` once the server listed it in `server_caps` (as R11's `shared`). */
    fun caps(o: ServerOrigin): List<String> = if (active(o)) listOf("luts") else emptyList()

    private fun lock(o: ServerOrigin) = locks.getOrPut(o) { Mutex() }

    // ---------------------------------------------------------------- the book on disk

    private fun file(o: ServerOrigin) = dir?.let { File(it, "${o.key}.json") }
    private fun book(o: ServerOrigin): Book = books.getOrPut(o) { load(o) }

    private fun load(o: ServerOrigin): Book {
        val obj = file(o)?.let { readWhole(it) }?.let { runCatching { json.parseToJsonElement(it.decodeToString()) as JsonObject }.getOrNull() }
            ?: return Book()
        val b = Book(since = obj["since"].long() ?: 0, checkedAt = obj["checked_at"].num() ?: 0.0)
        (obj["base"] as? JsonObject)?.forEach { (p, v) -> v.str()?.let { b.base[p] = it } }
        (obj["server"] as? JsonArray)?.forEach { e -> LutEntry.of(e)?.let { b.server[it.path] = it } }
        (obj["conflicts"] as? JsonArray)?.forEach { e ->
            val x = e as? JsonObject ?: return@forEach
            val p = x["path"].str() ?: return@forEach
            b.conflicts[p] = LutConflict(p, x["server_sha"].str() ?: return@forEach, x["server_device"].str(), x["server_changed_at"].num() ?: 0.0)
        }
        return b
    }

    private fun save(o: ServerOrigin, b: Book) {
        val f = file(o) ?: return
        val obj = buildJsonObject {
            put("format", 1); put("since", b.since); put("checked_at", b.checkedAt)
            put("base", buildJsonObject { for ((p, h) in b.base) put(p, h) })
            put("server", JsonArray(b.server.values.map { it.toJson() }))
            put("conflicts", buildJsonArray {
                for (c in b.conflicts.values) add(buildJsonObject {
                    put("path", c.path); put("server_sha", c.serverSha)
                    put("server_device", c.serverDevice?.let { JsonPrimitive(it) } ?: JsonNull); put("server_changed_at", c.serverChangedAt)
                })
            })
        }
        runCatching { f.parentFile?.mkdirs(); atomicWrite(f, obj.toString().toByteArray()) }
            .onFailure { trace("luts: state of ${o.url} not written: ${it.message}") }
    }

    // ---------------------------------------------------------------- passes

    /**
     * One pass with [o]: ask what changed, then, when automatic sync is on or [transfer] says so, upload the phone's
     * changes and download LPL's (4.6), and report the list. Without the cap, nothing happens. Network failures are
     * kept in the view and thrown only to callers that want them ([throwing]).
     */
    suspend fun pass(o: ServerOrigin, transfer: Boolean = autoSync(), throwing: Boolean = false): Unit = withContext(Dispatchers.IO) {
        if (!active(o) || servers.get(o) == null) { _views.update { it + (o to LutView(active = false)) }; return@withContext }
        lock(o).withLock {
            val b = book(o)
            try {
                val c = clientOf(o)
                val list = c.lutList(b.since)
                for (e in list.files) b.server[e.path] = e
                b.since = list.seq
                b.checkedAt = clock()
                if (transfer) exchange(o, b, c)
                c.lutState(servers.installId, reportOf(b))
                save(o, b)
                publish(o, b)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                save(o, b)
                publish(o, b, (e as? LplException)?.message ?: e.message ?: e.javaClass.simpleName)
                if (throwing) throw e
            }
        }
    }

    suspend fun passAll(transfer: Boolean = autoSync()) { for (s in servers.list()) pass(s.origin, transfer) }

    private val lastPoll = ConcurrentHashMap<ServerOrigin, Long>()

    /** At most once a minute per server while the app is open (as R11's poll). */
    suspend fun pollIfDue(nowMs: Long = System.currentTimeMillis()) {
        for (s in servers.list()) {
            val last = lastPoll[s.origin] ?: 0L
            if (nowMs - last >= 60_000L) { lastPoll[s.origin] = nowMs; pass(s.origin) }
        }
    }

    /** The rules of 4.6 for every path the phone or LPL has. */
    private fun exchange(o: ServerOrigin, b: Book, c: LplClient) {
        val removed = folder.removed()
        val download = autoDownload()  // P6c: off = a pass downloads nothing
        val local = folder.files().filter { LutPaths.problem(it.path) == null }.map { it.path }.toSet()
        for (p in (local + b.server.keys).sorted()) {
            val s = b.server[p]?.takeIf { !it.deleted }
            val base = b.base[p]
            val l = if (p in local) folder.hashOf(p) else null
            if (l == null) {
                if (base != null && p !in removed) { trace("luts: $p is gone from the folder: kept as removed"); folder.remove(p); continue }
                if (download && s != null && p !in removed && LutPaths.wanted(p)) download(c, b, p, s)
                continue
            }
            if (s != null && s.sha256 == l) { b.base[p] = l; b.conflicts.remove(p); continue }
            if (p in b.conflicts) continue  // settled by the user
            val localChange = base == null || l != base
            val serverChange = s != null && s.sha256 != base
            if (localChange && serverChange && base != null) { conflict(b, p, s!!); continue }
            if (localChange) {
                when (val r = c.lutPut(p, servers.installId, folder.fileOf(p)!!, l, base ?: "")) {
                    is LutPut.Stored -> { b.base[p] = l; stored(b, o, p, l, r.seq); trace("luts: sent $p") }
                    is LutPut.ChangedElsewhere -> r.current?.let { b.server[p] = it; conflict(b, p, it) }
                }
            }
            // P6c: a server change of a file the phone has and did not change is never taken by itself ("Update" asks)
        }
    }

    /** What the server holds after this phone's upload (until the next list says more). */
    private fun stored(b: Book, o: ServerOrigin, p: String, sha: String, seq: Long) {
        b.server[p] = LutEntry(p, folder.fileOf(p)?.length() ?: 0, sha, seq, clock(), servers.get(o)?.deviceName, false)
    }

    private fun conflict(b: Book, p: String, s: LutEntry) {
        b.conflicts[p] = LutConflict(p, s.sha256, s.deviceName, s.changedAt)
        trace("luts: $p changed here and in LightPhotoLibrary")
    }

    /** The server's file of [p] over the local one (or into its empty place); base = its hash. */
    private fun download(c: LplClient, b: Book, p: String, s: LutEntry) {
        val tmp = File.createTempFile("lut-", ".part", folder.root.parentFile ?: folder.root)
        try {
            val h = c.lutGet(p, tmp)
            if (h != s.sha256) { trace("luts: $p arrived with another hash than listed (changed meanwhile): taken at the next pass"); b.since = minOf(b.since, s.seq - 1); return }
            val err = folder.write(p) { out -> tmp.inputStream().use { it.copyTo(out) } }
            if (err != null) { trace("luts: $p not written: $err"); return }
            b.base[p] = h
            b.conflicts.remove(p)
            trace("luts: took $p")
        } finally {
            tmp.delete()
        }
    }

    private fun reportOf(b: Book): Map<String, Pair<String?, String>> {
        val out = LinkedHashMap<String, Pair<String?, String>>()
        for (f in folder.files()) {
            if (LutPaths.problem(f.path) != null) continue
            val st = when {
                f.path in b.conflicts -> "conflict"
                !LutPaths.usable(f.name) -> "unusable"
                else -> "ok"
            }
            out[f.path] = folder.hashOf(f.path) to st
        }
        for (p in folder.removed()) if (p !in out) out[p] = null to "removed"
        return out
    }

    private fun publish(o: ServerOrigin, b: Book, error: String? = null) {
        val local = folder.files().filter { LutPaths.problem(it.path) == null }
        val removed = folder.removed()
        val states = LinkedHashMap<String, LutSyncState>()
        for (f in local) states[f.path] = when {
            f.path in b.conflicts -> LutSyncState.CONFLICT
            !f.usable -> LutSyncState.UNUSABLE
            folder.hashOf(f.path).let { h -> h != null && b.base[f.path] == h && b.server[f.path]?.let { e -> !e.deleted && e.sha256 == h } == true } -> LutSyncState.SYNCED
            folder.hashOf(f.path).let { h -> h != null && b.base[f.path] == h && b.server[f.path]?.let { e -> !e.deleted && e.sha256 != h } == true } -> LutSyncState.NEWER_IN_LPL
            else -> LutSyncState.LOCAL
        }
        for ((p, e) in b.server) if (!e.deleted && p !in states) states[p] = if (p in removed) LutSyncState.REMOVED else LutSyncState.ONLY_LPL
        _views.update {
            it + (o to LutView(true, states, b.conflicts.values.toList(),
                b.server.values.filter { e -> !e.deleted }.map { e -> e.path }.toSet(),
                b.server.filterValues { e -> !e.deleted }, error, b.checkedAt))
        }
    }

    /** The view from disk, before any call. */
    fun showStored() { for (s in servers.list()) if (active(s.origin)) publish(s.origin, book(s.origin)) }

    /** Whether any server with the cap has [path] (not removed there). */
    fun onServer(path: String): ServerOrigin? =
        servers.list().map { it.origin }.firstOrNull { o -> active(o) && book(o).server[path]?.deleted == false }

    // ---------------------------------------------------------------- the user's actions

    /** "Get from LightPhotoLibrary": clears a "removed" mark and downloads [path]. An error text, or null when done. */
    suspend fun fetch(path: String): String? = withContext(Dispatchers.IO) {
        val o = onServer(path) ?: return@withContext "LightPhotoLibrary does not have $path"
        lock(o).withLock {
            val b = book(o)
            try {
                val s = b.server[path] ?: return@withLock "LightPhotoLibrary does not have $path"
                folder.unmark(path)
                download(clientOf(o), b, path, s)
                save(o, b); publish(o, b)
                if (folder.has(path)) null else "$path could not be taken (see the log)"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** "Get all" of the missing-files list: every path in [paths] that LPL has, one after the other. An error text, or null. */
    suspend fun fetchAll(paths: List<String>): String? {
        var first: String? = null
        var failed = 0
        for (p in paths) fetch(p)?.let { failed++; if (first == null) first = it }
        return if (failed == 0) null else "$failed of ${paths.size} not taken: $first"
    }

    /** Settle the conflict of [path] with [o]: "mine", "theirs" or "both" (4.6). An error text, or null when done. */
    suspend fun resolve(o: ServerOrigin, path: String, choice: String): String? = withContext(Dispatchers.IO) {
        lock(o).withLock {
            val b = book(o)
            val cf = b.conflicts[path] ?: return@withLock null
            val c = clientOf(o)
            try {
                val s = b.server[path]
                when (choice) {
                    "mine" -> {
                        val h = folder.hashOf(path) ?: return@withLock "$path is no longer on this phone"
                        when (val r = c.lutPut(path, servers.installId, folder.fileOf(path)!!, h, cf.serverSha)) {
                            is LutPut.Stored -> { b.base[path] = h; b.conflicts.remove(path); stored(b, o, path, h, r.seq) }
                            is LutPut.ChangedElsewhere -> { r.current?.let { b.server[path] = it; conflict(b, path, it) }; return@withLock "$path changed again in LightPhotoLibrary" }
                        }
                    }
                    "theirs" -> {
                        if (s == null || s.deleted) { b.conflicts.remove(path); b.base.remove(path) }
                        else { b.conflicts.remove(path); download(c, b, path, s) }
                    }
                    "both" -> {
                        val device = servers.get(o)?.deviceName ?: "phone"
                        val other = LutPaths.keepBothName(path, device) { folder.has(it) || b.server[it]?.deleted == false }
                        if (!folder.rename(path, other)) return@withLock "$path could not be renamed"
                        val h = folder.hashOf(other)!!
                        when (val r = c.lutPut(other, servers.installId, folder.fileOf(other)!!, h, "")) {
                            is LutPut.Stored -> { b.base[other] = h; stored(b, o, other, h, r.seq) }
                            is LutPut.ChangedElsewhere -> trace("luts: $other exists in LightPhotoLibrary: sent at the next pass")
                        }
                        b.conflicts.remove(path); b.base.remove(path)
                        if (s != null && !s.deleted) download(c, b, path, s)
                    }
                    else -> return@withLock "unknown choice $choice"
                }
                c.lutState(servers.installId, reportOf(b))
                save(o, b); publish(o, b)
                null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                save(o, b); publish(o, b, e.message)
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** "Remove" in Settings › LUT files: the phone's copy only (4.6). */
    fun remove(path: String): Boolean {
        val ok = folder.remove(path)
        for (s in servers.list()) if (active(s.origin)) publish(s.origin, book(s.origin))
        return ok
    }
}

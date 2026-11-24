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
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the app's shared items live (the engine's data.db for presets and styles, layouts.json for layouts).
 * The app implements it with the engine and its layout store; tests with a map.
 */
interface SharedPort {
    /** Every item this device would share now, with its own hash (4.5 "Finding local changes"). */
    suspend fun snapshot(): List<SharedLocal>
    /** Store a body (insert or replace); false = it needs a newer darktable, nothing was written. */
    suspend fun apply(body: JsonObject): Boolean
    /** Remove the item of [key]; nothing happens if there is none. */
    suspend fun delete(key: SharedKey)
    /** The engine's version; when it changes, items marked `unusable` are offered again (4.5). Null = not known yet. */
    suspend fun engineVersion(): String? = null
}

/**
 * PLAN.md 4.5 "Not shared": darktable's shipped styles (names starting `_l10n_`) and desktop's "last modified layout"
 * in every translation, from docs/design/R11-excluded-names.json (packed as a resource). The one place the phone
 * applies the exclusions: neither uploaded nor offered.
 */
class SharedExclusions(val stylePrefix: String, val layoutNames: Set<String>) {
    fun excluded(k: SharedKey) = when (k.kind) {
        SharedKind.STYLE -> k.name.startsWith(stylePrefix)
        SharedKind.LAYOUT -> k.name in layoutNames
        SharedKind.PRESET -> false
    }

    companion object {
        fun parse(text: String): SharedExclusions {
            val o = Json.parseToJsonElement(text) as JsonObject
            return SharedExclusions(o["style_prefix"].str() ?: error("no style_prefix"),
                o["layout_names"].arr()?.mapNotNull { it.str() }?.toSet() ?: error("no layout_names"))
        }

        val default: SharedExclusions by lazy {
            val text = SharedExclusions::class.java.getResourceAsStream("/dtandroid/lpl/R11-excluded-names.json")?.use { it.readBytes().decodeToString() }
                ?: error("R11-excluded-names.json is not packed")
            parse(text)
        }
    }
}

/**
 * The phone's settings for sharing (Settings › LightPhotoLibrary): on or off, which kinds, and what happens when
 * the same item was changed on two devices. Kept in `<file>`, written whole; null = in memory only.
 */
class SharedSettings(private val file: File?) {
    data class Values(
        val on: Boolean = true,
        val styles: Boolean = true,
        val presets: Boolean = true,
        val layouts: Boolean = true,
        val policy: SharedPolicy = SharedPolicy.ASK,
    ) {
        fun enabled(kind: SharedKind) = on && when (kind) {
            SharedKind.STYLE -> styles
            SharedKind.PRESET -> presets
            SharedKind.LAYOUT -> layouts
        }
    }

    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values.asStateFlow()

    private fun load(): Values {
        val f = file ?: return Values()
        val o = readWhole(f)?.let { runCatching { Json.parseToJsonElement(it.decodeToString()) as JsonObject }.getOrNull() } ?: return Values()
        return Values(o["on"].bool() ?: true, o["styles"].bool() ?: true, o["presets"].bool() ?: true, o["layouts"].bool() ?: true,
            SharedPolicy.of(o["policy"].str()))
    }

    /** Throws [java.io.IOException] when the settings cannot be stored. */
    @Synchronized
    fun set(v: Values) {
        file?.let { f ->
            f.parentFile?.mkdirs()
            atomicWrite(f, buildJsonObject {
                put("on", v.on); put("styles", v.styles); put("presets", v.presets); put("layouts", v.layouts); put("policy", v.policy.json)
            }.toString().toByteArray())
        }
        _values.value = v
    }
}

/** What 4.5 says a device keeps per item ("What a device keeps"); [sentHash] = the hash of a copy the server holds as a conflict candidate (null for a deletion). */
data class SharedEntry(
    val id: Long,
    val key: SharedKey,
    val rev: Int,
    /** this device's own hash of the item right after the last upload or update; null = the device does not have it */
    val localHash: String?,
    val state: SharedState,
    val sentHash: String? = null,
)

/** One line of the review sheet. */
data class SharedRow(
    val meta: SharedMeta,
    val change: Change,
    /** for [Change.CONFLICT]: this device's side */
    val conflict: SharedConflict? = null,
) {
    enum class Change { NEW, CHANGED, DELETED, CONFLICT, UNUSABLE }

    val waits: Boolean get() = change != Change.UNUSABLE
}

/** A line of "Sent from this phone". */
data class SharedSent(val name: String, val kind: SharedKind, val what: String, val at: Double)

/** What the screens show for one server. */
data class SharedView(
    val rows: List<SharedRow> = emptyList(),
    /** when the server was last asked; 0 = not yet */
    val checkedAt: Double = 0.0,
    /** what the last update did ("3 taken in · 1 still waiting"), null until one ran */
    val lastUpdate: String? = null,
    val sent: List<SharedSent> = emptyList(),
    /** the server lists `shared` and the phone shares */
    val active: Boolean = false,
    val error: String? = null,
) {
    val waiting: List<SharedRow> get() = rows.filter { it.waits }
    /** the devices the waiting changes come from, in the order first seen */
    val devices: List<String> get() = waiting.map { it.meta.deviceName.ifEmpty { "another device" } }.distinct()
}

/** The user's answers in the review sheet: items to take in, deletions (true = delete here too, false = keep), conflicts (`mine`, `theirs`, `both`). */
data class SharedChoices(
    val take: Set<Long> = emptySet(),
    val deletions: Map<Long, Boolean> = emptyMap(),
    val conflicts: Map<Long, String> = emptyMap(),
)

/**
 * Brief P3l, PLAN.md 4.5 (R11): the phone's side of shared styles, presets and module layouts, per server.
 * Uploads are automatic (not while "sync automatically" is off); taking in happens only in [update], when the user
 * asks. Comparisons are with the device's own hash, never the server's (4.5). Every call is blocking network work
 * on [Dispatchers.IO]; one server at a time (a mutex per server).
 */
class SharedSync(
    private val servers: ServerStore,
    private val dir: File?,
    private val clientOf: (ServerOrigin) -> LplClient,
    /** the server's last `server_caps`, null = not known yet */
    private val serverCaps: (ServerOrigin) -> Set<String>?,
    private val autoSync: () -> Boolean,
    val settings: SharedSettings = SharedSettings(null),
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val trace: (String) -> Unit = {},
    private val exclusions: () -> SharedExclusions = { SharedExclusions.default },
) {
    @Volatile var port: SharedPort? = null

    private class Book(
        var since: Long = 0,
        val entries: MutableMap<Long, SharedEntry> = LinkedHashMap(),
        val meta: MutableMap<Long, SharedMeta> = LinkedHashMap(),
        val conflicts: MutableMap<Long, SharedConflict> = LinkedHashMap(),
        var checkedAt: Double = 0.0,
        var lastUpdate: String? = null,
        val sent: ArrayDeque<SharedSent> = ArrayDeque(),
        var stateSent: Boolean = false,
        var policySent: SharedPolicy? = null,
        var engineVersion: String? = null,
    ) {
        fun entryOf(k: SharedKey) = entries.values.firstOrNull { it.key == k }
        fun metaOf(k: SharedKey) = meta.values.firstOrNull { it.key == k }
    }

    private val books = ConcurrentHashMap<ServerOrigin, Book>()
    private val locks = ConcurrentHashMap<ServerOrigin, Mutex>()
    private val lastPoll = ConcurrentHashMap<ServerOrigin, Long>()

    private val _views = MutableStateFlow<Map<ServerOrigin, SharedView>>(emptyMap())
    /** Per server: what waits, the conflicts, what was sent. */
    val views: StateFlow<Map<ServerOrigin, SharedView>> = _views.asStateFlow()

    /** Whether the phone shares with [o]: the switch is on and the server listed `shared` in `server_caps`. */
    fun active(o: ServerOrigin): Boolean = settings.values.value.on && serverCaps(o)?.contains("shared") == true

    /** What the heartbeat to [o] adds to `caps`. */
    fun caps(o: ServerOrigin): List<String> = if (active(o)) listOf("shared") else emptyList()

    private fun lock(o: ServerOrigin) = locks.getOrPut(o) { Mutex() }

    // ------------------------------------------------------------------ the book on disk

    private fun file(o: ServerOrigin) = dir?.let { File(it, "${o.key}.json") }

    private fun book(o: ServerOrigin): Book = books.getOrPut(o) { load(o) }

    private fun load(o: ServerOrigin): Book {
        val f = file(o) ?: return Book()
        val obj = readWhole(f)?.let { runCatching { Json.parseToJsonElement(it.decodeToString()) as JsonObject }.getOrNull() } ?: return Book()
        val b = Book(since = obj["since"].long() ?: 0, checkedAt = obj["checked_at"].num() ?: 0.0, lastUpdate = obj["last_update"].str(),
            engineVersion = obj["engine_version"].str())
        for (e in obj["entries"].arr().orEmpty()) {
            val x = e as? JsonObject ?: continue
            val kind = SharedKind.of(x["kind"].str()) ?: continue
            val id = x["id"].long() ?: continue
            b.entries[id] = SharedEntry(id, SharedKey(kind, x["operation"].str() ?: "", x["op_version"].num()?.toInt() ?: 0, x["name"].str() ?: continue),
                x["rev"].num()?.toInt() ?: 0, x["local_hash"].str(), SharedState.of(x["state"].str()) ?: SharedState.OK, x["sent_hash"].str())
        }
        for (e in obj["meta"].arr().orEmpty()) SharedMeta.fromJson(e)?.let { b.meta[it.id] = it }
        for (e in obj["conflicts"].arr().orEmpty()) SharedConflict.fromJson(e)?.let { b.conflicts[it.itemId] = it }
        for (e in obj["sent"].arr().orEmpty()) {
            val x = e as? JsonObject ?: continue
            b.sent += SharedSent(x["name"].str() ?: continue, SharedKind.of(x["kind"].str()) ?: continue, x["what"].str() ?: "", x["at"].num() ?: 0.0)
        }
        return b
    }

    private fun save(o: ServerOrigin, b: Book) {
        val f = file(o) ?: return
        val obj = buildJsonObject {
            put("format", 1); put("since", b.since); put("checked_at", b.checkedAt)
            put("last_update", b.lastUpdate?.let { JsonPrimitive(it) } ?: JsonNull)
            put("engine_version", b.engineVersion?.let { JsonPrimitive(it) } ?: JsonNull)
            put("entries", buildJsonArray {
                for (e in b.entries.values) add(buildJsonObject {
                    put("id", e.id); put("kind", e.key.kind.json); put("operation", e.key.operation); put("op_version", e.key.opVersion)
                    put("name", e.key.name); put("rev", e.rev); put("local_hash", e.localHash?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("state", e.state.json); put("sent_hash", e.sentHash?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            })
            put("meta", JsonArray(b.meta.values.map { it.toJson() }))
            put("conflicts", JsonArray(b.conflicts.values.map { it.toJson() }))
            put("sent", buildJsonArray { for (s in b.sent) add(buildJsonObject { put("name", s.name); put("kind", s.kind.json); put("what", s.what); put("at", s.at) }) })
        }
        runCatching { f.parentFile?.mkdirs(); atomicWrite(f, obj.toString().toByteArray()) }
            .onFailure { trace("shared: state of ${o.url} not written: ${it.message}") }
    }

    // ------------------------------------------------------------------ rules

    private fun enabled(k: SharedKind) = settings.values.value.enabled(k)
    private fun shared(k: SharedKey) = enabled(k.kind) && !exclusions().excluded(k)

    /** 4.5: a device clears its `unusable` marks when its engine version changes, so those items are offered again. */
    private fun engineChanged(b: Book, version: String?): Boolean {
        if (version == null || version == b.engineVersion) return false
        val first = b.engineVersion == null
        b.engineVersion = version
        if (first) return false
        var cleared = 0
        for (e in b.entries.values.toList()) if (e.state == SharedState.UNUSABLE) {
            b.entries[e.id] = e.copy(state = SharedState.OK, rev = 0); cleared++
        }
        if (cleared > 0) trace("shared: engine now $version: $cleared items that needed a newer darktable are offered again")
        return cleared > 0
    }

    /** The review sheet's lines (4.5 "Waiting"), and the greyed ones that need a newer darktable. */
    private fun rows(b: Book): List<SharedRow> {
        val out = ArrayList<SharedRow>()
        for (m in b.meta.values) {
            if (!shared(m.key)) continue
            val e = b.entries[m.id]
            val cf = b.conflicts[m.id]
            when {
                cf != null -> out += SharedRow(m, SharedRow.Change.CONFLICT, cf)
                e != null && e.rev >= m.rev -> if (e.state == SharedState.UNUSABLE && !m.deleted) out += SharedRow(m, SharedRow.Change.UNUSABLE)
                m.deleted -> if (e != null && e.localHash != null) out += SharedRow(m, SharedRow.Change.DELETED)
                e == null || e.localHash == null -> out += SharedRow(m, SharedRow.Change.NEW)
                else -> out += SharedRow(m, SharedRow.Change.CHANGED)
            }
        }
        return out.sortedWith(compareBy({ it.meta.key.kind.ordinal }, { it.meta.key.name }))
    }

    private fun publish(o: ServerOrigin, b: Book, error: String? = null) {
        val v = SharedView(rows(b), b.checkedAt, b.lastUpdate, b.sent.toList(), active(o), error)
        _views.update { it + (o to v) }
    }

    private fun merge(b: Book, items: List<SharedMeta>) { for (m in items) b.meta[m.id] = m }

    /**
     * After a poll: adopt items both sides already have alike (first sync, no conflict), and learn how a conflict was
     * resolved elsewhere (4.5 "Uploads": a `theirs` resolution is seen because the conflict is gone and the current hash
     * is not this device's; the item then waits, and the local copy is not a local change until the update).
     */
    private fun reconcile(b: Book, locals: Map<SharedKey, SharedLocal>) {
        for (m in b.meta.values) {
            if (!shared(m.key)) continue
            val l = locals[m.key]
            val e = b.entries[m.id]
            if (e == null) {
                if (!m.deleted && l != null && l.hash == m.hash) b.entries[m.id] = SharedEntry(m.id, m.key, m.rev, l.hash, SharedState.OK)
                continue
            }
            if (e.state == SharedState.CONFLICT && m.id !in b.conflicts) {
                b.entries[m.id] = when {
                    e.sentHash != null && !m.deleted && m.hash == e.sentHash -> e.copy(rev = m.rev, localHash = e.sentHash, state = SharedState.OK, sentHash = null)
                    e.sentHash == null && m.deleted -> e.copy(rev = m.rev, localHash = null, state = SharedState.OK)
                    else -> e.copy(state = SharedState.OK, localHash = l?.hash, sentHash = null)
                }
            } else if (e.state == SharedState.OK && !m.deleted && l != null && l.hash == m.hash && e.rev < m.rev) {
                // the same content arrived by another way (imported by hand, or the same change on both sides)
                b.entries[m.id] = e.copy(rev = m.rev, localHash = l.hash)
            }
        }
        for (cf in b.conflicts.values) b.entries[cf.itemId]?.let { e -> if (e.state != SharedState.CONFLICT) b.entries[cf.itemId] = e.copy(state = SharedState.CONFLICT) }
    }

    private fun noteSent(b: Book, key: SharedKey, what: String) {
        b.sent.addFirst(SharedSent(key.name, key.kind, what, clock()))
        while (b.sent.size > 20) b.sent.removeLast()
    }

    /** 4.5 "Uploads": PUT or DELETE every local change; true if anything was sent. */
    private fun upload(b: Book, locals: Map<SharedKey, SharedLocal>, c: LplClient, agent: String): Boolean {
        var sent = false
        for (l in locals.values) {
            val e = b.entryOf(l.key)
            val base = when {
                e == null -> {
                    val m = b.metaOf(l.key)
                    if (m != null && !m.deleted && m.hash == l.hash) continue
                    0
                }
                e.state == SharedState.CONFLICT -> continue
                e.localHash == l.hash -> continue
                else -> e.rev
            }
            val w = try {
                c.sharedPut(agent, l, clock(), base)
            } catch (x: LplException) {
                // 400 {"error": "hash_mismatch"}: the server computed another hash from the body. A bug on one side, not a
                // conflict: logged, the item is left as it is and tried again at the next pass
                if (x.failure != LplFailure.REFUSED || x.status != 400) throw x
                trace("shared: ${l.key.kind.json} '${l.key.name}' refused by the server (${x.message}); hash ${l.hash}")
                continue
            }
            sent = true
            when (w) {
                is SharedWrite.Stored -> {
                    val keepRev = w.decided == "theirs"
                    b.entries[w.id] = SharedEntry(w.id, l.key, if (keepRev) (e?.rev ?: 0) else w.rev, l.hash, SharedState.OK)
                    noteSent(b, l.key, if (keepRev) "not taken: the other device's change was newer" else if (e == null || e.localHash == null) "new" else "changed")
                    trace("shared: sent ${l.key.kind.json} '${l.key.name}' rev ${w.rev}" + (w.decided?.let { " (decided: $it)" } ?: ""))
                }
                is SharedWrite.Conflict -> {
                    val m = w.item
                    if (m != null) b.meta[m.id] = m
                    val id = m?.id ?: e?.id ?: continue
                    b.entries[id] = SharedEntry(id, l.key, e?.rev ?: 0, e?.localHash, SharedState.CONFLICT, l.hash)
                    b.conflicts[id] = SharedConflict(id, clock(), false)
                    trace("shared: ${l.key.kind.json} '${l.key.name}' changed elsewhere too: conflict")
                }
            }
        }
        for (e in b.entries.values.toList()) {
            if (!shared(e.key) || e.state == SharedState.CONFLICT || e.state == SharedState.UNUSABLE || e.localHash == null) continue
            if (e.key in locals) continue
            val m = b.meta[e.id]
            if (m != null && m.deleted) { b.entries[e.id] = e.copy(localHash = null, rev = maxOf(e.rev, m.rev)); continue }
            val w = c.sharedDelete(agent, e.id, e.rev, clock())
            sent = true
            when (w) {
                is SharedWrite.Stored -> {
                    b.entries[e.id] = e.copy(rev = w.rev, localHash = null, state = SharedState.OK)
                    noteSent(b, e.key, "deleted")
                    trace("shared: deleted ${e.key.kind.json} '${e.key.name}'")
                }
                is SharedWrite.Conflict -> {
                    w.item?.let { b.meta[it.id] = it }
                    b.entries[e.id] = e.copy(state = SharedState.CONFLICT, sentHash = null)
                    b.conflicts[e.id] = SharedConflict(e.id, clock(), true)
                }
            }
        }
        return sent
    }

    private fun sendState(b: Book, c: LplClient, agent: String) {
        val policy = settings.values.value.policy
        c.sharedState(agent, b.entries.values.associate { it.id to (it.rev to it.state) }, policy)
        b.stateSent = true
        b.policySent = policy
    }

    // ------------------------------------------------------------------ passes

    /**
     * One pass with [o]: ask what changed, compare with this phone's items, send local changes (unless automatic sync is
     * off or [upload] is false) and the state. Does nothing while sharing is not active with [o]. Network failures are
     * kept in the view and thrown as [LplException] only to callers that want them ([throwing]).
     */
    suspend fun pass(o: ServerOrigin, upload: Boolean = true, throwing: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val port = port ?: return@withContext
        if (!active(o) || servers.get(o) == null) { _views.update { m -> m[o]?.let { m + (o to it.copy(active = false)) } ?: m }; return@withContext }
        lock(o).withLock {
            val b = book(o)
            val c = clientOf(o)
            val agent = servers.installId
            try {
                val list = c.sharedList(agent, b.since)
                merge(b, list.items)
                b.since = list.seq
                b.conflicts.clear(); for (cf in list.conflicts) b.conflicts[cf.itemId] = cf
                b.checkedAt = clock()
                lastPoll[o] = System.currentTimeMillis()
                val locals = port.snapshot().filter { shared(it.key) }.associateBy { it.key }
                val cleared = engineChanged(b, port.engineVersion())
                reconcile(b, locals)
                // once on start, before anything is uploaded: the server then knows this phone's policy for a conflict
                if (!b.stateSent || cleared || b.policySent != settings.values.value.policy) sendState(b, c, agent)
                val sent = upload && autoSync() && upload(b, locals, c, agent)
                if (sent) sendState(b, c, agent)
                save(o, b)
                publish(o, b)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                save(o, b)
                publish(o, b, errorText(e))
                if (throwing) throw e
            }
        }
    }

    /** [pass] with every added server that shares. */
    suspend fun passAll(upload: Boolean = true) { for (s in servers.list()) pass(s.origin, upload) }

    /** At most once a minute per server while the app is open (4.5: polling). */
    suspend fun pollIfDue(nowMs: Long = System.currentTimeMillis()) {
        for (s in servers.list()) {
            val last = lastPoll[s.origin] ?: 0L
            if (nowMs - last >= POLL_MS) { lastPoll[s.origin] = nowMs; pass(s.origin) }
        }
    }

    /** The view from disk, before any call (for the list right after the app starts). */
    fun showStored() { for (s in servers.list()) { val b = book(s.origin); publish(s.origin, b) } }

    /**
     * Brief P3l: "Update" in the review sheet. Resolves the conflicts as chosen, takes in the chosen items, deletes or
     * keeps the chosen deletions, then reports the state. Returns the summary the list's banner shows.
     */
    suspend fun update(o: ServerOrigin, choices: SharedChoices): String = withContext(Dispatchers.IO) {
        val port = port ?: throw IllegalStateException("no engine for shared items")
        lock(o).withLock {
            val b = book(o)
            val c = clientOf(o)
            val agent = servers.installId
            val take = LinkedHashSet(choices.take)
            val deletions = LinkedHashMap(choices.deletions)
            var taken = 0; var unusable = 0; var deleted = 0; var kept = 0
            try {
                val present = port.snapshot().associateBy { it.key }
                for ((id, choice) in choices.conflicts) {
                    val before = b.meta[id]
                    val changed = c.sharedResolve(agent, id, choice)
                    merge(b, changed)
                    b.conflicts.remove(id)
                    val m = b.meta[id] ?: continue
                    val e = b.entries[id] ?: SharedEntry(id, m.key, 0, present[m.key]?.hash, SharedState.OK)
                    when (choice) {
                        "mine" -> b.entries[id] = e.copy(rev = m.rev, localHash = e.sentHash, state = SharedState.OK, sentHash = null)
                        "both" -> {
                            b.entries[id] = e.copy(rev = m.rev, localHash = e.sentHash, state = SharedState.OK, sentHash = null)
                            for (x in changed) if (x.id != id && !x.deleted) take += x.id
                        }
                        else -> {
                            b.entries[id] = e.copy(state = SharedState.OK, localHash = present[m.key]?.hash, sentHash = null)
                            if (m.deleted) { if (present[m.key] != null) deletions[id] = true else b.entries[id] = e.copy(rev = m.rev, localHash = null, state = SharedState.OK, sentHash = null) }
                            else take += id
                        }
                    }
                    trace("shared: conflict on '${before?.key?.name ?: m.key.name}' resolved: $choice")
                }
                val applied = ArrayList<Pair<SharedMeta, Boolean>>()
                for (id in take) {
                    val (m, body) = c.sharedGet(id)
                    b.meta[m.id] = m
                    if (m.deleted || !shared(m.key)) continue
                    applied += m to port.apply(body)
                }
                for ((id, del) in deletions) {
                    val m = b.meta[id] ?: continue
                    val e = b.entries[id] ?: SharedEntry(id, m.key, 0, present[m.key]?.hash, SharedState.OK)
                    if (del) {
                        port.delete(m.key)
                        b.entries[id] = e.copy(rev = m.rev, localHash = null, state = SharedState.OK)
                        deleted++
                    } else {
                        b.entries[id] = e.copy(rev = m.rev, state = SharedState.KEPT)
                        kept++
                    }
                }
                val after = port.snapshot().associateBy { it.key }
                for ((m, usable) in applied) {
                    if (usable) {
                        b.entries[m.id] = SharedEntry(m.id, m.key, m.rev, after[m.key]?.hash, SharedState.OK)
                        taken++
                    } else {
                        val e = b.entries[m.id] ?: SharedEntry(m.id, m.key, 0, present[m.key]?.hash, SharedState.OK)
                        b.entries[m.id] = e.copy(rev = m.rev, state = SharedState.UNUSABLE)
                        unusable++
                    }
                }
                val still = rows(b).count { it.waits }
                b.lastUpdate = listOfNotNull(
                    "$taken taken in".takeIf { taken > 0 || (deleted == 0 && kept == 0) },
                    "$deleted deleted here".takeIf { deleted > 0 },
                    "$kept kept on this phone".takeIf { kept > 0 },
                    "$unusable need a newer darktable".takeIf { unusable > 0 },
                    "$still still waiting".takeIf { still > 0 },
                ).joinToString(" · ")
                sendState(b, c, agent)
                save(o, b)
                publish(o, b)
                b.lastUpdate!!
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                save(o, b)
                publish(o, b, errorText(e))
                throw e
            }
        }
    }

    private fun errorText(e: Exception) = (e as? LplException)?.userText ?: "styles, presets or layouts could not be read or written here: ${e.message}"

    /** Forget the "after an update" line. */
    fun dismissSummary(o: ServerOrigin) {
        val b = books[o] ?: return
        b.lastUpdate = null
        save(o, b)
        publish(o, b)
    }

    companion object {
        const val POLL_MS = 60_000L
    }
}

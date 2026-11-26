// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Brief P6b: the server side of R19 (PLAN.md 4.6) as the stub LPL keeps it, written from the contract (LPL's own
 * implementation: its `server/lpl/luts.py`). [StubLpl] routes `/api/luts...` here when its `r19` switch is on; tests
 * also call [put] and [remove] directly to play a PC or LPL's web UI.
 */
class StubLuts(private val deviceName: (String) -> String) {
    class File(val path: String, var bytes: ByteArray, var sha: String, var seq: Long, var changedAt: Double, var device: String?, var deleted: Boolean = false)

    private var seq = 0L
    val files = LinkedHashMap<String, File>()
    /** earlier contents per path (history) */
    val history = ArrayList<Pair<String, String>>()
    /** the last report per agent */
    val reports = HashMap<String, JsonObject>()
    /** every PUT: path, agent, base sent (null = none), status */
    val puts = ArrayList<String>()

    private fun entry(f: File) = buildJsonObject {
        put("path", f.path); put("size", f.bytes.size); put("sha256", f.sha); put("seq", f.seq); put("changed_at", f.changedAt)
        put("device_id", JsonNull); put("device_name", f.device?.let { JsonPrimitive(it) } ?: JsonNull); put("deleted", f.deleted)
    }

    @Synchronized fun list(since: Long): JsonObject = buildJsonObject {
        put("seq", seq)
        put("files", buildJsonArray { for (f in files.values.sortedBy { it.seq }) if (f.seq > since) add(entry(f)) })
    }

    @Synchronized fun get(path: String): File? = files[path]?.takeIf { !it.deleted }

    /** The contract's PUT rules; (status, answer). */
    @Synchronized fun put(agent: String, path: String, bytes: ByteArray, sha: String?, base: String?): Pair<Int, JsonObject> {
        val got = sha256(bytes)
        fun log(s: Int) = s.also { puts += "$path $agent ${base ?: "-"} $s" }
        if (LutPaths.problem(path) != null || bytes.isEmpty()) return log(400) to buildJsonObject { put("detail", "bad path") }
        if (sha != got) return log(400) to buildJsonObject { put("error", "hash_mismatch") }
        val f = files[path]
        if (f != null && !f.deleted && f.sha == got) return log(200) to buildJsonObject { put("path", path); put("sha256", got); put("seq", f.seq); put("stored", false) }
        if (f != null && !f.deleted && base != null && base != f.sha) return log(409) to buildJsonObject { put("error", "changed_elsewhere"); put("file", entry(f)) }
        seq++
        if (f == null) files[path] = File(path, bytes, got, seq, now(), deviceName(agent))
        else {
            if (f.sha != got) history += path to f.sha
            f.bytes = bytes; f.sha = got; f.seq = seq; f.changedAt = now(); f.device = deviceName(agent); f.deleted = false
        }
        return log(200) to buildJsonObject { put("path", path); put("sha256", got); put("seq", seq); put("stored", true) }
    }

    /** The web UI's removal: from LPL only. */
    @Synchronized fun remove(path: String) { files[path]?.let { seq++; it.deleted = true; it.seq = seq } }

    @Synchronized fun report(agent: String, o: JsonObject) { reports[agent] = o }

    var clock: () -> Double = { System.currentTimeMillis() / 1000.0 }
    private fun now() = clock()

    companion object {
        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Brief P3i: an export waiting for its server; the file is a copy kept in the app's own folder until it is sent. */
data class QueuedExport(
    val n: Long,
    val server: ServerOrigin,
    val photo: Long,
    val version: Int,
    val name: String,
    val file: File,
)

/**
 * Brief P3i (request R18): what the app keeps about exports to LightPhotoLibrary, in one folder:
 * - `caps.json`: the `server_caps` each server last announced in a heartbeat answer (kept across restarts, so the
 *   destination can be offered, and an export queued, before the first heartbeat of this run);
 * - `queue/<n>.json` and `queue/<n>.bin`: exports that could not be sent because the server did not answer. Written
 *   whole and atomically ([atomicWrite]); the file is copied first, the entry last, so a half-queued export is not
 *   an entry and its stray file is removed at the next start.
 */
class ExportBook(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val capsFile get() = File(dir, "caps.json")
    private val queueDir get() = File(dir, "queue")

    @Synchronized
    fun capsOf(origin: ServerOrigin): Set<String>? = readCaps()[origin.key]

    /** [caps] null (the answer had no `server_caps`) is remembered as an empty set: an older server. */
    @Synchronized
    fun setCaps(origin: ServerOrigin, caps: Set<String>?) {
        val all = readCaps().toMutableMap()
        val now = caps ?: emptySet()
        if (all[origin.key] == now) return
        all[origin.key] = now
        runCatching {
            atomicWrite(capsFile, JsonObject(all.mapValues { (_, v) -> buildJsonArray { v.sorted().forEach { add(JsonPrimitive(it)) } } }).toString().toByteArray())
        }
    }

    private fun readCaps(): Map<String, Set<String>> {
        val o = runCatching { json.parseToJsonElement(readWhole(capsFile)!!.decodeToString()) as? JsonObject }.getOrNull() ?: return emptyMap()
        return o.mapValues { (_, v) -> (v as? JsonArray)?.mapNotNull { it.str() }?.toSet() ?: emptySet() }
    }

    /** Keep a copy of [source] for [origin]'s photo until it is sent. Throws [java.io.IOException] when it cannot be kept. */
    @Synchronized
    fun queue(origin: ServerOrigin, photo: Long, version: Int, name: String, source: File): QueuedExport {
        queueDir.mkdirs()
        val n = (list().maxOfOrNull { it.n } ?: 0L) + 1
        val bin = File(queueDir, "$n.bin")
        source.copyTo(bin, overwrite = true)
        val entry = buildJsonObject {
            put("server", origin.url); put("photo", photo); put("version", version); put("name", name)
        }
        atomicWrite(File(queueDir, "$n.json"), entry.toString().toByteArray())
        return QueuedExport(n, origin, photo, version, name, bin)
    }

    /** In the order they were queued; leftovers of a half-queued export are deleted. */
    @Synchronized
    fun list(): List<QueuedExport> {
        val files = queueDir.listFiles() ?: return emptyList()
        val entries = files.filter { it.name.endsWith(".json") }.mapNotNull { f ->
            val n = f.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
            val o = runCatching { json.parseToJsonElement(readWhole(f)!!.decodeToString()) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            val origin = o["server"].str()?.let { ServerOrigin.parseExact(it) } ?: return@mapNotNull null
            val bin = File(queueDir, "$n.bin")
            if (!bin.isFile) return@mapNotNull null
            QueuedExport(n, origin, o["photo"].long() ?: return@mapNotNull null, o["version"].long()?.toInt() ?: 0, o["name"].str() ?: return@mapNotNull null, bin)
        }.sortedBy { it.n }
        val kept = entries.map { it.n }.toSet()
        for (f in files) {
            val n = f.name.substringBefore('.').toLongOrNull()
            if (f.name.startsWith(".") && f.name.endsWith(".tmp") || (n != null && n !in kept)) f.delete()
        }
        return entries
    }

    @Synchronized
    fun remove(q: QueuedExport) {
        File(queueDir, "${q.n}.json").delete()
        q.file.delete()
    }

    @Synchronized
    fun count(): Int = list().size
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * One photo the app holds from LPL. Saved as `state.json` in the photo's folder (PLAN.md 3.7):
 * `<root>/<server key>/<lpl id>/` holds the RAW under its original file name, its `.xmp`, the XMP as LPL
 * sent it (`downloaded.xmp`, the backup PLAN.md 2.1 asks for, kept until the edit is synced), a
 * pending preview (`preview.jpg`) and this state.
 */
data class PhotoRecord(
    val server: ServerOrigin,
    val id: Long,
    val filename: String,
    /** size and modification time of the original on the NAS at checkout (a changed file is downloaded again) */
    val size: Long,
    val mtime: Double,
    /** the server's time at checkout, sent back with the XMP (PLAN.md 4.1) */
    val checkoutTs: Double,
    val status: PhotoStatus,
    /** when the edit last reached LPL (server-independent clock of this phone, seconds) */
    val syncedTs: Double = 0.0,
    /** SHA-256 of the local XMP as LPL last has it; a different local XMP is an unsynced edit */
    val syncedHash: String = "",
    val error: String = "",
    /** the XMP fails the engine's version guard: viewing only, never posted back (PLAN.md 2.1) */
    val readOnly: Boolean = false,
    /** a preview is still to be rendered or posted */
    val previewPending: Boolean = false,
    /** order in which photos were taken, for the list */
    val added: Long = 0,
) {
    val key get() = server to id
}

/** The photos' folders and their state files. Files only; the rules live in [LplAgent]. */
class WorkStore(val root: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun folder(server: ServerOrigin, id: Long) = File(File(root, server.key), id.toString())
    fun raw(r: PhotoRecord) = File(folder(r.server, r.id), r.filename)
    fun part(r: PhotoRecord) = File(folder(r.server, r.id), r.filename + ".part")
    fun xmp(r: PhotoRecord) = File(folder(r.server, r.id), r.filename + ".xmp")
    fun backup(r: PhotoRecord) = File(folder(r.server, r.id), "downloaded.xmp")
    fun preview(r: PhotoRecord) = File(folder(r.server, r.id), "preview.jpg")
    private fun stateFile(server: ServerOrigin, id: Long) = File(folder(server, id), "state.json")

    fun hasRaw(r: PhotoRecord) = raw(r).isFile

    /** Every record on disk. Unreadable state files are skipped (their folder stays). */
    fun loadAll(): List<PhotoRecord> {
        val out = ArrayList<PhotoRecord>()
        for (sd in root.listFiles().orEmpty()) for (pd in sd.listFiles().orEmpty()) {
            val f = File(pd, "state.json")
            if (!f.isFile) continue
            runCatching { decode(json.parseToJsonElement(f.readText()) as JsonObject) }.getOrNull()?.let { r ->
                if (folder(r.server, r.id) == pd) out += r
            }
        }
        return out.sortedBy { it.added }
    }

    fun save(r: PhotoRecord) {
        val d = folder(r.server, r.id)
        d.mkdirs()
        atomicWrite(stateFile(r.server, r.id), encode(r).toString().toByteArray())
    }

    /** Remove the photo's folder with everything in it. */
    fun delete(server: ServerOrigin, id: Long) {
        folder(server, id).deleteRecursively()
    }

    private fun encode(r: PhotoRecord): JsonObject = buildJsonObject {
        put("server", r.server.url)
        put("id", r.id)
        put("filename", r.filename)
        put("size", r.size)
        put("mtime", r.mtime)
        put("checkout_ts", r.checkoutTs)
        put("status", r.status.word)
        put("synced_ts", r.syncedTs)
        put("synced_hash", r.syncedHash)
        put("error", r.error)
        put("read_only", r.readOnly)
        put("preview_pending", r.previewPending)
        put("added", r.added)
    }

    private fun decode(o: JsonObject): PhotoRecord? {
        val server = o["server"].str()?.let { ServerOrigin.parseExact(it) } ?: return null
        val id = o["id"].long() ?: return null
        val name = o["filename"].str()?.let { safeFileName(it, id) } ?: return null
        return PhotoRecord(
            server, id, name, o["size"].long() ?: -1, o["mtime"].num() ?: 0.0, o["checkout_ts"].num() ?: 0.0,
            PhotoStatus.of(o["status"].str()) ?: PhotoStatus.DIRTY,
            o["synced_ts"].num() ?: 0.0, o["synced_hash"].str() ?: "", o["error"].str() ?: "",
            o["read_only"].bool() ?: false, o["preview_pending"].bool() ?: false, o["added"].long() ?: 0,
        )
    }

    companion object {
        /**
         * LPL's file name as a plain file name in the photo's folder: the last path part, no control
         * characters, not starting with a dot, at most 120 characters with the extension kept (darktable
         * needs it). Something unusable becomes `photo-<id>`.
         */
        fun safeFileName(name: String, id: Long): String {
            var n = name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim()
            if (n.length > 120) {
                val ext = n.substringAfterLast('.', "").take(10)
                n = n.take(110) + if (ext.isNotEmpty()) ".$ext" else ""
            }
            if (n.isEmpty() || n.startsWith(".") || n == "state.json" || n == "downloaded.xmp" || n == "preview.jpg" ||
                n.endsWith(".xmp", ignoreCase = true) || n.endsWith(".part") || n.endsWith(".tmp") || n.endsWith(".etag")
            ) return "photo-$id"
            return n
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256(f: File): String = if (f.isFile) sha256(f.readBytes()) else ""
    }
}

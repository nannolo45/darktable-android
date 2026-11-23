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
    /** taken back from a folder whose state was lost: the version guard is checked again before a sync */
    val recovered: Boolean = false,
    /** R15: the last edit as LightPhotoLibrary reported it; null when unknown or the server has no R15 */
    val lastEdit: LastEdit? = null,
    /** R17: what is known of the versions n >= 1 beside the photo's own edit (their files are `NAME_nn.EXT.xmp`) */
    val versions: List<VersionState> = emptyList(),
    /** R17: the server of this photo knows versions (its checkout listed them): versions are synced and may be made */
    val versionsCap: Boolean = false,
    /** R17: versions deleted on the phone that LPL still has: `DELETE /xmp?version=n` is sent at the next sync */
    val deletedVersions: List<Int> = emptyList(),
) {
    val key get() = server to id

    fun versionState(n: Int) = versions.firstOrNull { it.n == n }
}

/**
 * R17: the sync state of one version n >= 1: the hash of the file as LPL last has it (empty = never sent, so it is
 * an unsynced edit), whether its file fails the engine's version guard (never sent, never rewritten), and a
 * pending preview.
 */
data class VersionState(val n: Int, val syncedHash: String = "", val readOnly: Boolean = false, val previewPending: Boolean = false)

/** The photos' folders and their state files. Files only; the rules live in [LplAgent]. */
class WorkStore(val root: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun folder(server: ServerOrigin, id: Long) = File(File(root, server.key), id.toString())
    fun raw(r: PhotoRecord) = File(folder(r.server, r.id), r.filename)
    fun part(r: PhotoRecord) = File(folder(r.server, r.id), r.filename + ".part")
    fun xmp(r: PhotoRecord) = File(folder(r.server, r.id), r.filename + ".xmp")
    fun versionXmp(r: PhotoRecord, n: Int) = VersionFiles.file(folder(r.server, r.id), r.filename, n)
    fun versionPreview(r: PhotoRecord, n: Int) = File(folder(r.server, r.id), "preview-v$n.jpg")
    fun backup(r: PhotoRecord) = File(folder(r.server, r.id), "downloaded.xmp")
    fun preview(r: PhotoRecord) = File(folder(r.server, r.id), "preview.jpg")
    private fun stateFile(server: ServerOrigin, id: Long) = File(folder(server, id), "state.json")

    fun hasRaw(r: PhotoRecord) = raw(r).isFile

    /** What is on disk: the photos with a readable state, and photo folders whose state cannot be read. */
    class Loaded(val records: List<PhotoRecord>, val withoutState: List<File>)

    /**
     * Every record on disk. State files are only ever replaced whole ([AtomicFiles]); temporary files a
     * write left when the process died are removed. A folder whose state is missing or unreadable is
     * reported in [Loaded.withoutState], never silently dropped: it may hold an edit.
     */
    fun load(): Loaded {
        val out = ArrayList<PhotoRecord>()
        val lost = ArrayList<File>()
        for (sd in root.listFiles().orEmpty()) for (pd in sd.listFiles().orEmpty()) {
            if (!pd.isDirectory) continue
            pd.listFiles().orEmpty().filter { AtomicFiles.isLeftover(it.name) }.forEach { it.delete() }
            val r = readWhole(File(pd, "state.json"))?.let { b ->
                runCatching { decode(json.parseToJsonElement(b.decodeToString()) as JsonObject) }.getOrNull()
            }?.takeIf { folder(it.server, it.id) == pd }
            if (r != null) out += r else lost += pd
        }
        return Loaded(out.sortedBy { it.added }, lost)
    }

    fun loadAll(): List<PhotoRecord> = load().records

    /** Write the photo's state (whole, atomically). Throws [java.io.IOException] when it cannot be written. */
    fun save(r: PhotoRecord) {
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
        put("recovered", r.recovered)
        r.lastEdit?.let { put("last_edit", it.toJson()) }
        if (r.versionsCap) put("versions_cap", true)
        if (r.versions.isNotEmpty()) put("versions", kotlinx.serialization.json.buildJsonArray {
            for (v in r.versions) add(buildJsonObject {
                put("n", v.n); put("synced_hash", v.syncedHash); put("read_only", v.readOnly); put("preview_pending", v.previewPending)
            })
        })
        if (r.deletedVersions.isNotEmpty()) put("deleted_versions", kotlinx.serialization.json.buildJsonArray {
            for (n in r.deletedVersions) add(kotlinx.serialization.json.JsonPrimitive(n))
        })
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
            o["recovered"].bool() ?: false,
            LastEdit.fromJson(o["last_edit"]),
            versions = o["versions"].arr().orEmpty().mapNotNull { e ->
                val v = e as? JsonObject ?: return@mapNotNull null
                val n = v["n"].long()?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                VersionState(n, v["synced_hash"].str() ?: "", v["read_only"].bool() ?: false, v["preview_pending"].bool() ?: false)
            },
            versionsCap = o["versions_cap"].bool() ?: false,
            deletedVersions = o["deleted_versions"].arr().orEmpty().mapNotNull { it.long()?.toInt() },
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
            if (n.isEmpty() || n.startsWith(".") || n == "state.json" || n == "downloaded.xmp" || Regex("preview-v\\d+\\.jpg").matches(n) || n == "preview.jpg" || n == "thumb.rgba" || n == "picture.rgba" ||
                n.endsWith(".xmp", ignoreCase = true) || n.endsWith(".part") || n.endsWith(".tmp") || n.endsWith(".etag")
            ) return "photo-$id"
            return n
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256(f: File): String = if (f.isFile) sha256(f.readBytes()) else ""
    }
}

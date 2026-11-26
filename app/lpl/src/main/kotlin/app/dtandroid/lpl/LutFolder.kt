// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/*
 * Brief P6b, PLAN.md 4.6 (R19): the phone's LUT folder, darktable's `plugins/darkroom/lut3d/def_path`. lut 3D's
 * parameter `filepath` is a path relative to it, so an XMP made on a PC finds the same file here when the folder has
 * it at the same relative path. This file: the path rules and the folder on disk. LutSync.kt: the exchange with LPL.
 */

/** The contract's path rules (4.6) and what this app's darktable reads. */
object LutPaths {
    /** Extensions that travel (any case); `.gmz` only for darktable built with G'MIC. */
    val EXTENSIONS = listOf(".cube", ".3dl", ".png", ".gmz")
    /** The spellings iop/lut3d.c's _calculate_clut() reads (desktop lists ".Cube" but renders nothing for it). */
    private val READ = listOf(".png", ".PNG", ".cube", ".CUBE", ".3dl", ".3DL")
    const val MAX_PATH_BYTES = 500
    const val MAX_BYTES = 64L * 1024 * 1024

    /** Why [p] is not a relative LUT path the contract allows; null when it is. */
    fun problem(p: String): String? = when {
        p.isEmpty() -> "empty path"
        p.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES -> "path longer than $MAX_PATH_BYTES bytes"
        '\\' in p || p.startsWith("/") || (p.length > 1 && p[1] == ':') -> "the path must be relative, with / separators"
        p.any { it.code < 32 || it.code == 127 } -> "control character in the path"
        p.split('/').any { it.isEmpty() || it == "." || it == ".." } -> "empty, . or .. step in the path"
        EXTENSIONS.none { p.lowercase().endsWith(it) } -> "not a LUT file (.cube, .3dl, .png)"
        else -> null
    }

    /** Whether this app's darktable renders a file of this name. */
    fun usable(name: String): Boolean = READ.any { name.endsWith(it) }

    /** Why a file of this name is listed but cannot be chosen; null when it can. */
    fun notUsableReason(name: String): String? = when {
        usable(name) -> null
        name.lowercase().endsWith(".gmz") -> "G'MIC compressed LUT: this app's darktable is built without G'MIC"
        EXTENSIONS.any { name.lowercase().endsWith(it) } -> "darktable reads only .cube, .CUBE, .3dl, .3DL, .png or .PNG spelt that way"
        else -> "not a LUT file (.cube, .3dl, .png)"
    }

    /** Whether the phone takes this file from LPL (`.gmz` is of no use here). */
    fun wanted(path: String): Boolean = !path.lowercase().endsWith(".gmz")

    /** `<name> (<device>).<ext>` in the same folder; a taken one gets " 2", " 3" (keep both, 4.6). */
    fun keepBothName(path: String, device: String, taken: (String) -> Boolean): String {
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
        val file = path.substring(slash + 1)
        val dot = file.lastIndexOf('.')
        val stem = if (dot > 0) file.substring(0, dot) else file
        val ext = if (dot > 0) file.substring(dot) else ""
        val who = device.replace('/', ' ').replace('\\', ' ').trim().ifEmpty { "phone" }
        var n = 1
        while (true) {
            val cand = dir + stem + " (" + who + (if (n > 1) " $n" else "") + ")" + ext
            if (!taken(cand)) return cand
            n++
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A file in the LUT folder. [reason]: why it cannot be chosen (null = it can). */
data class LutFile(val path: String, val size: Long, val reason: String?) {
    val name: String get() = path.substringAfterLast('/')
    val usable: Boolean get() = reason == null
}

/** One folder level of the chooser: its subfolders (relative paths) and its files. */
data class LutLevel(val dir: String, val folders: List<String>, val files: List<LutFile>)

/** What became of an import. */
sealed interface LutImport {
    data class Added(val path: String) : LutImport
    /** a file of that path exists: the user is asked whether to replace it */
    data class Exists(val path: String) : LutImport
    data class Refused(val name: String, val reason: String) : LutImport
}

/**
 * The phone's LUT folder ([root], in the app's private storage) and its marks ([stateFile]: paths the user removed
 * here, which are not downloaded again). Never follows links out of [root]. Thread-safe enough for one writer at a
 * time: the app's sync and its screens go through the same instance.
 */
class LutFolder(val root: File, private val stateFile: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val hashes = HashMap<String, Triple<Long, Long, String>>()  // path -> (size, mtime, sha256)

    private val _version = MutableStateFlow(0)
    /** Counts the changes of the folder's files (import, download, removal): the engine reads its LUTs again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun changed() = _version.update { it + 1 }

    /** The file of [path]; null when the path breaks the rules. */
    fun fileOf(path: String): File? {
        if (LutPaths.problem(path) != null) return null
        val f = File(root, path)
        val canonRoot = root.canonicalFile
        val canon = f.canonicalFile
        return if (canon.path.startsWith(canonRoot.path + File.separator)) f else null
    }

    fun has(path: String): Boolean = fileOf(path)?.isFile == true

    /** Every file under the folder (supported or not), sorted by path. */
    @Synchronized
    fun files(): List<LutFile> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<LutFile>()
        fun walk(d: File, prefix: String) {
            for (f in d.listFiles().orEmpty().sortedBy { it.name.lowercase() }) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                val rel = prefix + f.name
                if (f.isDirectory) walk(f, "$rel/")
                else if (f.isFile && !f.name.startsWith(".lut-")) out += LutFile(rel, f.length(), LutPaths.notUsableReason(f.name)
                    ?: LutPaths.problem(rel))
            }
        }
        walk(root, "")
        return out.sortedBy { it.path.lowercase() }
    }

    /** One level of the chooser: [dir] "" = the folder itself. */
    fun level(dir: String): LutLevel {
        val all = files()
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val inside = all.filter { it.path.startsWith(prefix) }
        val folders = inside.mapNotNull { f -> f.path.removePrefix(prefix).takeIf { '/' in it }?.substringBefore('/') }
            .distinct().sortedBy { it.lowercase() }.map { prefix + it }
        // empty subfolders made by "Add from phone…" into a new folder are shown too
        val dirs = File(root, dir).listFiles().orEmpty().filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .map { prefix + it.name }
        return LutLevel(dir, (folders + dirs).distinct().sortedBy { it.lowercase() }, inside.filter { '/' !in it.path.removePrefix(prefix) })
    }

    /** SHA-256 of [path]'s file (kept while its size and time do not change); null when there is none. */
    @Synchronized
    fun hashOf(path: String): String? {
        val f = fileOf(path)?.takeIf { it.isFile } ?: return null
        val key = f.length() to f.lastModified()
        hashes[path]?.let { (s, m, h) -> if (s == key.first && m == key.second) return h }
        val h = LutPaths.sha256(f)
        hashes[path] = Triple(key.first, key.second, h)
        return h
    }

    fun totalBytes(): Long = files().sumOf { it.size }

    /**
     * Copy a picked file named [name] into [subfolder] ("" = the folder itself). An existing path is not replaced
     * unless [replace]: [LutImport.Exists] asks first.
     */
    fun import(name: String, subfolder: String, source: () -> InputStream, replace: Boolean = false): LutImport {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val path = (if (subfolder.isEmpty()) "" else "${subfolder.trim('/')}/") + clean
        LutPaths.problem(path)?.let { return LutImport.Refused(clean, it) }
        val f = fileOf(path) ?: return LutImport.Refused(clean, "outside the LUT folder")
        if (f.exists() && !replace) return LutImport.Exists(path)
        val r = write(path) { out -> source().use { it.copyLimited(out) } }
        return if (r == null) LutImport.Added(path) else LutImport.Refused(clean, r)
    }

    /**
     * Write [path] through a temporary file beside it, moved in at the end (a reader never sees half a file).
     * Returns an error text, or null when written. Clears a "removed" mark.
     */
    @Synchronized
    fun write(path: String, fill: (java.io.OutputStream) -> Unit): String? {
        val f = fileOf(path) ?: return "outside the LUT folder"
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".lut-" + System.nanoTime())
        return try {
            tmp.outputStream().use(fill)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("cannot move the file into place") }
            hashes.remove(path)
            unmark(path)
            changed()
            null
        } catch (e: Exception) {
            tmp.delete()
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Rename [from] to [to] inside the folder (keep both); false when it cannot. */
    @Synchronized
    fun rename(from: String, to: String): Boolean {
        val a = fileOf(from) ?: return false
        val b = fileOf(to) ?: return false
        if (b.exists()) return false
        b.parentFile?.mkdirs()
        val ok = a.renameTo(b)
        if (ok) { hashes.remove(from); hashes.remove(to); changed() }
        return ok
    }

    /** "Remove" in Settings › LUT files: the phone's copy only, marked so that sync does not bring it back. */
    @Synchronized
    fun remove(path: String): Boolean {
        val f = fileOf(path) ?: return false
        val ok = !f.exists() || f.delete()
        if (ok) { hashes.remove(path); mark(path); changed() }
        return ok
    }

    // ---------------------------------------------------------------- removed marks

    @Synchronized
    fun removed(): Set<String> = readMarks()

    @Synchronized
    fun unmark(path: String) { val m = readMarks(); if (path in m) writeMarks(m - path) }

    @Synchronized
    private fun mark(path: String) { writeMarks(readMarks() + path) }

    private fun readMarks(): Set<String> = readWhole(stateFile)?.let { b ->
        runCatching { (json.parseToJsonElement(b.decodeToString()) as JsonObject)["removed"] as? JsonArray }.getOrNull()
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet()
    } ?: emptySet()

    private fun writeMarks(m: Set<String>) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            atomicWrite(stateFile, buildJsonObject {
                put("format", 1)
                put("removed", JsonArray(m.sorted().map { JsonPrimitive(it) }))
            }.toString().toByteArray())
        }
    }
}

/** Copy at most [LutPaths.MAX_BYTES]; more is an error (the contract's limit). */
internal fun InputStream.copyLimited(out: java.io.OutputStream) {
    val buf = ByteArray(1 shl 16)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > LutPaths.MAX_BYTES) throw java.io.IOException("larger than ${LutPaths.MAX_BYTES / (1024 * 1024)} MB")
        out.write(buf, 0, n)
    }
}

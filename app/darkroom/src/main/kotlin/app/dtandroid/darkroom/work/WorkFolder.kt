// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.work

import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The app's private working folder (brief P2b, before LightPhotoLibrary): photos come in through
 * Android's file picker as copies, each with its sidecar `<file name>.xmp` next to it, the way
 * darktable names sidecars. The filmstrip shows what is in this folder.
 *
 * Rules
 * - A photo keeps its original file name: darktable needs the extension, and the XMP refers to it.
 * - A photo whose name is already in the folder: the same bytes are kept as they are (its edit stays);
 *   different bytes get a new name, `<name>_2.<ext>`, `_3`, ...
 * - An `.xmp` picked together with photos belongs to the photo it names (`IMG_1.RAF.xmp`, or
 *   `IMG_1.xmp` as other programs write it). If the photo already has a sidecar with other content,
 *   that sidecar is kept as `<sidecar>.bak` before the picked one replaces it.
 * - Copies are written to a temporary file and renamed, so an interrupted copy leaves nothing behind.
 *   The copy of a photo is made read-only: nothing in the app writes to a RAW.
 */
class WorkFolder(val dir: File) {

    /** A file chosen in the picker: its display name and a way to read it. */
    class Incoming(val name: String, val open: () -> InputStream)

    data class Result(val imported: List<SeriesItem>, val messages: List<String>)

    private fun isSidecar(name: String) = name.endsWith(".xmp", ignoreCase = true)
    private fun isInternal(name: String) = name.startsWith(".") || name.endsWith(".tmp") || name.endsWith(".bak")

    /** The photos in the folder, by name. */
    fun items(): List<SeriesItem> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && !isSidecar(it.name) && !isInternal(it.name) }
            .sortedBy { it.name.lowercase() }
            .map { item(it.name) }

    fun item(name: String) = SeriesItem(name, File(dir, name).path, File(dir, "$name.xmp").path)

    fun series(name: String = "working folder") = Series(name, items())

    /** Copy [files] into the folder; see the class rules. */
    fun import(files: List<Incoming>): Result {
        dir.mkdirs()
        val messages = ArrayList<String>()
        val imported = ArrayList<SeriesItem>()
        // original name -> name in the folder
        val placed = LinkedHashMap<String, String>()
        for (f in files.filter { !isSidecar(it.name) }) {
            val name = safeName(f.name)
            if (name == null) { messages += "skipped \"${f.name}\": not a usable file name"; continue }
            try {
                val (tmp, hash) = copyToTemp(f)
                var target = File(dir, name)
                var n = 2
                var same = false
                while (target.exists()) {
                    if (target.length() == tmp.length() && sha256(target) == hash) { same = true; break }
                    target = File(dir, numbered(name, n++))
                }
                if (same) {
                    tmp.delete()
                    messages += "${target.name} is already in the working folder; its edit is kept"
                } else {
                    if (!tmp.renameTo(target)) { tmp.delete(); throw java.io.IOException("cannot move the copy to ${target.name}") }
                    target.setWritable(false, false)
                    if (target.name != name) messages += "${f.name}: another file of that name is already here, copied as ${target.name}"
                }
                placed[name] = target.name
                imported += item(target.name)
            } catch (e: Exception) {
                messages += "could not copy ${f.name}: ${e.message}"
            }
        }
        val existing = items().map { it.fileName }
        for (f in files.filter { isSidecar(it.name) }) {
            val photo = photoFor(f.name, placed, existing)
            if (photo == null) { messages += "${f.name}: no photo of that name was picked or is in the working folder"; continue }
            try {
                val (tmp, hash) = copyToTemp(f)
                val sidecar = File(dir, "$photo.xmp")
                if (sidecar.exists()) {
                    if (sha256(sidecar) == hash) { tmp.delete(); continue }
                    val bak = File(dir, "$photo.xmp.bak")
                    bak.delete()
                    sidecar.renameTo(bak)
                    messages += "$photo: the previous edit was kept as ${bak.name}"
                }
                if (!tmp.renameTo(sidecar)) { tmp.delete(); throw java.io.IOException("cannot move the copy to ${sidecar.name}") }
            } catch (e: Exception) {
                messages += "could not copy ${f.name}: ${e.message}"
            }
        }
        return Result(imported.distinctBy { it.fileName }, messages)
    }

    /** Remove a photo and its sidecar from the folder (the original elsewhere is untouched). */
    fun remove(item: SeriesItem) {
        File(item.rawPath).delete()
        File(item.xmpPath).delete()
    }

    private fun photoFor(xmpName: String, placed: Map<String, String>, existing: List<String>): String? {
        val base = xmpName.dropLast(4) // without ".xmp"
        placed[base]?.let { return it }
        existing.firstOrNull { it == base }?.let { return it }
        // "IMG_1.xmp" as other programs write it: the photo whose name without extension matches
        val stem = { n: String -> n.substringBeforeLast('.', n) }
        placed.entries.firstOrNull { stem(it.key) == base }?.let { return it.value }
        return existing.firstOrNull { stem(it) == base }
    }

    private fun copyToTemp(f: Incoming): Pair<File, String> {
        val tmp = File.createTempFile("import-", ".tmp", dir)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            f.open().use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return tmp to md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** The picker's display name as a plain file name, or null when nothing usable is left. */
        fun safeName(name: String): String? {
            val n = name.substringAfterLast('/').substringAfterLast('\\').trim()
            if (n.isEmpty() || n == "." || n == ".." || n.startsWith(".")) return null
            return n.replace(Regex("[\\u0000-\\u001f]"), "_")
        }

        /** `IMG_1.RAF`, 2 -> `IMG_1_2.RAF` */
        fun numbered(name: String, n: Int): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0) "${name}_$n" else name.substring(0, dot) + "_$n" + name.substring(dot)
        }
    }
}

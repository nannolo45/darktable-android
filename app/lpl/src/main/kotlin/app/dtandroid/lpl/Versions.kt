// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Brief P3f (design docs/design/P3f-versions.md, section 1): versions as desktop darktable keeps them.
 * Version 0 is `IMG_01.RAF` with `IMG_01.RAF.xmp`; version n >= 1 is `IMG_01_nn.RAF.xmp` beside it, the number before
 * the extension, two digits (three from 100). The RAW is never copied. The number of a version is in its file
 * name (darktable derives it from the name too); its optional name is `darktable:version_name` inside the XMP.
 */
object VersionFiles {
    /** The sidecar of [rawName]'s version [n]. */
    fun xmpName(rawName: String, n: Int): String {
        if (n <= 0) return "$rawName.xmp"
        val dot = rawName.lastIndexOf('.')
        val num = if (n < 100) "%02d".format(n) else n.toString()
        return if (dot <= 0) "${rawName}_$num.xmp" else rawName.substring(0, dot) + "_" + num + rawName.substring(dot) + ".xmp"
    }

    fun file(dir: File, rawName: String, n: Int) = File(dir, xmpName(rawName, n))

    /** The version [fileName] is a sidecar of for [rawName]: 0 for `NAME.EXT.xmp`, n for `NAME_nn.EXT.xmp`, else null. */
    fun numberOf(rawName: String, fileName: String): Int? {
        if (fileName == "$rawName.xmp") return 0
        if (!fileName.endsWith(".xmp")) return null
        val dot = rawName.lastIndexOf('.')
        val stem = if (dot <= 0) rawName else rawName.substring(0, dot)
        val ext = if (dot <= 0) "" else rawName.substring(dot)
        val prefix = stem + "_"
        val suffix = "$ext.xmp"
        if (fileName.length <= prefix.length + suffix.length || !fileName.startsWith(prefix) || !fileName.endsWith(suffix)) return null
        val digits = fileName.substring(prefix.length, fileName.length - suffix.length)
        if (digits.length < 2 || digits.length > 6 || !digits.all { it in '0'..'9' }) return null
        val n = digits.toInt()
        // two digits up to 99, three or more from 100 without a leading zero: exactly what [xmpName] writes
        if (n < 1 || xmpName(rawName, n) != fileName) return null
        return n
    }

    /**
     * The versions with a sidecar in [dir], ascending (0 only when its file exists). A file that is the sidecar of
     * another RAW of the folder (`IMG_02.RAF` next to `IMG.RAF`) is not a version of this one.
     */
    fun numbers(dir: File, rawName: String): List<Int> {
        val names = dir.list().orEmpty()
        val out = ArrayList<Int>()
        for (f in names) {
            val n = numberOf(rawName, f) ?: continue
            if (n > 0 && names.contains(f.removeSuffix(".xmp"))) continue
            out += n
        }
        return out.sorted()
    }

    private fun memory(dir: File, rawName: String) = File(dir, ".$rawName.versions")

    /** The highest number ever used for [rawName]: a deleted number is never used again (design section 1). */
    fun highest(dir: File, rawName: String): Int = maxOf(numbers(dir, rawName).maxOrNull() ?: 0, kept(dir, rawName))

    private fun kept(dir: File, rawName: String): Int = runCatching { memory(dir, rawName).readText().trim().toInt() }.getOrDefault(0)

    /** The number a new version gets: the highest ever used plus one. */
    fun next(dir: File, rawName: String): Int = highest(dir, rawName) + 1

    /** Remember that [n] was used (a version about to be written, or one that came from LPL). */
    fun remember(dir: File, rawName: String, n: Int) {
        if (n > kept(dir, rawName)) {
            runCatching { dir.mkdirs(); atomicWrite(memory(dir, rawName), n.toString().toByteArray()) }
        }
    }

    /** Move version [from] to number [to] (LPL answered `409 version_exists` with `next`); false when it cannot. */
    fun renumber(dir: File, rawName: String, from: Int, to: Int): Boolean {
        val a = file(dir, rawName, from)
        val b = file(dir, rawName, to)
        if (from <= 0 || to <= 0 || !a.isFile || b.exists()) return false
        remember(dir, rawName, to)
        return a.renameTo(b)
    }
}

/** One row of the versions sheet's list as far as the files tell: its number, name, and whether it has edits. */
data class VersionRow(val n: Int, val name: String, val edited: Boolean)

/**
 * Reading and changing what a sidecar says about a version, with an XML parser so the formatting of whoever wrote it
 * does not matter. Only the files of this app's own versions are changed, and only after the engine's version guard
 * said they may be rewritten (CLAUDE.md rule 4); a file that cannot be parsed is left alone ([null] answers).
 */
object XmpMeta {
    private const val DT = "http://darktable.sf.net/"
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"
    private val HISTORY_KEYS = listOf(
        "history", "history_end", "masks_history", "history_basic_hash", "history_auto_hash", "history_current_hash",
        "iop_order_version", "iop_order_list", "auto_presets_applied",
    )

    private fun parse(bytes: ByteArray): Document? = try {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // the XMP comes from a server: no DTD, no external entities
        // (Android's parser knows fewer features than the JVM's: each one is tried on its own)
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: Exception) { null }

    private fun write(d: Document): ByteArray {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.INDENT, "no")
        if (d.xmlStandalone) t.setOutputProperty(OutputKeys.STANDALONE, "yes")
        val out = ByteArrayOutputStream()
        t.transform(DOMSource(d), StreamResult(out))
        return out.toByteArray()
    }

    private fun descriptions(d: Document): List<Element> {
        val l = d.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description")
        return (0 until l.length).map { l.item(it) as Element }
    }

    private fun Element.dtAttr(name: String): String? = if (hasAttributeNS(DT, name)) getAttributeNS(DT, name) else null

    private fun Element.dtChild(name: String): Element? {
        var c = firstChild
        while (c != null) {
            if (c is Element && c.namespaceURI == DT && c.localName == name) return c
            c = c.nextSibling
        }
        return null
    }

    /** The version's name (`darktable:version_name`), "" when it has none; null when the file cannot be read. */
    fun versionName(xmp: ByteArray): String? {
        val d = parse(xmp) ?: return null
        for (e in descriptions(d)) {
            e.dtAttr("version_name")?.let { return it }
            e.dtChild("version_name")?.let { return it.textContent.trim() }
        }
        return ""
    }

    /** Whether the history has at least one entry (darktable's "altered"); null when the file cannot be read. */
    fun hasEdits(xmp: ByteArray): Boolean? {
        val d = parse(xmp) ?: return null
        for (e in descriptions(d)) {
            val h = e.dtChild("history") ?: continue
            val items = h.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li")
            if (items.length > 0) return (e.dtAttr("history_end")?.toIntOrNull() ?: items.length) > 0
        }
        return false
    }

    /** [xmp] with the version name set to [name] (removed when blank); null when the file cannot be parsed. */
    fun withVersionName(xmp: ByteArray, name: String?): ByteArray? {
        val d = parse(xmp) ?: return null
        val ds = descriptions(d)
        if (ds.isEmpty()) return null
        for (e in ds) { e.removeAttributeNS(DT, "version_name"); e.dtChild("version_name")?.let { e.removeChild(it) } }
        val clean = name?.filterNot { it.isISOControl() }?.trim().orEmpty()
        if (clean.isNotEmpty()) {
            val host = ds.firstOrNull { it.dtAttr("xmp_version") != null } ?: ds.first()
            if (!host.hasAttributeNS(XMLNS, "darktable")) host.setAttributeNS(XMLNS, "xmlns:darktable", DT)
            host.setAttributeNS(DT, "darktable:version_name", clean)
        }
        return write(d)
    }

    /**
     * The sidecar of a virgin duplicate: [xmp] without its history, masks and module order (rating, colour labels,
     * tags and the rest stay) and without a version name; null when the file cannot be parsed.
     */
    fun withoutHistory(xmp: ByteArray): ByteArray? {
        val d = parse(xmp) ?: return null
        for (e in descriptions(d)) {
            for (k in HISTORY_KEYS) { e.removeAttributeNS(DT, k); e.dtChild(k)?.let { e.removeChild(it) } }
        }
        return withVersionName(write(d), null)
    }
}

/** What happened when a version was made, renamed or deleted. */
sealed interface VersionResult {
    data class Done(val n: Int) : VersionResult
    /** the file fails the engine's version guard (newer darktable or unknown module): it is never rewritten */
    data object ReadOnly : VersionResult
    data class Refused(val why: String) : VersionResult
}

/**
 * The versions of one photo as files in [dir]. The engine is not involved here: [guard] says whether the engine's
 * version guard lets a file be rewritten (true) or not (false), and the darkroom writes the new version's content
 * itself (`xmp_write` to the path [reserve] gave) before [finish] turns it into a duplicate or a virgin duplicate.
 */
class VersionFolder(val dir: File, val rawName: String) {
    fun file(n: Int) = VersionFiles.file(dir, rawName, n)

    fun numbers(): List<Int> = VersionFiles.numbers(dir, rawName)

    fun rows(): List<VersionRow> = numbers().map { n ->
        val b = runCatching { file(n).readBytes() }.getOrNull()
        VersionRow(n, b?.let { XmpMeta.versionName(it) } ?: "", b?.let { XmpMeta.hasEdits(it) } ?: false)
    }

    /** The number the next version gets, remembered so it is not given twice. */
    fun reserve(): Int = VersionFiles.next(dir, rawName).also { VersionFiles.remember(dir, rawName, it) }

    /**
     * Turn the file the engine just wrote for version [n] into a duplicate ([virgin] false: only the name is dropped)
     * or a virgin duplicate (no history). The file is removed again when it cannot be made.
     */
    fun finish(n: Int, virgin: Boolean): VersionResult {
        val f = file(n)
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return VersionResult.Refused("the new version's file was not written")
        val out = if (virgin) XmpMeta.withoutHistory(bytes) else XmpMeta.withVersionName(bytes, null)
        if (out == null) { f.delete(); return VersionResult.Refused("the new version's file could not be read") }
        return try { atomicWrite(f, out); VersionResult.Done(n) } catch (e: java.io.IOException) { f.delete(); VersionResult.Refused("cannot write ${f.name}") }
    }

    /** Set (or with a blank [name] remove) the name of version [n]; [guard] is the engine's check of that file. */
    suspend fun rename(n: Int, name: String, guard: suspend (File) -> Boolean): VersionResult {
        val f = file(n)
        if (!f.isFile) return VersionResult.Refused("version $n has no file")
        if (!guard(f)) return VersionResult.ReadOnly
        val out = XmpMeta.withVersionName(f.readBytes(), name) ?: return VersionResult.Refused("the file of version $n could not be read")
        return try { atomicWrite(f, out); VersionResult.Done(n) } catch (e: java.io.IOException) { VersionResult.Refused("cannot write ${f.name}") }
    }

    /** Delete version [n]; never version 0. Its number stays used. */
    fun delete(n: Int): VersionResult {
        if (n <= 0) return VersionResult.Refused("version 0 is the photo's own edit and is not deleted from the app")
        VersionFiles.remember(dir, rawName, n)
        val f = file(n)
        return if (!f.exists() || f.delete()) VersionResult.Done(n) else VersionResult.Refused("cannot delete ${f.name}")
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema.layout

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.xml.parsers.DocumentBuilderFactory

/**
 * darktable's preset file (`.dtpreset`), as `dt_presets_save_to_file` writes it and
 * `dt_presets_import_from_file` reads it (`src/common/presets.c`). Desktop darktable
 * exports module layouts in this format: preferences → presets → export writes every
 * user preset, layouts included, as `modulegroups_<name>.dtpreset`; a layout's
 * "auto-apply" settings dialog in "manage module layouts" has "export..." for that one
 * layout (`<name>.dtpreset`). Import is preferences → presets → import.
 *
 * For a layout the operation is `modulegroups`, the version 1, and `op_params` is the
 * layout string ([LayoutCodec]) without its terminating zero, encoded as darktable
 * encodes blobs in XMP (`dt_exif_xmp_encode`): hexadecimal, or with the default
 * setting "only large entries" above 100 bytes, "gz", two digits of compression
 * factor and the zlib-compressed bytes in base64.
 */
object DtPreset {

    class DtPresetException(message: String) : Exception(message)

    const val OPERATION = "modulegroups"
    const val OP_VERSION = 1

    /** The fields of a preset file this app uses; the rest are read for checking only. */
    data class File(val name: String, val operation: String, val opVersion: Int, val opParams: ByteArray, val autoapply: Int) {
        /** The layout string: the blob up to its first zero byte, as darktable reads it (a C string). */
        fun layoutText(): String {
            val end = opParams.indexOf(0).let { if (it < 0) opParams.size else it }
            return opParams.copyOf(end).decodeToString()
        }
    }

    /** Read a `.dtpreset` file. Throws [DtPresetException] for anything darktable would not import. */
    fun read(bytes: ByteArray): File {
        val doc = try {
            val f = DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
                // not every parser knows this feature (Android's does not); external entities are not read either way
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw DtPresetException("not a darktable preset file: ${e.message}")
        }
        val root = doc.documentElement
        if (root?.tagName != "darktable_preset") throw DtPresetException("not a darktable preset file (no darktable_preset element)")
        // darktable takes the first element of each name anywhere in the document (XPath //name)
        fun text(n: String): String? = (root.getElementsByTagName(n).item(0) as? Element)?.textContent
        val name = text("name") ?: throw DtPresetException("the preset has no name")
        val operation = text("operation") ?: throw DtPresetException("the preset names no module")
        val params = text("op_params") ?: throw DtPresetException("the preset has no parameters")
        return File(name, operation, atoi(text("op_version")), decode(params.trim()), atoi(text("autoapply")))
    }

    /** `atoi`: leading digits, 0 when there are none. */
    private fun atoi(s: String?): Int = s?.trim()?.let { Regex("^[+-]?\\d+").find(it)?.value?.toIntOrNull() } ?: 0

    /**
     * A layout as darktable exports it: the same elements in the same order and the
     * values `dt_presets_add_lib` gives a layout (`src/common/presets.c`), so the file
     * differs from desktop's own export only in the compressed bytes of `op_params`
     * (zlib versions may compress differently; the content is the same).
     */
    fun writeLayout(name: String, layout: String): ByteArray {
        val p = encode(layout.encodeToByteArray())
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<darktable_preset version=\"1.0\"><preset>")
        fun el(n: String, v: String) { sb.append('<').append(n).append('>').append(escape(v)).append("</").append(n).append('>') }
        el("name", name)
        el("description", "")
        el("operation", OPERATION)
        el("op_params", p)
        el("op_version", OP_VERSION.toString())
        el("enabled", "1")
        el("autoapply", "0")
        el("model", "%")
        el("maker", "%")
        el("lens", "%")
        el("iso_min", "0.000000")
        el("iso_max", "340282346638528859811704183484516925440.000000")
        el("exposure_min", "0.000000")
        el("exposure_max", "10000000.000000")
        el("aperture_min", "0.000000")
        el("aperture_max", "100000000.000000")
        el("focal_length_min", "0")
        el("focal_length_max", "1000")
        el("blendop_params", "")
        el("blendop_version", "0")
        el("multi_priority", "0")
        // darktable prints the empty (NULL) instance name of a layout as "(null)"
        el("multi_name", "(null)")
        el("multi_name_hand_edited", "0")
        el("filter", "0")
        el("def", "0")
        el("format", "0")
        sb.append("</preset></darktable_preset>\n")
        return sb.toString().encodeToByteArray()
    }

    /** libxml2's escaping of element text. */
    private fun escape(s: String) = buildString {
        for (ch in s) when (ch) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '\r' -> append("&#13;")
            else -> append(ch)
        }
    }

    /** The file name darktable gives an exported preset (`dt_presets_save_to_file`), from preferences' export. */
    fun fileName(layoutName: String): String =
        "${OPERATION}_$layoutName".map { if (it in "/<>:\"\\|*?[]") '_' else it }.joinToString("") + ".dtpreset"

    /** `dt_exif_xmp_encode` with darktable's default "only large entries". */
    fun encode(bytes: ByteArray): String {
        if (bytes.size <= 100) return bytes.joinToString("") { "%02x".format(it) }
        val d = Deflater(Deflater.DEFAULT_COMPRESSION)
        d.setInput(bytes)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        val z = out.toByteArray()
        val factor = minOf(bytes.size / z.size + 1, 99)
        return "gz%02d".format(factor) + Base64.getEncoder().encodeToString(z)
    }

    /** `dt_exif_xmp_decode`: "gz" + factor + base64 of zlib data, or hexadecimal. */
    fun decode(s: String): ByteArray {
        if (s.startsWith("gz")) {
            if (s.length < 4) throw DtPresetException("damaged parameters")
            val z = try { Base64.getMimeDecoder().decode(s.substring(4)) } catch (e: IllegalArgumentException) {
                throw DtPresetException("damaged parameters: ${e.message}")
            }
            val inf = Inflater()
            inf.setInput(z)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            try {
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw DtPresetException("damaged parameters: truncated")
                    out.write(buf, 0, n)
                }
            } catch (e: java.util.zip.DataFormatException) {
                throw DtPresetException("damaged parameters: ${e.message}")
            } finally {
                inf.end()
            }
            return out.toByteArray()
        }
        if (s.length % 2 != 0 || !s.all { it in "0123456789abcdefABCDEF" }) throw DtPresetException("damaged parameters: not hexadecimal")
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /**
     * A layout from a preset file, checked as the app needs it: the file must be a
     * module layout (operation `modulegroups`, version 1). darktable's own import takes
     * any preset; a preset of another module is refused here with a clear message.
     */
    fun readLayout(bytes: ByteArray): Pair<String, String> {
        val f = read(bytes)
        if (f.operation != OPERATION) throw DtPresetException("\"${f.name}\" is a preset for the module \"${f.operation}\", not a module layout")
        if (f.opVersion != OP_VERSION) throw DtPresetException("\"${f.name}\" is a module layout of version ${f.opVersion}; this app reads version $OP_VERSION")
        if (f.name.isEmpty()) throw DtPresetException("the layout has no name")
        return f.name to f.layoutText()
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.fake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.Base64

/**
 * The fake engine's sidecar. It looks like a darktable XMP where the version
 * guard reads it (one `rdf:li` per history entry with `darktable:operation`
 * and `darktable:modversion`), but parameters are the fake's own JSON in
 * base64, marked `dtandroid:fake="1"`. Desktop darktable cannot read these
 * parameters; the file exists so the fake can save and reopen edits and so the
 * guard can be exercised with a hand-made XMP.
 */
internal object FakeXmp {
    data class Entry(
        val num: Int,
        val operation: String,
        val modversion: Int,
        val enabled: Boolean,
        val priority: Int,
        val multiName: String,
        val params: JsonObject?,
    )

    data class Parsed(val xmpVersion: Int, val historyEnd: Int?, val entries: List<Entry>, val iopOrder: Map<String, Int>)

    private val li = Regex("""<rdf:li\b([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL)
    private val attr = Regex("""([A-Za-z_][\w.-]*:[\w.-]+)="([^"]*)"""")

    fun parse(text: String): Parsed {
        val xmpVersion = Regex("""darktable:xmp_version="(\d+)"""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        val end = Regex("""darktable:history_end="(\d+)"""").find(text)?.groupValues?.get(1)?.toInt()
        val order = Regex("""dtandroid:iop_order="([^"]*)"""").find(text)?.groupValues?.get(1)
            ?.split(',')?.mapNotNull { e -> e.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }
            ?.toMap() ?: emptyMap()
        val historyBlock = Regex("""<darktable:history>(.*?)</darktable:history>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: ""
        val entries = li.findAll(historyBlock).mapIndexedNotNull { idx, m ->
            val a = attr.findAll(m.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            val op = a["darktable:operation"] ?: return@mapIndexedNotNull null
            val params = if (a["dtandroid:fake"] == "1") a["darktable:params"]?.let {
                runCatching { Json.parseToJsonElement(String(Base64.getDecoder().decode(it))).jsonObject }.getOrNull()
            } else null
            Entry(
                num = a["darktable:num"]?.toIntOrNull() ?: idx,
                operation = op,
                modversion = a["darktable:modversion"]?.toIntOrNull() ?: 0,
                enabled = a["darktable:enabled"] != "0",
                priority = a["darktable:multi_priority"]?.toIntOrNull() ?: 0,
                multiName = a["darktable:multi_name"] ?: "",
                params = params,
            )
        }.toList()
        return Parsed(xmpVersion, end, entries, order)
    }

    fun write(file: File, entries: List<Entry>, historyEnd: Int, iopOrder: Map<String, Int>) {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        sb.append("  <rdf:Description rdf:about=\"\"\n")
        sb.append("    xmlns:darktable=\"http://darktable.sf.net/\"\n")
        sb.append("    xmlns:dtandroid=\"urn:dtandroid:fake-engine\"\n")
        sb.append("    darktable:xmp_version=\"5\"\n")
        sb.append("    darktable:history_end=\"$historyEnd\"\n")
        sb.append("    dtandroid:iop_order=\"${iopOrder.entries.joinToString(",") { "${it.key}=${it.value}" }}\">\n")
        sb.append("   <darktable:history>\n    <rdf:Seq>\n")
        for (e in entries) {
            val p = e.params?.let { Base64.getEncoder().encodeToString(it.toString().toByteArray()) } ?: ""
            sb.append("     <rdf:li darktable:num=\"${e.num}\" darktable:operation=\"${escape(e.operation)}\"")
            sb.append(" darktable:enabled=\"${if (e.enabled) 1 else 0}\" darktable:modversion=\"${e.modversion}\"")
            sb.append(" darktable:multi_priority=\"${e.priority}\" darktable:multi_name=\"${escape(e.multiName)}\"")
            sb.append(" darktable:params=\"$p\" dtandroid:fake=\"1\"/>\n")
        }
        sb.append("    </rdf:Seq>\n   </darktable:history>\n  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n")
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot rename $tmp to $file")
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}

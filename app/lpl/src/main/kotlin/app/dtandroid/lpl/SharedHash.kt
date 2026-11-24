// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * The R11 item hash (PLAN.md 4.5), the same as the bridge's (`engine/src/shared.c`) and the reference
 * implementation `tools/r11/r11_hash.py`: lowercase hex SHA-256 of the canonical JSON (keys sorted by code
 * point, separators `,` and `:`, strings escaped as Python's `json.dumps(ensure_ascii=False)`) of the
 * item's core object. The app computes it for its layouts; the bridge gives it for presets and styles.
 */
object SharedHash {
    val PRESET_CORE = listOf(
        "name", "description", "operation", "op_version", "op_params", "enabled", "blendop_params", "blendop_version",
        "multi_priority", "multi_name", "multi_name_hand_edited", "model", "maker", "lens", "iso_min", "iso_max",
        "exposure_min", "exposure_max", "aperture_min", "aperture_max", "focal_length_min", "focal_length_max",
        "autoapply", "filter", "def", "format",
    )
    val LAYOUT_CORE = listOf("name", "operation", "op_version", "op_params")
    val STYLE_CORE = listOf("name", "description", "iop_list")
    val ITEM_CORE = listOf(
        "num", "module", "operation", "op_params", "enabled", "blendop_params", "blendop_version", "multi_priority",
        "multi_name", "multi_name_hand_edited",
    )

    /** The core object of a body; null for a body without a known kind. */
    fun core(body: JsonObject): JsonObject? {
        val kind = (body["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        fun pick(src: JsonObject?, cols: List<String>) = JsonObject(cols.associateWith { src?.get(it) ?: JsonNull })
        return when (kind) {
            "style" -> {
                val items = (body["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    .sortedBy { (it["num"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L } // stable, as Python's sorted
                JsonObject(mapOf(
                    "kind" to JsonPrimitive(kind),
                    "style" to pick(body["style"] as? JsonObject, STYLE_CORE),
                    "items" to JsonArray(items.map { pick(it, ITEM_CORE) }),
                ))
            }
            "preset", "layout" -> JsonObject(mapOf(
                "kind" to JsonPrimitive(kind),
                "row" to pick(body["row"] as? JsonObject, if (kind == "layout") LAYOUT_CORE else PRESET_CORE),
            ))
            else -> null
        }
    }

    /** The canonical text of the core object, or null for a body without a known kind. */
    fun canonicalCore(body: JsonObject): String? = core(body)?.let { canonical(it) }

    /** The item's hash; null for a body without a known kind. */
    fun hash(body: JsonObject): String? = canonicalCore(body)?.let { sha256(it) }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Canonical JSON: sorted keys (by code point), no spaces, strings as Python writes them with ensure_ascii=False. */
    fun canonical(e: JsonElement): String = StringBuilder().also { write(it, e) }.toString()

    private val byCodePoint = Comparator<String> { a, b ->
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i); val y = b.codePointAt(j)
            if (x != y) return@Comparator x.compareTo(y)
            i += Character.charCount(x); j += Character.charCount(y)
        }
        (a.length - i).compareTo(b.length - j)
    }

    private fun write(sb: StringBuilder, e: JsonElement) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonObject -> {
                sb.append('{')
                var first = true
                for (k in e.keys.sortedWith(byCodePoint)) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k); sb.append(':'); write(sb, e.getValue(k))
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }
                sb.append(']')
            }
            is JsonPrimitive -> if (e.isString) string(sb, e.content) else sb.append(e.content)
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** A REAL as 4.5 writes it: C's `%.17g` (Python's `format(x, ".17g")`). */
    fun real(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        if (x == 0.0) return if (1.0 / x < 0) "-0" else "0"
        val bd = BigDecimal(x).round(MathContext(17, RoundingMode.HALF_EVEN))
        val digits = bd.unscaledValue().abs().toString()
        val exp = digits.length - 1 - bd.scale()
        val d = digits.padEnd(17, '0').substring(0, 17)
        val sign = if (x < 0) "-" else ""
        if (exp < -4 || exp >= 17) {
            val frac = d.substring(1).trimEnd('0')
            val e = (if (exp < 0) "-" else "+") + kotlin.math.abs(exp).toString().padStart(2, '0')
            return sign + d[0] + (if (frac.isNotEmpty()) ".$frac" else "") + "e" + e
        }
        val (int, frac) = if (exp >= 0) d.substring(0, exp + 1) to d.substring(exp + 1) else "0" to ("0".repeat(-exp - 1) + d)
        val f = frac.trimEnd('0')
        return sign + int + (if (f.isNotEmpty()) ".$f" else "")
    }

    /** Bytes as 4.5 writes a BLOB: lowercase hex. */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Lowercase or uppercase hex to bytes; null if it is not hex. */
    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[2 * i], 16); val lo = Character.digit(s[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = (hi * 16 + lo).toByte()
        }
        return out
    }
}

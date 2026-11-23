// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * Copy and (selective) paste of the history between photos: docs/design/engine-api.md section 9 (brief P3j).
 * As in Model.kt, each type parses the bridge's JSON and writes the same shape back.
 */

private fun bad(what: String): Nothing = throw EngineException(EngineError.ENGINE, "engine JSON: $what")
private fun JsonElement.o(what: String) = this as? JsonObject ?: bad("$what is not an object")
private fun JsonElement.a(what: String) = this as? JsonArray ?: bad("$what is not an array")
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("\"$k\" is not a string")
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: bad("\"$k\" is not an integer")
private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: bad("\"$k\" is not a boolean")

/** Desktop's paste modes (plugins/lighttable/copy_history/pastemode): append on top of the history, or replace it. */
enum class PasteMode(val json: String) { APPEND("append"), OVERWRITE("overwrite") }

/**
 * One line of desktop's "selective copy" / "selective paste" dialog: one module instance of the history.
 * [safe]: a plain copy takes it (darktable leaves out the modules flagged unsafe to copy, such as white balance and
 * the raw black/white point). [selected]: only in the clipboard: whether a paste takes it.
 */
data class CopyItem(
    val num: Int, val op: String, val priority: Int, val name: String, val instanceName: String,
    val enabled: Boolean, val mask: Boolean, val safe: Boolean, val selected: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("num", num); put("op", op); put("priority", priority); put("name", name); put("instance_name", instanceName)
        put("enabled", enabled); put("mask", mask); put("safe", safe)
        if (selected != null) put("selected", selected)
    }

    companion object {
        fun fromJson(e: JsonElement): CopyItem {
            val o = e.o("copy item")
            return CopyItem(
                o.i("num"), o.s("op"), o.i("priority"), o.s("name"), o.s("instance_name"), o.b("enabled"), o.b("mask"), o.b("safe"),
                (o["selected"] as? JsonPrimitive)?.booleanOrNull,
            )
        }

        fun listFromJson(e: JsonElement?): List<CopyItem> = (e ?: bad("items missing")).a("items").map { fromJson(it) }
    }
}

/** [DarktableEngine.historyCopyItems]: the open photo's items, and its module order's name (as desktop's dialog shows). */
data class CopyItems(val items: List<CopyItem>, val moduleOrder: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("items", JsonArray(items.map { it.toJson() })); put("module_order", moduleOrder)
    }

    companion object {
        fun fromJson(e: JsonElement): CopyItems {
            val o = e.o("copy items")
            return CopyItems(CopyItem.listFromJson(o["items"]), o.s("module_order"))
        }
    }
}

/** What was copied ([DarktableEngine.historyClipboard]); [parts]: a selective copy or paste chose the items. */
data class Clipboard(val parts: Boolean, val copyModuleOrder: Boolean, val items: List<CopyItem>, val moduleOrder: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("parts", parts); put("copy_module_order", copyModuleOrder)
        put("items", JsonArray(items.map { it.toJson() })); put("module_order", moduleOrder)
    }

    companion object {
        fun fromJson(e: JsonElement): Clipboard? {
            if (e is JsonNull) return null
            val o = e.o("clipboard")
            return Clipboard(o.b("parts"), o.b("copy_module_order"), CopyItem.listFromJson(o["items"]), o.s("module_order"))
        }
    }
}

/**
 * A selective copy or paste: the history nums of the chosen [items] (as listed), those of them to [reset] (desktop's
 * "reset" column: pasted with their default parameters), and, for a copy, whether the module order travels.
 */
data class HistorySelection(val items: List<Int>, val reset: List<Int> = emptyList(), val moduleOrder: Boolean = true) {
    fun toJson(withOrder: Boolean): JsonObject = buildJsonObject {
        put("items", JsonArray(items.map { JsonPrimitive(it) }))
        put("reset", JsonArray(reset.map { JsonPrimitive(it) }))
        if (withOrder) put("module_order", moduleOrder)
    }
}

/** A photo that is not open, to paste onto: its RAW and the XMP to read (if it exists) and write. */
data class PasteTarget(val raw: String, val xmp: String)

/** One photo of [DarktableEngine.historyPasteFiles]: [error] null = pasted and written. */
data class PasteResult(val raw: String, val xmp: String, val error: EngineError?, val message: String?) {
    companion object {
        fun listFromJson(e: JsonElement): List<PasteResult> = e.a("paste results").map {
            val o = it.o("paste result")
            val code = o.i("code")
            PasteResult(o.s("raw"), o.s("xmp"), if (code == 0) null else EngineError.of(code),
                (o["error"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content)
        }
    }
}

/** The options JSON of the paste calls. */
fun pasteOptionsJson(mode: PasteMode?, selection: HistorySelection?): String = buildJsonObject {
    put("mode", mode?.let { JsonPrimitive(it.json) } ?: JsonNull)
    selection?.let { s -> s.toJson(false).forEach { (k, v) -> put(k, v) } }
}.toString()

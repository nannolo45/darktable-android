// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * P1b part 2: docs/design/engine-api.md section 6 (render options, pickers,
 * histogram, coordinates, blending, masks). As in Model.kt, each type parses
 * the bridge's JSON and writes the same shape back.
 */

private fun bad(what: String): Nothing = throw EngineException(EngineError.ENGINE, "engine JSON: $what")
private fun JsonElement.o(what: String) = this as? JsonObject ?: bad("$what is not an object")
private fun JsonElement.a(what: String) = this as? JsonArray ?: bad("$what is not an array")
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("\"$k\" is not a string")
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: bad("\"$k\" is not an integer")
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull ?: bad("\"$k\" is not a number")
private fun JsonElement.dbl(what: String) = (this as? JsonPrimitive)?.doubleOrNull ?: bad("$what is not a number")

/** A module instance by name and priority (darktable's multi_priority). */
data class InstanceRef(val op: String, val priority: Int) {
    fun toJson(): JsonObject = buildJsonObject { put("op", op); put("priority", priority) }
}

/**
 * Options of one render (engine-api.md 6.1). [bypass]: instances processed as if switched off for this call only
 * (no history item, no undo step, the edit untouched). [showMask]: desktop's "show mask" overlay of that instance.
 */
data class RenderOptions(val bypass: List<InstanceRef> = emptyList(), val showMask: InstanceRef? = null) {
    val isNone: Boolean get() = bypass.isEmpty() && showMask == null

    fun toJson(): JsonObject = buildJsonObject {
        if (bypass.isNotEmpty()) put("bypass", JsonArray(bypass.map { it.toJson() }))
        showMask?.let { put("show_mask", it.toJson()) }
    }

    companion object {
        val NONE = RenderOptions()
        fun bypass(op: String, priority: Int) = RenderOptions(bypass = listOf(InstanceRef(op, priority)))
    }
}

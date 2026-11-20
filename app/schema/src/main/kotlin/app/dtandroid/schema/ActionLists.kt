// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Brief P3c deliverable 5 (P3b open question 4): a list whose entries and current entry the engine gives per photo
 * through a module action (engine-api.md 5.4: input colour profile's `list_profiles`, denoise (profiled)'s
 * `list_profiles` with `current`). [list] is the action that lists the entries; only entries whose fields equal
 * [filter] are shown; [label] and [current] name the entry's text and its "is the current one" field. Choosing an
 * entry writes either parameters from its fields ([params]: parameter to entry field, one history step) or the module
 * action [action] with arguments from its fields ([actionArgs]: argument to entry field).
 */
data class ActionList(
    val controlId: Int,
    val list: String,
    val filter: Map<String, String> = emptyMap(),
    val label: String = "name",
    val current: String = "current",
    val params: Map<String, String> = emptyMap(),
    val action: String? = null,
    val actionArgs: Map<String, String> = emptyMap(),
) {
    /** The entries of [answer] (the list action's answer) this list shows, in its order. */
    fun entries(answer: JsonElement?): List<JsonObject> =
        (answer as? JsonArray)?.mapNotNull { it as? JsonObject }?.filter { e ->
            filter.all { (k, v) -> (e[k] as? JsonPrimitive)?.content == v }
        } ?: emptyList()

    fun label(e: JsonObject): String = (e[label] as? JsonPrimitive)?.content ?: ""

    /** The position of the current entry, or -1. */
    fun currentIndex(entries: List<JsonObject>): Int = entries.indexOfFirst { (it[current] as? JsonPrimitive)?.content == "true" }

    /** What choosing [e] writes: parameters (by name) or null when it is an action. */
    fun paramWrites(e: JsonObject): Map<String, JsonPrimitive>? =
        if (action != null) null else params.mapValues { (_, field) -> (e[field] as? JsonPrimitive) ?: JsonPrimitive("") }

    /** The action's arguments for [e]. */
    fun args(e: JsonObject): JsonObject = JsonObject(actionArgs.mapValues { (_, field) -> e[field] ?: JsonPrimitive("") })
}

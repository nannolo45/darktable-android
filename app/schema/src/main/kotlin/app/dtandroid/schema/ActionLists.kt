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
    /** brief P6a: entry field that is false for an entry that cannot be chosen (shown greyed out with [reason]) */
    val enabled: String? = null,
    /** brief P6a: entry field with the reason an entry cannot be chosen */
    val reason: String = "reason",
    /** brief P6a: entries whose fields equal these are headings (white balance's camera name) */
    val section: Map<String, String> = emptyMap(),
    /** brief P6a: entries whose fields equal these arm the picker of control [pickerNode] instead ("from image area") */
    val picker: Map<String, String> = emptyMap(),
    val pickerNode: Int? = null,
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

    /** The action's arguments for [e]; brief P6a: a field the entry does not have is left out (a mode has no tuning). */
    fun args(e: JsonObject): JsonObject =
        JsonObject(actionArgs.mapNotNull { (arg, field) -> e[field]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { arg to it } }.toMap())

    private fun matches(e: JsonObject, m: Map<String, String>) = m.isNotEmpty() && m.all { (k, v) -> (e[k] as? JsonPrimitive)?.content == v }

    /** brief P6a: a heading, not an entry */
    fun isSection(e: JsonObject): Boolean = matches(e, section)

    /** brief P6a: the entry arms a picker (its control id) instead of calling the action */
    fun armsPicker(e: JsonObject): Int? = if (matches(e, picker)) pickerNode else null

    /** brief P6a: whether the entry can be chosen, and if not why */
    fun isEnabled(e: JsonObject): Boolean = !isSection(e) && (enabled == null || (e[enabled] as? JsonPrimitive)?.content != "false")

    fun whyDisabled(e: JsonObject): String? = if (isEnabled(e) || isSection(e)) null else (e[reason] as? JsonPrimitive)?.content
}

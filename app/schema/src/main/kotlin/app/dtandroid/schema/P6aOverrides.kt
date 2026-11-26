// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.schema.SchemaLoader.SchemaException
import app.dtandroid.schema.SchemaLoader.int
import app.dtandroid.schema.SchemaLoader.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Brief P6a: a desktop button (rgb levels' "auto", AgX's "read exposure", "reset primaries", "set from above") whose
 * callback the engine does as a module action. [choices]: one entry per action desktop offers from the button (one,
 * or the entries of its menu, as AgX's "reset primaries"); [tabArgs]: argument to a tabs control, whose chosen page
 * is passed (0 while the tabs are hidden), as a picker's `tab_args`.
 */
data class ActionButton(val controlId: Int, val label: String, val choices: List<Choice>, val tabArgs: Map<String, Int> = emptyMap()) {
    data class Choice(val label: String, val action: String, val args: JsonObject = JsonObject(emptyMap()))

    /** The arguments of [choice] with the tab pages [tab] gives. */
    fun args(choice: Choice, tab: (Int) -> Int): JsonObject =
        JsonObject(choice.args + tabArgs.mapValues { (_, tabs) -> JsonPrimitive(tab(tabs)) })
}

internal object P6aOverrides {
    fun apply(m0: ModuleSchema, o: JsonObject, file: String): ModuleSchema {
        val list = o["action_button"] as? JsonArray ?: return m0
        val buttons = LinkedHashMap(m0.actionButtons)
        for (ee in list) {
            val e = ee.jsonObject
            val node = e.int("node") ?: throw SchemaException("$file: action_button without node")
            val c = m0.control(node) ?: throw SchemaException("$file: ${m0.op} has no node $node")
            if (c !is Control.Unsupported || c.kind != "button") throw SchemaException("$file: action_button: node $node is not a button")
            e.str("expect_label")?.let { if ((c.label ?: "").trim() != it) throw SchemaException("$file: node $node is \"${c.label}\", expected \"$it\"") }
            e.str("expect_action_path")?.let {
                if (m0.actionPaths[node] != it) throw SchemaException("$file: node $node has action path \"${m0.actionPaths[node]}\", expected \"$it\"")
            }
            fun choice(x: JsonObject, label: String): ActionButton.Choice {
                val a = x["action"] as? JsonObject ?: throw SchemaException("$file: action_button $node without action")
                return ActionButton.Choice(label, a.str("name") ?: throw SchemaException("$file: action_button $node: action without name"),
                    (a["args"] as? JsonObject) ?: JsonObject(emptyMap()))
            }
            val label = e.str("label") ?: c.label ?: throw SchemaException("$file: action_button $node without label")
            val choices = (e["choices"] as? JsonArray)?.map { ce ->
                val x = ce.jsonObject
                choice(x, x.str("label") ?: throw SchemaException("$file: action_button $node: a choice without label"))
            } ?: listOf(choice(e, label))
            val tabs = (e["tab_args"] as? JsonObject)?.mapValues { (k, v) ->
                val id = (v as? JsonPrimitive)?.content?.toIntOrNull() ?: throw SchemaException("$file: tab_args $k is not a node")
                if (m0.control(id) !is Control.Tabs) throw SchemaException("$file: tab_args $k: node $id is not tabs")
                id
            } ?: emptyMap()
            buttons[node] = ActionButton(node, label, choices, tabs)
        }
        return m0.copy(actionButtons = buttons)
    }
}

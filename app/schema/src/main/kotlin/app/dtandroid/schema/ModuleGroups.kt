// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.schema.SchemaLoader.SchemaException
import app.dtandroid.schema.SchemaLoader.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Module groups and the quick panel, from a data file
 * (`module-groups.json`, ui-spec.md section 2: "configurable data, not code").
 * A group lists modules by darktable's default group (`schema_groups`), by
 * name (`modules`), or both. The quick panel lists single controls by module
 * and parameter (`param`) or, for controls without a parameter, by label.
 */
data class ModuleGroups(val groups: List<Group>, val quick: List<QuickControl>) {

    data class Group(val id: String, val label: String, val ops: List<String>)

    data class QuickControl(val op: String, val controlId: Int, val label: String)

    companion object {
        fun fromResources(schema: UiSchema, loader: ClassLoader = ModuleGroups::class.java.classLoader): ModuleGroups {
            val text = loader.getResourceAsStream("dtandroid/module-groups.json")?.use { it.readBytes().decodeToString() }
                ?: throw SchemaException("module-groups.json is not packed into the app")
            return parse(text, schema)
        }

        /** Modules that can appear in a group: they have a panel and are not deprecated. */
        fun listable(m: ModuleSchema) = m.hasPanel && !m.deprecated

        fun parse(text: String, schema: UiSchema): ModuleGroups {
            val root = Json.parseToJsonElement(text).jsonObject
            val groups = (root["groups"] as? JsonArray ?: throw SchemaException("module-groups.json: no groups")).map { ge ->
                val g = ge.jsonObject
                val id = g.str("id") ?: throw SchemaException("module-groups.json: group without id")
                val fromSchema = (g["schema_groups"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
                val named = (g["modules"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
                for (op in named) if (op !in schema.modules) throw SchemaException("module-groups.json: unknown module $op in $id")
                val ops = schema.modules.values.filter { listable(it) && (it.op in named || it.defaultGroups.any { d -> d in fromSchema }) }.map { it.op }
                Group(id, g.str("label") ?: id, ops)
            }
            val quick = (root["quick"] as? JsonArray)?.map { qe ->
                val q = qe.jsonObject
                val op = q.str("op") ?: throw SchemaException("module-groups.json: quick entry without op")
                val m = schema.modules[op] ?: throw SchemaException("module-groups.json: unknown module $op")
                val param = q.str("param")
                val label = q.str("label")
                val c = m.allControls().firstOrNull { c ->
                    when {
                        param != null -> c.binding()?.let { it is Binding.Param && it.path == param } == true
                        label != null -> c.label == label && c is Control.Slider
                        else -> false
                    }
                } ?: throw SchemaException("module-groups.json: $op has no control ${param ?: label}")
                QuickControl(op, c.id, "${m.name} · ${c.label ?: param ?: ""}")
            } ?: emptyList()
            return ModuleGroups(groups, quick)
        }
    }
}

/** The binding of an editable control, or null for containers and labels. */
fun Control.binding(): Binding? = when (this) {
    is Control.Slider -> binding
    is Control.ComboBox -> binding
    is Control.Toggle -> binding
    else -> null
}

internal fun JsonObject.has(k: String) = containsKey(k)

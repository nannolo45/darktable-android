// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.schema.SchemaLoader.SchemaException
import app.dtandroid.schema.SchemaLoader.bool
import app.dtandroid.schema.SchemaLoader.int
import app.dtandroid.schema.SchemaLoader.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Brief P3c: the override operations for colour pickers (`schema/overrides/README.md`, "Brief P3c"):
 *
 * - `picker_setting`: an interface-only slider, list or switch that holds a picker's setting (desktop keeps it in
 *   its configuration); bound to [Binding.Setting].
 * - `picker`: what the picker of a marked control does: a module action with the picked area or point, or showing
 *   the picked values only.
 *
 * Each names its node and what it expects there (label, action path), so a schema update that moves a control
 * makes loading fail instead of wiring the wrong one.
 */
internal object P3cOverrides {

    fun apply(m0: ModuleSchema, o: JsonObject, file: String): ModuleSchema {
        var m = m0
        val settings = LinkedHashMap(m.pickerSettings)
        val pickers = LinkedHashMap(m.pickers)

        fun target(e: JsonObject, op: String): Control {
            val node = e.int("node") ?: throw SchemaException("$file: $op without node")
            val c = m.control(node) ?: throw SchemaException("$file: ${m.op} has no node $node")
            e.str("expect_label")?.let { if ((c.label ?: "").trim() != it) throw SchemaException("$file: node $node is \"${c.label}\", expected \"$it\"") }
            e.str("expect_action_path")?.let {
                if (m.actionPaths[node] != it) throw SchemaException("$file: node $node has action path \"${m.actionPaths[node]}\", expected \"$it\"")
            }
            return c
        }

        // ------------------------------------------------------------ settings first (pickers refer to them)
        (o["picker_setting"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "picker_setting")
            val key = e.str("key") ?: throw SchemaException("$file: picker_setting without key")
            val default = e["default"] as? JsonPrimitive ?: throw SchemaException("$file: picker_setting $key without default")
            val entries = (e["entries"] as? JsonObject)?.map { (label, v) -> label to v.jsonPrimitive } ?: emptyList()
            val unwritable = (e["unwritable"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val reapply = (e["reapply_when"] as? JsonObject)?.mapValues { it.value.jsonPrimitive }
            val binding = Binding.Setting(key)
            m = m.copy(controls = Overrides.replace(m.controls, c.id) {
                when (it) {
                    is Control.Slider -> {
                        if (it.binding !is Binding.None) throw SchemaException("$file: node ${c.id} is already bound")
                        if (default.isString) throw SchemaException("$file: picker_setting $key: a slider's default is a number")
                        it.copy(binding = binding, default = default.content.toDouble())
                    }
                    is Control.ComboBox -> {
                        if (it.binding !is Binding.None) throw SchemaException("$file: node ${c.id} is already bound")
                        if (entries.map { e -> e.first } != it.entries.map { e -> e.label })
                            throw SchemaException("$file: picker_setting $key: entries differ from the schema's ${it.entries.map { e -> e.label }}")
                        if (entries.none { e -> PickerSetting.same(e.second, default) }) throw SchemaException("$file: picker_setting $key: default is no entry")
                        unwritable.keys.forEach { l -> if (entries.none { e -> e.first == l }) throw SchemaException("$file: no entry $l") }
                        it.copy(binding = binding)
                    }
                    is Control.Toggle -> {
                        if (it.binding !is Binding.None) throw SchemaException("$file: node ${c.id} is already bound")
                        if (default.settingBool() == null) throw SchemaException("$file: picker_setting $key: a switch's default is true or false")
                        it.copy(binding = binding, default = default.settingBool()!!)
                    }
                    else -> throw SchemaException("$file: picker_setting: node ${c.id} is not a slider, list or switch")
                }
            })
            settings[c.id] = PickerSetting(c.id, key, default, entries, unwritable, reapply)
        }
        val keys = settings.values.map { it.key }.toSet()
        fun checkKey(k: String) { if (k !in keys) throw SchemaException("$file: no picker_setting with key $k") }
        settings.values.forEach { s -> s.reapplyWhen?.keys?.forEach(::checkKey) }

        // ------------------------------------------------------------ pickers
        (o["picker"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "picker")
            if (c.id !in m.pickerMarks) throw SchemaException("$file: node ${c.id} of ${m.op} has no colour picker in the schema")
            val kinds = (e["kinds"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: throw SchemaException("$file: picker ${c.id} without kinds")
            if (kinds.isEmpty() || kinds.any { it != "point" && it != "area" }) throw SchemaException("$file: picker ${c.id}: kinds are \"point\" and \"area\"")
            val action = (e["action"] as? JsonObject)?.let { a ->
                val settingsMap = (a["settings"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                settingsMap.values.forEach(::checkKey)
                val results = (a["results"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                results.values.forEach(::checkKey)
                val tabs = (a["tab_args"] as? JsonObject)?.mapValues { (k, v) ->
                    val id = v.jsonPrimitive.content.toIntOrNull() ?: throw SchemaException("$file: tab_args $k is not a node")
                    if (m.control(id) !is Control.Tabs) throw SchemaException("$file: tab_args $k: node $id is not tabs")
                    id
                } ?: emptyMap()
                val instead = (a["instead"] as? JsonObject)?.let { i ->
                    val w = (i["when"] as? JsonObject)?.mapValues { it.value.jsonPrimitive } ?: emptyMap()
                    w.keys.forEach(::checkKey)
                    PickerAction.Instead(w, i.str("name") ?: throw SchemaException("$file: instead without name"))
                }
                PickerAction(
                    name = a.str("name") ?: throw SchemaException("$file: picker action without name"),
                    args = (a["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                    settings = settingsMap, tabArgs = tabs, results = results, instead = instead,
                )
            }
            val slider = (e["set_slider"] as? JsonObject)?.let { s ->
                val formula = s.str("formula") ?: throw SchemaException("$file: set_slider without formula")
                val writes = (s["writes"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val roles = PickerFormulas.roles[formula] ?: throw SchemaException("$file: unknown picker formula $formula")
                if (writes.keys != roles) throw SchemaException("$file: picker formula $formula writes ${roles}, got ${writes.keys}")
                writes.values.forEach { p -> if (p !in m.params && p.substringBefore('[') !in m.fieldNames) throw SchemaException("$file: ${m.op} has no parameter $p") }
                PickerSlider(formula, writes, s.str("buffer") ?: "input")
            }
            if (action != null && slider != null) throw SchemaException("$file: picker ${c.id}: action or set_slider, not both")
            val label = e.str("label") ?: c.tooltip?.lineSequence()?.firstOrNull() ?: c.label ?: ""
            pickers[c.id] = PickerSpec(c.id, label, "point" in kinds, "area" in kinds, e.bool("denoise") ?: false, action, slider)
        }
        return m.copy(pickerSettings = settings, pickers = pickers)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.schema.SchemaLoader.SchemaException
import app.dtandroid.schema.SchemaLoader.int
import app.dtandroid.schema.SchemaLoader.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hand corrections from `schema/overrides/<op>.json` (PLAN.md 3.3). Two
 * operations, each addressed by the schema's node id and checked against the
 * node's kind and action path, so an override that no longer fits the schema
 * fails loudly instead of changing the wrong control:
 *
 * - `replace_with_buttons`: a node (usually a row of unlabelled icon buttons
 *   whose callbacks are code on desktop) becomes a row of buttons that set an
 *   enum parameter by a table, current constant to new constant.
 * - `bind_action`: an unbound slider is read and written through
 *   `module_action` (values desktop derives from parameters).
 */
internal object Overrides {

    fun apply(m: ModuleSchema, o: JsonObject, file: String): ModuleSchema {
        var controls = m.controls
        (o["replace_with_buttons"] as? JsonArray)?.forEach { re ->
            val r = re.jsonObject
            val node = r.int("node") ?: throw SchemaException("$file: replace_with_buttons without node")
            val target = m.control(node) ?: throw SchemaException("$file: ${m.op} has no node $node")
            val expectPaths = (r["expect_action_paths"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
            val found = target.children.filterIsInstance<Control.Unsupported>().mapNotNull { it.actionPath }
            if (found != expectPaths) throw SchemaException("$file: node $node has buttons $found, expected $expectPaths")
            val buttons = (r["buttons"] as? JsonArray)?.map { be ->
                val b = be.jsonObject
                val param = b.str("param") ?: throw SchemaException("$file: button without param")
                val field = m.params[param] ?: throw SchemaException("$file: ${m.op} has no parameter $param")
                val map = (b["map"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                for ((k, v) in map) {
                    if (k !in field.enumValues || v !in field.enumValues) throw SchemaException("$file: $k -> $v is not an enum constant of $param")
                }
                Control.MapButton(
                    actionPath = b.str("action_path") ?: "",
                    icon = b.str("icon") ?: throw SchemaException("$file: button without icon"),
                    label = b.str("label") ?: "",
                    param = param,
                    map = map,
                )
            } ?: emptyList()
            val label = r.str("label") ?: target.children.filterIsInstance<Control.Label>().firstOrNull()?.label
            controls = replace(controls, node) { Control.ButtonRow(node, label, r.str("tooltip"), it.hiddenByDefault, buttons) }
        }
        (o["bind_action"] as? JsonArray)?.forEach { be ->
            val b = be.jsonObject
            val node = b.int("node") ?: throw SchemaException("$file: bind_action without node")
            val target = m.control(node) as? Control.Slider ?: throw SchemaException("$file: node $node of ${m.op} is not a slider")
            val expectLabel = b.str("expect_label")
            if (expectLabel != null && target.label != expectLabel) throw SchemaException("$file: node $node is \"${target.label}\", expected \"$expectLabel\"")
            if (target.binding !is Binding.None) throw SchemaException("$file: node $node is already bound")
            val binding = Binding.Action(
                get = b.str("get") ?: throw SchemaException("$file: bind_action without get"),
                set = b.str("set") ?: throw SchemaException("$file: bind_action without set"),
                field = b.str("field") ?: throw SchemaException("$file: bind_action without field"),
                fields = (b["fields"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
            controls = replace(controls, node) { (it as Control.Slider).copy(binding = binding) }
        }
        var aspect = m.aspect
        (o["aspect_list"] as? JsonArray)?.forEach { ae ->
            val a = ae.jsonObject
            val node = a.int("node") ?: throw SchemaException("$file: aspect_list without node")
            val target = m.control(node) as? Control.ComboBox ?: throw SchemaException("$file: node $node of ${m.op} is not a list")
            val expectLabel = a.str("expect_label")
            if (expectLabel != null && target.label != expectLabel) throw SchemaException("$file: node $node is \"${target.label}\", expected \"$expectLabel\"")
            if (target.binding !is Binding.None) throw SchemaException("$file: node $node is already bound")
            val entries = (a["entries"] as? JsonArray)?.map { ee ->
                val e = ee.jsonObject
                AspectEntry(e.str("label") ?: throw SchemaException("$file: aspect entry without label"),
                    e.int("d") ?: throw SchemaException("$file: aspect entry without d"), e.int("n") ?: throw SchemaException("$file: aspect entry without n"))
            } ?: emptyList()
            // the table must be the list desktop shows, in its order
            if (entries.map { it.label } != target.entries.map { it.label })
                throw SchemaException("$file: the aspect entries differ from the schema's list ${target.entries.map { it.label }}")
            fun param(k: String): String {
                val name = a.str(k) ?: throw SchemaException("$file: aspect_list without $k")
                if (name !in m.params) throw SchemaException("$file: ${m.op} has no parameter $name")
                return name
            }
            aspect = AspectSpec(node, param("ratio_d"), param("ratio_n"), param("left"), param("top"), param("right"), param("bottom"),
                entries, a.str("flip_label") ?: "swap", a.str("flip_icon") ?: "aspectFlip")
            controls = replace(controls, node) { (it as Control.ComboBox).copy(binding = Binding.Aspect) }
        }
        val p2j = P2jOverrides.apply(m.copy(controls = controls), o, file)
        return p2j.copy(overrides = m.overrides + file, aspect = aspect)
    }

    internal fun replace(cs: List<Control>, id: Int, f: (Control) -> Control): List<Control> = cs.map { c ->
        if (c.id == id) f(c) else when (c) {
            is Control.Collapsible -> c.copy(children = replace(c.children, id, f))
            is Control.Page -> c.copy(children = replace(c.children, id, f))
            is Control.Tabs -> c.copy(pages = replace(c.pages, id, f).filterIsInstance<Control.Page>())
            is Control.Stack -> c.copy(pages = replace(c.pages, id, f).filterIsInstance<Control.Page>())
            is Control.Group -> c.copy(children = replace(c.children, id, f))
            else -> c
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.schema.SchemaLoader.SchemaException
import app.dtandroid.schema.SchemaLoader.bool
import app.dtandroid.schema.SchemaLoader.dbl
import app.dtandroid.schema.SchemaLoader.int
import app.dtandroid.schema.SchemaLoader.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Brief P2j: the override operations for controls that desktop keeps outside the parameters
 * (`schema/overrides/README.md`). Every operation names its node and the label it expects there, so a schema
 * update that moves a control makes loading fail instead of binding the wrong one.
 */
internal object P2jOverrides {

    fun apply(m0: ModuleSchema, o: JsonObject, file: String): ModuleSchema {
        var m = m0
        val computed = LinkedHashMap(m.computed)
        val notes = LinkedHashMap(m.notes)
        val hidden = LinkedHashSet(m.hidden)
        val visibility = LinkedHashMap(m.visibility)
        val hiddenEvery = LinkedHashSet(m.hiddenInEveryState)

        fun target(e: JsonObject, op: String): Control {
            val node = e.int("node") ?: throw SchemaException("$file: $op without node")
            val c = m.control(node) ?: throw SchemaException("$file: ${m.op} has no node $node")
            val expect = e.str("expect_label")
            if (expect != null && (c.label ?: "").trim() != expect) throw SchemaException("$file: node $node is \"${c.label}\", expected \"$expect\"")
            return c
        }

        fun unbound(c: Control, op: String) {
            if (c.binding() !is Binding.None) throw SchemaException("$file: $op: node ${c.id} is already bound")
        }

        fun checkParam(name: String, op: String) {
            if (name !in m.params && name.substringBefore('[') !in m.fieldNames)
                throw SchemaException("$file: $op: ${m.op} has no parameter $name")
        }

        fun values(e: JsonObject?, op: String): Map<String, JsonPrimitive> =
            e?.mapValues { (k, v) -> checkParam(k, op); v.jsonPrimitive } ?: emptyMap()

        fun bind(c: Control, b: Binding): Unit = run {
            m = m.copy(controls = Overrides.replace(m.controls, c.id) {
                when (it) {
                    is Control.Slider -> it.copy(binding = b)
                    is Control.ComboBox -> it.copy(binding = b)
                    is Control.Toggle -> it.copy(binding = b)
                    else -> throw SchemaException("$file: node ${c.id} is not a slider, list or switch")
                }
            })
        }

        // ------------------------------------------------------------ computed sliders
        (o["computed"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "computed") as? Control.Slider ?: throw SchemaException("$file: computed: node is not a slider")
            unbound(c, "computed")
            val formula = e.str("formula") ?: throw SchemaException("$file: computed without formula")
            val role = e.str("role") ?: "value"
            val roles = Formulas.roles[formula] ?: throw SchemaException("$file: unknown formula $formula")
            if (role !in roles) throw SchemaException("$file: formula $formula has no role $role")
            val params = (e["params"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            params.filterKeys { !it.endsWith("_min") && !it.endsWith("_max") }.values.forEach { checkParam(it, "computed") }
            val partners = (e["partners"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.intOrNull ?: -1 } ?: emptyMap()
            computed[c.id] = try { Formulas.slider(c.id, formula, role, params, partners) } catch (x: NoSuchElementException) {
                throw SchemaException("$file: formula $formula: ${x.message}")
            }
            bind(c, Binding.Computed)
        }

        // ------------------------------------------------------------ fixed lists
        (o["value_list"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "value_list") as? Control.ComboBox ?: throw SchemaException("$file: value_list: node is not a list")
            unbound(c, "value_list")
            val raw = (e["entries"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            if (raw.map { it.str("label") } != c.entries.map { it.label })
                throw SchemaException("$file: value_list ${c.id}: entries differ from the schema's ${c.entries.map { it.label }}")
            val items = raw.mapIndexed { i, it ->
                ValueList.Item(it.str("label")!!, c.entries[i].value, (it["set"] as? JsonObject)?.let { s -> values(s, "value_list") }, it.bool("section") ?: false)
            }
            val unmatched = e.str("unmatched")?.let { l -> items.indexOfFirst { it.label == l && !it.section }.takeIf { it >= 0 } ?: throw SchemaException("$file: no entry $l") }
            val unwritable = (e["unwritable"] as? JsonObject)?.entries?.associate { (l, why) ->
                (items.indexOfFirst { it.label == l }.takeIf { it >= 0 } ?: throw SchemaException("$file: no entry $l")) to why.jsonPrimitive.content
            } ?: emptyMap()
            val read = (e["read"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: throw SchemaException("$file: value_list without read")
            read.forEach { checkParam(it, "value_list") }
            computed[c.id] = ValueList(c.id, items, read, e.dbl("tolerance") ?: 1e-6, unmatched, unwritable)
            bind(c, Binding.Computed)
        }

        // ------------------------------------------------------------ spektrafilm's stock lists
        (o["stock_list"] as? JsonObject)?.let { e ->
            val stocks = (e["stocks"] as? JsonArray)?.map { se ->
                val s = se.jsonObject
                SpektrafilmStocks.Stock(
                    s.str("stock")!!, s.str("name")!!, s.bool("printing") ?: false, s.bool("positive") ?: false, s.bool("bw") ?: false,
                    (s["dev_times"] as? JsonArray)?.map { it.jsonPrimitive.content.toDouble() } ?: emptyList(), s.str("target_print"),
                )
            } ?: emptyList()
            val tables = (e["tables"] as? JsonArray)?.map { te ->
                val t = te.jsonObject
                SpektrafilmStocks.Table(t.str("label")!!, (t["hash"] as JsonPrimitive).longOrNull!!, t.bool("reflectance") ?: false)
            } ?: emptyList()
            val sf = SpektrafilmStocks(stocks, tables, (e["pack_lut_hash"] as? JsonPrimitive)?.longOrNull ?: 0L,
                e.int("film_node")!!, e.int("paper_node")!!, e.int("upsampling_node")!!)
            for (name in listOf("film_hash", "paper_hash", "lut_hash", "pack_hash", "upsampling_hash", "scan_film", "development_min",
                "print_development_min", "scan_black_correction", "scan_white_correction")) checkParam(name, "stock_list")
            val empty = JsonObject(emptyMap())
            for ((node, model, expect, skip) in listOf(
                Quad(sf.filmNode, sf.Film(), "film stock", -1),
                Quad(sf.paperNode, sf.Paper(), "print paper", 1),
                Quad(sf.upsamplingNode, sf.Upsampling(), "spectral upsampling", -1),
            )) {
                val c = m.control(node) as? Control.ComboBox ?: throw SchemaException("$file: stock_list: node $node is not a list")
                if (c.label != expect) throw SchemaException("$file: node $node is \"${c.label}\", expected \"$expect\"")
                unbound(c, "stock_list")
                // the pack's entries must be the ones the schema was extracted with (the auto entry's text follows the film)
                val mine = model.entries(empty).map { it.label }
                val theirs = c.entries.map { it.label }
                if (mine.size != theirs.size || mine.indices.any { it != skip && mine[it] != theirs[it] })
                    throw SchemaException("$file: stock_list $expect: the pack's entries $mine differ from the schema's $theirs")
                computed[node] = model
                bind(c, Binding.Computed)
            }
        }

        // ------------------------------------------------------------ switches that write values
        (o["toggle_writes"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "toggle_writes") as? Control.Toggle ?: throw SchemaException("$file: toggle_writes: node is not a switch")
            unbound(c, "toggle_writes")
            computed[c.id] = ToggleWrites(c.id, values(e["set"] as? JsonObject, "toggle_writes"), values(e["on_when"] as? JsonObject, "toggle_writes"))
            bind(c, Binding.Computed)
        }

        // ------------------------------------------------------------ plain parameters the schema did not see
        (o["bind_param"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "bind_param")
            unbound(c, "bind_param")
            val param = e.str("param") ?: throw SchemaException("$file: bind_param without param")
            checkParam(param, "bind_param")
            val type = e.str("param_type")
            m = m.copy(controls = Overrides.replace(m.controls, c.id) {
                when (it) {
                    is Control.Slider -> it.copy(binding = Binding.Param(param))
                    is Control.ComboBox -> it.copy(binding = Binding.Param(param), paramType = type ?: it.paramType)
                    is Control.Toggle -> it.copy(binding = Binding.Param(param))
                    else -> throw SchemaException("$file: node ${c.id} is not a slider, list or switch")
                }
            })
        }

        // ------------------------------------------------------------ waiting for a later tool / not shown
        (o["note"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "note")
            notes[c.id] = e.str("text") ?: throw SchemaException("$file: note without text")
        }
        (o["hide"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "hide")
            if (e.str("reason").isNullOrBlank()) throw SchemaException("$file: hide without reason")
            hidden += c.id
        }

        // ------------------------------------------------------------ visibility as desktop's code says
        (o["visibility"] as? JsonArray)?.forEach { ee ->
            val e = ee.jsonObject
            val c = target(e, "visibility")
            val conds = (e["when_all"] as? JsonArray)?.map { ce ->
                val x = ce.jsonObject
                val p = x.str("param")?.also { checkParam(it, "visibility") }
                Condition(p, x.int("control"), (x["in"] as? JsonArray)?.map { it.jsonPrimitive.intOrNull!! }?.toSet() ?: emptySet(), emptySet())
            } ?: emptyList()
            val table = (e["table"] as? JsonObject)?.let { t ->
                fun tuples(k: String) = (t[k] as? JsonArray)?.map { r -> (r as JsonArray).map { it.jsonPrimitive.intOrNull!! } }?.toSet() ?: emptySet()
                Table((t["variables"] as JsonArray).map { it.jsonPrimitive.content }, tuples("visible_for"), tuples("hidden_for"))
            }
            if (conds.isEmpty() && table == null) throw SchemaException("$file: visibility ${c.id} without when_all or table")
            visibility[c.id] = Rule(c.id, conds, table, verified = true)
            hiddenEvery -= c.id
        }

        return m.copy(computed = computed, notes = notes, hidden = hidden, visibility = visibility, hiddenInEveryState = hiddenEvery)
    }

    private data class Quad(val node: Int, val model: ComputedList, val label: String, val skip: Int)
}

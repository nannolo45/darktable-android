// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.fake

import app.dtandroid.engine.api.ParamPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the fake engine knows about darktable's modules: read from the control
 * schema (schema/ui-schema.json), the same file the interface is generated
 * from. Module list, versions, flags, parameter fields with types, ranges and
 * enum constants, and this image's defaults (a control's default wins over the
 * introspection default, as darktable's reload_defaults does for a raw).
 */
class FakeModuleCatalog private constructor(val darktableVersion: String, val modules: Map<String, ModuleDef>) {

    enum class FieldType { FLOAT, INT, BOOL, ENUM, CHAR }

    data class EnumConst(val name: String, val value: Int)

    data class FieldDef(
        /** template path as the schema lists it, `curve[0].x` */
        val template: String,
        val type: FieldType,
        val count: Int,
        val min: Double?,
        val max: Double?,
        /** default as a JSON value: number, bool or enum constant name */
        val default: JsonPrimitive,
        val enumValues: List<EnumConst>,
    ) {
        fun enumName(value: Int): String? = enumValues.firstOrNull { it.value == value }?.name
        fun enumValue(name: String): Int? = enumValues.firstOrNull { it.name == name }?.value
    }

    data class ModuleDef(
        val op: String,
        val name: String,
        val version: Int,
        val pipePosition: Int,
        val defaultEnabled: Boolean,
        val canSwitch: Boolean,
        val hidden: Boolean,
        val deprecated: Boolean,
        val oneInstance: Boolean,
        val supportsBlending: Boolean,
        val fields: Map<String, FieldDef>,
        /** the module's default parameter structure for this image */
        val defaultParams: JsonObject,
    )

    operator fun get(op: String): ModuleDef? = modules[op]

    companion object {
        fun fromSchemaJson(text: String): FakeModuleCatalog {
            val root = Json.parseToJsonElement(text).jsonObject
            val version = root["darktable_version"]?.jsonPrimitive?.content ?: "unknown"
            val mods = LinkedHashMap<String, ModuleDef>()
            for ((op, m) in root["modules"]!!.jsonObject) {
                val o = m.jsonObject
                val status = o["status"]?.jsonPrimitive?.content
                val flags = o["flags"] as? JsonObject
                val fields = parseFields(o["params"] as? JsonObject)
                val controlDefaults = HashMap<String, JsonPrimitive>()
                (o["controls"] as? JsonArray)?.let { collectControlDefaults(it, fields, controlDefaults) }
                val withImageDefaults = fields.mapValues { (t, f) -> controlDefaults[t]?.let { f.copy(default = it) } ?: f }
                mods[op] = ModuleDef(
                    op = op,
                    name = o["name"]?.jsonPrimitive?.content ?: op,
                    version = o["version"]?.jsonPrimitive?.intOrNull ?: 1,
                    pipePosition = o["pipe_position"]?.jsonPrimitive?.intOrNull ?: 0,
                    defaultEnabled = o["default_enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    // modules without a panel and modules whose switch desktop hides are always on
                    canSwitch = status != "hidden" && o["hide_enable_button"]?.jsonPrimitive?.booleanOrNull != true,
                    hidden = status == "hidden" || flags?.get("hidden")?.jsonPrimitive?.booleanOrNull == true,
                    deprecated = flags?.get("deprecated")?.jsonPrimitive?.booleanOrNull ?: false,
                    oneInstance = flags?.get("one_instance")?.jsonPrimitive?.booleanOrNull ?: false,
                    supportsBlending = flags?.get("supports_blending")?.jsonPrimitive?.booleanOrNull ?: false,
                    fields = withImageDefaults,
                    defaultParams = buildParams(withImageDefaults.values.toList(), ARRAY_DIMS[op] ?: emptyMap()),
                )
            }
            return FakeModuleCatalog(version, mods)
        }

        private fun parseFields(params: JsonObject?): Map<String, FieldDef> {
            val out = LinkedHashMap<String, FieldDef>()
            val list = params?.get("fields") as? JsonArray ?: return out
            for (fe in list) {
                val f = fe.jsonObject
                val type = when (f["type"]?.jsonPrimitive?.content) {
                    "float" -> FieldType.FLOAT
                    "bool" -> FieldType.BOOL
                    "enum" -> FieldType.ENUM
                    "char" -> FieldType.CHAR
                    else -> FieldType.INT // int, uint, int8, ushort
                }
                val enums = (f["values"] as? JsonArray)?.map {
                    val v = it.jsonObject
                    EnumConst(v["name"]!!.jsonPrimitive.content, v["value"]!!.jsonPrimitive.intOrNull ?: 0)
                } ?: emptyList()
                val rawDefault = f["default"] as? JsonPrimitive
                val def: JsonPrimitive = when (type) {
                    FieldType.FLOAT -> JsonPrimitive(rawDefault?.doubleOrNull ?: 0.0)
                    FieldType.INT, FieldType.CHAR -> JsonPrimitive(rawDefault?.doubleOrNull?.toLong() ?: 0L)
                    FieldType.BOOL -> JsonPrimitive(rawDefault?.booleanOrNull ?: false)
                    FieldType.ENUM -> {
                        val dv = rawDefault?.intOrNull ?: 0
                        JsonPrimitive(enums.firstOrNull { it.value == dv }?.name ?: enums.firstOrNull()?.name ?: "")
                    }
                }
                val name = f["name"]!!.jsonPrimitive.content
                out[name] = FieldDef(
                    template = name,
                    type = type,
                    count = f["count"]?.jsonPrimitive?.intOrNull ?: 1,
                    min = (f["min"] as? JsonPrimitive)?.doubleOrNull,
                    max = (f["max"] as? JsonPrimitive)?.doubleOrNull,
                    default = def,
                    enumValues = enums,
                )
            }
            return out
        }

        /** Image defaults from the controls bound to a scalar (non-array) field. */
        private fun collectControlDefaults(controls: JsonArray, fields: Map<String, FieldDef>, out: MutableMap<String, JsonPrimitive>) {
            for (ce in controls) {
                val c = ce.jsonObject
                val param = (c["param"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                val boundToParams = (c["bound_to"] as? JsonPrimitive)?.content == "params"
                val field = param?.let { fields[it] }
                if (field != null && boundToParams && field.count == 1) {
                    when (c["kind"]?.jsonPrimitive?.content) {
                        "slider" -> (c["default"] as? JsonPrimitive)?.doubleOrNull?.let {
                            out[param] = if (field.type == FieldType.FLOAT) JsonPrimitive(it) else JsonPrimitive(Math.round(it))
                        }
                        "toggle" -> (c["default"] as? JsonPrimitive)?.booleanOrNull?.let {
                            if (field.type == FieldType.BOOL) out[param] = JsonPrimitive(it)
                        }
                        "combobox" -> {
                            val pos = (c["default_pos"] as? JsonPrimitive)?.intOrNull
                            val entries = c["entries"] as? JsonArray
                            val v = if (pos != null && entries != null && pos in entries.indices)
                                entries[pos].jsonObject["value"]?.jsonPrimitive?.intOrNull else null
                            if (v != null) when (field.type) {
                                FieldType.ENUM -> field.enumName(v)?.let { out[param] = JsonPrimitive(it) }
                                FieldType.BOOL -> out[param] = JsonPrimitive(v != 0)
                                else -> out[param] = JsonPrimitive(v)
                            }
                        }
                    }
                }
                (c["children"] as? JsonArray)?.let { collectControlDefaults(it, fields, out) }
            }
        }

        // --- building the nested parameter structure from the schema's field templates ---

        private sealed class Node {
            class Obj(val fields: LinkedHashMap<String, Node> = LinkedHashMap()) : Node()
            class Arr(var size: Int, var element: Node?) : Node()
            class Leaf(val value: JsonPrimitive) : Node()
        }

        /**
         * The schema lists array fields as templates (`curve[0][0].x`) with the
         * total element count. Where a template has one index, the count is the
         * array's length. With several indices the split is taken from a sibling
         * template with fewer indices if one exists (`rt_forms[0].formid` gives
         * `rt_forms` 300, so `rt_forms[0].fill_color[0]` 900 is 300 x 3);
         * otherwise the outer dimensions are 1 and the innermost holds the count.
         * Only graph and on-image modules have such arrays (Tier 2 and 3).
         */
        /**
         * Brief P3e-b: the shape of two-dimensional arrays that the field list alone does not give (the real engine's
         * introspection gives it): contrast equalizer's `x` and `y` are [5][6], colour zones' `curve` is [3][20].
         */
        private val ARRAY_DIMS: Map<String, Map<String, List<Int>>> = mapOf(
            "atrous" to mapOf("x" to listOf(5, 6), "y" to listOf(5, 6)),
            "colorzones" to mapOf("curve" to listOf(3, 20)),
        )

        fun buildParams(fields: List<FieldDef>, dims: Map<String, List<Int>> = emptyMap()): JsonObject {
            val root = Node.Obj()
            val prefixSize = HashMap<String, Int>()
            for (f in fields) {
                val steps = ParamPath.parse(f.template)
                val idx = steps.withIndex().filter { it.value is ParamPath.Step.Index }
                if (idx.size == 1) prefixSize[prefixOf(steps, idx[0].index)] = f.count
            }
            for (f in fields) {
                val steps = ParamPath.parse(f.template)
                val indexPositions = steps.indices.filter { steps[it] is ParamPath.Step.Index }
                val sizes = IntArray(indexPositions.size) { 1 }
                val hint = (steps.first() as? ParamPath.Step.Field)?.let { dims[it.name] }?.takeIf { it.size == indexPositions.size }
                var remaining = f.count
                if (hint != null) {
                    for (k in sizes.indices) sizes[k] = hint[k]
                } else for ((k, pos) in indexPositions.withIndex()) {
                    if (k == indexPositions.lastIndex) { sizes[k] = maxOf(1, remaining); break }
                    val known = prefixSize[prefixOf(steps, pos)]
                    if (known != null && known > 0 && remaining % known == 0) { sizes[k] = known; remaining /= known }
                }
                // brief P3e: the schema does not give the dimensions of these two-index arrays; the stand-in keeps darktable's
                SHAPES[f.template]?.copyInto(sizes)
                insert(root, steps, 0, sizes, 0, f.default)
            }
            return toJson(root) as JsonObject
        }

        /** brief P3e: [channels][nodes] of the curve modules and [channels][black, grey, white] of rgb levels (darktable's structs) */
        private val SHAPES: Map<String, IntArray> = mapOf(
            "tonecurve[0][0].x" to intArrayOf(3, 20), "tonecurve[0][0].y" to intArrayOf(3, 20),
            "curve_nodes[0][0].x" to intArrayOf(3, 20), "curve_nodes[0][0].y" to intArrayOf(3, 20),
            "levels[0][0]" to intArrayOf(3, 3),
        )

        private fun prefixOf(steps: List<ParamPath.Step>, upToInclusive: Int): String = buildString {
            for (i in 0..upToInclusive) when (val s = steps[i]) {
                is ParamPath.Step.Field -> { if (i > 0) append('.'); append(s.name) }
                is ParamPath.Step.Index -> append("[0]")
            }
        }

        private fun insert(node: Node, steps: List<ParamPath.Step>, i: Int, sizes: IntArray, k: Int, leaf: JsonPrimitive) {
            when (val s = steps[i]) {
                is ParamPath.Step.Field -> {
                    val obj = node as Node.Obj
                    if (i == steps.lastIndex) { obj.fields[s.name] = Node.Leaf(leaf); return }
                    val next = steps[i + 1]
                    val child = obj.fields.getOrPut(s.name) {
                        if (next is ParamPath.Step.Index) Node.Arr(sizes[k], null) else Node.Obj()
                    }
                    insert(child, steps, i + 1, sizes, k, leaf)
                }
                is ParamPath.Step.Index -> {
                    val arr = node as Node.Arr
                    arr.size = sizes[k]
                    if (i == steps.lastIndex) { arr.element = Node.Leaf(leaf); return }
                    val next = steps[i + 1]
                    val el = arr.element ?: (if (next is ParamPath.Step.Index) Node.Arr(sizes[k + 1], null) else Node.Obj())
                        .also { arr.element = it }
                    insert(el, steps, i + 1, sizes, k + 1, leaf)
                }
            }
        }

        private fun toJson(n: Node): JsonElement = when (n) {
            is Node.Leaf -> n.value
            is Node.Obj -> JsonObject(n.fields.mapValues { toJson(it.value) })
            is Node.Arr -> {
                val el = n.element?.let { toJson(it) } ?: JsonNull
                JsonArray(List(n.size) { el })
            }
        }
    }
}

internal fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
internal fun JsonElement.arrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

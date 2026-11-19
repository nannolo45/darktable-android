// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

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
 * Reads `schema/ui-schema.json` into [UiSchema] and applies the override files
 * of `schema/overrides/` (format in `schema/overrides/README.md`).
 */
object SchemaLoader {

    class SchemaException(message: String) : Exception(message)

    /** The schema and overrides packed into the app at build time (see schema/build.gradle.kts). */
    fun fromResources(loader: ClassLoader = SchemaLoader::class.java.classLoader): UiSchema {
        fun read(name: String): String? = loader.getResourceAsStream("dtandroid/schema/$name")?.use { it.readBytes().decodeToString() }
        val schema = read("ui-schema.json") ?: throw SchemaException("ui-schema.json is not packed into the app")
        val index = read("overrides/index.txt").orEmpty().lines().map { it.trim() }.filter { it.endsWith(".json") }
        return load(schema, index.associateWith { read("overrides/$it") ?: throw SchemaException("override $it missing") })
    }

    /** Raw ui-schema.json text from the packed resources (the fake engine reads it too). */
    fun schemaTextFromResources(loader: ClassLoader = SchemaLoader::class.java.classLoader): String =
        loader.getResourceAsStream("dtandroid/schema/ui-schema.json")?.use { it.readBytes().decodeToString() }
            ?: throw SchemaException("ui-schema.json is not packed into the app")

    fun load(schemaText: String, overrides: Map<String, String> = emptyMap()): UiSchema {
        val root = Json.parseToJsonElement(schemaText).jsonObject
        val format = root["schema_format"]?.jsonPrimitive?.intOrNull
        if (format != 1) throw SchemaException("unsupported schema_format $format")
        val image = root["image"] as? JsonObject
        val modules = LinkedHashMap<String, ModuleSchema>()
        for ((op, m) in root["modules"]!!.jsonObject) modules[op] = parseModule(op, m.jsonObject)
        for ((file, text) in overrides.toSortedMap()) {
            val o = Json.parseToJsonElement(text).jsonObject
            val op = o.str("op") ?: throw SchemaException("$file: no \"op\"")
            val m = modules[op] ?: throw SchemaException("$file: unknown module $op")
            modules[op] = Overrides.apply(m, o, file)
        }
        return UiSchema(
            darktableVersion = root["darktable_version"]?.jsonPrimitive?.content ?: "unknown",
            imageDescription = listOfNotNull(
                image?.str("camera_maker"), image?.str("camera_model"),
                image?.str("image")?.let { "($it)" },
            ).joinToString(" "),
            modules = modules,
        )
    }

    internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    internal fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonElement.ints(): List<Int> = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()

    private fun parseModule(op: String, o: JsonObject): ModuleSchema {
        val params = LinkedHashMap<String, ParamField>()
        ((o["params"] as? JsonObject)?.get("fields") as? JsonArray)?.forEach {
            val f = it.jsonObject
            if (f.bool("in_array") == true) return@forEach
            val name = f.str("name") ?: return@forEach
            params[name] = ParamField(
                name = name,
                type = f.str("type") ?: "int",
                enumValues = (f["values"] as? JsonArray)?.associate { v ->
                    val vo = v.jsonObject
                    vo.str("name")!! to (vo.int("value") ?: 0)
                } ?: emptyMap(),
            )
        }
        val controls = (o["controls"] as? JsonArray)?.map { parseControl(it.jsonObject, params) } ?: emptyList()
        val actions = LinkedHashMap<Int, Pair<String, String>>().also { collectActions(o["controls"] as? JsonArray, it) }
        val vis = o["visibility"] as? JsonObject
        val sens = o["sensitivity"] as? JsonObject
        val flags = o["flags"] as? JsonObject
        return ModuleSchema(
            op = op,
            name = o.str("name") ?: op,
            version = o.int("version") ?: 0,
            defaultGroups = (o["default_group"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            deprecated = flags?.bool("deprecated") ?: false,
            hasPanel = o.str("status") != "hidden",
            controls = controls,
            visibility = parseRules(vis?.get("rules")),
            sensitivity = parseRules(sens?.get("rules")),
            hiddenInEveryState = vis?.get("hidden_in_every_state")?.ints()?.toSet() ?: emptySet(),
            insensitiveInEveryState = sens?.get("insensitive_in_every_state")?.ints()?.toSet() ?: emptySet(),
            params = params,
            aliases = o.str("aliases") ?: "",
            pipePosition = o.int("pipe_position") ?: 0,
            actionPaths = actions.mapValues { it.value.first },
            actionKinds = actions.mapValues { it.value.second },
        )
    }

    /** Action path and kind of every control that has an action path, by id, in tree order. */
    private fun collectActions(cs: JsonArray?, out: LinkedHashMap<Int, Pair<String, String>>) {
        cs?.forEach {
            val c = it.jsonObject
            val id = c.int("id")
            val path = c.str("action_path")
            if (id != null && !path.isNullOrEmpty()) out[id] = path to (c.str("kind") ?: "unknown")
            collectActions(c["children"] as? JsonArray, out)
        }
    }

    private fun parseRules(e: JsonElement?): Map<Int, Rule> {
        val out = LinkedHashMap<Int, Rule>()
        (e as? JsonArray)?.forEach { re ->
            val r = re.jsonObject
            val node = r.int("node") ?: return@forEach
            val conds = (r["when_all"] as? JsonArray)?.map {
                val c = it.jsonObject
                Condition(
                    param = c.str("param"),
                    control = c.int("control"),
                    allowed = c["in"]?.ints()?.toSet() ?: emptySet(),
                    observed = c["values_observed"]?.ints()?.toSet() ?: emptySet(),
                )
            } ?: emptyList()
            val table = (r["table"] as? JsonObject)?.let { t ->
                Table(
                    variables = (t["variables"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                    visibleFor = (t["visible_for"] as? JsonArray)?.map { it.ints() }?.toSet() ?: emptySet(),
                    hiddenFor = (t["hidden_for"] as? JsonArray)?.map { it.ints() }?.toSet() ?: emptySet(),
                )
            }
            out[node] = Rule(node, conds, if (r.str("form") == "table") table else null, r.bool("verified") ?: true)
        }
        return out
    }

    private fun binding(c: JsonObject): Binding {
        val param = c.str("param")
        return if (param != null && c.str("bound_to") == "params") Binding.Param(param) else Binding.None
    }

    internal fun parseControl(c: JsonObject, params: Map<String, ParamField>): Control {
        val id = c.int("id") ?: throw SchemaException("control without id")
        val label = c.str("label")
        val tooltip = c.str("tooltip")
        val hidden = c.bool("hidden_by_default") ?: false
        fun kids() = (c["children"] as? JsonArray)?.map { parseControl(it.jsonObject, params) } ?: emptyList()
        fun pages() = kids().filterIsInstance<Control.Page>()
        return when (val kind = c.str("kind")) {
            "slider" -> {
                val b = binding(c)
                val pt = c.str("param_type")
                Control.Slider(
                    id, label, tooltip, hidden, b,
                    softMin = c.dbl("soft_min") ?: 0.0, softMax = c.dbl("soft_max") ?: 1.0,
                    hardMin = c.dbl("hard_min") ?: 0.0, hardMax = c.dbl("hard_max") ?: 1.0,
                    default = c.dbl("default") ?: 0.0, step = c.dbl("step") ?: 0.01,
                    digits = c.int("digits") ?: 2, factor = c.dbl("factor") ?: 1.0, offset = c.dbl("offset") ?: 0.0,
                    format = c.str("format") ?: "", textDefault = c.str("text_default"),
                    integer = pt != null && pt != "float",
                    gradient = (c["gradient"] as? JsonArray)?.mapNotNull { st ->
                        val v = (st as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
                        if (v != null && v.size == 4) Control.GradientStop(v[0], v[1], v[2], v[3]) else null
                    } ?: emptyList(),
                    gradientRecomputed = (c["gradient_recomputed"] as? JsonArray)?.isNotEmpty() == true,
                    fillFeedback = c.bool("fill_feedback") ?: true,
                )
            }
            "combobox" -> Control.ComboBox(
                id, label, tooltip, hidden, binding(c),
                entries = (c["entries"] as? JsonArray)?.map {
                    val e = it.jsonObject
                    Control.Entry(e.str("label") ?: "", e.int("value") ?: 0, e.bool("sensitive") ?: true)
                } ?: emptyList(),
                currentPos = c.int("current_pos") ?: 0,
                paramType = c.str("param_type"),
            )
            "toggle" -> Control.Toggle(id, label, tooltip, hidden, binding(c), c.bool("default") ?: false)
            "section" -> Control.Section(id, label, tooltip, hidden)
            "collapsible" -> Control.Collapsible(id, label, tooltip, hidden, c.bool("expanded") ?: false, kids())
            "page" -> Control.Page(id, label, tooltip, hidden, c.bool("visible_child") ?: false, kids())
            "tabs" -> Control.Tabs(id, hidden, pages())
            "stack" -> Control.Stack(id, hidden, pages())
            "row", "grid" -> Control.Group(id, hidden, kids())
            "label" -> Control.Label(id, label, tooltip, hidden)
            else -> Control.Unsupported(id, kind ?: "unknown", label, tooltip, hidden, c.str("action_path"))
        }
    }
}

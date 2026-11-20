// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * Presets, styles and the per-module render times: docs/design/engine-api.md
 * section 5 (work package P1b part 1). As in Model.kt, each type parses the
 * bridge's JSON and writes the same shape back.
 */

private fun bad(what: String): Nothing = throw EngineException(EngineError.ENGINE, "engine JSON: $what")

private fun JsonElement.o(what: String) = this as? JsonObject ?: bad("$what is not an object")
private fun JsonElement.a(what: String) = this as? JsonArray ?: bad("$what is not an array")
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("\"$k\" is not a string")
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: bad("\"$k\" is not an integer")
private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: bad("\"$k\" is not a boolean")
private fun JsonObject.bn(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull ?: bad("\"$k\" is not a number")
private fun JsonObject.sn(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/** An image condition range: [max] null = no upper limit. */
data class PresetRange(val min: Double, val max: Double?) {
    fun toJson(): JsonArray = buildJsonArray { add(JsonPrimitive(min)); add(max?.let { JsonPrimitive(it) } ?: JsonNull) }

    companion object {
        fun fromJson(e: JsonElement?): PresetRange {
            val a = e?.a("range") ?: bad("range missing")
            return PresetRange((a[0] as JsonPrimitive).doubleOrNull ?: 0.0, (a[1] as? JsonPrimitive)?.doubleOrNull)
        }
    }
}

/** The image conditions of a preset (its filter and its auto-apply use them). SQL LIKE patterns, "%" = any. */
data class PresetMatch(
    val model: String = "%", val maker: String = "%", val lens: String = "%",
    val iso: PresetRange = PresetRange(0.0, null),
    val exposure: PresetRange = PresetRange(0.0, null),
    val aperture: PresetRange = PresetRange(0.0, null),
    val focalLength: PresetRange = PresetRange(0.0, 100000.0),
    /** "raw", "ldr", "hdr", "matrix", "monochrome", "color"; empty = any format */
    val format: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("model", model); put("maker", maker); put("lens", lens)
        put("iso", iso.toJson()); put("exposure", exposure.toJson()); put("aperture", aperture.toJson())
        put("focal_length", focalLength.toJson())
        put("format", JsonArray(format.map { JsonPrimitive(it) }))
    }

    companion object {
        fun fromJson(e: JsonElement): PresetMatch {
            val o = e.o("match")
            return PresetMatch(
                o.s("model"), o.s("maker"), o.s("lens"),
                PresetRange.fromJson(o["iso"]), PresetRange.fromJson(o["exposure"]),
                PresetRange.fromJson(o["aperture"]), PresetRange.fromJson(o["focal_length"]),
                o["format"]?.a("format")?.map { (it as JsonPrimitive).content } ?: emptyList(),
            )
        }
    }
}

/** One preset of a module (engine-api.md 5.2). */
data class Preset(
    val name: String,
    val description: String,
    val builtin: Boolean,
    val opVersion: Int,
    val usable: Boolean,
    val enabled: Boolean,
    val autoapply: Boolean,
    val filter: Boolean,
    val match: PresetMatch,
    /** the open image meets [match]; null without an open image */
    val matches: Boolean?,
    /** shown for the open image (desktop's menu): true without [filter]; null without an open image */
    val fits: Boolean?,
    /** the instance's state equals the preset's; null without an open image */
    val active: Boolean?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name); put("description", description); put("builtin", builtin); put("op_version", opVersion)
        put("usable", usable); put("enabled", enabled); put("autoapply", autoapply); put("filter", filter)
        put("match", match.toJson())
        put("matches", matches?.let { JsonPrimitive(it) } ?: JsonNull)
        put("fits", fits?.let { JsonPrimitive(it) } ?: JsonNull)
        put("active", active?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        fun fromJson(e: JsonElement): Preset {
            val o = e.o("preset")
            return Preset(
                o.s("name"), o.s("description"), o.b("builtin"), o.i("op_version"), o.b("usable"), o.b("enabled"),
                o.b("autoapply"), o.b("filter"), PresetMatch.fromJson(o["match"] ?: bad("match missing")),
                o.bn("matches"), o.bn("fits"), o.bn("active"),
            )
        }

        fun listFromJson(e: JsonElement): List<Preset> = e.a("presets").map { fromJson(it) }
    }
}

/** What [DarktableEngine.presetImport] stored. */
data class PresetRef(val op: String, val name: String, val opVersion: Int, val usable: Boolean) {
    fun toJson(): JsonObject = buildJsonObject { put("op", op); put("name", name); put("op_version", opVersion); put("usable", usable) }

    companion object {
        fun fromJson(e: JsonElement): PresetRef {
            val o = e.o("preset import")
            return PresetRef(o.s("op"), o.s("name"), o.i("op_version"), o.b("usable"))
        }
    }
}

/** Why the engine cannot use a stored item (the version guard's words). */
enum class ItemProblem(val json: String) {
    UNKNOWN_OPERATION("unknown_operation"),
    NEWER_MODULE_VERSION("newer_module_version"),
    NEWER_BLEND_VERSION("newer_blend_version");

    companion object {
        fun of(s: String?) = s?.let { v -> entries.firstOrNull { it.json == v } ?: bad("unknown problem \"$v\"") }
    }
}

data class StyleItem(
    val num: Int, val op: String, val priority: Int, val instanceName: String, val enabled: Boolean,
    val moduleVersion: Int, val blendVersion: Int, val problem: ItemProblem?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("num", num); put("op", op); put("priority", priority); put("instance_name", instanceName)
        put("enabled", enabled); put("module_version", moduleVersion); put("blend_version", blendVersion)
        put("problem", problem?.let { JsonPrimitive(it.json) } ?: JsonNull)
    }

    companion object {
        fun fromJson(e: JsonElement): StyleItem {
            val o = e.o("style item")
            return StyleItem(
                o.i("num"), o.s("op"), o.i("priority"), o.s("instance_name"), o.b("enabled"),
                o.i("module_version"), o.i("blend_version"), ItemProblem.of(o.sn("problem")),
            )
        }
    }
}

/** One style (engine-api.md 5.3). */
data class Style(
    val name: String,
    val description: String,
    val hasModuleOrder: Boolean,
    val items: List<StyleItem>,
    /** no item has a problem: [DarktableEngine.styleApply] refuses the others */
    val usable: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name); put("description", description); put("has_module_order", hasModuleOrder)
        put("items", JsonArray(items.map { it.toJson() })); put("usable", usable)
    }

    companion object {
        fun fromJson(e: JsonElement): Style {
            val o = e.o("style")
            return Style(
                o.s("name"), o.s("description"), o.b("has_module_order"),
                (o["items"] ?: bad("items missing")).a("items").map { StyleItem.fromJson(it) }, o.b("usable"),
            )
        }

        fun listFromJson(e: JsonElement): List<Style> = e.a("styles").map { fromJson(it) }
    }
}

/** How a style is applied: desktop's two modes (DT_STYLE_HISTORY_APPEND, _OVERWRITE). */
enum class StyleMode(val json: String) { APPEND("append"), OVERWRITE("overwrite") }

/** The result of [DarktableEngine.stylesImport]. */
data class StyleImport(val imported: List<Imported>, val refused: List<Refused>) {
    data class Imported(val path: String, val name: String, val usable: Boolean)
    /** [reason]: "io", "not_a_style" or "exists" */
    data class Refused(val path: String, val reason: String)

    fun toJson(): JsonObject = buildJsonObject {
        put("imported", JsonArray(imported.map { buildJsonObject { put("path", it.path); put("name", it.name); put("usable", it.usable) } }))
        put("refused", JsonArray(refused.map { buildJsonObject { put("path", it.path); put("reason", it.reason) } }))
    }

    companion object {
        fun fromJson(e: JsonElement): StyleImport {
            val o = e.o("styles import")
            return StyleImport(
                (o["imported"] ?: bad("imported missing")).a("imported").map { val x = it.o("imported"); Imported(x.s("path"), x.s("name"), x.b("usable")) },
                (o["refused"] ?: bad("refused missing")).a("refused").map { val x = it.o("refused"); Refused(x.s("path"), x.s("reason")) },
            )
        }
    }
}

/** One module's time in the last render (engine-api.md 5.6). */
data class ModuleTime(val op: String, val priority: Int, val instanceName: String, val ms: Double, val cpuMs: Double, val tiles: Int)

/** [pipe]: "full", "preview" or null (no render yet in this session). */
data class RenderTimes(val pipe: String?, val totalMs: Double, val modules: List<ModuleTime>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("pipe", pipe?.let { JsonPrimitive(it) } ?: JsonNull)
        put("total_ms", totalMs)
        put("modules", JsonArray(modules.map {
            buildJsonObject {
                put("op", it.op); put("priority", it.priority); put("instance_name", it.instanceName)
                put("ms", it.ms); put("cpu_ms", it.cpuMs); put("tiles", it.tiles)
            }
        }))
    }

    companion object {
        fun fromJson(e: JsonElement): RenderTimes {
            val o = e.o("render times")
            return RenderTimes(
                o.sn("pipe"), o.d("total_ms"),
                (o["modules"] ?: bad("modules missing")).a("modules").map {
                    val m = it.o("module time")
                    ModuleTime(m.s("op"), m.i("priority"), m.s("instance_name"), m.d("ms"), m.d("cpu_ms"), m.i("tiles"))
                },
            )
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.Json
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Data that crosses the engine boundary as JSON (docs/design/engine-api.md
 * section 2). Each type parses the exact JSON shape the specification gives
 * and writes it back, so the JNI implementation can hand over the bridge's
 * JSON text unchanged and the fake engine produces the same text.
 */

internal val engineJson = Json { ignoreUnknownKeys = true }

/** Parse JSON text from the engine; malformed text is an engine failure. */
fun parseEngineJson(text: String): JsonElement = try {
    engineJson.parseToJsonElement(text)
} catch (e: Exception) {
    throw EngineException(EngineError.ENGINE, "malformed JSON from the engine: ${e.message}")
}

private fun JsonObject.req(key: String): JsonElement =
    this[key] ?: throw EngineException(EngineError.ENGINE, "engine JSON lacks \"$key\"")

private fun JsonObject.str(key: String): String = (req(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw EngineException(EngineError.ENGINE, "\"$key\" is not a string")

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.int(key: String): Int = (req(key) as? JsonPrimitive)?.intOrNull
    ?: throw EngineException(EngineError.ENGINE, "\"$key\" is not an integer")

private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.bool(key: String): Boolean = (req(key) as? JsonPrimitive)?.booleanOrNull
    ?: throw EngineException(EngineError.ENGINE, "\"$key\" is not a boolean")

private fun JsonElement.obj(what: String): JsonObject = this as? JsonObject
    ?: throw EngineException(EngineError.ENGINE, "$what is not a JSON object")

private fun JsonElement.arr(what: String): JsonArray = this as? JsonArray
    ?: throw EngineException(EngineError.ENGINE, "$what is not a JSON array")

/** Paths given to [DarktableEngine.init] (engine-api.md 2.1). */
data class EnginePaths(
    val configDir: String,
    val cacheDir: String,
    val dataDir: String,
    val moduleDir: String,
    val tmpDir: String,
    val library: String = ":memory:",
)

/** engine_info() */
data class EngineInfo(
    val darktableVersion: String,
    val apiVersion: Int,
    val threads: Int,
    val pluginFiles: Int,
    val pluginsLoaded: Int,
    val pluginsMissing: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("darktable_version", darktableVersion)
        put("api_version", apiVersion)
        put("threads", threads)
        put("plugins", buildJsonObject {
            put("files", pluginFiles)
            put("loaded", pluginsLoaded)
            put("missing", JsonArray(pluginsMissing.map { JsonPrimitive(it) }))
        })
    }

    companion object {
        fun fromJson(e: JsonElement): EngineInfo {
            val o = e.obj("engine_info")
            val p = o.req("plugins").obj("plugins")
            return EngineInfo(
                darktableVersion = o.str("darktable_version"),
                apiVersion = o.int("api_version"),
                threads = o.int("threads"),
                pluginFiles = p.int("files"),
                pluginsLoaded = p.int("loaded"),
                pluginsMissing = p.req("missing").arr("missing").map { it.jsonPrimitiveContent() },
            )
        }
    }
}

private fun JsonElement.jsonPrimitiveContent(): String =
    (this as? JsonPrimitive)?.content ?: throw EngineException(EngineError.ENGINE, "expected a string")

/** Reasons of xmp_check() problems (engine-api.md 2.2). */
enum class XmpProblemReason(val json: String) {
    UNKNOWN_OPERATION("unknown_operation"),
    NEWER_MODULE_VERSION("newer_module_version"),
    NEWER_BLEND_VERSION("newer_blend_version"),
    NEWER_MASKS_VERSION("newer_masks_version");

    companion object {
        fun of(s: String) = entries.firstOrNull { it.json == s }
            ?: throw EngineException(EngineError.ENGINE, "unknown xmp_check reason \"$s\"")
    }
}

data class XmpProblem(
    val num: Int,
    val operation: String,
    val modversion: Int,
    /** the engine's version of the module, null when the engine does not know it */
    val engineVersion: Int?,
    val reason: XmpProblemReason,
)

/** xmp_check() */
data class XmpCheck(
    val ok: Boolean,
    val xmpVersion: Int,
    val entries: Int,
    val problems: List<XmpProblem>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ok", ok)
        put("xmp_version", xmpVersion)
        put("entries", entries)
        put("problems", buildJsonArray {
            for (p in problems) add(buildJsonObject {
                put("num", p.num)
                put("operation", p.operation)
                put("modversion", p.modversion)
                put("engine_version", p.engineVersion?.let { JsonPrimitive(it) } ?: JsonNull)
                put("reason", p.reason.json)
            })
        })
    }

    companion object {
        fun fromJson(e: JsonElement): XmpCheck {
            val o = e.obj("xmp_check")
            return XmpCheck(
                ok = o.bool("ok"),
                xmpVersion = o.int("xmp_version"),
                entries = o.int("entries"),
                problems = o.req("problems").arr("problems").map {
                    val p = it.obj("problem")
                    XmpProblem(
                        num = p.int("num"),
                        operation = p.str("operation"),
                        modversion = p.int("modversion"),
                        engineVersion = p.intOrNull("engine_version"),
                        reason = XmpProblemReason.of(p.str("reason")),
                    )
                },
            )
        }
    }
}

enum class ImageClass(val json: String) {
    BAYER("bayer"), XTRANS("xtrans"), NONRAW("nonraw"), MONOCHROME("monochrome");

    companion object {
        fun of(s: String) = entries.firstOrNull { it.json == s }
            ?: throw EngineException(EngineError.ENGINE, "unknown image_class \"$s\"")
    }
}

data class Exif(
    val maker: String,
    val model: String,
    val lens: String,
    val iso: Double?,
    val aperture: Double?,
    /** exposure time in seconds */
    val exposure: Double?,
    /** focal length in mm */
    val focalLength: Double?,
    val datetime: String,
)

/** image_info() */
data class ImageInfo(
    val width: Int,
    val height: Int,
    val rawWidth: Int,
    val rawHeight: Int,
    val imageClass: ImageClass,
    val readOnly: Boolean,
    val exif: Exif,
    val rating: Int,
    /**
     * Colour labels. The specification writes `"color_labels": [..]` without an
     * element type; this takes darktable's label numbers (0 red ... 4 purple).
     * Open question in the P2a report.
     */
    val colorLabels: List<Int>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("width", width)
        put("height", height)
        put("raw_width", rawWidth)
        put("raw_height", rawHeight)
        put("image_class", imageClass.json)
        put("read_only", readOnly)
        put("exif", buildJsonObject {
            put("maker", exif.maker)
            put("model", exif.model)
            put("lens", exif.lens)
            put("iso", exif.iso)
            put("aperture", exif.aperture)
            put("exposure", exif.exposure)
            put("focal_length", exif.focalLength)
            put("datetime", exif.datetime)
        })
        put("rating", rating)
        put("color_labels", JsonArray(colorLabels.map { JsonPrimitive(it) }))
    }

    companion object {
        fun fromJson(e: JsonElement): ImageInfo {
            val o = e.obj("image_info")
            val x = o.req("exif").obj("exif")
            return ImageInfo(
                width = o.int("width"),
                height = o.int("height"),
                rawWidth = o.int("raw_width"),
                rawHeight = o.int("raw_height"),
                imageClass = ImageClass.of(o.str("image_class")),
                readOnly = o.bool("read_only"),
                exif = Exif(
                    maker = x.strOrNull("maker") ?: "",
                    model = x.strOrNull("model") ?: "",
                    lens = x.strOrNull("lens") ?: "",
                    iso = x.dbl("iso"),
                    aperture = x.dbl("aperture"),
                    exposure = x.dbl("exposure"),
                    focalLength = x.dbl("focal_length"),
                    datetime = x.strOrNull("datetime") ?: "",
                ),
                rating = o.intOrNull("rating") ?: 0,
                colorLabels = (o["color_labels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    ?: emptyList(),
            )
        }
    }
}

/** One entry of modules(), in pipeline order (engine-api.md 2.4). */
data class ModuleInstance(
    val op: String,
    val name: String,
    val priority: Int,
    val instanceName: String,
    val iopOrder: Int,
    val enabled: Boolean,
    /** false for always-on modules, which show no power icon */
    val canSwitch: Boolean,
    val defaultEnabled: Boolean,
    val hidden: Boolean,
    val deprecated: Boolean,
    val oneInstance: Boolean,
    val supportsBlending: Boolean,
    val version: Int,
    val inHistory: Boolean,
    /** the whole parameter structure; enums as constant names, non-finite numbers as null */
    val params: JsonObject,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("op", op)
        put("name", name)
        put("priority", priority)
        put("instance_name", instanceName)
        put("iop_order", iopOrder)
        put("enabled", enabled)
        put("can_switch", canSwitch)
        put("default_enabled", defaultEnabled)
        put("hidden", hidden)
        put("deprecated", deprecated)
        put("one_instance", oneInstance)
        put("supports_blending", supportsBlending)
        put("version", version)
        put("in_history", inHistory)
        put("params", params)
    }

    companion object {
        fun fromJson(e: JsonElement): ModuleInstance {
            val o = e.obj("module")
            return ModuleInstance(
                op = o.str("op"),
                name = o.str("name"),
                priority = o.int("priority"),
                instanceName = o.strOrNull("instance_name") ?: "",
                iopOrder = o.int("iop_order"),
                enabled = o.bool("enabled"),
                canSwitch = o.bool("can_switch"),
                defaultEnabled = o.bool("default_enabled"),
                hidden = o.bool("hidden"),
                deprecated = o.bool("deprecated"),
                oneInstance = o.bool("one_instance"),
                supportsBlending = o.bool("supports_blending"),
                version = o.int("version"),
                inHistory = o.bool("in_history"),
                params = o.req("params").obj("params"),
            )
        }

        fun listFromJson(e: JsonElement): List<ModuleInstance> = e.arr("modules").map { fromJson(it) }
    }
}

data class HistoryItem(
    val num: Int,
    val op: String,
    val priority: Int,
    val name: String,
    val instanceName: String,
    val enabled: Boolean,
    /** An item the engine could not load (read-only session, unknown module); no effect on the picture. */
    val unknown: Boolean = false,
)

/** history() (engine-api.md 2.6). `end` is the number of items in effect. */
data class History(val end: Int, val items: List<HistoryItem>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("end", end)
        put("items", buildJsonArray {
            for (i in items) add(buildJsonObject {
                put("num", i.num)
                put("op", i.op)
                put("priority", i.priority)
                put("name", i.name)
                put("instance_name", i.instanceName)
                put("enabled", i.enabled)
                put("unknown", i.unknown)
            })
        })
    }

    companion object {
        fun fromJson(e: JsonElement): History {
            val o = e.obj("history")
            return History(
                end = o.int("end"),
                items = o.req("items").arr("items").map {
                    val i = it.obj("history item")
                    HistoryItem(
                        num = i.int("num"),
                        op = i.str("op"),
                        priority = i.int("priority"),
                        name = i.str("name"),
                        instanceName = i.strOrNull("instance_name") ?: "",
                        enabled = i.bool("enabled"),
                        unknown = (i["unknown"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                },
            )
        }
    }
}

data class UndoState(val canUndo: Boolean, val canRedo: Boolean) {
    fun toJson(): JsonObject = buildJsonObject { put("can_undo", canUndo); put("can_redo", canRedo) }

    companion object {
        fun fromJson(e: JsonElement): UndoState {
            val o = e.obj("undo_state")
            return UndoState(o.bool("can_undo"), o.bool("can_redo"))
        }
    }
}

data class RenderSize(val w: Int, val h: Int) {
    fun toJson(): JsonObject = buildJsonObject { put("w", w); put("h", h) }

    companion object {
        fun fromJson(e: JsonElement): RenderSize {
            val o = e.obj("render_size")
            return RenderSize(o.int("w"), o.int("h"))
        }
    }
}

/** A rendered picture: RGBA8, alpha 255, sRGB, rows top to bottom, `width * height * 4` bytes. */
class RenderedImage(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(rgba.size == width * height * 4) { "buffer size ${rgba.size} != ${width}x${height}x4" }
    }
}

enum class ExportFormat(val json: String) { JPEG("jpeg"), PNG("png"), TIFF("tiff"), WEBP("webp") }

enum class ExportProfile(val json: String) { SRGB("srgb"), ADOBERGB("adobergb"), DISPLAYP3("displayp3"), IMAGE("image") }

/** export() options (engine-api.md 2.8). */
data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 95,
    val bitDepth: Int = 8,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val upscale: Boolean = false,
    val highQuality: Boolean = true,
    val profile: ExportProfile = ExportProfile.SRGB,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("format", format.json)
        put("quality", quality)
        put("bit_depth", bitDepth)
        put("max_width", maxWidth)
        put("max_height", maxHeight)
        put("upscale", upscale)
        put("high_quality", highQuality)
        put("profile", profile.json)
    }

    companion object {
        fun fromJson(e: JsonElement): ExportOptions {
            val o = e.obj("export options")
            return ExportOptions(
                format = ExportFormat.entries.firstOrNull { it.json == o.strOrNull("format") }
                    ?: throw EngineException(EngineError.ARG, "unknown export format ${o["format"]}"),
                quality = o.intOrNull("quality") ?: 95,
                bitDepth = o.intOrNull("bit_depth") ?: 8,
                maxWidth = o.intOrNull("max_width") ?: 0,
                maxHeight = o.intOrNull("max_height") ?: 0,
                upscale = (o["upscale"] as? JsonPrimitive)?.booleanOrNull ?: false,
                highQuality = (o["high_quality"] as? JsonPrimitive)?.booleanOrNull ?: true,
                profile = ExportProfile.entries.firstOrNull { it.json == (o.strOrNull("profile") ?: "srgb") }
                    ?: throw EngineException(EngineError.ARG, "unknown export profile ${o["profile"]}"),
            )
        }
    }
}

/** Parameter values for [DarktableEngine.paramSet]: number, bool, or enum constant name. */
object ParamValue {
    fun of(v: Double): JsonPrimitive = JsonPrimitive(v)
    fun of(v: Int): JsonPrimitive = JsonPrimitive(v)
    fun of(v: Boolean): JsonPrimitive = JsonPrimitive(v)
    fun enum(constantName: String): JsonPrimitive = JsonPrimitive(constantName)
}

/** Read a value at a parameter path (`exposure`, `curve[2].x`, `y[0][3]`) inside a params object. */
fun JsonObject.paramAt(path: String): JsonElement? {
    var cur: JsonElement = this
    for (step in ParamPath.parse(path)) {
        cur = when (step) {
            is ParamPath.Step.Field -> (cur as? JsonObject)?.get(step.name) ?: return null
            is ParamPath.Step.Index -> (cur as? JsonArray)?.getOrNull(step.index) ?: return null
        }
    }
    return cur
}

/** Parameter paths as engine-api.md 2.4 writes them. */
object ParamPath {
    sealed interface Step {
        data class Field(val name: String) : Step
        data class Index(val index: Int) : Step
    }

    private val token = Regex("""([A-Za-z_][A-Za-z0-9_]*)|\[(\d+)]|\.""")

    fun parse(path: String): List<Step> {
        if (path.isEmpty()) throw EngineException(EngineError.ARG, "empty parameter path")
        val out = ArrayList<Step>()
        var i = 0
        var expectField = true
        while (i < path.length) {
            val m = token.matchAt(path, i) ?: throw EngineException(EngineError.ARG, "bad parameter path \"$path\"")
            when {
                m.groupValues[1].isNotEmpty() -> {
                    if (!expectField) throw EngineException(EngineError.ARG, "bad parameter path \"$path\"")
                    out += Step.Field(m.groupValues[1]); expectField = false
                }
                m.groupValues[2].isNotEmpty() -> {
                    if (expectField) throw EngineException(EngineError.ARG, "bad parameter path \"$path\"")
                    out += Step.Index(m.groupValues[2].toInt())
                }
                else -> {
                    if (expectField) throw EngineException(EngineError.ARG, "bad parameter path \"$path\"")
                    expectField = true
                }
            }
            i = m.range.last + 1
        }
        if (expectField) throw EngineException(EngineError.ARG, "bad parameter path \"$path\"")
        return out
    }

    /** `curve[2].x` -> `curve[0].x`: the template the control schema lists fields under. */
    fun template(path: String): String = path.replace(Regex("""\[\d+]"""), "[0]")
}

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
internal fun JsonElement.asArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
internal fun JsonElement.asObj(): JsonObject = jsonObject

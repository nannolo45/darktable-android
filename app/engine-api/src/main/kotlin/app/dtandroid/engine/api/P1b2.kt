// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * P1b part 2: docs/design/engine-api.md section 6 (render options, pickers,
 * histogram, coordinates, blending, masks). As in Model.kt, each type parses
 * the bridge's JSON and writes the same shape back.
 */

private fun bad(what: String): Nothing = throw EngineException(EngineError.ENGINE, "engine JSON: $what")
private fun JsonElement.o(what: String) = this as? JsonObject ?: bad("$what is not an object")
private fun JsonElement.a(what: String) = this as? JsonArray ?: bad("$what is not an array")
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("\"$k\" is not a string")
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: bad("\"$k\" is not an integer")
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull ?: bad("\"$k\" is not a number")
private fun JsonElement.dbl(what: String) = (this as? JsonPrimitive)?.doubleOrNull ?: bad("$what is not a number")

/** A module instance by name and priority (darktable's multi_priority). */
data class InstanceRef(val op: String, val priority: Int) {
    fun toJson(): JsonObject = buildJsonObject { put("op", op); put("priority", priority) }
}

/**
 * Options of one render (engine-api.md 6.1). [bypass]: instances processed as if switched off for this call only
 * (no history item, no undo step, the edit untouched). [showMask]: desktop's "show mask" overlay of that instance.
 */
data class RenderOptions(val bypass: List<InstanceRef> = emptyList(), val showMask: InstanceRef? = null) {
    val isNone: Boolean get() = bypass.isEmpty() && showMask == null

    fun toJson(): JsonObject = buildJsonObject {
        if (bypass.isNotEmpty()) put("bypass", JsonArray(bypass.map { it.toJson() }))
        showMask?.let { put("show_mask", it.toJson()) }
    }

    companion object {
        val NONE = RenderOptions()
        fun bypass(op: String, priority: Int) = RenderOptions(bypass = listOf(InstanceRef(op, priority)))
    }
}

// ------------------------------------------------------------------ 6.3 colour pickers

/**
 * A picker request (engine-api.md 6.3): an [area] (x0, y0, x1, y1) or a [point] (x, y) in normalised coordinates of
 * the picture; [denoise] as desktop's pickers with DT_COLOR_PICKER_DENOISE; [fullPipe] picks on the full pipe
 * instead of the preview pipe (desktop's default); [options] as a render's (bypass).
 */
data class PickRequest(
    val area: List<Double>? = null,
    val point: List<Double>? = null,
    val denoise: Boolean = false,
    val fullPipe: Boolean = false,
    val options: RenderOptions = RenderOptions.NONE,
) {
    fun toJson(): JsonObject = buildJsonObject {
        area?.let { a -> put("area", JsonArray(a.map { JsonPrimitive(it) })) }
        point?.let { p -> put("point", JsonArray(p.map { JsonPrimitive(it) })) }
        if (denoise) put("denoise", true)
        if (fullPipe) put("pipe", "full")
        for ((k, v) in options.toJson()) put(k, v)
    }

    companion object {
        fun area(x0: Double, y0: Double, x1: Double, y1: Double) = PickRequest(area = listOf(x0, y0, x1, y1))
        fun point(x: Double, y: Double) = PickRequest(point = listOf(x, y))
    }
}

/** The statistics of one buffer: [colorspace] of the numbers ("raw", "rgb", "lab", "lch", "hsl", "jzczhz", "none"). */
data class PickStats(
    val colorspace: String,
    val mean: List<Double?>,
    val min: List<Double?>,
    val max: List<Double?>,
    /** the sampled rectangle in pixels of that buffer */
    val box: List<Int>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("colorspace", colorspace)
        put("mean", JsonArray(mean.map { if (it == null) JsonNull else JsonPrimitive(it) }))
        put("min", JsonArray(min.map { if (it == null) JsonNull else JsonPrimitive(it) }))
        put("max", JsonArray(max.map { if (it == null) JsonNull else JsonPrimitive(it) }))
        put("box", JsonArray(box.map { JsonPrimitive(it) }))
    }

    companion object {
        private fun nums(o: JsonObject, k: String) = o[k].let { it ?: bad("no \"$k\"") }.a(k).map { (it as? JsonPrimitive)?.doubleOrNull }
        fun fromJson(e: JsonElement?): PickStats? {
            if (e == null || e is JsonNull) return null
            val o = e.o("pick")
            return PickStats(o.s("colorspace"), nums(o, "mean"), nums(o, "min"), nums(o, "max"),
                o["box"]!!.a("box").map { (it as JsonPrimitive).intOrNull ?: bad("box") })
        }
    }
}

/** A pick: the module's [input] and [output] (null where the area gives no sample); the global picker fills [output]. */
data class PickResult(val pipe: String, val input: PickStats?, val output: PickStats?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("pipe", pipe)
        put("input", input?.toJson() ?: JsonNull)
        put("output", output?.toJson() ?: JsonNull)
    }

    companion object {
        fun fromJson(e: JsonElement): PickResult {
            val o = e.o("pick result")
            return PickResult(o.s("pipe"), PickStats.fromJson(o["input"]), PickStats.fromJson(o["output"]))
        }
    }
}

// ------------------------------------------------------------------ 6.4 histogram

/** Desktop's RGB histogram of the preview pipe's picture (engine-api.md 6.4); [counts] per channel, [bins] each. */
data class Histogram(
    val bins: Int,
    val channels: List<String>,
    val counts: List<List<Long>>,
    val max: Long,
    val pixels: Long,
    val width: Int,
    val height: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("bins", bins)
        put("channels", JsonArray(channels.map { JsonPrimitive(it) }))
        put("counts", JsonArray(counts.map { c -> JsonArray(c.map { JsonPrimitive(it) }) }))
        put("max", max); put("pixels", pixels); put("width", width); put("height", height)
    }

    companion object {
        fun fromJson(e: JsonElement): Histogram {
            val o = e.o("histogram")
            fun l(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: bad("\"$k\" is not an integer")
            return Histogram(o.i("bins"), o["channels"]!!.a("channels").map { (it as JsonPrimitive).content },
                o["counts"]!!.a("counts").map { c -> c.a("counts").map { (it as JsonPrimitive).content.toLong() } },
                l("max"), l("pixels"), o.i("width"), o.i("height"))
        }
    }
}

// ------------------------------------------------------------------ 6.5 coordinates

enum class CoordsDirection(val json: String) { TO_PROCESSED("to_processed"), TO_ORIGINAL("to_original") }

/** Points mapped by [DarktableEngine.coordsTransform]: normalised; null for a point no module could map. */
data class CoordsResult(
    val points: List<Pair<Double, Double>?>,
    val originalWidth: Int,
    val originalHeight: Int,
    val processedWidth: Int,
    val processedHeight: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("points", JsonArray(points.map { p -> if (p == null) JsonNull else JsonArray(listOf(JsonPrimitive(p.first), JsonPrimitive(p.second))) }))
        put("original", buildJsonObject { put("width", originalWidth); put("height", originalHeight) })
        put("processed", buildJsonObject { put("width", processedWidth); put("height", processedHeight) })
    }

    companion object {
        fun request(direction: CoordsDirection, points: List<Pair<Double, Double>>, options: RenderOptions): JsonObject = buildJsonObject {
            put("direction", direction.json)
            put("points", JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))
            for ((k, v) in options.toJson()) put(k, v)
        }

        fun fromJson(e: JsonElement): CoordsResult {
            val o = e.o("coords")
            val pts = o["points"]!!.a("points").map { p ->
                if (p is JsonNull) null else p.a("point").let { it[0].dbl("x") to it[1].dbl("y") }
            }
            val orig = o["original"]!!.o("original")
            val proc = o["processed"]!!.o("processed")
            return CoordsResult(pts, orig.i("width"), orig.i("height"), proc.i("width"), proc.i("height"))
        }
    }
}

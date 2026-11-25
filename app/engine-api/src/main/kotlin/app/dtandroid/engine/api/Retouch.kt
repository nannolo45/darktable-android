// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * P3m: docs/design/engine-api.md 6.10, retouch's own shapes (dtb_retouch). Each type parses the bridge's JSON and
 * writes the same shape back.
 */

private fun bad(what: String): Nothing = throw EngineException(EngineError.ENGINE, "engine JSON: $what")
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("\"$k\" is not a string")
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: bad("\"$k\" is not an integer")
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull ?: bad("\"$k\" is not a number")
private fun JsonObject.rgb(k: String) = (this[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: bad("\"$k\"") }
    ?.takeIf { it.size == 3 } ?: bad("\"$k\" is not [r, g, b]")

private fun rgbJson(c: List<Double>) = JsonArray(c.map { JsonPrimitive(it) })

/** One retouch shape: its form ([type] circle, ellipse, path or brush) and its entry of the module's table. */
data class RetouchShape(
    val id: Int,
    /** its place in the module's table (rt_forms) */
    val index: Int,
    val type: String,
    val name: String,
    /** clone and heal shapes have a source (DT_MASKS_CLONE); blur and fill shapes do not */
    val clone: Boolean,
    /** as [MaskForm.geometry], plus "source": [x, y] for clone and heal, original-image fractions */
    val geometry: JsonObject,
    val opacity: Double,
    /** wavelet scale: 0 the image, 1..15 a scale, 16 the residual */
    val scale: Int,
    val algorithm: String,
    val blurType: String,
    val blurRadius: Double,
    val fillMode: String,
    val fillColor: List<Double>,
    val fillBrightness: Double,
) {
    val source: Pair<Double, Double>?
        get() = (geometry["source"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("index", index); put("type", type); put("name", name); put("clone", clone); put("geometry", geometry)
        put("opacity", opacity); put("scale", scale); put("algorithm", algorithm); put("blur_type", blurType); put("blur_radius", blurRadius)
        put("fill_mode", fillMode); put("fill_color", rgbJson(fillColor)); put("fill_brightness", fillBrightness)
    }

    companion object {
        fun fromJson(o: JsonObject) = RetouchShape(
            o.i("id"), o.i("index"), o.s("type"), o.s("name"), (o["clone"] as? JsonPrimitive)?.booleanOrNull ?: bad("\"clone\""),
            o["geometry"] as? JsonObject ?: bad("\"geometry\""), o.d("opacity"), o.i("scale"), o.s("algorithm"), o.s("blur_type"),
            o.d("blur_radius"), o.s("fill_mode"), o.rgb("fill_color"), o.d("fill_brightness"),
        )
    }
}

/** A retouch instance: its shapes (every scale) and the module's settings (the algorithm new shapes get, ...). */
data class RetouchState(
    val group: Int?,
    /** desktop's shape count: every scale */
    val count: Int,
    val max: Int,
    val algorithm: String,
    val numScales: Int,
    val currScale: Int,
    val mergeFromScale: Int,
    val blurType: String,
    val blurRadius: Double,
    val fillMode: String,
    val fillColor: List<Double>,
    val fillBrightness: Double,
    val shapes: List<RetouchShape>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("group", group?.let { JsonPrimitive(it) } ?: JsonNull); put("count", count); put("max", max); put("algorithm", algorithm)
        put("num_scales", numScales); put("curr_scale", currScale); put("merge_from_scale", mergeFromScale)
        put("blur_type", blurType); put("blur_radius", blurRadius); put("fill_mode", fillMode); put("fill_color", rgbJson(fillColor))
        put("fill_brightness", fillBrightness); put("shapes", JsonArray(shapes.map { it.toJson() }))
    }

    companion object {
        val ALGORITHMS = listOf("clone", "heal", "blur", "fill")

        fun fromJson(e: JsonElement): RetouchState {
            val o = e as? JsonObject ?: bad("retouch is not an object")
            return RetouchState(
                (o["group"] as? JsonPrimitive)?.intOrNull, o.i("count"), o.i("max"), o.s("algorithm"), o.i("num_scales"), o.i("curr_scale"),
                o.i("merge_from_scale"), o.s("blur_type"), o.d("blur_radius"), o.s("fill_mode"), o.rgb("fill_color"), o.d("fill_brightness"),
                (o["shapes"] as? JsonArray ?: bad("\"shapes\"")).map { RetouchShape.fromJson(it as? JsonObject ?: bad("shape")) },
            )
        }
    }
}

/**
 * P4c (engine-api.md 6.10, dtb_retouch_focus): retouch's focus. While [focused] and not [paused], the engine's renders,
 * sizes, coordinates and pickers leave out [leftOut] (desktop's skipped modules: crop, borders, ...), so they show the
 * whole picture. [frame]: the edit's picture in it, corners top-left, top-right, bottom-right, bottom-left as
 * fractions of the whole picture; null when nothing that moves pixels is left out.
 */
data class RetouchFocus(
    val focused: Boolean,
    val paused: Boolean,
    val leftOut: List<InstanceRef>,
    val frame: List<Pair<Double, Double>>?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("focused", focused); put("paused", paused)
        put("left_out", JsonArray(leftOut.map { it.toJson() }))
        put("frame", frame?.let { f -> JsonArray(f.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }) } ?: JsonNull)
    }

    companion object {
        val NONE = RetouchFocus(false, false, emptyList(), null)

        fun fromJson(e: JsonElement): RetouchFocus {
            val o = e as? JsonObject ?: bad("retouch focus is not an object")
            val f = (o["frame"] as? JsonArray)?.map { p ->
                val a = p as? JsonArray ?: bad("\"frame\"")
                ((a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: bad("\"frame\"")) to ((a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: bad("\"frame\""))
            }?.takeIf { it.size == 4 }
            return RetouchFocus(
                (o["focused"] as? JsonPrimitive)?.booleanOrNull ?: bad("\"focused\""),
                (o["paused"] as? JsonPrimitive)?.booleanOrNull ?: false,
                (o["left_out"] as? JsonArray)?.map { (it as? JsonObject ?: bad("left_out")).let { r -> InstanceRef(r.s("op"), r.i("priority")) } } ?: emptyList(),
                f,
            )
        }
    }
}

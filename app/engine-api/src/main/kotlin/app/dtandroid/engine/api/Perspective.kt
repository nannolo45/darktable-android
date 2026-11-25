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
 * P3n: docs/design/engine-api.md 6.11, rotate and perspective's structure, guides and fit (dtb_ashift). The engine runs
 * darktable's own code (iop/ashift.c): structure and fit buttons, the mouse on the photo. Positions are fractions of
 * the rendered picture (which, while the module has the focus, is its whole uncropped picture).
 */

private fun bad(what: String): Nothing = throw EngineException(EngineError.ENGINE, "engine JSON: $what")
private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: bad("\"$k\" is not a boolean")
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: bad("\"$k\" is not an integer")
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull ?: bad("\"$k\" is not a number")
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("\"$k\" is not a string")
private fun nums(e: JsonElement?, n: Int, k: String) =
    (e as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: bad(k) }?.takeIf { it.size == n } ?: bad("\"$k\" is not $n numbers")

private fun numsJson(v: List<Double>) = JsonArray(v.map { JsonPrimitive(it) })

/** A structure line on the picture as desktop draws it. [kind]: vertical_used, vertical_outlier, horizontal_used,
 *  horizontal_outlier, other; [color]: desktop's colour for it on screen (green, red, blue, yellow, grey). */
data class GuideLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val kind: String, val color: String, val near: Boolean)

/** A corner of the rectangle or an end of a drawn line. */
data class GuideHandle(val x: Double, val y: Double, val near: Boolean)

data class PerspectiveState(
    val focused: Boolean,
    val enabled: Boolean,
    /** none, lines_found, rectangle, drawn */
    val method: String,
    /** the structure buttons that are on */
    val linesFoundOn: Boolean,
    val rectangleOn: Boolean,
    val drawnOn: Boolean,
    val fitEnabled: Boolean,
    val vertical: Int,
    val horizontal: Int,
    val flipped: Boolean,
    val handled: Boolean,
    val lines: List<GuideLine>,
    val handles: List<GuideHandle>,
    /** shift+drag in progress: where it started and whether it selects */
    val areaFrom: Pair<Double, Double>?,
    val areaSelect: Boolean,
    /** the automatic crop's corners (top-left, bottom-left, bottom-right, top-right) while focused */
    val clip: List<Pair<Double, Double>>?,
    /** cl, cr, ct, cb */
    val crop: List<Double>,
    val rotation: Double,
    val lensshiftV: Double,
    val lensshiftH: Double,
    val shear: Double,
    val cropmode: Int,
    val savedLines: Int,
    val message: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("focused", focused); put("enabled", enabled); put("method", method)
        put("structure", buildJsonObject { put("lines_found", linesFoundOn); put("rectangle", rectangleOn); put("drawn", drawnOn) })
        put("fit_enabled", fitEnabled); put("vertical", vertical); put("horizontal", horizontal); put("flipped", flipped); put("handled", handled)
        put("size", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1))))
        put("lines", JsonArray(lines.map { l ->
            buildJsonObject { put("p", numsJson(listOf(l.x1, l.y1, l.x2, l.y2))); put("kind", l.kind); put("color", l.color); put("near", l.near) }
        }))
        put("handles", JsonArray(handles.map { h -> buildJsonObject { put("p", numsJson(listOf(h.x, h.y))); put("near", h.near) } }))
        put("area", areaFrom?.let { a -> buildJsonObject { put("from", numsJson(listOf(a.first, a.second))); put("select", areaSelect) } } ?: JsonNull)
        put("clip", clip?.let { c -> JsonArray(c.map { numsJson(listOf(it.first, it.second)) }) } ?: JsonNull)
        put("crop", numsJson(crop))
        put("params", buildJsonObject {
            put("rotation", rotation); put("lensshift_v", lensshiftV); put("lensshift_h", lensshiftH); put("shear", shear)
            put("cropmode", cropmode); put("saved_lines", savedLines)
        })
        put("message", message?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        val KINDS = listOf("vertical_used", "vertical_outlier", "horizontal_used", "horizontal_outlier", "other")

        fun fromJson(e: JsonElement): PerspectiveState {
            val o = e as? JsonObject ?: bad("perspective is not an object")
            val st = o["structure"] as? JsonObject ?: bad("\"structure\"")
            val pa = o["params"] as? JsonObject ?: bad("\"params\"")
            val area = o["area"] as? JsonObject
            return PerspectiveState(
                o.b("focused"), o.b("enabled"), o.s("method"), st.b("lines_found"), st.b("rectangle"), st.b("drawn"),
                o.b("fit_enabled"), o.i("vertical"), o.i("horizontal"), o.b("flipped"), o.b("handled"),
                (o["lines"] as? JsonArray ?: bad("\"lines\"")).map { l ->
                    val lo = l as? JsonObject ?: bad("line")
                    val p = nums(lo["p"], 4, "p")
                    GuideLine(p[0], p[1], p[2], p[3], lo.s("kind"), lo.s("color"), lo.b("near"))
                },
                (o["handles"] as? JsonArray ?: bad("\"handles\"")).map { h ->
                    val ho = h as? JsonObject ?: bad("handle")
                    val p = nums(ho["p"], 2, "p")
                    GuideHandle(p[0], p[1], ho.b("near"))
                },
                area?.let { nums(it["from"], 2, "from").let { f -> f[0] to f[1] } }, area?.b("select") ?: false,
                (o["clip"] as? JsonArray)?.map { nums(it, 2, "clip").let { c -> c[0] to c[1] } },
                nums(o["crop"], 4, "crop"),
                pa.d("rotation"), pa.d("lensshift_v"), pa.d("lensshift_h"), pa.d("shear"), pa.i("cropmode"), pa.i("saved_lines"),
                (o["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.engine.api.PickStats
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Brief P3c: colour pickers. A picker belongs to a control, as on desktop (`dt_color_picker_new` in a module's
 * gui_init gives a slider its quad button, or makes a toggle button): the extractor marks those controls
 * ([ModuleSchema.pickerMarks]); the override files say what each does in the app ([PickerSpec]), and which
 * interface-only controls hold its settings ([PickerSetting]). Format: schema/overrides/README.md, "Brief P3c".
 */

/** What a picker does when an area or a point is picked. */
data class PickerSpec(
    val controlId: Int,
    /** a short text for a picker button that stands alone (desktop shows only the icon; its tooltip says this) */
    val label: String,
    /** desktop's DT_COLOR_PICKER_POINT / _AREA: which selections the picker takes */
    val point: Boolean,
    val area: Boolean,
    /** desktop's DT_COLOR_PICKER_DENOISE (the plain picks of a "show" picker use it) */
    val denoise: Boolean,
    /** the module action that applies the pick (engine-api.md 6.3); null: the picker only shows the picked values */
    val action: PickerAction?,
    /**
     * Brief P3c deliverable 3: a slider set from the picked values (no module action): the formula and the
     * parameters it writes. Null for the others.
     */
    val setSlider: PickerSlider? = null,
) {
    /** The selection a new arm starts with when the picker has none yet: a point where it takes points. */
    val startsWithArea: Boolean get() = area && !point
}

/**
 * A module action of engine-api.md 6.3 called with the picked area or point ("area": [x0, y0, x1, y1] or
 * "point": [x, y], normalised picture coordinates) and these arguments.
 */
data class PickerAction(
    val name: String,
    /** fixed arguments */
    val args: JsonObject = JsonObject(emptyMap()),
    /** argument path (dots for an object member: `target.hue`) to the key of a [PickerSetting] */
    val settings: Map<String, String> = emptyMap(),
    /** argument to a tabs control: the index of its selected page, 0 while the tabs are hidden (linked channels) */
    val tabArgs: Map<String, Int> = emptyMap(),
    /** result field to the setting it is written back to (exposure's "measure" gives the target lightness) */
    val results: Map<String, String> = emptyMap(),
    /** another action of the same module used when these settings hold (the neutral case) */
    val instead: Instead? = null,
) {
    data class Instead(val whenSettings: Map<String, JsonPrimitive>, val name: String)

    /** The action's name and arguments for [selection] with the settings and tab pages given. */
    fun call(selection: PickSelection, setting: (String) -> JsonPrimitive?, tab: (Int) -> Int): Pair<String, JsonObject> {
        val out = LinkedHashMap<String, JsonElement>()
        out.putAll(args)
        for ((path, key) in settings) {
            val v = setting(key) ?: continue
            val parts = path.split('.')
            if (parts.size == 1) out[path] = v
            else {
                val inner = LinkedHashMap((out[parts[0]] as? JsonObject) ?: JsonObject(emptyMap()))
                inner[parts[1]] = v
                out[parts[0]] = JsonObject(inner)
            }
        }
        for ((arg, tabs) in tabArgs) out[arg] = JsonPrimitive(tab(tabs))
        out.putAll(selection.toJson())
        val name = instead?.takeIf { i -> i.whenSettings.all { (k, v) -> PickerSetting.same(setting(k), v) } }?.name ?: name
        return name to JsonObject(out)
    }
}

/**
 * Brief P3c deliverable 3: a slider set from the picked values, as the module's `color_picker_apply` does it
 * ([PickerFormulas]): [formula] by name, the parameters it writes by role.
 */
data class PickerSlider(val formula: String, val writes: Map<String, String>, val buffer: String = "input")

/**
 * An interface-only control that holds a picker setting: a slider (a number), a list ([entries]: label to the
 * value passed to the action) or a switch (true or false).
 */
data class PickerSetting(
    val controlId: Int,
    val key: String,
    val default: JsonPrimitive,
    val entries: List<Pair<String, JsonPrimitive>> = emptyList(),
    /** list entries that cannot be chosen, with the reason */
    val unwritable: Map<String, String> = emptyMap(),
    /** desktop applies the armed picker again when this setting changes and these settings hold */
    val reapplyWhen: Map<String, JsonPrimitive>? = null,
) {
    /** The entry shown for value [v] of a list setting, else -1. */
    fun entryOf(v: JsonPrimitive?): Int = entries.indexOfFirst { same(it.second, v) }

    companion object {
        fun same(a: JsonPrimitive?, b: JsonPrimitive?): Boolean {
            if (a == null || b == null) return a == b
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            if (x != null && y != null && !a.isString && !b.isString) return abs(x - y) < 1e-6
            return a.content == b.content
        }
    }
}

/** A picked area or point in normalised picture coordinates (the picture `render_preview` shows). */
data class PickSelection(val area: List<Double>? = null, val point: List<Double>? = null) {
    val isArea: Boolean get() = area != null

    fun toJson(): Map<String, JsonElement> =
        if (area != null) mapOf("area" to kotlinx.serialization.json.JsonArray(area.map { JsonPrimitive(it) }))
        else mapOf("point" to kotlinx.serialization.json.JsonArray(point!!.map { JsonPrimitive(it) }))

    /** The points that are kept in original-image coordinates: the four corners of an area, or the point. */
    fun corners(): List<Pair<Double, Double>> = if (area != null) {
        val (x0, y0, x1, y1) = area
        listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
    } else listOf(point!![0] to point[1])

    companion object {
        fun point(x: Double, y: Double) = PickSelection(point = listOf(x, y))
        fun area(x0: Double, y0: Double, x1: Double, y1: Double) = PickSelection(area = listOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)))

        /**
         * The selection the picture shows for points mapped back from the original image: one point is a point;
         * four corners give their bounding box (a rotation in the edit turns the area's corners), clipped to the
         * picture. Null when a point could not be mapped or the area lies outside the picture.
         */
        fun fromCorners(points: List<Pair<Double, Double>?>, area: Boolean): PickSelection? {
            if (points.any { it == null } || points.isEmpty()) return null
            val p = points.filterNotNull()
            if (!area) return point(p[0].first.coerceIn(0.0, 1.0), p[0].second.coerceIn(0.0, 1.0))
            val x0 = p.minOf { it.first }.coerceIn(0.0, 1.0)
            val x1 = p.maxOf { it.first }.coerceIn(0.0, 1.0)
            val y0 = p.minOf { it.second }.coerceIn(0.0, 1.0)
            val y1 = p.maxOf { it.second }.coerceIn(0.0, 1.0)
            if (x1 - x0 <= 0.0 || y1 - y0 <= 0.0) return null
            return PickSelection(area = listOf(x0, y0, x1, y1))
        }

        /** desktop's first area (`dt_lib_colorpicker_reset_box_area`) and first point (`_reset_point`) */
        val DEFAULT_AREA = area(0.02, 0.02, 0.98, 0.98)
        val DEFAULT_POINT = point(0.5, 0.5)
    }
}

/**
 * The picker's selection on the photo, as the crop frame's ([CropTool]): a tap places a point, a drag draws an area;
 * a drag that starts on the area (inside it or on a side or corner, within a touch target) moves or resizes it, one
 * that starts on the point moves the point. Coordinates are fractions of the picture shown.
 */
object PickerTool {
    const val GRAB_NONE = 0
    const val GRAB_LEFT = 1
    const val GRAB_TOP = 2
    const val GRAB_RIGHT = 4
    const val GRAB_BOTTOM = 8
    const val GRAB_MOVE = 16
    const val GRAB_POINT = 32

    /** the smallest area a resize leaves, as a fraction of the picture */
    const val MIN_SIZE = 0.005

    /**
     * What a touch at ([x], [y]) takes hold of: a side or corner of the area within [tx] x [ty] (half a touch target
     * each way), inside the area (move), the point within a touch target, or nothing (a new selection).
     */
    fun hit(s: PickSelection?, x: Double, y: Double, tx: Double, ty: Double): Int {
        if (s == null) return GRAB_NONE
        s.point?.let { p -> return if (abs(x - p[0]) <= tx && abs(y - p[1]) <= ty) GRAB_POINT else GRAB_NONE }
        val (x0, y0, x1, y1) = s.area!!
        if (x < x0 - tx / 2 || x > x1 + tx / 2 || y < y0 - ty / 2 || y > y1 + ty / 2) return GRAB_NONE
        // a small area keeps its inside for moving: the sides take only what is left
        val hx = min(tx / 2, (x1 - x0) / 4)
        val hy = min(ty / 2, (y1 - y0) / 4)
        var g = 0
        if (abs(x - x0) <= hx) g = g or GRAB_LEFT else if (abs(x - x1) <= hx) g = g or GRAB_RIGHT
        if (abs(y - y0) <= hy) g = g or GRAB_TOP else if (abs(y - y1) <= hy) g = g or GRAB_BOTTOM
        if (g == 0) g = if (x in x0..x1 && y in y0..y1) GRAB_MOVE else GRAB_NONE
        return g
    }

    /** The selection after a drag on [grab] from ([sx], [sy]) to ([x], [y]), starting from [start]; kept on the picture. */
    fun drag(start: PickSelection, grab: Int, sx: Double, sy: Double, x: Double, y: Double): PickSelection {
        val dx = x - sx
        val dy = y - sy
        if (grab == GRAB_POINT) {
            val p = start.point ?: return start
            return PickSelection.point((p[0] + dx).coerceIn(0.0, 1.0), (p[1] + dy).coerceIn(0.0, 1.0))
        }
        val a = start.area ?: return start
        var (x0, y0, x1, y1) = a
        if (grab == GRAB_MOVE) {
            val mx = dx.coerceIn(-x0, 1.0 - x1)
            val my = dy.coerceIn(-y0, 1.0 - y1)
            return PickSelection(area = listOf(x0 + mx, y0 + my, x1 + mx, y1 + my))
        }
        if (grab and GRAB_LEFT != 0) x0 = (x0 + dx).coerceIn(0.0, x1 - MIN_SIZE)
        if (grab and GRAB_RIGHT != 0) x1 = (x1 + dx).coerceIn(x0 + MIN_SIZE, 1.0)
        if (grab and GRAB_TOP != 0) y0 = (y0 + dy).coerceIn(0.0, y1 - MIN_SIZE)
        if (grab and GRAB_BOTTOM != 0) y1 = (y1 + dy).coerceIn(y0 + MIN_SIZE, 1.0)
        return PickSelection(area = listOf(x0, y0, x1, y1))
    }

    /** A new area drawn from ([x0], [y0]) to ([x1], [y1]), clipped to the picture; null when it has no size. */
    fun newArea(x0: Double, y0: Double, x1: Double, y1: Double): PickSelection? {
        val a = PickSelection.area(x0.coerceIn(0.0, 1.0), y0.coerceIn(0.0, 1.0), x1.coerceIn(0.0, 1.0), y1.coerceIn(0.0, 1.0))
        val (ax0, ay0, ax1, ay1) = a.area!!
        return if (ax1 - ax0 < MIN_SIZE || ay1 - ay0 < MIN_SIZE) null else a
    }

    /** A new point at ([x], [y]), on the picture; null when the tap is outside it. */
    fun newPoint(x: Double, y: Double): PickSelection? =
        if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) null else PickSelection.point(x, y)
}

/**
 * Brief P3c deliverable 4: the picked values as desktop's global colour picker shows them (`libs/colorpicker.c`
 * `_update_sample_label`): RGB as `round(v * 255)` of the picture's display RGB, and Lab (D50), with two decimals and
 * L clamped to 0..100; mean, and for an area its min and max.
 */
object PickerReadout {
    /** One line per statistic: "mean", then for an area "min" and "max". Empty without values. */
    fun lines(stats: PickStats?, area: Boolean): List<String> {
        if (stats == null) return emptyList()
        val rows = if (area) listOf("mean" to stats.mean, "min" to stats.min, "max" to stats.max) else listOf("mean" to stats.mean)
        return rows.mapNotNull { (name, v) ->
            val rgb = v.take(3).map { it ?: return@mapNotNull null }
            val lab = labOfSrgb(rgb[0], rgb[1], rgb[2])
            String.format(Locale.ROOT, "%s  RGB %d %d %d  Lab %.2f %.2f %.2f", name,
                (rgb[0] * 255.0).roundToInt(), (rgb[1] * 255.0).roundToInt(), (rgb[2] * 255.0).roundToInt(),
                lab[0].coerceIn(0.0, 100.0), lab[1], lab[2])
        }
    }

    /**
     * CIE Lab (D50) of a display-encoded sRGB colour: the sRGB curve undone, sRGB's primaries adapted to D50 by
     * Bradford (the ICC profile's colorants, as LittleCMS builds darktable's sRGB profile), then CIE 1976 Lab with
     * darktable's D50 white (`dt_XYZ_to_Lab`).
     */
    fun labOfSrgb(r: Double, g: Double, b: Double): DoubleArray {
        fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        val m = SRGB_TO_XYZ_D50
        val x = m[0] * rl + m[1] * gl + m[2] * bl
        val y = m[3] * rl + m[4] * gl + m[5] * bl
        val z = m[6] * rl + m[7] * gl + m[8] * bl
        return xyzToLab(x, y, z)
    }

    /** darktable's `dt_XYZ_to_Lab` (common/colorspaces_inline_conversions.h), D50 white 0.9642, 1, 0.8249 */
    fun xyzToLab(x: Double, y: Double, z: Double): DoubleArray {
        fun f(t: Double): Double {
            val eps = 216.0 / 24389.0
            val kappa = 24389.0 / 27.0
            return if (t > eps) cbrt(t) else (kappa * t + 16.0) / 116.0
        }
        val fx = f(x / 0.9642)
        val fy = f(y)
        val fz = f(z / 0.8249)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /**
     * sRGB to XYZ (D50), row-major: sRGB's primaries and D65 white (x 0.3127, y 0.3290) adapted by Bradford to
     * darktable's D50 white (0.9642, 1, 0.8249), as LittleCMS builds the colorants of darktable's sRGB profile; white
     * maps onto D50 exactly.
     */
    val SRGB_TO_XYZ_D50 = doubleArrayOf(
        0.4360412516, 0.3851129108, 0.1430458376,
        0.2224845402, 0.7169050786, 0.0606103812,
        0.0139201875, 0.0970672387, 0.7139125738,
    )
}

/** A setting's value as a number or a switch, for the generated controls. */
fun JsonPrimitive.settingNumber(): Double? = if (isString) null else doubleOrNull
fun JsonPrimitive.settingBool(): Boolean? = if (isString) null else booleanOrNull

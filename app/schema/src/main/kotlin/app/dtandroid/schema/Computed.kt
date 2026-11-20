// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import app.dtandroid.engine.api.paramAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/**
 * Brief P2j: a control that desktop keeps outside the module's parameters. Its callback writes one or more
 * parameters; the app repeats that callback (and the panel's update from the parameters) on the parameters
 * alone. Built from the override files in `schema/overrides/` ([Overrides]); each kind names the upstream
 * function it follows. [writes] are one history step.
 */
sealed interface Computed {
    val controlId: Int
}

/** A slider shown from parameters and written back by upstream's arithmetic. */
interface ComputedSlider : Computed {
    /**
     * The value desktop shows for [p], or null where it shows the last value set (color calibration's hue at
     * chroma 0). [recall] gives the last value the app set on another control of this module, or null.
     */
    fun value(p: JsonObject, recall: (Int) -> Double?): Double?

    /** The parameters desktop's callback writes when the slider is set to [v]. */
    fun write(v: Double, p: JsonObject, recall: (Int) -> Double?): Map<String, JsonPrimitive>
}

/** One entry of a computed list; [value] is the position the schema's rules name ("control N"). */
data class ComputedEntry(val label: String, val value: Int, val enabled: Boolean, val section: Boolean = false)

/**
 * Brief P3b (P2j section 6): what desktop's callback writes through an engine module action instead of parameters,
 * for values outside a field's limits (engine-api.md 5.4: framing's `set_aspect` 0 and -1, lens' `use_latest_md`).
 */
data class ModuleWrite(val action: String, val args: JsonObject = JsonObject(emptyMap()))

/** A list whose entries and current entry come from parameters (and packaged data). */
interface ComputedList : Computed {
    fun entries(p: JsonObject): List<ComputedEntry>

    /** Index into [entries] of the entry desktop shows, or null when it shows none. */
    fun current(p: JsonObject): Int?

    /** The parameters an entry writes; empty when choosing it changes nothing. */
    fun choose(index: Int, p: JsonObject): Map<String, JsonPrimitive>

    /** Brief P3b: the module action an entry is written with instead of [choose]'s parameters, or null. */
    fun chooseAction(index: Int, p: JsonObject): ModuleWrite? = null

    /** Whether desktop lets the list be used for these parameters (besides the schema's rules). */
    fun enabled(p: JsonObject): Boolean = true

    /** The value the schema's visibility and sensitivity rules see for this control. */
    fun ruleValue(p: JsonObject): Int? = current(p)?.let { entries(p).getOrNull(it)?.value }

    /** Text shown when no entry matches, or null for none. */
    fun fallbackText(p: JsonObject): String? = null
}

/** A switch whose callback writes parameters; desktop shows it from parameters by [on]. */
interface ComputedToggle : Computed {
    fun on(p: JsonObject): Boolean
    fun write(on: Boolean, p: JsonObject): Map<String, JsonPrimitive>

    /** Brief P3b: the module action the switch is written with instead of [write]'s parameters, or null. */
    fun action(on: Boolean, p: JsonObject): ModuleWrite? = null
}

internal fun JsonObject.num(path: String): Double? = (paramAt(path) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.booleanOrNull?.let { b -> if (b) 1.0 else 0.0 } }
internal fun JsonObject.text(path: String): String? = (paramAt(path) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A float as desktop stores it (single precision), for JSON. */
internal fun f32(v: Float) = JsonPrimitive(v.toDouble())

/**
 * A list of fixed entries, each writing fixed values (framing's aspect and positions: `borders.c`
 * `_aspect_changed`, `_position_h_changed`; spektrafilm's format: `_format_changed`; the curve modules'
 * interpolation: `rgbcurve.c` / `tonecurve.c` `interpolator_callback`; the colour profiles' built-in entries).
 * Desktop shows the first entry whose [Item.set] matches the parameters ([read] within [tolerance] for numbers,
 * equal for text), else [unmatched] (framing's "custom...").
 */
class ValueList(
    override val controlId: Int,
    val items: List<Item>,
    val read: List<String>,
    val tolerance: Double,
    val unmatched: Int?,
    /** entries that cannot be written through the bridge (a value outside the field's limits), by index */
    val unwritable: Map<Int, String> = emptyMap(),
) : ComputedList {
    /**
     * [set] null: a section heading or an entry that writes nothing (framing's "custom..."). [action]: written through
     * that module action instead of [set] ([set] still says when the entry is the one shown).
     */
    data class Item(val label: String, val value: Int, val set: Map<String, JsonPrimitive>?, val section: Boolean, val action: ModuleWrite? = null)

    override fun entries(p: JsonObject) = items.mapIndexed { i, it ->
        ComputedEntry(it.label, it.value, !it.section && i !in unwritable, it.section)
    }

    override fun current(p: JsonObject): Int? {
        for ((i, it) in items.withIndex()) {
            val set = it.set ?: continue
            if (read.all { r -> matches(p, r, set[r]) }) return i
        }
        return unmatched
    }

    private fun matches(p: JsonObject, path: String, want: JsonPrimitive?): Boolean {
        if (want == null) return true
        val have = p.paramAt(path) as? JsonPrimitive ?: return false
        val wd = want.doubleOrNull
        val hd = have.doubleOrNull
        return if (!want.isString && wd != null && hd != null) abs(hd - wd) < tolerance else have.content == want.content
    }

    override fun choose(index: Int, p: JsonObject): Map<String, JsonPrimitive> = items.getOrNull(index)?.set ?: emptyMap()

    override fun chooseAction(index: Int, p: JsonObject): ModuleWrite? = items.getOrNull(index)?.action
}

/** A switch that writes fixed values when switched on and is shown on when [onWhen] holds. */
class ToggleWrites(
    override val controlId: Int,
    val set: Map<String, JsonPrimitive>,
    val onWhen: Map<String, JsonPrimitive>,
    /** brief P3b: written through this module action when switched on (lens' "use latest algorithm") */
    val onAction: ModuleWrite? = null,
) : ComputedToggle {
    override fun on(p: JsonObject) = onWhen.isNotEmpty() && onWhen.all { (k, v) -> (p.paramAt(k) as? JsonPrimitive)?.content == v.content }
    override fun write(on: Boolean, p: JsonObject) = if (on) set else emptyMap()
    override fun action(on: Boolean, p: JsonObject) = if (on) onAction else null
}

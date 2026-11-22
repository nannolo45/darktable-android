// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.Blend
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.PickRequest
import app.dtandroid.engine.api.PickStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Brief P3d stage 2b: classic parametric masks (blend_get's `parametric` channels, engine-api.md 6.6) and the mask
 * refinement controls (feathering, blur, brightness, contrast, details). Manual notes: docs/design/manual-notes-masks.md.
 * Screens: ui/ParametricMask.kt, ui/MaskRefinement.kt, ui/RangeSlider4.kt (the pickers go through the shared P3c tool).
 */

/** One channel button: [base] is the engine's name without `_in` / `_out`, [boost] whether the channel has a boost factor. */
data class ChannelSpec(val base: String, val label: String, val boost: Boolean) {
    val inName get() = base + "_in"
    val outName get() = base + "_out"
}

/** The channels of each blend colour space, in the order of the manual (parametric masks page). */
object ParamChannels {
    val LAB = listOf(ChannelSpec("L", "L", false), ChannelSpec("a", "a", false), ChannelSpec("b", "b", false),
        ChannelSpec("C", "C", false), ChannelSpec("h", "h", false))
    val DISPLAY = listOf(ChannelSpec("g", "g", false), ChannelSpec("R", "R", false), ChannelSpec("G", "G", false),
        ChannelSpec("B", "B", false), ChannelSpec("H", "H", false), ChannelSpec("S", "S", false), ChannelSpec("l", "L", false))
    val SCENE = listOf(ChannelSpec("g", "g", true), ChannelSpec("R", "R", true), ChannelSpec("G", "G", true),
        ChannelSpec("B", "B", true), ChannelSpec("Jz", "Jz", true), ChannelSpec("Cz", "Cz", true), ChannelSpec("hz", "hz", false))

    /** [space]: blend_get's effective colour space ("lab", "rgb_display", "rgb_scene"); "raw" and others have none. */
    fun of(space: String): List<ChannelSpec> = when (space) { "lab" -> LAB; "rgb_display" -> DISPLAY; "rgb_scene" -> SCENE; else -> emptyList() }

    fun spaceLabel(space: String) = when (space) { "lab" -> "Lab"; "rgb_display" -> "display RGB"; "rgb_scene" -> "scene RGB"; "raw" -> "raw"; else -> space }

    /** desktop's default boost of a channel (blend.c `dt_develop_blend_init_blendif`: Jz and Cz start at -log2(100)) */
    fun defaultBoost(space: String, base: String): Double = if (space == "rgb_scene" && (base == "Jz" || base == "Cz")) -6.64385619 else 0.0
}

/** The four markers of a range slider (engine-api.md 6.6: a <= b <= c <= d, each in 0..1). */
object RangeMath {
    val FULL = listOf(0.0, 0.0, 1.0, 1.0)

    fun isFull(r: List<Double>) = r.size == 4 && r[0] <= 0.0 && r[1] <= 0.0 && r[2] >= 1.0 && r[3] >= 1.0

    /** Marker [i] moved to [v]: it stops at its neighbours (they may touch, never cross) and at 0 and 1. */
    fun move(r: List<Double>, i: Int, v: Double): List<Double> {
        val lo = if (i == 0) 0.0 else r[i - 1]
        val hi = if (i == 3) 1.0 else r[i + 1]
        return r.toMutableList().also { it[i] = v.coerceIn(lo, hi) }
    }

    /**
     * The markers a finger at [x] may take: those within [tol] of it that are nearest (several when markers touch;
     * the direction of the first movement then decides). Empty when none is near.
     */
    fun candidates(r: List<Double>, x: Double, tol: Double): List<Int> {
        val nearest = r.minOf { abs(it - x) }
        if (nearest > tol) return emptyList()
        return r.indices.filter { abs(r[it] - x) <= nearest + 1e-9 }
    }
}

/**
 * The range a picked area gives a channel, as desktop's `_blendif_scale` and its "set range" picker do it
 * (blend_gui.c): the picked minimum and maximum scaled to the slider's 0..1, a feather of 0.01 on each side.
 * Not built: the reversed hue range across the wrap, the profile's own luminance for `g` (0.3, 0.59, 0.11 as
 * desktop's fallback), `h` from a Lab pick (needs a Lch pick) and the polarity bit.
 */
object PickRange {
    /** the colour space the channel's numbers are in, as desktop's `_picker_colorspace_for_channel` */
    fun needed(space: String, base: String): String? = when (space) {
        "rgb_display" -> if (base in listOf("g", "R", "G", "B")) "rgb" else "hsl"
        "rgb_scene" -> if (base in listOf("g", "R", "G", "B")) "rgb" else "jzczhz"
        "lab" -> if (base in listOf("L", "a", "b")) "lab" else "lch"
        else -> null
    }

    /** a pick's own space as the module's picker gives it: "none" is the buffer's own, the blend colour space's here */
    private fun have(space: String, pickSpace: String): String? = when (pickSpace) {
        "none" -> if (space == "lab") "lab" else if (space.startsWith("rgb")) "rgb" else null
        else -> pickSpace
    }

    /** the channel's bounds on 0..1 from the picked [min] and [max] vectors, or null when this pick cannot give them */
    fun bounds(space: String, base: String, pickSpace: String, mins: List<Double?>, maxs: List<Double?>, boostEv: Double): Pair<Double, Double>? {
        val need = needed(space, base) ?: return null
        val got = have(space, pickSpace)
        val k = 2.0.pow(boostEv)
        fun v(l: List<Double?>, i: Int): Double? = l.getOrNull(i)
        fun both(f: (List<Double?>) -> Double?): Pair<Double, Double>? {
            val a = f(mins) ?: return null
            val b = f(maxs) ?: return null
            return a / k to b / k
        }
        return when {
            got == need -> when (base) {
                "L" -> both { v(it, 0)?.div(100.0) }
                "a" -> both { v(it, 1)?.let { x -> (x + 128.0) / 256.0 } }
                "b" -> both { v(it, 2)?.let { x -> (x + 128.0) / 256.0 } }
                "C" -> both { v(it, 1)?.div(128.0 * sqrt(2.0)) }
                "h" -> both { v(it, 2) }
                "g" -> both { l -> val r = v(l, 0); val g = v(l, 1); val b = v(l, 2); if (r == null || g == null || b == null) null else 0.3 * r + 0.59 * g + 0.11 * b }
                "R" -> both { v(it, 0) }
                "G" -> both { v(it, 1) }
                "B" -> both { v(it, 2) }
                "H", "Jz" -> both { v(it, 0) }
                "S", "Cz" -> both { v(it, 1) }
                "l", "hz" -> both { v(it, 2) }
                else -> null
            }
            // chroma from a Lab pick: the picked box of (a, b) gives the nearest and the farthest distance from grey
            need == "lch" && got == "lab" && base == "C" -> {
                val a0 = v(mins, 1); val a1 = v(maxs, 1); val b0 = v(mins, 2); val b1 = v(maxs, 2)
                if (a0 == null || a1 == null || b0 == null || b1 == null) null
                else {
                    fun near(lo: Double, hi: Double) = if (lo <= 0.0 && hi >= 0.0) 0.0 else min(abs(lo), abs(hi))
                    val c0 = hypot(near(a0, a1), near(b0, b1))
                    val c1 = hypot(max(abs(a0), abs(a1)), max(abs(b0), abs(b1)))
                    val d = 128.0 * sqrt(2.0) * k
                    c0 / d to c1 / d
                }
            }
            else -> null
        }
    }

    /** the four markers for [lo]..[hi] (desktop: feather 0.01, the inner markers on the picked values when it is wider) */
    fun markers(lo: Double, hi: Double): List<Double> {
        val a = min(lo, hi)
        val b = max(lo, hi)
        val feather = 0.01
        var p0 = (a - feather).coerceIn(0.0, 1.0)
        var p1 = (a + feather).coerceIn(0.0, 1.0)
        var p2 = (b - feather).coerceIn(0.0, 1.0)
        var p3 = (b + feather).coerceIn(0.0, 1.0)
        if (p1 > p2) { p1 = a.coerceIn(0.0, 1.0); p2 = b.coerceIn(0.0, 1.0) }
        p0 = p0.coerceIn(0.0, p1)
        p3 = p3.coerceIn(p2, 1.0)
        return listOf(p0, p1, p2, p3)
    }

    fun fromStats(space: String, base: String, s: PickStats, boostEv: Double): List<Double>? =
        bounds(space, base, s.colorspace, s.min, s.max, boostEv)?.let { (lo, hi) -> markers(lo, hi) }
}

/** The sheets of the parametric section. */
sealed interface ParamSheet {
    data object Space : ParamSheet
    /** asked before the colour space changes: it resets the parametric channels */
    data class ConfirmSpace(val space: String) : ParamSheet
    data object Guide : ParamSheet
}

data class ParametricState(
    /** the instance whose blending [blend] is */
    val key: InstanceKey? = null,
    val blend: Blend? = null,
    /** channels (their base names) whose output slider is shown */
    val outputShown: Set<String> = emptySet(),
    val refineOpen: Boolean = false,
    val sheet: ParamSheet? = null,
    val note: String? = null,
) {
    /** blend_get's effective colour space, "" when unknown */
    val space: String get() = blend?.effectiveColorspace ?: blend?.colorspace ?: ""
    /** blend_get's feathering guide */
    val guide: String get() = (blend?.json?.get("feathering_guide") as? JsonPrimitive)?.content ?: ParametricMasks.DEFAULT_GUIDE
}

/** The parametric section and the refinement of the masks tab (every change one history item, as other sliders). */
class ParametricMasks internal constructor(private val c: DarkroomController) {
    private val _state = MutableStateFlow(ParametricState())
    val state: StateFlow<ParametricState> = _state.asStateFlow()

    private val readOnly get() = c.state.value.readOnly

    fun reset() { _state.value = ParametricState() }

    /** Read the blending of [key] (null: no module chosen or no blending), under the engine lock. */
    internal suspend fun readLocked(e: DarktableEngine, key: InstanceKey?) {
        val blend = if (key == null) null else try { e.blendGet(key.op, key.priority).takeIf { it.supported } } catch (x: EngineException) { null }
        _state.update { if (it.key != key) it.copy(key = key, blend = blend, outputShown = emptySet(), sheet = null, note = null) else it.copy(blend = blend) }
    }

    fun openSheet(sheet: ParamSheet?) = _state.update { it.copy(sheet = sheet, note = null) }
    fun toggleRefine() = _state.update { it.copy(refineOpen = !it.refineOpen) }
    fun toggleOutput(base: String) = _state.update { it.copy(outputShown = if (base in it.outputShown) it.outputShown - base else it.outputShown + base) }

    private fun noteOf(x: Exception): String = (x as? EngineException)?.detail ?: x.message ?: x.javaClass.simpleName

    private suspend fun edit(block: suspend (DarktableEngine) -> Unit) {
        if (readOnly) return
        _state.update { it.copy(note = null) }
        try { c.engineChange { e -> block(e) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(note = noteOf(e)) } }
    }

    private suspend fun set(values: Map<String, JsonElement>) {
        val k = _state.value.key ?: return
        edit { e -> e.blendSet(k.op, k.priority, values); e.historySeal() }
    }

    private fun num(v: Double) = JsonPrimitive(v)
    private fun arr(r: List<Double>) = JsonArray(r.map { JsonPrimitive(it) })

    // ------------------------------------------------------------------ channels

    /** A channel button: its row appears (desktop: the channel's sliders; the range starts as the whole range). */
    suspend fun addChannel(sp: ChannelSpec) {
        val b = _state.value.blend ?: return
        if (b.parametric[sp.inName]?.active == true) return
        set(mapOf("parametric.${sp.inName}.active" to JsonPrimitive(true)))
    }

    /** Remove a channel: both its ranges back to the whole range, not inverted, not active (the boost stays). */
    suspend fun removeChannel(sp: ChannelSpec) {
        _state.update { it.copy(outputShown = it.outputShown - sp.base) }
        val m = LinkedHashMap<String, JsonElement>()
        for (n in listOf(sp.inName, sp.outName)) {
            m["parametric.$n.range"] = arr(RangeMath.FULL)
            m["parametric.$n.invert"] = JsonPrimitive(false)
            m["parametric.$n.active"] = JsonPrimitive(false)
        }
        set(m)
    }

    /** The markers of [name] (`L_in`, ...) set: one history item per call. The order is kept (a <= b <= c <= d). */
    suspend fun setRange(name: String, range: List<Double>) {
        if (range.size != 4) return
        var prev = 0.0
        val r = range.map { v -> v.coerceIn(prev, 1.0).also { prev = it } }
        set(mapOf("parametric.$name.range" to arr(r), "parametric.$name.active" to JsonPrimitive(true)))
    }

    suspend fun setInvert(name: String, on: Boolean) = set(mapOf("parametric.$name.invert" to JsonPrimitive(on), "parametric.$name.active" to JsonPrimitive(true)))

    suspend fun setBoost(name: String, ev: Double) = set(mapOf("parametric.$name.boost" to num(ev)))

    // ------------------------------------------------------------------ colour space

    /** A colour space chosen: asked first (it resets the parametric channels), done by [confirmSpace]. */
    fun askSpace(space: String) {
        val b = _state.value.blend ?: return
        if (space == b.colorspace) { openSheet(null); return }
        _state.update { it.copy(sheet = ParamSheet.ConfirmSpace(space), note = null) }
    }

    suspend fun confirmSpace() {
        val sh = _state.value.sheet as? ParamSheet.ConfirmSpace ?: return
        _state.update { it.copy(sheet = null, outputShown = emptySet()) }
        set(mapOf("colorspace" to JsonPrimitive(sh.space)))
    }

    // ------------------------------------------------------------------ refinement

    suspend fun setRefine(path: String, value: Double) = set(mapOf(path to num(value)))

    suspend fun setGuide(guide: String) {
        _state.update { it.copy(sheet = null) }
        set(mapOf("feathering_guide" to JsonPrimitive(guide)))
    }

    /** Back to desktop's defaults: no details, no feathering, no blur, brightness and contrast 0, guide input after blur. */
    suspend fun resetRefine() = set(mapOf("details" to num(0.0), "feathering_radius" to num(0.0), "feathering_guide" to JsonPrimitive(DEFAULT_GUIDE),
        "blur_radius" to num(0.0), "brightness" to num(0.0), "contrast" to num(0.0)))

    // ------------------------------------------------------------------ picker (the shared picker tool of P3c)

    /** the picker id of a channel slider: a negative number below [PICK_BASE], by its place in blend_get's list */
    fun pickerId(name: String): Int {
        val i = _state.value.blend?.parametric?.keys?.indexOf(name) ?: -1
        return if (i < 0) 0 else PICK_BASE - i
    }

    /**
     * The armed picker's area picked: the channel's range set from it (desktop's "set range" picker), under the
     * engine lock, as one history item. Returns whether the edit changed.
     */
    internal suspend fun pickLocked(e: DarktableEngine, key: InstanceKey, controlId: Int, area: List<Double>?, point: List<Double>?): Boolean {
        val st = _state.value
        val b = st.blend ?: return false
        val name = b.parametric.keys.toList().getOrNull(PICK_BASE - controlId) ?: return false
        val base = name.substringBeforeLast('_')
        val out = name.endsWith("_out")
        val pick = e.colorPick(key.op, key.priority, PickRequest(area, point))
        val stats = (if (out) pick.output else pick.input)
        if (stats == null) { _state.update { it.copy(note = "the picked area gives no sample for ${base} ${if (out) "output" else "input"}") }; return false }
        val ch = b.parametric[name] ?: return false
        val range = PickRange.fromStats(st.space, base, stats, ch.boost)
        if (range == null) { _state.update { it.copy(note = "this module's picker does not give ${base} (${stats.colorspace}): set the range by hand") }; return false }
        e.blendSet(key.op, key.priority, mapOf("parametric.$name.range" to arr(range), "parametric.$name.active" to JsonPrimitive(true)))
        e.historySeal()
        _state.update { it.copy(note = null) }
        readLocked(e, key)
        return true
    }

    companion object {
        /** control ids at or below this are the parametric sliders' pickers (the schema's control ids are positive) */
        const val PICK_BASE = -1000
        const val DEFAULT_GUIDE = "input_after_blur"
        val GUIDES = listOf("output_before_blur" to "output before blur", "input_before_blur" to "input before blur",
            "output_after_blur" to "output after blur", "input_after_blur" to "input after blur")
    }
}

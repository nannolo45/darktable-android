// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.Blend
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.MaskForm
import app.dtandroid.engine.api.MaskMember
import app.dtandroid.engine.api.PickRequest
import app.dtandroid.engine.api.PickStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
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
    /** asked before the colour space changes: it removes the parametric elements */
    data class ConfirmSpace(val space: String) : ParamSheet
    data object Guide : ParamSheet
}

/** One parametric element of the module's mask group (engine-api.md 6.9): one channel, its ranges, its member settings. */
data class ParamElement(
    val id: Int,
    /** the channel without `_in` / `_out`: `L`, `g`, `Jz`, ... */
    val base: String,
    val space: String,
    val inRange: List<Double>,
    val outRange: List<Double>,
    /** the member's invert (desktop's element row) */
    val inverse: Boolean,
    val disabled: Boolean,
    val boostSupported: Boolean,
    val boost: Double,
    val showOutput: Boolean,
    /** desktop's warning badge: the element restricts nothing */
    val whole: Boolean,
)

data class ParametricState(
    /** the instance whose blending [blend] and elements are */
    val key: InstanceKey? = null,
    val blend: Blend? = null,
    /** the module's mask group (blend_get's mask_id), null = none yet */
    val root: Int? = null,
    /** the parametric elements among its direct members, bottom first */
    val elements: List<ParamElement> = emptyList(),
    val refineOpen: Boolean = false,
    val sheet: ParamSheet? = null,
    val note: String? = null,
) {
    /** blend_get's effective colour space, "" when unknown */
    val space: String get() = blend?.effectiveColorspace ?: blend?.colorspace ?: ""
    /** blend_get's feathering guide */
    val guide: String get() = (blend?.json?.get("feathering_guide") as? JsonPrimitive)?.content ?: ParametricMasks.DEFAULT_GUIDE
}

/**
 * Parametric elements and the refinement of the masks tab (every change one history item, as other sliders).
 * At the pinned engine a module's mask is a flexi tree (engine-api.md 6.9): a channel button adds a parametric
 * element to the module's group, its ranges, boost and output view are the element's geometry, its invert the
 * member's. The classic `blend_get` `parametric` channels are not used (the engine migrates them into the tree).
 * The refinement stays on `blend_set` (feathering, blur, brightness, contrast, details).
 */
class ParametricMasks internal constructor(private val c: DarkroomController) {
    private val _state = MutableStateFlow(ParametricState())
    val state: StateFlow<ParametricState> = _state.asStateFlow()

    private val readOnly get() = c.state.value.readOnly

    fun reset() { _state.value = ParametricState() }

    /** Read the blending and the elements of [key] (null: no module chosen or no blending), under the engine lock. */
    internal suspend fun readLocked(e: DarktableEngine, key: InstanceKey?) {
        var blend: Blend? = null
        var root: Int? = null
        var els = emptyList<ParamElement>()
        if (key != null) try {
            blend = e.blendGet(key.op, key.priority).takeIf { it.supported }
            root = blend?.maskId
            if (root != null) els = elementsOf(e.masks(), root)
        } catch (x: EngineException) { blend = null }
        _state.update { if (it.key != key) it.copy(key = key, blend = blend, root = root, elements = els, sheet = null, note = null) else it.copy(blend = blend, root = root, elements = els) }
    }

    private fun elementsOf(forms: List<MaskForm>, root: Int): List<ParamElement> {
        val group = forms.firstOrNull { it.id == root } ?: return emptyList()
        val out = ArrayList<ParamElement>()
        for (m in (group.geometry["members"] as? JsonArray) ?: JsonArray(emptyList())) {
            val o = m as? JsonObject ?: continue
            val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: continue
            val f = forms.firstOrNull { it.id == id }
            if (f != null && f.type == "parametric") {
                val g = f.geometry
                fun rng(k: String) = (g[k] as? JsonArray)?.map { (it as JsonPrimitive).double } ?: RangeMath.FULL
                fun flag(k: String) = (g[k] as? JsonPrimitive)?.booleanOrNull ?: false
                out += ParamElement(id, (g["channel"] as? JsonPrimitive)?.content ?: "", (g["colorspace"] as? JsonPrimitive)?.content ?: "",
                    rng("in"), rng("out"), (o["inverse"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    (o["disabled"] as? JsonPrimitive)?.booleanOrNull ?: false, flag("boost_supported"),
                    (g["boost"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, flag("show_output"), flag("whole_range"))
            }
        }
        return out
    }

    fun openSheet(sheet: ParamSheet?) = _state.update { it.copy(sheet = sheet, note = null) }
    fun toggleRefine() = _state.update { it.copy(refineOpen = !it.refineOpen) }

    private fun noteOf(x: Exception): String = (x as? EngineException)?.detail ?: x.message ?: x.javaClass.simpleName

    private suspend fun edit(block: suspend (DarktableEngine) -> Unit) {
        if (readOnly) return
        _state.update { it.copy(note = null) }
        try { c.engineChange { e -> block(e) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(note = noteOf(e)) } }
    }

    private suspend fun setBlend(values: Map<String, JsonElement>) {
        val k = _state.value.key ?: return
        edit { e -> e.blendSet(k.op, k.priority, values); e.historySeal() }
    }

    private fun num(v: Double) = JsonPrimitive(v)
    private fun arr(r: List<Double>) = JsonArray(r.map { JsonPrimitive(it) })
    private fun element(id: Int) = _state.value.elements.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ elements

    /** A channel button: a new element (the whole range, so it restricts nothing yet) in the module's mask group. */
    suspend fun addChannel(spec: ChannelSpec) {
        val st = _state.value
        val k = st.key ?: return
        val geometry = buildJsonObject { put("channel", spec.base); put("in", arr(RangeMath.FULL)); put("out", arr(RangeMath.FULL)) }
        edit { e -> e.maskAdd(k.op, k.priority, "parametric", geometry, group = st.root); e.historySeal() }
    }

    /** Remove an element: out of the group, and the form deleted (it is nobody else's). */
    suspend fun removeChannel(id: Int) {
        val st = _state.value
        val root = st.root ?: return
        edit { e ->
            e.maskMemberRemove(root, id)
            try { e.maskDelete(id) } catch (x: EngineException) { /* still used elsewhere: it stays */ }
            e.historySeal()
        }
    }

    private suspend fun updateElement(id: Int, geometry: JsonObject) {
        edit { e -> e.maskUpdate(id, geometry); e.historySeal() }
    }

    /** The markers of element [id]'s input or output slider set: one history item per call, in order (a <= b <= c <= d). */
    suspend fun setRange(id: Int, output: Boolean, range: List<Double>) {
        if (range.size != 4) return
        var prev = 0.0
        val r = range.map { v -> v.coerceIn(prev, 1.0).also { prev = it } }
        updateElement(id, buildJsonObject { put(if (output) "out" else "in", arr(r)) })
    }

    suspend fun setInvert(id: Int, on: Boolean) {
        val root = _state.value.root ?: return
        edit { e -> e.maskMemberUpdate(root, id, MaskMember(inverse = on)); e.historySeal() }
    }

    /** The boost in EV (0..18, as the engine's `boost`), for channels that have one. */
    suspend fun setBoost(id: Int, ev: Double) = updateElement(id, buildJsonObject { put("boost", ev) })

    /** The output slider shown or hidden (the engine keeps it with the element; a set output range applies hidden). */
    suspend fun setShowOutput(id: Int, on: Boolean) = updateElement(id, buildJsonObject { put("show_output", on) })

    // ------------------------------------------------------------------ colour space

    /** A colour space chosen: asked first (it removes the parametric elements), done by [confirmSpace]. */
    fun askSpace(space: String) {
        val b = _state.value.blend ?: return
        if (space == b.colorspace) { openSheet(null); return }
        _state.update { it.copy(sheet = ParamSheet.ConfirmSpace(space), note = null) }
    }

    /** The colour space changed; elements of the old space are removed (manual: parametric masks). */
    suspend fun confirmSpace() {
        val sh = _state.value.sheet as? ParamSheet.ConfirmSpace ?: return
        _state.update { it.copy(sheet = null) }
        val k = _state.value.key ?: return
        edit { e ->
            e.blendSet(k.op, k.priority, mapOf("colorspace" to JsonPrimitive(sh.space)))
            val b = e.blendGet(k.op, k.priority)
            val root = b.maskId
            if (root != null) {
                val space = b.effectiveColorspace ?: b.colorspace ?: ""
                for (el in elementsOf(e.masks(), root)) if (el.space != space) {
                    e.maskMemberRemove(root, el.id)
                    try { e.maskDelete(el.id) } catch (x: EngineException) { /* stays */ }
                }
            }
            e.historySeal()
        }
    }

    // ------------------------------------------------------------------ refinement

    suspend fun setRefine(path: String, value: Double) = setBlend(mapOf(path to num(value)))

    suspend fun setGuide(guide: String) {
        _state.update { it.copy(sheet = null) }
        setBlend(mapOf("feathering_guide" to JsonPrimitive(guide)))
    }

    /** Back to desktop's defaults: no details, no feathering, no blur, brightness and contrast 0, guide input after blur. */
    suspend fun resetRefine() = setBlend(mapOf("details" to num(0.0), "feathering_radius" to num(0.0), "feathering_guide" to JsonPrimitive(DEFAULT_GUIDE),
        "blur_radius" to num(0.0), "brightness" to num(0.0), "contrast" to num(0.0)))

    // ------------------------------------------------------------------ picker (the shared picker tool of P3c)

    /** the picker id of an element's slider: below [PICK_BASE], by the element's id and input (0) or output (1) */
    fun pickerId(id: Int, output: Boolean): Int = PICK_BASE - (id * 2 + if (output) 1 else 0)

    /**
     * The armed picker's area picked: the element's range set from it (desktop's "set range" picker), under the
     * engine lock, as one history item. Returns whether the edit changed.
     */
    internal suspend fun pickLocked(e: DarktableEngine, key: InstanceKey, controlId: Int, area: List<Double>?, point: List<Double>?): Boolean {
        val n = PICK_BASE - controlId
        val el = element(n / 2) ?: return false
        val out = n % 2 == 1
        val pick = e.colorPick(key.op, key.priority, PickRequest(area, point))
        val stats = if (out) pick.output else pick.input
        if (stats == null) { _state.update { it.copy(note = "the picked area gives no sample for ${el.base} ${if (out) "output" else "input"}") }; return false }
        val range = PickRange.fromStats(el.space, el.base, stats, el.boost + ParamChannels.defaultBoost(el.space, el.base))
        if (range == null) { _state.update { it.copy(note = "this module's picker does not give ${el.base} (${stats.colorspace}): set the range by hand") }; return false }
        e.maskUpdate(el.id, buildJsonObject { put(if (out) "out" else "in", arr(range)) })
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

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.PickRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Brief P3m: the colour look up table's patch editor (darktable's colorchecker.c, the manual's "color look up table"
 * page). Everything it edits is a plain parameter (source_L/a/b, target_L/a/b, num_patches), written with params_set
 * as desktop's callbacks write them, one history item each. The patch chosen and the colour picked are the editor's
 * own, as desktop's gui data.
 */

const val LUT_OP = "colorchecker"

/** The patches of a colour look up table instance, as its parameters hold them (Lab). */
data class LutPatches(
    val n: Int,
    val sourceL: List<Double>, val sourceA: List<Double>, val sourceB: List<Double>,
    val targetL: List<Double>, val targetA: List<Double>, val targetB: List<Double>,
    /** the arrays' length (49) */
    val max: Int,
) {
    fun source(i: Int) = Triple(sourceL[i], sourceA[i], sourceB[i])
    fun target(i: Int) = Triple(targetL[i], targetA[i], targetB[i])

    /** desktop draws an outline when the target differs from the source by more than 1e-5 */
    fun altered(i: Int) = abs(targetL[i] - sourceL[i]) > 1e-5 || abs(targetA[i] - sourceA[i]) > 1e-5 || abs(targetB[i] - sourceB[i]) > 1e-5

    /** the grid: 6 x 4 up to 24 patches, 7 x 7 above */
    val columns: Int get() = if (n > 24) 7 else 6
    val rows: Int get() = if (n > 24) 7 else 4

    /** The sliders' values for patch [i], relative to the source as desktop's default "relative" target. */
    fun offsets(i: Int): LutOffsets {
        val cin = sqrt(sourceA[i] * sourceA[i] + sourceB[i] * sourceB[i])
        val cout = sqrt(targetA[i] * targetA[i] + targetB[i] * targetB[i])
        return LutOffsets(targetL[i] - sourceL[i], targetA[i] - sourceA[i], targetB[i] - sourceB[i], cout - cin)
    }

    /** colorchecker.c color_picker_apply: the patch whose source is nearest to [lab] (squared distance). */
    fun nearest(lab: Triple<Double, Double, Double>): Int {
        var best = 0
        fun d(i: Int) = (lab.first - sourceL[i]).let { it * it } + (lab.second - sourceA[i]).let { it * it } + (lab.third - sourceB[i]).let { it * it }
        for (i in 1 until n) if (d(i) < d(best)) best = i
        return best
    }

    companion object {
        fun of(params: JsonObject): LutPatches? {
            fun arr(k: String) = (params[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
            val sl = arr("source_L") ?: return null
            val sa = arr("source_a") ?: return null
            val sb = arr("source_b") ?: return null
            val tl = arr("target_L") ?: return null
            val ta = arr("target_a") ?: return null
            val tb = arr("target_b") ?: return null
            // gui_update: the stored count is clamped to 0..MAX_PATCHES
            val n = ((params["num_patches"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0).coerceIn(0, sl.size)
            return LutPatches(n, sl, sa, sb, tl, ta, tb, sl.size)
        }
    }
}

data class LutOffsets(val lightness: Double, val greenRed: Double, val blueYellow: Double, val saturation: Double)

data class LutUi(
    val key: InstanceKey? = null,
    /** desktop's g->patch: the patch the sliders edit */
    val patch: Int = 0,
    /** the picker is armed: a tap on the photo picks there */
    val picking: Boolean = false,
    /** the colour picked last (Lab, the module's input), for "replace" and "add" */
    val picked: Triple<Double, Double, Double>? = null,
    /** the patch whose long-press menu is open */
    val menu: Int? = null,
    val note: String? = null,
)

class Lut internal constructor(private val c: DarkroomController) {
    private val _state = MutableStateFlow(LutUi())
    val state: StateFlow<LutUi> = _state.asStateFlow()

    fun wanted(s: DarkroomState): InstanceKey? = s.expanded?.takeIf { it.op == LUT_OP && s.info != null && !s.loading }

    fun open(key: InstanceKey) { if (_state.value.key != key) _state.value = LutUi(key = key) }

    fun close() = _state.update { it.copy(key = null, picking = false, menu = null) }

    private fun patches(): LutPatches? {
        val k = _state.value.key ?: return null
        return c.state.value.modules.firstOrNull { it.key == k }?.let { LutPatches.of(it.params) }
    }

    /** A tap on a patch or the patch list (desktop's click; the patch combo box). */
    fun select(i: Int) {
        val p = patches() ?: return
        if (p.n <= 0) return
        _state.update { it.copy(patch = i.coerceIn(0, p.n - 1), note = null, menu = null) }
    }

    fun openMenu(i: Int?) = _state.update { it.copy(menu = i, note = null) }

    private suspend fun write(values: Map<String, Double>) {
        val k = _state.value.key ?: return
        if (c.state.value.readOnly) return
        try {
            c.engineChange { e ->
                e.paramsSet(k.op, k.priority, values.mapValues { (path, v) -> if (path == "num_patches") JsonPrimitive(v.toInt()) else JsonPrimitive(v) })
                e.historySeal()
            }
        } catch (x: EngineException) { _state.update { it.copy(note = x.detail) } }
    }

    /** A slider of the selected patch: "lightness", "green-red", "blue-yellow", "saturation" (desktop's target_*_callback, relative). */
    suspend fun setOffset(which: String, v: Double) {
        val p = patches() ?: return
        val i = _state.value.patch.takeIf { it in 0 until p.n } ?: return
        val values = LinkedHashMap<String, Double>()
        when (which) {
            "lightness" -> values["target_L[$i]"] = p.sourceL[i] + v
            "green-red" -> values["target_a[$i]"] = (p.sourceA[i] + v).coerceIn(-128.0, 128.0)
            "blue-yellow" -> values["target_b[$i]"] = (p.sourceB[i] + v).coerceIn(-128.0, 128.0)
            "saturation" -> {
                val cin = sqrt(p.sourceA[i] * p.sourceA[i] + p.sourceB[i] * p.sourceB[i])
                val cout = max(1e-4, sqrt(p.targetA[i] * p.targetA[i] + p.targetB[i] * p.targetB[i]))
                val cnew = (cin + v).coerceIn(0.01, 128.0)
                values["target_a[$i]"] = (p.targetA[i] * cnew / cout).coerceIn(-128.0, 128.0)
                values["target_b[$i]"] = (p.targetB[i] * cnew / cout).coerceIn(-128.0, 128.0)
            }
            else -> return
        }
        write(values)
    }

    /** Desktop's double-click: the patch's target back to its source. */
    suspend fun reset(i: Int) {
        val p = patches() ?: return
        if (i !in 0 until p.n) return
        _state.update { it.copy(menu = null) }
        write(mapOf("target_L[$i]" to p.sourceL[i], "target_a[$i]" to p.sourceA[i], "target_b[$i]" to p.sourceB[i]))
    }

    /** Desktop's right-click: the patch deleted, the ones after it move up one place. */
    suspend fun delete(i: Int) {
        val p = patches() ?: return
        if (i !in 0 until p.n) return
        _state.update { it.copy(menu = null, patch = it.patch.coerceAtMost(max(0, p.n - 2))) }
        write(deleteValues(p, i))
    }

    /** Desktop's shift+click on a patch while picking: its source (and target) becomes the picked colour. */
    suspend fun replaceSource(i: Int) {
        val p = patches() ?: return
        val lab = _state.value.picked ?: run { _state.update { it.copy(note = "pick a colour on the photo first", menu = null) }; return }
        _state.update { it.copy(menu = null) }
        if (i !in 0 until p.n || !validNew(p, lab)) { _state.update { it.copy(note = "the picked colour is already a patch or too dark to use") }; return }
        _state.update { it.copy(patch = i) }
        write(patchValues(i, lab))
    }

    /** Desktop's shift+click on empty space while picking: a new patch from the picked colour (at most 49). */
    suspend fun addPicked() {
        val p = patches() ?: return
        val lab = _state.value.picked ?: run { _state.update { it.copy(note = "pick a colour on the photo first") }; return }
        if (p.n >= p.max) { _state.update { it.copy(note = "the table holds ${p.max} patches already") }; return }
        if (!validNew(p, lab)) { _state.update { it.copy(note = "the picked colour is already a patch or too dark to use") }; return }
        val i = p.n
        _state.update { it.copy(patch = i) }
        write(patchValues(i, lab) + ("num_patches" to (p.n + 1).toDouble()))
    }

    /** The picker button: armed, a tap on the photo picks (desktop's module picker, point). */
    fun togglePicking() {
        if (c.state.value.readOnly) return
        c.disarmPicker()
        _state.update { it.copy(picking = !it.picking, note = null) }
    }

    /** A tap on the photo with the picker armed: the module's input colour there, and the nearest patch selected. */
    fun tap(x: Double, y: Double): Boolean {
        val u = _state.value
        val k = u.key ?: return false
        if (!u.picking) return false
        c.launch {
            val mean = try {
                engineRead { e -> e.colorPick(k.op, k.priority, PickRequest(point = listOf(x, y))).input?.mean }
            } catch (e: EngineException) { _state.update { it.copy(note = e.detail) }; null }
            val v = mean?.take(3)?.map { it ?: 0.0 }?.takeIf { it.size == 3 } ?: return@launch
            val lab = Triple(v[0], v[1], v[2])
            val p = patches()
            _state.update { it.copy(picked = lab, patch = p?.takeIf { q -> q.n > 0 }?.nearest(lab) ?: it.patch) }
        }
        return true
    }

    companion object {
        fun patchValues(i: Int, lab: Triple<Double, Double, Double>) = mapOf(
            "source_L[$i]" to lab.first, "source_a[$i]" to lab.second, "source_b[$i]" to lab.third,
            "target_L[$i]" to lab.first, "target_a[$i]" to lab.second, "target_b[$i]" to lab.third,
        )

        /** colorchecker.c checker_button_press: the colour must be away from zero in every channel and not a patch already. */
        fun validNew(p: LutPatches, lab: Triple<Double, Double, Double>): Boolean {
            if (abs(lab.first) <= 1e-3 || abs(lab.second) <= 1e-3 || abs(lab.third) <= 1e-3) return false
            for (i in 0 until p.n) if (abs(lab.first - p.sourceL[i]) < 1e-3 && abs(lab.second - p.sourceA[i]) < 1e-3 && abs(lab.third - p.sourceB[i]) < 1e-3) return false
            return true
        }

        /** The memmove of desktop's right-click as parameter values: patches after [i] one place up, one patch fewer. */
        fun deleteValues(p: LutPatches, i: Int): Map<String, Double> {
            val v = LinkedHashMap<String, Double>()
            for (k in i until p.n - 1) {
                v["source_L[$k]"] = p.sourceL[k + 1]; v["source_a[$k]"] = p.sourceA[k + 1]; v["source_b[$k]"] = p.sourceB[k + 1]
                v["target_L[$k]"] = p.targetL[k + 1]; v["target_a[$k]"] = p.targetA[k + 1]; v["target_b[$k]"] = p.targetB[k + 1]
            }
            v["num_patches"] = (p.n - 1).toDouble()
            return v
        }
    }
}

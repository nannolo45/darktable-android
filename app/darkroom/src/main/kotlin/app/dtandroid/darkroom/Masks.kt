// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.CoordsDirection
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.MaskMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/*
 * Brief P3d (stage 1): masks and blending over the engine's calls (engine-api.md 6.5 to 6.8). The masks tab edits the
 * mask of one module instance: its mode, opacity, blend mode, its drawn shapes, and lists every shape of the photo
 * (desktop's mask manager). Shapes are drawn on the photo in fractions of the picture shown (the processed image)
 * and handed to the engine in original-image coordinates through coords_transform.
 */

/** The mask modes of desktop's blending section, as the masks tab offers them. */
enum class MaskMode(val label: String, val engineName: String) {
    OFF("off", "off"),
    UNIFORM("uniform", "uniform"),
    DRAWN("drawn", "drawn"),
    PARAMETRIC("parametric", "parametric"),
    BOTH("drawn & parametric", "drawn_parametric");

    /** stage 1 builds off, uniform and drawn; the parametric modes come with stage 2 */
    val available: Boolean get() = this == OFF || this == UNIFORM || this == DRAWN

    companion object {
        /**
         * blend_get's "masks" as a mode of the tab. "flexi" is what an instance's mask becomes once a shape is attached
         * (engine-api.md 6.7): with only drawn shapes (stage 1 creates nothing else) it is the drawn mode.
         */
        fun of(engine: String?): MaskMode? = when (engine) {
            "off" -> OFF
            "uniform" -> UNIFORM
            "drawn", "flexi" -> DRAWN
            "parametric" -> PARAMETRIC
            "drawn_parametric" -> BOTH
            else -> null
        }
    }
}

/** The shapes the tab draws, in the order of its buttons. */
enum class ShapeType(val key: String) {
    BRUSH("brush"), CIRCLE("circle"), ELLIPSE("ellipse"), GRADIENT("gradient"), PATH("path");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** One form of the photo (engine-api.md 6.7), as the manager lists it. */
data class MaskShape(
    val id: Int,
    val type: String,
    val name: String,
    val geometry: JsonObject,
    /** instances whose mask holds it (through any group) */
    val users: List<InstanceKey>,
    /** the instance whose whole mask this group is, if any */
    val rootOf: InstanceKey?,
    /** a group's members in order */
    val members: List<MaskMemberState>,
) {
    val isGroup: Boolean get() = type == "group"
}

/** A member of a group: its own inversion, opacity and switch. */
data class MaskMemberState(val id: Int, val inverse: Boolean, val opacity: Double, val disabled: Boolean)

/** The blending of the instance the tab edits. */
data class ModuleMask(
    val key: InstanceKey,
    /** the module has blending (desktop shows the blending section) */
    val supported: Boolean,
    val engineMode: String?,
    val opacity: Double,
    val blendMode: String,
    val colorspace: String,
    /** desktop's "invert mask" (blend_get combine.invert) */
    val invert: Boolean,
    /** the instance's mask group, null = none yet */
    val rootId: Int?,
    /** the group's operator ("maximum", ...), null without a group */
    val operator: String?,
    val members: List<MaskMemberState>,
) {
    val mode: MaskMode? get() = MaskMode.of(engineMode)
}

/** A finished finger stroke of the brush, with the settings it was painted with. */
data class Stroke(val points: List<Pair<Double, Double>>, val size: Double, val hardness: Double, val opacity: Double)

/**
 * Drawing mode: one new shape for [key]. Positions are fractions of the picture shown (processed image); sizes
 * ([size], [feather]) fractions of its shorter side; [hardness], [opacity], [flatness], [compression] 0..1;
 * [rotation] in degrees.
 */
data class DrawTool(
    val type: ShapeType,
    val key: InstanceKey,
    val size: Double,
    val feather: Double,
    val hardness: Double,
    val opacity: Double,
    val rotation: Double,
    val flatness: Double,
    val compression: Double,
    /** circle, ellipse, gradient: where it is placed (null until the first tap) */
    val center: Pair<Double, Double>? = null,
    /** where it was before the finger now on the photo moved it (a pinch puts it back) */
    val before: Pair<Double, Double>? = null,
    /** path: its points; [pending] is where the finger on the photo will add the next one */
    val points: List<Pair<Double, Double>> = emptyList(),
    val pending: Pair<Double, Double>? = null,
    val closed: Boolean = false,
    val strokes: List<Stroke> = emptyList(),
    /** brush: the stroke the finger is painting now */
    val current: List<Pair<Double, Double>>? = null,
    /** the brush's strokes filled (on) or as thin lines (off) */
    val showMask: Boolean = true,
) {
    val canFinish: Boolean
        get() = when (type) {
            ShapeType.BRUSH -> strokes.isNotEmpty()
            ShapeType.PATH -> points.size >= 3
            else -> center != null
        }

    companion object {
        /**
         * Desktop's starting sizes for new shapes as far as the engine API names them (engine-api.md 6.7 takes
         * desktop's remembered values for missing sizes; the tool always gives its own so the drawing and the
         * result agree).
         */
        fun start(type: ShapeType, key: InstanceKey) = DrawTool(
            type, key, size = 0.05, feather = 0.05, hardness = 0.66, opacity = 1.0, rotation = 0.0,
            flatness = 0.7, compression = 0.5,
        )
    }
}

/** A sheet of the masks tab. */
sealed interface MaskSheet {
    /** the photo's module instances, to choose whose mask the tab edits */
    data object Modules : MaskSheet
    data object BlendModes : MaskSheet
    data object Combine : MaskSheet
    /** "use an existing shape…" */
    data object Existing : MaskSheet
    /** a shape's "more" sheet; [from] the instance whose group the row was in; [group] the group of the tree row */
    data class More(val id: Int, val from: InstanceKey?, val group: Int? = null) : MaskSheet
    /** P3d 2a: a group's operator (engine-api.md 6.9) */
    data class Operator(val group: Int) : MaskSheet
    /** P3d 2a: "group together…": [first] and the other members of [group] to choose */
    data class Together(val group: Int, val first: Int) : MaskSheet
    data class Rename(val id: Int) : MaskSheet
    data class UseIn(val id: Int) : MaskSheet
    data class Delete(val id: Int) : MaskSheet
}

/**
 * P3d 2a: one row of a module's mask tree, top first (desktop lists rows bottom-up: the bottom member of a group is
 * its first). [index] is the member's place in its [group] (0 = bottom) of [count]; [depth] 0 for the module's own
 * group's members.
 */
data class MaskTreeRow(val group: Int, val member: MaskMemberState, val form: MaskShape?, val depth: Int, val index: Int, val count: Int) {
    val isGroup: Boolean get() = form?.isGroup == true
}

/** A shape's outline on the photo, in fractions of the picture shown. */
data class ShapeOutline(val points: List<Pair<Double, Double>>, val closed: Boolean)

data class MasksState(
    /** the instance whose mask the tab edits */
    val key: InstanceKey? = null,
    val module: ModuleMask? = null,
    val forms: List<MaskShape> = emptyList(),
    /** the shape tapped in the list: its outline is shown on the photo */
    val selected: Int? = null,
    val outline: ShapeOutline? = null,
    /** folded groups of the manager */
    val folded: Set<Int> = emptySet(),
    /** P3d 2a: nested groups of the module's tree that are open (collapsed by default) */
    val expanded: Set<Int> = emptySet(),
    val draw: DrawTool? = null,
    val sheet: MaskSheet? = null,
    /** a sentence for the user about the last action that could not be done */
    val note: String? = null,
)

class Masks internal constructor(private val c: DarkroomController) {
    private val _state = MutableStateFlow(MasksState())
    val state: StateFlow<MasksState> = _state.asStateFlow()

    private val readOnly get() = c.state.value.readOnly

    /** Another photo: nothing chosen, drawn or open. */
    fun reset() { _state.value = MasksState() }

    // ------------------------------------------------------------------ reading

    /** Choose the instance whose mask the tab edits. */
    suspend fun select(key: InstanceKey) {
        if (c.state.value.showMask != null && c.state.value.showMask != key) c.setShowMask(null)
        _state.update { it.copy(key = key, sheet = null, note = null) }
        reload()
    }

    /** Read the module's blending and the photo's shapes again. */
    suspend fun reload() {
        if (c.state.value.info == null) return
        try { c.engineRead { reloadLocked(it) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(note = noteOf(e)) } }
    }

    /** After every change of the edit (under the engine lock): only while the tab or a drawing is open. */
    internal suspend fun afterChangeLocked(e: DarktableEngine) {
        if (c.state.value.view != BottomView.MASKS && _state.value.draw == null) return
        try { reloadLocked(e) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { _state.update { it.copy(note = noteOf(x)) } }
    }

    private fun noteOf(x: Exception): String = (x as? EngineException)?.detail ?: x.message ?: x.javaClass.simpleName

    private fun defaultKey(): InstanceKey? {
        val s = c.state.value
        s.expanded?.let { k -> if (s.modules.any { it.key == k && it.supportsBlending }) return k }
        return s.modules.firstOrNull { s.maskModes[it.key]?.let { m -> m != "off" } == true }?.key
    }

    private suspend fun reloadLocked(e: DarktableEngine) {
        val s = c.state.value
        if (s.info == null) return
        val asked = _state.value.key
        val key = (asked ?: defaultKey())?.takeIf { k -> s.modules.any { it.key == k } }
        val raw = e.masks()
        val forms = raw.map { f ->
            val members = (f.geometry["members"] as? JsonArray)?.mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                MaskMemberState(id, o.bool("inverse"), (o["opacity"] as? JsonPrimitive)?.doubleOrNull ?: 1.0, o.bool("disabled"))
            } ?: emptyList()
            MaskShape(f.id, f.type, f.name, f.geometry, f.usedBy.map { InstanceKey(it.op, it.priority) }.distinct(),
                f.usedBy.firstOrNull { it.root }?.let { InstanceKey(it.op, it.priority) }, members)
        }
        val module = key?.let { k ->
            val inst = s.modules.first { it.key == k }
            if (!inst.supportsBlending) ModuleMask(k, false, null, 100.0, "normal", "", false, null, null, emptyList())
            else {
                val b = e.blendGet(k.op, k.priority)
                val root = b.maskId?.let { id -> forms.firstOrNull { it.id == id } }
                ModuleMask(k, b.supported, modeName(b), b.opacity ?: 100.0, b.mode ?: "normal", b.effectiveColorspace ?: b.colorspace ?: "",
                    b.combineInvert, root?.id, root?.geometry?.str("operator"), root?.members ?: emptyList())
            }
        }
        val selected = _state.value.selected?.takeIf { id -> forms.any { it.id == id } }
        val outline = selected?.let { id -> outlineLocked(e, forms.first { it.id == id }) }
        // a module chosen meanwhile (its own reload follows) is not overwritten by this older answer
        _state.update { if (it.key != asked) it else it.copy(key = key, module = module, forms = forms, selected = selected, outline = outline) }
    }

    /** The outline of [f] in fractions of the picture shown (null for a group or a form without a drawing). */
    private suspend fun outlineLocked(e: DarktableEngine, f: MaskShape): ShapeOutline? {
        val info = c.state.value.info ?: return null
        // the original image's shape: lengths of masks are fractions of its shorter side
        val probe = e.coordsTransform(CoordsDirection.TO_PROCESSED, listOf(0.5 to 0.5))
        val ow = probe.originalWidth.takeIf { it > 0 } ?: info.width
        val oh = probe.originalHeight.takeIf { it > 0 } ?: info.height
        val m = min(ow, oh).toDouble()
        val g = f.geometry
        val (pts, closed) = when (f.type) {
            "circle" -> {
                val cpt = g.pair("center") ?: return null
                val r = g.num("radius") ?: return null
                ring(cpt, r * m / ow, r * m / oh, 0.0) to true
            }
            "ellipse" -> {
                val cpt = g.pair("center") ?: return null
                val r = (g["radius"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
                if (r.size < 2) return null
                ringRotated(cpt, r[0] * m, r[1] * m, g.num("rotation") ?: 0.0, ow, oh) to true
            }
            "gradient" -> {
                val a = g.pair("anchor") ?: return null
                val rot = Math.toRadians(g.num("rotation") ?: 0.0)
                val l = 2.0
                listOf(a.first - cos(rot) * l * m / ow to a.second - sin(rot) * l * m / oh, a.first + cos(rot) * l * m / ow to a.second + sin(rot) * l * m / oh) to false
            }
            "path", "brush" -> {
                val corners = (g["points"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.pair("corner") } ?: return null
                corners to (f.type == "path")
            }
            else -> return null
        }
        if (pts.isEmpty()) return null
        val out = e.coordsTransform(CoordsDirection.TO_PROCESSED, pts).points.filterNotNull()
        return ShapeOutline(out, closed)
    }

    private fun ring(cpt: Pair<Double, Double>, rx: Double, ry: Double, @Suppress("SameParameterValue") start: Double) =
        (0 until RING).map { i -> val t = start + 2 * Math.PI * i / RING; cpt.first + rx * cos(t) to cpt.second + ry * sin(t) }

    private fun ringRotated(cpt: Pair<Double, Double>, a: Double, b: Double, deg: Double, ow: Int, oh: Int): List<Pair<Double, Double>> {
        val r = Math.toRadians(deg)
        return (0 until RING).map { i ->
            val t = 2 * Math.PI * i / RING
            val x = a * cos(t)
            val y = b * sin(t)
            cpt.first + (x * cos(r) - y * sin(r)) / ow to cpt.second + (x * sin(r) + y * cos(r)) / oh
        }
    }

    // ------------------------------------------------------------------ the tab's sheets and list

    fun openSheet(sheet: MaskSheet?) = _state.update { it.copy(sheet = sheet, note = null) }

    fun toggleFold(groupId: Int) = _state.update { it.copy(folded = if (groupId in it.folded) it.folded - groupId else it.folded + groupId) }

    /** P3d 2a: open or close a nested group of the module's tree. */
    fun toggleExpanded(groupId: Int) = _state.update { it.copy(expanded = if (groupId in it.expanded) it.expanded - groupId else it.expanded + groupId) }

    /** Tap on a shape in the list: its outline on the photo (tap again: hidden). */
    suspend fun selectShape(id: Int?) {
        val next = if (id == _state.value.selected) null else id
        _state.update { it.copy(selected = next, outline = null) }
        if (next == null) return
        try {
            c.engineRead { e -> _state.value.forms.firstOrNull { it.id == next }?.let { f -> outlineLocked(e, f) } }
                ?.let { o -> _state.update { if (it.selected == next) it.copy(outline = o) else it } }
        } catch (e: EngineException) { _state.update { it.copy(note = e.detail) } }
    }

    /** The modules a shape can be used in: every instance with blending, in the list's order. */
    fun blendingModules(): List<app.dtandroid.engine.api.ModuleInstance> = c.state.value.modules.filter { it.supportsBlending }

    /** Desktop's blend modes for the module's blend colour space (blend_set refuses the others). */
    fun blendModes(colorspace: String): List<String> = when (colorspace) {
        "lab" -> DISPLAY_MODES + listOf("lab_lightness", "lab_a", "lab_b", "lab_color", "hue", "color", "coloradjustment", "lightness", "chromaticity")
        "rgb_display" -> DISPLAY_MODES + listOf("rgb_red", "rgb_green", "rgb_blue", "hsv_value", "hsv_color", "hue", "color", "coloradjustment", "lightness", "chromaticity")
        "raw" -> listOf("normal")
        else -> SCENE_MODES
    }

    // ------------------------------------------------------------------ changes (each one history item)

    private suspend fun edit(block: suspend (DarktableEngine) -> Unit) {
        if (readOnly) return
        _state.update { it.copy(note = null) }
        // brief P3d fix: whatever a masks action throws becomes the tab's note, never the end of the app
        try { c.engineChange { e -> block(e) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(note = noteOf(e)) } }
    }

    private fun current(): ModuleMask? = _state.value.module?.takeIf { it.supported }

    suspend fun setMode(mode: MaskMode) {
        val m = current() ?: return
        if (!mode.available || m.mode == mode) return
        if (mode != MaskMode.DRAWN) c.setShowMask(null)
        edit { e -> e.blendSet(m.key.op, m.key.priority, mapOf("masks" to JsonPrimitive(mode.engineName))); e.historySeal() }
    }

    suspend fun setOpacity(value: Double) {
        val m = current() ?: return
        edit { e -> e.blendSet(m.key.op, m.key.priority, mapOf("opacity" to JsonPrimitive(value.coerceIn(0.0, 100.0)))); e.historySeal() }
    }

    suspend fun setBlendMode(name: String) {
        val m = current() ?: return
        _state.update { it.copy(sheet = null) }
        if (name == m.blendMode) return
        edit { e -> e.blendSet(m.key.op, m.key.priority, mapOf("mode" to JsonPrimitive(name))); e.historySeal() }
    }

    suspend fun setInvert(on: Boolean) {
        val m = current() ?: return
        edit { e -> e.blendSet(m.key.op, m.key.priority, mapOf("combine.invert" to JsonPrimitive(on))); e.historySeal() }
    }

    /** How the module's shapes combine: the operator of its mask group (engine-api.md 6.7). */
    suspend fun setCombine(label: String) {
        val m = current() ?: return
        _state.update { it.copy(sheet = null) }
        val root = m.rootId ?: return
        val op = COMBINE.firstOrNull { it.first == label }?.second ?: return
        if (op == m.operator) return
        edit { e -> e.maskGroupSet(root, buildJsonObject { put("operator", op) }); e.historySeal() }
    }

    suspend fun setMemberInvert(id: Int, on: Boolean) {
        val m = current() ?: return
        edit { e -> e.maskMemberSet(m.key.op, m.key.priority, id, MaskMember(inverse = on)); e.historySeal() }
    }

    /** "remove": the shape leaves the module's mask (it stays in the photo). */
    suspend fun detach(id: Int, from: InstanceKey? = null) {
        val k = from ?: current()?.key ?: return
        _state.update { it.copy(sheet = null) }
        edit { e -> e.maskDetach(k.op, k.priority, id); e.historySeal() }
    }

    /** "use an existing shape…" and "use in another module…". */
    suspend fun attach(id: Int, to: InstanceKey? = null) {
        val k = to ?: current()?.key ?: return
        _state.update { it.copy(sheet = null) }
        // brief P3d fix: a group never goes into itself or into a group that holds it (a cycle)
        val rootOfTarget = _state.value.forms.firstOrNull { it.rootOf == k }?.id
        if (rootOfTarget != null && makesCycle(_state.value.forms, id, rootOfTarget)) {
            _state.update { it.copy(note = "a group cannot be used inside itself") }
            return
        }
        edit { e -> e.maskAttach(k.op, k.priority, id); e.historySeal() }
    }

    suspend fun rename(id: Int, name: String) {
        _state.update { it.copy(sheet = null) }
        if (name.isBlank()) return
        edit { e -> e.maskRename(id, name.trim()); e.historySeal() }
    }

    /** A copy of the shape (darktable's automatic name), in the module whose group the row was in. */
    suspend fun duplicate(id: Int, from: InstanceKey?) {
        val f = _state.value.forms.firstOrNull { it.id == id && !it.isGroup } ?: return
        _state.update { it.copy(sheet = null) }
        var made: Int? = null
        edit { e ->
            val n = e.maskCreate(f.type, f.geometry)
            made = n
            if (from != null) e.maskAttach(from.op, from.priority, n)
            e.historySeal()
        }
        made?.let { selectShape(it) }
    }

    /** "delete shape": from the photo and every group (the question is asked by the sheet first). */
    suspend fun delete(id: Int) {
        _state.update { it.copy(sheet = null, selected = if (it.selected == id) null else it.selected) }
        edit { e -> e.maskDelete(id); e.historySeal() }
    }

    // ------------------------------------------------------------------ P3d 2a: the mask tree (engine-api.md 6.9)

    /** A group's operator ("maximum", "minimum", ...). */
    suspend fun setGroupOperator(group: Int, op: String) {
        _state.update { it.copy(sheet = null) }
        if (OPERATORS.none { it.second == op }) return
        if (_state.value.forms.firstOrNull { it.id == group }?.geometry?.str("operator") == op) return
        edit { e -> e.maskGroupSet(group, buildJsonObject { put("operator", op) }); e.historySeal() }
    }

    /** A group's own invert (after its operator). */
    suspend fun setGroupInvert(group: Int, on: Boolean) =
        edit { e -> e.maskGroupSet(group, buildJsonObject { put("invert", on) }); e.historySeal() }

    /** A group's own opacity, 0..1. */
    suspend fun setGroupOpacity(group: Int, value: Double) =
        edit { e -> e.maskGroupSet(group, buildJsonObject { put("opacity", value.coerceIn(0.0, 1.0)) }); e.historySeal() }

    /** A member's invert in any group (a member that is a group: the group's own invert). */
    suspend fun setMemberInvertIn(group: Int, id: Int, on: Boolean) {
        if (_state.value.forms.firstOrNull { it.id == id }?.isGroup == true) return setGroupInvert(id, on)
        edit { e -> e.maskMemberUpdate(group, id, MaskMember(inverse = on)); e.historySeal() }
    }

    /** A member's opacity, 0..1, in any group (a member that is a group: the group's own opacity). */
    suspend fun setMemberOpacityIn(group: Int, id: Int, value: Double) {
        if (_state.value.forms.firstOrNull { it.id == id }?.isGroup == true) return setGroupOpacity(id, value)
        edit { e -> e.maskMemberUpdate(group, id, MaskMember(opacity = value.coerceIn(0.0, 1.0))); e.historySeal() }
    }

    /** "move up" (towards the top of the group) or "move down"; nothing at either end. */
    suspend fun moveMember(group: Int, id: Int, up: Boolean) {
        val members = _state.value.forms.firstOrNull { it.id == group }?.members ?: return
        val i = members.indexOfFirst { it.id == id }
        if (i < 0) return
        val to = if (up) i + 1 else i - 1
        if (to < 0 || to >= members.size) return
        edit { e -> e.maskMemberMove(group, id, group, to); e.historySeal() }
    }

    /** "remove from this group", for any group (the shape stays in the photo). */
    suspend fun removeFrom(group: Int, id: Int) {
        _state.update { it.copy(sheet = null) }
        edit { e -> e.maskMemberRemove(group, id); e.historySeal() }
    }

    /** "wrap in group": the member goes into a new group at its place; the new group opens. */
    suspend fun wrapInGroup(group: Int, id: Int) = groupTogether(group, listOf(id))

    /** "group together…": the members (in their order) go into a new group where the topmost of them was. */
    suspend fun groupTogether(group: Int, ids: List<Int>) {
        _state.update { it.copy(sheet = null) }
        if (ids.isEmpty()) return
        var made: Int? = null
        edit { e -> made = e.maskGroupWrap(group, ids); e.historySeal() }
        made?.let { g -> _state.update { it.copy(expanded = it.expanded + g) } }
    }

    /** "show mask": the engine's mask display of the module (only for a drawn mask of a module that is on). */
    fun toggleShowMask() {
        val m = current() ?: return
        if (c.state.value.showMask == m.key) { c.setShowMask(null); return }
        val on = c.state.value.modules.firstOrNull { it.key == m.key }?.enabled == true
        if (!on || !displayable(m.engineMode)) {
            _state.update { it.copy(note = "show mask: the module must be on and have a drawn mask") }
            return
        }
        c.setShowMask(m.key)
    }

    // ------------------------------------------------------------------ drawing mode

    /** Start drawing a new [type] for the module the tab edits. */
    fun startDraw(type: ShapeType) {
        val m = current() ?: return
        if (readOnly) return
        c.disarmPicker()
        // the crop tool shows the uncropped picture: shapes are drawn on the picture as it is
        c.state.value.cropTool?.let { c.state.value.expanded?.let { k -> c.toggleExpanded(k) } }
        c.setShowMask(null)
        _state.update { it.copy(draw = DrawTool.start(type, m.key), sheet = null, note = null, selected = null, outline = null) }
    }

    fun cancelDraw() = _state.update { it.copy(draw = null) }

    /** A slider of the tool panel: "size", "feather", "hardness", "opacity", "rotation", "flatness", "compression". */
    fun setDrawValue(name: String, v: Double) = updateDraw {
        when (name) {
            "size" -> it.copy(size = v)
            "feather" -> it.copy(feather = v)
            "hardness" -> it.copy(hardness = v)
            "opacity" -> it.copy(opacity = v)
            "rotation" -> it.copy(rotation = v)
            "flatness" -> it.copy(flatness = v)
            "compression" -> it.copy(compression = v)
            else -> it
        }
    }

    fun toggleDrawShow() = updateDraw { it.copy(showMask = !it.showMask) }
    fun undoStroke() = updateDraw { it.copy(strokes = it.strokes.dropLast(1)) }
    fun removeLastPoint() = updateDraw { it.copy(points = it.points.dropLast(1), closed = false) }
    fun closePath() = updateDraw { if (it.points.size >= 3) it.copy(closed = true) else it }

    private inline fun updateDraw(f: (DrawTool) -> DrawTool) = _state.update { s -> s.draw?.let { s.copy(draw = f(it)) } ?: s }

    /** One finger down on the photo at ([x], [y]), fractions of the picture shown. */
    fun touchDown(x: Double, y: Double) = updateDraw { d ->
        when (d.type) {
            ShapeType.BRUSH -> d.copy(current = listOf(x to y))
            ShapeType.PATH -> if (d.closed) d else d.copy(pending = x to y)
            else -> d.copy(before = d.center, center = x to y)
        }
    }

    fun touchMove(x: Double, y: Double) = updateDraw { d ->
        when (d.type) {
            ShapeType.BRUSH -> d.current?.let { d.copy(current = it + (x to y)) } ?: d
            ShapeType.PATH -> if (d.pending != null) d.copy(pending = x to y) else d
            else -> d.copy(center = x to y)
        }
    }

    /** The finger lifted: a stroke ends, a path point is added where it was. */
    fun touchUp() = updateDraw { d ->
        when (d.type) {
            ShapeType.BRUSH -> d.current?.let { d.copy(strokes = d.strokes + Stroke(it, d.size, d.hardness, d.opacity), current = null) } ?: d
            ShapeType.PATH -> d.pending?.let { d.copy(points = d.points + it, pending = null) } ?: d
            else -> d.copy(before = null)
        }
    }

    /** A second finger came: the touch was a pinch, whatever it began goes. */
    fun touchCancel() = updateDraw { d ->
        when (d.type) {
            ShapeType.BRUSH -> d.copy(current = null)
            ShapeType.PATH -> d.copy(pending = null)
            else -> d.copy(center = d.before, before = null)
        }
    }

    /**
     * "done": the shape (a brush: one form per stroke, as desktop's brush tool makes one per stroke) goes to the
     * engine in original-image coordinates and joins the module's mask.
     */
    suspend fun finishDraw() {
        val d = _state.value.draw ?: return
        if (!d.canFinish || readOnly) return
        var last: Int? = null
        try {
            c.engineChange { e ->
                val forms = geometries(e, d)
                // P3d 2a: each shape is one call and one history item (dtb_mask_add); a brush session's strokes are
                // one history group, so "done" is one item and one undo step; a failure leaves nothing behind
                e.historyGroupBegin()
                var kept = false
                try {
                    for (g in forms) last = e.maskAdd(d.key.op, d.key.priority, d.type.key, g)
                    kept = true
                } finally {
                    e.historyGroupEnd(kept)
                    if (!kept) last = null
                }
                e.historySeal()
            }
        } catch (x: EngineException) {
            _state.update { it.copy(note = x.detail) }
        }
        if (last == null) {
            // the error is in the darkroom's message line; the drawing stays so nothing is lost
            if (c.state.value.message == null) _state.update { it.copy(note = "the shape could not be added") }
            return
        }
        _state.update { it.copy(draw = null, key = d.key) }
        c.setView(BottomView.MASKS)
        selectShape(last)
    }

    /** The tool's shape as engine geometry (engine-api.md 6.7), mapped to the original image. */
    private suspend fun geometries(e: DarktableEngine, d: DrawTool): List<JsonObject> {
        // one call for every point of the shape, plus a probe a unit to the right of the first for the length scale
        val own: List<Pair<Double, Double>> = when (d.type) {
            ShapeType.BRUSH -> d.strokes.flatMap { it.points }
            ShapeType.PATH -> d.points
            else -> listOf(d.center!!)
        }
        val first = own.first()
        val res = e.coordsTransform(CoordsDirection.TO_ORIGINAL, own + listOf(first.first + PROBE to first.second))
        val pw = res.processedWidth.toDouble()
        val ph = res.processedHeight.toDouble()
        val ow = res.originalWidth.toDouble()
        val oh = res.originalHeight.toDouble()
        val pts = res.points
        if (pts.any { it == null } || pw <= 0 || ow <= 0) throw EngineException(EngineError.ARG, "a point of the shape lies outside the picture")
        val o0 = pts[0]!!
        val o1 = pts.last()!!
        // processed length -> original length, both as fractions of the shorter side
        val k = (hypot((o1.first - o0.first) * ow, (o1.second - o0.second) * oh) / (PROBE * pw)) * min(pw, ph) / min(ow, oh)
        fun xy(p: Pair<Double, Double>) = JsonArray(listOf(JsonPrimitive(p.first), JsonPrimitive(p.second)))
        return when (d.type) {
            ShapeType.CIRCLE -> listOf(buildJsonObject {
                put("center", xy(o0))
                put("radius", (d.size * k).coerceIn(0.0005, 1.0))
                put("border", (d.feather * k).coerceIn(0.0005, 1.0))
            })
            ShapeType.ELLIPSE -> listOf(buildJsonObject {
                val a = (d.size * k).coerceIn(0.001, 1.0)
                put("center", xy(o0))
                put("radius", JsonArray(listOf(JsonPrimitive(a), JsonPrimitive((a * d.flatness).coerceIn(0.001, 1.0)))))
                put("rotation", d.rotation)
                put("border", (d.feather * k).coerceIn(0.001, 1.0))
            })
            ShapeType.GRADIENT -> listOf(buildJsonObject {
                put("anchor", xy(o0))
                put("rotation", d.rotation)
                put("compression", d.compression.coerceIn(0.001, 1.0))
            })
            ShapeType.PATH -> listOf(buildJsonObject {
                put("points", JsonArray(pts.take(d.points.size).map { p -> buildJsonObject { put("corner", xy(p!!)) } }))
            })
            ShapeType.BRUSH -> {
                var i = 0
                d.strokes.map { s ->
                    val mine = pts.subList(i, i + s.points.size).map { it!! }
                    i += s.points.size
                    // a tap is a dot: the engine's brush needs two samples
                    val samples = if (mine.size >= 2) mine else listOf(mine[0], mine[0].first + DOT to mine[0].second)
                    buildJsonObject {
                        put("stroke", JsonArray(samples.map { xy(it) }))
                        put("size", (s.size * k).coerceIn(0.00005, 0.5))
                        put("hardness", s.hardness.coerceIn(0.0005, 1.0))
                        put("density", s.opacity.coerceIn(0.0, 1.0))
                    }
                }
            }
        }
    }

    companion object {
        private const val RING = 48
        private const val PROBE = 0.01
        private const val DOT = 0.0005

        /** the tab's combine modes and the group operators they set (engine-api.md 6.7) */
        val COMBINE = listOf("union" to "maximum", "intersection" to "minimum", "difference" to "difference", "exclusion" to "exclusion")

        fun combineLabel(operator: String?): String = COMBINE.firstOrNull { it.second == operator }?.first ?: (operator ?: "union")

        /**
         * blend_get's mode, with the combinations it names "other" read from its flags: a shape attached to an instance
         * in desktop's drawn mode adds FLEXI to DRAWN (dtb_mask_attach does as desktop's _blendop_mask_enable), which
         * is still the drawn mode for the tab; with PARAMETRIC too it is drawn & parametric.
         */
        fun modeName(b: app.dtandroid.engine.api.Blend): String? {
            val m = b.masks
            if (m != "other") return m
            val flags = (b.json["mask_mode_flags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: return m
            val drawn = "drawn" in flags || "flexi" in flags
            return when {
                drawn && "parametric" in flags -> "drawn_parametric"
                drawn -> "flexi"
                "raster" in flags -> "raster"
                else -> m
            }
        }

        /** whether the engine can show the mask of an instance in this mode (engine-api.md 6.8: not off, not uniform) */
        fun displayable(engineMode: String?): Boolean = engineMode != null && engineMode != "off" && engineMode != "uniform"

        /** whether the header shows the mask indicator for this mode */
        fun indicated(engineMode: String?): Boolean = engineMode != null && engineMode != "off"

        private val DISPLAY_MODES = listOf("normal", "average", "difference", "normal_bounded", "lighten", "addition", "screen", "darken",
            "subtract", "multiply", "overlay", "softlight", "hardlight", "vividlight", "linearlight", "pinlight")
        private val SCENE_MODES = listOf("normal", "average", "difference", "multiply", "divide", "addition", "subtract", "geometric_mean",
            "harmonic_mean", "rgb_red", "rgb_green", "rgb_blue", "lightness", "chromaticity")

        /** a blend mode's name as the list shows it */
        fun blendLabel(name: String) = name.replace('_', ' ')

        /** attaching [id] to the group [root] would make a cycle: [id] is [root] or holds it, through any groups */
        fun makesCycle(forms: List<MaskShape>, id: Int, root: Int): Boolean {
            if (id == root) return true
            val byId = forms.associateBy { it.id }
            val seen = HashSet<Int>()
            val todo = ArrayDeque(listOf(id))
            while (todo.isNotEmpty()) {
                val f = byId[todo.removeFirst()] ?: continue
                if (!seen.add(f.id)) continue
                for (m in f.members) { if (m.id == root) return true; todo.addLast(m.id) }
            }
            return false
        }

        /** The forms "use an existing shape…" offers [module]: single shapes, and groups that would not make a cycle. */
        fun existingOffers(forms: List<MaskShape>, module: ModuleMask): List<MaskShape> =
            forms.filter { f -> f.id != module.rootId && (!f.isGroup || module.rootId == null || !makesCycle(forms, f.id, module.rootId)) }

        fun shapeLabel(f: MaskShape) = f.name.ifBlank { "${f.type} #${f.id}" }

        /**
         * P3d 2a: a group's operators as the tab lists them (engine-api.md 6.9): the plain names first, then the soft
         * ones, each with desktop's name (blend_gui.c dt_masks_flexi_ops) where it differs.
         */
        val OPERATORS = listOf(
            "union (maximum)" to "maximum", "intersection (minimum)" to "minimum", "difference" to "difference",
            "exclusion" to "exclusion", "screen (smooth union)" to "screen", "sum (additive union)" to "sum",
            "product (smooth intersection)" to "product",
        )

        /** the short label of an operator on a group's row */
        fun operatorLabel(op: String?): String = when (op ?: "maximum") {
            "maximum" -> "union"; "minimum" -> "intersection"; else -> op ?: "union"
        }

        /**
         * The rows of the tree under [root], top first; the members of a nested group follow its row, one step
         * deeper, only when the group is in [expanded] (collapsed by default). Cycles and darktable's nesting limit end
         * the walk.
         */
        fun treeRows(forms: List<MaskShape>, root: Int?, expanded: Set<Int>): List<MaskTreeRow> {
            val byId = forms.associateBy { it.id }
            val out = ArrayList<MaskTreeRow>()
            fun walk(g: Int, depth: Int, seen: Set<Int>) {
                val members = byId[g]?.members ?: return
                for (i in members.indices.reversed()) {
                    val m = members[i]
                    val f = byId[m.id]
                    out += MaskTreeRow(g, m, f, depth, i, members.size)
                    if (f != null && f.isGroup && f.id in expanded && f.id !in seen && depth < 8) walk(f.id, depth + 1, seen + f.id)
                }
            }
            if (root != null) walk(root, 0, setOf(root))
            return out
        }
    }
}

private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.pair(k: String): Pair<Double, Double>? {
    val a = this[k] as? JsonArray ?: return null
    if (a.size != 2) return null
    val x = (a[0] as? JsonPrimitive)?.doubleOrNull ?: return null
    val y = (a[1] as? JsonPrimitive)?.doubleOrNull ?: return null
    return x to y
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.CoordsDirection
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.PickRequest
import app.dtandroid.engine.api.RetouchShape
import app.dtandroid.engine.api.RetouchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/*
 * Brief P3m: the retouch module's own editor (darktable's retouch.c, the manual's "retouch" page). The shapes are
 * darktable forms in retouch's own group, read and changed through engine-api.md 6.10 (dtb_retouch, _add, _shape_set,
 * _shape_remove, _select); the module's settings that are plain parameters (the algorithm buttons, blur and fill
 * settings with no shape selected) go through paramSet as every control.
 *
 * On the photo (owner: "tap and drag are enough"): with a shape tool on, a tap places the target and its source at
 * the default offset; for circle and ellipse a press on the spot dragged to where the source should be places both
 * (desktop's click-and-drag); a path is tapped point by point and closed with "done" or a tap on its first point; a
 * brush stroke is one shape when the finger lifts. Without a tool, dragging a shape's target or source moves it (one
 * history item when the finger lifts), a tap selects a shape, a tap elsewhere unselects. Two fingers always pan and
 * zoom the photo. Positions here are fractions of the picture shown (processed image); the engine's are fractions
 * of the original image, mapped with coordsTransform.
 */

const val RETOUCH_OP = "retouch"

/** The shape buttons, in desktop's order. */
enum class RetouchTool(val key: String) {
    CIRCLE("circle"), ELLIPSE("ellipse"), PATH("path"), BRUSH("brush");

    val shape: ShapeType get() = when (this) {
        CIRCLE -> ShapeType.CIRCLE
        ELLIPSE -> ShapeType.ELLIPSE
        PATH -> ShapeType.PATH
        BRUSH -> ShapeType.BRUSH
    }
}

enum class RetouchPart { TARGET, SOURCE }

/** A shape over the photo: its target and (clone, heal) source outlines, fractions of the picture shown. */
data class RetouchOutline(
    val id: Int,
    val type: String,
    val target: List<Pair<Double, Double>>,
    val source: List<Pair<Double, Double>>?,
    val closed: Boolean,
    /** circle, ellipse: the centre; path, brush: the first point (where darktable's source refers to) */
    val targetAt: Pair<Double, Double>,
    val sourceAt: Pair<Double, Double>?,
    /** brush: half its width, a fraction of the picture's shorter side */
    val halfWidth: Double = 0.0,
)

/** A finger moving a shape's target or source. */
data class RetouchDrag(val id: Int, val part: RetouchPart, val from: Pair<Double, Double>, val now: Pair<Double, Double>, val moved: Boolean = false) {
    val dx: Double get() = now.first - from.first
    val dy: Double get() = now.second - from.second
}

data class RetouchUi(
    /** the retouch instance whose panel is open */
    val key: InstanceKey? = null,
    val st: RetouchState? = null,
    /** the shapes of the current wavelet scale over the photo */
    val outlines: List<RetouchOutline> = emptyList(),
    val selected: Int? = null,
    /** the shape button that is on (null: none) and whether it stays on after a shape (desktop's ctrl+click) */
    val tool: RetouchTool? = null,
    val continuous: Boolean = false,
    /** desktop's "show and edit shapes", "display masks", "temporarily switch off shapes" */
    val showShapes: Boolean = true,
    val displayMasks: Boolean = false,
    val suppress: Boolean = false,
    /** a new shape's size: circle radius, ellipse's long radius, brush size; fractions of the picture's shorter side */
    val size: Double = 0.03,
    val feather: Double = 0.01,
    val flatness: Double = 0.6,
    val rotation: Double = 0.0,
    val hardness: Double = 0.66,
    /** circle, ellipse: where the finger went down (the target) and where it is (the source when it moved) */
    val press: Pair<Double, Double>? = null,
    val pressNow: Pair<Double, Double>? = null,
    val pressMoved: Boolean = false,
    /** path: its points so far; brush: the stroke under the finger */
    val pathPoints: List<Pair<Double, Double>> = emptyList(),
    val stroke: List<Pair<Double, Double>>? = null,
    val drag: RetouchDrag? = null,
    /** "pick fill color from image" armed: the next tap on the photo picks */
    val picking: Boolean = false,
    val note: String? = null,
) {
    val selectedShape: RetouchShape? get() = selected?.let { id -> st?.shapes?.firstOrNull { it.id == id } }
    val wavelet: Boolean get() = (st?.numScales ?: 0) > 0
}

class Retouch internal constructor(private val c: DarkroomController) {
    private val _state = MutableStateFlow(RetouchUi())
    val state: StateFlow<RetouchUi> = _state.asStateFlow()

    private val readOnly get() = c.state.value.readOnly

    /** The retouch instance whose panel is open on an editable photo, else null. */
    fun wanted(s: DarkroomState): InstanceKey? = s.expanded?.takeIf { it.op == RETOUCH_OP && s.info != null && !s.loading }

    /** Whether the photo's touches and drawing belong to retouch now. */
    fun active(s: DarkroomState): Boolean {
        val k = _state.value.key ?: return false
        return k == wanted(s) && !s.before && s.cropTool == null
    }

    /** Another photo: nothing open, drawn or chosen; the module's switches back to desktop's defaults. */
    fun reset() { val was = _state.value.suppress; _state.value = RetouchUi(); if (was) c.requestRender() }

    /** The panel of [key] opened (or the photo changed under it): its shapes read. */
    suspend fun open(key: InstanceKey) {
        if (_state.value.key != key) {
            val was = _state.value.suppress
            _state.value = RetouchUi(key = key)
            if (was) c.requestRender()
        }
        reload()
    }

    /** The panel closed: tools off; "temporarily switch off shapes" ends with it, as desktop's (only while focused). */
    fun close() {
        val was = _state.value.suppress
        _state.update { it.copy(key = null, tool = null, press = null, pressNow = null, pathPoints = emptyList(), stroke = null, drag = null, picking = false, suppress = false) }
        if (was) c.requestRender()
    }

    suspend fun reload() {
        if (_state.value.key == null || c.state.value.info == null) return
        try { c.engineRead { reloadLocked(it) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { note(e) }
    }

    /** After every change of the edit (under the engine lock), while the panel is open. */
    internal suspend fun afterChangeLocked(e: DarktableEngine) {
        val k = _state.value.key ?: return
        if (c.state.value.modules.none { it.key == k }) { _state.update { RetouchUi() }; return }
        try { reloadLocked(e) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { note(x) }
    }

    private fun note(x: Exception) = _state.update { it.copy(note = (x as? EngineException)?.detail ?: x.message ?: x.javaClass.simpleName) }

    private suspend fun reloadLocked(e: DarktableEngine) {
        val k = _state.value.key ?: return
        if (c.state.value.modules.none { it.key == k }) return
        val st = e.retouch(k.op, k.priority)
        val shown = st.shapes.filter { it.scale == st.currScale }
        val outlines = outlinesLocked(e, shown)
        _state.update { u ->
            if (u.key != k) u
            else u.copy(st = st, outlines = outlines, selected = u.selected?.takeIf { id -> shown.any { it.id == id } }, note = null)
        }
    }

    // ------------------------------------------------------------------ outlines

    private suspend fun outlinesLocked(e: DarktableEngine, shapes: List<RetouchShape>): List<RetouchOutline> {
        if (shapes.isEmpty()) return emptyList()
        val probe = e.coordsTransform(CoordsDirection.TO_PROCESSED, listOf(0.5 to 0.5))
        val ow = probe.originalWidth.takeIf { it > 0 } ?: return emptyList()
        val oh = probe.originalHeight.takeIf { it > 0 } ?: return emptyList()
        // every shape's target and source in original coordinates, then one call for all
        data class Raw(val s: RetouchShape, val target: List<Pair<Double, Double>>, val anchor: Pair<Double, Double>, val closed: Boolean, val half: Double)
        val raws = shapes.mapNotNull { s -> rawOutline(s, ow, oh)?.let { (pts, anchor, closed) ->
            // a brush's border is a fraction of the original's shorter side; as a fraction of the picture's width
            val border = (firstPoint(s.geometry)?.get("border") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: 0.0
            val half = if (s.type == "brush") border * min(ow, oh) / ow else 0.0
            Raw(s, pts, anchor, closed, half)
        } }
        val all = ArrayList<Pair<Double, Double>>()
        for (r in raws) {
            all.add(r.anchor); all.addAll(r.target)
            val src = r.s.source
            if (src != null) {
                val dx = src.first - r.anchor.first
                val dy = src.second - r.anchor.second
                all.add(src); all.addAll(r.target.map { it.first + dx to it.second + dy })
            }
        }
        val mapped = e.coordsTransform(CoordsDirection.TO_PROCESSED, all).points
        var i = 0
        val out = ArrayList<RetouchOutline>()
        for (r in raws) {
            val anchor = mapped[i++]
            val target = mapped.subList(i, i + r.target.size).filterNotNull(); i += r.target.size
            var sourceAt: Pair<Double, Double>? = null
            var source: List<Pair<Double, Double>>? = null
            if (r.s.source != null) {
                sourceAt = mapped[i++]
                source = mapped.subList(i, i + r.target.size).filterNotNull(); i += r.target.size
            }
            if (anchor == null || target.isEmpty()) continue
            out.add(RetouchOutline(r.s.id, r.s.type, target, source, r.closed, anchor, sourceAt, r.half))
        }
        return out
    }

    /** The target outline in original coordinates (lengths of masks are fractions of the original's shorter side). */
    private fun rawOutline(s: RetouchShape, ow: Int, oh: Int): Triple<List<Pair<Double, Double>>, Pair<Double, Double>, Boolean>? {
        val g = s.geometry
        val m = min(ow, oh).toDouble()
        return when (s.type) {
            "circle" -> {
                val cpt = g.pair("center") ?: return null
                val r = g.num("radius") ?: return null
                Triple(ring(cpt, r * m / ow, r * m / oh, 0.0, ow, oh), cpt, true)
            }
            "ellipse" -> {
                val cpt = g.pair("center") ?: return null
                val rr = (g["radius"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
                if (rr.size < 2) return null
                Triple(ring(cpt, rr[0] * m, rr[1] * m, g.num("rotation") ?: 0.0, ow, oh, pixels = true), cpt, true)
            }
            "path", "brush" -> {
                val corners = (g["points"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.pair("corner") } ?: return null
                if (corners.isEmpty()) return null
                Triple(corners, corners.first(), s.type == "path")
            }
            else -> null
        }
    }

    private fun ring(cpt: Pair<Double, Double>, a: Double, b: Double, deg: Double, ow: Int, oh: Int, pixels: Boolean = false): List<Pair<Double, Double>> {
        val r = Math.toRadians(deg)
        return (0 until RING).map { i ->
            val t = 2 * Math.PI * i / RING
            if (!pixels) cpt.first + a * cos(t) to cpt.second + b * sin(t)
            else {
                val x = a * cos(t)
                val y = b * sin(t)
                cpt.first + (x * cos(r) - y * sin(r)) / ow to cpt.second + (x * sin(r) + y * cos(r)) / oh
            }
        }
    }

    // ------------------------------------------------------------------ the panel

    /** A shape button: on (a tap), on for several shapes (a long press, desktop's ctrl+click), off when on already. */
    fun setTool(t: RetouchTool?, continuous: Boolean = false) {
        if (readOnly) return
        val cur = _state.value
        val off = t == null || (cur.tool == t && cur.continuous == continuous)
        c.disarmPicker()
        _state.update { it.copy(tool = if (off) null else t, continuous = !off && continuous, press = null, pressNow = null, pathPoints = emptyList(), stroke = null, drag = null, picking = false, note = null) }
    }

    fun toggleShowShapes() = _state.update { it.copy(showShapes = !it.showShapes) }

    fun toggleDisplayMasks() = _state.update { it.copy(displayMasks = !it.displayMasks) }

    /** Desktop's "temporarily switch off shapes": the picture rendered without the module, the edit untouched. */
    fun toggleSuppress() {
        _state.update { it.copy(suppress = !it.suppress) }
        c.requestRender()
    }

    /** The render options' instance to leave out while shapes are switched off (only while the panel is open). */
    fun suppressed(): InstanceKey? = _state.value.takeIf { it.suppress }?.key?.takeIf { it == wanted(c.state.value) }

    /** A new shape's size and look (the tool's sliders). */
    fun setNewValue(name: String, v: Double) = _state.update {
        when (name) {
            "size" -> it.copy(size = v)
            "feather" -> it.copy(feather = v)
            "flatness" -> it.copy(flatness = v)
            "rotation" -> it.copy(rotation = v)
            "hardness" -> it.copy(hardness = v)
            else -> it
        }
    }

    private fun key(): InstanceKey? = _state.value.key?.takeIf { !readOnly }

    private suspend fun change(block: suspend (DarktableEngine, InstanceKey) -> Unit) {
        val k = key() ?: return
        try {
            c.engineChange { e -> block(e, k); e.historySeal() }
        } catch (x: EngineException) { note(x) }
    }

    /** An algorithm button (desktop's click): the algorithm of new shapes. */
    suspend fun setAlgorithm(name: String) = change { e, k ->
        e.paramSet(k.op, k.priority, "algorithm", JsonPrimitive("DT_IOP_RETOUCH_" + name.uppercase()))
    }

    /** A long press on an algorithm button (desktop's ctrl+click): the selected shape's algorithm (clone <-> heal, blur <-> fill). */
    suspend fun applyAlgorithmToSelected(name: String) {
        val s = _state.value.selectedShape ?: run { _state.update { it.copy(note = "select a shape first") }; return }
        if (!switchable(s.algorithm, name)) {
            _state.update { it.copy(note = "a ${s.algorithm} shape can become ${if (s.clone) "clone or heal" else "blur or fill"} only") }
            return
        }
        change { e, k -> e.retouchShapeSet(k.op, k.priority, s.id, buildJsonObject { put("algorithm", name) }) }
    }

    /** A shape tapped (null: none). Desktop's selection makes the shape's algorithm and settings the module's. */
    suspend fun select(id: Int?) {
        _state.update { it.copy(selected = id, note = null) }
        if (id == null || readOnly) return
        val s = _state.value.selectedShape ?: return
        val st = _state.value.st ?: return
        val differs = st.algorithm != s.algorithm || (s.algorithm == "blur" && (st.blurType != s.blurType || st.blurRadius != s.blurRadius)) ||
            (s.algorithm == "fill" && (st.fillMode != s.fillMode || st.fillBrightness != s.fillBrightness || st.fillColor != s.fillColor))
        if (differs) change { e, k -> e.retouchSelect(k.op, k.priority, id) }
    }

    /**
     * A blur or fill control: the selected shape's and the module's value when the selected shape has that algorithm
     * (desktop's controls); else the module's alone. [seal] at the end of a slider drag.
     */
    suspend fun setSetting(name: String, value: JsonElement, seal: Boolean = true) {
        val k = key() ?: return
        val s = _state.value.selectedShape
        val forShape = s != null && ((name.startsWith("blur") && s.algorithm == "blur") || (name.startsWith("fill") && s.algorithm == "fill"))
        try {
            c.engineChange { e ->
                if (forShape) e.retouchShapeSet(k.op, k.priority, s!!.id, JsonObject(mapOf(name to value)))
                else when (name) {
                    "blur_type" -> e.paramSet(k.op, k.priority, name, JsonPrimitive("DT_IOP_RETOUCH_BLUR_" + (value as JsonPrimitive).content.uppercase()))
                    "fill_mode" -> e.paramSet(k.op, k.priority, name, JsonPrimitive("DT_IOP_RETOUCH_FILL_" + (value as JsonPrimitive).content.uppercase()))
                    "fill_color" -> e.paramsSet(k.op, k.priority, (value as JsonArray).mapIndexed { i, v -> "fill_color[$i]" to (v as JsonPrimitive) }.toMap())
                    else -> e.paramSet(k.op, k.priority, name, value as JsonPrimitive)
                }
                if (seal) e.historySeal()
            }
        } catch (x: EngineException) { note(x) }
    }

    /** The selected shape's mask opacity (one history item). */
    suspend fun setOpacity(v: Double) {
        val s = _state.value.selectedShape ?: return
        change { e, k -> e.retouchShapeSet(k.op, k.priority, s.id, buildJsonObject { put("opacity", v) }) }
    }

    /** The selected circle's or ellipse's size and look: "size", "feather", "flatness", "rotation" (one history item). */
    suspend fun setShapeValue(name: String, v: Double) {
        val s = _state.value.selectedShape ?: return
        val g = JsonObject(s.geometry - "source")
        val ng: JsonObject = when (s.type) {
            "circle" -> when (name) {
                "size" -> JsonObject(g + ("radius" to JsonPrimitive(v)))
                "feather" -> JsonObject(g + ("border" to JsonPrimitive(v)))
                else -> return
            }
            "ellipse" -> {
                val r = (g["radius"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return
                val a = r.getOrElse(0) { 0.05 }
                val b = r.getOrElse(1) { a }
                when (name) {
                    "size" -> JsonObject(g + ("radius" to JsonArray(listOf(JsonPrimitive(v), JsonPrimitive(v * b / a)))))
                    "flatness" -> JsonObject(g + ("radius" to JsonArray(listOf(JsonPrimitive(a), JsonPrimitive((a * v).coerceAtLeast(0.001))))))
                    "rotation" -> JsonObject(g + ("rotation" to JsonPrimitive(v)))
                    "feather" -> JsonObject(g + ("border" to JsonPrimitive(v)))
                    else -> return
                }
            }
            else -> return
        }
        change { e, k -> e.retouchShapeSet(k.op, k.priority, s.id, buildJsonObject { put("geometry", ng) }) }
    }

    /** Desktop's right-click on a shape: removed for good (one history item). */
    suspend fun removeSelected() {
        val s = _state.value.selectedShape ?: return
        _state.update { it.copy(selected = null) }
        change { e, k -> e.retouchShapeRemove(k.op, k.priority, s.id) }
    }

    /** "pick fill color from image": the next tap on the photo picks the module's output there (desktop's point picker). */
    fun togglePicking() {
        if (readOnly) return
        c.disarmPicker()
        _state.update { it.copy(picking = !it.picking, tool = if (!it.picking) null else it.tool) }
    }

    private suspend fun pickAt(x: Double, y: Double) {
        val k = key() ?: return
        _state.update { it.copy(picking = false) }
        val rgb = try {
            c.engineRead { e -> e.colorPick(k.op, k.priority, PickRequest(point = listOf(x, y))).output?.mean }
        } catch (e: EngineException) { note(e); null }
        val v = rgb?.take(3)?.map { (it ?: 0.0).coerceIn(0.0, 1.0) }?.takeIf { it.size == 3 } ?: return
        setSetting("fill_color", JsonArray(v.map { JsonPrimitive(it) }))
    }

    // ------------------------------------------------------------------ on the photo

    /**
     * One finger down at ([x], [y]); [tx], [ty] the touch target's half size as fractions of the picture. True when the
     * touch belongs to retouch (a tool is on, or it is on a shape); false lets the photo pan.
     */
    fun touchDown(x: Double, y: Double, tx: Double, ty: Double): Boolean {
        val u = _state.value
        if (readOnly || u.key == null) return false
        if (u.picking) return false
        when (u.tool) {
            RetouchTool.CIRCLE, RetouchTool.ELLIPSE -> { _state.update { it.copy(press = x to y, pressNow = x to y, pressMoved = false) }; return true }
            RetouchTool.PATH -> return true
            RetouchTool.BRUSH -> { _state.update { it.copy(stroke = listOf(x to y)) }; return true }
            null -> {}
        }
        if (!u.showShapes) return false
        val hit = hit(u, x, y, tx, ty) ?: return false
        _state.update { it.copy(drag = RetouchDrag(hit.first, hit.second, x to y, x to y)) }
        return true
    }

    fun touchMove(x: Double, y: Double, slopX: Double, slopY: Double) = _state.update { u ->
        when {
            u.press != null -> u.copy(pressNow = x to y, pressMoved = u.pressMoved || far(u.press, x to y, slopX, slopY))
            u.stroke != null -> u.copy(stroke = u.stroke + (x to y))
            u.drag != null -> u.copy(drag = u.drag.copy(now = x to y, moved = u.drag.moved || far(u.drag.from, x to y, slopX, slopY)))
            else -> u
        }
    }

    /** A second finger: whatever the first began goes. */
    fun touchCancel() = _state.update { it.copy(press = null, pressNow = null, pressMoved = false, stroke = null, drag = null) }

    /** The finger lifted: a shape is placed, a stroke becomes a shape, a moved part is written, a tap selects. */
    fun touchUp(x: Double, y: Double, tx: Double, ty: Double) {
        val u = _state.value
        when {
            u.press != null -> {
                val target = u.press
                val source = if (u.pressMoved) u.pressNow else null
                _state.update { it.copy(press = null, pressNow = null, pressMoved = false) }
                c.launch { add(u.tool ?: return@launch, listOf(target), source) }
            }
            u.tool == RetouchTool.PATH -> {
                val first = u.pathPoints.firstOrNull()
                if (first != null && u.pathPoints.size >= 3 && abs(x - first.first) <= tx && abs(y - first.second) <= ty) { c.launch { finishPath() }; return }
                _state.update { it.copy(pathPoints = it.pathPoints + (x to y)) }
            }
            u.stroke != null -> {
                val pts = u.stroke
                _state.update { it.copy(stroke = null) }
                c.launch { add(RetouchTool.BRUSH, pts, null) }
            }
            u.drag != null -> {
                val d = u.drag
                _state.update { it.copy(drag = null) }
                if (d.moved) c.launch { move(d) } else c.launch { select(d.id) }
            }
        }
    }

    /** A tap on the photo that retouch did not take at its start: picks the fill colour, or selects or unselects. */
    fun tap(x: Double, y: Double, tx: Double, ty: Double): Boolean {
        val u = _state.value
        if (u.key == null) return false
        if (u.picking) { c.launch { pickAt(x, y) }; return true }
        if (u.tool != null || !u.showShapes) return false
        val hit = hit(u, x, y, tx, ty)
        c.launch { select(hit?.first) }
        return true
    }

    /** "done" of a path (also a tap on its first point). */
    suspend fun finishPath() {
        val pts = _state.value.pathPoints
        if (pts.size < 3) return
        _state.update { it.copy(pathPoints = emptyList()) }
        add(RetouchTool.PATH, pts, null)
    }

    /** The same from a button (launched). */
    fun finishPathLater() { c.launch { finishPath() } }

    /** The fill colour chooser's red, green, blue (launched). */
    fun setFillColorLater(rgb: List<Double>) { c.launch { setSetting("fill_color", JsonArray(rgb.map { JsonPrimitive(it) })) } }

    fun cancelPath() = _state.update { it.copy(pathPoints = emptyList()) }

    private fun far(a: Pair<Double, Double>, b: Pair<Double, Double>, sx: Double, sy: Double) =
        abs(a.first - b.first) > sx || abs(a.second - b.second) > sy

    private fun abs(v: Double) = kotlin.math.abs(v)

    /** The shape part under ([x], [y]): the selected shape first, then the others, top (last) first; a source before its target. */
    internal fun hit(u: RetouchUi, x: Double, y: Double, tx: Double, ty: Double): Pair<Int, RetouchPart>? {
        val order = u.outlines.sortedByDescending { if (it.id == u.selected) Int.MAX_VALUE else u.outlines.indexOf(it) }
        for (o in order) {
            if (o.source != null && inside(o.source, o.closed, o.halfWidth, o.sourceAt, x, y, tx, ty)) return o.id to RetouchPart.SOURCE
            if (inside(o.target, o.closed, o.halfWidth, o.targetAt, x, y, tx, ty)) return o.id to RetouchPart.TARGET
        }
        return null
    }

    // ------------------------------------------------------------------ writes

    /** A new shape from the tool: [pts] the circle's or ellipse's centre, the path's points or the brush stroke. */
    private suspend fun add(tool: RetouchTool, pts: List<Pair<Double, Double>>, sourceAt: Pair<Double, Double>?) {
        val k = key() ?: return
        val u = _state.value
        if (pts.isEmpty()) return
        val draw = DrawTool.start(tool.shape, k).copy(
            size = if (tool == RetouchTool.BRUSH) u.size / 2 else u.size, feather = u.feather, flatness = u.flatness, rotation = u.rotation,
            hardness = u.hardness, opacity = 1.0,
            center = if (tool == RetouchTool.CIRCLE || tool == RetouchTool.ELLIPSE) pts.first() else null,
            points = if (tool == RetouchTool.PATH) pts else emptyList(),
            strokes = if (tool == RetouchTool.BRUSH) listOf(Stroke(pts, u.size / 2, u.hardness, 1.0)) else emptyList(),
        )
        var id: Int? = null
        try {
            c.engineChange { e ->
                val g = c.masks.geometries(e, draw).first()
                val clone = (u.st?.algorithm ?: "heal") in CLONE_ALGOS
                val source = if (!clone) null else if (sourceAt != null) {
                    e.coordsTransform(CoordsDirection.TO_ORIGINAL, listOf(sourceAt)).points.firstOrNull()
                        ?: throw EngineException(EngineError.ARG, "the source lies outside the picture")
                } else defaultSource(e, tool, g)
                id = e.retouchAdd(k.op, k.priority, tool.key, g, source)
                e.historySeal()
            }
        } catch (x: EngineException) { note(x); return }
        _state.update { it.copy(selected = id ?: it.selected, tool = if (it.continuous) it.tool else null, continuous = it.continuous) }
    }

    /**
     * Desktop's default source position (the "+"): circle and ellipse a radius to the right and up (masks/circle.c,
     * ellipse.c _initial_source_pos), path 2 % and brush 1 % of the image to the right and down (path.c, brush.c),
     * from the target's centre or first point, in original-image fractions.
     */
    private fun defaultSource(@Suppress("UNUSED_PARAMETER") e: DarktableEngine, tool: RetouchTool, g: JsonObject): Pair<Double, Double> {
        val anchor = when (tool) {
            RetouchTool.CIRCLE, RetouchTool.ELLIPSE -> g.pair("center")
            else -> firstPoint(g)?.pair("corner") ?: (g["stroke"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonArray)?.mapNotNull { v -> (v as? JsonPrimitive)?.doubleOrNull } }?.let { it[0] to it[1] }
        } ?: (0.5 to 0.5)
        return when (tool) {
            RetouchTool.CIRCLE -> { val r = g.num("radius") ?: 0.03; anchor.first + r to anchor.second - r }
            RetouchTool.ELLIPSE -> {
                val r = (g["radius"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: listOf(0.03, 0.02)
                anchor.first + r[0] to anchor.second - r.getOrElse(1) { r[0] }
            }
            RetouchTool.PATH -> anchor.first + 0.02 to anchor.second + 0.02
            RetouchTool.BRUSH -> anchor.first + 0.01 to anchor.second + 0.01
        }
    }

    /** A moved target or source written (one history item). The target moves as a whole; the source stays where it is. */
    private suspend fun move(d: RetouchDrag) {
        val k = key() ?: return
        val o = _state.value.outlines.firstOrNull { it.id == d.id } ?: return
        val s = _state.value.st?.shapes?.firstOrNull { it.id == d.id } ?: return
        try {
            c.engineChange { e ->
                val outside = EngineException(EngineError.ARG, "the shape would lie outside the picture")
                if (d.part == RetouchPart.SOURCE) {
                    val at = o.sourceAt ?: return@engineChange
                    val src = e.coordsTransform(CoordsDirection.TO_ORIGINAL, listOf(at.first + d.dx to at.second + d.dy)).points.first() ?: throw outside
                    e.retouchShapeSet(k.op, k.priority, s.id, buildJsonObject { put("source", JsonArray(listOf(JsonPrimitive(src.first), JsonPrimitive(src.second)))) })
                } else {
                    val res = e.coordsTransform(CoordsDirection.TO_ORIGINAL, listOf(o.targetAt, o.targetAt.first + d.dx to o.targetAt.second + d.dy)).points
                    val a = res[0] ?: throw outside
                    val b = res[1] ?: throw outside
                    val g = moved(JsonObject(s.geometry - "source"), b.first - a.first, b.second - a.second)
                    e.retouchShapeSet(k.op, k.priority, s.id, buildJsonObject { put("geometry", g) })
                }
                e.historySeal()
            }
        } catch (x: EngineException) { note(x) }
        _state.update { it.copy(selected = d.id) }
    }

    companion object {
        private const val RING = 48
        val CLONE_ALGOS = setOf("clone", "heal")

        /** Desktop's ctrl+click on an algorithm: clone <-> heal and blur <-> fill only. */
        fun switchable(from: String, to: String) = (from in CLONE_ALGOS) == (to in CLONE_ALGOS)

        /** The geometry moved by ([dx], [dy]) original fractions: centres and every point's corner and control points. */
        fun moved(g: JsonObject, dx: Double, dy: Double): JsonObject {
            fun shift(v: JsonElement?): JsonElement? = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 }
                ?.let { JsonArray(listOf(JsonPrimitive(it[0] + dx), JsonPrimitive(it[1] + dy))) }
            val out = g.toMutableMap()
            shift(g["center"])?.let { out["center"] = it }
            (g["points"] as? JsonArray)?.let { pts ->
                out["points"] = JsonArray(pts.map { p ->
                    val o = p as? JsonObject ?: return@map p
                    val m = o.toMutableMap()
                    for (key in listOf("corner", "ctrl1", "ctrl2")) shift(o[key])?.let { m[key] = it }
                    JsonObject(m)
                })
            }
            return JsonObject(out)
        }

        /** Whether ([x], [y]) is on a part: inside its closed outline, near a brush's line, or within a touch of its point. */
        fun inside(pts: List<Pair<Double, Double>>, closed: Boolean, half: Double, at: Pair<Double, Double>?, x: Double, y: Double, tx: Double, ty: Double): Boolean {
            if (at != null && kotlin.math.abs(at.first - x) <= tx && kotlin.math.abs(at.second - y) <= ty) return true
            if (closed && pts.size >= 3) {
                var c = false
                var j = pts.size - 1
                for (i in pts.indices) {
                    val (xi, yi) = pts[i]
                    val (xj, yj) = pts[j]
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                    j = i
                }
                return c
            }
            // an open line (brush): near any piece
            val reach = maxOf(half, tx)
            for (i in 1 until pts.size) {
                val (x0, y0) = pts[i - 1]
                val (x1, y1) = pts[i]
                val vx = x1 - x0
                val vy = y1 - y0
                val l2 = vx * vx + vy * vy
                val t = if (l2 <= 0) 0.0 else (((x - x0) * vx + (y - y0) * vy) / l2).coerceIn(0.0, 1.0)
                if (hypot(x0 + t * vx - x, y0 + t * vy - y) <= reach) return true
            }
            return pts.size == 1 && hypot(pts[0].first - x, pts[0].second - y) <= reach
        }
    }
}

private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.pair(k: String): Pair<Double, Double>? {
    val a = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
    return if (a.size >= 2) a[0] to a[1] else null
}
private fun firstPoint(g: JsonObject): JsonObject? = (g["points"] as? JsonArray)?.firstOrNull() as? JsonObject

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.PerspectiveState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Brief P3n: rotate and perspective's structure and fit (the manual's "rotate and perspective" page; darktable's
 * ashift.c). Everything is darktable's own code in the engine (engine-api.md 6.11, dtb_ashift): the structure
 * buttons (rectangle, find lines with its enhancements, draw lines), the fit buttons with their variants, and the
 * mouse on the photo. While the panel is open the module has the focus as on desktop: the photo shows the whole,
 * uncropped picture and the automatic crop as an outline.
 *
 * Touch for desktop's mouse: a finger is the left button; a long press (held still for LONG_PRESS_MS before it moves or
 * lifts) is the right button (deselect a line, delete a drawn line, sweep-deselect); the "select by area" switch is
 * desktop's shift (drag a box: the lines inside are selected; with a long press, deselected). Two fingers pan and zoom.
 */

const val ASHIFT_OP = "ashift"

/** The two fit variants desktop reaches with ctrl and shift (and ctrl+shift on "both"). */
enum class FitVariant(val key: String, val label: String) {
    PLAIN("plain", "rotation and lens shift"),
    ROTATION("rotation", "rotation only"),
    SHIFT("shift", "lens shift only"),
    NO_SHEAR("no_shear", "without shear"),
}

data class PerspectiveUi(
    /** the rotate and perspective instance whose panel is open (focused in the engine) */
    val key: InstanceKey? = null,
    val st: PerspectiveState? = null,
    /** desktop's shift: a drag selects or deselects the lines in a box */
    val areaMode: Boolean = false,
    /** composition guides over the automatic crop (desktop draws the darkroom's guides there) */
    val showGuides: Boolean = false,
    /** the finger is down on the photo, as fractions of the picture (for the area's box) */
    val finger: Pair<Double, Double>? = null,
    val note: String? = null,
)

class Perspective internal constructor(private val c: DarkroomController) {
    private val _state = MutableStateFlow(PerspectiveUi())
    val state: StateFlow<PerspectiveUi> = _state.asStateFlow()

    private val readOnly get() = c.state.value.readOnly

    /** The instance whose panel is open on an editable photo, else null. */
    fun wanted(s: DarkroomState): InstanceKey? =
        s.expanded?.takeIf { it.op == ASHIFT_OP && s.info != null && !s.loading && !s.readOnly }

    /** Whether the photo shows the structure and takes one finger. */
    fun active(s: DarkroomState): Boolean {
        val k = _state.value.key ?: return false
        return k == wanted(s) && !s.before && s.cropTool == null
    }

    /** Whether a finger on the photo goes to the module (desktop takes the mouse when there is structure or a crop to drag). */
    fun takesFinger(): Boolean {
        val st = _state.value.st ?: return false
        return st.lines.isNotEmpty() || st.handles.isNotEmpty() || st.method == "drawn" || (st.cropmode == 2 && st.clip != null)
    }

    /** Another photo: nothing open (the engine dropped the module's interface data when the photo closed). */
    fun reset() { _state.value = PerspectiveUi(); press = null }

    /** The panel of [key] opened: the module takes the focus (whole picture, structure buttons). */
    suspend fun open(key: InstanceKey) {
        if (readOnly) return
        if (_state.value.key == key && _state.value.st?.focused == true) return
        _state.update { PerspectiveUi(key = key, areaMode = it.areaMode, showGuides = it.showGuides) }
        try {
            val st = c.engineRead { e -> e.ashift(key.op, key.priority, req("begin")) }
            _state.update { if (it.key == key) it.copy(st = st, note = st.message) else it }
        } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { note(x) }
        c.requestRender()
    }

    /** Another module expanded or this one collapsed: the focus leaves. */
    fun focusChanged() {
        val k = _state.value.key ?: return
        if (k != wanted(c.state.value)) close()
    }

    /** The focus leaves (desktop's gui_focus out): drawn lines and the rectangle are kept in the parameters. */
    fun close() {
        val k = _state.value.key ?: return
        _state.update { it.copy(key = null, st = null, finger = null) }
        press = null
        c.launch {
            try { c.engineChange { e -> e.ashift(k.op, k.priority, req("end")) } } catch (x: EngineException) { note(x) }
        }
    }

    /** After every change of the edit (under the engine lock), while the panel is open: the structure again. */
    internal suspend fun afterChangeLocked(e: DarktableEngine) {
        val k = _state.value.key ?: return
        if (c.state.value.modules.none { it.key == k }) { _state.update { PerspectiveUi() }; return }
        try {
            val st = e.ashift(k.op, k.priority, req("state"))
            _state.update { if (it.key == k) it.copy(st = st) else it }
        } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { note(x) }
    }

    /**
     * A control of the focused module changed its parameters (under the engine lock, before the item is sealed): desktop's
     * gui_changed, so the automatic crop follows the rotation and lens shift, merged into the same history item.
     */
    internal suspend fun changedLocked(e: DarktableEngine, key: InstanceKey, paths: Collection<String>) {
        if (_state.value.key != key || readOnly) return
        val r = buildJsonObject { put("action", "changed"); if ("cropmode" in paths) put("what", "cropmode") }
        try {
            val st = e.ashift(key.op, key.priority, r)
            _state.update { if (it.key == key) it.copy(st = st) else it }
        } catch (x: EngineException) { note(x) }
    }

    /**
     * A render for someone else on the open photo (LightPhotoLibrary's preview, under the engine lock): the module's
     * focus is paused around it so it gets the edit's own, cropped picture, as P3a's crop tool brackets its picture.
     * Nothing in the history or the structure changes.
     */
    internal suspend fun <T> pausedLocked(e: DarktableEngine, block: suspend () -> T): T {
        val k = _state.value.key?.takeIf { !readOnly && _state.value.st?.focused == true } ?: return block()
        runCatching { e.ashift(k.op, k.priority, req("pause")) }
        try { return block() } finally { runCatching { e.ashift(k.op, k.priority, req("resume")) } }
    }

    private fun note(x: Exception) = _state.update { it.copy(note = (x as? EngineException)?.detail ?: x.message ?: x.javaClass.simpleName) }

    private fun req(action: String) = buildJsonObject { put("action", action) }

    private suspend fun change(r: JsonObject) {
        val k = _state.value.key?.takeIf { !readOnly } ?: return
        try {
            var st: PerspectiveState? = null
            c.engineChange { e -> st = e.ashift(k.op, k.priority, r) }
            st?.let { s -> _state.update { if (it.key == k) it.copy(st = it.st ?: s, note = s.message) else it } }
        } catch (x: EngineException) { note(x) }
    }

    /** A structure button: "lines_found" (with an enhancement: none, edges, detail, both), "rectangle", "drawn". */
    suspend fun structure(method: String, enhance: String = "none") = change(buildJsonObject {
        put("action", "structure"); put("method", method); if (method == "lines_found") put("enhance", enhance)
    })

    /** A fit button: "vertical", "horizontal", "both" and a variant. */
    suspend fun fit(axis: String, variant: FitVariant = FitVariant.PLAIN) = change(buildJsonObject {
        put("action", "fit"); put("axis", axis); put("variant", variant.key)
    })

    fun toggleAreaMode() = _state.update { it.copy(areaMode = !it.areaMode) }
    fun toggleGuides() = _state.update { it.copy(showGuides = !it.showGuides) }

    // ------------------------------------------------------------------ the finger as desktop's mouse

    private data class Press(val x: Double, val y: Double, val t: Long, val radius: Double, var started: Boolean = false,
                             var button: Int = 1, var lastX: Double = x, var lastY: Double = y)

    private data class Ptr(val key: InstanceKey, val event: String, val x: Double, val y: Double, val button: Int, val shift: Boolean, val radius: Double)

    private var press: Press? = null
    private val ptrs = Channel<Ptr>(Channel.UNLIMITED)
    private var consumer: Job? = null

    /** A finger down at ([x], [y]) (fractions of the picture) at time [t] (ms); [radius]: a touch target's half, a fraction of the picture's width. */
    fun touchDown(x: Double, y: Double, t: Long, radius: Double): Boolean {
        if (readOnly || _state.value.key == null || !takesFinger()) return false
        press = Press(x, y, t, radius)
        _state.update { it.copy(finger = x to y) }
        return true
    }

    /** The finger moved; [slop] is the touch slop as a fraction of the picture's width. */
    fun touchMove(x: Double, y: Double, t: Long, slop: Double) {
        val p = press ?: return
        p.lastX = x; p.lastY = y
        _state.update { it.copy(finger = x to y) }
        if (!p.started) {
            if (kotlin.math.hypot(x - p.x, y - p.y) <= slop) return
            start(p, t)
        }
        send(p, "move", x, y)
    }

    /** The finger lifted: a tap is a click (a long press the right button), a drag ends. */
    fun touchUp(x: Double, y: Double, t: Long) {
        val p = press ?: return
        press = null
        _state.update { it.copy(finger = null) }
        if (!p.started) { start(p, t); send(p, "release", p.x, p.y) } else send(p, "release", x, y)
    }

    /** A second finger: desktop has no cancel, what the finger began ends where it is. */
    fun touchCancel() {
        val p = press ?: return
        press = null
        _state.update { it.copy(finger = null) }
        if (p.started) send(p, "release", p.lastX, p.lastY)
    }

    private fun start(p: Press, t: Long) {
        p.started = true
        p.button = if (t - p.t >= LONG_PRESS_MS) 3 else 1
        send(p, "press", p.x, p.y)
    }

    private fun send(p: Press, event: String, x: Double, y: Double) {
        val k = _state.value.key ?: return
        ptrs.trySend(Ptr(k, event, x, y, p.button, _state.value.areaMode, p.radius))
        if (consumer == null) consumer = c.launch { for (e in ptrs) handleQueued(e) }
    }

    private suspend fun handleQueued(first: Ptr) {
        var e = first
        // moves are conflated: only the newest one before the next press or release runs
        while (e.event == "move") {
            val n = ptrs.tryReceive().getOrNull() ?: break
            if (n.event == "move") e = n else { pointer(e); e = n }
        }
        pointer(e)
    }

    private suspend fun pointer(p: Ptr) {
        if (_state.value.key != p.key) return
        val r = buildJsonObject {
            put("action", "pointer"); put("event", p.event); put("x", p.x); put("y", p.y); put("button", p.button)
            put("shift", p.shift); put("radius", p.radius)
        }
        try {
            if (p.event == "release") {
                // the release may write the drawn lines, the rectangle or the crop: a change of the edit
                var st: PerspectiveState? = null
                c.engineChange { e -> st = e.ashift(p.key.op, p.key.priority, r) }
                st?.let { s -> _state.update { if (it.key == p.key) it.copy(st = s, note = s.message) else it } }
            } else {
                val st = c.engineRead { e -> e.ashift(p.key.op, p.key.priority, r) }
                _state.update { if (it.key == p.key) it.copy(st = st) else it }
            }
        } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { note(x) }
    }

    companion object {
        /** a finger held this long before it moves or lifts is desktop's right button (Android's long-press timeout) */
        const val LONG_PRESS_MS = 400L
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which list a picker shows. */
enum class LensPick { CAMERA, LENS }

/** The three lists of the panel: the parameter each writes (set_value) and what the sheet calls it. */
enum class LensValueKind(val param: String, val title: String, val unit: String) {
    FOCAL("focal", "focal length", "mm"),
    APERTURE("aperture", "aperture", "f/"),
    DISTANCE("distance", "distance", "m");

    /** a value as the panel shows it: 100 mm, f/2.8, 2.5 m */
    fun show(v: Double): String = if (this == APERTURE) "f/${LensValues.text(v)}" else "${LensValues.text(v)} $unit"
}

/** What the lens panel and its pickers show (brief P6d). */
data class LensUi(
    /** get_state of [stateKey]'s module; null until read */
    val state: LensState? = null,
    val stateKey: InstanceKey? = null,
    /** the open picker's list is the auto-search's short list (desktop's "find"), not every entry */
    val found: Boolean = false,
    val cameras: List<LensCamera> = emptyList(),
    val lenses: List<LensInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Brief P6d: lens correction's camera and lens by hand and its three value lists, over the module actions of `lens`
 * (engine-api.md 5.4). Every choice is one history item written by the engine; nothing is kept here but what the
 * screens show.
 */
class LensByHand(private val c: DarkroomController) {
    private val _ui = MutableStateFlow(LensUi())
    val ui: StateFlow<LensUi> = _ui.asStateFlow()

    /** Lensfun's whole camera list does not change while the app runs */
    private var allCameras: List<LensCamera>? = null

    private suspend fun action(key: InstanceKey, name: String, args: JsonObject = JsonObject(emptyMap())) =
        c.engineRead { it.moduleAction(LENS_OP, key.priority, name, args) }

    /** Read what the panel shows (get_state) again, after a change or a photo. */
    fun refresh(key: InstanceKey): Job = c.launch { readState(key) }

    private suspend fun readState(key: InstanceKey) {
        val st = runCatching { LensPicker.parseState(action(key, "get_state")) }.getOrNull()
        _ui.update { it.copy(state = st ?: it.state, stateKey = key) }
    }

    /** Open a picker; [found] starts with desktop's auto-search result (the Exif camera or lens) instead of every entry. */
    fun open(key: InstanceKey, pick: LensPick, found: Boolean): Job {
        _ui.update { it.copy(found = found, loading = true, error = null, cameras = emptyList(), lenses = emptyList()) }
        c.setOverlay(Overlay.LensPicker(key, pick))
        return load(key, pick, found)
    }

    /** "show all" inside a picker opened by "find" */
    fun showAll(key: InstanceKey, pick: LensPick): Job {
        _ui.update { it.copy(found = false, loading = true, error = null) }
        return load(key, pick, false)
    }

    private fun load(key: InstanceKey, pick: LensPick, found: Boolean): Job =
        c.launch {
            try {
                when (pick) {
                    LensPick.CAMERA -> {
                        val list = if (found) LensPicker.parseCameras(action(key, "find_cameras"))
                        else allCameras ?: LensPicker.parseCameras(action(key, "list_cameras")).also { allCameras = it }
                        _ui.update { it.copy(cameras = list, loading = false) }
                    }
                    LensPick.LENS -> {
                        // desktop: lenses that fit the chosen camera (all of them when Lensfun does not know it)
                        val cam = _ui.value.state?.cameraForLensList
                        val args = buildJsonObject { if (cam != null) put("camera", cam) }
                        val list = LensPicker.parseLenses(action(key, if (found) "find_lenses" else "list_lenses", args))
                        _ui.update { it.copy(lenses = list, loading = false) }
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "the list could not be read") }
            }
        }

    private fun write(key: InstanceKey, name: String, args: JsonObject): Job =
        c.launch {
            engineChange { e ->
                e.moduleAction(LENS_OP, key.priority, name, args)
                e.historySeal()
            }
            readState(key)
        }

    /** _camera_menu_select(): the camera and its crop factor; the lens stays as it is, as on desktop. */
    fun chooseCamera(key: InstanceKey, cam: LensCamera): Job {
        c.setOverlay(null)
        return write(key, "set_camera", buildJsonObject {
            put("model", cam.model)
            put("maker", cam.maker)
            cam.variant?.let { put("variant", it) }
        })
    }

    /** _lens_menu_select(): the lens, the automatic scale, `has_been_set`. */
    fun chooseLens(key: InstanceKey, lens: LensInfo): Job {
        c.setOverlay(null)
        return write(key, "set_lens", buildJsonObject {
            put("lens", lens.model)
            put("maker", lens.maker)
        })
    }

    /** _lens_comboentry_*_update(): the value and `has_been_set`. */
    fun setValue(key: InstanceKey, kind: LensValueKind, value: Double): Job {
        c.setOverlay(null)
        return write(key, "set_value", buildJsonObject {
            put("name", kind.param)
            put("value", JsonPrimitive(value))
        })
    }

    fun openValue(key: InstanceKey, kind: LensValueKind) = c.setOverlay(Overlay.LensValue(key, kind))
}

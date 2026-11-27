// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.paramAt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File

const val BORDERS_OP = "borders"
const val WATERMARK_OP = "watermark"

/**
 * Brief P6e: a colour row's parameter: the float array [field] (`color` writes `color[0..2]`), the dialog's [title]
 * (desktop's colour button title), and the picker button next to it ([pickerControl], a schema node with a `picker`
 * override), if any.
 */
data class ColorTarget(val field: String, val label: String, val title: String, val pickerControl: Int?) {
    fun paths(): List<String> = (0..2).map { "$field[$it]" }
}

/** The schema nodes of framing and watermark the app builds by hand (brief P6e), from `schema/ui-schema.json`. */
object FramingWatermarkNodes {
    /** framing: the border colour row (label 12, colour button 13, picker 14) and the frame line row (16, 17, 18) */
    const val BORDER_ROW = 11
    const val FRAME_ROW = 15
    val BORDER = ColorTarget("color", "border color", "select border color", 14)
    val FRAME = ColorTarget("frame_color", "frame line color", "select frame line color", 18)

    /** watermark: grid 0 (marker list, text, font, colour with picker 10), grid 18 (the 3x3 alignment) */
    const val WATERMARK_GRID = 0
    const val ALIGNMENT_GRID = 18
    val WATERMARK_COLOR = ColorTarget("color", "color", "select watermark color", 10)

    fun handles(op: String, id: Int): Boolean = when (op) {
        BORDERS_OP -> id == BORDER_ROW || id == FRAME_ROW
        WATERMARK_OP -> id == WATERMARK_GRID || id == ALIGNMENT_GRID
        else -> false
    }

    /** desktop's alignment toggles, row by row: top left (0) .. bottom right (8); the default is the centre (4) */
    val ALIGNMENT_NAMES = listOf(
        "top left", "top", "top right", "left", "center", "right", "bottom left", "bottom", "bottom right",
    )
}

/** One entry of watermark's marker list (bridge `watermark list_files`). */
data class WatermarkFile(val file: String, val label: String, val svg: Boolean, val folder: String)

/** One face of a font family (bridge `watermark list_fonts`); [font] is what the parameter holds. */
data class FontFace(val family: String, val face: String, val font: String)

/** What watermark's lists show. */
data class WatermarkLists(
    val files: List<WatermarkFile> = emptyList(),
    val fonts: List<FontFace> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Brief P6e: the colour dialog's writes, its recent colours, and watermark's marker and font lists. Every change is
 * one history item made by the engine (`params_set` of three floats, a text field, or the alignment).
 */
class FramingWatermark(private val c: DarkroomController) {
    private val _lists = MutableStateFlow(WatermarkLists())
    val lists: StateFlow<WatermarkLists> = _lists.asStateFlow()

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    /** recently chosen colours, newest first ("#RRGGBB"); kept per app */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()
    private var recentList = RecentColors()

    /** where the recent colours are kept between starts (the host sets it); null keeps them for this run only */
    @Volatile var recentFile: File? = null
        set(f) {
            field = f
            recentList = runCatching { f?.takeIf { it.isFile }?.readText()?.let { RecentColors.parse(it) } }.getOrNull() ?: RecentColors()
            _recent.value = recentList.colors
        }

    /** the colour [t] of [m] as its parameters hold it; null if a channel is missing */
    fun colorOf(m: ModuleInstance, t: ColorTarget): Rgb? {
        val v = t.paths().map { (m.params.paramAt(it) as? JsonPrimitive)?.doubleOrNull ?: return null }
        return Rgb.of(v)
    }

    /** Open the colour dialog for [t] of the instance [key]. */
    fun open(key: InstanceKey, t: ColorTarget) = c.setOverlay(Overlay.ColorDialog(key, t))

    /**
     * The dialog's OK: the three floats as one history item (desktop's `_colorpick_color_set`, which first turns
     * the module's picker off). A colour that writes the same floats as now writes nothing.
     */
    fun apply(key: InstanceKey, t: ColorTarget, rgb: Rgb): Job = c.launch {
        setOverlay(null)
        rememberColor(rgb)
        val m = state.value.modules.firstOrNull { it.key == key } ?: return@launch
        if (state.value.picker?.key == key) disarmPicker()
        if (colorOf(m, t)?.asFloats() == rgb.asFloats()) return@launch
        setParams(key, t.paths().zip(rgb.channels).associate { (p, v) -> p to JsonPrimitive(v) })
    }

    fun cancel() = c.setOverlay(null)

    private fun rememberColor(rgb: Rgb) {
        recentList.add(ColorMath.hex(rgb))
        _recent.value = recentList.colors
        recentFile?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(recentList.text()) } }
    }

    // ------------------------------------------------------------------ watermark

    /** a text field of watermark (filename, text, font) as a string; the fake engine's char arrays read as "" */
    fun text(m: ModuleInstance, field: String): String = (m.params.paramAt(field) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    fun alignment(m: ModuleInstance): Int = (m.params.paramAt("alignment") as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 4

    /** Read the marker files and fonts the engine offers (each opening of the panel: desktop's refresh button). */
    fun refresh(key: InstanceKey): Job = c.launch {
        _lists.update { it.copy(loading = true, error = null) }
        try {
            val files = parseFiles(engineRead { it.moduleAction(WATERMARK_OP, key.priority, "list_files", JsonObject(emptyMap())) })
            val fonts = parseFonts(engineRead { it.moduleAction(WATERMARK_OP, key.priority, "list_fonts", JsonObject(emptyMap())) })
            _lists.update { WatermarkLists(files, fonts) }
        } catch (e: Exception) {
            _lists.update { it.copy(loading = false, error = e.message ?: "the engine gave no lists") }
        }
    }

    /** One of watermark's text fields (filename, text, font): one history item, nothing when unchanged. */
    fun setText(key: InstanceKey, field: String, value: String): Job = c.launch {
        val m = state.value.modules.firstOrNull { it.key == key } ?: return@launch
        if (text(m, field) == value) return@launch
        setParam(key, field, JsonPrimitive(value))
    }

    /** desktop's alignment toggle [i] (0..8): one history item */
    fun setAlignment(key: InstanceKey, i: Int): Job = c.launch {
        val m = state.value.modules.firstOrNull { it.key == key } ?: return@launch
        if (alignment(m) == i) return@launch
        setParam(key, "alignment", JsonPrimitive(i))
    }

    companion object {
        /** a text watermark's controls (text, font, colour) apply to .svg markers only (_text_color_font_set_sensitive) */
        fun textApplies(filename: String): Boolean {
            val dot = filename.lastIndexOf('.')
            return dot < 0 || filename.substring(dot).equals(".svg", ignoreCase = true)
        }

        fun parseFiles(e: JsonElement): List<WatermarkFile> {
            val arr = (e as? JsonObject)?.get("files") as? JsonArray ?: return emptyList()
            return arr.mapNotNull { x ->
                val o = x as? JsonObject ?: return@mapNotNull null
                val f = (o["file"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                WatermarkFile(f, (o["label"] as? JsonPrimitive)?.content ?: f, (o["svg"] as? JsonPrimitive)?.booleanOrNull ?: textApplies(f),
                    (o["folder"] as? JsonPrimitive)?.content ?: "data")
            }
        }

        fun parseFonts(e: JsonElement): List<FontFace> {
            val arr = (e as? JsonObject)?.get("families") as? JsonArray ?: return emptyList()
            return arr.flatMap { x ->
                val o = x as? JsonObject ?: return@flatMap emptyList()
                val fam = (o["family"] as? JsonPrimitive)?.content ?: return@flatMap emptyList()
                ((o["faces"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { fe ->
                    val fo = fe as? JsonObject ?: return@mapNotNull null
                    val font = (fo["font"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    FontFace(fam, (fo["face"] as? JsonPrimitive)?.content ?: "", font)
                }
            }
        }

        /** the family a font description starts with, among [faces]' families (the longest that matches), else null */
        fun familyOf(font: String, faces: List<FontFace>): String? =
            faces.map { it.family }.distinct().filter { font == it || font.startsWith("$it ") }.maxByOrNull { it.length }
    }
}

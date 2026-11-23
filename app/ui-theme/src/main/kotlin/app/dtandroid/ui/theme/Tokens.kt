// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Theme tokens (PLAN.md 3.6, ui-spec.md section 5), read from
 * `ui-theme/tokens.json`. Screens take every colour and size from here.
 */
class DtColors(t: TokenSource) {
    val bg = t.color("color.bg")
    val panel = t.color("color.panel")
    val header = t.color("color.header")
    val border = t.color("color.border")
    val surround = t.color("color.surround")
    val filmstrip = t.color("color.filmstrip")
    val selected = t.color("color.selected")
    val text = t.color("color.text")
    val textStrong = t.color("color.textStrong")
    val textBright = t.color("color.textBright")
    val textMuted = t.color("color.textMuted")
    val textDisabled = t.color("color.textDisabled")
    val textDim = t.color("color.textDim")
    val sliderTrack = t.color("color.sliderTrack")
    val sliderFill = t.color("color.sliderFill")
    val maskOverlay = t.color("color.maskOverlay")
    val scrim = t.color("color.scrim")
    val photoBackground = t.color("color.photoBackground")
    val banner = t.color("color.banner")
    /** brief P3a, crop tool (mockup B3-Crop): the picture outside the frame, the frame, its grid, its handles */
    val cropDim = t.color("color.cropDim")
    val cropFrame = t.color("color.cropFrame")
    val cropGrid = t.color("color.cropGrid")
    val cropHandle = t.color("color.cropHandle")
    val cropHandleFill = t.color("color.cropHandleFill")
    /** brief P3e, graph editors: the graph's ground and grid, the curve, a node and its fill, the histogram behind, the R, G, B channel lines */
    val curveBg = t.color("color.curveBg")
    val curveGrid = t.color("color.curveGrid")
    val curveLine = t.color("color.curveLine")
    val curveNode = t.color("color.curveNode")
    val curveNodeFill = t.color("color.curveNodeFill")
    val curveHistogram = t.color("color.curveHistogram")
    val curveRed = t.color("color.curveRed")
    val curveGreen = t.color("color.curveGreen")
    val curveBlue = t.color("color.curveBlue")
    /** brief P3c, colour picker on the photo: its area or point, drawn light over a dark outline (desktop: dashed) */
    val pickerFrame = t.color("color.pickerFrame")
    val pickerShadow = t.color("color.pickerShadow")
    /** brief P3d, masks on the photo: a shape's outline (light over a dark edge); the fill is `maskOverlay` */
    val maskLine = t.color("color.maskLine")
    val maskShadow = t.color("color.maskShadow")
    /** brief PUI2a: the accent and where it is used; the darktable skin sets each to today's colour of that use */
    val accent = t.color("color.accent")
    val accentPower = t.color("color.accentPower")
    val accentTab = t.color("color.accentTab")
    /** raised surfaces: an open module's header and the text buttons */
    val raised = t.color("color.raised")
}

class DtSizes(t: TokenSource) {
    val touch = t.dp("size.touch")
    val moduleHeader = t.dp("size.moduleHeader")
    val sliderRow = t.dp("size.sliderRow")
    val sliderTrack = t.dp("size.sliderTrack")
    val radiusControl = t.dp("radius.control")
    val radiusSheet = t.dp("radius.sheet")
    val border = t.dp("size.border")
    val icon = t.dp("size.icon")
    val iconLarge = t.dp("size.iconLarge")
    val tabIndicator = t.dp("size.tabIndicator")
    val sliderMarkerWidth = t.dp("size.sliderMarkerWidth")
    val sliderMarkerHeight = t.dp("size.sliderMarkerHeight")
    val sliderGap = t.dp("size.sliderGap")
    val checkbox = t.dp("size.checkbox")
    val infoLine = t.dp("size.infoLine")
    val groupTabs = t.dp("size.groupTabs")
    val bottomTabs = t.dp("size.bottomTabs")
    val historyRow = t.dp("size.historyRow")
    val historyNumber = t.dp("size.historyNumber")
    val sheetHeader = t.dp("size.sheetHeader")
    val sheetItem = t.dp("size.sheetItem")
    val sheetMaxHeight = t.dp("size.sheetMaxHeight")
    val menuItem = t.dp("size.menuItem")
    val panelHeader = t.dp("size.panelHeader")
    val tabletTopBar = t.dp("size.tabletTopBar")
    val tabletLeftPanel = t.dp("size.tabletLeftPanel")
    val tabletRightPanel = t.dp("size.tabletRightPanel")
    val tabletInfoLabel = t.dp("size.tabletInfoLabel")
    val filmstripPhone = t.dp("size.filmstripPhone")
    val filmstripTablet = t.dp("size.filmstripTablet")
    val thumbPhoneWidth = t.dp("size.thumbPhoneWidth")
    val thumbPhoneHeight = t.dp("size.thumbPhoneHeight")
    val thumbTabletWidth = t.dp("size.thumbTabletWidth")
    val thumbTabletHeight = t.dp("size.thumbTabletHeight")
    val thumbRing = t.dp("size.thumbRing")
    val photoPhoneHeight = t.dp("size.photoPhoneHeight")
    /** window size from which the tablet layout is used (width and height) */
    val tabletMinWidth = t.dp("size.tabletMinWidth")
    val tabletMinHeight = t.dp("size.tabletMinHeight")
    /** widest a column of text and fields gets on large screens (the LightPhotoLibrary shell's native screens) */
    val formMaxWidth = t.dp("size.formMaxWidth")
    /** brief P2f: the thin bar of a download, a sync or a photo that opens */
    val progressLine = t.dp("size.progressLine")
    /** brief P2f: the thumbnail and the height of a row of the photo list */
    val listThumb = t.dp("size.listThumb")
    val listRow = t.dp("size.listRow")
    /** brief P3a, crop tool: frame and grid lines, the corner handles (square, outlined), the side handles (bars) */
    val cropFrameLine = t.dp("size.cropFrameLine")
    val cropGridLine = t.dp("size.cropGridLine")
    val cropHandle = t.dp("size.cropHandle")
    val cropHandleLine = t.dp("size.cropHandleLine")
    val cropSideHandleLength = t.dp("size.cropSideHandleLength")
    val cropSideHandleWidth = t.dp("size.cropSideHandleWidth")
    /** brief P3e, graph editors: the tallest a graph grows (it is square, as wide as the panel), its curve and grid lines, a node's drawn size */
    val curveMaxHeight = t.dp("size.curveMaxHeight")
    val curveLine = t.dp("size.curveLine")
    val curveGridLine = t.dp("size.curveGridLine")
    val curveNode = t.dp("size.curveNode")
    /** brief P3c, colour picker: the area's line, its corner handles, the point's cross (half its length) */
    val pickerLine = t.dp("size.pickerLine")
    val pickerHandle = t.dp("size.pickerHandle")
    val pickerPoint = t.dp("size.pickerPoint")
    /** brief P3d: a shape's outline on the photo, its point handles, the mask indicator in a module header, an add-shape button */
    val maskLine = t.dp("size.maskLine")
    val maskHandle = t.dp("size.maskHandle")
    val maskIndicator = t.dp("size.maskIndicator")
    val shapeButton = t.dp("size.shapeButton")
    /** brief P3d stage 2b: the markers of a four-marker range slider (a diamond) and the line of an open one */
    val rangeMarker = t.dp("size.rangeMarker")
    val rangeMarkerLine = t.dp("size.rangeMarkerLine")
    /** brief PUI2a: gap between modules (a bar in the page colour), the hairline between them, the round slider thumb */
    val moduleGap = t.dp("size.moduleGap")
    val moduleSeparator = t.dp("size.moduleSeparator")
    val sliderThumbRound = t.dp("size.sliderThumbRound")
}

class DtSpace(t: TokenSource) {
    /** no space: for code that needs an explicit zero (paddings, offsets) */
    val none: Dp = 0.dp
    val xxs = t.dp("space.xxs")
    val xs = t.dp("space.xs")
    val s = t.dp("space.s")
    val m = t.dp("space.m")
    val l = t.dp("space.l")
    val xl = t.dp("space.xl")
    val panel = t.dp("space.panel")
    val xxl = t.dp("space.xxl")
    val xxxl = t.dp("space.xxxl")
    val menuIndent = t.dp("space.menuIndent")
}

class DtTextSizes(t: TokenSource) {
    val body: TextUnit = t.sp("type.body")
    val label: TextUnit = t.sp("type.label")
    val small: TextUnit = t.sp("type.small")
    val tiny: TextUnit = t.sp("type.tiny")
    val moduleName: TextUnit = t.sp("type.moduleName")
}

/** Component styles chosen by name in the tokens (brief PUI2a). */
class DtShapes(t: TokenSource) {
    /** `marker` (a triangle under the track) or `round` (a circle on it, in the accent colour) */
    val sliderThumbRound = t.choice("shape.sliderThumb", SLIDER_THUMBS) == "round"
    /** `tabular`: values with tabular figures */
    val tabularValues = t.choice("shape.valueFigures", VALUE_FIGURES) == "tabular"

    companion object {
        val SLIDER_THUMBS = setOf("marker", "round")
        val VALUE_FIGURES = setOf("proportional", "tabular")
    }
}

class DtAlpha(t: TokenSource) {
    /** history steps above the current one */
    val dimmed: Float = t.number("alpha.dimmed").toFloat()
    /** colour stops of a slider's track: desktop paints them at 0.4 (bauhaus.c, _draw_baseline) */
    val sliderGradient: Float = t.number("alpha.sliderGradient").toFloat()
    /** brief P3d: the mask colour over the photo while a shape is drawn or shown (the mockup's 0.45) */
    val maskFill: Float = t.number("alpha.maskFill").toFloat()
}

/**
 * Brief PUI3a: the darkroom's l1 phone layout. [layout] is true in the `l1` skin (token `shape.darkroom`); the
 * other values are the layout's own sizes, colours and fade times. The other skins carry defaults for them.
 */
class DtL1(t: TokenSource) {
    /** `panels` (the darktable and modern phone layout) or `l1` */
    val layout = t.choice("shape.darkroom", DARKROOMS) == "l1"
    /** the soft shade under the top bar that floats over the photo */
    val shade = t.color("color.l1Shade")
    /** the `module · control · value` pill while a slider is dragged */
    val pill = t.color("color.l1Pill")
    val pillHeight = t.dp("size.l1Pill")
    /** upright: the panel's height cap; sideways: its width */
    val panelMaxHeight = t.dp("size.l1PanelMaxHeight")
    val panelWidth = t.dp("size.l1PanelWidth")
    /** opacity of the panel's colour */
    val panelAlpha: Float = t.number("alpha.l1Panel").toFloat()
    val toolsRow = t.dp("size.l1ToolsRow")
    val sectionsRow = t.dp("size.l1SectionsRow")
    /** sideways: the width of the sections column and of the tools column */
    val sideColumn = t.dp("size.l1SideColumn")
    /** the short mark under a group that holds an enabled, non-default module */
    val sectionMark = t.dp("size.l1SectionMark")
    val sectionMarkWidth = t.dp("size.l1SectionMarkWidth")
    /** while a slider is dragged: how long the chrome takes to fade out and to come back, in milliseconds */
    val fadeOutMs: Int = t.number("time.l1FadeOut").toInt()
    val fadeInMs: Int = t.number("time.l1FadeIn").toInt()
    /** up to this many sections the row shows labels only; with more, icons and short labels */
    val sectionsFull: Int = t.number("count.l1SectionsFull").toInt()

    companion object {
        val DARKROOMS = setOf("panels", "l1")
    }
}

/** All tokens of one palette. */
class DtTokens(source: TokenSource) {
    val colors = DtColors(source)
    val sizes = DtSizes(source)
    val space = DtSpace(source)
    val text = DtTextSizes(source)
    val alpha = DtAlpha(source)
    val shapes = DtShapes(source)
    val l1 = DtL1(source)
    /** brief P3e-b: the band editor */
    val band = DtBand(source)
    /** names in an override file that are not tokens (reported, otherwise ignored) */
    val unknownOverrides: List<String> = source.unknownOverrides

    companion object {
        /** The tokens packed into the app (ui-theme/tokens.json) with an optional override file's text on top. */
        fun load(overrideJson: String? = null, loader: ClassLoader = DtTokens::class.java.classLoader): DtTokens =
            load(Skin.DARKTABLE, overrideJson, loader)

        /**
         * Brief PUI2a: the tokens of a skin (its files on top of tokens.json, in order; brief PUI3a: `l1` stacks two),
         * then an override file's text.
         */
        fun load(skin: Skin, overrideJson: String? = null, loader: ClassLoader = DtTokens::class.java.classLoader): DtTokens {
            val base = loader.getResourceAsStream("dtandroid/theme/tokens.json")?.use { it.readBytes().decodeToString() }
                ?: throw TokenException("tokens.json is not packed into the app")
            val layers = skin.files.map { f ->
                loader.getResourceAsStream("dtandroid/theme/$f")?.use { it.readBytes().decodeToString() }
                    ?: throw TokenException("$f is not packed into the app")
            } + listOfNotNull(overrideJson)
            return DtTokens(TokenSource.parse(base, layers))
        }
    }
}

/**
 * The interface skins (brief PUI2a); [key] is the stored value of the app setting `interface`, [files] the token
 * files loaded on top of tokens.json, in order, and [label] the name the settings screen shows.
 */
enum class Skin(val key: String, val files: List<String>, val label: String) {
    DARKTABLE("darktable", emptyList(), "darktable"),
    MODERN("modern", listOf("modern-tokens.json"), "modern"),
    /** brief PUI3a: the modern skin's tokens and l1-tokens.json; the darkroom's second phone layout */
    L1("l1", listOf("modern-tokens.json", "l1-tokens.json"), "lightcraft-style");

    companion object {
        fun fromKey(k: String?): Skin = entries.firstOrNull { it.key == k } ?: DARKTABLE
    }
}

class TokenException(message: String) : Exception(message)

/** Token values by name: the base file, then an override file's values on top. */
class TokenSource private constructor(private val values: Map<String, JsonPrimitive>, val unknownOverrides: List<String>) {

    fun color(name: String): Color = parseColor(name, string(name))

    fun dp(name: String): Dp = number(name).toFloat().dp

    fun sp(name: String): TextUnit = number(name).toFloat().sp

    fun number(name: String): Double {
        val v = values[name] ?: throw TokenException("token $name missing")
        if (v.isString) throw TokenException("token $name must be a number")
        return v.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: throw TokenException("token $name must be a number >= 0")
    }

    /** a token whose value is one of [allowed] words */
    fun choice(name: String, allowed: Set<String>): String {
        val v = string(name)
        if (v !in allowed) throw TokenException("token $name must be one of ${allowed.sorted().joinToString()}")
        return v
    }

    private fun string(name: String): String {
        val v = values[name] ?: throw TokenException("token $name missing")
        if (!v.isString) throw TokenException("token $name must be a colour string")
        return v.content
    }

    companion object {
        fun parse(baseJson: String, overrideJson: String? = null): TokenSource = parse(baseJson, listOfNotNull(overrideJson))

        /** the base file, then each override file's values on top, in order */
        fun parse(baseJson: String, overrides: List<String>): TokenSource {
            val base = tokens(baseJson)
            val over = LinkedHashMap<String, JsonPrimitive>()
            for (o in overrides) over.putAll(tokens(o))
            val unknown = over.keys.filter { it !in base }.sorted()
            for ((k, v) in over) {
                val b = base[k] ?: continue
                if (b.isString != v.isString) throw TokenException("override of $k has the wrong kind of value")
                if (v.isString && !isChoice(k)) parseColor(k, v.content)
            }
            return TokenSource(base + over.filterKeys { it in base }, unknown)
        }

        /** names that hold a word, not a colour */
        private fun isChoice(name: String) = name.startsWith("shape.")

        private fun tokens(json: String): Map<String, JsonPrimitive> {
            val o = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw TokenException("not a JSON object: ${e.message}")
            }
            return o.filterKeys { it != "comment" }.mapValues { (k, v) ->
                v as? JsonPrimitive ?: throw TokenException("token $k must be a string or a number")
            }
        }

        fun parseColor(name: String, s: String): Color {
            val hex = s.removePrefix("#")
            val argb = when (hex.length) {
                6 -> hex.toLongOrNull(16)?.let { it or 0xFF000000L }
                8 -> hex.toLongOrNull(16)
                else -> null
            } ?: throw TokenException("token $name: \"$s\" is not #rrggbb or #aarrggbb")
            return Color(argb.toInt())
        }
    }
}

internal fun JsonObject.keysWithout(k: String) = keys - k

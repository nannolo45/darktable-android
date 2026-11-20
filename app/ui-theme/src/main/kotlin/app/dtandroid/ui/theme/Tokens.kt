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
    /** brief P3c, colour picker on the photo: its area or point, drawn light over a dark outline (desktop: dashed) */
    val pickerFrame = t.color("color.pickerFrame")
    val pickerShadow = t.color("color.pickerShadow")
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
    /** brief P3c, colour picker: the area's line, its corner handles, the point's cross (half its length) */
    val pickerLine = t.dp("size.pickerLine")
    val pickerHandle = t.dp("size.pickerHandle")
    val pickerPoint = t.dp("size.pickerPoint")
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
}

class DtAlpha(t: TokenSource) {
    /** history steps above the current one */
    val dimmed: Float = t.number("alpha.dimmed").toFloat()
    /** colour stops of a slider's track: desktop paints them at 0.4 (bauhaus.c, _draw_baseline) */
    val sliderGradient: Float = t.number("alpha.sliderGradient").toFloat()
}

/** All tokens of one palette. */
class DtTokens(source: TokenSource) {
    val colors = DtColors(source)
    val sizes = DtSizes(source)
    val space = DtSpace(source)
    val text = DtTextSizes(source)
    val alpha = DtAlpha(source)
    /** names in an override file that are not tokens (reported, otherwise ignored) */
    val unknownOverrides: List<String> = source.unknownOverrides

    companion object {
        /** The tokens packed into the app (ui-theme/tokens.json) with an optional override file's text on top. */
        fun load(overrideJson: String? = null, loader: ClassLoader = DtTokens::class.java.classLoader): DtTokens {
            val base = loader.getResourceAsStream("dtandroid/theme/tokens.json")?.use { it.readBytes().decodeToString() }
                ?: throw TokenException("tokens.json is not packed into the app")
            return DtTokens(TokenSource.parse(base, overrideJson))
        }
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

    private fun string(name: String): String {
        val v = values[name] ?: throw TokenException("token $name missing")
        if (!v.isString) throw TokenException("token $name must be a colour string")
        return v.content
    }

    companion object {
        fun parse(baseJson: String, overrideJson: String? = null): TokenSource {
            val base = tokens(baseJson)
            val over = overrideJson?.let { tokens(it) } ?: emptyMap()
            val unknown = over.keys.filter { it !in base }.sorted()
            for ((k, v) in over) {
                val b = base[k] ?: continue
                if (b.isString != v.isString) throw TokenException("override of $k has the wrong kind of value")
                if (v.isString) parseColor(k, v.content)
            }
            return TokenSource(base + over.filterKeys { it in base }, unknown)
        }

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

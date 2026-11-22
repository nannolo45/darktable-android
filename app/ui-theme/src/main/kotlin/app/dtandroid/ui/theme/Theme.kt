// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.PlatformTextStyle

/** Text styles built from the tokens (system font, Roboto on Android). */
class DtTextStyles(t: DtTokens) {
    private fun base(size: androidx.compose.ui.unit.TextUnit) = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = size,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    val body = base(t.text.body)
    val label = base(t.text.label)
    val small = base(t.text.small)
    val tiny = base(t.text.tiny)
    /** module names in the top bar and sheet titles: body size, medium weight */
    val title = body.copy(fontWeight = FontWeight.Medium)
    /** history step numbers */
    val number = label.copy(fontFamily = FontFamily.Monospace)
    /** module names in the module headers (brief PUI2a: its own size token) */
    val moduleName = base(t.text.moduleName)
    /** slider values: tabular figures when the skin asks for them (brief PUI2a) */
    val value = if (t.shapes.tabularValues) label.copy(fontFeatureSettings = "tnum") else label
}

private val LocalDtTokens = staticCompositionLocalOf<DtTokens> { error("DtTheme is not set up") }
private val LocalDtTextStyles = staticCompositionLocalOf<DtTextStyles> { error("DtTheme is not set up") }

/** Provides the tokens to everything inside. */
@Composable
fun DtTheme(tokens: DtTokens, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDtTokens provides tokens,
        LocalDtTextStyles provides DtTextStyles(tokens),
        content = content,
    )
}

/** Access to the current tokens: `DtTheme.colors.bg`, `DtTheme.sizes.touch`, `DtTheme.type.body`. */
object DtTheme {
    val tokens: DtTokens
        @Composable @ReadOnlyComposable get() = LocalDtTokens.current
    val colors: DtColors
        @Composable @ReadOnlyComposable get() = LocalDtTokens.current.colors
    val sizes: DtSizes
        @Composable @ReadOnlyComposable get() = LocalDtTokens.current.sizes
    val space: DtSpace
        @Composable @ReadOnlyComposable get() = LocalDtTokens.current.space
    val shapes: DtShapes
        @Composable @ReadOnlyComposable get() = LocalDtTokens.current.shapes
    val alpha: DtAlpha
        @Composable @ReadOnlyComposable get() = LocalDtTokens.current.alpha
    /** brief PUI3a: the l1 layout's tokens */
    val l1: DtL1
        @Composable @ReadOnlyComposable get() = LocalDtTokens.current.l1
    val type: DtTextStyles
        @Composable @ReadOnlyComposable get() = LocalDtTextStyles.current
}

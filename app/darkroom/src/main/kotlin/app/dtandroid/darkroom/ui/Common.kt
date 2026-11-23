// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/** One line of text in the theme's style; long text ends in an ellipsis rather than wrapping. */
@Composable
fun DtText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    /** brief P2f: file names are shortened in the middle, so the number at their end stays visible */
    middle: Boolean = false,
) {
    BasicText(
        text = text,
        style = style.copy(color = color),
        modifier = modifier,
        maxLines = if (middle) 1 else maxLines,
        overflow = if (middle) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
    )
}

/** A square icon button with a touch target of `size.touch`. */
@Composable
fun DtIconButton(
    icon: DtIcon,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = DtTheme.sizes.icon,
    /** keep [tint] when disabled, for icons that show a state (the power icon of a read-only photo) */
    keepTint: Boolean = false,
) {
    val c = DtTheme.colors
    Box(
        modifier = modifier
            .size(DtTheme.sizes.touch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        DtIconImage(icon, null, if (enabled || keepTint) tint else c.textDisabled, iconSize)
    }
}

/** darktable's flat text button: header grey, small radius, `size.touch` high. */
@Composable
fun DtTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    /** brief P2f: less padding, where a title needs the room (the phone's top bar) */
    compact: Boolean = false,
) {
    val c = DtTheme.colors
    // pressed feedback from tokens: the pressed button takes the selected colour (a tap is seen at once)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(DtTheme.sizes.touch)
            .clip(RoundedCornerShape(DtTheme.sizes.radiusControl))
            .background(if (selected || pressed) c.selected else c.raised)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) DtTheme.space.m else DtTheme.space.xl),
        contentAlignment = Alignment.Center,
    ) {
        DtText(label, DtTheme.type.label, when {
            !enabled -> c.textDisabled
            selected -> c.textBright
            else -> c.textStrong
        })
    }
}

/** A row of equal tabs with darktable's underline for the selected one. */
@Composable
fun <T> DtTabRow(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (T, Boolean) -> Unit,
) {
    val c = DtTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.Start) {
            for (item in items) {
                val sel = item == selected
                Column(
                    Modifier
                        .weight(1f)
                        .height(height)
                        .background(if (sel) c.header else c.bg)
                        .clickable(role = Role.Tab) { onSelect(item) },
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content(item, sel) }
                    Box(Modifier.fillMaxWidth().height(DtTheme.sizes.tabIndicator).background(if (sel) c.accentTab else c.bg))
                }
            }
        }
        Divider()
    }
}

/** A separator line in `color.border`. */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(DtTheme.sizes.border).background(DtTheme.colors.border))
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Box(modifier.width(DtTheme.sizes.border).background(DtTheme.colors.border))
}

/** A panel heading as on the tablet's left panel ("history", "image information"). */
@Composable
fun PanelHeading(text: String) {
    Box(
        Modifier.fillMaxWidth().height(DtTheme.sizes.panelHeader).background(DtTheme.colors.header)
            .padding(horizontal = DtTheme.space.panel),
        contentAlignment = Alignment.CenterStart,
    ) { DtText(text, DtTheme.type.body, DtTheme.colors.textStrong) }
}

/** The About screen's "interface" row: one button per skin, the chosen one in the selected state. */
@Composable
fun SkinChooser(chosen: app.dtandroid.ui.theme.Skin, onChoose: (app.dtandroid.ui.theme.Skin) -> Unit, modifier: Modifier = Modifier) {
    // one button per line: three labels side by side did not fit a phone's width (the third was cut off)
    Column(modifier.padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m), verticalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
        for (s in app.dtandroid.ui.theme.Skin.entries) DtTextButton(s.label, { onChoose(s) }, Modifier.fillMaxWidth(), selected = s == chosen)
    }
}

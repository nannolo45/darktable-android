// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/** Draw an icon from the registry, tinted. The only way screens draw icons. */
@Composable
fun DtIconImage(icon: DtIcon, contentDescription: String?, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(icon.drawable),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

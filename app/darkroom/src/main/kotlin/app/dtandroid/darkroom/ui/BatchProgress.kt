// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dtandroid.ui.theme.DtTheme

/**
 * One overall bar for a job of many steps (brief P2k): a [label] ("photo 3 of 12"), a bar at [fraction]
 * (0..1) or, with null, a bar of unknown length (a short piece of the track, no animation), an optional
 * [step] line under it ("downloading 40 %") and an optional [onCancel] shown as a "stop" button. The
 * photo list uses it for a batch from LightPhotoLibrary; the export screen will use it too.
 */
@Composable
fun BatchProgress(
    label: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
    step: String? = null,
    onCancel: (() -> Unit)? = null,
    cancelLabel: String = "stop",
) {
    val c = DtTheme.colors
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = listOfNotNull(label, step).joinToString(": ") }
            .padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
            Column(Modifier.weight(1f)) {
                DtText(label, DtTheme.type.body, c.textStrong)
                step?.let { DtText(it, DtTheme.type.small, c.textMuted) }
            }
            onCancel?.let { DtTextButton(cancelLabel, it) }
        }
        Box(Modifier.fillMaxWidth().padding(top = DtTheme.space.xs).height(DtTheme.sizes.progressLine).background(c.sliderTrack)) {
            Box(Modifier.fillMaxWidth(fraction?.coerceIn(0f, 1f) ?: INDETERMINATE_SHARE).height(DtTheme.sizes.progressLine).background(c.accent))
        }
    }
}

private const val INDETERMINATE_SHARE = 0.25f

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dtandroid.ui.theme.DtTheme

/**
 * Brief P3k item 6 (PLAN.md 3.7): in the LightPhotoLibrary settings, whether Android exempts the app from battery
 * optimisation, a button to the system's list, and the note about the phone makers' own settings. Stateless: the app
 * reads `PowerManager.isIgnoringBatteryOptimizations` and opens the system screen.
 */
@Composable
fun BatteryHint(exempt: Boolean, onOpenSettings: () -> Unit) {
    val c = DtTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs)) {
        DtText(
            if (exempt) "battery optimisation: off for this app (syncs can run in the background)"
            else "battery optimisation: on for this app (Android may stop syncs in the background)",
            DtTheme.type.label, c.text,
            Modifier.fillMaxWidth().semantics { contentDescription = if (exempt) "battery exempt" else "battery not exempt" },
            maxLines = 4,
        )
        DtTextButton("battery settings…", onOpenSettings, Modifier.padding(vertical = DtTheme.space.s))
        DtText(
            "Some phones also need \"allow background activity\" or \"auto-launch\" turned on for this app in their own settings.",
            DtTheme.type.label, c.textMuted, Modifier.fillMaxWidth(), maxLines = 6,
        )
    }
}

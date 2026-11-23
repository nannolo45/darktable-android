// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.ui.SkinChooser
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The About screen's "interface" row in all three skins with each choice selected (owner report: the chosen
 * skin was not visible in modern and lightcraft-style). Written to build/outputs/screenshots as
 * "skinrow-<skin>-<chosen>.png"; also checks that the selected colour differs from the raised colour in every skin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h200dp-port-xhdpi")
class SkinChooserScreenshots {
    @get:Rule val compose = createComposeRule()
    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }

    @Test fun everySkinEveryChoice() {
        var skin by mutableStateOf(Skin.DARKTABLE)
        var chosen by mutableStateOf(Skin.DARKTABLE)
        var tapped: Skin? = null
        var selected: Any? = null
        var raised: Any? = null
        compose.setContent {
            DtTheme(DtTokens.load(skin)) {
                selected = DtTheme.colors.selected; raised = DtTheme.colors.raised
                Column(Modifier.fillMaxWidth().background(DtTheme.colors.panel)) { SkinChooser(chosen, { tapped = it; chosen = it }) }
            }
        }
        for (s in Skin.entries) {
            skin = s
            compose.waitForIdle()
            assertNotEquals("${s.key}: selected must differ from raised", selected, raised)
            for (choice in Skin.entries) {
                compose.onNodeWithText(choice.label).performClick()
                compose.waitForIdle()
                assertEquals(choice, tapped)
                compose.onRoot().captureRoboImage(File(outDir, "skinrow-${s.key}-${choice.key}.png").path)
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.ui.BatteryHint
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Brief P3k item 6: the battery optimisation line of the LightPhotoLibrary settings, exempt and not exempt (phone). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
class P3kBatteryHintScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }

    private fun show(exempt: Boolean, onOpen: () -> Unit = {}) {
        compose.setContent {
            DtTheme(DtTokens.load()) {
                Box(Modifier.fillMaxWidth().background(DtTheme.colors.bg)) { BatteryHint(exempt, onOpen) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun exempt() {
        show(true)
        compose.onNodeWithContentDescription("battery exempt").assertExists()
        compose.onRoot().captureRoboImage(File(outDir, "phone-p3k-01-battery-exempt.png").path)
    }

    @Test fun notExempt() {
        var opened = 0
        show(false) { opened++ }
        compose.onNodeWithContentDescription("battery not exempt").assertExists()
        compose.onNodeWithText("battery settings…").performClick()
        assertEquals(1, opened)
        compose.onRoot().captureRoboImage(File(outDir, "phone-p3k-02-battery-not-exempt.png").path)
    }
}

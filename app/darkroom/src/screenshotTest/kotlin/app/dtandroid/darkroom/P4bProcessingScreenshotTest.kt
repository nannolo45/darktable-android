// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.speed.ProcessingResources
import app.dtandroid.darkroom.ui.ProcessingScreen
import app.dtandroid.darkroom.ui.ProcessingUi
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Brief P4b deliverable 5: Settings › processing in the three skins: the GPU switch, processing memory, "measure speed" and "copy". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
class P4bProcessingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }

    private val result = """
        dt-android speed (brief P4b): public test RAW mire1.cr2 (darktable-tests), measured on this device
        app: 0.4.0-p4b
        fit = whole photo in 2400 x 1080; 100% = 1080 x 1080 at 100 %; heavy = default + diffuse + bilat + channelmixerrgb + sigmoid
        times in ms: first render after opening (RAW decode included) | 3 renders after an exposure change | median
        cpu default fit  first    812 | 240 236 251 | median 240
        cpu default 100% first    655 | 180 176 182 | median 180
        cpu heavy   fit  first   3120 | 2410 2398 2405 | median 2405
        cpu heavy   100% first   2210 | 1702 1688 1710 | median 1702
    """.trimIndent()

    private fun shot(tokens: DtTokens, name: String, ui: ProcessingUi) {
        var copied = 0; var measured = 0; var gpu: Boolean? = null; var memory: ProcessingResources.Choice? = null
        compose.setContent {
            DtTheme(tokens) { ProcessingScreen(ui, { gpu = it }, { memory = it }, { measured++ }, { copied++ }, {}) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
        if (!ui.busy) {
            compose.onNodeWithText("copy").performClick()
            assertEquals(if (ui.speedText != null) 1 else 0, copied)
            compose.onNodeWithText("measure speed").performClick()
            assertEquals(1, measured)
            compose.onNodeWithText("on").performClick()
            assertEquals(true, gpu)
            compose.onNodeWithText("more").performClick()
            assertEquals(ProcessingResources.Choice.MORE, memory)
        }
    }

    private val done = ProcessingUi(gpu = false, gpuNote = "no OpenCL library was found on this phone",
        memoryNow = "12 GB and more phone, processing memory normal: darktable resource level default (384 8 32 700), pipeline caches 1/8 and 1/32, 3 decoded photos kept",
        speedText = result)

    @Test fun darktable() = shot(DtTokens.load(), "phone-darktable-p4b-01-processing", done)
    @Test fun modern() = shot(DtTokens.load(Skin.MODERN), "phone-modern-p4b-01-processing", done)
    @Test fun l1() = shot(DtTokens.load(Skin.L1), "phone-l1-p4b-01-processing", done)

    @Test
    fun busyWhileMeasuring() {
        val ui = ProcessingUi(gpu = true, gpuNote = "GPU on: QUALCOMM Adreno(TM) 650; the test picture agrees with the CPU's: PSNR 52.3 dB",
            memory = ProcessingResources.Choice.MORE, memoryPending = true, speedRunning = "gpu, heavy edit, fit")
        shot(DtTokens.load(), "phone-darktable-p4b-02-processing-busy", ui)
        compose.onNodeWithContentDescription("speed running").assertExists()
        compose.onNodeWithContentDescription("memory pending").assertExists()
        compose.onNodeWithText("measure speed").assertIsNotEnabled()
        compose.onNodeWithText("copy").assertIsNotEnabled()
    }
}

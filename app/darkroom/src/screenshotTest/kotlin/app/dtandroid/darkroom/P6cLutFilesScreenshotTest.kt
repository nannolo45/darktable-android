// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.ui.LutFilesScreen
import app.dtandroid.darkroom.ui.LutFilesUi
import app.dtandroid.lpl.LutFile
import app.dtandroid.lpl.LutSyncState
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

/**
 * Brief P6c: Settings › LUT files with the download option off (default): the switch, the list of files LightPhotoLibrary
 * has and the phone lacks with "Get" and "Get all", and a file with "newer version in LightPhotoLibrary" and "Update".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
class P6cLutFilesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }

    private val ui = LutFilesUi(
        files = listOf(LutFile("film/warm.cube", 1_200_000, null), LutFile("hald/identity.png", 2_400_000, null)),
        totalBytes = 3_600_000,
        states = mapOf("film/warm.cube" to LutSyncState.NEWER_IN_LPL, "hald/identity.png" to LutSyncState.SYNCED,
            "film/cool.cube" to LutSyncState.ONLY_LPL, "kodak/2383.3dl" to LutSyncState.ONLY_LPL),
        synced = true, autoDownload = false,
        missing = listOf("film/cool.cube" to 900_000L, "kodak/2383.3dl" to 1_800_000L),
    )

    private fun shot(tokens: DtTokens, name: String) {
        val got = ArrayList<String>(); var all = 0; var toggled: Boolean? = null
        compose.setContent {
            DtTheme(tokens) { LutFilesScreen(ui, {}, { _, _ -> }, {}, {}, {}, onGet = { got += it }, onGetAll = { all++ }, onAutoDownload = { toggled = it }) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
        compose.onNodeWithText("Update").performClick()
        compose.onNodeWithText("Get all (2 files, 2.7 MB)").performClick()
        compose.onNodeWithText("Download LUT files from LightPhotoLibrary automatically").performClick()
        assertEquals(listOf("film/warm.cube"), got)
        assertEquals(1, all)
        assertEquals(true, toggled)
    }

    @Test fun darktable() = shot(DtTokens.load(), "phone-darktable-p6c-01-lut-files")
    @Test fun modern() = shot(DtTokens.load(Skin.MODERN), "phone-modern-p6c-01-lut-files")
    @Test fun l1() = shot(DtTokens.load(Skin.L1), "phone-l1-p6c-01-lut-files")
}

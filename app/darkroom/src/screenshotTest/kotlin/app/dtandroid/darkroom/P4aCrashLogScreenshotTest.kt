// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.robust.CrashEntry
import app.dtandroid.darkroom.robust.CrashLog
import app.dtandroid.darkroom.ui.CrashLogScreen
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

/** Brief P4a deliverable 5: the crash log screen (Settings › About › crash log) in the three skins, with entries and empty. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
class P4aCrashLogScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }

    private val entries = listOf(
        CrashEntry(1_764_032_000_000, CrashLog.KIND_BRIDGE, "IO: cannot decode IMG_0412.RAF", "0.4.0-p4a"),
        CrashEntry(1_764_035_600_000, CrashLog.KIND_LOW_MEMORY, "as Android recorded it at the end of the run\nmemory at the end: 1840 MB (PSS), 1990 MB (RSS); the app was in the background", "0.4.0-p4a"),
        CrashEntry(1_764_039_200_000, CrashLog.KIND_CRASH,
            "thread \"main\"\njava.lang.IllegalStateException: example\n\tat app.dtandroid.darkroom.DarkroomController.open(DarkroomController.kt:120)\n" +
                "\tat app.dtandroid.real.AppHost.open(AppHost.kt:88)\n\tat kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)\n" +
                "\tat android.os.Handler.handleCallback(Handler.java:958)\n\tat android.os.Looper.loop(Looper.java:294)\n" +
                "\tat android.app.ActivityThread.main(ActivityThread.java:8177)", "0.4.0-p4a"),
    )

    private fun shot(tokens: DtTokens, name: String) {
        var copied = 0
        compose.setContent { DtTheme(tokens) { CrashLogScreen(entries, { copied++ }, {}, {}) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("crash log entries: 3").assertExists()
        compose.onNodeWithText("copy").performClick()
        assertEquals(1, copied)
        compose.onRoot().captureRoboImage(File(outDir, "$name-p4a-01-crash-log.png").path)
    }

    @Test fun darktable() = shot(DtTokens.load(), "phone-darktable")
    @Test fun modern() = shot(DtTokens.load(Skin.MODERN), "phone-modern")
    @Test fun l1() = shot(DtTokens.load(Skin.L1), "phone-l1")

    @Test fun clearAsksFirst() {
        var cleared = 0
        compose.setContent { DtTheme(DtTokens.load()) { CrashLogScreen(entries, {}, { cleared++ }, {}) } }
        compose.onNodeWithText("clear").performClick()
        compose.onNodeWithContentDescription("clear question").assertExists()
        compose.onRoot().captureRoboImage(File(outDir, "phone-darktable-p4a-02-crash-log-clear.png").path)
        compose.onNodeWithText("keep").performClick()
        assertEquals(0, cleared)
        compose.onNodeWithText("clear").performClick()
        compose.onNodeWithText("delete").performClick()
        assertEquals(1, cleared)
    }

    @Test fun empty() {
        compose.setContent { DtTheme(DtTokens.load(Skin.MODERN)) { CrashLogScreen(emptyList(), {}, {}, {}) } }
        compose.onNodeWithContentDescription("crash log empty").assertExists()
        compose.onRoot().captureRoboImage(File(outDir, "phone-modern-p4a-03-crash-log-empty.png").path)
    }
}

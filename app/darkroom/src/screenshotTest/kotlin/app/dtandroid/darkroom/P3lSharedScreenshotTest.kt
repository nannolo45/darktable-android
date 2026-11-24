// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.ui.PhotoList
import app.dtandroid.darkroom.ui.PhotoListScreen
import app.dtandroid.darkroom.ui.SharedBanner
import app.dtandroid.darkroom.ui.SharedConflictDialog
import app.dtandroid.darkroom.ui.SharedDeletionDialog
import app.dtandroid.darkroom.ui.SharedReviewSheet
import app.dtandroid.darkroom.ui.SharedSettingsSection
import app.dtandroid.darkroom.ui.SharedUi
import app.dtandroid.lpl.SharedConflict
import app.dtandroid.lpl.SharedKey
import app.dtandroid.lpl.SharedKind
import app.dtandroid.lpl.SharedMeta
import app.dtandroid.lpl.SharedPolicy
import app.dtandroid.lpl.SharedRow
import app.dtandroid.lpl.SharedSent
import app.dtandroid.lpl.SharedSettings
import app.dtandroid.lpl.SharedView
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Brief P3l deliverable 4: the photo list's banner and settings dot, the review sheet, the conflict and the deletion
 * dialog, and the settings section, in the three skins (phone). Interactions: ticking, opening the dialogs, choosing.
 * Example data only (no real device names).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
class P3lSharedScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }
    private val t = 1_764_000_000.0

    private fun meta(id: Long, kind: SharedKind, name: String, device: String = "Desktop", deleted: Boolean = false, rev: Int = 2) =
        SharedMeta(id, SharedKey(kind, if (kind == SharedKind.STYLE) "" else if (kind == SharedKind.LAYOUT) "modulegroups" else "exposure",
            if (kind == SharedKind.STYLE) 0 else 1, name), rev, id, "h$id", t - id * 3600, "pc-$device", device, deleted, 900)

    private val rows = listOf(
        SharedRow(meta(1, SharedKind.STYLE, "film | portra 400"), SharedRow.Change.NEW),
        SharedRow(meta(2, SharedKind.STYLE, "bw | selenium"), SharedRow.Change.CHANGED),
        SharedRow(meta(3, SharedKind.PRESET, "bright day"), SharedRow.Change.CONFLICT, SharedConflict(3, t - 600, false)),
        SharedRow(meta(4, SharedKind.PRESET, "future preset"), SharedRow.Change.UNUSABLE),
        SharedRow(meta(5, SharedKind.LAYOUT, "my workflow", device = "Laptop"), SharedRow.Change.NEW),
        SharedRow(meta(6, SharedKind.STYLE, "old look", deleted = true), SharedRow.Change.DELETED),
    )

    private fun skinned(skin: Skin, content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        compose.setContent { DtTheme(DtTokens.load(skin)) { Box(Modifier.fillMaxSize().background(DtTheme.colors.bg)) { content() } } }
        compose.waitForIdle()
    }

    @Test fun bannerAndDotOnTheList() {
        val view = SharedView(rows, t, active = true)
        val text = SharedUi.banner(view)!!
        assertEquals("5 updates to styles, presets and layouts from Desktop and Laptop", text)
        for (skin in Skin.entries) {
            var reviewed = 0
            skinned(skin) {
                PhotoListScreen(
                    title = "dt-android",
                    sections = listOf(PhotoList.Section("s", "LightPhotoLibrary", line = "this phone is \"Phone\" · connected")),
                    menu = listOf(app.dtandroid.darkroom.HostAction("LightPhotoLibrary server…") {}),
                    banner = { SharedBanner(text, { reviewed++ }, null) },
                    menuDot = true,
                )
            }
            compose.onNodeWithContentDescription("something waits").assertExists()
            compose.onNodeWithText("Review").performClick()
            assertEquals(1, reviewed)
            compose.onRoot().captureRoboImage(File(outDir, "phone-p3l-01-banner-${skin.key}.png").path)
        }
        val after = SharedUi.banner(SharedView(emptyList(), t, lastUpdate = "3 taken in · 1 still waiting", active = true))
        assertEquals("Styles, presets and layouts updated: 3 taken in · 1 still waiting", after)
        skinned(Skin.DARKTABLE) { SharedBanner(after!!, null) {} }
        compose.onNodeWithText("OK").assertExists()
        compose.onRoot().captureRoboImage(File(outDir, "phone-p3l-02-banner-after-update.png").path)
    }

    @Test fun reviewSheetTicksAndOpensTheDialogs() {
        for (skin in Skin.entries) {
            var review by mutableStateOf(SharedUi.Review(rows, take = setOf(1L, 2L, 5L)))
            val opened = ArrayList<String>()
            var updated = 0
            skinned(skin) {
                SharedReviewSheet(review, { id -> review = review.copy(take = if (id in review.take) review.take - id else review.take + id) },
                    { opened += "conflict ${it.meta.key.name}" }, { opened += "deletion ${it.meta.key.name}" }, {}, { updated++ })
            }
            compose.onNodeWithText("Update 3").assertExists()
            compose.onNodeWithText("Styles").assertExists()
            compose.onNodeWithText("Presets").assertExists()
            compose.onNodeWithText("Module layouts").assertExists()
            compose.onNodeWithText("Deleted elsewhere").assertExists()
            compose.onNodeWithText("needs a newer darktable").assertExists()
            compose.onRoot().captureRoboImage(File(outDir, "phone-p3l-03-review-${skin.key}.png").path)
            compose.onNodeWithContentDescription("shared film | portra 400", substring = true).performClick()
            compose.onNodeWithText("Update 2").assertExists()
            compose.onNodeWithText("changed here too ›").performClick()
            compose.onNodeWithText("Decide ›").performClick()
            assertEquals(listOf("conflict bright day", "deletion old look"), opened)
            // a greyed line cannot be chosen
            compose.onNodeWithContentDescription("shared future preset", substring = true).assertIsNotEnabled()
            compose.onNodeWithText("Update 2").performClick()
            assertEquals(1, updated)
        }
    }

    @Test fun conflictDialog() {
        for (skin in Skin.entries) {
            var same = false
            val chosen = ArrayList<String>()
            skinned(skin) { SharedConflictDialog(rows[2], "Phone", same, 2, { same = !same }, { chosen += it }, {}) }
            compose.onNodeWithText("Keep this phone's").assertExists()
            compose.onNodeWithText("Take Desktop's").assertExists()
            compose.onNodeWithText("The other copy arrives as \"bright day (Desktop)\".").assertExists()
            compose.onRoot().captureRoboImage(File(outDir, "phone-p3l-04-conflict-${skin.key}.png").path)
            compose.onNodeWithContentDescription("same choice for the other conflicts").performClick()
            assertTrue(same)
            compose.onNodeWithText("Keep both").performClick()
            assertEquals(listOf("both"), chosen)
        }
        // the other side deleted the item: no "keep both"
        skinned(Skin.DARKTABLE) { SharedConflictDialog(rows[2].copy(meta = rows[2].meta.copy(deleted = true)), "Phone", false, 0, {}, {}, {}) }
        compose.onNodeWithText("Keep both").assertDoesNotExist()
    }

    @Test fun deletionDialog() {
        for (skin in Skin.entries) {
            val chosen = ArrayList<String>()
            skinned(skin) { SharedDeletionDialog(rows[5], { chosen += "delete" }, { chosen += "keep" }, { chosen += "later" }) }
            compose.onNodeWithText("Deleted on Desktop", substring = true).assertExists()
            compose.onNodeWithText("A kept item stays on this phone only", substring = true).assertExists()
            compose.onRoot().captureRoboImage(File(outDir, "phone-p3l-05-deletion-${skin.key}.png").path)
            compose.onNodeWithText("Delete here too").performClick()
            compose.onNodeWithText("Keep on this phone").performClick()
            compose.onNodeWithText("Decide later").performClick()
            assertEquals(listOf("delete", "keep", "later"), chosen)
        }
    }

    @Test fun settingsSection() {
        for (skin in Skin.entries) {
            var v by mutableStateOf(SharedSettings.Values())
            var reviewed = 0
            val view = SharedView(rows, t, sent = listOf(SharedSent("film | portra 400", SharedKind.STYLE, "changed", t - 60)), active = true)
            skinned(skin) {
                Column(Modifier.verticalScroll(rememberScrollState())) { SharedSettingsSection(v, view, true, { v = it }, { reviewed++ }) }
            }
            compose.onNodeWithText("5 waiting", substring = true).assertExists()
            compose.onRoot().captureRoboImage(File(outDir, "phone-p3l-06-settings-${skin.key}.png").path)
            compose.onNodeWithText("Newest wins").performClick()
            assertEquals(SharedPolicy.NEWEST, v.policy)
            compose.onNodeWithText("Review").performClick()
            assertEquals(1, reviewed)
        }
    }
}

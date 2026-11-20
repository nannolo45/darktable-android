// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.rememberTextMeasurer
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.darkroom.ui.DarkroomScreen
import app.dtandroid.darkroom.ui.PhotoList
import app.dtandroid.darkroom.ui.PhotoListScreen
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Brief P2f deliverable 5: screenshots of the photo list (empty; photos in each state; a download in
 * progress) on phone and tablet, and of the darkroom's top bar at 360 dp. Written to
 * build/outputs/screenshots ("list-…", "topbar-…") for the reviewer and the owner; each test also checks
 * a few facts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ListScreenshotBase {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    abstract val prefix: String

    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }

    protected fun capture(name: String) = compose.onRoot().captureRoboImage(File(outDir, "$prefix-$name.png").path)

    /** A grey-scale gradient standing in for a thumbnail (the test set's pictures are not in this module). */
    private fun thumb(seed: Int): RenderedImage {
        val w = 96; val h = 64
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val v = ((x * 2 + y + seed * 37) % 200 + 30).toByte()
            px[i] = v; px[i + 1] = ((v.toInt() and 0xff) * 9 / 10).toByte(); px[i + 2] = ((v.toInt() and 0xff) * 8 / 10).toByte(); px[i + 3] = -1
        }
        return RenderedImage(w, h, px)
    }

    private val more = listOf(HostAction("sync now") {}, HostAction("open in LightPhotoLibrary") {}, HostAction("remove from this phone") {})

    protected fun server(rows: List<PhotoList.Row>, line: String) = PhotoList.Section(
        key = "srv", title = "LightPhotoLibrary", line = line, action = HostAction("sync now", enabled = rows.isNotEmpty()) {},
        empty = "No photos from LightPhotoLibrary yet. In LightPhotoLibrary, open a photo and tap \"Edit on this phone\".", rows = rows,
    )

    protected fun local(rows: List<PhotoList.Row>) = PhotoList.Section(
        key = "local", title = "photos from this phone's files",
        empty = "Tap \"open photos\" and pick one or several RAW files, optionally with their .xmp files. The app works on copies in its own folder; your originals are never changed.",
        rows = rows,
    )

    /** One photo in each of the contract's states, in plain words, with a download in progress. */
    protected fun allStates(): List<PhotoList.Row> = listOf(
        PhotoList.Row("1", "IMG_20261119_000123.CR2", "downloading 40 %", progress = 0.4f, thumb = null, actions = more),
        PhotoList.Row("2", "IMG_20261119_000124.CR2", "ready to edit", thumb = thumb(2), actions = more,
            lastEdit = "last edited on Desktop · 18 Nov · exposure, color balance rgb"),
        PhotoList.Row("3", "IMG_20261119_000125.CR2", "open in the darkroom", thumb = thumb(3), actions = more,
            note = "saved your open changes and sent them · as LightPhotoLibrary asked", lastEdit = "last edited on this phone · today 14:05 · exposure"),
        PhotoList.Row("4", "IMG_20261119_000126.CR2", "syncing: making the preview", progress = 0f, thumb = thumb(4), actions = more,
            lastEdit = "edited here, not synced yet"),
        PhotoList.Row("5", "IMG_20261119_000127.CR2", "waiting to sync · the server is not reachable", thumb = thumb(5), actions = more,
            lastEdit = "edited here, not synced yet"),
        PhotoList.Row("6", "IMG_20261119_000128.CR2", "synced · preview still to send", thumb = thumb(6), actions = more,
            lastEdit = "last edited on this phone · today 14:05 · exposure, sigmoid"),
        PhotoList.Row("7", "A_VERY_LONG_FILE_NAME_FROM_A_SCANNER_EXPORT_000129.DNG", "synced", thumb = thumb(7), actions = more,
            lastEdit = "changed outside LightPhotoLibrary · 2 Nov · crop"),
        PhotoList.Row("8", "IMG_20261119_000130.CR2", "error · this photo is gone from the library", thumb = thumb(8), actions = more),
    )

    protected fun showList(sections: List<PhotoList.Section>, messages: List<String> = emptyList()) {
        compose.setContent {
            DtTheme(DtTokens.load()) {
                PhotoListScreen("dt-android", sections, topActions = listOf(HostAction("open photos") {}),
                    menu = listOf(HostAction("LightPhotoLibrary server…") {}, HostAction("timings…") {}, HostAction("about") {}), messages = messages)
            }
        }
        compose.waitForIdle()
    }

    @Test fun empty() {
        showList(listOf(server(emptyList(), "this phone is \"Phone\" · connected"), local(emptyList())))
        compose.onNode(hasText("photos from this phone's files")).assertExists()
        capture("01-empty")
    }

    @Test fun photosInEachState() {
        showList(listOf(server(allStates(), "this phone is \"Phone\" · connected · 2 photos waiting to sync")
            .copy(note = "LightPhotoLibrary asked to save 1 photo: 1 photo sent"), local(emptyList())),
            messages = listOf("1 photo arrived from LightPhotoLibrary"))
        // one "more" per row, no row of buttons
        compose.onNodeWithContentDescription("more for IMG_20261119_000124.CR2").assertExists()
        capture("02-photos-in-each-state")
    }

    @Test fun moreSheet() {
        showList(listOf(server(allStates().take(3), "this phone is \"Phone\" · connected"), local(emptyList())))
        compose.onNodeWithContentDescription("more for IMG_20261119_000124.CR2").performClick()
        compose.waitForIdle()
        // "sync now" is also the server header's button: at least one node each
        for (t in listOf("sync now", "open in LightPhotoLibrary", "remove from this phone"))
            assertTrue(t, compose.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty())
        capture("03-more-sheet")
    }

    @Test fun downloadInProgress() {
        showList(listOf(server(allStates().take(1), "this phone is \"Phone\" · connected"),
            local(listOf(PhotoList.Row("l1", "PHONE_FILE_0001.DNG", "edited", thumb = thumb(9))))))
        compose.onNodeWithContentDescription("IMG_20261119_000123.CR2: downloading 40 %").assertExists()
        capture("04-download-in-progress")
    }

    /** Brief P2i: the header while "sync automatically" is off, and photos whose edit stays on the phone. */
    @Test fun autoSyncOff() {
        val rows = listOf(
            PhotoList.Row("1", "IMG_20261119_000124.CR2", PhotoList.NOT_SYNCED_HERE, thumb = thumb(2), actions = more,
                lastEdit = "last edited on Desktop · 18 Nov · exposure, color balance rgb"),
            PhotoList.Row("2", "IMG_20261119_000125.CR2", PhotoList.NOT_SYNCED_HERE, thumb = thumb(3), actions = more),
            PhotoList.Row("3", "IMG_20261119_000126.CR2", "synced", thumb = thumb(4), actions = more,
                lastEdit = "last edited on this phone · today 14:05 · exposure"),
        )
        val line = PhotoList.serverLine("Phone", "connected", 2, autoSync = false)
        assertTrue(line, line.contains(PhotoList.AUTO_SYNC_OFF))
        showList(listOf(server(rows, line), local(emptyList())))
        compose.onNode(hasText(line)).assertExists()
        capture("05-auto-sync-off")
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListPhoneScreenshots : ListScreenshotBase() { override val prefix = "list-phone" }

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListTabletScreenshots : ListScreenshotBase() { override val prefix = "list-tablet" }

/**
 * The darkroom's top bar on a 360 dp phone with a long file name: back, title (shortened in the middle),
 * "before", "more" (an icon), "done". The title must have room for at least the first letters and the
 * last four characters of the name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopBarScreenshots {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }

    private fun topBar(name: String, file: String, whole: Boolean = false) {
        val (c, _) = TestSetup.controller(tmp.root)
        val item = c.state.value.current!!
        c.replaceItem(0, item.copy(fileName = name))
        var needed = 0
        compose.setContent {
            DtTheme(DtTokens.load()) {
                // the narrowest the title may show: three letters, the ellipsis, the last four characters
                val tm = rememberTextMeasurer()
                needed = tm.measure(if (whole) name else name.take(3) + "…" + name.takeLast(4), DtTheme.type.title).size.width
                DarkroomScreen(c, onBack = {}, onDone = {}, layout = DarkroomLayout.PHONE,
                    actions = listOf(HostAction("export JPEG…") {}, HostAction("timings…") {}))
            }
        }
        compose.waitForIdle()
        runBlocking { c.renderNow() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("more").assertExists()
        val w = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().size.width
        assertTrue("title is $w px wide, needs $needed px", w >= needed)
        compose.onRoot().captureRoboImage(File(outDir, "topbar-$file.png").path)
    }

    /** a 14-character name (camera names are usually 12) is shown whole at 360 dp */
    @Test fun shortName() = topBar("PHOTO04261.RAW", "360dp-short-name", whole = true)

    @Test fun longName() = topBar("IMG_20261119_LONG_EXPORT_NAME_04261.ARW", "360dp-long-name")
}

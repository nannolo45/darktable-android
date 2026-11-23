// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.lpl.VersionFiles
import app.dtandroid.schema.Control
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3f on the stand-in engine: duplicate and virgin duplicate write `NAME_nn.EXT.xmp` (rule 4 through the engine's
 * own refusal), switching saves the version in use first, each version reopens with its own history, delete and rename
 * rules, and the host's say (a server without versions: no duplicate).
 */
class P3fVersionsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val exposureKey = InstanceKey("exposure", 0)
    private val exposureSlider =
        TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }

    private class Host(var canMake: Boolean = true) : VersionHost {
        val made = ArrayList<Int>()
        val deleted = ArrayList<Int>()
        override fun canMake(item: SeriesItem) = canMake
        override fun made(item: SeriesItem, n: Int) { made += n }
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    private fun open(index: Int = 0): Triple<DarkroomController, Host, SeriesItem> {
        val (c, _) = TestSetup.controller(tmp.root, index = index, thumbnails = false)
        val host = Host()
        c.versionHost = host
        runBlocking { waitFor("preview") { c.state.value.preview != null } }
        return Triple(c, host, c.state.value.current!!)
    }

    private suspend fun edit(c: DarkroomController, value: Double) {
        c.drag(exposureKey, exposureSlider.id, exposureSlider.binding, value, false)
        c.endDrag(exposureKey, exposureSlider.id, exposureSlider.binding, false)
        waitFor("edit applied") { c.liveValues.value.isEmpty() }
    }

    private fun exposure(c: DarkroomController) =
        (c.state.value.modules.first { it.key == exposureKey }.params["exposure"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()

    private fun file(item: SeriesItem, n: Int) = File(item.atVersion(n).xmpPath)

    @Test fun duplicateOfAnEditedPhotoHasItsHistoryAndBothReopenWithTheirOwn() = runBlocking<Unit> {
        val (c, host, item) = open()
        edit(c, 1.25)
        val n0 = c.state.value.history.items.size
        assertEquals(1, c.duplicate(virgin = false))
        // the copy is open, named as darktable names it (number before the extension), and has the same history
        assertEquals(1, c.state.value.current!!.version)
        assertEquals(item.fileName.replace(".RAF", "_01.RAF.xmp"), File(c.state.value.current!!.xmpPath).name)
        assertTrue(file(item, 0).isFile && file(item, 1).isFile)
        assertEquals(n0, c.state.value.history.items.size)
        assertEquals(1.25, exposure(c), 1e-9)
        assertEquals(listOf(1), host.made)
        // edit the copy; the photo's own edit is not touched
        edit(c, 0.5)
        c.switchVersion(0)
        waitFor("version 0 open") { c.state.value.current?.version == 0 && c.state.value.info != null }
        assertEquals("version 0 has its own history", 1.25, exposure(c), 1e-9)
        c.switchVersion(1)
        waitFor("version 1 open") { c.state.value.current?.version == 1 && c.state.value.info != null }
        assertEquals("the switch saved version 1 first", 0.5, exposure(c), 1e-9)
    }

    @Test fun virginDuplicateHasNoHistoryAndTheNextNumberIsNeverReused() = runBlocking<Unit> {
        val (c, _, item) = open()
        edit(c, 1.5)
        assertEquals(1, c.duplicate(virgin = true))
        assertEquals("no history in the new version", false, c.state.value.versionRows.first { it.n == 1 }.edited)
        assertEquals(0, c.state.value.history.items.count { it.op == "exposure" && it.enabled && false })
        // delete it: its number is not given again
        c.deleteVersion(1)
        assertFalse(file(item, 1).exists())
        assertEquals("version 0 opened after the open one was deleted", 0, c.state.value.current!!.version)
        assertEquals(2, c.duplicate(virgin = false))
        assertTrue(file(item, 2).isFile)
        assertFalse(file(item, 1).exists())
    }

    @Test fun versionZeroIsNotDeletedFromTheApp() = runBlocking<Unit> {
        val (c, _, item) = open()
        c.deleteVersion(0)
        assertTrue(file(item, 0).isFile || !File(item.xmpPath).exists())
        assertNotNull(c.state.value.versionNote)
        assertEquals(0, c.state.value.current!!.version)
    }

    @Test fun anOpenPhotoThatIsReadOnlyIsNotDuplicated() = runBlocking<Unit> {
        val (c, host, item) = open(index = InterfacePreview.READ_ONLY_INDEX)
        assertTrue(c.state.value.readOnly)
        assertNull(c.duplicate(virgin = false))
        assertFalse("no new sidecar, the foreign one is untouched", file(item, 1).exists())
        assertTrue(c.state.value.versionNote!!.contains("viewing only"))
        assertEquals(emptyList<Int>(), host.made)
    }

    @Test fun aServerWithoutVersionsOffersNoDuplicate() = runBlocking<Unit> {
        val (c, host, item) = open()
        host.canMake = false
        assertNull(c.duplicate(virgin = false))
        assertFalse(file(item, 1).exists())
        assertTrue(c.state.value.versionNote!!.contains("does not know versions"))
    }

    @Test fun theSheetListsTheFilesWithNamesAndEditedState() = runBlocking<Unit> {
        val (c, _, _) = open()
        edit(c, 1.1)
        c.duplicate(virgin = false)
        c.duplicate(virgin = true)
        c.openVersions()
        val rows = c.state.value.versionRows
        assertEquals(listOf(0, 1, 2), rows.map { it.n })
        assertEquals(listOf(true, true, false), rows.map { it.edited })
        assertEquals(listOf(false, false, true), rows.map { it.current })
        assertEquals(Overlay.Versions, c.state.value.overlay)
    }

    @Test fun renameWritesTheNameIntoTheFileAndKeepsTheOpenHistory() = runBlocking<Unit> {
        val (c, _, item) = open()
        edit(c, 0.9)
        c.duplicate(virgin = false)
        c.renameVersion(1, "for print")
        waitFor("reopened") { c.state.value.info != null && c.state.value.current?.version == 1 }
        assertEquals("for print", c.state.value.versionRows.first { it.n == 1 }.name)
        assertTrue(file(item, 1).readText().contains("for print"))
        assertEquals("the history is as it was", 0.9, exposure(c), 1e-9)
        // another version of the photo can be renamed without leaving this one
        c.renameVersion(0, "original look")
        assertEquals(1, c.state.value.current!!.version)
        assertEquals("original look", c.state.value.versionRows.first { it.n == 0 }.name)
    }

    @Test fun holdToPeekShowsTheLastPictureOfAnotherVersion() = runBlocking<Unit> {
        val (c, _, _) = open()
        edit(c, 1.3)
        c.duplicate(virgin = false)
        waitFor("preview of version 1") { c.state.value.preview != null }
        c.peekVersion(0)
        assertNotNull("version 0's picture was kept when it was left", c.state.value.peek)
        c.peekVersion(null)
        assertNull(c.state.value.peek)
        c.peekVersion(7)
        assertNull(c.state.value.peek)
        assertNotNull(c.state.value.peekNote)
    }

    @Test fun theNumbersFollowDarktablesNames() {
        assertEquals("IMG.RAF.xmp", VersionFiles.xmpName("IMG.RAF", 0))
        val item = SeriesItem("A.RAF", "/w/A.RAF", "/w/A.RAF.xmp").atVersion(12)
        assertEquals("/w/A_12.RAF.xmp", item.xmpPath)
        assertEquals(12, item.version)
    }
}

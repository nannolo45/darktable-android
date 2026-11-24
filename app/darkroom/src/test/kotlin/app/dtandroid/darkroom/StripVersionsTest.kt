// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The filmstrip's version badge on the stand-in engine: which photos get one, expanding and collapsing, and opening a
 * version of the open photo and of another photo.
 */
class StripVersionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    private fun open(index: Int = 0): DarkroomController {
        val (c, _) = TestSetup.controller(tmp.root, index = index, thumbnails = false)
        c.versionHost = object : VersionHost {}
        runBlocking { waitFor("preview") { c.state.value.preview != null } }
        return c
    }

    private suspend fun opened(c: DarkroomController, index: Int, version: Int) =
        waitFor("photo $index at version $version") { c.state.value.index == index && c.state.value.current?.version == version && c.state.value.info != null }

    @Test fun aPhotoWithOneVersionHasNoBadgeAndASecondVersionGivesIt() = runBlocking<Unit> {
        val c = open()
        c.refreshStripVersions()
        assertTrue(c.state.value.stripVersions.isEmpty())
        assertEquals(1, c.duplicate(virgin = false))
        val v = c.state.value.stripVersions
        assertEquals(setOf(0), v.keys)
        assertEquals(listOf(0, 1), v.getValue(0).map { it.n })
    }

    @Test fun withoutAVersionHostThereAreNoStripVersions() = runBlocking<Unit> {
        val c = open()
        c.duplicate(virgin = false)
        c.toggleStripVersions(0)
        c.versionHost = null
        c.refreshStripVersions()
        assertTrue(c.state.value.stripVersions.isEmpty())
        assertNull(c.state.value.stripExpanded)
    }

    @Test fun toggleExpandsOnePhotoAndCollapsesIt() = runBlocking<Unit> {
        val c = open()
        c.duplicate(virgin = false)
        assertNull(c.state.value.stripExpanded)
        c.toggleStripVersions(0)
        assertEquals(0, c.state.value.stripExpanded)
        c.toggleStripVersions(3)
        assertEquals("another photo's badge takes over", 3, c.state.value.stripExpanded)
        c.toggleStripVersions(3)
        assertNull(c.state.value.stripExpanded)
    }

    @Test fun anExpandedPhotoThatLosesItsVersionsCollapses() = runBlocking<Unit> {
        val c = open()
        c.duplicate(virgin = false)
        c.toggleStripVersions(0)
        c.deleteVersion(1)
        assertTrue("one version left: no badge", c.state.value.stripVersions.isEmpty())
        assertNull(c.state.value.stripExpanded)
    }

    @Test fun openingAnotherVersionOfTheOpenPhotoSwitchesIt() = runBlocking<Unit> {
        val c = open()
        c.duplicate(virgin = false)
        c.openStripVersion(0, 0)
        opened(c, 0, 0)
        assertEquals(0, c.state.value.index)
        c.openStripVersion(0, 1)
        opened(c, 0, 1)
        assertEquals(1, c.state.value.series.items[0].version)
    }

    @Test fun openingAVersionOfAnotherPhotoOpensThatPhotoAtIt() = runBlocking<Unit> {
        val c = open(index = 0)
        c.duplicate(virgin = false)          // photo 0 has versions 0 and 1; version 1 is open
        c.openPhoto(2)
        opened(c, 2, 0)
        assertEquals("photo 0 keeps the version it was left at", 1, c.state.value.series.items[0].version)
        c.openStripVersion(0, 0)
        opened(c, 0, 0)
        assertEquals(0, c.state.value.series.items[0].version)
        assertEquals(0, c.state.value.index)
        // and back to the other photo's other version
        c.openStripVersion(0, 1)
        opened(c, 0, 1)
    }

    @Test fun aVersionWithoutAFileIsNotOpenedAndSaysSo() = runBlocking<Unit> {
        val c = open(index = 0)
        c.openPhoto(2)
        opened(c, 2, 0)
        c.openStripVersion(0, 7)
        assertEquals("the open photo stays", 2, c.state.value.index)
        assertTrue(c.state.value.message!!.contains("version 7"))
        assertFalse(c.state.value.series.items[0].version == 7)
    }

    @Test fun anIndexOutsideTheSeriesDoesNothing() = runBlocking<Unit> {
        val c = open()
        c.openStripVersion(99, 1)
        assertEquals(0, c.state.value.index)
    }
}

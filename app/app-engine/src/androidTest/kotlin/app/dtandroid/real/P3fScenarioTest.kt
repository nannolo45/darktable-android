// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Series
import app.dtandroid.darkroom.SeriesItem
import app.dtandroid.darkroom.VersionHost
import app.dtandroid.darkroom.key
import app.dtandroid.schema.Control
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Brief P3f on the real engine: duplicate an edited photo, edit the copy; both sidecars exist (`NAME_01.EXT.xmp` beside
 * `NAME.EXT.xmp`) and reopen with their own history; a virgin duplicate has none; a version name survives in the
 * real darktable XMP and a reopen; the real XMP is read by the parser the app uses for names and "edited".
 */
@RunWith(AndroidJUnit4::class)
class P3fScenarioTest {
    private val exposure = InstanceKey("exposure", 0)
    private var scenario: ActivityScenario<MainActivity>? = null
    @get:org.junit.Rule val guard = HangGuard.rule(12)

    @After fun tearDown() {
        runBlocking { runCatching { AppHost.controller.closePhoto() } }
        scenario?.close()
    }

    private fun waitFor(what: String, ms: Long = 180_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(10) }
    }

    private fun exposureOf(c: DarkroomController) =
        (c.state.value.modules.first { it.key == exposure }.params["exposure"] as JsonPrimitive).content.toDouble()

    private suspend fun setExposure(c: DarkroomController, v: Double) {
        val s = AppHost.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        c.setSlider(exposure, s.binding, v, false)
    }

    @Test fun duplicateEditReopenAndRename() = runBlocking<Unit> {
        TestFiles.engine()
        val dir = File(TestFiles.ctx.filesDir, "p3f").apply { deleteRecursively(); mkdirs() }
        val raw = File(dir, TestFiles.raw.name).also { TestFiles.raw.copyTo(it); it.setWritable(false, false) }
        val item = SeriesItem(raw.name, raw.path, File(dir, raw.name + ".xmp").path)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.vm.show(Screen.DARKROOM) }
        val c = AppHost.controller
        c.versionHost = object : VersionHost {}
        c.start(Series("p3f", listOf(item)), 0, filmstrip = false, thumbnails = false)
        waitFor("preview") { c.state.value.preview != null }
        setExposure(c, 0.7)
        val e0 = exposureOf(c)
        assertEquals(1, c.duplicate(virgin = false))
        val v1 = File(dir, raw.name.substringBeforeLast('.') + "_01." + raw.name.substringAfterLast('.') + ".xmp")
        assertTrue("version 1 file ${v1.name}", v1.isFile)
        assertTrue(File(item.xmpPath).isFile)
        assertEquals("the copy has the history", e0, exposureOf(c), 1e-6)
        setExposure(c, -0.4)
        val e1 = exposureOf(c)
        c.switchVersion(0)
        waitFor("version 0") { c.state.value.current?.version == 0 && c.state.value.info != null }
        assertEquals("version 0 keeps its own", e0, exposureOf(c), 1e-6)
        c.switchVersion(1)
        waitFor("version 1") { c.state.value.current?.version == 1 && c.state.value.info != null }
        assertEquals("version 1 keeps its own", e1, exposureOf(c), 1e-6)
        // a virgin duplicate: no history; the rows read the real XMP
        assertEquals(2, c.duplicate(virgin = true))
        c.openVersions()
        val rows = c.state.value.versionRows
        assertEquals(listOf(0, 1, 2), rows.map { it.n })
        assertEquals(listOf(true, true, false), rows.map { it.edited })
        // a name survives a real XMP and a reopen
        c.renameVersion(2, "plain")
        waitFor("reopened") { c.state.value.info != null }
        assertEquals("plain", c.state.value.versionRows.first { it.n == 2 }.name)
        assertTrue(File(dir, v1.name.replace("_01", "_02")).readText().contains("plain"))
        assertFalse(File(dir, raw.name + ".xmp").readText().contains("plain"))
        c.closePhoto()
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.schema.Control
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Brief P6d (JVM): lens correction by hand on the stand-in engine (its small Lensfun): the schema nodes the hand-built
 * rows stand for, the picker's lists, one history item per choice (camera, lens, each of the three values), undo, the
 * state the panel shows before and after.
 */
class P6dLensByHandTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = InstanceKey("lens", 0)

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }
    private var lastC: DarkroomController? = null

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun param(c: DarkroomController, n: String) = c.state.value.modules.first { it.key == key }.params[n] as JsonPrimitive

    @Test fun theRowsStandForTheSchemaNodesOfDesktopsPanel() {
        val m = TestSetup.schema.modules.getValue("lens")
        fun labels(c: Control): List<String> = when (c) {
            is Control.Group -> c.children.map { (it as? Control.Unsupported)?.label ?: (it as? Control.ComboBox)?.label ?: "" }
            else -> emptyList()
        }
        // the second button of each row is an icon button on desktop (its text is the tooltip)
        assertEquals("camera model", labels(m.control(LensNodes.CAMERA_ROW)!!).first())
        assertEquals(2, labels(m.control(LensNodes.CAMERA_ROW)!!).size)
        assertEquals("lens model", labels(m.control(LensNodes.LENS_ROW)!!).first())
        assertEquals(2, labels(m.control(LensNodes.LENS_ROW)!!).size)
        assertEquals(listOf("mm", "f/", "d"), labels(m.control(LensNodes.VALUES_ROW)!!))
        assertTrue("the three notes of P2j are gone", m.notes.isEmpty())
    }

    @Test fun choosingACameraALensAndTheValuesAreOneHistoryItemEach() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        c.setViewport(Viewport(400, 300))
        // the camera: the picker's list, then a choice
        val n0 = items(c)
        c.lens.open(key, LensPick.CAMERA, found = false)
        waitFor("cameras") { c.lens.ui.value.cameras.size == 6 }
        assertTrue(c.state.value.overlay is Overlay.LensPicker)
        c.lens.chooseCamera(key, c.lens.ui.value.cameras.first { it.model == "ILCE-7M3" && it.variant == "APS-C crop" })
        waitFor("camera written") { (param(c, "camera") as JsonPrimitive).content == "ILCE-7M3" }
        assertEquals(1.5, param(c, "crop").doubleOrNull!!, 1e-6)
        assertEquals(true, (param(c, "has_been_set")).content.toBoolean())
        assertEquals(n0 + 1, items(c))
        assertNull("the picker is closed", c.state.value.overlay)
        // the lens
        c.lens.open(key, LensPick.LENS, found = false)
        waitFor("lenses") { c.lens.ui.value.lenses.size == 4 }
        c.lens.chooseLens(key, c.lens.ui.value.lenses.first { it.model.startsWith("Canon EF 100mm") })
        waitFor("lens written") { (param(c, "lens")).content == "Canon EF 100mm f/2.8 Macro USM" }
        assertEquals("a prime fixes the focal length", 100.0, param(c, "focal").doubleOrNull!!, 1e-6)
        assertEquals(n0 + 2, items(c))
        // the three values
        for ((i, kind) in LensValueKind.entries.withIndex()) {
            c.lens.setValue(key, kind, 3.5 + i)
            waitFor("${kind.param} written") { param(c, kind.param).doubleOrNull == 3.5 + i }
            assertEquals(n0 + 3 + i, items(c))
        }
        // undo goes back one value
        c.undo()
        waitFor("undone") { param(c, "distance").doubleOrNull != 5.5 }
        // the state the panel shows
        c.lens.refresh(key)
        waitFor("state") { c.lens.ui.value.state?.lens?.model == "Canon EF 100mm f/2.8 Macro USM" }
        val st = c.lens.ui.value.state!!
        assertEquals("Sony, ILCE-7M3", st.cameraTitle)
        assertEquals(listOf("100mm"), listOf(st.lens!!.focalText))
        assertEquals(4.5, st.aperture, 1e-6)
    }

    @Test fun findOpensTheShortListAndShowAllTheWholeOne() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        c.lens.open(key, LensPick.CAMERA, found = true)
        waitFor("found") { c.lens.ui.value.cameras.size == 1 }
        assertTrue(c.lens.ui.value.found)
        c.lens.showAll(key, LensPick.CAMERA)
        waitFor("all") { c.lens.ui.value.cameras.size == 6 }
        assertFalse(c.lens.ui.value.found)
        c.lens.open(key, LensPick.LENS, found = true)
        waitFor("lenses found") { c.lens.ui.value.lenses.size == 1 }
        c.setOverlay(null)
    }

    @Test fun aValueTheEngineRefusesLeavesTheHistoryAlone() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        val n0 = items(c)
        c.lens.setValue(key, LensValueKind.FOCAL, 0.0)
        delay(300)
        assertEquals(n0, items(c))
    }

    @Test fun theValueSheetListsWhatTheLensAllows() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        c.lens.chooseLens(key, LensInfo("Canon", "Canon EF-S 18-55mm f/3.5-5.6 IS", 18.0, 55.0, 3.5, 5.6))
        waitFor("lens") { param(c, "lens").content == "Canon EF-S 18-55mm f/3.5-5.6 IS" }
        c.lens.refresh(key)
        waitFor("state") { c.lens.ui.value.state?.lens != null }
        val st = c.lens.ui.value.state!!
        val focal = LensValues.focalEntries(st.lens, st.focal).map { it.text }
        assertEquals("18", focal[1])
        assertEquals("55", focal.last())
        assertTrue(LensValues.apertureEntries(st.lens, st.aperture).map { it.text }.contains("3.5"))
        assertNotNull(c.lens)
    }
}

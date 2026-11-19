// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.schema.Binding
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

/** The darkroom's logic on the fake engine, without a screen. */
class DarkroomControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val exposureKey = InstanceKey("exposure", 0)
    private val exposureSlider by lazy {
        TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
    }

    private fun exposureOf(c: DarkroomController) =
        (c.state.value.modules.first { it.key == exposureKey }.params["exposure"] as JsonPrimitive).doubleOrNull!!

    private suspend fun waitFor(what: String, cond: () -> Boolean) = withTimeout(5000) {
        while (!cond()) delay(10)
    }.also { assertTrue(what, cond()) }

    @Test
    fun opensWithPreviewThumbnailsAndActiveModules() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root)
        val s = c.state.value
        assertFalse(s.loading)
        assertEquals(InterfacePreview.COUNT, s.thumbnails.size)
        waitFor("preview") { c.state.value.preview != null }
        val active = c.visibleModules(c.state.value).map { it.op }
        // desktop order: last in the pipe on top
        assertEquals("colorout", active.first())
        assertTrue("exposure" in active && "sigmoid" in active)
        assertFalse("modules without a panel are not listed", "gamma" in active)
    }

    @Test
    fun dragsMergeIntoOneHistoryItemSealedOnRelease() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val n0 = c.state.value.history.items.size
        for (v in listOf(0.8, 1.0, 1.2, 1.4)) c.drag(exposureKey, exposureSlider.id, exposureSlider.binding, v, false)
        assertEquals("the slider shows the finger's value at once", 1.4, c.liveValues.value[exposureKey to exposureSlider.id]!!, 0.0)
        c.endDrag(exposureKey, exposureSlider.id, exposureSlider.binding, false)
        waitFor("drag applied") { c.liveValues.value.isEmpty() }
        assertEquals(1.4, exposureOf(c), 1e-9)
        assertEquals(n0 + 1, c.state.value.history.items.size)
        // a second drag is a new item
        c.drag(exposureKey, exposureSlider.id, exposureSlider.binding, 0.5, false)
        c.endDrag(exposureKey, exposureSlider.id, exposureSlider.binding, false)
        waitFor("second drag") { c.liveValues.value.isEmpty() }
        assertEquals(n0 + 2, c.state.value.history.items.size)
        c.undo()
        assertEquals(1.4, exposureOf(c), 1e-9)
    }

    @Test
    fun whiteBalanceTemperatureThroughItsAction() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val t = TestSetup.schema.modules.getValue("temperature").control(12) as Control.Slider
        val key = InstanceKey("temperature", 0)
        assertTrue(t.binding is Binding.Action)
        assertEquals(5105.0, c.sliderValue(c.state.value, key, t, emptyMap())!!, 1.0)
        c.setSlider(key, t.binding, 6500.0, false)
        assertEquals(6500.0, c.sliderValue(c.state.value, key, t, emptyMap())!!, 1.0)
    }

    @Test
    fun switchingPhotosKeepsEachEdit() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        c.setSlider(exposureKey, exposureSlider.binding, 2.0, false)
        c.openPhoto(1)
        assertEquals(1, c.state.value.index)
        assertEquals(0.7, exposureOf(c), 1e-6)
        c.openPhoto(0)
        assertEquals(2.0, exposureOf(c), 1e-6)
    }

    @Test
    fun readOnlyPhotoExplainsWhy() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, index = InterfacePreview.READ_ONLY_INDEX, thumbnails = false)
        val s = c.state.value
        assertTrue(s.readOnly)
        assertNotNull(s.readOnlyReason)
        assertTrue(s.readOnlyReason!!, s.readOnlyReason!!.contains("color balance rgb version 99"))
        val before = java.io.File(s.current!!.xmpPath).readText()
        c.setSlider(exposureKey, exposureSlider.binding, 2.0, false)
        assertTrue(c.state.value.message!!.contains("READ_ONLY"))
        c.openPhoto(0)
        assertEquals("the foreign sidecar is never rewritten", before, java.io.File(s.current!!.xmpPath).readText())
    }

    @Test
    fun beforeShowsHistoryPositionZeroWithoutChangingTheEdit() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        c.setSlider(exposureKey, exposureSlider.binding, 3.0, false)
        val h = c.state.value.history
        val undo = c.state.value.undo
        c.setBefore(true)
        assertNotNull(c.state.value.beforePreview)
        assertEquals(h, c.state.value.history)
        c.setBefore(false)
        c.undo()
        assertEquals("before is not an undo step", 0.7, exposureOf(c), 1e-6)
        assertTrue(undo.canUndo)
    }

    @Test
    fun instancesFromTheMenu() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        c.instanceNew(exposureKey)
        assertEquals(InstanceKey("exposure", 1), c.state.value.expanded)
        assertEquals(2, c.state.value.modules.count { it.op == "exposure" })
        c.instanceDelete(InstanceKey("exposure", 1))
        assertEquals(1, c.state.value.modules.count { it.op == "exposure" })
        assertNull(c.state.value.expanded)
        c.selectGroup(GroupTab.Configured("base"))
        assertTrue(c.visibleModules(c.state.value).any { it.op == "exposure" })
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3d stage 2c (JVM, stand-in engine): the group a new shape goes into, editing a shape after "done" (cancel
 * leaves no history, done is one item and one undo step, "used by n modules"), and parametric elements added to the
 * chosen group.
 */
class P3dEditShapesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val exposure = InstanceKey("exposure", 0)

    private fun controller(): DarkroomController {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 600)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = false) }
        return c
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) {
            throw AssertionError("timed out: $what")
        }

    private suspend fun onExposure(c: DarkroomController) {
        c.setView(BottomView.MASKS)
        c.masks.select(exposure)
        waitFor("exposure's mask read") { c.masks.state.value.module?.key == exposure }
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun forms(c: DarkroomController) = c.masks.state.value.forms
    private fun form(c: DarkroomController, id: Int) = forms(c).first { it.id == id }

    private suspend fun circle(c: DarkroomController, x: Double): Int {
        c.masks.startDraw(ShapeType.CIRCLE)
        c.masks.touchDown(x, 0.5); c.masks.touchUp()
        c.masks.finishDraw()
        return c.masks.state.value.selected!!
    }

    private fun memberIds(c: DarkroomController, group: Int) = form(c, group).members.map { it.id }

    @Test fun theSelectedGroupReceivesTheNewShape() = runBlocking {
        val c = controller()
        onExposure(c)
        val a = circle(c, 0.3)
        val root = c.masks.state.value.module!!.rootId!!
        // by default new shapes go into the module's own group
        assertEquals(root, c.masks.targetGroup())
        c.masks.wrapInGroup(root, a)
        val nested = Masks.treeRows(c.masks.state.value.forms, root, emptySet()).first { it.isGroup }.member.id
        c.masks.chooseGroup(nested)
        assertEquals(nested, c.masks.targetGroup())
        val b = circle(c, 0.6)
        assertTrue("the new shape is in the chosen group", b in memberIds(c, nested))
        assertFalse("not in the module's group", b in memberIds(c, root))
        // the group opens so the new row shows
        assertTrue(nested in c.masks.state.value.expanded)
        // tapping the chosen group again goes back to the module's group
        c.masks.chooseGroup(nested)
        assertEquals(root, c.masks.targetGroup())
        val d = circle(c, 0.8)
        assertTrue(d in memberIds(c, root))
    }

    @Test fun editThenCancelLeavesNoHistory() = runBlocking {
        val c = controller()
        onExposure(c)
        val id = circle(c, 0.3)
        val n0 = items(c)
        val before = form(c, id).geometry
        c.masks.startEdit(id)
        val d = c.masks.state.value.draw!!
        assertEquals(id, d.editing)
        assertTrue(d.canFinish)
        c.masks.touchDown(d.center!!.first, d.center!!.second)
        c.masks.touchMove(0.7, 0.6)
        c.masks.touchUp()
        c.masks.setDrawValue("size", 0.2)
        c.masks.cancelDraw()
        assertNull(c.masks.state.value.draw)
        assertEquals(n0, items(c))
        assertEquals(before, form(c, id).geometry)
    }

    @Test fun editThenDoneIsOneItemAndOneUndoStep() = runBlocking {
        val c = controller()
        onExposure(c)
        val id = circle(c, 0.3)
        val n0 = items(c)
        val before = form(c, id).geometry
        c.masks.startEdit(id)
        val d = c.masks.state.value.draw!!
        c.masks.touchDown(d.center!!.first, d.center!!.second)
        c.masks.touchMove(0.7, 0.6)
        c.masks.touchUp()
        c.masks.setDrawValue("size", 0.2)
        c.masks.finishDraw()
        assertNull(c.masks.state.value.draw)
        assertEquals("one history item", n0 + 1, items(c))
        assertNotEquals(before, form(c, id).geometry)
        assertEquals("the shape stays selected", id, c.masks.state.value.selected)
        c.undo()
        assertEquals("one undo step restores it", before, form(c, id).geometry)
    }

    @Test fun aBrushOnlyMovesAndAPathMovesAPoint() = runBlocking {
        val c = controller()
        onExposure(c)
        c.masks.startDraw(ShapeType.BRUSH)
        c.masks.touchDown(0.2, 0.2); c.masks.touchMove(0.3, 0.25); c.masks.touchMove(0.4, 0.3); c.masks.touchUp()
        c.masks.finishDraw()
        val brush = c.masks.state.value.selected!!
        val b0 = form(c, brush).geometry
        val n0 = items(c)
        c.masks.startEdit(brush)
        assertEquals(1, c.masks.state.value.draw!!.strokes.size)
        c.masks.touchDown(0.3, 0.25); c.masks.touchMove(0.5, 0.45); c.masks.touchUp()
        c.masks.finishDraw()
        assertEquals(n0 + 1, items(c))
        assertNotEquals(b0, form(c, brush).geometry)
        assertEquals("same number of points", (b0["points"] as JsonArray).size, (form(c, brush).geometry["points"] as JsonArray).size)

        c.masks.startDraw(ShapeType.PATH)
        for ((x, y) in listOf(0.2 to 0.2, 0.5 to 0.2, 0.5 to 0.5, 0.2 to 0.5)) { c.masks.touchDown(x, y); c.masks.touchUp() }
        c.masks.closePath()
        c.masks.finishDraw()
        val path = c.masks.state.value.selected!!
        val p0 = form(c, path).geometry
        val n1 = items(c)
        c.masks.startEdit(path)
        // a touch far from every point moves nothing; a touch on a point drags it
        c.masks.touchDown(0.9, 0.9); c.masks.touchMove(0.95, 0.95); c.masks.touchUp()
        assertEquals(c.masks.state.value.draw!!.points.size, 4)
        c.masks.touchDown(0.5, 0.5); c.masks.touchMove(0.6, 0.6); c.masks.touchUp()
        c.masks.finishDraw()
        assertEquals(n1 + 1, items(c))
        assertNotEquals(p0, form(c, path).geometry)
        assertEquals((p0["points"] as JsonArray).size, (form(c, path).geometry["points"] as JsonArray).size)
    }

    @Test fun usedByAndWhatCannotBeEdited() = runBlocking {
        val c = controller()
        onExposure(c)
        val id = circle(c, 0.3)
        val root = c.masks.state.value.module!!.rootId!!
        assertTrue(c.masks.editable(form(c, id)))
        assertFalse("a group is not edited by drawing", c.masks.editable(form(c, root)))
        val other = c.masks.blendingModules().map { it.key }.first { it != exposure }
        c.masks.attach(id, other)
        c.masks.startEdit(id)
        assertEquals(2, c.masks.state.value.draw!!.usedBy)
        c.masks.cancelDraw()
        // starting an edit on a group does nothing
        c.masks.startEdit(root)
        assertNull(c.masks.state.value.draw)
    }

    @Test fun aParametricElementGoesIntoTheSelectedGroup() = runBlocking {
        val c = controller()
        onExposure(c)
        val a = circle(c, 0.3)
        val root = c.masks.state.value.module!!.rootId!!
        c.masks.wrapInGroup(root, a)
        val nested = Masks.treeRows(c.masks.state.value.forms, root, emptySet()).first { it.isGroup }.member.id
        c.masks.chooseGroup(nested)
        c.masks.setMode(MaskMode.DRAWN)
        waitFor("parametric state read") { c.parametric.state.value.root == root }
        val spec = ParamChannels.of(c.parametric.state.value.space).first()
        val n0 = items(c)
        c.parametric.addChannel(spec, c.masks.targetGroup(), select = true)
        assertEquals("one history item", n0 + 1, items(c))
        val el = forms(c).first { it.type == "parametric" }
        assertTrue("in the chosen group", el.id in memberIds(c, nested))
        assertFalse(el.id in memberIds(c, root))
        waitFor("the element is read from the nested group") { c.parametric.state.value.all.any { it.id == el.id } }
        assertTrue("not a direct member of the module's group", c.parametric.state.value.elements.none { it.id == el.id })
        assertEquals("its row is selected", el.id, c.masks.state.value.selected)
        assertNotNull(c.masks.state.value.module)
        // not edited by drawing
        assertFalse(c.masks.editable(el))
        // its range changes through the same calls as in parametric mode
        c.parametric.setRange(el.id, false, listOf(0.2, 0.3, 0.6, 0.7))
        assertEquals(listOf(0.2, 0.3, 0.6, 0.7), c.parametric.state.value.all.first { it.id == el.id }.inRange)
    }
}

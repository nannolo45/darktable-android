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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3d stage 2a (JVM, stand-in engine): one history item and one undo step per finished shape and per brush
 * session; the module's mask as a tree (rows top first, nested groups closed by default), operators, invert and
 * opacity per group and member, move up / down, wrap in group, group together, remove from any group.
 */
class P3dMaskTreeTest {
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

    private suspend fun circle(c: DarkroomController, x: Double): Int {
        c.masks.startDraw(ShapeType.CIRCLE)
        c.masks.touchDown(x, 0.5); c.masks.touchUp()
        c.masks.finishDraw()
        return c.masks.state.value.selected!!
    }

    private fun rows(c: DarkroomController) = c.masks.state.value.let { Masks.treeRows(it.forms, it.module?.rootId, it.expanded) }

    @Test fun oneItemPerShapeAndPerBrushSession() = runBlocking {
        val c = controller()
        onExposure(c)
        val n0 = items(c)
        circle(c, 0.3)
        assertEquals("a circle is one history item", n0 + 1, items(c))
        c.masks.startDraw(ShapeType.BRUSH)
        for (k in 0 until 3) { c.masks.touchDown(0.1 + 0.2 * k, 0.2); c.masks.touchMove(0.15 + 0.2 * k, 0.25); c.masks.touchUp() }
        c.masks.finishDraw()
        assertEquals(3, c.masks.state.value.forms.count { it.type == "brush" })
        assertEquals("a brush session of three strokes is one history item", n0 + 2, items(c))
        c.undo()
        waitFor("one undo takes all three strokes") { c.masks.state.value.forms.none { it.type == "brush" } }
        assertEquals(1, c.masks.state.value.forms.count { it.type == "circle" })
        // cancel: nothing reaches the engine
        c.masks.startDraw(ShapeType.CIRCLE)
        c.masks.touchDown(0.5, 0.5); c.masks.touchUp()
        c.masks.cancelDraw()
        assertEquals(n0 + 1, items(c))
    }

    @Test fun theTreeAndItsEdits() = runBlocking {
        val c = controller()
        onExposure(c)
        val a = circle(c, 0.2)
        val b = circle(c, 0.4)
        val d = circle(c, 0.6)
        val root = c.masks.state.value.module!!.rootId!!
        // top first: the last added on top
        assertEquals(listOf(d, b, a), rows(c).map { it.member.id })
        assertEquals(listOf(2, 1, 0), rows(c).map { it.index })
        // operator of the module's group, from the seven
        assertEquals(7, Masks.OPERATORS.size)
        assertEquals(listOf("maximum", "minimum", "difference", "exclusion", "screen", "sum", "product"), Masks.OPERATORS.map { it.second })
        val n0 = items(c)
        c.masks.setGroupOperator(root, "difference")
        assertEquals("difference", c.masks.state.value.module!!.operator)
        // move down / up: one item each; nothing at the ends
        c.masks.moveMember(root, d, up = false)
        assertEquals(listOf(b, d, a), rows(c).map { it.member.id })
        c.masks.moveMember(root, b, up = true)
        assertEquals(listOf(b, d, a), rows(c).map { it.member.id })
        assertEquals(n0 + 2, items(c))
        // wrap in group: the new group at the member's place, open; group together where the topmost was
        c.masks.wrapInGroup(root, a)
        val w = rows(c).first { it.isGroup }.member.id
        assertTrue(w in c.masks.state.value.expanded)
        assertEquals(listOf(b, d, w, a), rows(c).map { it.member.id })
        assertEquals(listOf(0, 0, 0, 1), rows(c).map { it.depth })
        c.masks.groupTogether(root, listOf(d, b))
        val g = rows(c).first { it.isGroup && it.member.id != w }.member.id
        // a nested group shows its members only while open (a new group opens; closing hides them)
        assertTrue(g in c.masks.state.value.expanded)
        c.masks.toggleExpanded(g)
        assertEquals(listOf(g, w, a), rows(c).map { it.member.id })
        c.masks.toggleExpanded(g)
        assertEquals(listOf(g, b, d, w, a), rows(c).map { it.member.id })
        // invert and opacity of a member in a nested group, and of a group (its own settings)
        c.masks.setMemberInvertIn(g, d, true)
        c.masks.setMemberOpacityIn(g, d, 0.4)
        val dm = c.masks.state.value.forms.first { it.id == g }.members.first { it.id == d }
        assertTrue(dm.inverse)
        assertEquals(0.4, dm.opacity, 1e-9)
        c.masks.setMemberInvertIn(root, g, true)
        c.masks.setMemberOpacityIn(root, g, 0.5)
        val gg = c.masks.state.value.forms.first { it.id == g }.geometry
        assertEquals(JsonPrimitive(true), gg["invert"])
        assertEquals(0.5, (gg["opacity"] as JsonPrimitive).content.toDouble(), 1e-9)
        // remove from a nested group: the shape stays in the photo
        c.masks.removeFrom(g, d)
        assertFalse(c.masks.state.value.forms.first { it.id == g }.members.any { it.id == d })
        assertTrue(c.masks.state.value.forms.any { it.id == d })
        // undo goes back one edit
        c.undo()
        waitFor("undo of the removal") { c.masks.state.value.forms.first { it.id == g }.members.any { it.id == d } }
        assertNull(c.masks.state.value.note)
    }
}

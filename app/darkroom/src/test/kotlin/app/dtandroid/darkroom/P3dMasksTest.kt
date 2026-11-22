// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.CoordsDirection
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
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
 * Brief P3d stage 1 (JVM): the masks tab's logic on the stand-in engine: modes, opacity, blend mode, the five shapes
 * drawn by touch (one finger draws, a second finger cancels what the touch began), the shapes handed over in
 * original-image coordinates, combine, invert, remove, the manager's actions, "show mask" as a render option, one
 * history item per finished setting or shape, undo and redo, the indicator's modes, and a read-only photo.
 */
class P3dMasksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val exposure = InstanceKey("exposure", 0)

    /** The stand-in engine, recording the render options of previews. */
    private class Hooked(val e: DarktableEngine) : DarktableEngine by e {
        val previewOptions = java.util.Collections.synchronizedList(ArrayList<RenderOptions>())
        override suspend fun renderPreview(maxSize: Int, options: RenderOptions): RenderedImage {
            previewOptions += options
            return e.renderPreview(maxSize, options)
        }
    }

    private var last: DarkroomController? = null

    private fun controller(index: Int = 0): Pair<DarkroomController, Hooked> {
        val dir = tmp.root
        val engine = Hooked(InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE))
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 600)
        c.setViewport(Viewport(400, 300))
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), index, filmstrip = false, thumbnails = false) }
        last = c
        return c to engine
    }

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) {
            throw AssertionError("timed out: $what; masks ${last?.masks?.state?.value}, message ${last?.state?.value?.message}")
        }

    /** The masks tab open on exposure's mask. */
    private suspend fun onExposure(c: DarkroomController) {
        c.setView(BottomView.MASKS)
        c.masks.select(exposure)
        waitFor("exposure's mask read") { c.masks.state.value.module?.key == exposure }
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size

    private suspend fun drawCircleAt(c: DarkroomController, x: Double, y: Double): Int {
        c.masks.startDraw(ShapeType.CIRCLE)
        c.masks.touchDown(x, y)
        c.masks.touchUp()
        c.masks.finishDraw()
        return c.masks.state.value.selected!!
    }

    @Test fun indicatorAndModesOneHistoryItemEach() = runBlocking {
        val (c, _) = controller()
        onExposure(c)
        assertTrue(c.masks.state.value.module!!.supported)
        assertEquals(MaskMode.OFF, c.masks.state.value.module!!.mode)
        assertFalse(Masks.indicated(c.state.value.maskModes[exposure]))
        val n0 = items(c)
        c.masks.setMode(MaskMode.UNIFORM)
        assertEquals("uniform", c.state.value.maskModes[exposure])
        assertTrue(Masks.indicated(c.state.value.maskModes[exposure]))
        assertEquals(n0 + 1, items(c))
        c.masks.setMode(MaskMode.DRAWN)
        assertEquals(MaskMode.DRAWN, c.masks.state.value.module!!.mode)
        assertEquals(n0 + 2, items(c))
        // stage 2b builds parametric; drawn & parametric is still offered but not built (stage 2a finds out what it needs)
        c.masks.setMode(MaskMode.BOTH)
        assertEquals(MaskMode.DRAWN, c.masks.state.value.module!!.mode)
        assertEquals(n0 + 2, items(c))
        // opacity, blend mode and invert: one item each
        c.masks.setOpacity(60.0)
        c.masks.setBlendMode("multiply")
        c.masks.setInvert(true)
        val m = c.masks.state.value.module!!
        assertEquals(60.0, m.opacity, 1e-9)
        assertEquals("multiply", m.blendMode)
        assertTrue(m.invert)
        assertEquals(n0 + 5, items(c))
        // undo goes back one setting and the tab shows it
        c.undo()
        assertFalse(c.masks.state.value.module!!.invert)
        c.redo()
        assertTrue(c.masks.state.value.module!!.invert)
        // switching off: the indicator goes
        c.masks.setMode(MaskMode.OFF)
        assertFalse(Masks.indicated(c.state.value.maskModes[exposure]))
    }

    @Test fun aCircleByTouchGoesToTheEngineInOriginalCoordinates() = runBlocking {
        val (c, e) = controller()
        onExposure(c)
        val n0 = items(c)
        c.masks.startDraw(ShapeType.CIRCLE)
        assertNotNull(c.masks.state.value.draw)
        assertFalse(c.masks.state.value.draw!!.canFinish)
        // a tap places it, a drag moves it, a pinch (second finger) puts it back where it was
        c.masks.touchDown(0.2, 0.2); c.masks.touchUp()
        c.masks.touchDown(0.3, 0.4); c.masks.touchMove(0.35, 0.45); c.masks.touchUp()
        c.masks.touchDown(0.9, 0.9); c.masks.touchCancel()
        assertEquals(0.35 to 0.45, c.masks.state.value.draw!!.center)
        c.masks.setDrawValue("size", 0.1)
        c.masks.finishDraw()
        val s = c.masks.state.value
        assertNull(s.draw)
        val id = s.selected!!
        val f = s.forms.first { it.id == id }
        assertEquals("circle", f.type)
        assertEquals(listOf(exposure), f.users)
        assertEquals(listOf(id), s.module!!.members.map { it.id })
        assertEquals(MaskMode.DRAWN, s.module!!.mode)
        assertEquals("one history item for the finished shape (the stand-in records none for the unattached form)", n0 + 1, items(c))
        // the centre is the tapped point of the picture mapped to the original image
        val want = e.coordsTransform(CoordsDirection.TO_ORIGINAL, listOf(0.35 to 0.45)).points[0]!!
        val centre = (f.geometry["center"] as JsonArray).map { it.jsonPrimitive.double }
        assertEquals(want.first, centre[0], 1e-6)
        assertEquals(want.second, centre[1], 1e-6)
        val r = f.geometry["radius"]!!.jsonPrimitive.double
        assertTrue("radius $r", r > 0.05 && r < 0.2)
        // the tapped shape's outline is shown, around the tapped point
        val o = s.outline!!
        assertTrue(o.closed)
        val cx = o.points.map { it.first }.average()
        val cy = o.points.map { it.second }.average()
        assertEquals(0.35, cx, 0.01)
        assertEquals(0.45, cy, 0.01)
        assertTrue(Masks.indicated(c.state.value.maskModes[exposure]))
    }

    @Test fun brushStrokesPathEllipseAndGradient() = runBlocking {
        val (c, _) = controller()
        onExposure(c)
        // desktop's drawn mode first: attaching a shape adds FLEXI to its flags (blend_get then says "other")
        c.masks.setMode(MaskMode.DRAWN)
        c.masks.startDraw(ShapeType.BRUSH)
        c.masks.touchDown(0.1, 0.1); c.masks.touchMove(0.2, 0.15); c.masks.touchMove(0.3, 0.2); c.masks.touchUp()
        c.masks.touchDown(0.5, 0.5); c.masks.touchCancel() // a pinch: no stroke
        c.masks.touchDown(0.6, 0.6); c.masks.touchUp() // a tap: a dot
        c.masks.touchDown(0.7, 0.7); c.masks.touchMove(0.8, 0.7); c.masks.touchUp()
        assertEquals(3, c.masks.state.value.draw!!.strokes.size)
        c.masks.undoStroke()
        assertEquals(2, c.masks.state.value.draw!!.strokes.size)
        c.masks.finishDraw()
        assertEquals(2, c.masks.state.value.forms.count { it.type == "brush" })
        assertEquals(MaskMode.DRAWN, c.masks.state.value.module!!.mode)
        assertEquals("flexi", c.state.value.maskModes[exposure])
        // a path needs three points
        c.masks.startDraw(ShapeType.PATH)
        c.masks.touchDown(0.2, 0.2); c.masks.touchUp()
        c.masks.touchDown(0.4, 0.2); c.masks.touchUp()
        c.masks.finishDraw()
        assertNotNull("not done with two points", c.masks.state.value.draw)
        c.masks.touchDown(0.3, 0.4); c.masks.touchMove(0.32, 0.42); c.masks.touchUp()
        c.masks.removeLastPoint()
        c.masks.touchDown(0.3, 0.4); c.masks.touchUp()
        c.masks.closePath()
        assertTrue(c.masks.state.value.draw!!.closed)
        c.masks.finishDraw()
        val path = c.masks.state.value.forms.first { it.type == "path" }
        assertEquals(3, (path.geometry["points"] as JsonArray).size)
        c.masks.startDraw(ShapeType.ELLIPSE)
        c.masks.setDrawValue("rotation", 30.0)
        c.masks.touchDown(0.5, 0.5); c.masks.touchUp()
        c.masks.finishDraw()
        val el = c.masks.state.value.forms.first { it.type == "ellipse" }
        assertEquals(30.0, el.geometry["rotation"]!!.jsonPrimitive.double, 1e-9)
        c.masks.startDraw(ShapeType.GRADIENT)
        c.masks.touchDown(0.5, 0.3); c.masks.touchUp()
        c.masks.finishDraw()
        assertEquals(1, c.masks.state.value.forms.count { it.type == "gradient" })
        assertEquals(5, c.masks.state.value.module!!.members.size)
        // cancel leaves nothing
        val n = c.masks.state.value.forms.size
        c.masks.startDraw(ShapeType.CIRCLE)
        c.masks.touchDown(0.5, 0.5); c.masks.touchUp()
        c.masks.cancelDraw()
        assertEquals(n, c.masks.state.value.forms.size)
    }

    @Test fun combineInvertRemoveAndTheManager() = runBlocking {
        val (c, _) = controller()
        onExposure(c)
        val a = drawCircleAt(c, 0.3, 0.3)
        val b = drawCircleAt(c, 0.6, 0.6)
        val root = c.masks.state.value.module!!.rootId!!
        val n0 = items(c)
        c.masks.setCombine("intersection")
        assertEquals("minimum", c.masks.state.value.module!!.operator)
        assertEquals("intersection", Masks.combineLabel(c.masks.state.value.module!!.operator))
        c.masks.setMemberInvert(a, true)
        assertTrue(c.masks.state.value.module!!.members.first { it.id == a }.inverse)
        assertEquals(n0 + 2, items(c))
        // the manager lists the module's group with both shapes
        val g = c.masks.state.value.forms.first { it.id == root }
        assertEquals(exposure, g.rootOf)
        assertEquals(listOf(a, b), g.members.map { it.id })
        // rename, duplicate (into the same group), use in another module, remove from the group, delete
        c.masks.rename(a, "spot")
        assertEquals("spot", c.masks.state.value.forms.first { it.id == a }.name)
        c.masks.duplicate(b, exposure)
        val dup = c.masks.state.value.selected!!
        assertTrue(dup != b)
        assertEquals(3, c.masks.state.value.module!!.members.size)
        val other = c.masks.blendingModules().first { it.key != exposure }.key
        c.masks.attach(b, other)
        assertEquals(2, c.masks.state.value.forms.first { it.id == b }.users.size)
        c.masks.detach(dup, exposure)
        assertEquals(2, c.masks.state.value.module!!.members.size)
        assertTrue("the removed shape stays in the photo", c.masks.state.value.forms.any { it.id == dup })
        // undo puts it back into the module's mask, redo removes it again
        c.undo()
        assertEquals(3, c.masks.state.value.module!!.members.size)
        c.redo()
        assertEquals(2, c.masks.state.value.module!!.members.size)
        c.masks.delete(dup)
        assertFalse(c.masks.state.value.forms.any { it.id == dup })
    }

    @Test fun showMaskIsARenderOptionAndEndsWithTheMask() = runBlocking {
        val (c, e) = controller()
        onExposure(c)
        c.masks.toggleShowMask()
        assertNull("no mask: nothing to show", c.state.value.showMask)
        assertNotNull(c.masks.state.value.note)
        drawCircleAt(c, 0.4, 0.4)
        c.masks.toggleShowMask()
        assertEquals(exposure, c.state.value.showMask)
        e.previewOptions.clear()
        c.renderNow()
        assertEquals("exposure", e.previewOptions.last().showMask?.op)
        // leaving the tab ends it; the next preview is the plain one
        c.setView(BottomView.MODULES)
        assertNull(c.state.value.showMask)
        c.setView(BottomView.MASKS)
        c.masks.toggleShowMask()
        assertEquals(exposure, c.state.value.showMask)
        c.masks.setMode(MaskMode.OFF)
        assertNull("mask off: show mask ends", c.state.value.showMask)
    }

    @Test fun theIndicatorOpensTheTabWithItsModule() = runBlocking {
        val (c, _) = controller()
        c.openMasks(exposure)
        waitFor("tab open on exposure") { c.state.value.view == BottomView.MASKS && c.masks.state.value.module?.key == exposure }
    }

    @Test fun readOnlyPhotoShowsMasksButChangesNothing() = runBlocking {
        val (c, _) = controller(index = InterfacePreview.READ_ONLY_INDEX)
        assertTrue(c.state.value.readOnly)
        onExposure(c)
        val n0 = items(c)
        c.masks.setMode(MaskMode.DRAWN)
        c.masks.startDraw(ShapeType.CIRCLE)
        assertNull(c.masks.state.value.draw)
        c.masks.setOpacity(10.0)
        assertEquals(n0, items(c))
        assertEquals(MaskMode.OFF, c.masks.state.value.module!!.mode)
    }

    @Test fun anotherPhotoForgetsTheDrawing() = runBlocking {
        val (c, _) = controller()
        onExposure(c)
        c.masks.startDraw(ShapeType.BRUSH)
        c.openPhoto(1)
        assertNull(c.masks.state.value.draw)
        assertNull(c.masks.state.value.key)
        assertNull(c.state.value.showMask)
    }

    @Test fun existingShapeSheetOffersAndRefusesCycles() = runBlocking {
        val (c, _) = controller()
        onExposure(c)
        // no shape in the photo, no group yet
        c.masks.openSheet(MaskSheet.Existing)
        assertTrue(Masks.existingOffers(c.masks.state.value.forms, c.masks.state.value.module!!).isEmpty())
        // one shape, detached: offered; picked: in the module; offered again (now "in this module")
        val a = drawCircleAt(c, 0.3, 0.3)
        c.masks.detach(a)
        assertEquals(listOf(a), Masks.existingOffers(c.masks.state.value.forms, c.masks.state.value.module!!).map { it.id })
        c.masks.attach(a)
        assertEquals(listOf(a), c.masks.state.value.module!!.members.map { it.id })
        // a second module with its own group: its group is offered to exposure and can be picked
        val other = c.masks.blendingModules().first { it.key != exposure }.key
        val rootEx = c.masks.state.value.module!!.rootId!!
        c.masks.select(other)
        waitFor("other") { c.masks.state.value.module?.key == other }
        val b = drawCircleAt(c, 0.7, 0.7)
        val rootOther = c.masks.state.value.module!!.rootId!!
        assertTrue(rootEx in Masks.existingOffers(c.masks.state.value.forms, c.masks.state.value.module!!).map { it.id })
        c.masks.attach(rootEx)
        assertTrue(c.masks.state.value.module!!.members.any { it.id == rootEx })
        // now other's group holds exposure's: offering it to exposure, or attaching it, would make a cycle
        c.masks.select(exposure)
        waitFor("exposure") { c.masks.state.value.module?.key == exposure }
        val forms = c.masks.state.value.forms
        assertFalse(rootOther in Masks.existingOffers(forms, c.masks.state.value.module!!).map { it.id })
        assertTrue(Masks.makesCycle(forms, rootOther, rootEx))
        assertTrue(Masks.makesCycle(forms, rootEx, rootEx))
        val n = items(c)
        c.masks.attach(rootOther)
        assertEquals("refused, nothing written", n, items(c))
        assertNotNull(c.masks.state.value.note)
        assertEquals(listOf(a), c.masks.state.value.module!!.members.map { it.id })
        // a cyclic photo (desktop data gone wrong) does not hang the check
        val loop = listOf(
            MaskShape(1, "group", "", kotlinx.serialization.json.JsonObject(emptyMap()), emptyList(), null, listOf(MaskMemberState(2, false, 1.0, false))),
            MaskShape(2, "group", "", kotlinx.serialization.json.JsonObject(emptyMap()), emptyList(), null, listOf(MaskMemberState(1, false, 1.0, false))),
        )
        assertFalse(Masks.makesCycle(loop, 1, 99))
        assertTrue(b != a)
    }
}

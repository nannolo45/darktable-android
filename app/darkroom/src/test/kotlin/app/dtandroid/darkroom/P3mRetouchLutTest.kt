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
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/**
 * Brief P3m (JVM, stand-in engine): retouch's tools on the photo (tap: target and the default source; press and drag:
 * the source where the finger lifted; path; brush), moving a source, selection, the algorithm buttons and their long
 * press, blur and fill settings, opacity, remove, undo, "switch off shapes", the wavelet line; the colour look up
 * table's patch edits (offsets, reset, delete, replace, add, nearest patch).
 */
class P3mRetouchLutTest {
    @get:Rule val tmp = TemporaryFolder()

    private val retouch = InstanceKey(RETOUCH_OP, 0)
    private val lutKey = InstanceKey(LUT_OP, 0)

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

    private suspend fun openRetouch(c: DarkroomController) {
        waitFor("photo open") { c.state.value.modules.any { it.key == retouch } && !c.state.value.loading }
        c.toggleExpanded(retouch)
        c.retouch.open(retouch)
        waitFor("retouch read") { c.retouch.state.value.st != null }
    }

    private fun items(c: DarkroomController) = c.state.value.history.items.size
    private fun shapes(c: DarkroomController) = c.retouch.state.value.st!!.shapes
    private val T = 0.01

    private suspend fun tapCircle(c: DarkroomController, x: Double, y: Double): Int {
        val before = shapes(c).size
        c.retouch.setTool(RetouchTool.CIRCLE)
        assertTrue(c.retouch.touchDown(x, y, T, T))
        c.retouch.touchUp(x, y, T, T)
        waitFor("shape placed") { shapes(c).size == before + 1 && c.retouch.state.value.selected != null }
        return c.retouch.state.value.selected!!
    }

    @Test fun aTapPlacesTargetAndSourceAtTheDefaultOffset() = runBlocking {
        val c = controller()
        openRetouch(c)
        val h0 = items(c)
        val id = tapCircle(c, 0.4, 0.5)
        assertEquals("one history item", h0 + 1, items(c))
        val s = shapes(c).first { it.id == id }
        assertEquals("heal", s.algorithm)
        assertTrue(s.clone)
        val center = (s.geometry["center"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).doubleOrNull!! }
        val r = (s.geometry["radius"] as JsonPrimitive).doubleOrNull!!
        val src = s.source!!
        // desktop's "+": a radius to the right and up
        assertEquals(center[0] + r, src.first, 1e-9)
        assertEquals(center[1] - r, src.second, 1e-9)
        // a single tap places one shape: the tool is off again (desktop's click without ctrl)
        assertNull(c.retouch.state.value.tool)
        assertEquals(1, c.retouch.state.value.st!!.count)
        assertEquals(1, c.retouch.state.value.outlines.size)
        assertNotNull(c.retouch.state.value.outlines[0].source)
    }

    @Test fun pressAndDragPlacesTheSourceWhereTheFingerLifts() = runBlocking {
        val c = controller()
        openRetouch(c)
        c.retouch.setTool(RetouchTool.ELLIPSE, continuous = true)
        assertTrue(c.retouch.touchDown(0.3, 0.3, T, T))
        c.retouch.touchMove(0.6, 0.7, T, T)
        c.retouch.touchUp(0.6, 0.7, T, T)
        waitFor("ellipse placed") { shapes(c).size == 1 }
        val s = shapes(c)[0]
        assertEquals("ellipse", s.type)
        // the stand-in maps the picture shown to the original one to one
        assertEquals(0.6, s.source!!.first, 1e-6)
        assertEquals(0.7, s.source!!.second, 1e-6)
        // continuous (long press): the tool stays on
        assertEquals(RetouchTool.ELLIPSE, c.retouch.state.value.tool)
        c.retouch.setTool(RetouchTool.ELLIPSE, continuous = true)
        assertNull(c.retouch.state.value.tool)
    }

    @Test fun pathAndBrushShapes() = runBlocking {
        val c = controller()
        openRetouch(c)
        c.retouch.setTool(RetouchTool.PATH)
        for ((x, y) in listOf(0.2 to 0.2, 0.3 to 0.2, 0.25 to 0.3)) { c.retouch.touchDown(x, y, T, T); c.retouch.touchUp(x, y, T, T) }
        assertEquals(3, c.retouch.state.value.pathPoints.size)
        // a tap on the first point closes it
        c.retouch.touchDown(0.2, 0.2, T, T); c.retouch.touchUp(0.2, 0.2, T, T)
        waitFor("path placed") { shapes(c).size == 1 }
        val path = shapes(c)[0]
        assertEquals("path", path.type)
        assertEquals(0.22, path.source!!.first, 1e-6) // first point + 2 % (path.c)
        c.retouch.setAlgorithm("blur")
        waitFor("blur chosen") { c.retouch.state.value.st!!.algorithm == "blur" }
        c.retouch.setTool(RetouchTool.BRUSH)
        c.retouch.touchDown(0.5, 0.5, T, T); c.retouch.touchMove(0.55, 0.52, T, T); c.retouch.touchMove(0.6, 0.5, T, T); c.retouch.touchUp(0.6, 0.5, T, T)
        waitFor("brush placed") { shapes(c).size == 2 }
        val brush = shapes(c)[1]
        assertEquals("brush", brush.type)
        assertEquals("blur", brush.algorithm)
        assertFalse("blur shapes have no source", brush.clone)
        assertNull(brush.source)
    }

    @Test fun dragMovesTheSourceAndATapSelects() = runBlocking {
        val c = controller()
        openRetouch(c)
        val id = tapCircle(c, 0.4, 0.5)
        val s0 = shapes(c)[0]
        val o = c.retouch.state.value.outlines[0]
        val at = o.sourceAt!!
        val h0 = items(c)
        assertTrue("a touch on the source belongs to retouch", c.retouch.touchDown(at.first, at.second, T, T))
        c.retouch.touchMove(at.first + 0.1, at.second + 0.05, T, T)
        c.retouch.touchUp(at.first + 0.1, at.second + 0.05, T, T)
        waitFor("source moved") { abs(shapes(c)[0].source!!.first - (s0.source!!.first + 0.1)) < 1e-6 }
        assertEquals("one history item", h0 + 1, items(c))
        // the target stayed
        assertEquals(s0.geometry["center"], shapes(c)[0].geometry["center"])
        // undo puts the source back
        c.undo()
        waitFor("undone") { abs(shapes(c)[0].source!!.first - s0.source!!.first) < 1e-6 }
        // a tap elsewhere unselects, a tap on the shape selects
        assertTrue(c.retouch.tap(0.95, 0.95, T, T))
        waitFor("unselected") { c.retouch.state.value.selected == null }
        assertFalse("a touch away from the shapes lets the photo pan", c.retouch.touchDown(0.95, 0.95, T, T))
        c.retouch.tap(0.4, 0.5, T, T)
        waitFor("selected") { c.retouch.state.value.selected == id }
    }

    @Test fun algorithmsSettingsOpacityRemove() = runBlocking {
        val c = controller()
        openRetouch(c)
        val heal = tapCircle(c, 0.3, 0.5)
        // ctrl+click as a long press: heal -> clone allowed, heal -> blur refused with a note
        c.retouch.applyAlgorithmToSelected("clone")
        waitFor("clone") { shapes(c).first { it.id == heal }.algorithm == "clone" }
        c.retouch.applyAlgorithmToSelected("blur")
        assertNotNull(c.retouch.state.value.note)
        assertEquals("clone", shapes(c).first { it.id == heal }.algorithm)
        // a fill shape: its settings follow the controls, the module's too
        c.retouch.setAlgorithm("fill")
        waitFor("fill") { c.retouch.state.value.st!!.algorithm == "fill" }
        val fill = tapCircle(c, 0.7, 0.5)
        assertNull(shapes(c).first { it.id == fill }.source)
        c.retouch.setSetting("fill_mode", JsonPrimitive("color"))
        c.retouch.setSetting("fill_brightness", JsonPrimitive(0.25))
        waitFor("fill settings") { shapes(c).first { it.id == fill }.let { it.fillMode == "color" && abs(it.fillBrightness - 0.25) < 1e-9 } }
        assertEquals("color", c.retouch.state.value.st!!.fillMode)
        c.retouch.setOpacity(0.01)
        waitFor("opacity") { abs(shapes(c).first { it.id == fill }.opacity - 0.05) < 1e-9 } // clamped as desktop
        // selecting the clone shape makes clone the module's algorithm (desktop's selection)
        c.retouch.select(heal)
        waitFor("module algorithm follows the selection") { c.retouch.state.value.st!!.algorithm == "clone" }
        val h0 = items(c)
        c.retouch.removeSelected()
        waitFor("removed") { shapes(c).none { it.id == heal } }
        assertEquals(h0 + 1, items(c))
        assertEquals(0, shapes(c).first { it.id == fill }.index)
    }

    @Test fun switchOffShapesLeavesRetouchOutOfTheRender() = runBlocking {
        val c = controller()
        openRetouch(c)
        tapCircle(c, 0.4, 0.5)
        assertNull(c.retouch.suppressed())
        c.retouch.toggleSuppress()
        assertEquals(retouch, c.retouch.suppressed())
        c.toggleExpanded(retouch) // the panel closes: as desktop, only while focused
        c.retouch.close()
        assertNull(c.retouch.suppressed())
    }

    @Test fun waveletScalesShowTheCurrentScaleOnly() = runBlocking {
        val c = controller()
        openRetouch(c)
        tapCircle(c, 0.3, 0.5)
        // as desktop's wavelet bar would set them (the app never does)
        c.engineChange { e -> e.paramsSet(RETOUCH_OP, 0, mapOf("num_scales" to JsonPrimitive(4), "curr_scale" to JsonPrimitive(2))); e.historySeal() }
        waitFor("scales read") { c.retouch.state.value.st!!.numScales == 4 }
        assertTrue(c.retouch.state.value.wavelet)
        assertEquals("the scale 0 shape is not shown on scale 2", 0, c.retouch.state.value.outlines.size)
        val id = tapCircle(c, 0.6, 0.5)
        assertEquals(2, shapes(c).first { it.id == id }.scale)
        assertEquals(1, c.retouch.state.value.outlines.size)
        val p = c.state.value.modules.first { it.key == retouch }.params
        assertEquals(4.0, (p["num_scales"] as JsonPrimitive).doubleOrNull!!, 0.0)
        assertEquals(2.0, (p["curr_scale"] as JsonPrimitive).doubleOrNull!!, 0.0)
    }

    // ------------------------------------------------------------------ colour look up table

    private fun patches(c: DarkroomController) = LutPatches.of(c.state.value.modules.first { it.key == lutKey }.params)!!

    @Test fun lookUpTablePatchEdits() = runBlocking {
        val c = controller()
        waitFor("photo open") { c.state.value.modules.any { it.key == lutKey } && !c.state.value.loading }
        c.toggleExpanded(lutKey)
        c.lut.open(lutKey)
        val p0 = patches(c)
        assertEquals("the 24 colour checker patches", 24, p0.n)
        assertEquals(6, p0.columns); assertEquals(4, p0.rows)
        assertFalse(p0.altered(3))
        c.lut.select(3)
        val h0 = items(c)
        c.lut.setOffset("lightness", 10.0)
        waitFor("target L") { abs(patches(c).targetL[3] - (p0.sourceL[3] + 10)) < 1e-6 }
        assertEquals(h0 + 1, items(c))
        assertTrue(patches(c).altered(3))
        assertEquals(10.0, patches(c).offsets(3).lightness, 1e-6)
        c.lut.setOffset("saturation", 5.0)
        waitFor("saturation") { abs(patches(c).offsets(3).saturation - 5.0) < 1e-3 }
        c.lut.reset(3)
        waitFor("reset") { !patches(c).altered(3) }
        // delete patch 0: the others move up
        c.lut.delete(0)
        waitFor("deleted") { patches(c).n == 23 }
        assertEquals(p0.sourceL[1], patches(c).sourceL[0], 1e-6)
        // the picked colour: nearest patch, replace, add
        val lab = Triple(p0.sourceL[5] + 0.5, p0.sourceA[5], p0.sourceB[5] + 0.5)
        assertEquals(4, patches(c).nearest(lab)) // patch 5 is now 4
        val add = Triple(60.0, 20.0, -10.0)
        assertTrue(Lut.validNew(patches(c), add))
        assertFalse("a patch's own colour is not new", Lut.validNew(patches(c), patches(c).source(2)))
        assertFalse("zero channels are refused", Lut.validNew(patches(c), Triple(50.0, 0.0, 10.0)))
        val v = Lut.patchValues(23, add) + ("num_patches" to 24.0)
        c.engineChange { e -> e.paramsSet(LUT_OP, 0, v.mapValues { (k, x) -> if (k == "num_patches") JsonPrimitive(x.toInt()) else JsonPrimitive(x) }); e.historySeal() }
        waitFor("added") { patches(c).n == 24 && abs(patches(c).sourceL[23] - 60.0) < 1e-6 }
        val del = Lut.deleteValues(patches(c), 22)
        assertEquals(23.0, del["num_patches"]!!, 0.0)
        assertEquals(60.0, del["source_L[22]"]!!, 0.0)
    }

    @Test fun moreThan24PatchesAreASevenBySevenGrid() {
        val p = LutPatches(25, List(49) { 50.0 }, List(49) { 0.0 }, List(49) { 0.0 }, List(49) { 50.0 }, List(49) { 0.0 }, List(49) { 0.0 }, 49)
        assertEquals(7, p.columns)
        assertEquals(7, p.rows)
    }

    @Test fun hitTestsAndMoves() {
        val square = listOf(0.1 to 0.1, 0.2 to 0.1, 0.2 to 0.2, 0.1 to 0.2)
        assertTrue(Retouch.inside(square, true, 0.0, null, 0.15, 0.15, 0.001, 0.001))
        assertFalse(Retouch.inside(square, true, 0.0, null, 0.25, 0.15, 0.001, 0.001))
        assertTrue("near a brush line", Retouch.inside(listOf(0.1 to 0.5, 0.3 to 0.5), false, 0.02, null, 0.2, 0.51, 0.001, 0.001))
        assertTrue(Retouch.switchable("heal", "clone"))
        assertFalse(Retouch.switchable("heal", "fill"))
        val g = kotlinx.serialization.json.buildJsonObject {
            put("center", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(0.5), JsonPrimitive(0.5))))
        }
        val m = Retouch.moved(g, 0.1, -0.1)
        assertEquals(listOf(0.6, 0.4), (m["center"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).doubleOrNull!! })
    }
}

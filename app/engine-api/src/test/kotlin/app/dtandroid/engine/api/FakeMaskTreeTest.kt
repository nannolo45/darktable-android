// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.engine.fake.FakeModuleCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The stand-in engine's 6.9 calls (P3d stage 2a) behave as the bridge's: items, undo, tree edits, refusals. */
class FakeMaskTreeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val catalog by lazy { FakeModuleCatalog.fromSchemaJson(TestSchema.text) }

    private fun circle(x: Double, r: Double) = buildJsonObject { putJsonArray("center") { add(JsonPrimitive(x)); add(JsonPrimitive(0.5)) }; put("radius", r) }

    private suspend fun open(): FakeEngine {
        val e = FakeEngine(catalog, FakeEngine.Timing.NONE)
        e.init(EnginePaths("a", "b", "c", "d", "e"))
        e.open(File(tmp.root, "x.RAF").path)
        return e
    }

    private suspend fun FakeEngine.members(g: Int) = masks().first { it.id == g }.members
    private suspend fun FakeEngine.has(id: Int) = masks().any { it.id == id }

    private suspend fun expect(code: EngineError, block: suspend () -> Unit) {
        try { block(); fail("expected $code") } catch (x: EngineException) { assertEquals(code, x.error) }
    }

    @Test
    fun shapeAndBrushSessionAreOneItemEach() = runBlocking<Unit> {
        val e = open()
        val n0 = e.history().items.size
        val c = e.maskAdd("exposure", 0, "circle", circle(0.3, 0.1))
        assertEquals(n0 + 1, e.history().items.size)
        e.historyGroupBegin()
        expect(EngineError.STATE) { e.historyGroupBegin() }
        val strokes = (0 until 3).map { e.maskAdd("exposure", 0, "brush", buildJsonObject { putJsonArray("stroke") { add(kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(0.1 * it), JsonPrimitive(0.5)))) } }) }
        e.historyGroupEnd(true)
        assertEquals(n0 + 2, e.history().items.size)
        e.undo()
        assertFalse(strokes.any { e.has(it) })
        assertTrue(e.has(c))
        e.redo()
        assertTrue(strokes.all { e.has(it) })
        // cancel: no item, no form
        e.historyGroupBegin()
        val x = e.maskAdd("exposure", 0, "circle", circle(0.6, 0.1))
        e.historyGroupEnd(false)
        assertEquals(n0 + 2, e.history().items.size)
        assertFalse(e.has(x))
        expect(EngineError.STATE) { e.historyGroupEnd(true) }
    }

    @Test
    fun treeEditing() = runBlocking<Unit> {
        val e = open()
        val big = e.maskAdd("exposure", 0, "circle", circle(0.5, 0.3))
        val small = e.maskAdd("exposure", 0, "circle", circle(0.5, 0.1))
        val third = e.maskAdd("exposure", 0, "circle", circle(0.2, 0.1))
        val root = e.blendGet("exposure", 0).maskId!!
        assertEquals(listOf(big, small, third), e.members(root))
        e.maskMemberMove(root, small, root, 0)
        assertEquals(listOf(small, big, third), e.members(root))
        expect(EngineError.RANGE) { e.maskMemberMove(root, small, root, 5) }
        val w = e.maskGroupWrap(root, listOf(big))
        assertEquals(listOf(small, w, third), e.members(root))
        assertEquals(listOf(big), e.members(w))
        val gt = e.maskGroupWrap(root, listOf(third, small), "minimum")
        assertEquals(listOf(w, gt), e.members(root))
        assertEquals(listOf(small, third), e.members(gt))
        e.maskMemberUpdate(gt, third, MaskMember(inverse = true, opacity = 0.4))
        e.maskMemberMove(gt, third, w, -1)
        assertEquals(listOf(big, third), e.members(w))
        expect(EngineError.ARG) { e.maskMemberMove(root, gt, gt, 0) }
        expect(EngineError.ARG) { e.maskMemberUpdate(root, gt, MaskMember(opacity = 0.5)) }
        e.maskMemberRemove(w, third)
        assertEquals(listOf(big), e.members(w))
        assertTrue(e.has(third))
        e.undo()
        assertEquals(listOf(big, third), e.members(w))
        val p = e.maskAdd("exposure", 0, "parametric", buildJsonObject { put("channel", "g"); putJsonArray("in") { listOf(0.2, 0.3, 0.7, 0.8).forEach { add(JsonPrimitive(it)) } } }, group = w)
        assertEquals(p, e.members(w).last())
        expect(EngineError.ARG) { e.maskAdd("exposure", 0, "parametric", buildJsonObject { put("channel", "nope") }) }
    }
}

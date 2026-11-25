// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
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
 * Brief P4c item 3: diffuse or sharpen's built-in preset "lens deblur | medium" is stored as darktable stores its own
 * presets, "_builtin_lens deblur | medium" (gui/presets.c dt_gui_presets_add_generic adds BUILTIN_PREFIX); the P4b
 * benchmark asked the engine for the name as shown, which no row has. The presets sheet lists the built-in presets
 * under the "lens deblur" folder and applies them by their stored name; a caller with the shown name finds the stored
 * one with [DtNames.storedName].
 */
class P4cDiffusePresetTest {
    @get:Rule val tmp = TemporaryFolder()

    private val diffuse = InstanceKey("diffuse", 0)
    private val medium = "_builtin_lens deblur | medium"

    private fun controller(): DarkroomController {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900)
        c.stylesPresets.settings = MemoryStyleSettings()
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), 0, filmstrip = false, thumbnails = false) }
        return c
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    @Test fun theShownNameFindsTheStoredOne() {
        val names = listOf("_builtin_lens deblur | soft", medium, "my preset")
        assertEquals(medium, DtNames.storedName(names, "lens deblur | medium"))
        assertEquals("my preset", DtNames.storedName(names, "my preset"))
        assertEquals(medium, DtNames.storedName(names, medium))
        assertNull(DtNames.storedName(names, "lens deblur | strong"))
        assertEquals("lens deblur | medium", DtNames.display(medium))
    }

    @Test fun theSheetListsTheBuiltInPresetsInTheirFolder() = runBlocking<Unit> {
        val c = controller()
        waitFor("photo") { c.state.value.modules.any { it.key == diffuse } }
        c.stylesPresets.loadPresets(diffuse)
        val list = c.stylesPresets.state.value.presets
        assertEquals(diffuse, c.stylesPresets.state.value.presetsFor)
        val builtins = list.filter { it.builtin }.map { it.name }
        assertTrue(builtins.toString(), medium in builtins && "_builtin_lens deblur | soft" in builtins)
        val rows = NameTree.rows(list.map { it.name }, emptySet(), allOpen = true)
        val group = rows.filterIsInstance<TreeRow.Group>().single()
        assertEquals("lens deblur", group.label)
        assertEquals(listOf("medium", "soft"), rows.filterIsInstance<TreeRow.Leaf>().map { it.label })
        assertEquals(medium, rows.filterIsInstance<TreeRow.Leaf>().first { it.label == "medium" }.name)
    }

    @Test fun theSheetAppliesItAsOneHistoryItem() = runBlocking<Unit> {
        val c = controller()
        waitFor("photo") { c.state.value.modules.any { it.key == diffuse } }
        c.stylesPresets.loadPresets(diffuse)
        val n0 = c.state.value.history.items.size
        assertTrue(c.stylesPresets.applyPreset(diffuse, medium))
        assertEquals("one history item", n0 + 1, c.state.value.history.items.size)
        val m = c.state.value.modules.first { it.key == diffuse }
        assertTrue("switched on", m.enabled)
        assertEquals(16.0, (m.params["iterations"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals(10.0, (m.params["radius"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals(true, c.stylesPresets.state.value.presets.first { it.name == medium }.active)
    }

    @Test fun theEngineFindsPresetsByTheStoredNameOnly() = runBlocking<Unit> {
        val c = controller()
        waitFor("photo") { c.state.value.modules.any { it.key == diffuse } }
        // as desktop's presets table: the shown name is no row (the P4b benchmark's call)
        val e = runCatching { c.withEngine { it.presetApply("diffuse", 0, "lens deblur | medium") } }.exceptionOrNull()
        assertTrue(e.toString(), e is EngineException && e.error == EngineError.NOT_FOUND)
        // by the stored name found from the shown one, as the benchmark now does
        val stored = c.withEngine { en -> DtNames.storedName(en.presets("diffuse", 0).filter { it.builtin }.map { it.name }, "lens deblur | medium") }
        assertEquals(medium, stored)
        c.withEngine { it.presetApply("diffuse", 0, stored!!) }
        assertFalse(c.state.value.modules.first { it.key == diffuse }.params.isEmpty())
        assertEquals(16.0, (c.state.value.modules.first { it.key == diffuse }.params["iterations"] as JsonPrimitive).content.toDouble(), 0.0)
    }
}

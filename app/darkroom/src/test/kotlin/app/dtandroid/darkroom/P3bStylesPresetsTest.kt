// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.StyleMode
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
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
 * Brief P3b deliverable 7: every action of the presets and styles sheets through the darkroom on the stand-in engine:
 * presets (list, apply as one history item, store, overwrite question, update, rename, delete, refusals), styles
 * (tree, search, apply in both modes as one undo step, create, delete, favourites and recently used), the import of
 * .dtstyle and .dtpreset files (results per file, refusals, the question for an existing name with "for all"), and
 * the read-only photo.
 */
class P3bStylesPresetsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val exposure = InstanceKey("exposure", 0)
    private val builtin = "_builtin_scene-referred default"

    private fun controller(index: Int = 0, settings: StyleSettings = MemoryStyleSettings()): DarkroomController {
        val dir = tmp.root
        val engine = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, TestSetup.schema, TestSetup.builtins, TestSetup.layouts(dir),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), previewMaxSize = 900)
        c.stylesPresets.settings = settings
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), index, filmstrip = false, thumbnails = false) }
        return c
    }

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    private val DarkroomController.sp get() = stylesPresets.state.value
    private fun DarkroomController.module(op: String) = state.value.modules.first { it.op == op && it.priority == 0 }
    private fun DarkroomController.exposureValue() = (module("exposure").params["exposure"] as JsonPrimitive).content.toDouble()

    // ------------------------------------------------------------------ presets

    @Test fun presetListAndApplyIsOneHistoryItem() = runBlocking<Unit> {
        val c = controller()
        c.stylesPresets.loadPresets(exposure)
        val list = c.sp.presets
        assertEquals(exposure, c.sp.presetsFor)
        val p = list.first { it.name == builtin }
        assertTrue(p.builtin)
        assertEquals("scene-referred default", DtNames.leaf(p.name))
        assertEquals(false, p.active)
        val n0 = c.state.value.history.items.size
        assertTrue(c.stylesPresets.applyPreset(exposure, builtin))
        assertEquals("one history item", n0 + 1, c.state.value.history.items.size)
        assertEquals(0.7, c.exposureValue(), 1e-6)
        assertEquals(true, c.sp.presets.first { it.name == builtin }.active)
        assertEquals(builtin, c.sp.lastPreset[exposure])
        // undo takes it back in one step
        c.undo()
        assertEquals(n0, c.state.value.history.items.size)
    }

    @Test fun storeOverwriteUpdateRenameDelete() = runBlocking<Unit> {
        val c = controller()
        val sp = c.stylesPresets
        sp.loadPresets(exposure)
        assertFalse("desktop's placeholder is not a name", sp.storePreset(exposure, StylesPresets.NEW_PRESET, ""))
        assertEquals("please give preset a name", c.sp.note)
        assertTrue(sp.storePreset(exposure, "look|warm", "made by the test"))
        val stored = c.sp.presets.first { it.name == "look|warm" }
        assertFalse(stored.builtin)
        assertEquals("made by the test", stored.description)
        assertEquals("equals the current settings", true, stored.active)
        // the same name again: desktop's question first, nothing stored
        assertFalse(sp.storePreset(exposure, "look|warm", "second"))
        assertEquals(SpPage.PresetOverwrite("look|warm", "second"), c.sp.page)
        assertTrue(sp.storePreset(exposure, "look|warm", "second", overwrite = true))
        assertEquals("second", c.sp.presets.first { it.name == "look|warm" }.description)
        assertNull(c.sp.page)
        // a built-in name cannot be replaced
        assertFalse(sp.storePreset(exposure, builtin, ""))
        assertTrue(c.sp.note!!, c.sp.note!!.contains("write-protected"))
        // change the module: the preset is no longer the current one; "update" stores the new settings, description kept
        val slider = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<app.dtandroid.schema.Control.Slider>().first { it.label == "exposure" }
        c.setSlider(exposure, slider.binding, 1.3, false, slider.id)
        sp.loadPresets(exposure)
        assertEquals(false, c.sp.presets.first { it.name == "look|warm" }.active)
        assertTrue(sp.updatePreset(exposure, "look|warm"))
        val updated = c.sp.presets.first { it.name == "look|warm" }
        assertEquals(true, updated.active)
        assertEquals("second", updated.description)
        // rename; an existing name is refused; built-in presets cannot be renamed or deleted
        assertTrue(sp.renamePreset(exposure, "look|warm", "look|cool"))
        assertTrue(c.sp.presets.any { it.name == "look|cool" } && c.sp.presets.none { it.name == "look|warm" })
        assertTrue(sp.storePreset(exposure, "other", ""))
        assertFalse(sp.renamePreset(exposure, "other", "look|cool"))
        assertEquals("preset `look|cool' already exists", c.sp.note)
        assertFalse(sp.renamePreset(exposure, builtin, "x"))
        assertTrue(c.sp.note!!, c.sp.note!!.contains("built-in"))
        sp.deletePreset(exposure, builtin)
        assertTrue(c.sp.presets.any { it.name == builtin })
        sp.deletePreset(exposure, "look|cool")
        assertTrue(c.sp.presets.none { it.name == "look|cool" })
        // the folder tree of the user's presets
        sp.storePreset(exposure, "look|a", "")
        sp.storePreset(exposure, "look|b", "")
        val names = c.sp.presets.filter { !it.builtin }.map { it.name }
        val closed = NameTree.rows(names, emptySet())
        assertEquals(listOf("look", "other"), closed.map { (it as? TreeRow.Group)?.label ?: (it as TreeRow.Leaf).label })
        val open = NameTree.rows(names, setOf("look"))
        assertEquals(listOf("look", "a", "b", "other"), open.map { (it as? TreeRow.Group)?.label ?: (it as TreeRow.Leaf).label })
    }

    @Test fun readOnlyPhotoListsButAppliesNothing() = runBlocking<Unit> {
        val c = controller(index = InterfacePreview.READ_ONLY_INDEX)
        assertTrue(c.state.value.readOnly)
        val sp = c.stylesPresets
        sp.loadPresets(exposure)
        assertTrue("the list is shown", c.sp.presets.any { it.name == builtin })
        val h0 = c.state.value.history
        assertFalse(sp.applyPreset(exposure, builtin))
        assertTrue(c.sp.note!!, c.sp.note!!.contains("read-only"))
        assertEquals(h0, c.state.value.history)
        // a style neither
        val m = c.module("exposure")
        sp.import(listOf("one.dtstyle" to P3bFiles.style("one", "", listOf(P3bFiles.item(m, mapOf("exposure" to JsonPrimitive(1.0)))))), tmp.newFolder())
        assertNotNull(sp.style("one"))
        assertFalse(sp.applyStyle("one", StyleMode.APPEND))
        assertEquals(h0, c.state.value.history)
        assertTrue(c.sp.prefs.recent.isEmpty())
    }

    // ------------------------------------------------------------------ styles

    private fun pack(c: DarkroomController): List<Pair<String, ByteArray>> {
        val exp = c.module("exposure")
        val cb = c.module("colorbalancergb")
        return listOf(
            "pack one.dtstyle" to P3bFiles.style("pack|one", "warm and bright", listOf(P3bFiles.item(exp, mapOf("exposure" to JsonPrimitive(1.0))))),
            "pack two.dtstyle" to P3bFiles.style("pack|two", "", listOf(P3bFiles.item(exp, mapOf("exposure" to JsonPrimitive(-0.5))),
                P3bFiles.item(cb, mapOf("vibrance" to JsonPrimitive(0.3))))),
            "solo.dtstyle" to P3bFiles.style("solo", "only one", listOf(P3bFiles.item(cb, mapOf("vibrance" to JsonPrimitive(0.1))))),
            "notes.txt" to "not a style".toByteArray(),
            "unknown.dtstyle" to P3bFiles.style("pack|unknown", "", listOf(P3bFiles.Item("p3bnosuchmodule", 1, null))),
            "newer.dtstyle" to P3bFiles.style("pack|newer", "", listOf(P3bFiles.Item("exposure", exp.version + 1, null))),
        )
    }

    @Test fun importTreeSearchApplyBothModesUndo() = runBlocking<Unit> {
        val settings = MemoryStyleSettings()
        val c = controller(settings = settings)
        val sp = c.stylesPresets
        val lines = sp.import(pack(c), tmp.newFolder())
        assertEquals(listOf("pack one.dtstyle", "pack two.dtstyle", "solo.dtstyle", "notes.txt", "unknown.dtstyle", "newer.dtstyle"), lines.map { it.file })
        assertEquals(listOf(true, true, true, false, true, true), lines.map { it.imported })
        assertEquals("imported style `pack | one'", lines[0].text)
        assertEquals("not a darktable style or preset file", lines[3].text)
        assertTrue(lines[4].text, lines[4].text.contains("cannot be applied here") && lines[4].text.contains("p3bnosuchmodule") && lines[4].text.contains("unknown"))
        assertTrue(lines[5].text, lines[5].text.contains("newer than this app's"))
        assertTrue(c.sp.page is SpPage.ImportResult)
        // the tree: "pack" folded, then "solo"; opened: its four styles
        val names = c.sp.styles!!.map { it.name }
        assertEquals(listOf("pack", "solo"), NameTree.rows(names, emptySet()).map { (it as? TreeRow.Group)?.label ?: (it as TreeRow.Leaf).label })
        assertEquals(4, (NameTree.rows(names, emptySet())[0] as TreeRow.Group).count)
        assertEquals(listOf("pack", "newer", "one", "two", "unknown", "solo"),
            NameTree.rows(names, setOf("pack")).map { (it as? TreeRow.Group)?.label ?: (it as TreeRow.Leaf).label })
        // refused: not usable, not applied, nothing changed
        val h0 = c.state.value.history
        assertFalse(sp.applyStyle("pack|unknown", StyleMode.APPEND))
        assertTrue(c.sp.note!!, c.sp.note!!.contains("cannot be applied"))
        assertEquals(h0, c.state.value.history)
        // append: the style's items on top; one undo step takes it back
        assertTrue(sp.applyStyle("pack|two", StyleMode.APPEND))
        assertEquals(h0.items.size + 2, c.state.value.history.items.size)
        assertEquals(-0.5, c.exposureValue(), 1e-6)
        assertEquals(listOf("pack|two"), c.sp.prefs.recent)
        assertTrue(c.state.value.message!!, c.state.value.message!!.startsWith("applied style `pack | two'"))
        c.undo()
        assertEquals(h0, c.state.value.history)
        // overwrite: the default edit plus the style; a module switched on by hand before is gone
        val extra = c.state.value.modules.first { m -> m.canSwitch && !m.enabled && c.state.value.history.items.none { it.op == m.op } }
        c.setEnabled(extra.key, true)
        assertTrue(c.state.value.history.items.any { it.op == extra.op })
        assertTrue(sp.applyStyle("pack|one", StyleMode.OVERWRITE))
        val h2 = c.state.value.history
        assertTrue("overwrite drops the hand edit", h2.items.none { it.op == extra.op })
        assertEquals("exposure", h2.items.last().op)
        assertEquals(1.0, c.exposureValue(), 1e-6)
        assertEquals("the mode is remembered", StyleMode.OVERWRITE, settings.load().mode)
        assertEquals(listOf("pack|one", "pack|two"), settings.load().recent)
        c.undo()
        assertTrue("one undo step back", c.state.value.history.items.any { it.op == extra.op })
    }

    @Test fun importAsksBeforeReplacingWithForAll() = runBlocking<Unit> {
        val c = controller()
        val sp = c.stylesPresets
        val files = pack(c).take(3)
        sp.import(files, tmp.newFolder())
        // the same three again: the question for the first, "skip" for it alone
        val work = tmp.newFolder()
        val run = CoroutineScope(Dispatchers.Default).async { sp.import(files, work) }
        waitFor("question") { c.sp.page is SpPage.ImportAsk }
        val q = (c.sp.page as SpPage.ImportAsk).q
        assertTrue(q.style)
        assertEquals("pack|one", q.name)
        assertEquals(2, q.more)
        sp.answer(ImportAnswer.SKIP, forAll = false)
        waitFor("second question") { (c.sp.page as? SpPage.ImportAsk)?.q?.name == "pack|two" }
        sp.answer(ImportAnswer.OVERWRITE, forAll = true)
        val lines = run.await()
        assertEquals("skipped: style `pack | one' exists and was kept", lines[0].text)
        assertEquals("replaced style `pack | two'", lines[1].text)
        assertEquals("for all: no third question", "replaced style `solo'", lines[2].text)
        // cancel: nothing of the rest is imported
        val run2 = CoroutineScope(Dispatchers.Default).async { sp.import(files, tmp.newFolder()) }
        waitFor("question again") { c.sp.page is SpPage.ImportAsk }
        assertTrue("back cancels", sp.back())
        val lines2 = run2.await()
        assertTrue(lines2.all { !it.imported && it.text.contains("cancelled") })
    }

    @Test fun presetFilesImportAndRefusals() = runBlocking<Unit> {
        val c = controller()
        val sp = c.stylesPresets
        sp.loadPresets(exposure)
        val m = c.module("exposure")
        val files = listOf(
            "bright.dtpreset" to P3bFiles.preset("bright", "exposure", m.version, kotlinx.serialization.json.JsonObject(m.params + ("exposure" to JsonPrimitive(1.5))), "from a file"),
            "old.dtpreset" to P3bFiles.preset("old", "exposure", m.version - 1, null),
            "unknown.dtpreset" to P3bFiles.preset("u", "p3bnosuchmodule", 1, null),
            "newer.dtpreset" to P3bFiles.preset("n", "exposure", m.version + 1, null),
            "builtin.dtpreset" to P3bFiles.preset(builtin, "exposure", m.version, null),
            // desktop's preferences → presets → export writes module layouts with the presets
            "modulegroups_test layout.dtpreset" to app.dtandroid.schema.layout.DtPreset.writeLayout("test layout",
                TestSetup.builtins.presets(TestSetup.builtins.defaultWorkflow).first().params),
        )
        val lines = sp.import(files, tmp.newFolder())
        assertEquals("imported preset `bright' of exposure", lines[0].text)
        assertTrue(lines[1].text, lines[1].imported && lines[1].text.contains("older version"))
        assertTrue(lines[2].text, !lines[2].imported && lines[2].text.contains("unknown to this app"))
        assertTrue(lines[3].text, !lines[3].imported && lines[3].text.contains("newer than this app's"))
        assertTrue(lines[4].text, !lines[4].imported && lines[4].text.contains("built-in"))
        assertTrue(lines[5].text, lines[5].imported && lines[5].text.contains("module layout `test layout'"))
        assertTrue(c.layouts.list().any { it.name == "test layout" && !it.writeProtected })
        // the open list was read again: the old one greyed out (not usable), the new one applies
        val bright = c.sp.presets.first { it.name == "bright" }
        assertTrue(bright.usable)
        assertFalse(c.sp.presets.first { it.name == "old" }.usable)
        assertTrue(sp.applyPreset(exposure, "bright"))
        assertEquals(1.5, c.exposureValue(), 1e-6)
        // the same file again: asked; overwrite replaces it
        val run = CoroutineScope(Dispatchers.Default).async { sp.import(files.take(1), tmp.newFolder()) }
        waitFor("question") { c.sp.page is SpPage.ImportAsk }
        assertFalse((c.sp.page as SpPage.ImportAsk).q.style)
        sp.answer(ImportAnswer.OVERWRITE, forAll = false)
        assertEquals("replaced preset `bright' of exposure", run.await()[0].text)
    }

    @Test fun createAndDeleteStyle() = runBlocking<Unit> {
        val c = controller()
        val sp = c.stylesPresets
        sp.loadStyles()
        val slider = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<app.dtandroid.schema.Control.Slider>().first { it.label == "exposure" }
        c.setSlider(exposure, slider.binding, 0.9, false, slider.id)
        val h = c.state.value.history
        assertFalse(sp.createStyle("  ", "", null))
        assertEquals("please give style a name", c.sp.note)
        assertTrue(sp.createStyle("mine|first", "made here", null))
        val st = sp.style("mine|first")!!
        assertEquals("made here", st.description)
        assertTrue(st.items.any { it.op == "exposure" })
        assertEquals("creating does not change the edit", h, c.state.value.history)
        // the same name: asked first
        assertFalse(sp.createStyle("mine|first", "", listOf(h.end - 1)))
        assertEquals(SpPage.StyleOverwrite("mine|first", "", listOf(h.end - 1)), c.sp.page)
        assertTrue(sp.createStyle("mine|first", "", listOf(h.end - 1), overwrite = true))
        assertEquals(listOf("exposure"), sp.style("mine|first")!!.items.map { it.op })
        // favourite, then delete: gone from the list and the favourites
        sp.toggleFavorite("mine|first")
        assertEquals(listOf("mine|first"), c.sp.prefs.favorites)
        sp.deleteStyle("mine|first")
        assertNull(sp.style("mine|first"))
        assertTrue(c.sp.prefs.favorites.isEmpty())
        // shipped styles are recognised (darktable's names carry _l10n_ on each segment)
        assertTrue(DtNames.shipped("_l10n_darktable|_l10n_examples|_l10n_effects|_l10n_day for twilight"))
        assertEquals("darktable | examples | effects | day for twilight", DtNames.display("_l10n_darktable|_l10n_examples|_l10n_effects|_l10n_day for twilight"))
        assertFalse(DtNames.shipped("mine|first"))
    }

    @Test fun settingsFileKeepsFavouritesRecentAndMode() {
        val f = File(tmp.root, "styles/settings.json")
        val s = FileStyleSettings(f)
        assertEquals(StylePrefs(), s.load())
        val p = StylePrefs(StyleMode.OVERWRITE, listOf("a|b", "c"), listOf("c", "d"))
        s.save(p)
        assertEquals(p, FileStyleSettings(f).load())
        f.writeText("not json")
        assertEquals("an unreadable file counts as none", StylePrefs(), FileStyleSettings(f).load())
    }

    @Test fun prefetchReadsTheListOnceAndFavouritesComeFirst() = runBlocking<Unit> {
        val c = controller()
        val sp = c.stylesPresets
        sp.import(pack(c).take(3), tmp.newFolder())
        sp.toggleFavorite("solo")
        val list = c.sp.styles
        sp.prefetch()
        assertTrue("prefetch keeps the cache", list === c.sp.styles)
        assertNotNull(c.sp.listMs)
        assertEquals(listOf("solo"), c.sp.prefs.favorites)
        assertTrue(sp.applyStyle("pack|one", StyleMode.APPEND))
        assertEquals(listOf("pack|one"), c.sp.prefs.recent)
        // the sheet closed: pages go, the cache stays
        c.setOverlay(Overlay.Styles)
        sp.setPage(SpPage.StyleDetail("solo"))
        c.setOverlay(null)
        assertNull(c.sp.page)
        assertTrue(list != null && c.sp.styles != null)
    }

    @Test fun treeOfNamesThatAreAlsoFolders() {
        val rows = NameTree.rows(listOf("b", "A|x", "a", "B|y|z"), setOf("B", "B|y"))
        // case ignored in the order (ties: as stored); the folder "A" holds "x"; "B" open with its folder "y" open
        val labels = rows.map { r -> when (r) { is TreeRow.Group -> "[${r.label} ${r.count}]@${r.depth}"; is TreeRow.Leaf -> "${r.label}@${r.depth}" } }
        assertEquals(listOf("[A 1]@0", "a@0", "[B 1]@0", "[y 1]@1", "z@2", "b@0"), labels)
        assertEquals(7, NameTree.rows(listOf("b", "A|x", "a", "B|y|z"), emptySet(), allOpen = true).size)
    }

    // ------------------------------------------------------------------ deliverable 6 (P2j section 6), the fixed-value actions

    @Test fun framingImageAndConstantBorderAndLensLatestAlgorithmGoThroughModuleActions() = runBlocking<Unit> {
        val c = controller()
        val borders = InstanceKey("borders", 0)
        val list = TestSetup.schema.modules.getValue("borders").computed.getValue(2) as app.dtandroid.schema.ComputedList
        val entries = list.entries(c.module("borders").params)
        val n0 = c.state.value.history.items.size
        c.chooseComputed(borders, 2, entries.indexOfFirst { it.label == "image" })
        assertNull(c.state.value.message)
        assertEquals(0.0, (c.module("borders").params["aspect"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals("image", entries[list.current(c.module("borders").params)!!].label)
        c.chooseComputed(borders, 2, entries.indexOfFirst { it.label == "constant border" })
        assertEquals(-1.0, (c.module("borders").params["aspect"] as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals("one history item each", n0 + 2, c.state.value.history.items.size)
        // lens: shown while md_version is the old one; switched on, the action writes the new algorithm and it hides
        val lens = InstanceKey("lens", 0)
        val ms = TestSetup.schema.modules.getValue("lens")
        val visible = { app.dtandroid.schema.Rules.holds(ms.visibility.getValue(24), app.dtandroid.schema.ParamRuleInputs(ms, c.module("lens").params)) }
        assertTrue(visible())
        c.toggleComputed(lens, 24, true)
        assertNull(c.state.value.message)
        assertEquals("DT_IOP_LENS_EMBEDDED_METADATA_VERSION_2", (c.module("lens").params["md_version"] as JsonPrimitive).content)
        assertFalse(visible())
    }
}

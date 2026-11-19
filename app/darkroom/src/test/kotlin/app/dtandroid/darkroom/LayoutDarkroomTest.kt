// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.LayoutEditor.Dialog
import app.dtandroid.darkroom.LayoutEditor.NameAction
import app.dtandroid.schema.layout.DtPreset
import app.dtandroid.schema.layout.LayoutCodec
import app.dtandroid.schema.layout.QuickAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The darkroom on darktable's module layouts, and "manage module layouts", on the fake engine. */
class LayoutDarkroomTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val b get() = TestSetup.builtins

    private fun desktopDefault() = LayoutCodec.parse(b.presets(b.defaultWorkflow).first { it.name == b.fallbackLayout }.params)

    @Test
    fun defaultLayoutIsDarktablesSceneReferredLayout() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val s = c.state.value
        val desktop = desktopDefault()
        assertEquals("workflow: scene-referred", s.layoutName)
        assertEquals(desktop, s.layout)
        // darktable's default selected group (plugins/darkroom/groups = 2): the second group
        assertEquals(GroupTab.Group(1), s.group)
        val report = StringBuilder("## Default layout: darkroom on the fake engine vs desktop darktable 04477d3, workflow \"${b.defaultWorkflow}\"\n\n")
        report.append("Tabs: active, quick access, ${desktop.groups.joinToString { "${it.name} (${it.icon})" }}; search line ${if (desktop.showSearch) "on" else "off"}.\n\n")
        report.append("| group | desktop's modules (stored order) | modules the app lists (top to bottom) | same set |\n|---|---|---|---|\n")
        for ((i, g) in desktop.groups.withIndex()) {
            val shown = c.visibleModules(s.copy(group = GroupTab.Group(i))).map { it.op }
            // every module of the group exists in the fake engine's pipe and is listed once; none is deprecated
            val expected = g.modules.filter { op -> s.modules.any { it.op == op && !it.hidden } }
            assertEquals(g.name, expected.toSet(), shown.toSet())
            report.append("| ${g.name} | ${g.modules.joinToString(" ")} | ${shown.joinToString(" ")} | ${expected.toSet() == shown.toSet()} |\n")
        }
        val blocks = c.quickPanel(s)
        report.append("\n### Quick access panel (top to bottom)\n\n| module | widgets | app |\n|---|---|---|\n")
        for (bl in blocks) {
            report.append("| ${bl.moduleName} | ${(listOfNotNull(bl.onOff) + bl.entries).joinToString { it.label }} | " +
                "${(listOfNotNull(bl.onOff) + bl.entries).joinToString { if (it.mapped) "usable" else "disabled" }} |\n")
        }
        val out = File(System.getProperty("dta.reportDir") ?: "build/reports/p2d").apply { mkdirs() }
        File(out, "default-layout-comparison.md").writeText(report.toString())
        // every entry of desktop's list is somewhere in the panel, nothing dropped, no white balance
        assertEquals(desktop.quickAccess.toSet(), blocks.flatMap { listOfNotNull(it.onOff) + it.entries }.map { it.id }.toSet())
        assertTrue(blocks.none { it.instance?.op == "temperature" })
        // ordered as the module list: last in the pipe first; sigmoid (tone mapper) above color calibration
        val ops = blocks.mapNotNull { it.instance?.op }
        assertTrue(ops.indexOf("sigmoid") < ops.indexOf("channelmixerrgb"))
        // within a module, in the order of its panel: illuminant before temperature in color calibration
        val cc = blocks.first { it.instance?.op == "channelmixerrgb" }.entries.map { it.id }
        assertTrue(cc.indexOf("channelmixerrgb/illuminant") < cc.indexOf("channelmixerrgb/temperature"))
    }

    @Test
    fun groupsTabsSearchAndActive() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        c.selectGroup(GroupTab.Group(0))
        assertTrue(c.visibleModules(c.state.value).any { it.op == "exposure" })
        // tapping the selected group again shows all modules of all groups (desktop's DT_MODULEGROUP_NONE)
        c.selectGroup(GroupTab.Group(0))
        assertEquals(GroupTab.AllModules, c.state.value.group)
        val all = c.visibleModules(c.state.value).map { it.op }.toSet()
        assertTrue("exposure" in all && "channelmixerrgb" in all && "diffuse" in all)
        assertFalse("not in any group of the default layout and off", "bloom" in all)
        // search: by name, by alias, case-insensitive; deprecated modules only when on
        c.setSearch("Color Cal")
        assertEquals(listOf("channelmixerrgb"), c.visibleModules(c.state.value).map { it.op })
        val alias = TestSetup.schema.modules.values.first { it.aliases.isNotBlank() && !it.deprecated && it.hasPanel }
        c.setSearch(alias.aliases.split(",").first().trim())
        assertTrue(c.visibleModules(c.state.value).any { it.op == alias.op })
        c.setSearch("channel mixer")
        assertTrue("deprecated and off", c.visibleModules(c.state.value).none { it.deprecated })
        c.selectGroup(GroupTab.Active)
        assertEquals("", c.state.value.search)
        assertTrue(c.visibleModules(c.state.value).all { it.enabled })
    }

    @Test
    fun quickAccessFollowsInstancesAndVisibility() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val key = InstanceKey("colorbalancergb", 0)
        assertFalse(c.quickPanel(c.state.value).first { it.instance?.op == "colorbalancergb" }.severalInstances)
        c.instanceNew(key)
        assertTrue(c.quickPanel(c.state.value).first { it.instance?.op == "colorbalancergb" }.severalInstances)
        // F source is shown only for a fluorescent illuminant, as in the module
        val cc = c.state.value.modules.first { it.op == "channelmixerrgb" }
        val fSource = c.state.value.quick.first { it.id == "channelmixerrgb/F source" }
        assertFalse(c.quickVisible(cc, fSource))
        assertTrue(c.quickVisible(cc, c.state.value.quick.first { it.id == "channelmixerrgb/illuminant" }))
        // "go to the full module": the first group that has it, expanded
        c.goToModule(InstanceKey("channelmixerrgb", 0))
        assertEquals(GroupTab.Group(1), c.state.value.group)
        assertEquals(InstanceKey("channelmixerrgb", 0), c.state.value.expanded)
    }

    @Test
    fun chooseLayoutFromTheMenu() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        assertEquals(listOf("modules: all", "search only", "workflow: beginner", "workflow: display-referred", "workflow: scene-referred"),
            c.state.value.layoutList.map { it.name })
        c.chooseLayout("search only")
        val s = c.state.value
        assertTrue(s.layout.groups.isEmpty())
        assertFalse(s.layout.quickAccessShown)
        assertEquals("the selected group no longer exists", GroupTab.Active, s.group)
        c.chooseLayout("workflow: display-referred")
        assertTrue(c.state.value.quick.any { it.op == "temperature" }) // display-referred keeps white balance in quick access
    }

    @Test
    fun editExportImportIsIdentical() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val ed = c.layoutEditor
        c.openLayoutEditor()
        assertTrue(ed.state.value.readOnly)
        ed.setShowSearch(false)
        assertTrue("built-in layouts are not changed", ed.state.value.layout.showSearch)
        assertNull(ed.applyName(NameAction.DUPLICATE, "my layout"))
        assertEquals("my layout", ed.state.value.name)
        assertFalse(ed.state.value.readOnly)
        // edit everything the editor can edit
        ed.setFullActive(true)
        ed.addWidget("agx/curve/contrast")
        ed.removeWidget("colorbalancergb/global saturation")
        ed.addGroup()
        val last = ed.state.value.layout.groups.lastIndex
        assertNull(ed.renameGroup(last, "my look"))
        ed.setGroupIcon(last, "favorites")
        ed.addModule(last, "grain")
        ed.addModule(last, "vignette")
        ed.moveGroup(last, up = true)
        ed.removeModule(0, "flip")
        assertNotNull(ed.renameGroup(0, "a|b"))
        val edited = ed.state.value.layout
        val (fileName, bytes) = ed.export()
        assertEquals("modulegroups_my layout.dtpreset", fileName)
        // stored as edited
        assertEquals(LayoutCodec.write(edited), TestSetup.layouts(tmp.root).read("my layout")!!.params)
        // remove it, import the exported file: identical
        ed.delete()
        assertNull(TestSetup.layouts(tmp.root).read("my layout"))
        val msg = ed.import(listOf(fileName to bytes))
        assertEquals(listOf("$fileName: imported \"my layout\""), msg)
        assertEquals(edited, ed.state.value.layout)
        assertArrayEquals(LayoutCodec.write(edited).encodeToByteArray(), TestSetup.layouts(tmp.root).read("my layout")!!.params.encodeToByteArray())
        // exported again: the same file
        assertArrayEquals(bytes, ed.export().second)
    }

    @Test
    fun editorRulesAsOnDesktop() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val ed = c.layoutEditor
        c.openLayoutEditor()
        assertEquals("a preset with this name already exists!", ed.nameProblem("modules: all"))
        assertEquals("workflow: scene-referred_1", ed.defaultName(NameAction.DUPLICATE))
        assertNull(ed.applyName(NameAction.NEW, "new"))
        val l = ed.state.value.layout
        // "new" is darktable's minimal layout for the workflow, read as darktable reads it
        assertEquals(LayoutCodec.parse(b.newLayout(b.defaultWorkflow)), l)
        // the last group cannot go while neither search nor quick access is shown
        ed.setQuickAccessShown(false)
        ed.setShowSearch(false)
        assertFalse(ed.canRemoveGroup())
        ed.removeGroup(0)
        assertEquals(1, ed.state.value.layout.groups.size)
        ed.setShowSearch(true)
        ed.removeGroup(0)
        assertTrue(ed.state.value.layout.groups.isEmpty())
        ed.setShowSearch(false)
        assertTrue("search cannot go off without groups or quick access", ed.state.value.layout.showSearch)
        // reset brings back the layout as chosen
        ed.reset()
        assertEquals(l, ed.state.value.layout)
        // add module: recommended by the group's name
        val cand = ed.moduleCandidates(0)
        assertTrue(cand.first { it.op == "temperature" }.recommended)
        assertFalse("already in the group", cand.any { it.op == "exposure" })
        // closing keeps the edits; the darkroom shows the new current layout only once chosen
        ed.setFullActive(true)
        c.closeLayoutEditor()
        assertTrue(TestSetup.layouts(tmp.root).read("new")!!.params.startsWith("1|1"))
        assertEquals("workflow: scene-referred", c.state.value.layoutName)
        c.chooseLayout("new")
        assertTrue(c.state.value.layout.fullActive)
        ed.ask(Dialog.AddWidget)
        assertTrue(ed.widgetCandidates().any { it.recommended && it.id == "exposure/exposure" })
    }

    @Test
    fun importsTheFileDesktopExported() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val ed = c.layoutEditor
        c.openLayoutEditor()
        val desk = javaClass.classLoader!!.getResourceAsStream("dtpreset/desktop-export.dtpreset")!!.readBytes()
        val wrong = DtPreset.writeLayout("modules: all", "1|0ꬹ0||")
        val msg = ed.import(listOf("modulegroups_probe layout.dtpreset" to desk, "clash.dtpreset" to wrong, "junk.dtpreset" to "x".toByteArray()))
        assertEquals("modulegroups_probe layout.dtpreset: imported \"probe layout\"", msg[0])
        assertTrue(msg[1].contains("built-in"))
        assertTrue(msg[2].startsWith("junk.dtpreset: not a darktable preset file"))
        c.closeLayoutEditor()
        c.chooseLayout("probe layout")
        val s = c.state.value
        assertEquals(listOf("my edits", "unusual"), s.layout.groups.map { it.name })
        // a module the app does not know stays in the layout and is simply not listed
        assertTrue("nosuchmodule" in s.layout.groups[1].modules)
        assertEquals(listOf("exposure/exposure", "colorbalancergb/global vibrance", "lens"), s.quick.map { it.id })
        assertEquals(QuickAccess.Kind.ON_OFF, s.quick.last().kind)
    }
}

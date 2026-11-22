// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.darkroom.ui.SliderDrag
import app.dtandroid.darkroom.ui.l1.L1Model
import app.dtandroid.darkroom.ui.l1.L1Tool
import app.dtandroid.schema.Control
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Brief PUI3a: the l1 layout's logic on the stand-in engine (no drawing): the skin, the sections built from the
 * module layout with their marks, tools, the active list's order, and what of the chrome shows while an on-photo
 * tool is open or a slider is dragged. The layout's taps (one module open at a time, power on a closed row, a row
 * in "active" opening its group) are tested on the drawn screen in PUI3aL1ScreenshotTest.
 */
class PUI3aL1ModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) =
        try { withTimeout(ms) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    @Test
    fun skinL1SwitchesTheLayoutAndRoundTrips() {
        assertEquals(Skin.L1, Skin.fromKey(Skin.L1.key))
        assertEquals("l1", Skin.L1.key)
        assertTrue(DtTokens.load(Skin.L1).l1.layout)
        assertFalse(DtTokens.load(Skin.MODERN).l1.layout)
        assertFalse(DtTokens.load().l1.layout)
    }

    @Test
    fun sectionsComeFromTheLayoutWithQuickFirstAndActiveLast() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val s = c.state.value
        val sections = L1Model.sections(s)
        val expected = buildList {
            if (s.layout.quickAccessShown) add(GroupTab.Quick)
            s.layout.groups.indices.forEach { add(GroupTab.Group(it)) }
            add(GroupTab.Active)
        }
        assertEquals(expected, sections.map { it.tab })
        assertEquals(s.layout.groups.map { it.name }, sections.filter { it.tab is GroupTab.Group }.map { it.label })
        assertEquals("active", sections.last().label)
        // the same with the quick access panel hidden: no `quick`
        val noQuick = L1Model.sections(s.copy(layout = s.layout.copy(quickAccessShown = false)))
        assertEquals(GroupTab.Group(0), noQuick.first().tab)
        val withQuick = L1Model.sections(s.copy(layout = s.layout.copy(quickAccessShown = true)))
        assertEquals(GroupTab.Quick, withQuick.first().tab)
    }

    @Test
    fun aGroupIsMarkedWhenItHoldsAnEnabledNonDefaultModule() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val s0 = c.state.value
        // every mark agrees with the rule
        fun check(s: DarkroomState) {
            val changed = s.modules.filter { L1Model.changed(it) }.map { it.op }.toSet()
            for (sec in L1Model.sections(s)) {
                val g = (sec.tab as? GroupTab.Group)?.let { s.layout.groups[it.index] }
                assertEquals(sec.label, g != null && g.modules.any { it in changed }, sec.marked)
            }
        }
        check(s0)
        // every module at its default: no mark
        val clean = s0.copy(modules = s0.modules.map { it.copy(enabled = it.defaultEnabled, inHistory = false) })
        assertTrue(L1Model.sections(clean).none { it.marked })
        // a default-on module edited in the history marks the groups that hold it
        val g0 = clean.layout.groups.indices.first { i -> clean.modules.any { it.op in clean.layout.groups[i].modules && !it.hidden } }
        val edited = clean.modules.first { it.op in clean.layout.groups[g0].modules && !it.hidden }
        val s1 = clean.copy(modules = clean.modules.map { if (it.key == edited.key) it.copy(enabled = true, defaultEnabled = true, inHistory = true) else it })
        assertTrue(L1Model.sections(s1).first { it.tab == GroupTab.Group(g0) }.marked)
        check(s1)
        // switched off again: not marked
        val s2 = clean.copy(modules = clean.modules.map { if (it.key == edited.key) it.copy(enabled = false, defaultEnabled = true, inHistory = true) else it })
        assertFalse(L1Model.sections(s2).first { it.tab == GroupTab.Group(g0) }.marked)
        // on the stand-in engine: a module that is off by default, switched on, marks every group that holds it
        val m = s0.modules.first { op -> s0.layout.groups.any { op.op in it.modules } && op.canSwitch && !op.enabled && !op.defaultEnabled && !op.hidden }
        c.setEnabled(m.key, true)
        val s3 = c.state.value
        for (sec in L1Model.sections(s3)) {
            val g = (sec.tab as? GroupTab.Group)?.let { s3.layout.groups[it.index] } ?: continue
            if (m.op in g.modules) assertTrue("${m.op} on marks group ${g.name}", sec.marked)
        }
        check(s3)
        // quick and active carry no mark
        assertTrue(L1Model.sections(s3).filter { it.tab !is GroupTab.Group }.none { it.marked })
    }

    @Test
    fun choosingTheChosenSectionAgainKeepsIt() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        L1Model.selectSection(c, c.state.value, GroupTab.Group(0))
        assertEquals(GroupTab.Group(0), c.state.value.group)
        // the other layouts' tabs turn this into "all modules"; l1 has no such tab
        L1Model.selectSection(c, c.state.value, GroupTab.Group(0))
        assertEquals(GroupTab.Group(0), L1Model.selectedSection(c.state.value))
        L1Model.selectSection(c, c.state.value, GroupTab.Active)
        assertEquals(GroupTab.Active, c.state.value.group)
    }

    @Test
    fun toolsMapToTheDarkroomsViewsAndTheStylesList() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        assertEquals(L1Tool.MODULES, L1Model.tool(c.state.value))
        L1Model.selectTool(c, c.state.value, L1Tool.STYLES)
        assertEquals(Overlay.Styles, c.state.value.overlay)
        assertEquals(L1Tool.STYLES, L1Model.tool(c.state.value))
        L1Model.selectTool(c, c.state.value, L1Tool.MASKS)
        assertNull(c.state.value.overlay)
        assertEquals(BottomView.MASKS, c.state.value.view)
        assertEquals(L1Tool.MASKS, L1Model.tool(c.state.value))
        L1Model.selectTool(c, c.state.value, L1Tool.MORE)
        assertEquals(BottomView.HISTORY, c.state.value.view)
        assertEquals(L1Tool.MORE, L1Model.tool(c.state.value))
        // a section chosen from styles: back to the modules
        L1Model.selectTool(c, c.state.value, L1Tool.STYLES)
        L1Model.selectSection(c, c.state.value, GroupTab.Active)
        assertNull(c.state.value.overlay)
        assertEquals(L1Tool.MODULES, L1Model.tool(c.state.value))
    }

    @Test
    fun activeListsEnabledModulesLastAppliedOnTop() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val off = c.state.value.modules.first { it.canSwitch && !it.enabled && !it.hidden && c.schema.modules[it.op]?.hasPanel != false }
        c.setEnabled(off.key, true)
        L1Model.selectSection(c, c.state.value, GroupTab.Active)
        val s = c.state.value
        val list = c.visibleModules(s)
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.enabled })
        assertTrue(list.any { it.key == off.key })
        // last in the pipe (applied last) on top
        assertEquals(list.sortedByDescending { it.iopOrder }.map { it.key }, list.map { it.key })
    }

    @Test
    fun rowsAreLeftOutWhileAnOnPhotoToolIsOpen() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        assertTrue(L1Model.chrome(c.state.value, drawing = false, drag = null).rows)
        // drawing a shape
        assertFalse(L1Model.chrome(c.state.value, drawing = true, drag = null).rows)
        // the crop frame: crop's panel opened
        val crop = InstanceKey("crop", 0)
        c.toggleExpanded(crop)
        c.syncCropTool()
        waitFor("crop tool") { c.state.value.cropTool != null }
        assertFalse(L1Model.chrome(c.state.value, drawing = false, drag = null).rows)
        // "done": the panel closed, the rows come back
        c.toggleExpanded(crop)
        c.syncCropTool()
        waitFor("crop tool closed") { c.state.value.cropTool == null }
        assertTrue(L1Model.chrome(c.state.value, drawing = false, drag = null).rows)
    }

    @Test
    fun aDragHidesTheChromeAndShowsThePill() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root, thumbnails = false)
        val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
        val d = SliderDrag(InstanceKey("exposure", 0), e.id, "exposure", "exposure", "+1.00 EV")
        val ch = L1Model.chrome(c.state.value, drawing = false, drag = d)
        assertFalse(ch.topBar)
        assertTrue(ch.fading)
        assertEquals("exposure · exposure · +1.00 EV", ch.pill)
        val rest = L1Model.chrome(c.state.value, drawing = false, drag = null)
        assertTrue(rest.topBar)
        assertFalse(rest.fading)
        assertNull(rest.pill)
    }
}

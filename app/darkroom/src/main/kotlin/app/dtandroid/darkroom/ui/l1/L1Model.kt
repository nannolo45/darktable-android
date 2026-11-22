// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui.l1

import app.dtandroid.darkroom.BottomView
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.GroupTab
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.ui.SliderDrag
import app.dtandroid.engine.api.ModuleInstance

/**
 * Brief PUI3a: what the l1 layout shows, worked out from the darkroom's state alone, so it is tested on the JVM
 * without drawing. Every action goes through the existing [DarkroomController] calls; the layout keeps no edit state
 * of its own.
 */

/** The tools row, left to right. */
enum class L1Tool(val label: String) { STYLES("styles"), MODULES("modules"), MASKS("masks"), MORE("more") }

/** One entry of the sections row: `quick`, the layout's groups in their order, `active`. */
data class L1Section(
    val tab: GroupTab,
    val label: String,
    /** the group's darktable icon name; null for `quick` and `active`, which have their own icons */
    val icon: String?,
    /** the group holds an enabled module that is not at its default (switched on by the user or edited) */
    val marked: Boolean,
)

/** What of the layout's chrome shows. */
data class L1Chrome(
    /** the top bar over the photo (hidden while a slider is dragged) */
    val topBar: Boolean,
    /** the sections and tools rows: left out while an on-photo tool is open */
    val rows: Boolean,
    /** the rows, the panel and the top bar fade while a slider is dragged; only that slider stays */
    val fading: Boolean,
    /** `module · control · value` while a slider is dragged */
    val pill: String?,
)

object L1Model {
    /** The chosen tool: the styles while their list is open, otherwise the darkroom's bottom view. */
    fun tool(s: DarkroomState): L1Tool = when {
        s.overlay == Overlay.Styles -> L1Tool.STYLES
        s.view == BottomView.MASKS -> L1Tool.MASKS
        s.view == BottomView.HISTORY -> L1Tool.MORE
        else -> L1Tool.MODULES
    }

    fun selectTool(controller: DarkroomController, s: DarkroomState, t: L1Tool) {
        // leaving the styles goes through setOverlay, so their pages are dropped as when their sheet closes
        if (t != L1Tool.STYLES && s.overlay == Overlay.Styles) controller.setOverlay(null)
        when (t) {
            L1Tool.STYLES -> if (s.overlay == Overlay.Styles) controller.setOverlay(null) else controller.setOverlay(Overlay.Styles)
            L1Tool.MODULES -> controller.setView(BottomView.MODULES)
            L1Tool.MASKS -> controller.setView(BottomView.MASKS)
            L1Tool.MORE -> controller.setView(BottomView.HISTORY)
        }
    }

    /** A module that is on and not at its default: switched on although off by default, or changed in the history. */
    fun changed(m: ModuleInstance): Boolean = m.enabled && !m.hidden && (!m.defaultEnabled || m.inHistory)

    /**
     * The sections of the active module layout: `quick` first when the layout shows the quick access panel, then
     * its groups in their order, `active` last. A group is marked when it holds a [changed] module.
     */
    fun sections(s: DarkroomState): List<L1Section> = buildList {
        if (s.layout.quickAccessShown) add(L1Section(GroupTab.Quick, "quick", null, marked = false))
        val changedOps = s.modules.filter { changed(it) }.map { it.op }.toSet()
        s.layout.groups.forEachIndexed { i, g ->
            add(L1Section(GroupTab.Group(i), g.name, g.icon, marked = g.modules.any { it in changedOps }))
        }
        add(L1Section(GroupTab.Active, "active", null, marked = false))
    }

    /** The chosen section; none while the list shows all modules or a search. */
    fun selectedSection(s: DarkroomState): GroupTab? =
        s.group.takeIf { it != GroupTab.AllModules && s.search.isEmpty() && s.forceShow == null }

    /**
     * Choose a section. The controller's group tabs turn a second tap on the chosen group into "all modules", as on
     * desktop; l1 has no such tab, so a tap on the chosen section changes nothing.
     */
    fun selectSection(controller: DarkroomController, s: DarkroomState, tab: GroupTab) {
        if (s.overlay == Overlay.Styles) controller.setOverlay(null)
        if (s.view != BottomView.MODULES) controller.setView(BottomView.MODULES)
        if (selectedSection(s) != tab) controller.selectGroup(tab)
    }

    /** An on-photo tool is open: the crop frame, a colour picker, or a shape being drawn. */
    fun onPhotoTool(s: DarkroomState, drawing: Boolean): Boolean = s.cropTool != null || s.picker != null || drawing

    fun chrome(s: DarkroomState, drawing: Boolean, drag: SliderDrag?): L1Chrome = L1Chrome(
        topBar = drag == null,
        rows = !onPhotoTool(s, drawing),
        fading = drag != null,
        pill = drag?.pill,
    )

    /** The series pill: "n / N". */
    fun seriesPill(s: DarkroomState): String = if (s.series.items.isEmpty()) "" else "${s.index + 1} / ${s.series.items.size}"
}

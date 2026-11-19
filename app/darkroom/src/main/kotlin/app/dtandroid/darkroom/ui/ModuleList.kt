// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.GroupTab
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Overlay
import app.dtandroid.darkroom.key
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.schema.Control
import app.dtandroid.schema.ParamRuleInputs
import app.dtandroid.schema.Rules
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/** Group tabs: active (power icon), quick, then the configured groups. */
@Composable
fun GroupTabs(controller: DarkroomController, s: DarkroomState) {
    val tabs = listOf<GroupTab>(GroupTab.Active, GroupTab.Quick) + controller.groups.groups.map { GroupTab.Configured(it.id) }
    DtTabRow(tabs, s.group, { controller.selectGroup(it) }, DtTheme.sizes.groupTabs) { tab, sel ->
        val col = if (sel) DtTheme.colors.textBright else DtTheme.colors.textDim
        when (tab) {
            GroupTab.Active -> DtIconImage(DtIcon.Power, "active modules", col, DtTheme.sizes.icon)
            GroupTab.Quick -> DtText("quick", DtTheme.type.label, col)
            is GroupTab.Configured -> DtText(controller.groups.groups.first { it.id == tab.id }.label, DtTheme.type.label, col)
        }
    }
}

/** The scrolling module list of the current group, or the quick panel. */
@Composable
fun ModuleList(controller: DarkroomController, s: DarkroomState, live: Map<Pair<InstanceKey, Int>, Double>, tablet: Boolean, modifier: Modifier = Modifier) {
    val mods = controller.visibleModules(s)
    val listState = rememberLazyListState()
    // bring a module that was just expanded to the top of the list
    LaunchedEffect(s.expanded, s.group) {
        val i = mods.indexOfFirst { it.key == s.expanded }
        if (i >= 0) listState.animateScrollToItem(i + 1)
    }
    LazyColumn(modifier.fillMaxSize().background(DtTheme.colors.bg), state = listState) {
        item { Spacer(Modifier.height(DtTheme.space.xxs)) }
        if (s.group == GroupTab.Quick) {
            item { QuickPanel(controller, s, live) }
        } else {
            if (mods.isEmpty()) item {
                DtText(
                    if (s.group == GroupTab.Active) "No module is active at this point in the history." else "No module in this group.",
                    DtTheme.type.label, DtTheme.colors.textMuted,
                    Modifier.padding(horizontal = DtTheme.space.xxl, vertical = DtTheme.space.xxxl), maxLines = 3,
                )
            }
            items(mods, key = { "${it.op}/${it.priority}" }) { m ->
                ModuleBlock(controller, s, live, m, tablet)
            }
        }
        item { Spacer(Modifier.height(DtTheme.space.xxl)) }
    }
}

@Composable
private fun ModuleBlock(controller: DarkroomController, s: DarkroomState, live: Map<Pair<InstanceKey, Int>, Double>, m: ModuleInstance, tablet: Boolean) {
    val schema = controller.schema.modules[m.op] ?: return
    val open = s.expanded == m.key
    Column(Modifier.fillMaxWidth().padding(bottom = DtTheme.space.xxs)) {
        ModuleHeader(controller, s, m, open, showIcons = tablet || open)
        val menu = s.overlay
        if (tablet && menu is Overlay.Instances && menu.key == m.key) InstancesMenuInline(controller, s, m)
        if (open) ModulePanel(PanelContext(controller, s, live, schema, m, s.readOnly))
    }
}

/** Header bar: power icon (only where `can_switch`), name, instance label, and the three icon buttons. */
@Composable
fun ModuleHeader(controller: DarkroomController, s: DarkroomState, m: ModuleInstance, open: Boolean, showIcons: Boolean) {
    val c = DtTheme.colors
    val full = fullName(m)
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.moduleHeader).background(c.header),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.canSwitch) {
            DtIconButton(
                DtIcon.Power, (if (m.enabled) "Turn off " else "Turn on ") + full,
                if (m.enabled) c.textBright else c.textDisabled,
                enabled = !s.readOnly,
                keepTint = true,
                onClick = { controller.launch { setEnabled(m.key, !m.enabled) } },
                modifier = Modifier.semantics { stateDescription = if (m.enabled) "on" else "off" },
            )
        } else {
            Spacer(Modifier.width(DtTheme.space.panel))
        }
        Row(
            Modifier.weight(1f).height(DtTheme.sizes.moduleHeader)
                .clickable(role = Role.Button) { controller.toggleExpanded(m.key) }
                .semantics { contentDescription = full; stateDescription = if (open) "expanded" else "collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DtText(m.name, DtTheme.type.body, if (m.enabled) c.textStrong else c.textDim, Modifier.weight(1f, fill = false))
            if (m.instanceName.isNotEmpty()) {
                DtText(m.instanceName, DtTheme.type.body, c.textDim, Modifier.padding(start = DtTheme.space.s))
            }
        }
        if (showIcons) {
            DtIconButton(DtIcon.Instances, "Instances of $full", c.text, enabled = !s.readOnly, onClick = {
                controller.setOverlay(if (s.overlay == Overlay.Instances(m.key)) null else Overlay.Instances(m.key))
            })
            DtIconButton(DtIcon.Reset, "Reset $full", c.text, enabled = !s.readOnly, onClick = { controller.launch { reset(m.key) } })
            DtIconButton(DtIcon.Presets, "Presets for $full", c.text, onClick = { controller.setOverlay(Overlay.Presets(m.key)) })
        }
    }
}

fun fullName(m: ModuleInstance) = if (m.instanceName.isEmpty()) m.name else "${m.name} ${m.instanceName}"

/** What the instances menu offers for a module (ui-spec.md 2), shared by the sheet and the inline menu. */
data class MenuItem(val label: String, val enabled: Boolean, val run: () -> Unit)

fun instanceMenu(controller: DarkroomController, s: DarkroomState, m: ModuleInstance): List<MenuItem> {
    val all = s.modules.filter { !it.hidden }
    val idx = all.indexOfFirst { it.key == m.key }
    val count = s.modules.count { it.op == m.op }
    val ro = s.readOnly
    return listOf(
        MenuItem("new instance", !ro && !m.oneInstance) { controller.launch { instanceNew(m.key) } },
        MenuItem("duplicate instance", !ro && !m.oneInstance) { controller.launch { instanceDuplicate(m.key) } },
        // up in the list is later in the pipe
        MenuItem("move up", !ro && idx in 0 until all.lastIndex) { controller.launch { instanceMove(m.key, up = true) } },
        MenuItem("move down", !ro && idx > 0) { controller.launch { instanceMove(m.key, up = false) } },
        MenuItem("delete", !ro && count > 1) { controller.launch { instanceDelete(m.key) } },
        MenuItem("rename", !ro) { controller.setOverlay(Overlay.Rename(m.key)) },
    )
}

/** Tablet: the instances menu opens inline under the header. */
@Composable
private fun InstancesMenuInline(controller: DarkroomController, s: DarkroomState, m: ModuleInstance) {
    val c = DtTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .padding(start = DtTheme.space.menuIndent, end = DtTheme.space.m, bottom = DtTheme.space.s, top = DtTheme.space.xxs)
            .background(c.bg).border(DtTheme.sizes.border, c.border),
    ) {
        for (it in instanceMenu(controller, s, m)) {
            Box(
                Modifier.fillMaxWidth().height(DtTheme.sizes.menuItem).clickable(enabled = it.enabled, role = Role.Button, onClick = it.run)
                    .padding(horizontal = DtTheme.space.xl),
                contentAlignment = Alignment.CenterStart,
            ) { DtText(it.label, DtTheme.type.body, if (it.enabled) c.textStrong else c.textDisabled) }
        }
    }
}

/** Selected single controls of several modules, labelled "module · control". */
@Composable
private fun QuickPanel(controller: DarkroomController, s: DarkroomState, live: Map<Pair<InstanceKey, Int>, Double>) {
    val sp = DtTheme.space
    Column(Modifier.fillMaxWidth().background(DtTheme.colors.panel).padding(start = sp.panel, end = sp.panel, top = sp.s, bottom = sp.m)) {
        for (q in controller.groups.quick) {
            val key = InstanceKey(q.op, 0)
            val m = s.modules.firstOrNull { it.key == key } ?: continue
            val schema = controller.schema.modules[q.op] ?: continue
            val c = schema.control(q.controlId) as? Control.Slider ?: continue
            val inputs = ParamRuleInputs(schema, m.params)
            val enabled = !s.readOnly && Rules.isSensitive(schema, c, inputs) && c.isEditable
            val value = controller.sliderValue(s, key, c, live) ?: c.default
            SliderControl(
                label = q.label, slider = c, value = value, enabled = enabled,
                onDrag = { v -> controller.drag(key, c.id, c.binding, v, c.integer) },
                onDragEnd = { controller.endDrag(key, c.id, c.binding, c.integer) },
                onReset = { controller.launch { setSlider(key, c.binding, c.default, c.integer) } },
                onExact = { controller.setOverlay(Overlay.NumberEntry(key, c.id)) },
            )
        }
    }
}

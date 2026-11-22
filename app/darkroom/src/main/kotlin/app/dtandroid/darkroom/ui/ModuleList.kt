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
import app.dtandroid.schema.layout.GroupIcons
import app.dtandroid.schema.layout.QuickAccess
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import app.dtandroid.schema.ParamRuleInputs
import app.dtandroid.schema.Rules
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/**
 * darktable's module groups bar for the current layout: active modules (power icon), the
 * quick access panel when the layout shows it, then the layout's groups with their icons and
 * names; at the end the layouts menu, where desktop has its presets button.
 */
@Composable
fun GroupTabs(controller: DarkroomController, s: DarkroomState) {
    val tabs = buildList<GroupTab> {
        add(GroupTab.Active)
        if (s.layout.quickAccessShown) add(GroupTab.Quick)
        s.layout.groups.indices.forEach { add(GroupTab.Group(it)) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DtTabRow(tabs, s.group, { controller.selectGroup(it) }, DtTheme.sizes.groupTabs, Modifier.weight(1f)) { tab, sel ->
            val col = if (sel && s.search.isEmpty() && s.forceShow == null) DtTheme.colors.textBright else DtTheme.colors.textDim
            when (tab) {
                GroupTab.Active -> TabFace(DtIcon.Power, "active", "active modules", col)
                GroupTab.Quick -> TabFace(DtIcon.QuickAccess, "quick", "quick access panel", col)
                is GroupTab.Group -> s.layout.groups.getOrNull(tab.index)?.let { g ->
                    TabFace(groupIcon(g.icon), g.name, "group ${g.name}", col)
                }
                GroupTab.AllModules -> Unit
            }
        }
        DtIconButton(DtIcon.Presets, "module layouts: ${s.layoutName}", DtTheme.colors.text, onClick = {
            controller.setOverlay(if (s.overlay == Overlay.Layouts) null else Overlay.Layouts)
        })
    }
    if (s.layout.showSearch) SearchLine(controller, s)
}

/** The icon of a group's icon name; names darktable does not know draw as "basic", as on desktop. */
fun groupIcon(name: String): DtIcon =
    DtIcon.byKey("group" + GroupIcons.drawn(name).replaceFirstChar { it.uppercase() }) ?: DtIcon.GroupBasic

@Composable
private fun TabFace(icon: DtIcon, name: String, description: String, col: androidx.compose.ui.graphics.Color) {
    Column(Modifier.semantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        DtIconImage(icon, null, col, DtTheme.sizes.icon)
        DtText(name, DtTheme.type.tiny, col, Modifier.padding(horizontal = DtTheme.space.xxs))
    }
}

/** "search modules by name or tag": shown when the layout has "show search line" on. */
@Composable
private fun SearchLine(controller: DarkroomController, s: DarkroomState) {
    val c = DtTheme.colors
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.touch).background(c.bg).padding(start = DtTheme.space.panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DtIconImage(DtIcon.Search, null, c.textMuted, DtTheme.sizes.icon)
        Box(Modifier.weight(1f).padding(horizontal = DtTheme.space.m), contentAlignment = Alignment.CenterStart) {
            if (s.search.isEmpty()) DtText("search modules by name or tag", DtTheme.type.label, c.textMuted)
            BasicTextField(
                value = s.search,
                onValueChange = { controller.setSearch(it) },
                singleLine = true,
                textStyle = DtTheme.type.label.copy(color = c.textBright),
                cursorBrush = SolidColor(c.textBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "search modules" },
            )
        }
        if (s.search.isNotEmpty()) DtIconButton(DtIcon.Close, "clear text", c.text, onClick = { controller.setSearch("") })
    }
    Divider()
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
        if (s.group == GroupTab.Quick && s.search.isEmpty() && s.forceShow == null) {
            item { QuickPanel(controller, s, live) }
        } else {
            if (mods.isEmpty()) item {
                DtText(
                    when {
                        s.search.isNotEmpty() -> "No module matches \"${s.search}\"."
                        s.group == GroupTab.Active -> "No module is active at this point in the history."
                        s.group == GroupTab.AllModules -> "No module in any group of this layout."
                        else -> "No module in this group."
                    },
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
    Column(Modifier.fillMaxWidth().padding(bottom = DtTheme.sizes.moduleGap)) {
        // brief PUI2a: the modern skin has no gap but a hairline between modules (tokens)
        if (DtTheme.sizes.moduleSeparator > DtTheme.space.none) Box(Modifier.fillMaxWidth().height(DtTheme.sizes.moduleSeparator).background(DtTheme.colors.border))
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
        Modifier.fillMaxWidth().height(DtTheme.sizes.moduleHeader).background(if (open) c.raised else c.header),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.canSwitch) {
            DtIconButton(
                DtIcon.Power, (if (m.enabled) "Turn off " else "Turn on ") + full,
                if (m.enabled) c.accentPower else c.textDisabled,
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
            DtText(m.name, DtTheme.type.moduleName, if (m.enabled) c.textStrong else c.textDim, Modifier.weight(1f, fill = false))
            if (m.instanceName.isNotEmpty()) {
                DtText(m.instanceName, DtTheme.type.moduleName, c.textDim, Modifier.padding(start = DtTheme.space.s))
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

/**
 * The quick access panel (darktable manual, "quick access panel"): per module a header with
 * its on-off button when the layout lists it, the module name, reset, presets and "go to the
 * full module", then the module's listed widgets. A module with several instances has its
 * widgets disabled, as on desktop. Entries the app cannot use are shown greyed with their name.
 */
@Composable
private fun QuickPanel(controller: DarkroomController, s: DarkroomState, live: Map<Pair<InstanceKey, Int>, Double>) {
    val sp = DtTheme.space
    val blocks = controller.quickPanel(s)
    Column(Modifier.fillMaxWidth()) {
        if (blocks.isEmpty()) DtText(
            "The quick access panel of this layout is empty. Add widgets in \"manage module layouts\" (the menu at the end of the tabs).",
            DtTheme.type.label, DtTheme.colors.textMuted, Modifier.padding(horizontal = sp.xxl, vertical = sp.xxxl), maxLines = 4,
        )
        for (b in blocks) {
            QuickHeader(controller, s, b)
            Column(Modifier.fillMaxWidth().background(DtTheme.colors.panel).padding(start = sp.panel, end = sp.panel, top = sp.xs, bottom = sp.s)) {
                if (b.severalInstances) DtText(
                    "this quick access widget is disabled as there are multiple instances of this module present. Please use the full module to access this widget...",
                    DtTheme.type.tiny, DtTheme.colors.textMuted, Modifier.padding(vertical = sp.xs), maxLines = 3,
                )
                val m = b.instance
                for (e in b.entries) {
                    val ms = e.module
                    val c = e.control
                    if (m == null || ms == null || c == null || e.kind != QuickAccess.Kind.CONTROL) {
                        UnavailableControl(e.label, e.reason)
                        continue
                    }
                    if (!controller.quickVisible(m, e)) continue
                    SingleControl(c, PanelContext(controller, s, live, ms, m, s.readOnly), e.label, disabled = b.severalInstances)
                }
            }
        }
    }
}

@Composable
private fun QuickHeader(controller: DarkroomController, s: DarkroomState, b: DarkroomController.QuickBlock) {
    val c = DtTheme.colors
    val m = b.instance
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.moduleHeader).background(c.header).padding(top = DtTheme.space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (b.onOff != null && m != null && m.canSwitch) {
            DtIconButton(
                DtIcon.Power, (if (m.enabled) "Turn off " else "Turn on ") + b.moduleName,
                if (m.enabled) c.accentPower else c.textDisabled,
                enabled = !s.readOnly && !b.severalInstances, keepTint = true,
                onClick = { controller.launch { setEnabled(m.key, !m.enabled) } },
                modifier = Modifier.semantics { stateDescription = if (m.enabled) "on" else "off" },
            )
        } else {
            Spacer(Modifier.width(DtTheme.space.panel))
        }
        DtText(b.moduleName, DtTheme.type.moduleName, if (m?.enabled == true) c.textStrong else c.textDim, Modifier.weight(1f))
        if (m != null) {
            DtIconButton(DtIcon.Reset, "Reset ${b.moduleName}", c.text, enabled = !s.readOnly && !b.severalInstances,
                onClick = { controller.launch { reset(m.key) } })
            DtIconButton(DtIcon.Presets, "Presets for ${b.moduleName}", c.text, onClick = { controller.setOverlay(Overlay.Presets(m.key)) })
            DtIconButton(DtIcon.Link, "go to the full version of the ${b.moduleName} module", c.text, onClick = { controller.goToModule(m.key) })
        }
    }
}

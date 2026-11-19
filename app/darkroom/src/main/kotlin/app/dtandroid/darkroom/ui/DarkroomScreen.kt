// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.dtandroid.darkroom.BottomView
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.HostAction
import app.dtandroid.darkroom.InstanceKey
import app.dtandroid.darkroom.Overlay
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.icons.DtIconImage
import app.dtandroid.ui.theme.DtTheme

/** Which layout a window gets: decided by its size, not by the kind of device (ui-spec.md 1). */
enum class DarkroomLayout { PHONE, TABLET }

/**
 * The darkroom. Phone portrait and tablet landscape layouts share all logic
 * ([DarkroomController]); the tablet layout is used from the window size in
 * the tokens `size.tabletMinWidth` x `size.tabletMinHeight` upwards.
 */
@Composable
fun DarkroomScreen(
    controller: DarkroomController,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    layout: DarkroomLayout? = null,
    /** import and export of module layouts; null where there is no file picker (tests) */
    layoutFiles: LayoutFiles? = null,
    /** the app's photo menu ("more" in the top bar); empty = no menu (interface preview, tests) */
    actions: List<HostAction> = emptyList(),
) {
    val s by controller.state.collectAsState()
    val live by controller.liveValues.collectAsState()
    if (s.layoutEditor) {
        LayoutEditorScreen(controller, layoutFiles, modifier)
        return
    }
    BoxWithConstraints(modifier.fillMaxSize().background(DtTheme.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val tablet = when (layout) {
            null -> maxWidth >= DtTheme.sizes.tabletMinWidth && maxHeight >= DtTheme.sizes.tabletMinHeight
            else -> layout == DarkroomLayout.TABLET
        }
        if (tablet) TabletLayout(controller, s, live, onBack, onDone, actions.isNotEmpty()) else PhoneLayout(controller, s, live, onBack, onDone, actions.isNotEmpty())
        Overlays(controller, s, tablet, actions)
    }
}

// ----------------------------------------------------------------------- phone

@Composable
private fun PhoneLayout(controller: DarkroomController, s: DarkroomState, live: Map<Pair<InstanceKey, Int>, Double>, onBack: () -> Unit, onDone: () -> Unit, menu: Boolean) {
    Column(Modifier.fillMaxSize()) {
        PhoneTopBar(controller, s, onBack, onDone, menu)
        PhotoView(controller, s, DtTheme.space.m, twoFingerUndo = true, Modifier.fillMaxWidth().height(DtTheme.sizes.photoPhoneHeight))
        InfoLine(s)
        if (s.filmstrip) Filmstrip(controller, s, DtTheme.sizes.filmstripPhone, DtTheme.sizes.thumbPhoneWidth, DtTheme.sizes.thumbPhoneHeight, centered = false)
        Notices(s)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (s.view) {
                BottomView.MODULES -> Column(Modifier.fillMaxSize()) {
                    GroupTabs(controller, s)
                    ModuleList(controller, s, live, tablet = false, modifier = Modifier.weight(1f))
                }
                BottomView.HISTORY -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HistoryView(controller, s)
                }
            }
        }
        BottomTabs(controller, s)
    }
}

@Composable
private fun PhoneTopBar(controller: DarkroomController, s: DarkroomState, onBack: () -> Unit, onDone: () -> Unit, menu: Boolean) {
    val c = DtTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = DtTheme.space.xs, end = DtTheme.space.l, top = DtTheme.space.s, bottom = DtTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s),
    ) {
        DtIconButton(DtIcon.Back, "Back", c.text, onBack, iconSize = DtTheme.sizes.iconLarge)
        Row(
            Modifier.weight(1f).height(DtTheme.sizes.touch)
                .clickable(role = Role.Button) { controller.toggleFilmstrip() }
                .semantics {
                    contentDescription = (if (s.filmstrip) "Hide" else "Show") + " filmstrip for ${s.series.name}"
                    stateDescription = if (s.filmstrip) "expanded" else "collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(DtTheme.space.xxs)) {
                DtText(s.current?.fileName ?: "", DtTheme.type.title, c.textStrong)
                DtText(seriesLine(s), DtTheme.type.small, c.textMuted)
            }
            DtIconImage(DtIcon.ChevronDown, null, c.textMuted, DtTheme.sizes.icon,
                Modifier.padding(start = DtTheme.space.xs).rotate(if (s.filmstrip) HALF_TURN else NO_TURN))
        }
        BeforeButton(controller, s)
        if (menu) MoreButton(controller)
        DtTextButton("done", onDone)
    }
}

@Composable
private fun MoreButton(controller: DarkroomController) {
    DtTextButton("more", { controller.setOverlay(Overlay.HostMenu) })
}

private const val HALF_TURN = 180f
private const val NO_TURN = 0f

fun seriesLine(s: DarkroomState) =
    if (s.series.items.isEmpty()) "" else "${s.series.name} · ${s.index + 1} of ${s.series.items.size}"

@Composable
private fun BeforeButton(controller: DarkroomController, s: DarkroomState) {
    DtTextButton("before", { controller.launch { setBefore(!s.before) } }, selected = s.before,
        modifier = Modifier.semantics { stateDescription = if (s.before) "on" else "off" })
}

@Composable
private fun BottomTabs(controller: DarkroomController, s: DarkroomState) {
    Column(Modifier.fillMaxWidth()) {
        Divider()
        Row(Modifier.fillMaxWidth().height(DtTheme.sizes.bottomTabs)) {
            for ((v, label) in listOf(BottomView.MODULES to "modules", BottomView.HISTORY to "history · ${s.history.end}")) {
                val sel = s.view == v
                Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { controller.setView(v) }) {
                    Box(Modifier.fillMaxWidth().height(DtTheme.sizes.tabIndicator).background(if (sel) DtTheme.colors.text else DtTheme.colors.bg))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DtText(label, DtTheme.type.label, if (sel) DtTheme.colors.textBright else DtTheme.colors.textDim)
                    }
                }
            }
        }
    }
}

/** The read-only banner and the last engine error. */
@Composable
private fun Notices(s: DarkroomState) {
    val c = DtTheme.colors
    s.readOnlyReason?.let {
        Box(Modifier.fillMaxWidth().background(c.banner).padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m)
            .semantics { contentDescription = "read only" }) {
            DtText(it, DtTheme.type.label, c.textStrong, maxLines = 4)
        }
    }
    s.message?.let {
        Box(Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs)) {
            DtText(it, DtTheme.type.small, c.textMuted, maxLines = 3)
        }
    }
}

// ----------------------------------------------------------------------- tablet

@Composable
private fun TabletLayout(controller: DarkroomController, s: DarkroomState, live: Map<Pair<InstanceKey, Int>, Double>, onBack: () -> Unit, onDone: () -> Unit, menu: Boolean) {
    Column(Modifier.fillMaxSize()) {
        TabletTopBar(controller, s, onBack, onDone, menu)
        Divider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(DtTheme.sizes.tabletLeftPanel).fillMaxHeight().verticalScroll(rememberScrollState())) {
                PanelHeading("history")
                HistoryRows(controller, s)
                HistoryActions(controller, s, withUndo = false)
                PanelHeading("image information")
                ImageInformation(s)
            }
            VerticalDivider(Modifier.fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Notices(s)
                PhotoView(controller, s, DtTheme.space.xxl, twoFingerUndo = false, Modifier.weight(1f).fillMaxWidth())
                if (s.filmstrip) Filmstrip(controller, s, DtTheme.sizes.filmstripTablet, DtTheme.sizes.thumbTabletWidth, DtTheme.sizes.thumbTabletHeight, centered = true)
            }
            VerticalDivider(Modifier.fillMaxHeight())
            Column(Modifier.width(DtTheme.sizes.tabletRightPanel).fillMaxHeight()) {
                GroupTabs(controller, s)
                ModuleList(controller, s, live, tablet = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabletTopBar(controller: DarkroomController, s: DarkroomState, onBack: () -> Unit, onDone: () -> Unit, menu: Boolean) {
    val c = DtTheme.colors
    Row(
        Modifier.fillMaxWidth().height(DtTheme.sizes.tabletTopBar).padding(start = DtTheme.space.s, end = DtTheme.space.panel),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.l),
    ) {
        DtIconButton(DtIcon.Back, "Back", c.text, onBack, iconSize = DtTheme.sizes.iconLarge)
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xl, Alignment.CenterHorizontally),
        ) {
            DtText(s.current?.fileName ?: "", DtTheme.type.title, c.textStrong)
            DtText(s.info?.exif?.let { exifLine(it, withCamera = false) } ?: "", DtTheme.type.label, c.textMuted)
            Row(
                Modifier.height(DtTheme.sizes.touch).clickable(role = Role.Button) { controller.toggleFilmstrip() }
                    .semantics { stateDescription = if (s.filmstrip) "expanded" else "collapsed" }
                    .padding(horizontal = DtTheme.space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DtText(seriesLine(s), DtTheme.type.label, c.textMuted)
                DtIconImage(DtIcon.ChevronDown, null, c.textMuted, DtTheme.sizes.icon,
                    Modifier.padding(start = DtTheme.space.xs).rotate(if (s.filmstrip) HALF_TURN else NO_TURN))
            }
        }
        DtIconButton(DtIcon.Undo, "undo", c.textStrong, enabled = s.undo.canUndo, onClick = { controller.launch { undo() } })
        DtIconButton(DtIcon.Redo, "redo", c.textStrong, enabled = s.undo.canRedo, onClick = { controller.launch { redo() } })
        BeforeButton(controller, s)
        if (menu) MoreButton(controller)
        DtTextButton("done", onDone)
    }
}

@Composable
private fun ImageInformation(s: DarkroomState) {
    val c = DtTheme.colors
    val e = s.info?.exif
    val rows = listOfNotNull(
        "filename" to (s.current?.fileName ?: ""),
        "series" to s.series.name,
        e?.let { "camera" to cameraName(it) },
        e?.let { "lens" to it.lens },
        e?.let { "exposure" to exifLine(it, withCamera = false) },
        s.info?.let { "size" to "${it.width} × ${it.height}" },
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.l),
        verticalArrangement = Arrangement.spacedBy(DtTheme.space.m)) {
        for ((k, v) in rows) Row {
            DtText(k, DtTheme.type.label, c.textMuted, Modifier.width(DtTheme.sizes.tabletInfoLabel))
            DtText(v, DtTheme.type.label, c.text, Modifier.weight(1f))
        }
        Spacer(Modifier.height(DtTheme.space.m))
    }
}

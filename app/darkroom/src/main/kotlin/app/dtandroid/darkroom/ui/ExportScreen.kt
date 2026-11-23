// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.DarkroomState
import app.dtandroid.darkroom.ExportJob
import app.dtandroid.darkroom.ExportScope
import app.dtandroid.darkroom.ExportSettings
import app.dtandroid.darkroom.ExportSize
import app.dtandroid.darkroom.ExportTarget
import app.dtandroid.darkroom.Overlay
import app.dtandroid.engine.api.ExportFormat
import app.dtandroid.engine.api.ExportProfile
import app.dtandroid.ui.icons.DtIcon
import app.dtandroid.ui.theme.DtTheme

/*
 * Brief P3g: the export screen (mockup B3-Export; darktable's export module: storage options, format options,
 * global options) with the progress of the export under way, and the darkroom top bar's small indicator.
 * ui-spec.md section "export".
 */

/** One of the screen's lists, open in a sheet: its title, entries and which is chosen. */
private class ExportList(val title: String, val entries: List<String>, val chosen: Int, val choose: (Int) -> Unit)

@Composable
fun BoxScope.ExportScreen(controller: DarkroomController, s: DarkroomState, tablet: Boolean) {
    val ex = controller.exports
    val set by ex.settings.collectAsState()
    val job by ex.job.collectAsState()
    var list by remember { mutableStateOf<ExportList?>(null) }
    val c = DtTheme.colors
    val running = job?.running == true
    val seriesSize = s.series.items.size
    val close = {
        // an ended job is forgotten with the screen; one under way stays in the top bar
        ex.dismiss()
        controller.setOverlay(null)
    }
    val maxW = if (tablet) DtTheme.sizes.tabletRightPanel + DtTheme.sizes.tabletLeftPanel else null
    Box(
        Modifier.matchParentSize().background(c.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.TopCenter,
    ) {
        var col = Modifier.fillMaxWidth()
        if (maxW != null) col = col.widthIn(max = maxW)
        Column(col) {
            Row(
                Modifier.fillMaxWidth().padding(start = DtTheme.space.xs, end = DtTheme.space.m, top = DtTheme.space.s, bottom = DtTheme.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DtTheme.space.xs),
            ) {
                DtIconButton(DtIcon.Back, "Back", c.text, close, iconSize = DtTheme.sizes.iconLarge)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DtTheme.space.xxs)) {
                    DtText("export", DtTheme.type.title, c.textStrong)
                    DtText(listOf(s.current?.fileName ?: "", seriesLine(s)).filter { it.isNotEmpty() }.joinToString(" · "),
                        DtTheme.type.small, c.textMuted, middle = true)
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                PanelHeading("storage options")
                Column(Modifier.padding(horizontal = DtTheme.space.panel)) {
                    val targets = ExportTarget.entries
                    ListControl("target storage", set.target.label, !running) {
                        list = ExportList("target storage", targets.map { it.label }, targets.indexOf(set.target)) { i -> ex.set { it.copy(target = targets[i]) } }
                    }
                    val scopes = ExportScope.entries
                    val scopeLabel = { sc: ExportScope -> if (sc == ExportScope.SERIES) "${sc.label} ($seriesSize)" else sc.label }
                    ListControl("images", scopeLabel(set.scope), !running && seriesSize > 1) {
                        list = ExportList("images", scopes.map(scopeLabel), scopes.indexOf(set.scope)) { i -> ex.set { it.copy(scope = scopes[i]) } }
                    }
                    ControlNote(set.target.note)
                }
                PanelHeading("format options")
                Column(Modifier.padding(horizontal = DtTheme.space.panel)) {
                    val formats = ExportFormat.entries
                    ListControl("file format", ExportSettings.formatLabel(set.format), !running) {
                        list = ExportList("file format", formats.map { ExportSettings.formatLabel(it) }, formats.indexOf(set.format)) { i -> ex.set { it.withFormat(formats[i]) } }
                    }
                    val depths = ExportSettings.bitDepths(set.format)
                    if (depths.size > 1) ListControl("bit depth", "${set.bitDepth} bit", !running) {
                        list = ExportList("bit depth", depths.map { "$it bit" }, depths.indexOf(set.bitDepth)) { i -> ex.set { it.copy(bitDepth = depths[i]) } }
                    }
                    val qs = ExportSettings.QUALITIES
                    if (set.lossy) ListControl("quality", "${set.quality} %", !running) {
                        list = ExportList("quality", qs.map { "$it %" }, qs.indexOf(set.quality)) { i -> ex.set { it.copy(quality = qs[i]) } }
                    }
                }
                PanelHeading("global options")
                Column(Modifier.padding(horizontal = DtTheme.space.panel)) {
                    val sizes = ExportSize.entries
                    ListControl("set size", set.size.label, !running) {
                        list = ExportList("set size", sizes.map { it.label }, sizes.indexOf(set.size)) { i -> ex.set { it.copy(size = sizes[i]) } }
                    }
                    ToggleControl("high quality resampling", set.highQuality, !running) { ex.set { it.copy(highQuality = !it.highQuality) } }
                    val profiles = listOf(ExportProfile.IMAGE, ExportProfile.SRGB, ExportProfile.ADOBERGB, ExportProfile.DISPLAYP3)
                    ListControl("profile", ExportSettings.profileLabel(set.profile), !running) {
                        list = ExportList("profile", profiles.map { ExportSettings.profileLabel(it) }, profiles.indexOf(set.profile)) { i -> ex.set { it.copy(profile = profiles[i]) } }
                    }
                }
            }
            job?.let { j -> ExportProgressLines(j, onCancel = { ex.cancel() }) }
            val count = if (set.scope == ExportScope.SERIES && seriesSize > 1) seriesSize else 1
            val ended = job?.let { !it.running } == true
            val label = when {
                running -> "exporting…"
                ended -> "export again"
                count > 1 -> "export $count images"
                else -> "export"
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m)) {
                DtTextButton(label, { ex.dismiss(); ex.start() }, Modifier.weight(1f), selected = !running, enabled = !running && s.info != null)
            }
        }
    }
    list?.let { l ->
        Sheet(l.title, { list = null }, maxW) {
            for ((i, e) in l.entries.withIndex()) SheetItem(e, enabled = true, selected = i == l.chosen) { l.choose(i); list = null }
        }
    }
}

/** The bar with the percent and the step; for a series the overall bar ("photo n of N") and the photo's own. */
@Composable
fun ExportProgressLines(j: ExportJob, onCancel: () -> Unit) {
    val cancel = if (j.running) onCancel else null
    BatchProgress(j.label, j.overall, step = j.stepLine, onCancel = cancel, cancelLabel = "cancel")
    if (j.series && j.running) BatchProgress(j.fileName, j.photoFraction.toFloat(), step = j.photoLine)
}

/** Brief P3g: the darkroom top bar's small indicator of an export under way or just ended; opens the export screen. */
@Composable
fun ExportIndicator(controller: DarkroomController, s: DarkroomState) {
    val job by controller.exports.job.collectAsState()
    val j = job
    if (j != null && s.overlay != Overlay.Export) {
        val c = DtTheme.colors
        Column(
            Modifier.width(IntrinsicSize.Max).height(DtTheme.sizes.touch)
                .clickable(role = Role.Button) { controller.exports.open() }
                .semantics { contentDescription = j.short }
                .padding(horizontal = DtTheme.space.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            DtText(j.short, DtTheme.type.small, if (j.running) c.textStrong else c.textMuted)
            Box(Modifier.fillMaxWidth().padding(top = DtTheme.space.xxs).height(DtTheme.sizes.progressLine).background(c.sliderTrack)) {
                Box(Modifier.fillMaxWidth(j.overall).height(DtTheme.sizes.progressLine).background(c.accent))
            }
        }
    }
}

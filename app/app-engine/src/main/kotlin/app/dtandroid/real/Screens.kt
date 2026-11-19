// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dtandroid.darkroom.ui.Divider
import app.dtandroid.darkroom.ui.DtText
import app.dtandroid.darkroom.ui.DtTextButton
import app.dtandroid.darkroom.ui.PanelHeading
import app.dtandroid.darkroom.ui.toImageBitmap
import app.dtandroid.ui.theme.DtTheme
import java.io.File
import java.util.Locale

/** A plain screen: the theme's background, inside the safe area. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(DtTheme.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
}

@Composable
private fun TopBar(title: String, buttons: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.l, vertical = DtTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s),
    ) {
        DtText(title, DtTheme.type.title, DtTheme.colors.textStrong, Modifier.weight(1f))
        buttons()
    }
}

@Composable
private fun Paragraph(text: String, muted: Boolean = false) {
    DtText(text, DtTheme.type.label, if (muted) DtTheme.colors.textMuted else DtTheme.colors.text,
        Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs), maxLines = 40)
}

/** First start: unpacking darktable's data (once), then starting the engine. */
@Composable
fun StartScreen(status: AppEngine.Status) = Page {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        when (status) {
            is AppEngine.Status.Unpacking -> {
                Paragraph("preparing darktable's engine (only after installing or updating the app)")
                Paragraph(String.format(Locale.ROOT, "unpacking… %d %%", (status.progress * 100).toInt()), muted = true)
                Box(Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m)
                    .height(DtTheme.sizes.sliderTrack).background(DtTheme.colors.sliderTrack)) {
                    Box(Modifier.fillMaxWidth(status.progress.coerceIn(0f, 1f)).height(DtTheme.sizes.sliderTrack).background(DtTheme.colors.sliderFill))
                }
            }
            AppEngine.Status.Starting -> Paragraph("starting darktable's engine…")
            is AppEngine.Status.Failed -> {
                PanelHeading("the engine did not start")
                Paragraph(status.text)
                Paragraph("please send a screenshot of this page", muted = true)
            }
            is AppEngine.Status.Ready -> Paragraph("ready")
        }
    }
}

/** The working folder: the photos copied in through the file picker. */
@Composable
fun LibraryScreen(vm: AppViewModel, onOpen: () -> Unit) = Page {
    val lib by vm.library.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("dt-android engine") {
            DtTextButton("about", { vm.show(Screen.ABOUT) })
            DtTextButton("open photos", onOpen, enabled = lib.busy == null)
        }
        Divider()
        lib.busy?.let { Paragraph(it, muted = true) }
        for (m in lib.messages) Paragraph(m, muted = true)
        if (lib.items.isEmpty() && lib.busy == null) {
            Paragraph("No photos yet. Tap \"open photos\" and pick one or several RAW files, optionally with their .xmp files. " +
                "The app works on copies in its own folder; your originals are never changed.")
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(lib.items) { i, item ->
                val thumb = lib.thumbnails[item.fileName]
                val bmp = remember(thumb) { thumb?.toImageBitmap() }
                Row(
                    Modifier.fillMaxWidth().height(DtTheme.sizes.filmstripPhone)
                        .clickable(role = Role.Button) { vm.openDarkroom(i) }
                        .semantics { contentDescription = "open ${item.fileName}" }
                        .padding(horizontal = DtTheme.space.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DtTheme.space.l),
                ) {
                    Box(Modifier.size(DtTheme.sizes.thumbPhoneWidth, DtTheme.sizes.thumbPhoneHeight).background(DtTheme.colors.photoBackground)) {
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f)) {
                        DtText(item.fileName, DtTheme.type.body, DtTheme.colors.textStrong)
                        DtText(if (File(item.xmpPath).isFile) "with edit" else "not edited yet", DtTheme.type.small, DtTheme.colors.textMuted)
                    }
                }
                Divider()
            }
        }
    }
}

/** About: versions, how the engine started, and the timings page. */
@Composable
fun AboutScreen(vm: AppViewModel, ready: AppEngine.Status.Ready, appVersion: String) = Page {
    Column(Modifier.fillMaxSize()) {
        TopBar("about") {
            DtTextButton("timings", { vm.openTimings() })
            DtTextButton("back", { vm.show(Screen.LIBRARY) })
        }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Paragraph("dt-android engine $appVersion: an unofficial Android port of darktable, not a darktable release. " +
                "Licence GPL-3.0-or-later; source: the darktable-android repository at the commit in the version.")
            Paragraph("engine: darktable ${ready.info.darktableVersion}, engine API ${ready.info.apiVersion}, package ${AppEngine.engineId}")
            Paragraph("plugins: ${ready.info.pluginsLoaded} of ${ready.info.pluginFiles} loaded")
            Paragraph("threads: ${ready.info.threads}; ${ready.cores.describe()}")
            Paragraph("start: " + (if (ready.freshUnpack) "unpacked in ${ready.unpackMs} ms, " else "already unpacked, ") + "engine started in ${ready.initMs} ms")
            Paragraph("working folder: ${vm.work.dir.path}", muted = true)
        }
    }
}

/** Timings the owner can send back (brief P2b, deliverable 6). */
@Composable
fun TimingsScreen(vm: AppViewModel, appVersion: String, onShare: (String) -> Unit) = Page {
    val t by vm.timings.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val wPx = with(d) { maxWidth.roundToPx() }
        val hPx = with(d) { DtTheme.sizes.photoPhoneHeight.roundToPx() }
        Column(Modifier.fillMaxSize()) {
            TopBar("timings") {
                DtTextButton("run", { vm.runTimings(wPx, hPx, appVersion) }, enabled = t.running == null)
                DtTextButton("share", { t.text?.let(onShare) }, enabled = t.text != null && t.running == null)
            }
            Divider()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Paragraph("Runs a fixed sequence on the open photo (or the last opened one): open, previews, module changes, " +
                    "the drag picture, the sharp region, modules(), an export. It takes a minute or two. Your edit is kept; " +
                    "its undo steps start afresh afterwards. \"share\" sends the text (without the phone's model).", muted = true)
                // shown here only, never in the shared text (brief P2b)
                Paragraph("this device: ${Build.MANUFACTURER} ${Build.MODEL}", muted = true)
                t.photo?.let { Paragraph("photo: $it") }
                t.running?.let { Paragraph("running: $it…") }
                t.text?.let { text -> for (line in text.lines().filter { it.isNotBlank() }) Paragraph(line) }
            }
        }
    }
}

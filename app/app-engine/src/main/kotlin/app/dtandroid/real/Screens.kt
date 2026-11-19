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
import androidx.compose.foundation.lazy.items
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
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.lpl.PhotoRecord
import app.dtandroid.lpl.PhotoStatus
import app.dtandroid.lpl.Progress
import app.dtandroid.lpl.ServerOrigin
import app.dtandroid.lpl.UserText
import app.dtandroid.ui.theme.DtTheme
import java.io.File
import java.util.Locale

/** A plain screen: the theme's background, inside the safe area. */
@Composable
internal fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(DtTheme.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
}

@Composable
internal fun TopBar(title: String, buttons: @Composable () -> Unit) {
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
internal fun Paragraph(text: String, muted: Boolean = false) {
    DtText(text, DtTheme.type.label, if (muted) DtTheme.colors.textMuted else DtTheme.colors.text,
        Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs), maxLines = 40)
}

/** First start: unpacking darktable's data (once), then starting the engine. */
@Composable
fun StartScreen(status: AppEngine.Status, onRetry: () -> Unit = {}) = Page {
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
                // brief P2g: a failed start does not need the app to be killed
                Row(Modifier.padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.s)) {
                    DtTextButton("try again", onRetry)
                }
                Paragraph("if it fails again, please send a screenshot of this page", muted = true)
            }
            is AppEngine.Status.Ready -> Paragraph("ready")
        }
    }
}

/**
 * The list: the photos from LightPhotoLibrary (brief P2e item 3) with their status in the contract's
 * words and what the user can do with each, then the photos copied in through the file picker (P2b).
 */
@Composable
fun LibraryScreen(vm: AppViewModel, onOpen: () -> Unit, onOpenInLpl: (ServerOrigin, Long) -> Unit) = Page {
    val lib by vm.library.collectAsState()
    val photos by vm.agent.photos.collectAsState()
    val progress by vm.agent.progress.collectAsState()
    val errors by vm.agent.lastError.collectAsState()
    val lplThumbs by vm.lplThumbs.collectAsState()
    val servers = remember(photos, lib) { vm.servers.list() }
    Column(Modifier.fillMaxSize()) {
        TopBar("dt-android") {
            DtTextButton("server", { vm.showServerForm() })
            DtTextButton("about", { vm.show(Screen.ABOUT) })
            DtTextButton("open photos", onOpen, enabled = lib.busy == null)
        }
        Divider()
        lib.busy?.let { Paragraph(it, muted = true) }
        for (m in lib.messages) Paragraph(m, muted = true)
        lib.confirmRemove?.let { r ->
            Paragraph("${r.filename} has an edit that LightPhotoLibrary does not have yet. Remove it from this phone anyway? The edit is lost.")
            Row(Modifier.padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                DtTextButton("keep it", { vm.keep() })
                DtTextButton("remove anyway", { vm.remove(r, evenUnsynced = true) })
            }
            Divider()
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            for (srv in servers) {
                val mine = photos.filter { it.server == srv.origin }
                item(key = "server-${srv.origin.url}") {
                    PanelHeading("LightPhotoLibrary · this phone is \"${srv.deviceName}\"")
                    val err = errors[srv.origin]
                    Row(Modifier.fillMaxWidth().padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.xs),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                        DtText(if (err != null) err.userText else if (srv.origin in errors) "connected" else srv.origin.url,
                            DtTheme.type.small, DtTheme.colors.textMuted, Modifier.weight(1f), maxLines = 2)
                        DtTextButton("sync now", { vm.syncAll() }, enabled = mine.isNotEmpty())
                    }
                    if (mine.isEmpty()) Paragraph("No photos from LightPhotoLibrary yet. In LightPhotoLibrary, open a photo and tap \"Edit on this phone\".", muted = true)
                    Divider()
                }
                items(mine, key = { "lpl-${it.server.url}-${it.id}" }) { r ->
                    LplRow(vm, r, progress[r.key], lplThumbs[r.key], onOpenInLpl)
                    Divider()
                }
            }
            if (servers.isEmpty()) item(key = "no-server") {
                PanelHeading("LightPhotoLibrary")
                Paragraph("To edit photos from your LightPhotoLibrary, add its server: tap \"server\".", muted = true)
                Divider()
            }
            item(key = "local") {
                PanelHeading("photos from this phone's files")
                if (lib.items.isEmpty() && lib.busy == null) {
                    Paragraph("Tap \"open photos\" and pick one or several RAW files, optionally with their .xmp files. " +
                        "The app works on copies in its own folder; your originals are never changed.", muted = true)
                }
            }
            itemsIndexed(lib.items, key = { _, it -> "local-${it.fileName}" }) { i, item ->
                val thumb = lib.thumbnails[item.fileName]
                PhotoRow(thumb, item.fileName, if (File(item.xmpPath).isFile) "with edit" else "not edited yet", Modifier
                    .clickable(role = Role.Button) { vm.openDarkroom(i) }
                    .semantics { contentDescription = "open ${item.fileName}" })
                Divider()
            }
        }
    }
}

/** A photo's row: thumbnail, name and a line under it. */
@Composable
private fun PhotoRow(thumb: RenderedImage?, name: String, line: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val bmp = remember(thumb) { thumb?.toImageBitmap() }
    Column(modifier.fillMaxWidth().padding(horizontal = DtTheme.space.l, vertical = DtTheme.space.xs)) {
        Row(
            Modifier.fillMaxWidth().height(DtTheme.sizes.thumbPhoneHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DtTheme.space.l),
        ) {
            Box(Modifier.size(DtTheme.sizes.thumbPhoneWidth, DtTheme.sizes.thumbPhoneHeight).background(DtTheme.colors.photoBackground)) {
                if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f)) {
                DtText(name, DtTheme.type.body, DtTheme.colors.textStrong)
                DtText(line, DtTheme.type.small, DtTheme.colors.textMuted, maxLines = 2)
            }
        }
        actions()
    }
}

/** An LPL photo: status in the contract's word with plain words, and open, sync now, open in LPL, remove. */
@Composable
private fun LplRow(vm: AppViewModel, r: PhotoRecord, p: Progress?, thumb: RenderedImage?, onOpenInLpl: (ServerOrigin, Long) -> Unit) {
    val line = statusLine(vm, r, p)
    PhotoRow(thumb, r.filename, line, Modifier.semantics { contentDescription = "${r.filename}: $line" }) {
        Row(Modifier.fillMaxWidth().padding(top = DtTheme.space.xs), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
            DtTextButton("open", { vm.openLplPhoto(r) })
            DtTextButton("sync now", { vm.syncNow(r) }, enabled = !r.readOnly && r.status != PhotoStatus.DOWNLOADING)
            DtTextButton("in LightPhotoLibrary", { onOpenInLpl(r.server, r.id) })
            DtTextButton("remove", { vm.remove(r) })
        }
    }
}

/** The status as LPL names it, and what it means, in a few words (brief P2e item 3). */
private fun statusLine(vm: AppViewModel, r: PhotoRecord, p: Progress?): String {
    val dirty = vm.agent.isDirty(r)
    val words = when (r.status) {
        PhotoStatus.DOWNLOADING -> if (p != null) AppHost.progressText("", p.done, p.total).replace("downloading … ", "downloading ")
                                   else "downloading · not complete, continues when you open it"
        PhotoStatus.OPENING -> "opening · ready to edit"
        PhotoStatus.EDITING -> "editing · open in the darkroom"
        PhotoStatus.DIRTY -> "dirty · ${UserText.WAITING}"
        PhotoStatus.SYNCING -> "syncing"
        PhotoStatus.SYNCED -> if (dirty) "dirty · ${UserText.WAITING}" else "synced" + if (r.previewPending) " · preview still to send" else ""
        PhotoStatus.ERROR -> "error"
    }
    val extra = when {
        r.readOnly -> UserText.NEWER_DARKTABLE
        r.error.isNotEmpty() && r.status != PhotoStatus.SYNCED -> r.error
        else -> null
    }
    return if (extra != null) "$words · $extra" else words
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
            Paragraph("dt-android $appVersion: an unofficial Android port of darktable, not a darktable release. " +
                "Licence GPL-3.0-or-later; source: the darktable-android repository at the commit in the version.")
            Paragraph("engine: darktable ${ready.info.darktableVersion}, engine API ${ready.info.apiVersion}, package ${AppEngine.engineId}")
            Paragraph("plugins: ${ready.info.pluginsLoaded} of ${ready.info.pluginFiles} loaded")
            val pin by AppEngine.pinning.collectAsState()
            Paragraph("threads: ${ready.info.threads}; ${ready.cores.describe()}")
            // brief P2g: what the engine runs on, in plain words
            Paragraph("engine ${pin.describe()}")
            if (ready.pinningAtStart != pin) Paragraph("at start: ${ready.pinningAtStart.describe()}", muted = true)
            Paragraph("start: " + (if (ready.freshUnpack) "unpacked in ${ready.unpackMs} ms, " else "already unpacked, ") + "engine started in ${ready.initMs} ms")
            Paragraph("working folder: ${vm.work.dir.path}", muted = true)
            Paragraph("this install's id for LightPhotoLibrary: ${vm.servers.installId}", muted = true)
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

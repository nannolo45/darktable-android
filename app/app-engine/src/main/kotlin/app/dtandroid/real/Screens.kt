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
import app.dtandroid.lpl.Step
import app.dtandroid.darkroom.HostAction
import app.dtandroid.darkroom.ui.PhotoList
import app.dtandroid.darkroom.ui.PhotoListScreen
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
 * The list (brief P2f deliverable 5, replacing P2e's rows of four buttons): one row per photo with its
 * thumbnail, name (shortened in the middle), one status line in plain words with the contract's states
 * behind it, the last edit (R15) and a thin bar while it downloads or syncs. Tapping a row opens the
 * photo; "more" holds sync now, open in LightPhotoLibrary and remove from this phone. Per server a header
 * with this device's name, whether it is connected, what waits, and one "sync now".
 */
@Composable
fun LibraryScreen(vm: AppViewModel, onOpen: () -> Unit, onOpenInLpl: (ServerOrigin, Long) -> Unit) = Page {
    val lib by vm.library.collectAsState()
    val photos by vm.agent.photos.collectAsState()
    val progress by vm.agent.progress.collectAsState()
    val steps by vm.agent.steps.collectAsState()
    val errors by vm.agent.lastError.collectAsState()
    val lplThumbs by vm.lplThumbs.collectAsState()
    val notes by vm.agent.notes.collectAsState()
    val serverNotes by vm.agent.serverNotes.collectAsState()
    // brief P2i: the "sync automatically" switch
    val autoSync by vm.agent.settings.autoSync.collectAsState()
    val servers = remember(photos, lib) { vm.servers.list() }
    val sections = ArrayList<PhotoList.Section>()
    for (srv in servers) {
        val mine = photos.filter { it.server == srv.origin }
        val waiting = mine.count { vm.agent.isDirty(it) || it.status == PhotoStatus.DIRTY || it.previewPending }
        val err = errors[srv.origin]
        val conn = when {
            err != null -> err.userText
            srv.origin in errors -> "connected"
            else -> "not contacted yet"
        }
        val line = PhotoList.serverLine(srv.deviceName, conn, waiting, autoSync)
        sections += PhotoList.Section(
            key = srv.origin.url, title = "LightPhotoLibrary", line = line,
            action = HostAction("sync now", enabled = mine.isNotEmpty()) { vm.syncAll() },
            empty = "No photos from LightPhotoLibrary yet. In LightPhotoLibrary, open a photo and tap \"Edit on this phone\".",
            rows = mine.map { r -> lplRow(vm, r, progress[r.key], steps[r.key], lplThumbs[r.key], srv.origin, onOpenInLpl, autoSync).copy(note = notes[r.key]) },
            note = serverNotes[srv.origin],
        )
    }
    if (servers.isEmpty()) sections += PhotoList.Section(
        key = "no-server", title = "LightPhotoLibrary",
        action = HostAction("add server") { vm.showServerForm() },
        empty = "To edit photos from your LightPhotoLibrary, add its server.",
    )
    sections += PhotoList.Section(
        key = "local", title = "photos from this phone's files",
        empty = if (lib.busy == null) "Tap \"open photos\" and pick one or several RAW files, optionally with their .xmp files. " +
            "The app works on copies in its own folder; your originals are never changed." else null,
        rows = lib.items.mapIndexed { i, item ->
            PhotoList.Row(
                key = item.fileName, name = item.fileName,
                status = if (File(item.xmpPath).isFile) "edited" else "not edited yet",
                thumb = lib.thumbnails[item.fileName], onOpen = { vm.openDarkroom(i) },
            )
        },
    )
    PhotoListScreen(
        title = "dt-android",
        sections = sections,
        topActions = listOf(HostAction("open photos", enabled = lib.busy == null) { onOpen() }),
        menu = listOf(
            HostAction("LightPhotoLibrary server…") { vm.showServerForm() },
            HostAction("timings…") { vm.openTimings() },
            HostAction("about") { vm.show(Screen.ABOUT) },
        ),
        messages = listOfNotNull(lib.busy) + lib.messages,
        question = lib.confirmRemove?.let { r ->
            "${r.filename} has an edit that LightPhotoLibrary does not have yet. Remove it from this phone anyway? The edit is lost." to listOf(
                HostAction("keep it") { vm.keep() },
                HostAction("remove anyway") { vm.remove(r, evenUnsynced = true) },
            )
        },
    )
}

private fun lplRow(vm: AppViewModel, r: PhotoRecord, p: Progress?, step: Step?, thumb: RenderedImage?, origin: ServerOrigin,
                   onOpenInLpl: (ServerOrigin, Long) -> Unit, autoSync: Boolean = true): PhotoList.Row {
    val dirty = vm.agent.isDirty(r)
    val status = statusLine(vm, r, p, step, autoSync)
    val last = when {
        // brief P2i: when the status line says it already, the line below says what LightPhotoLibrary knows
        dirty && !r.readOnly && !status.startsWith(PhotoList.NOT_SYNCED_HERE) -> PhotoList.NOT_SYNCED_HERE
        dirty && !r.readOnly -> r.lastEdit?.line(thisDevice = vm.servers.installId)
        else -> r.lastEdit?.line(thisDevice = vm.servers.installId)
    }
    return PhotoList.Row(
        key = "${r.id}", name = r.filename,
        status = status,
        progress = step?.let { it.progress?.toFloat() ?: 0f } ?: p?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f },
        lastEdit = last, thumb = thumb,
        actions = listOf(
            HostAction("sync now", enabled = !r.readOnly && r.status != PhotoStatus.DOWNLOADING) { vm.syncNow(r) },
            HostAction("open in LightPhotoLibrary") { onOpenInLpl(origin, r.id) },
            HostAction("remove from this phone") { vm.remove(r) },
        ),
        onOpen = { vm.openLplPhoto(r) },
    )
}

/**
 * The status in plain words, one line, with the contract's state behind it (brief P2e item 3, P2f item 5):
 * a step that runs (R16) says what happens now.
 */
internal fun statusLine(vm: AppViewModel, r: PhotoRecord, p: Progress?, step: Step? = null, autoSync: Boolean = vm.agent.autoSync): String {
    val dirty = vm.agent.isDirty(r)
    step?.let { st ->
        val pct = st.progress?.let { String.format(Locale.ROOT, " %d %%", (it * 100).toInt()) } ?: ""
        return when (st.step) {
            Step.DOWNLOADING -> "downloading$pct"
            Step.SENDING_EDIT -> "syncing: sending the edit"
            Step.RENDERING_PREVIEW -> "syncing: making the preview"
            Step.SENDING_PREVIEW -> "syncing: sending the preview"
            else -> st.step
        }
    }
    val words = when (r.status) {
        PhotoStatus.DOWNLOADING -> if (p != null) AppHost.progressText("", p.done, p.total).replace("downloading … ", "downloading ")
                                   else "download not complete · continues when you open it"
        PhotoStatus.OPENING -> "ready to edit"
        PhotoStatus.EDITING -> "open in the darkroom"
        // brief P2i: with automatic sync off nothing waits: the edit stays here until "sync now"
        PhotoStatus.DIRTY -> if (autoSync) UserText.WAITING else PhotoList.NOT_SYNCED_HERE
        PhotoStatus.SYNCING -> "syncing"
        PhotoStatus.SYNCED -> if (dirty) (if (autoSync) UserText.WAITING else PhotoList.NOT_SYNCED_HERE)
                              else "synced" + if (r.previewPending) " · preview still to send" else ""
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
            Paragraph(SpektrafilmPack.state.let { st -> st.text + if (st.id != null) String.format(Locale.ROOT, ", %.1f MB; data licence CC BY-SA 4.0 (spektrafilm profiles)", st.bytes / 1e6) else "" })
            val pin by AppEngine.pinning.collectAsState()
            Paragraph("threads: ${ready.info.threads}; ${ready.cores.describe()}")
            // brief P2g: what the engine runs on, in plain words
            Paragraph("engine ${pin.describe()}")
            if (ready.pinningAtStart != pin) Paragraph("at start: ${ready.pinningAtStart.describe()}", muted = true)
            Paragraph("start: " + (if (ready.freshUnpack) "unpacked in ${ready.unpackMs} ms, " else "already unpacked, ") + "engine started in ${ready.initMs} ms")
            Paragraph("working folder: ${vm.work.dir.path}", muted = true)
            Paragraph("this install's id for LightPhotoLibrary: ${vm.servers.installId}", muted = true)
            // brief PUI2a: the interface skin, applied at once
            val skin by AppHost.settings.skin.collectAsState()
            PanelHeading("interface")
            Row(Modifier.padding(horizontal = DtTheme.space.panel, vertical = DtTheme.space.m), horizontalArrangement = Arrangement.spacedBy(DtTheme.space.s)) {
                for (s in app.dtandroid.ui.theme.Skin.entries) DtTextButton(s.key, { vm.setSkin(s) }, selected = s == skin)
            }
            Paragraph("darktable: the look of the desktop application. modern: the same screens with softer corners and an accent colour.", muted = true)
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

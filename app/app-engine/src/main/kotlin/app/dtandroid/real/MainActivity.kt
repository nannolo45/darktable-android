// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import app.dtandroid.darkroom.HostAction
import app.dtandroid.darkroom.ui.DarkroomScreen
import app.dtandroid.darkroom.ui.ImportFiles
import app.dtandroid.darkroom.ui.LayoutFiles
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import androidx.compose.runtime.remember

/**
 * The app on the real engine (brief P2b): start (unpack and start the engine), the working folder,
 * the darkroom, about and timings. Photos come in through Android's file picker and go out through
 * its save dialog; the app needs no storage permission.
 *
 * Brief P2e: photos also come from LightPhotoLibrary: the `lpldt://edit` link (VIEW, BROWSABLE) opens
 * them, the working set lists them, and while this activity is in the foreground the app heartbeats
 * to the server and carries out its jobs. One window (launchMode singleTask): a link that arrives
 * while the app is open reaches it through [onNewIntent].
 */
class MainActivity : ComponentActivity() {
    /** public for the instrumented tests (brief P2e), which drive the app through it */
    val vm: AppViewModel by viewModels()

    /** one or several photos, optionally with their .xmp files */
    private val openPhotos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.import(uris) }

    private val exportJpeg = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }

    private val saveXmp = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveXmpTo(uri)
    }

    /** brief P3b: .dtstyle and .dtpreset files (one or many) through Android's file picker, no storage permission */
    private val importStyles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val work = cacheDir
        AppEngine.scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { uri -> readPicked(uri) } }
            vm.controller.stylesPresets.import(files, work)
        }
    }

    private val importFiles = object : ImportFiles {
        override fun pickImport() = importStyles.launch(arrayOf("*/*"))
    }

    /** brief P3b: module layouts (.dtpreset) in and out through the file picker, as in the interface preview */
    private val importLayouts = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        AppEngine.scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { uri -> readPicked(uri) } }
            vm.controller.layoutEditor.import(files)
        }
    }

    private var pendingLayout: ByteArray? = null

    private val exportLayout = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingLayout ?: return@registerForActivityResult
        pendingLayout = null
        if (uri == null) return@registerForActivityResult
        val ok = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess
        vm.controller.layoutEditor.message(if (ok) "exported ${displayName(uri)}" else "the file could not be written")
    }

    private val layoutFiles = object : LayoutFiles {
        override fun pickImport() = importLayouts.launch(arrayOf("*/*"))
        override fun export(fileName: String, bytes: ByteArray) {
            pendingLayout = bytes
            exportLayout.launch(fileName)
        }
    }

    /** A picked file's name and bytes; null when it cannot be read (or is larger than any style or preset file). */
    private fun readPicked(uri: Uri): Pair<String, ByteArray>? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        contentResolver.openInputStream(uri)!!.use { s ->
            val buf = ByteArray(1 shl 16)
            while (out.size() <= MAX_IMPORT_BYTES) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        }
        if (out.size() > MAX_IMPORT_BYTES) null else displayName(uri) to out.toByteArray()
    }.getOrNull()

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun appVersion(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    /** brief P3i: the notification permission of Android 13 and later, asked once, when the first export starts */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("export", MODE_PRIVATE)
        if (prefs.getBoolean("asked-notifications", false)) return
        prefs.edit().putBoolean("asked-notifications", true).apply()
        askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        AppHost.askNotifications = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppEngine.start(this)
        // brief P3i: AppHost asks, from its own watch of the export job, when the first export starts
        AppHost.askNotifications = { runOnUiThread { askNotificationsOnce() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        val actions = listOf(
            HostAction("sync now") { syncCurrent() },
            HostAction("open in LightPhotoLibrary") { openCurrentInLpl() },
            // brief P3g: "export…" (the export screen) is the darkroom's own menu entry; the JPEG to a chosen file stays
            HostAction("export JPEG to a file…") { exportJpeg.launch(vm.exportName()) },
            HostAction("save XMP…") { saveXmp.launch(vm.xmpName()) },
            HostAction("timings…") { vm.openTimings() },
        )
        if (savedInstanceState == null) handle(intent)
        setContent {
            // brief PUI2a: the interface skin; a change applies at once, the engine keeps running
            val skin by AppHost.settings.skin.collectAsState()
            val tokens = remember(skin) { DtTokens.load(skin) }
            DtTheme(tokens) {
                val status by AppEngine.status.collectAsState()
                val screen by vm.screen.collectAsState()
                val ready = status as? AppEngine.Status.Ready
                when {
                    screen == Screen.CRASH_LOG -> CrashLogPage(onCopy = { copyText(it) }, onBack = { vm.show(if (ready == null) Screen.LIBRARY else Screen.ABOUT) })
                    ready == null -> StartScreen(status, onRetry = { AppEngine.retry(this@MainActivity) }, onCrashLog = { vm.show(Screen.CRASH_LOG) })
                    screen == Screen.LIBRARY -> LibraryScreen(vm, onOpen = { openPhotos.launch(arrayOf("*/*")) }, onOpenInLpl = { o, id -> openInLpl(o, id) })
                    screen == Screen.SERVER -> ServerScreen(vm)
                    screen == Screen.HANDOFF -> HandOffScreen(vm)
                    screen == Screen.DARKROOM -> DarkroomScreen(vm.controller, onBack = { vm.leaveDarkroom() }, onDone = { vm.leaveDarkroom() }, actions = actions,
                        layoutFiles = layoutFiles, importFiles = importFiles)
                    screen == Screen.ABOUT -> AboutScreen(vm, ready, appVersion())
                    screen == Screen.TIMINGS -> TimingsScreen(vm, appVersion(), onShare = { share(it) })
                }
            }
        }
    }

    private fun back() {
        when (vm.screen.value) {
            Screen.LIBRARY -> finish()
            Screen.DARKROOM ->
                if (vm.controller.state.value.layoutEditor) vm.controller.launch { closeLayoutEditor() }
                // brief P3d: a drawing is cancelled, a masks sheet closed, before the darkroom is left
                else if (vm.controller.masks.state.value.draw != null) vm.controller.masks.cancelDraw()
                else if (vm.controller.masks.state.value.sheet != null) vm.controller.masks.openSheet(null)
                // brief P3b: a page of the presets or styles sheet goes back to its list first
                else if (vm.controller.state.value.overlay != null) { if (!vm.controller.stylesPresets.back()) vm.controller.setOverlay(null) }
                else vm.leaveDarkroom()
            Screen.ABOUT -> vm.show(Screen.LIBRARY)
            Screen.CRASH_LOG -> vm.show(Screen.ABOUT)
            Screen.SERVER -> vm.show(Screen.LIBRARY)
            Screen.HANDOFF -> vm.cancelHandOff()
            Screen.TIMINGS -> vm.show(if (vm.controller.state.value.info != null) Screen.DARKROOM else Screen.LIBRARY)
        }
    }

    /** brief P4a: "copy" on the crash log: the text goes to the clipboard, nowhere else */
    private fun copyText(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("dt-android crash log", text))
    }

    private fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, "dt-android timings")
        startActivity(Intent.createChooser(i, "share the timings"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** brief P2e item 4: an `lpldt://edit` link; everything in it is checked by the view model. */
    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = intent.dataString ?: return
        vm.handOffLink(link)
    }

    override fun onStart() {
        super.onStart()
        // brief P2e item 6: heartbeat and jobs only while the app is in the foreground
        AppHost.foreground()
        AppHost.runKeptJobs()
        // brief P2g: the CPUs the app may use change with its state; pin again (through the engine's queue)
        AppEngine.onForeground()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshBattery()  // brief P3k: the settings line shows the state after a visit to the system's list
    }

    override fun onPause() {
        super.onPause()
        // brief P3k: LightPhotoLibrary work in flight goes on in the sync service. Started here, while the activity is
        // still visible: Android 12 and later refuse a foreground service started from the background, and onStop
        // comes after the activity is hidden. The service stops by itself when nothing is left.
        AppHost.startSyncIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        vm.saveNow()
        AppHost.background()
    }

    private fun syncCurrent() {
        val p = vm.currentLplPhoto()
        if (p == null) { vm.controller.showMessage("this photo is not from LightPhotoLibrary"); return }
        AppEngine.scope.launch {
            vm.controller.showMessage("syncing…")
            val o = vm.agent.sync(p.first, p.second)
            val r = vm.agent.record(p.first, p.second)
            vm.controller.showMessage(when (o) {
                app.dtandroid.lpl.SyncOutcome.SYNCED -> if (r?.previewPending == true) "synced; the preview follows when you leave the photo" else "synced"
                app.dtandroid.lpl.SyncOutcome.WAITING -> "waiting to sync: ${r?.error ?: "not reachable"}"
                app.dtandroid.lpl.SyncOutcome.READ_ONLY -> "edited with a newer darktable: never sent back"
                app.dtandroid.lpl.SyncOutcome.FAILED -> r?.error ?: "not synced"
                app.dtandroid.lpl.SyncOutcome.NOT_HELD -> "not on this phone"
            })
            if (o == app.dtandroid.lpl.SyncOutcome.WAITING) SyncJob.schedule(applicationContext)
        }
    }

    private fun openCurrentInLpl() {
        val p = vm.currentLplPhoto()
        if (p == null) { vm.controller.showMessage("this photo is not from LightPhotoLibrary"); return }
        openInLpl(p.first, p.second)
    }

    /**
     * brief P2e item 7: `<server>/?photo=<id>` in the LightPhotoLibrary app when it is installed (its
     * exported OPEN action, P2c), otherwise in the browser.
     */
    fun openInLpl(origin: app.dtandroid.lpl.ServerOrigin, id: Long) {
        val uri = Uri.parse("${origin.url}/?photo=$id")
        val shell = Intent(LPL_SHELL_OPEN, uri).setPackage(LPL_SHELL)
        try {
            if (shell.resolveActivity(packageManager) != null) { startActivity(shell); return }
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            vm.controller.showMessage("no app on this phone can open ${origin.url}")
        }
    }

    companion object {
        /** brief P3b: no style or preset file comes near this; a larger pick is not read */
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        const val LPL_SHELL = "app.dtandroid.lplshell"
        const val LPL_SHELL_OPEN = "app.dtandroid.lplshell.OPEN"
    }
}

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
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens

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
    private val tokens by lazy { DtTokens.load() }

    /** one or several photos, optionally with their .xmp files */
    private val openPhotos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.import(uris) }

    private val exportJpeg = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }

    private val saveXmp = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveXmpTo(uri)
    }

    private fun appVersion(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppEngine.start(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        val actions = listOf(
            HostAction("sync now") { syncCurrent() },
            HostAction("open in LightPhotoLibrary") { openCurrentInLpl() },
            HostAction("export JPEG…") { exportJpeg.launch(vm.exportName()) },
            HostAction("save XMP…") { saveXmp.launch(vm.xmpName()) },
            HostAction("timings…") { vm.openTimings() },
        )
        if (savedInstanceState == null) handle(intent)
        setContent {
            DtTheme(tokens) {
                val status by AppEngine.status.collectAsState()
                val screen by vm.screen.collectAsState()
                val ready = status as? AppEngine.Status.Ready
                when {
                    ready == null -> StartScreen(status, onRetry = { AppEngine.retry(this@MainActivity) })
                    screen == Screen.LIBRARY -> LibraryScreen(vm, onOpen = { openPhotos.launch(arrayOf("*/*")) }, onOpenInLpl = { o, id -> openInLpl(o, id) })
                    screen == Screen.SERVER -> ServerScreen(vm)
                    screen == Screen.HANDOFF -> HandOffScreen(vm)
                    screen == Screen.DARKROOM -> DarkroomScreen(vm.controller, onBack = { vm.leaveDarkroom() }, onDone = { vm.leaveDarkroom() }, actions = actions)
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
                else if (vm.controller.state.value.overlay != null) vm.controller.setOverlay(null)
                else vm.leaveDarkroom()
            Screen.ABOUT -> vm.show(Screen.LIBRARY)
            Screen.SERVER -> vm.show(Screen.LIBRARY)
            Screen.HANDOFF -> vm.cancelHandOff()
            Screen.TIMINGS -> vm.show(if (vm.controller.state.value.info != null) Screen.DARKROOM else Screen.LIBRARY)
        }
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
        const val LPL_SHELL = "app.dtandroid.lplshell"
        const val LPL_SHELL_OPEN = "app.dtandroid.lplshell.OPEN"
    }
}

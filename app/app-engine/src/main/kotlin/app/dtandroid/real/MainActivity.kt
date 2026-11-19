// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.dtandroid.darkroom.HostAction
import app.dtandroid.darkroom.ui.DarkroomScreen
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens

/**
 * The app on the real engine (brief P2b): start (unpack and start the engine), the working folder,
 * the darkroom, about and timings. Photos come in through Android's file picker and go out through
 * its save dialog; the app needs no storage permission.
 */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
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
            HostAction("export JPEG…") { exportJpeg.launch(vm.exportName()) },
            HostAction("save XMP…") { saveXmp.launch(vm.xmpName()) },
            HostAction("timings…") { vm.openTimings() },
        )
        setContent {
            DtTheme(tokens) {
                val status by AppEngine.status.collectAsState()
                val screen by vm.screen.collectAsState()
                val ready = status as? AppEngine.Status.Ready
                when {
                    ready == null -> StartScreen(status)
                    screen == Screen.LIBRARY -> LibraryScreen(vm, onOpen = { openPhotos.launch(arrayOf("*/*")) })
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
            Screen.TIMINGS -> vm.show(if (vm.controller.state.value.info != null) Screen.DARKROOM else Screen.LIBRARY)
        }
    }

    private fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, "dt-android timings")
        startActivity(Intent.createChooser(i, "share the timings"))
    }

    override fun onStop() {
        super.onStop()
        vm.saveNow()
    }
}

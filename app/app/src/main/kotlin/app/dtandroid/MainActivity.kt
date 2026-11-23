// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.InterfacePreview
import app.dtandroid.darkroom.ui.DarkroomScreen
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.darkroom.ui.LayoutFiles
import app.dtandroid.engine.api.FileLayoutStore
import app.dtandroid.schema.SchemaLoader
import app.dtandroid.schema.layout.BuiltinLayouts
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.OpenableColumns
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.TokenException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds the engine and the darkroom across configuration changes. Brief P2a:
 * the engine is the fake one, on the interface preview's sample series.
 */
class DarkroomViewModel(app: Application) : AndroidViewModel(app) {
    val schema = SchemaLoader.fromResources()
    val builtins = BuiltinLayouts.fromResources()
    /** module layouts in the app's private folder; later in darktable's own database (engine-api.md 3) */
    private val layouts = FileLayoutStore(
        File(app.filesDir, "layouts/layouts.json"),
        { wf -> builtins.presets(wf).map { it.name to it.params } },
        builtins.defaultWorkflow, builtins.fallbackLayout,
    )
    private val engine = InterfacePreview.engine(SchemaLoader.schemaTextFromResources())
    val controller = DarkroomController(engine, schema, builtins, layouts, viewModelScope).also { c ->
        // brief P3g: the stand-in engine writes PNG only, slowly enough to see the progress; files go to the app's folder
        c.exports.delivery = app.dtandroid.darkroom.FolderExportDelivery(File(app.cacheDir, "export"), File(app.filesDir, "exports"))
        c.exports.set { it.withFormat(app.dtandroid.engine.api.ExportFormat.PNG) }
        (engine as? app.dtandroid.engine.fake.FakeEngine)?.exportMsPerModule = 150
    }

    /** The tokens, with the debug override file `theme.json` from the app's folder if there is one. */
    val tokens: DtTokens
    val tokenNotice: String?

    init {
        val override = if (BuildConfig.DEBUG) app.getExternalFilesDir(null)?.let { File(it, "theme.json") }?.takeIf { it.isFile } else null
        var notice: String? = null
        tokens = try {
            DtTokens.load(override?.readText()).also { t ->
                if (override != null) notice = "palette from ${override.name}" +
                    (if (t.unknownOverrides.isNotEmpty()) "; unknown tokens ignored: ${t.unknownOverrides.joinToString()}" else "")
            }
        } catch (e: TokenException) {
            notice = "${override?.name}: ${e.message}; default palette used"
            DtTokens.load()
        }
        tokenNotice = notice
        val files = app.filesDir
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val d = File(files, "engine").apply { mkdirs() }
                engine.init(EnginePaths(d.path, app.cacheDir.path, d.path, d.path, app.cacheDir.path))
            }
            // filmstrip hidden by default on phones; shown by default in the tablet layout (ui-spec.md 1)
            val tablet = app.resources.configuration.smallestScreenWidthDp >= 600
            controller.start(InterfacePreview.series(File(files, "preview-series")), 0, filmstrip = tablet)
            tokenNotice?.let { controller.showMessage(it) }
        }
    }

    /** The layout file waiting for the save picker; kept here so a rotation while the picker is open does not lose it. */
    var pendingExport: ByteArray? = null
}

class MainActivity : ComponentActivity() {
    private val vm: DarkroomViewModel by viewModels()

    /** .dtpreset files come and go through Android's file picker: no storage permission. */
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val files = uris.mapNotNull { uri -> runCatching { displayName(uri) to contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull() }
        vm.controller.launch { layoutEditor.import(files) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = vm.pendingExport ?: return@registerForActivityResult
        vm.pendingExport = null
        if (uri == null) return@registerForActivityResult
        val ok = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess
        vm.controller.layoutEditor.message(if (ok) "exported ${displayName(uri)}" else "the file could not be written")
    }

    private val files = object : LayoutFiles {
        override fun pickImport() = importLauncher.launch(arrayOf("*/*"))
        override fun export(fileName: String, bytes: ByteArray) {
            vm.pendingExport = bytes
            exportLauncher.launch(fileName)
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // back closes "manage module layouts" first (edits are kept, as on desktop)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (vm.controller.state.value.layoutEditor) vm.controller.launch { closeLayoutEditor() } else leave()
            }
        })
        setContent {
            DtTheme(vm.tokens) {
                DarkroomScreen(vm.controller, onBack = { leave() }, onDone = { leave() }, layoutFiles = files)
            }
        }
    }

    private fun leave() {
        vm.controller.launch { save() }
        finish()
    }

    override fun onStop() {
        super.onStop()
        vm.controller.launch { save() }
    }
}

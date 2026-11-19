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
import app.dtandroid.schema.ModuleGroups
import app.dtandroid.schema.SchemaLoader
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
    val groups = ModuleGroups.fromResources(schema)
    private val engine = InterfacePreview.engine(SchemaLoader.schemaTextFromResources())
    val controller = DarkroomController(engine, schema, groups, viewModelScope)

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

}

class MainActivity : ComponentActivity() {
    private val vm: DarkroomViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DtTheme(vm.tokens) {
                DarkroomScreen(vm.controller, onBack = { leave() }, onDone = { leave() })
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

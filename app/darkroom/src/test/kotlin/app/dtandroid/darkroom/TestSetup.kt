// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.engine.api.FileLayoutStore
import app.dtandroid.schema.layout.BuiltinLayouts
import app.dtandroid.schema.SchemaLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File

/** A controller on the fake engine with the interface preview's series, as the app builds it. */
object TestSetup {
    val schemaText by lazy { SchemaLoader.schemaTextFromResources() }
    val schema by lazy { SchemaLoader.fromResources() }
    val builtins by lazy { BuiltinLayouts.fromResources() }

    /** A layout store in [dir], as the app makes it. */
    fun layouts(dir: File) = FileLayoutStore(
        File(dir, "layouts/layouts.json"), { wf -> builtins.presets(wf).map { it.name to it.params } },
        builtins.defaultWorkflow, builtins.fallbackLayout,
    )

    fun controller(dir: File, index: Int = 0, filmstrip: Boolean = false, thumbnails: Boolean = true): Pair<DarkroomController, FakeEngine> {
        val engine = InterfacePreview.engine(schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, schema, builtins, layouts(dir), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), previewMaxSize = 900)
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), index, filmstrip, thumbnails) }
        return c to engine
    }
}

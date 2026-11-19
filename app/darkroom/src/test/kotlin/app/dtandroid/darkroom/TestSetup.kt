// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.schema.ModuleGroups
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
    val groups by lazy { ModuleGroups.fromResources(schema) }

    fun controller(dir: File, index: Int = 0, filmstrip: Boolean = false, thumbnails: Boolean = true): Pair<DarkroomController, FakeEngine> {
        val engine = InterfacePreview.engine(schemaText, FakeEngine.Timing.NONE)
        runBlocking { engine.init(EnginePaths(dir.path, dir.path, dir.path, dir.path, dir.path)) }
        val c = DarkroomController(engine, schema, groups, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), previewMaxSize = 900)
        runBlocking { c.start(InterfacePreview.series(File(dir, "series")), index, filmstrip, thumbnails) }
        return c to engine
    }
}

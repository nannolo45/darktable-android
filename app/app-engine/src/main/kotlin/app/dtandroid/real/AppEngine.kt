// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.Context
import app.dtandroid.engine.Engine
import app.dtandroid.engine.EngineInstaller
import app.dtandroid.engine.FastCores
import app.dtandroid.engine.JniEngine
import app.dtandroid.engine.NativeBridge
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EngineInfo
import app.dtandroid.engine.api.EnginePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The engine of this process and its start (brief P2b). The first start unpacks darktable's data
 * and libraries (progress shown; an interrupted unpack starts again at the next start, see
 * [EngineInstaller]); every start loads them, starts darktable with dtb_init through
 * [JniEngine.init] and pins the engine to the fast cores ([FastCores], PLAN.md 3.8).
 *
 * The engine belongs to the process, not to an activity, and lives as long as the process.
 */
object AppEngine {
    sealed interface Status {
        data class Unpacking(val progress: Float) : Status
        data object Starting : Status
        data class Ready(val info: EngineInfo, val cores: FastCores, val unpackMs: Long, val initMs: Long, val freshUnpack: Boolean) : Status
        data class Failed(val text: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Unpacking(0f))
    val status: StateFlow<Status> = _status.asStateFlow()

    val engine: JniEngine get() = JniEngine.instance

    /** Work that must finish even when the activity goes away (exports, saves). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The packed engine's id (first 16 hex digits of its SHA-256), for the timings page. */
    @Volatile var engineId: String = "?"
        private set

    private var started = false

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch(Dispatchers.IO) {
            try {
                engineId = runCatching { EngineInstaller(app).packedId() }.getOrDefault("?")
                val p = Engine.prepare(app, progress = { _status.value = Status.Unpacking(it) })
                val r = p.report
                if (r.fatal != null || r.libFailures.isNotEmpty() || r.pluginDlopenFailures.isNotEmpty()) {
                    _status.value = Status.Failed(r.summary())
                    return@launch
                }
                _status.value = Status.Starting
                val t0 = System.nanoTime()
                engine.init(EnginePaths(p.paths.configDir, p.paths.cacheDir, p.paths.dataDir, p.paths.moduleDir, p.paths.tmpDir, ":memory:"), 0)
                val initMs = (System.nanoTime() - t0) / 1_000_000
                val cores = FastCores.detect()
                if (cores.pin) {
                    engine.setThreads(cores.chosen.size)
                    engine.setThreadCpus(cores.chosen)
                }
                val info = engine.engineInfo()
                if (info.pluginsMissing.isNotEmpty()) {
                    _status.value = Status.Failed("darktable did not load these plugins: ${info.pluginsMissing.joinToString()}")
                    return@launch
                }
                _status.value = Status.Ready(info, cores, r.unpackMs, initMs, r.freshUnpack)
            } catch (e: EngineException) {
                _status.value = Status.Failed("the engine did not start: ${e.message}\n${runCatching { NativeBridge.lastError() }.getOrDefault("")}")
            } catch (e: Throwable) {
                _status.value = Status.Failed("the engine did not start: $e")
            }
        }
    }
}

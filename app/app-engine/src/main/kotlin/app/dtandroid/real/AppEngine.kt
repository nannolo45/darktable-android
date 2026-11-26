// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.Context
import app.dtandroid.engine.Engine
import app.dtandroid.engine.EnginePinning
import app.dtandroid.engine.EngineInstaller
import app.dtandroid.engine.FastCores
import app.dtandroid.engine.JniEngine
import app.dtandroid.engine.NativeBridge
import app.dtandroid.engine.Pinning
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EngineInfo
import app.dtandroid.engine.api.EnginePaths
import kotlinx.coroutines.CoroutineExceptionHandler
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
 * Brief P2g: pinning never stops the start. The fast cores are chosen among the CPUs the process may
 * use at that moment ([EnginePinning]); when none is allowed or the bridge refuses, the engine runs
 * unpinned. The pinning is applied again each time the app comes to the foreground ([onForeground]),
 * because Android changes the CPUs a process may use with its state and may reset a thread's own
 * CPU set when it moves the process (see the P2g report). A failed start can be tried again ([retry]).
 *
 * The engine belongs to the process, not to an activity, and lives as long as the process.
 */
object AppEngine {
    sealed interface Status {
        data class Unpacking(val progress: Float) : Status
        data object Starting : Status
        data class Ready(val info: EngineInfo, val cores: FastCores, val unpackMs: Long, val initMs: Long, val freshUnpack: Boolean, val pinningAtStart: Pinning) : Status
        data class Failed(val text: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Unpacking(0f))
    val status: StateFlow<Status> = _status.asStateFlow()

    val engine: JniEngine get() = JniEngine.instance

    /** Work that must finish even when the activity goes away (exports, saves). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        // P2e follow-up: a failure in background work (a sync, a status write) is a bug to fix, but it must
        // not end the process with the user's darkroom in it. Logged under this tag; CI collects it.
        android.util.Log.e("dt-android", "uncaught in background work", e)
        // brief P4a: kept in the crash log as well (the process lives on)
        runCatching { AppHost.crashLog.append("error in background work", app.dtandroid.darkroom.robust.CrashLog.stackOf(e)) }
    })

    /** The packed engine's id (first 16 hex digits of its SHA-256), for the timings page. */
    @Volatile var engineId: String = "?"
        private set

    private var started = false

    private val _pinning = MutableStateFlow(Pinning.NONE)
    /** What the engine threads run on now (brief P2g), for the about page and the timings. */
    val pinning: StateFlow<Pinning> = _pinning.asStateFlow()

    /** The engine thread's CPUs right after dtb_init: the set the bridge checks every pinning against. */
    @Volatile var cpusAtStart: List<Int>? = null
        private set

    /** What the last return to the foreground found and did, in plain words (brief P2g, for the timings). */
    @Volatile var lastForeground: String = "the app has not come back to the foreground since the engine started"
        private set

    /** How many times [onForeground] has applied the pinning again (for tests). */
    @Volatile var foregroundRuns: Int = 0
        private set

    private val pinLock = kotlinx.coroutines.sync.Mutex()

    /** Brief P2g: try a failed start again without killing the app. */
    @Synchronized
    fun retry(ctx: Context) {
        if (_status.value !is Status.Failed) return
        started = false
        _status.value = Status.Starting
        start(ctx)
    }

    /**
     * Brief P2g: apply the pinning again (the app came to the foreground). Goes through the engine's
     * queue like every other call; never shows an error.
     */
    fun onForeground() {
        if (_status.value !is Status.Ready) return
        scope.launch {
            pinLock.lock()
            try {
                val prev = _pinning.value
                val threadNow = engine.engineThreadCpus()
                val allowed = EnginePinning.readAllowed()
                val kept = when {
                    !prev.pinned -> "the engine was not pinned"
                    threadNow == null -> "the engine thread's CPUs could not be read"
                    threadNow.sorted() == prev.cpus.sorted() -> "the pinning to CPUs ${FastCores.formatCpuList(prev.cpus)} was still in place"
                    else -> "the pinning to CPUs ${FastCores.formatCpuList(prev.cpus)} had been reset by Android to CPUs ${FastCores.formatCpuList(threadNow)}"
                }
                val cores = (_status.value as? Status.Ready)?.cores ?: return@launch
                val p = EnginePinning.pin(engine, cores, allowed, cpusAtStart, prev)
                _pinning.value = p
                lastForeground = "$kept; the app may use CPUs ${allowed?.let { FastCores.formatCpuList(it) } ?: "(not readable)"}; now ${p.describe()}"
                foregroundRuns++
                android.util.Log.i("dt-android", "foreground: $lastForeground")
            } finally {
                pinLock.unlock()
            }
        }
    }

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Processing.init(app)
        scope.launch(Dispatchers.IO) {
            try {
                engineId = runCatching { EngineInstaller(app).packedId() }.getOrDefault("?")
                val p = Engine.prepare(app, progress = { _status.value = Status.Unpacking(it) })
                val r = p.report
                if (r.fatal != null || r.libFailures.isNotEmpty() || r.pluginDlopenFailures.isNotEmpty()) {
                    _status.value = Status.Failed(r.summary())
                    return@launch
                }
                // brief P2f: spektrafilm's data, where darktable looks for a hand-installed pack
                SpektrafilmPack.install(app).also { android.util.Log.i("dt-android", it.text) }
                _status.value = Status.Starting
                val t0 = System.nanoTime()
                // a retry after a failure later in the start finds darktable already started (once per process)
                // brief P4b: darktable's memory budget for this phone and the "processing memory" setting; OpenCL allowed
                if (!engine.initialised)
                    engine.init(EnginePaths(p.paths.configDir, p.paths.cacheDir, p.paths.dataDir, p.paths.moduleDir, p.paths.tmpDir, ":memory:"), 0,
                        Processing.startOptions(app))
                // brief P6b: darktable's LUT folder (the same folder as AppHost.agent.luts: lpl/luts beside the work folder)
                runCatching { engine.lutFolderSet(java.io.File(app.filesDir, "lpl/luts").also { it.mkdirs() }.path) }
                val initMs = (System.nanoTime() - t0) / 1_000_000
                val cores = runCatching { FastCores.detect() }.getOrElse { FastCores(emptyList(), emptyMap(), emptyList()) }
                // brief P2g: never an error from here; the result says what happened
                pinLock.lock()
                val pin = try {
                    if (cpusAtStart == null) cpusAtStart = engine.engineThreadCpus()
                    EnginePinning.pin(engine, cores, EnginePinning.readAllowed(), cpusAtStart, _pinning.value)
                } finally { pinLock.unlock() }
                _pinning.value = pin
                android.util.Log.i("dt-android", "engine start: ${pin.describe()}; engine thread CPUs at start ${cpusAtStart?.let { FastCores.formatCpuList(it) } ?: "not readable"}")
                val info = engine.engineInfo()
                if (info.pluginsMissing.isNotEmpty()) {
                    _status.value = Status.Failed("darktable did not load these plugins: ${info.pluginsMissing.joinToString()}")
                    return@launch
                }
                // brief P4b: the GPU, when switched on (and checked) earlier
                Processing.afterStart(engine)
                _status.value = Status.Ready(info, cores, r.unpackMs, initMs, r.freshUnpack, pin)
            } catch (e: EngineException) {
                _status.value = Status.Failed("the engine did not start: ${e.message}\n${runCatching { NativeBridge.lastError() }.getOrDefault("")}")
            } catch (e: Throwable) {
                _status.value = Status.Failed("the engine did not start: $e")
            }
        }
    }
}

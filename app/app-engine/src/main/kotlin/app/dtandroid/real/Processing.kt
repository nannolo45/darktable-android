// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.dtandroid.darkroom.DarkroomController
import app.dtandroid.darkroom.robust.CrashLog
import app.dtandroid.darkroom.speed.GpuCheck
import app.dtandroid.darkroom.speed.ProcessingResources
import app.dtandroid.darkroom.speed.ProcessingSettings
import app.dtandroid.darkroom.speed.SpeedBenchmark
import app.dtandroid.darkroom.speed.TestRaw
import app.dtandroid.darkroom.ui.ProcessingUi
import app.dtandroid.engine.JniEngine
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EngineStartOptions
import app.dtandroid.engine.api.OpenClInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Brief P4b: Settings › processing for the whole process. The memory plan is chosen when the engine starts
 * ([startOptions]); the GPU is started then when the setting is on ([afterStart]); the screen's actions switch the GPU
 * (with [GpuCheck]), change the memory choice (next start) and run [SpeedBenchmark] on the public test RAW ([TestRaw]).
 * OpenCL errors during renders are written to the crash log ([JniEngine.onOpenClTrouble]).
 */
object Processing {
    @Volatile private var benchDir: File? = null
    @Volatile private var _settings: ProcessingSettings? = null

    /** in memory until the app's folder is known ([init]) */
    val settings: ProcessingSettings get() = _settings ?: synchronized(this) { _settings ?: ProcessingSettings(null).also { _settings = it } }

    /** The settings file and the benchmark folder in the app's storage; idempotent. */
    @Synchronized
    fun init(ctx: Context) {
        if (benchDir != null) return
        val app = ctx.applicationContext
        benchDir = File(app.filesDir, "benchmark")
        _settings = ProcessingSettings(File(app.filesDir, "settings/processing.json"))
        refresh()
    }

    private val _ui = MutableStateFlow(ProcessingUi())
    val ui: StateFlow<ProcessingUi> = _ui.asStateFlow()

    /** the plan the engine was started with */
    @Volatile var plan: ProcessingResources.Plan? = null
        private set

    @Volatile private var totalMem: Long = 0

    /** brief P4d: the text "copy GPU details" copies; from the last GPU switch on (also the one at the start) */
    @Volatile private var gpuDetails: String? = null

    /** brief P4d: the app's own OpenCL library loading (System.loadLibrary puts it in the app's linker namespace) */
    private fun libraries() = app.dtandroid.darkroom.speed.OpenClLibraries({ System.loadLibrary(it) })

    private suspend fun details(e: app.dtandroid.engine.api.DarktableEngine, appVersion: String?, note: String?, probe: app.dtandroid.darkroom.speed.OpenClLibraries.Probe?): String? =
        runCatching {
            val header = listOf("app: ${appVersion ?: "?"}, engine package ${AppEngine.engineId}",
                "android api ${Build.VERSION.SDK_INT}, abi ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
            app.dtandroid.darkroom.speed.GpuDetails.text(header, note, probe, e.openclDiagnostics(), e.openclInfo())
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; "GPU details could not be read: ${it.message}" }

    private fun refresh() {
        val v = settings.values.value
        _ui.update {
            it.copy(gpu = v.gpu, gpuNote = v.gpuNote, gpuDetails = gpuDetails, memory = v.memory, memoryNow = plan?.describe(),
                memoryPending = plan != null && plan?.choice != v.memory)
        }
    }

    /** The start-up options for this phone and setting; called before dtb_init_ex. */
    fun startOptions(ctx: Context): EngineStartOptions {
        init(ctx)
        val app = ctx.applicationContext
        totalMem = runCatching {
            val am = app.getSystemService(ActivityManager::class.java)
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }.getOrDefault(0L)
        val p = ProcessingResources.plan(totalMem, settings.values.value.memory)
        plan = p
        android.util.Log.i("dt-android", "processing: ${p.describe()} (total memory ${totalMem / 1048576} MB)")
        refresh()
        // darktable's compiled OpenCL kernels outside the cache folder: Android may clear that, and building them
        // again takes minutes (reviewer, P4b); not backed up (they belong to this phone's driver)
        return p.options.copy(openclKernelDir = File(app.noBackupFilesDir, "opencl-kernels").path)
    }

    /** After dtb_init_ex: watch OpenCL errors; start the GPU when the setting is on (its check passed earlier). */
    suspend fun afterStart(engine: JniEngine) {
        engine.onOpenClTrouble = { i -> trouble(i) }
        if (!settings.values.value.gpu) return
        val probe = runCatching { libraries().probe() }.getOrNull()
        probe?.let { p -> runCatching { engine.openclLibraries(p.preferred) } }
        val i = try { engine.openclSet(true) } catch (e: EngineException) { null }
        var note: String? = settings.values.value.gpuNote
        if (i == null || !i.running) {
            val d = runCatching { engine.openclDiagnostics() }.getOrNull()
            val why = "the GPU was switched off at the start: " + (if (i == null) "this engine cannot use it"
                else d?.let { app.dtandroid.darkroom.speed.GpuDetails.reason(it, i) } ?: "darktable found no GPU it can use")
            note = why
            settings.update { it.copy(gpu = false, gpuNote = why) }
            AppHost.crashLog.append(CrashLog.KIND_OPENCL, why)
        }
        gpuDetails = details(engine, null, note, probe)
        refresh()
    }

    private fun trouble(i: OpenClInfo) {
        val text = if (i.stopped) "darktable stopped using the GPU for this session after repeated OpenCL errors (${i.errorCount}); " +
            "renders run on the CPU. The GPU setting was switched off."
        else "an OpenCL error during a render or an export: darktable finished it on the CPU (GPU errors so far ${i.errorCount})"
        AppHost.crashLog.append(CrashLog.KIND_OPENCL, text)
        if (i.stopped) { settings.update { it.copy(gpu = false, gpuNote = text) }; refresh() }
    }

    fun show() = refresh()

    fun setMemory(choice: ProcessingResources.Choice) {
        settings.update { it.copy(memory = choice) }
        refresh()
    }

    private fun testRaw(progress: (String) -> Unit): String {
        val t = TestRaw(benchDir ?: throw IOException("the app's folder is not known yet"))
        if (!t.present()) progress("fetching the test photo (16 MB)")
        return t.ensure().path
    }

    fun setGpu(on: Boolean, controller: DarkroomController, appVersion: String? = null) {
        if (_ui.value.busy || on == settings.values.value.gpu) return
        AppEngine.scope.launch {
            if (!on) {
                runCatching { controller.withEngine { e -> e.openclSet(false) } }
                settings.update { it.copy(gpu = false, gpuNote = "GPU off") }
                refresh()
                return@launch
            }
            _ui.update { it.copy(gpuBusy = "starting") }
            val result = try {
                val raw = withContext(Dispatchers.IO) { testRaw { s -> _ui.update { it.copy(gpuBusy = s) } } }
                if (controller.state.value.info != null) controller.closePhoto()
                controller.withEngine { e ->
                    val check = GpuCheck(e, raw, libraries())
                    val r = check.run { s -> _ui.update { it.copy(gpuBusy = s) } }
                    val n = if (r is GpuCheck.Result.On) r.text else "GPU off: ${r.text}"
                    // brief P4d: what "copy GPU details" copies
                    gpuDetails = details(e, appVersion, n, check.probe)
                    r
                }
            } catch (e: IOException) {
                GpuCheck.Result.Off(e.message ?: "the test photo could not be fetched")
            }
            val note = if (result is GpuCheck.Result.On) result.text else "GPU off: ${result.text}"
            settings.update { it.copy(gpu = result is GpuCheck.Result.On, gpuNote = note) }
            if (result is GpuCheck.Result.Off) AppHost.crashLog.append(CrashLog.KIND_OPENCL, "switching the GPU on: ${result.text}")
            _ui.update { it.copy(gpuBusy = null) }
            refresh()
        }
    }

    fun measure(controller: DarkroomController, appVersion: String) {
        if (_ui.value.busy) return
        AppEngine.scope.launch {
            _ui.update { it.copy(speedRunning = "starting") }
            val text = try {
                val raw = withContext(Dispatchers.IO) { testRaw { s -> _ui.update { it.copy(speedRunning = s) } } }
                if (controller.state.value.info != null) controller.closePhoto()
                val gpu = settings.values.value.gpu
                val header = listOf(
                    "app: $appVersion, engine package ${AppEngine.engineId}",
                    "android api ${Build.VERSION.SDK_INT}; memory ${totalMem / 1048576} MB; ${plan?.describe() ?: "?"}",
                    "engine ${AppEngine.pinning.value.describe()}",
                    "gpu: " + (if (gpu) settings.values.value.gpuNote ?: "on" else "off"),
                )
                controller.withEngine { e ->
                    val info = e.engineInfo()
                    val r = SpeedBenchmark(e, raw, gpu, header + "threads: ${info.threads}").run { s -> _ui.update { it.copy(speedRunning = s) } }
                    val res = e.resourcesInfo()
                    r.text + "engine: ${res.describe()}\n" + String.format(Locale.ROOT, "RAW decodes since the app started: %d\n", res.mipmapFullDecodes)
                }
            } catch (e: IOException) {
                e.message ?: "the test photo could not be fetched"
            } catch (e: EngineException) {
                "measure speed stopped: ${e.message}"
            }
            _ui.update { it.copy(speedRunning = null, speedText = text) }
        }
    }
}

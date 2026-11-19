// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine

import android.content.Context
import android.system.Os
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue

/** What happened while the engine was unpacked, loaded and started. */
data class EngineStartReport(
    val engineDir: String,
    val freshUnpack: Boolean,
    val unpackMs: Long,
    val unpackedBytes: Long,
    val unpackedFiles: Int,
    val libsInLoadOrder: Int,
    val libFailures: List<String>,
    val jniError: String?,
    val pluginFiles: Int,
    val pluginDlopenFailures: List<String>,
    val initCode: Int,
    val initError: String?,
    val initMs: Long,
    val engineVersion: String?,
    val threads: Int,
    val pluginsJson: String?,
    val iopFiles: Int,
    val iopLoaded: Int,
    val formatFiles: Int,
    val formatLoaded: Int,
    val storageFiles: Int,
    val storageLoaded: Int,
    /** files that darktable did not load: iop, format and storage plugins */
    val notLoaded: List<String>,
    val fatal: String?,
) {
    /** Engine runs, every library and every plugin file loaded. */
    val ok: Boolean
        get() = fatal == null && libFailures.isEmpty() && jniError == null &&
            pluginDlopenFailures.isEmpty() && initCode == 0 && notLoaded.isEmpty()

    /** darktable's own count: processing modules, formats and storages */
    val pluginsLoaded: Int get() = iopLoaded + formatLoaded + storageLoaded
    val pluginsExpected: Int get() = iopFiles + formatFiles + storageFiles

    fun summary(): String = buildString {
        appendLine("engine dir: $engineDir")
        appendLine(
            "unpack: ${if (freshUnpack) "fresh, $unpackMs ms" else "already unpacked"}, " +
                "${unpackedFiles} files, ${unpackedBytes / 1_000_000.0} MB"
        )
        appendLine("libraries loaded from app storage: ${libsInLoadOrder - libFailures.size} of $libsInLoadOrder")
        libFailures.forEach { appendLine("  FAILED $it") }
        if (jniError != null) appendLine("JNI layer: FAILED $jniError")
        appendLine("plugin files that dlopen() accepts: ${pluginFiles - pluginDlopenFailures.size} of $pluginFiles")
        pluginDlopenFailures.forEach { appendLine("  FAILED $it") }
        appendLine("dtb_init: ${if (initCode == 0) "ok" else "FAILED ${NativeBridge.codeName(initCode)} $initError"} ($initMs ms)")
        if (initCode == 0) {
            appendLine("engine: darktable $engineVersion, $threads threads")
            appendLine(
                "darktable loaded: $iopLoaded of $iopFiles processing modules, " +
                    "$formatLoaded of $formatFiles formats, $storageLoaded of $storageFiles storages"
            )
            if (notLoaded.isNotEmpty()) appendLine("  NOT LOADED: ${notLoaded.joinToString()}")
        }
        if (fatal != null) appendLine("FATAL: $fatal")
    }
}

/**
 * The engine thread and the engine's start-up (brief G0, experiment S4).
 *
 * dtbridge.h requires one engine thread for all calls. It gets a large stack
 * because darktable's code expects a desktop-sized main-thread stack.
 */
object Engine {
    private const val STACK_BYTES = 64L * 1024 * 1024
    private val queue = LinkedBlockingQueue<Runnable>()
    private val thread = Thread(null, {
        while (true) {
            val r = queue.take()
            r.run()
        }
    }, "dt-engine", STACK_BYTES).apply { isDaemon = true; start() }

    @Volatile var report: EngineStartReport? = null
        private set

    /** Queue [r] on the engine thread (brief P2b: JniEngine's cancellable calls). */
    fun post(r: Runnable) = queue.put(r)

    /** True on the engine thread. */
    val onEngineThread: Boolean get() = Thread.currentThread() === thread

    /** Run [block] on the engine thread and wait for it without blocking a coroutine thread. */
    suspend fun <T> call(block: () -> T): T {
        val d = CompletableDeferred<T>()
        queue.put(Runnable { d.completeWith(runCatching(block)) })
        return d.await()
    }

    /** Same, blocking the caller; for tests and the start-up path. */
    fun <T> callBlocking(block: () -> T): T {
        check(Thread.currentThread() !== thread) { "already on the engine thread" }
        val t = FutureTask(block)
        queue.put(t)
        return t.get()
    }

    /** Unpack (first start), load and start the engine once per process. */
    @Synchronized
    fun start(context: Context, log: (String) -> Unit = {}): EngineStartReport {
        report?.let { return it }
        val r = callBlocking { startOnEngineThread(context.applicationContext, log) }
        report = r
        return r
    }

    /**
     * What [prepare] leaves behind: the engine is unpacked, its libraries and the JNI layer are
     * loaded, the environment is set, but dtb_init has not run. [paths] are the folders the app
     * passes to init (brief P2b: the app starts the engine through DarktableEngine.init).
     */
    data class Prepared(val report: EngineStartReport, val paths: Paths)

    data class Paths(val configDir: String, val cacheDir: String, val dataDir: String, val moduleDir: String, val tmpDir: String)

    @Volatile var prepared: Prepared? = null
        private set

    /**
     * Brief P2b: everything [start] does except dtb_init, once per process. [progress] gets
     * the share of the packed engine unpacked so far (0..1), on the engine thread.
     */
    @Synchronized
    fun prepare(context: Context, log: (String) -> Unit = {}, progress: (Float) -> Unit = {}): Prepared {
        prepared?.let { return it }
        val p = callBlocking { prepareOnEngineThread(context.applicationContext, log, progress) }
        if (p.report.fatal == null) prepared = p
        return p
    }

    private fun paths(ctx: Context, inst: EngineInstaller) = Paths(
        configDir = File(ctx.filesDir, "dt/config").absolutePath,
        cacheDir = File(ctx.cacheDir, "dt-cache").absolutePath,
        dataDir = File(inst.engineDir, "share/darktable").absolutePath,
        moduleDir = File(inst.engineDir, "lib/darktable").absolutePath,
        tmpDir = File(ctx.cacheDir, "dt-tmp").absolutePath,
    )

    private fun prepareOnEngineThread(ctx: Context, log: (String) -> Unit, progress: (Float) -> Unit): Prepared {
        val inst = EngineInstaller(ctx)
        val unpack = try {
            inst.unpack(log, progress)
        } catch (e: Exception) {
            return Prepared(failed(inst.engineDir, "unpack failed: $e"), paths(ctx, inst))
        }
        log("unpacked: ${unpack.files} files, ${unpack.bytes} bytes" + if (unpack.fresh) " in ${unpack.ms} ms" else " (kept)")
        val loads = inst.loadLibraries(log)
        try {
            System.loadLibrary("dtjni")
        } catch (e: Throwable) {
            val why = e.message ?: e.toString()
            return Prepared(failed(inst.engineDir, "JNI layer did not load: $why").copy(
                freshUnpack = unpack.fresh, unpackMs = unpack.ms, unpackedBytes = unpack.bytes,
                unpackedFiles = unpack.files, libsInLoadOrder = loads.total,
                libFailures = loads.failures, jniError = why,
            ), paths(ctx, inst))
        }
        NativeBridge.startLogCapture()
        NativeBridge.logLine("[dt-android] engine unpacked to ${inst.engineDir}")
        val plugins = inst.pluginFiles()
        val dlFailures = plugins.mapNotNull { f ->
            NativeBridge.dlopenCheck(f.absolutePath)?.let { "${f.relativeTo(inst.engineDir)}: $it" }
        }
        setEnvironment(ctx)
        val r = failed(inst.engineDir, "").copy(
            freshUnpack = unpack.fresh, unpackMs = unpack.ms, unpackedBytes = unpack.bytes,
            unpackedFiles = unpack.files, libsInLoadOrder = loads.total, libFailures = loads.failures,
            pluginFiles = plugins.size, pluginDlopenFailures = dlFailures, initCode = 0, initError = null, fatal = null,
        )
        return Prepared(r, paths(ctx, inst))
    }

    private fun setEnvironment(ctx: Context) {
        // darktable and GLib read these; the app has no HOME of its own
        val home = File(ctx.filesDir, "home").apply { mkdirs() }
        val tmp = File(ctx.cacheDir, "dt-tmp").apply { mkdirs() }
        Os.setenv("HOME", home.absolutePath, true)
        Os.setenv("TMPDIR", tmp.absolutePath, true)
        Os.setenv("XDG_CONFIG_HOME", File(home, ".config").absolutePath, true)
        Os.setenv("XDG_CACHE_HOME", File(ctx.cacheDir, "xdg").absolutePath, true)
        Os.setenv("XDG_DATA_HOME", File(home, ".local/share").absolutePath, true)
        // brief P2f: darktable's own per-module times ("-d perf": "[dev_pixelpipe] took … processed `exposure'"),
        // read back from the captured log by the timings page; dtb_init reads it (engine/src/core.c)
        Os.setenv("DTB_DT_DEBUG", "perf", false)
    }

    private fun startOnEngineThread(ctx: Context, log: (String) -> Unit): EngineStartReport {
        val inst = EngineInstaller(ctx)
        val unpack = try {
            inst.unpack(log)
        } catch (e: Exception) {
            return failed(inst.engineDir, "unpack failed: $e")
        }
        log("unpacked: ${unpack.files} files, ${unpack.bytes} bytes" + if (unpack.fresh) " in ${unpack.ms} ms" else " (kept)")

        val loads = inst.loadLibraries(log)
        var jniError: String? = null
        try {
            System.loadLibrary("dtjni")
        } catch (e: Throwable) {
            jniError = e.message ?: e.toString()
        }
        if (jniError != null) {
            return failed(inst.engineDir, "JNI layer did not load: $jniError").copy(
                freshUnpack = unpack.fresh, unpackMs = unpack.ms, unpackedBytes = unpack.bytes,
                unpackedFiles = unpack.files, libsInLoadOrder = loads.total,
                libFailures = loads.failures, jniError = jniError,
            )
        }
        NativeBridge.startLogCapture()
        NativeBridge.logLine("[dt-android] engine unpacked to ${inst.engineDir}")

        val plugins = inst.pluginFiles()
        val dlFailures = plugins.mapNotNull { f ->
            NativeBridge.dlopenCheck(f.absolutePath)?.let { "${f.relativeTo(inst.engineDir)}: $it" }
        }
        log("dlopen check: ${plugins.size - dlFailures.size} of ${plugins.size} plugin files")

        // darktable and GLib read these; the app has no HOME of its own
        val home = File(ctx.filesDir, "home").apply { mkdirs() }
        val tmp = File(ctx.cacheDir, "dt-tmp").apply { mkdirs() }
        Os.setenv("HOME", home.absolutePath, true)
        Os.setenv("TMPDIR", tmp.absolutePath, true)
        Os.setenv("XDG_CONFIG_HOME", File(home, ".config").absolutePath, true)
        Os.setenv("XDG_CACHE_HOME", File(ctx.cacheDir, "xdg").absolutePath, true)
        Os.setenv("XDG_DATA_HOME", File(home, ".local/share").absolutePath, true)

        val t0 = System.nanoTime()
        val code = NativeBridge.init(
            configdir = File(ctx.filesDir, "dt/config").absolutePath,
            cachedir = File(ctx.cacheDir, "dt-cache").absolutePath,
            datadir = File(inst.engineDir, "share/darktable").absolutePath,
            moduledir = File(inst.engineDir, "lib/darktable").absolutePath,
            tmpdir = tmp.absolutePath,
            // a fresh library per start: each open reads the XMP the user picked
            library = ":memory:",
            threads = 0,
        )
        val initMs = (System.nanoTime() - t0) / 1_000_000
        val initError = if (code != 0) NativeBridge.lastError() else null

        var pj: String? = null
        val counts = IntArray(6)
        val notLoaded = mutableListOf<String>()
        if (code == 0) {
            pj = NativeBridge.pluginsJson()
            pj?.let {
                // engine_info (Engine API v1): the plugin report is under "plugins"
                val o = JSONObject(it).getJSONObject("plugins")
                listOf("iop", "format", "storage").forEachIndexed { i, k ->
                    val kind = o.getJSONObject(k)
                    counts[2 * i] = kind.getInt("files")
                    counts[2 * i + 1] = kind.getInt("loaded")
                    val a = kind.getJSONArray("not_loaded")
                    for (j in 0 until a.length()) notLoaded += "$k/${a.getString(j)}"
                }
            }
        }
        return EngineStartReport(
            engineDir = inst.engineDir.absolutePath,
            freshUnpack = unpack.fresh, unpackMs = unpack.ms, unpackedBytes = unpack.bytes,
            unpackedFiles = unpack.files,
            libsInLoadOrder = loads.total, libFailures = loads.failures, jniError = null,
            pluginFiles = plugins.size, pluginDlopenFailures = dlFailures,
            initCode = code, initError = initError, initMs = initMs,
            engineVersion = if (code == 0) NativeBridge.engineVersion() else null,
            threads = if (code == 0) NativeBridge.getThreads() else 0,
            pluginsJson = pj,
            iopFiles = counts[0], iopLoaded = counts[1], formatFiles = counts[2],
            formatLoaded = counts[3], storageFiles = counts[4], storageLoaded = counts[5],
            notLoaded = notLoaded,
            fatal = when {
                code != 0 -> "dtb_init failed"
                pj == null -> "plugin report missing: ${NativeBridge.lastError()}"
                else -> null
            },
        )
    }

    private fun failed(dir: File, why: String) = EngineStartReport(
        engineDir = dir.absolutePath, freshUnpack = false, unpackMs = 0, unpackedBytes = 0,
        unpackedFiles = 0, libsInLoadOrder = 0, libFailures = emptyList(), jniError = null,
        pluginFiles = 0, pluginDlopenFailures = emptyList(), initCode = NativeBridge.E_STATE,
        initError = why, initMs = 0, engineVersion = null, threads = 0, pluginsJson = null,
        iopFiles = 0, iopLoaded = 0, formatFiles = 0, formatLoaded = 0, storageFiles = 0,
        storageLoaded = 0, notLoaded = emptyList(), fatal = why,
    )
}

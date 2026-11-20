// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EngineInfo
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.History
import app.dtandroid.engine.api.ImageInfo
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.Preset
import app.dtandroid.engine.api.PresetRef
import app.dtandroid.engine.api.RenderTimes
import app.dtandroid.engine.api.Style
import app.dtandroid.engine.api.StyleImport
import app.dtandroid.engine.api.StyleMode
import app.dtandroid.engine.api.RenderSize
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.api.UndoState
import app.dtandroid.engine.api.XmpCheck
import app.dtandroid.engine.api.parseEngineJson
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [DarktableEngine] over JNI on engine/include/dtbridge.h (brief P2b).
 *
 * Threading model
 * - One engine thread per process ([Engine]: 64 MB stack, a queue). Every call except
 *   [renderCancel] is a task on that queue; the caller suspends until it ran. The queue runs one
 *   task at a time, so calls on the session never overlap, whatever coroutine they come from.
 * - A caller that is cancelled while its task still waits in the queue removes it: the task is
 *   skipped when its turn comes (a stale render never starts). A task that already runs cannot be
 *   stopped from Kotlin; for renders, [renderCancel] stops the pipeline between modules and the
 *   call fails with CANCELLED.
 * - [renderCancel] calls dtb_render_cancel from the calling thread, as the bridge allows.
 * - The engine and its session belong to the process, not to an activity: an activity that is
 *   recreated finds the same session (the controller lives in a ViewModel); a render or export
 *   running when the app goes to the background finishes on the engine thread.
 *
 * Errors: a negative bridge code becomes [EngineException] with dtb_last_error's text.
 * JSON from the bridge is parsed into the same types the fake engine produces.
 *
 * dtb_init runs once per process: darktable is not known to survive dt_cleanup and a second
 * dt_init (G0). [Engine.prepare] must have run first (unpack, libraries); otherwise init throws STATE.
 */
class JniEngine private constructor() : DarktableEngine {

    companion object {
        /** The one engine of this process. */
        val instance: JniEngine by lazy { JniEngine() }
    }

    /** Measurements of the last [modules] call, for the timings page. */
    data class ModulesTiming(val bytes: Int, val bridgeNs: Long, val jniNs: Long, val parseNs: Long)

    @Volatile var lastModulesTiming: ModulesTiming? = null
        private set

    /** Whether the last region render needed a copy of the Java array in JNI (false = rendered in place). */
    @Volatile var lastRegionCopied: Boolean = false
        private set

    @Volatile var initialised = false
        private set

    // ------------------------------------------------------------------ engine thread

    private inner class Task<T>(val block: () -> T, val cont: CancellableContinuation<T>) : Runnable {
        val cancelled = AtomicBoolean(false)
        override fun run() {
            if (cancelled.get()) return
            // brief P2f: the bridge's threading rule, checked: no two bridge calls ever overlap
            if (inBridge.incrementAndGet() > 1) overlaps.incrementAndGet()
            val r = try { runCatching(block) } finally { inBridge.decrementAndGet() }
            r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
    }

    private val inBridge = java.util.concurrent.atomic.AtomicInteger(0)
    private val overlaps = AtomicLong(0)
    private val cancelsWithoutRender = AtomicLong(0)

    /** Brief P2f: bridge calls that overlapped another (must stay 0), for tests. */
    val bridgeOverlaps: Long get() = overlaps.get()

    /** Brief P2f: cancels asked while no render ran; not passed to the bridge (dtbridge.h allows a cancel only during a render). */
    val cancelsOutsideRender: Long get() = cancelsWithoutRender.get()

    /** Brief P2f: true while a render runs on the engine thread; set and cleared under [cancelGuard]. */
    @Volatile private var rendering = false

    private fun <T> render(block: () -> T): T {
        synchronized(cancelGuard) { rendering = true }
        try { return block() } finally { synchronized(cancelGuard) { rendering = false } }
    }

    /** Run [block] on the engine thread; skip it if the caller is cancelled before it starts. */
    private suspend fun <T> onEngine(block: () -> T): T {
        if (Engine.onEngineThread) return block()
        return suspendCancellableCoroutine { cont ->
            val t = Task(block, cont)
            cont.invokeOnCancellation { t.cancelled.set(true) }
            Engine.post(t)
        }
    }

    private fun check(code: Int) {
        if (code != NativeBridge.OK) throw EngineException(EngineError.of(code), NativeBridge.lastError())
    }

    private fun requireInit() {
        if (!initialised) throw EngineException(EngineError.STATE, "engine not initialised")
    }

    /** A JSON getter: the parsed JSON or the bridge's error. */
    private fun json(text: String?, status: IntArray): JsonElement {
        check(status[0])
        return parseEngineJson(text ?: throw EngineException(EngineError.ENGINE, "no JSON from the engine"))
    }

    private fun get(what: Int): JsonElement {
        requireInit()
        val st = IntArray(1)
        return json(NativeBridge.jsonGet(what, st), st)
    }

    // ------------------------------------------------------------------ 2.1 lifecycle

    override suspend fun init(paths: EnginePaths, threads: Int) = onEngine {
        if (initialised) throw EngineException(EngineError.STATE, "engine already initialised")
        if (Engine.prepared == null) throw EngineException(EngineError.STATE, "engine not unpacked and loaded (Engine.prepare)")
        check(NativeBridge.init(paths.configDir, paths.cacheDir, paths.dataDir, paths.moduleDir, paths.tmpDir, paths.library, threads))
        initialised = true
    }

    override suspend fun shutdown() = onEngine {
        synchronized(cancelGuard) { if (initialised) NativeBridge.shutdown(); initialised = false }
    }

    /**
     * Brief P2f: dtbridge.h says dtb_render_cancel() "must not be called concurrently with dtb_close()": it
     * writes to the pipe of the render in flight, which close frees. The app now cancels often from other
     * threads (interactive work stopping background renders, P2f), and a background task closes its own
     * session right after its render; run 44616800258 ended in heap corruption ("Scudo ERROR: corrupted chunk
     * header"). So [renderCancel] and every call that frees or replaces the session's pipes (open, close,
     * shutdown) exclude each other with this lock. The lock is held only for the native cancel (a few
     * instructions) or by the engine thread for the open or close itself.
     */
    private val cancelGuard = Any()

    override suspend fun engineInfo(): EngineInfo = onEngine { EngineInfo.fromJson(get(NativeBridge.JSON_ENGINE_INFO)) }

    /** The bridge's engine_info as text, with the fields the Kotlin type leaves out (thread_cpus, threads_max). */
    suspend fun engineInfoJson(): JsonObject = onEngine { get(NativeBridge.JSON_ENGINE_INFO) as JsonObject }

    override suspend fun setThreads(n: Int) = onEngine { requireInit(); check(NativeBridge.setThreadsCode(n)) }

    override suspend fun setThreadCpus(cpuIds: List<Int>) = onEngine { requireInit(); check(NativeBridge.setThreadCpus(cpuIds.toIntArray())) }

    /**
     * Brief P2g: the CPUs the engine thread may run on at this moment (its own affinity, read from
     * /proc on the engine thread itself), or null when unreadable. Right after dtb_init this is the set
     * the bridge checks every pinning against; later it shows whether a pinning is still in place.
     */
    suspend fun engineThreadCpus(): List<Int>? = onEngine {
        EnginePinning.readAllowed(java.io.File("/proc/thread-self/status"))
            ?: EnginePinning.readAllowed(java.io.File("/proc/self/task/${android.os.Process.myTid()}/status"))
    }

    // ------------------------------------------------------------------ 2.2 guard

    override suspend fun xmpCheck(xmpPath: String): XmpCheck = onEngine {
        requireInit()
        val st = IntArray(1)
        XmpCheck.fromJson(json(NativeBridge.xmpCheckStatus(xmpPath, st), st))
    }

    // ------------------------------------------------------------------ 2.3 session

    override suspend fun open(rawPath: String, xmpPath: String?) = onEngine {
        requireInit(); check(synchronized(cancelGuard) { NativeBridge.open(rawPath, xmpPath) })
    }

    override suspend fun close() = onEngine { requireInit(); check(synchronized(cancelGuard) { NativeBridge.close() }) }

    override suspend fun imageInfo(): ImageInfo = onEngine { ImageInfo.fromJson(get(NativeBridge.JSON_IMAGE_INFO)) }

    override suspend fun metadataReload(xmpPath: String) = onEngine { requireInit(); check(NativeBridge.metadataReload(xmpPath)) }

    // ------------------------------------------------------------------ 2.4 modules and parameters

    override suspend fun modules(): List<ModuleInstance> = onEngine {
        requireInit()
        val st = IntArray(1)
        val t = LongArray(2)
        val text = NativeBridge.modulesTimed(t, st)
        val p0 = System.nanoTime()
        val list = ModuleInstance.listFromJson(json(text, st))
        lastModulesTiming = ModulesTiming(text?.length ?: 0, t[0], t[1], System.nanoTime() - p0)
        list
    }

    override suspend fun paramSet(op: String, priority: Int, path: String, value: JsonPrimitive) = onEngine {
        requireInit()
        check(NativeBridge.paramSet(op, priority, path, value.toString()))
    }

    override suspend fun paramsSet(op: String, priority: Int, values: Map<String, JsonPrimitive>) = onEngine {
        requireInit()
        check(NativeBridge.paramsSet(op, priority, JsonObject(values).toString()))
    }

    override suspend fun setEnabled(op: String, priority: Int, enabled: Boolean) = onEngine {
        requireInit(); check(NativeBridge.setEnabled(op, priority, enabled))
    }

    override suspend fun moduleReset(op: String, priority: Int) = onEngine { requireInit(); check(NativeBridge.moduleReset(op, priority)) }

    override suspend fun moduleAction(op: String, priority: Int, action: String, args: JsonObject): JsonElement = onEngine {
        requireInit()
        val st = IntArray(1)
        val text = NativeBridge.moduleAction(op, priority, action, args.toString(), st)
        check(st[0])
        if (text == null) JsonNull else parseEngineJson(text)
    }

    // ------------------------------------------------------------------ 2.5 instances

    private fun add(kind: Int, op: String, priority: Int): Int {
        requireInit()
        val out = IntArray(1)
        check(NativeBridge.instanceAdd(kind, op, priority, out))
        return out[0]
    }

    override suspend fun instanceNew(op: String, priority: Int): Int = onEngine { add(0, op, priority) }
    override suspend fun instanceDuplicate(op: String, priority: Int): Int = onEngine { add(1, op, priority) }
    override suspend fun instanceDelete(op: String, priority: Int) = onEngine { requireInit(); check(NativeBridge.instanceDelete(op, priority)) }
    override suspend fun instanceMove(op: String, priority: Int, direction: Int) = onEngine {
        requireInit(); check(NativeBridge.instanceMove(op, priority, direction))
    }
    override suspend fun instanceRename(op: String, priority: Int, name: String) = onEngine {
        requireInit(); check(NativeBridge.instanceRename(op, priority, name))
    }

    // ------------------------------------------------------------------ 2.6 history

    override suspend fun history(): History = onEngine { History.fromJson(get(NativeBridge.JSON_HISTORY)) }

    private fun hist(what: Int, arg: Int = 0) { requireInit(); check(NativeBridge.historyOp(what, arg)) }

    override suspend fun historyGoto(end: Int) = onEngine { hist(NativeBridge.HISTORY_GOTO, end) }
    override suspend fun historyCompress() = onEngine { hist(NativeBridge.HISTORY_COMPRESS) }
    override suspend fun historySeal() = onEngine { hist(NativeBridge.HISTORY_SEAL) }
    override suspend fun undo() = onEngine { hist(NativeBridge.HISTORY_UNDO) }
    override suspend fun redo() = onEngine { hist(NativeBridge.HISTORY_REDO) }
    override suspend fun undoState(): UndoState = onEngine { UndoState.fromJson(get(NativeBridge.JSON_UNDO_STATE)) }

    // ------------------------------------------------------------------ 2.7 rendering

    override suspend fun renderSize(scale: Double): RenderSize = onEngine {
        requireInit()
        val wh = IntArray(2)
        check(NativeBridge.renderSize(scale.toFloat(), wh))
        RenderSize(wh[0], wh[1])
    }

    override suspend fun renderPreview(maxSize: Int): RenderedImage = onEngine {
        requireInit()
        val wh = IntArray(2)
        val st = IntArray(1)
        previewCalls.incrementAndGet()
        val px = render { NativeBridge.renderPreview(maxSize, wh, st) }
        check(st[0])
        RenderedImage(wh[0], wh[1], px ?: throw EngineException(EngineError.ENGINE, "no pixels"))
    }

    override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double): RenderedImage = onEngine {
        requireInit()
        if (w <= 0 || h <= 0 || x < 0 || y < 0) throw EngineException(EngineError.ARG, "bad region $x,$y ${w}x$h")
        val buf = try {
            ByteArray(Math.multiplyExact(Math.multiplyExact(w, h), 4))
        } catch (e: ArithmeticException) {
            throw EngineException(EngineError.ARG, "region too large: ${w}x$h")
        } catch (e: OutOfMemoryError) {
            throw EngineException(EngineError.ENGINE, "out of memory for a ${w}x$h region")
        }
        val copied = BooleanArray(1)
        regionCalls.incrementAndGet()
        check(render { NativeBridge.renderRegionInto(x, y, w, h, scale.toFloat(), buf, copied) })
        lastRegionCopied = copied[0]
        RenderedImage(w, h, buf)
    }

    /** Brief P2i, for tests: how many full-pipe renders (`render_region`) and preview renders were asked. */
    val regionCalls = AtomicLong()
    val previewCalls = AtomicLong()

    /** Counts calls, for tests: how many cancels were sent. */
    val cancels = AtomicLong()

    override fun renderCancel() {
        if (!initialised) return
        cancels.incrementAndGet()
        // dtbridge.h: "dtb_render_cancel() may be called from any thread while a render runs on the engine
        // thread", and never concurrently with dtb_close(): only then is it passed on
        synchronized(cancelGuard) { if (initialised && rendering) NativeBridge.renderCancel() else cancelsWithoutRender.incrementAndGet() }
    }

    override suspend fun cacheDrop() = onEngine { requireInit(); check(synchronized(cancelGuard) { NativeBridge.cacheDrop() }) }

    // ------------------------------------------------------------------ 2.8 output

    override suspend fun xmpWrite(path: String) = onEngine { requireInit(); check(NativeBridge.xmpWrite(path)) }

    override suspend fun export(path: String, options: ExportOptions) = onEngine {
        requireInit()
        check(NativeBridge.exportWith(path, options.toJson().toString()))
    }

    // ------------------------------------------------------------------ 5 (P1b part 1)

    private fun p1b(what: Int, s1: String? = null, s2: String? = null, s3: String? = null, n1: Int = 0, n2: Int = 0): JsonElement {
        requireInit()
        val st = IntArray(1)
        return json(NativeBridge.p1b(what, s1, s2, s3, n1, n2, st), st)
    }

    private fun jsonArrayOf(items: List<Any>): String =
        kotlinx.serialization.json.JsonArray(items.map { if (it is Int) JsonPrimitive(it) else JsonPrimitive(it.toString()) }).toString()

    override suspend fun presets(op: String, priority: Int): List<Preset> = onEngine {
        Preset.listFromJson(p1b(NativeBridge.PRESETS_LIST, op, n1 = priority))
    }

    override suspend fun presetApply(op: String, priority: Int, name: String) = onEngine {
        p1b(NativeBridge.PRESET_APPLY, op, name, n1 = priority); Unit
    }

    override suspend fun presetStore(op: String, priority: Int, name: String, description: String, overwrite: Boolean) = onEngine {
        p1b(NativeBridge.PRESET_STORE, op, name, description, n1 = priority, n2 = if (overwrite) 1 else 0); Unit
    }

    override suspend fun presetRename(op: String, name: String, newName: String) = onEngine {
        p1b(NativeBridge.PRESET_RENAME, op, name, newName); Unit
    }

    override suspend fun presetDelete(op: String, name: String) = onEngine {
        p1b(NativeBridge.PRESET_DELETE, op, name); Unit
    }

    override suspend fun presetImport(path: String, overwrite: Boolean): PresetRef = onEngine {
        PresetRef.fromJson(p1b(NativeBridge.PRESET_IMPORT, path, n2 = if (overwrite) 1 else 0))
    }

    override suspend fun presetExport(op: String, name: String, dir: String): String = onEngine {
        val o = p1b(NativeBridge.PRESET_EXPORT, op, name, dir) as? JsonObject
        (o?.get("path") as? JsonPrimitive)?.content ?: throw EngineException(EngineError.ENGINE, "no path from preset export")
    }

    override suspend fun styles(): List<Style> = onEngine { Style.listFromJson(p1b(NativeBridge.STYLES_LIST)) }

    override suspend fun styleApply(name: String, mode: StyleMode?) = onEngine {
        p1b(NativeBridge.STYLE_APPLY, name, mode?.json); Unit
    }

    override suspend fun stylesImport(paths: List<String>, overwrite: Boolean): StyleImport = onEngine {
        StyleImport.fromJson(p1b(NativeBridge.STYLES_IMPORT, jsonArrayOf(paths), n2 = if (overwrite) 1 else 0))
    }

    override suspend fun styleDelete(name: String) = onEngine { p1b(NativeBridge.STYLE_DELETE, name); Unit }

    override suspend fun styleCreate(name: String, description: String, items: List<Int>?, overwrite: Boolean) = onEngine {
        p1b(NativeBridge.STYLE_CREATE, name, description, items?.let { jsonArrayOf(it) }, n2 = if (overwrite) 1 else 0); Unit
    }

    override suspend fun renderTimes(): RenderTimes = onEngine { RenderTimes.fromJson(p1b(NativeBridge.RENDER_TIMES)) }
}

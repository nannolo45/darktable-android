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

    private class Task<T>(val block: () -> T, val cont: CancellableContinuation<T>) : Runnable {
        val cancelled = AtomicBoolean(false)
        override fun run() {
            if (cancelled.get()) return
            val r = runCatching(block)
            r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
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
        if (initialised) NativeBridge.shutdown()
        initialised = false
    }

    override suspend fun engineInfo(): EngineInfo = onEngine { EngineInfo.fromJson(get(NativeBridge.JSON_ENGINE_INFO)) }

    /** The bridge's engine_info as text, with the fields the Kotlin type leaves out (thread_cpus, threads_max). */
    suspend fun engineInfoJson(): JsonObject = onEngine { get(NativeBridge.JSON_ENGINE_INFO) as JsonObject }

    override suspend fun setThreads(n: Int) = onEngine { requireInit(); check(NativeBridge.setThreadsCode(n)) }

    override suspend fun setThreadCpus(cpuIds: List<Int>) = onEngine { requireInit(); check(NativeBridge.setThreadCpus(cpuIds.toIntArray())) }

    // ------------------------------------------------------------------ 2.2 guard

    override suspend fun xmpCheck(xmpPath: String): XmpCheck = onEngine {
        requireInit()
        val st = IntArray(1)
        XmpCheck.fromJson(json(NativeBridge.xmpCheckStatus(xmpPath, st), st))
    }

    // ------------------------------------------------------------------ 2.3 session

    override suspend fun open(rawPath: String, xmpPath: String?) = onEngine { requireInit(); check(NativeBridge.open(rawPath, xmpPath)) }

    override suspend fun close() = onEngine { requireInit(); check(NativeBridge.close()) }

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
        val px = NativeBridge.renderPreview(maxSize, wh, st)
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
        check(NativeBridge.renderRegionInto(x, y, w, h, scale.toFloat(), buf, copied))
        lastRegionCopied = copied[0]
        RenderedImage(w, h, buf)
    }

    /** Counts calls, for tests: how many cancels were sent. */
    val cancels = AtomicLong()

    override fun renderCancel() {
        if (!initialised) return
        cancels.incrementAndGet()
        NativeBridge.renderCancel()
    }

    override suspend fun cacheDrop() = onEngine { requireInit(); check(NativeBridge.cacheDrop()) }

    // ------------------------------------------------------------------ 2.8 output

    override suspend fun xmpWrite(path: String) = onEngine { requireInit(); check(NativeBridge.xmpWrite(path)) }

    override suspend fun export(path: String, options: ExportOptions) = onEngine {
        requireInit()
        check(NativeBridge.exportWith(path, options.toJson().toString()))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The darktable engine as the app sees it: docs/design/engine-api.md sections
 * 1 and 2 (core, work package P1a), function for function.
 *
 * - One engine, at most one open image (a session).
 * - Every function except [renderCancel] runs on the engine's own thread;
 *   implementations switch to it themselves (single-thread dispatcher), so
 *   callers may call from any coroutine.
 * - A module instance is addressed by `op` and `priority` (darktable's
 *   multi_priority, 0 for the first instance). Priorities can change after
 *   [instanceDelete]; callers re-read [modules].
 * - Every failure throws [EngineException] with the specification's code.
 *
 * Implementations: `FakeEngine` (pure Kotlin, this module) and, later, the
 * JNI implementation over engine/include/dtbridge.h.
 */
interface DarktableEngine {
    // 2.1 lifecycle

    /** Start the engine without its desktop interface. [threads] 0 = engine default. */
    suspend fun init(paths: EnginePaths, threads: Int = 0)
    suspend fun shutdown()
    suspend fun engineInfo(): EngineInfo

    /** Limit the worker thread count (never above the count at [init]). */
    suspend fun setThreads(n: Int)

    /** Pin the engine's worker threads to these CPUs. */
    suspend fun setThreadCpus(cpuIds: List<Int>)

    // 2.2 version guard

    /** Check an XMP's history against the engine's modules. Never loads the image. */
    suspend fun xmpCheck(xmpPath: String): XmpCheck

    // 2.3 session

    /**
     * Import and load. The guard runs first; if it fails the image opens
     * read-only and every mutating call throws [EngineError.READ_ONLY].
     */
    suspend fun open(rawPath: String, xmpPath: String? = null)

    /** Close the session. Writes nothing. */
    suspend fun close()
    suspend fun imageInfo(): ImageInfo

    /** Re-read rating, colour labels and tags from an XMP without touching the history. */
    suspend fun metadataReload(xmpPath: String)

    // 2.4 modules and parameters

    /** Every module instance in pipeline order. */
    suspend fun modules(): List<ModuleInstance>

    /**
     * Set one parameter. [path] is a field name with optional steps
     * (`exposure`, `curve[2].x`, `equalizer_y[0][3]`); [value] a number, a
     * bool or an enum constant name. Throws TYPE or RANGE. Switches the module on.
     * Consecutive calls on one instance merge into one history item until [historySeal].
     */
    suspend fun paramSet(op: String, priority: Int, path: String, value: JsonPrimitive)

    /** Several fields as one history step. */
    suspend fun paramsSet(op: String, priority: Int, values: Map<String, JsonPrimitive>)

    /** Throws ARG for modules whose `can_switch` is false. */
    suspend fun setEnabled(op: String, priority: Int, enabled: Boolean)

    /** Back to the module's defaults for this image. */
    suspend fun moduleReset(op: String, priority: Int)

    /** Escape hatch for what a module's desktop panel computes itself. Unknown action: NOT_FOUND. */
    suspend fun moduleAction(op: String, priority: Int, action: String, args: JsonObject = JsonObject(emptyMap())): JsonElement

    // 2.5 instances

    /** New instance with default parameters; returns its priority. */
    suspend fun instanceNew(op: String, priority: Int): Int

    /** Copy of the instance; returns the new priority. */
    suspend fun instanceDuplicate(op: String, priority: Int): Int
    suspend fun instanceDelete(op: String, priority: Int)

    /** [direction] +1 moves up the pipe (later in processing), -1 down. */
    suspend fun instanceMove(op: String, priority: Int, direction: Int)
    suspend fun instanceRename(op: String, priority: Int, name: String)

    // 2.6 history

    suspend fun history(): History

    /** State after the first [end] items; a following change discards the items above. */
    suspend fun historyGoto(end: Int)
    suspend fun historyCompress()
    suspend fun historySeal()
    suspend fun undo()
    suspend fun redo()
    suspend fun undoState(): UndoState

    // 2.7 rendering (RGBA8, alpha 255, sRGB)

    suspend fun renderSize(scale: Double): RenderSize

    /** Whole image from the downscaled input; the longer side is at most [maxSize]. */
    suspend fun renderPreview(maxSize: Int): RenderedImage

    /** Exactly the requested region of the processed image at [scale]. */
    suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double): RenderedImage

    /**
     * From any thread, not suspending. The running render throws
     * [EngineError.CANCELLED]; cancel takes effect between modules.
     */
    fun renderCancel()

    /** Drop the pipeline caches (for measurements). */
    suspend fun cacheDrop()

    // 2.8 output

    /** Refused for a read-only session and when an existing file at [path] fails the guard. Atomic. */
    suspend fun xmpWrite(path: String)
    suspend fun export(path: String, options: ExportOptions)

    // 5 (P1b part 1): presets, styles, render times. docs/design/engine-api.md section 5.

    /** Every stored preset of [op], desktop's menu order; fits/active refer to instance ([op], [priority]) of the open image. */
    suspend fun presets(op: String, priority: Int = 0): List<Preset>

    /** Apply a preset to an instance: one history item. */
    suspend fun presetApply(op: String, priority: Int, name: String)

    /** Store the instance's state as a user preset. An existing user preset of that name: ARG unless [overwrite]. */
    suspend fun presetStore(op: String, priority: Int, name: String, description: String = "", overwrite: Boolean = false)

    /** User presets only (ARG for built-in ones). */
    suspend fun presetRename(op: String, name: String, newName: String)
    suspend fun presetDelete(op: String, name: String)

    /** Import one .dtpreset file. Refused (ARG) for an unknown module or a newer version, a built-in name, or an existing name unless [overwrite]. */
    suspend fun presetImport(path: String, overwrite: Boolean = false): PresetRef

    /** Write <dir>/<name>.dtpreset; returns its path. */
    suspend fun presetExport(op: String, name: String, dir: String): String

    suspend fun styles(): List<Style>

    /** Apply a style to the open image ([mode] null = desktop's preference). A style with an item the engine cannot use: ARG, nothing changes. */
    suspend fun styleApply(name: String, mode: StyleMode? = null)

    /** Import .dtstyle files, one or several. */
    suspend fun stylesImport(paths: List<String>, overwrite: Boolean = false): StyleImport
    suspend fun styleDelete(name: String)

    /** Create a style from the open image's history: [items] null = every item below the end, else history nums. */
    suspend fun styleCreate(name: String, description: String = "", items: List<Int>? = null, overwrite: Boolean = false)

    /** Per-module times of the last render. */
    suspend fun renderTimes(): RenderTimes

    // 6 (P1b part 2): docs/design/engine-api.md section 6.

    /** [renderSize] with [options] for this call only (6.1): with a bypass, the size of the picture without the instance. */
    suspend fun renderSize(scale: Double, options: RenderOptions): RenderSize

    /** [renderPreview] with [options] for this call only (6.1). */
    suspend fun renderPreview(maxSize: Int, options: RenderOptions): RenderedImage

    /** [renderRegion] with [options] for this call only (6.1); the region is in the picture the options give. */
    suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double, options: RenderOptions): RenderedImage
}

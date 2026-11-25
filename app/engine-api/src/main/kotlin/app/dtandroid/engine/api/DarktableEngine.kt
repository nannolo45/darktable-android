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

    /** Brief P4a (engine-api.md 10): what the engine holds in memory. An engine without the call reports nothing. */
    suspend fun memoryInfo(): EngineMemory = EngineMemory.NONE

    /**
     * Brief P4a: give memory back. [level] 1: cached intermediate buffers and image cache entries not in use; 2: also the
     * pipelines and the open image's decoded input (the next render reads the RAW again). The open edit (history, undo)
     * is kept at both levels. Returns the memory afterwards.
     */
    suspend fun memoryTrim(level: Int): EngineMemory = EngineMemory.NONE

    // 2.8 output

    /** Refused for a read-only session and when an existing file at [path] fails the guard. Atomic. */
    suspend fun xmpWrite(path: String)
    suspend fun export(path: String, options: ExportOptions)

    /**
     * P3g (engine-api.md section 7): [export] with progress. [onProgress] is called on the engine thread, in
     * order: PREPARING, PROCESSING once per module of the export pipe, WRITING, DONE (only on success); it must
     * return quickly and call nothing of the engine. A cancelled export ([exportCancel]) throws
     * [EngineError.CANCELLED] and leaves no file at [path]. This default reports only the start and the end.
     */
    suspend fun export(path: String, options: ExportOptions, onProgress: (ExportProgress) -> Unit) {
        onProgress(ExportProgress(ExportStep.PREPARING, 0, 0, 0.0))
        export(path, options)
        onProgress(ExportProgress(ExportStep.DONE, 0, 0, 1.0))
    }

    /** P3g: from any thread, not suspending. The running export throws [EngineError.CANCELLED]; no export running: no effect. */
    fun exportCancel() {}

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

    // R11 (brief P3l, PLAN.md 4.5): styles and presets shared through LightPhotoLibrary. Bodies as 4.5 defines them.

    /**
     * Every shared item of the engine's database (user presets of processing modules, every style):
     * `[{kind, operation, op_version, name, hash, body}]`. The fake engine leaves `hash` out (the app computes it).
     */
    suspend fun sharedSnapshot(): kotlinx.serialization.json.JsonArray

    /** Store one preset or style body; false = an operation, module or blend version this engine cannot use (nothing written). */
    suspend fun sharedApply(body: JsonObject): Boolean

    /** Remove a user preset (name + operation + version) or a style (name). NOT_FOUND if there is none. */
    suspend fun sharedDelete(kind: String, operation: String, opVersion: Int, name: String)

    // 9 (P3j): copy and paste of the history, docs/design/engine-api.md section 9.

    /** The open photo's items as desktop's "selective copy" dialog lists them (one per module instance). */
    suspend fun historyCopyItems(): CopyItems

    /** Copy the open photo's history to the engine's clipboard: [selection] null = desktop's "copy" (the whole history without the modules unsafe to copy), else "selective copy". READ_ONLY in a read-only session. */
    suspend fun historyCopy(selection: HistorySelection? = null)

    /** What was copied; null = nothing yet. The clipboard outlives the photo's session. */
    suspend fun historyClipboard(): Clipboard?

    /** Paste onto the open photo, one undo step. [mode] null = desktop's preference (append). [selection]: a selective paste (its choice is kept, as on desktop). STATE: nothing copied. */
    suspend fun historyPaste(mode: PasteMode? = null, selection: HistorySelection? = null)

    /** Paste onto photos that are not open (no photo may be open: STATE); each XMP written. A target whose XMP fails the version guard is left alone (READ_ONLY in its result). */
    suspend fun historyPasteFiles(targets: List<PasteTarget>, mode: PasteMode? = null, selection: HistorySelection? = null): List<PasteResult>

    /** Per-module times of the last render. */
    suspend fun renderTimes(): RenderTimes

    // 6 (P1b part 2): docs/design/engine-api.md section 6.

    /** [renderSize] with [options] for this call only (6.1): with a bypass, the size of the picture without the instance. */
    suspend fun renderSize(scale: Double, options: RenderOptions): RenderSize

    /** [renderPreview] with [options] for this call only (6.1). */
    suspend fun renderPreview(maxSize: Int, options: RenderOptions): RenderedImage

    /** [renderRegion] with [options] for this call only (6.1); the region is in the picture the options give. */
    suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double, options: RenderOptions): RenderedImage

    /**
     * 6.3: desktop's module picker on ([op], [priority]): statistics of the area on the module's input and output;
     * [op] null = the picture the pipe puts out (desktop's global picker). ARG for a switched-off module or a bad area.
     */
    suspend fun colorPick(op: String?, priority: Int, request: PickRequest): PickResult

    /** 6.4: desktop's RGB histogram of the preview pipe's picture, at most [maxSize] on the longer side. */
    suspend fun histogram(maxSize: Int = 1024, options: RenderOptions = RenderOptions.NONE): Histogram

    /** 6.5: normalised points between the original image and the picture, through every module that moves pixels. */
    suspend fun coordsTransform(direction: CoordsDirection, points: List<Pair<Double, Double>>, options: RenderOptions = RenderOptions.NONE): CoordsResult

    /** 6.6: the blend settings of an instance by name. */
    suspend fun blendGet(op: String, priority: Int): Blend

    /**
     * 6.6: set blend settings by path (`opacity`, `masks`, `colorspace`, `mode`, `combine.invert`,
     * `parametric.<channel>.range`, ...), checked as desktop's panel constrains them; nothing changes if one is
     * refused. Merges with following blendSet / paramSet calls on the instance until [historySeal].
     */
    suspend fun blendSet(op: String, priority: Int, values: Map<String, JsonElement>)

    /** 6.7: every form of the session. */
    suspend fun masks(): List<MaskForm>

    /** 6.7: a new form (circle, ellipse, gradient, path, brush) used by no instance yet; returns its id. */
    suspend fun maskCreate(type: String, geometry: JsonObject, name: String? = null): Int

    suspend fun maskUpdate(id: Int, geometry: JsonObject)
    suspend fun maskRename(id: Int, name: String)

    /** Removes the form from every group; ARG for a form that is an instance's whole mask. */
    suspend fun maskDelete(id: Int)

    /** Add a form to an instance's mask (its flexi group, created if needed; the mask switched on). */
    suspend fun maskAttach(op: String, priority: Int, id: Int, member: MaskMember = MaskMember())
    suspend fun maskDetach(op: String, priority: Int, id: Int)
    suspend fun maskMemberSet(op: String, priority: Int, id: Int, member: MaskMember)

    /** A flexi group of existing forms; returns its id. */
    suspend fun maskGroupCreate(memberIds: List<Int>, name: String? = null): Int

    /** A flexi group's settings: operator, invert, bypass, opacity, name (engine-api.md 6.7). */
    suspend fun maskGroupSet(id: Int, settings: JsonObject)

    // ---- 6.9 (P3d stage 2a): history groups, one-step shapes, tree editing, parametric elements

    /** Start a history group: changes until [historyGroupEnd] are one undo step, one item per instance. */
    suspend fun historyGroupBegin()

    /** End the open group: keep its changes, or (keep = false) go back to where it started (cancel). */
    suspend fun historyGroupEnd(keep: Boolean)

    /**
     * A new element in the instance's mask as one history item: [type] circle, ellipse, gradient, path, brush or
     * parametric; [group] a flexi group in the instance's tree, null = its own group. Returns the form id.
     */
    suspend fun maskAdd(op: String, priority: Int, type: String, geometry: JsonObject, name: String? = null,
                        group: Int? = null, member: MaskMember = MaskMember()): Int

    /** Move member [id] of [group] to [position] (0 = bottom, -1 = top) of [toGroup]. */
    suspend fun maskMemberMove(group: Int, id: Int, toGroup: Int, position: Int)

    /** Remove member [id] from any flexi [group] (the form stays). */
    suspend fun maskMemberRemove(group: Int, id: Int)

    /** A member's inverse, opacity, disabled in any flexi group (a group member: disabled only). */
    suspend fun maskMemberUpdate(group: Int, id: Int, member: MaskMember)

    /** Wrap in group (one id) / group together (several): a new flexi group in the place of the topmost; its id. */
    suspend fun maskGroupWrap(group: Int, ids: List<Int>, operator: String? = null): Int

    // ---- 6.10 (P3m): retouch's own shapes

    /** The retouch instance's shapes (every wavelet scale) and the module's settings. ARG for another module. */
    suspend fun retouch(op: String, priority: Int): RetouchState

    /**
     * A new retouch shape as one history item ([type] circle, ellipse, path, brush; [geometry] as [maskAdd];
     * [source] original-image fractions, needed for clone and heal; [algorithm] becomes the module's first). It takes
     * the module's algorithm, blur or fill settings and current scale; the module is switched on. Returns its id.
     */
    suspend fun retouchAdd(op: String, priority: Int, type: String, geometry: JsonObject, source: Pair<Double, Double>?,
                           algorithm: String? = null): Int

    /**
     * Change a shape: "geometry", "source", "opacity" (one history item), "algorithm" (clone <-> heal, blur <-> fill
     * only), "blur_type", "blur_radius", "fill_mode", "fill_color", "fill_brightness" (merge as [paramSet]).
     */
    suspend fun retouchShapeSet(op: String, priority: Int, id: Int, changes: JsonObject)

    /** Delete a shape for good (one history item). */
    suspend fun retouchShapeRemove(op: String, priority: Int, id: Int)

    /** Desktop's selection: the module's algorithm and blur or fill settings take the shape's. */
    suspend fun retouchSelect(op: String, priority: Int, id: Int)

    // ---- 6.11 (P3n): rotate and perspective's structure, guides and fit

    /**
     * Desktop's structure and fit buttons and its mouse on the photo for a rotate and perspective instance, run by
     * darktable's own code; [request] holds "action" (begin, end, state, structure, fit, pointer, changed) and its
     * fields (engine-api.md 6.11). Changes are one history item per request. ARG for another module.
     */
    suspend fun ashift(op: String, priority: Int, request: JsonObject): PerspectiveState
}

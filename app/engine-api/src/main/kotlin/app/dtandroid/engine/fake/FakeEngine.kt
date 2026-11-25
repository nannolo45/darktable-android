// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.fake

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineMemory
import app.dtandroid.engine.api.RetouchShape
import app.dtandroid.engine.api.RetouchState
import app.dtandroid.engine.api.InstanceRef
import app.dtandroid.engine.api.RetouchFocus
import app.dtandroid.engine.api.PerspectiveState
import app.dtandroid.engine.api.GuideLine
import app.dtandroid.engine.api.GuideHandle
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EngineInfo
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.Exif
import app.dtandroid.engine.api.ExportFormat
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.ExportProgress
import app.dtandroid.engine.api.ExportStep
import app.dtandroid.engine.api.History
import app.dtandroid.engine.api.HistoryItem
import app.dtandroid.engine.api.ImageClass
import app.dtandroid.engine.api.ImageInfo
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.ParamPath
import app.dtandroid.engine.api.RenderOptions
import app.dtandroid.engine.api.PickRequest
import app.dtandroid.engine.api.MaskForm
import app.dtandroid.engine.api.MaskMember
import app.dtandroid.engine.api.MaskUser
import app.dtandroid.engine.api.Blend
import app.dtandroid.engine.api.PickResult
import app.dtandroid.engine.api.PickStats
import app.dtandroid.engine.api.Histogram
import app.dtandroid.engine.api.CoordsDirection
import app.dtandroid.engine.api.CoordsResult
import app.dtandroid.engine.api.paramAt
import app.dtandroid.engine.api.RenderSize
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.api.UndoState
import app.dtandroid.engine.api.ItemProblem
import app.dtandroid.engine.api.ModuleTime
import app.dtandroid.engine.api.Preset
import app.dtandroid.engine.api.PresetMatch
import app.dtandroid.engine.api.PresetRef
import app.dtandroid.engine.api.RenderTimes
import app.dtandroid.engine.api.Style
import app.dtandroid.engine.api.StyleImport
import app.dtandroid.engine.api.StyleItem
import app.dtandroid.engine.api.StyleMode
import app.dtandroid.engine.api.Clipboard
import app.dtandroid.engine.api.CopyItem
import app.dtandroid.engine.api.CopyItems
import app.dtandroid.engine.api.HistorySelection
import app.dtandroid.engine.api.PasteMode
import app.dtandroid.engine.api.PasteResult
import app.dtandroid.engine.api.PasteTarget
import app.dtandroid.engine.api.parseEngineJson
import app.dtandroid.engine.api.XmpCheck
import app.dtandroid.engine.api.XmpProblem
import app.dtandroid.engine.api.XmpProblemReason
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure-Kotlin stand-in for the darktable engine (docs/design/engine-api.md
 * sections 1 and 2), for interface work and tests without the native core.
 *
 * Modules, parameters, defaults, versions and flags come from the control
 * schema ([FakeModuleCatalog]). Instances, history (grouping and sealing,
 * go-to, compress, discarding steps above the current one), undo and redo,
 * `can_switch`, the version guard with read-only sessions and the error codes
 * follow the specification. The picture is generated ([FakeRenderer]); render
 * time is simulated ([Timing]) and cancel works between modules.
 *
 * What the fake does not do: read the RAW file (the scene is chosen from the
 * file name), read darktable's own XMP parameters (only its own sidecars, see
 * [FakeXmp]), apply pipeline rules when moving instances, or masks and blending.
 */
class FakeEngine(
    private val catalog: FakeModuleCatalog,
    private val timing: Timing = Timing.REALISTIC,
    private val samples: Map<String, SampleInfo> = emptyMap(),
) : DarktableEngine {

    /** Simulated render cost. */
    data class Timing(val previewFixedMs: Long, val regionFixedMs: Long, val msPerMegapixel: Long) {
        companion object {
            /** close to what gate G0 measured on the target phone with 4 threads */
            val REALISTIC = Timing(previewFixedMs = 60, regionFixedMs = 150, msPerMegapixel = 60)
            val NONE = Timing(0, 0, 0)
        }
    }

    /** Exif and starting edit of a sample, looked up by the RAW file's name. */
    data class SampleInfo(
        val exif: Exif,
        val rating: Int = 0,
        val colorLabels: List<Int> = emptyList(),
        /** parameter changes applied on top of the default edit when no XMP is given: op to (path to value) */
        val startEdit: List<Pair<String, Map<String, JsonPrimitive>>> = emptyList(),
    )

    companion object {
        const val API_VERSION = 1

        /** modules darktable switches on for a new raw in the scene-referred workflow, as the fake's default edit */
        val DEFAULT_EDIT = listOf(
            "rawprepare", "temperature", "highlights", "demosaic", "flip",
            "exposure", "colorin", "channelmixerrgb", "sigmoid", "colorout",
        )

        private const val UNDO_LIMIT = 100
        /** P3m: the stand-in has no shape group of its own; this id stands for it */
        private const val RT_GROUP = 999

        /** P3j: darktable's modules flagged IOP_FLAGS_UNSAFE_COPY (the iop sources at the pinned commit) */
        val FAKE_UNSAFE_COPY = setOf("flip", "gamma", "mask_manager", "rotatepixels", "scalepixels", "temperature", "rawprepare")

        /** the blend parameter version the fake claims (darktable's at the pinned commit) */
        const val FAKE_BLEND_VERSION = 15

        fun fromSchemaJson(text: String, timing: Timing = Timing.REALISTIC, samples: Map<String, SampleInfo> = emptyMap()) =
            FakeEngine(FakeModuleCatalog.fromSchemaJson(text), timing, samples)

        private val DEFAULT_EXIF = Exif(
            maker = "Generic", model = "Camera", lens = "23 mm f/1.4", iso = 400.0, aperture = 2.8,
            exposure = 1.0 / 250.0, focalLength = 23.0, datetime = "2026-06-01T18:30:00",
        )
    }

    // ------------------------------------------------------------ threading

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-engine").apply { isDaemon = true } }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    private suspend fun <T> onEngine(block: suspend () -> T): T = withContext(dispatcher) { block() }

    // ------------------------------------------------------------ state

    private data class Inst(val op: String, val priority: Int, val instanceName: String)

    /** [unknown]: an XMP entry whose module the engine does not know (read-only sessions only, P1c) */
    private data class Item(
        val op: String, val priority: Int, val instanceName: String, val enabled: Boolean, val params: JsonObject,
        val unknown: Boolean = false,
        /** P1b part 2: the blend settings (engine-api.md 6.6 JSON), null = the module's defaults */
        val blend: JsonObject? = null,
    )

    /** P1b part 2: a drawn form, kept as data (the stand-in does not render masks) */
    private data class FakeForm(val id: Int, val type: String, val name: String, val geometry: JsonObject,
                                /** P3m: one of retouch's own shapes, and its opacity in retouch's shape group */
                                val retouch: Boolean = false, val opacity: Double = 1.0)

    private data class Snapshot(val insts: List<Inst>, val items: List<Item>, val end: Int, val forms: List<FakeForm> = emptyList())

    private class Session(
        val rawPath: String,
        val readOnly: Boolean,
        val variant: Int,
        val sample: SampleInfo?,
        var insts: List<Inst>,
        var items: List<Item>,
        var end: Int,
        var rating: Int,
        var colorLabels: List<Int>,
    ) {
        val undo = ArrayDeque<Snapshot>()
        val redo = ArrayDeque<Snapshot>()
        /** P1b part 2: the drawn forms (engine-api.md 6.7) */
        var forms: List<FakeForm> = emptyList()
        var nextFormId = 1000
        /** instance whose param_set calls currently merge into the last history item */
        var openGroup: Pair<String, Int>? = null
        /** 6.9 history group: open; index of the first item made inside it (-1: no change yet) */
        var histGroup = false
        var histGroupFirst = -1
    }

    private var initialised = false
    private var initThreads = 0
    private var threads = 0
    private var session: Session? = null
    private val runningRender = AtomicReference<AtomicBoolean?>(null)

    private fun fail(e: EngineError, msg: String): Nothing = throw EngineException(e, msg)

    private fun requireInit() { if (!initialised) fail(EngineError.STATE, "engine not initialised") }

    private fun requireSession(): Session {
        requireInit()
        return session ?: fail(EngineError.STATE, "no image open")
    }

    private fun requireWritable(): Session {
        val s = requireSession()
        if (s.readOnly) fail(EngineError.READ_ONLY, "the image is open read-only (its XMP failed the version guard)")
        return s
    }

    private fun def(op: String) = catalog[op] ?: fail(EngineError.NOT_FOUND, "unknown module \"$op\"")

    private fun Session.inst(op: String, priority: Int): Inst =
        insts.firstOrNull { it.op == op && it.priority == priority }
            ?: fail(EngineError.NOT_FOUND, "no instance $op/$priority")

    /** enabled and params of every instance after the first [end] history items */
    private data class Live(val enabled: Boolean, val params: JsonObject, val inHistory: Boolean, val blend: JsonObject? = null)

    private fun Session.live(upTo: Int = end): Map<Pair<String, Int>, Live> {
        val m = LinkedHashMap<Pair<String, Int>, Live>()
        for (i in insts) {
            val d = catalog[i.op]!!
            m[i.op to i.priority] = Live(d.defaultEnabled || !d.canSwitch, d.defaultParams, false)
        }
        for (it in items.take(upTo)) {
            val k = it.op to it.priority
            if (k in m) m[k] = Live(it.enabled || !catalog[it.op]!!.canSwitch, it.params, true, it.blend)
        }
        // P2b: as the bridge, "in_history" means the instance has an item anywhere in the history list,
        // also above the current end
        for (it in items) {
            val k = it.op to it.priority
            m[k]?.let { l -> if (!l.inHistory) m[k] = l.copy(inHistory = true) }
        }
        return m
    }

    private fun Session.snapshot() = Snapshot(insts, items, end, forms)

    private fun Session.restore(s: Snapshot) { insts = s.insts; items = s.items; end = s.end; forms = s.forms; openGroup = null }

    /** Start a new history group: seal, record the undo state, discard history above `end`. */
    private fun Session.beginChange(truncate: Boolean = true) {
        openGroup = null
        // 6.9: inside a history group only the first change records the undo state
        if (histGroup) {
            if (histGroupFirst >= 0) return
            histGroupFirst = (if (truncate) minOf(end, items.size) else items.size)
        }
        undo.addLast(snapshot())
        while (undo.size > UNDO_LIMIT) undo.removeFirst()
        redo.clear()
        if (truncate && end < items.size) items = items.take(end)
    }

    private fun Session.addItem(op: String, priority: Int, enabled: Boolean, params: JsonObject,
                                blend: JsonObject? = live()[op to priority]?.blend) {
        val name = inst(op, priority).instanceName
        val last = items.lastOrNull()
        // 6.9: inside a history group the items of one instance merge, as darktable's unsealed item does
        items = if (histGroup && histGroupFirst in 0 until items.size && last != null && last.op == op && last.priority == priority)
            items.dropLast(1) + Item(op, priority, name, enabled, params, blend = blend)
        else items + Item(op, priority, name, enabled, params, blend = blend)
        end = items.size
    }

    // ------------------------------------------------------------ 2.1 lifecycle

    override suspend fun init(paths: EnginePaths, threads: Int) = onEngine {
        if (initialised) fail(EngineError.STATE, "engine already initialised")
        if (threads < 0) fail(EngineError.ARG, "threads must be >= 0")
        initThreads = if (threads == 0) Runtime.getRuntime().availableProcessors() else threads
        this.threads = initThreads
        presetsDb.clear(); presetsDb += builtinPresets(); stylesDb.clear(); lastTimes = null
        initialised = true
    }

    override suspend fun shutdown() = onEngine {
        session = null
        initialised = false
    }

    override suspend fun engineInfo(): EngineInfo = onEngine {
        requireInit()
        EngineInfo.fromJson(
            EngineInfo(
                darktableVersion = "fake engine (control schema of darktable ${catalog.darktableVersion})",
                apiVersion = API_VERSION,
                threads = threads,
                pluginFiles = catalog.modules.size,
                pluginsLoaded = catalog.modules.size,
                pluginsMissing = emptyList(),
            ).toJson()
        )
    }

    override suspend fun setThreads(n: Int) = onEngine {
        requireInit()
        // as dtb_set_threads: n <= 0 restores the count of init, a larger n is clamped to it (P2b)
        threads = if (n <= 0) initThreads else min(n, initThreads)
    }

    override suspend fun setThreadCpus(cpuIds: List<Int>) = onEngine {
        requireInit()
        // as dtb_set_thread_cpus: an empty list removes the pinning; ids the process cannot use are refused (P2b)
        val n = Runtime.getRuntime().availableProcessors()
        if (cpuIds.any { it < 0 || it >= n && cpusAtInit == null }) fail(EngineError.ARG, "bad CPU list $cpuIds")
        // brief P2g: as the bridge, a CPU outside the start-up set is refused with the bridge's words
        cpusAtInit?.let { allowed -> cpuIds.firstOrNull { it !in allowed }?.let { fail(EngineError.ARG, "CPU $it is not available to this process") } }
        threadCpus = cpuIds.sorted()
    }

    /**
     * Brief P2g: the CPUs the stand-in engine "could use at init". When set, [setThreadCpus] refuses any
     * other CPU as dtb_set_thread_cpus does, so tests can start an engine that refuses the pinning.
     */
    @Volatile var cpusAtInit: Set<Int>? = null

    /** The CPUs the stand-in's threads are pinned to (empty = not pinned), as engine_info's thread_cpus. */
    @Volatile var threadCpus: List<Int> = emptyList()
        private set

    /** The thread count now (as engine_info's threads). */
    val threadCount: Int get() = threads

    // ------------------------------------------------------------ 2.2 guard

    private fun check(text: String): XmpCheck {
        val p = FakeXmp.parse(text)
        val problems = p.entries.mapNotNull { e ->
            val d = catalog[e.operation]
            when {
                d == null -> XmpProblem(e.num, e.operation, e.modversion, null, XmpProblemReason.UNKNOWN_OPERATION)
                e.modversion > d.version -> XmpProblem(e.num, e.operation, e.modversion, d.version, XmpProblemReason.NEWER_MODULE_VERSION)
                else -> null
            }
        }
        return XmpCheck(problems.isEmpty(), p.xmpVersion, p.entries.size, problems)
    }

    private fun readXmp(path: String): String {
        val f = File(path)
        if (!f.isFile) fail(EngineError.IO, "cannot read $path")
        return try { f.readText() } catch (e: Exception) { fail(EngineError.IO, "cannot read $path: ${e.message}") }
    }

    override suspend fun xmpCheck(xmpPath: String): XmpCheck = onEngine {
        requireInit()
        XmpCheck.fromJson(check(readXmp(xmpPath)).toJson())
    }

    // ------------------------------------------------------------ 2.3 session

    override suspend fun open(rawPath: String, xmpPath: String?) = onEngine {
        requireInit()
        if (rawPath.isBlank()) fail(EngineError.ARG, "empty RAW path")
        // as dtb_open (P1b, E1): a file without a picture is refused; the fake decodes nothing, so it
        // can only tell an empty file
        File(rawPath).let { if (it.isFile && it.length() == 0L) fail(EngineError.IO, "${EngineException.UNDECODABLE_PREFIX}$rawPath: darktable does not recognise it as a picture (import refused)") }
        val name = File(rawPath).name
        val sample = samples[name]
        val variant = (name.hashCode() and 0x7fffffff) % 6
        val insts = catalog.modules.values.sortedBy { it.pipePosition }.map { Inst(it.op, 0, "") }
        val s = Session(rawPath, false, variant, sample, insts, emptyList(), 0, sample?.rating ?: 0, sample?.colorLabels ?: emptyList())
        var readOnly = false
        // as dtb_open: the given XMP, else the sidecar next to the RAW, else the default edit
        val xmp = xmpPath ?: "$rawPath.xmp".takeIf { File(it).isFile }
        if (xmp != null) {
            val text = readXmp(xmp)
            readOnly = !check(text).ok
            loadXmp(s, FakeXmp.parse(text))
        } else {
            applyDefaultEdit(s)
        }
        session = Session(rawPath, readOnly, variant, sample, s.insts, s.items, s.end, s.rating, s.colorLabels)
        lastTimes = null
    }

    private fun applyDefaultEdit(s: Session) {
        for (op in DEFAULT_EDIT) {
            val d = catalog[op] ?: continue
            if (s.insts.none { it.op == op }) continue
            s.addItem(op, 0, true, d.defaultParams)
        }
        for ((op, values) in s.sample?.startEdit ?: emptyList()) {
            val d = catalog[op] ?: continue
            var p = s.live()[op to 0]?.params ?: d.defaultParams
            for ((path, v) in values) p = setPath(d, p, path, v)
            s.addItem(op, 0, true, p)
        }
    }

    private fun loadXmp(s: Session, x: FakeXmp.Parsed) {
        // instances named in the history, in the saved pipe order where known
        val extra = x.entries.filter { e -> catalog[e.operation] != null && e.priority > 0 }
            .map { Inst(it.operation, it.priority, it.multiName) }.distinct()
        val all = (s.insts + extra).distinctBy { it.op to it.priority }.map { i ->
            val named = x.entries.lastOrNull { it.operation == i.op && it.priority == i.priority }
            if (named != null) i.copy(instanceName = named.multiName) else i
        }
        s.insts = all.sortedWith(compareBy({ x.iopOrder["${it.op}/${it.priority}"] ?: (catalog[it.op]!!.pipePosition * 100 + it.priority) }))
        val items = ArrayList<Item>()
        for (e in x.entries) {
            val d = catalog[e.operation]
            // as the bridge since P1c: an unknown module keeps its place in the list, flagged, without
            // effect (the session is read-only anyway)
            items += if (d == null) Item(e.operation, e.priority, e.multiName, e.enabled, JsonObject(emptyMap()), unknown = true)
            else Item(e.operation, e.priority, e.multiName, e.enabled, e.params ?: d.defaultParams)
        }
        s.items = items
        s.end = (x.historyEnd ?: items.size).coerceIn(0, items.size)
    }

    override suspend fun close() = onEngine {
        requireInit()
        session = null
        fp = null
        rtFocus = null
    }

    override suspend fun imageInfo(): ImageInfo = onEngine {
        val s = requireSession()
        val o = orientation(s)
        val (w, h) = FakeRenderer(s.variant).processedSize(o, 1.0)
        ImageInfo.fromJson(
            ImageInfo(
                width = w, height = h,
                rawWidth = FakeRenderer.BASE_W + 48, rawHeight = FakeRenderer.BASE_H + 32,
                imageClass = ImageClass.BAYER,
                readOnly = s.readOnly,
                exif = s.sample?.exif ?: DEFAULT_EXIF,
                rating = s.rating,
                colorLabels = s.colorLabels,
            ).toJson()
        )
    }

    override suspend fun metadataReload(xmpPath: String) = onEngine {
        val s = requireSession()
        val text = readXmp(xmpPath)
        Regex("""xmp:Rating="(-?\d+)"""").find(text)?.let { s.rating = it.groupValues[1].toInt() }
        val labels = Regex("""<darktable:colorlabels>(.*?)</darktable:colorlabels>""", RegexOption.DOT_MATCHES_ALL).find(text)
        if (labels != null) s.colorLabels = Regex("""<rdf:li>(\d+)</rdf:li>""").findAll(labels.groupValues[1]).map { it.groupValues[1].toInt() }.toList()
    }

    // ------------------------------------------------------------ 2.4 modules and parameters

    override suspend fun modules(): List<ModuleInstance> = onEngine {
        val s = requireSession()
        val live = s.live()
        val arr = buildJsonArray {
            s.insts.forEachIndexed { idx, i ->
                val d = catalog[i.op]!!
                val l = live[i.op to i.priority]!!
                add(ModuleInstance(
                    op = i.op, name = d.name, priority = i.priority, instanceName = i.instanceName,
                    iopOrder = (idx + 1) * 100, enabled = l.enabled, canSwitch = d.canSwitch,
                    defaultEnabled = d.defaultEnabled, hidden = d.hidden, deprecated = d.deprecated,
                    oneInstance = d.oneInstance, supportsBlending = d.supportsBlending, version = d.version,
                    inHistory = l.inHistory, params = l.params,
                ).toJson())
            }
        }
        ModuleInstance.listFromJson(arr)
    }

    /** Type and range check, then the new parameter structure. */
    private fun setPath(d: FakeModuleCatalog.ModuleDef, params: JsonObject, path: String, value: JsonPrimitive): JsonObject {
        val steps = ParamPath.parse(path)
        val field = d.fields[ParamPath.template(path)] ?: fail(EngineError.NOT_FOUND, "${d.op} has no parameter \"$path\"")
        val v: JsonPrimitive = when (field.type) {
            FakeModuleCatalog.FieldType.FLOAT -> {
                if (value.isString || value.booleanOrNull != null) fail(EngineError.TYPE, "$path takes a number")
                val x = value.doubleOrNull ?: fail(EngineError.TYPE, "$path takes a number")
                if (!x.isFinite()) fail(EngineError.RANGE, "$path: $x is not finite")
                if ((field.min != null && x < field.min) || (field.max != null && x > field.max))
                    fail(EngineError.RANGE, "$path: $x outside ${field.min}..${field.max}")
                JsonPrimitive(x)
            }
            FakeModuleCatalog.FieldType.INT, FakeModuleCatalog.FieldType.CHAR -> {
                if (value.isString || value.booleanOrNull != null) fail(EngineError.TYPE, "$path takes an integer")
                val x = value.doubleOrNull ?: fail(EngineError.TYPE, "$path takes an integer")
                if (x != Math.rint(x)) fail(EngineError.TYPE, "$path takes an integer, got $x")
                if ((field.min != null && x < field.min) || (field.max != null && x > field.max))
                    fail(EngineError.RANGE, "$path: $x outside ${field.min}..${field.max}")
                JsonPrimitive(x.toLong())
            }
            FakeModuleCatalog.FieldType.BOOL -> {
                val b = if (value.isString) null else value.booleanOrNull
                JsonPrimitive(b ?: fail(EngineError.TYPE, "$path takes true or false"))
            }
            FakeModuleCatalog.FieldType.ENUM -> {
                if (!value.isString) fail(EngineError.TYPE, "$path takes an enum constant name")
                if (field.enumValue(value.content) == null) fail(EngineError.RANGE, "$path: unknown constant ${value.content}")
                value
            }
        }
        return replaceAt(params, steps, 0, v, path) as JsonObject
    }

    private fun replaceAt(e: JsonElement, steps: List<ParamPath.Step>, i: Int, v: JsonPrimitive, path: String): JsonElement {
        if (i == steps.size) {
            if (e !is JsonPrimitive && e !is JsonNull) fail(EngineError.TYPE, "$path is not a scalar")
            return v
        }
        return when (val s = steps[i]) {
            is ParamPath.Step.Field -> {
                val o = e as? JsonObject ?: fail(EngineError.NOT_FOUND, "no parameter \"$path\"")
                val child = o[s.name] ?: fail(EngineError.NOT_FOUND, "no parameter \"$path\"")
                JsonObject(o + (s.name to replaceAt(child, steps, i + 1, v, path)))
            }
            is ParamPath.Step.Index -> {
                val a = e as? JsonArray ?: fail(EngineError.NOT_FOUND, "no parameter \"$path\"")
                if (s.index !in a.indices) fail(EngineError.NOT_FOUND, "index ${s.index} out of bounds in \"$path\"")
                JsonArray(a.toMutableList().also { it[s.index] = replaceAt(a[s.index], steps, i + 1, v, path) })
            }
        }
    }

    private fun Session.setParams(op: String, priority: Int, values: Map<String, JsonPrimitive>, merge: Boolean) {
        val d = def(op)
        inst(op, priority)
        val cur = live()[op to priority]!!
        var p = cur.params
        for ((path, v) in values) p = setPath(d, p, path, v) // validate all before changing anything
        val key = op to priority
        val canMerge = merge && openGroup == key && end == items.size && items.lastOrNull()?.let { it.op == op && it.priority == priority } == true
        if (canMerge) {
            items = items.dropLast(1) + items.last().copy(enabled = true, params = p)
        } else {
            beginChange()
            addItem(op, priority, true, p)
            if (merge) openGroup = key
        }
    }

    override suspend fun paramSet(op: String, priority: Int, path: String, value: JsonPrimitive) = onEngine {
        requireWritable().setParams(op, priority, mapOf(path to value), merge = true)
    }

    override suspend fun paramsSet(op: String, priority: Int, values: Map<String, JsonPrimitive>) = onEngine {
        if (values.isEmpty()) fail(EngineError.ARG, "no values")
        requireWritable().setParams(op, priority, values, merge = false)
    }

    override suspend fun setEnabled(op: String, priority: Int, enabled: Boolean) = onEngine {
        val s = requireWritable()
        val d = def(op)
        s.inst(op, priority)
        if (!d.canSwitch) fail(EngineError.ARG, "${d.name} cannot be switched off")
        val cur = s.live()[op to priority]!!
        s.beginChange()
        s.addItem(op, priority, enabled, cur.params)
    }

    override suspend fun moduleReset(op: String, priority: Int) = onEngine {
        val s = requireWritable()
        val d = def(op)
        s.inst(op, priority)
        val cur = s.live()[op to priority]!!
        s.beginChange()
        s.addItem(op, priority, cur.enabled, d.defaultParams)
    }

    override suspend fun moduleAction(op: String, priority: Int, action: String, args: JsonObject): JsonElement = onEngine {
        val s = requireSession()
        def(op)
        s.inst(op, priority)
        when (op to action) {
            "temperature" to "wb_from_area", "exposure" to "spot", "rgblevels" to "pick", "rgbcurve" to "set_nodes_from_area",
            "channelmixerrgb" to "illuminant_from_area", "channelmixerrgb" to "spot" ->
                fakePickAction(s, op, priority, action, args)
            "temperature" to "get_wb" -> {
                val p = s.live()[op to priority]!!.params
                val (t, tint) = FakeWhiteBalance.fromCoefficients(p)
                buildJsonObject { put("temperature", t); put("tint", tint); put("tuning", JsonNull); put("preset_index", JsonNull) }
            }
            // P1b (engine-api.md 5.4)
            "colorin" to "list_profiles" -> {
                val p = s.live()[op to priority]!!.params
                val t = (p["type"] as? JsonPrimitive)?.content
                val tw = (p["type_work"] as? JsonPrimitive)?.content
                val inputs = listOf("DT_COLORSPACE_STANDARD_MATRIX" to "standard color matrix", "DT_COLORSPACE_ENHANCED_MATRIX" to "enhanced color matrix",
                    "DT_COLORSPACE_SRGB" to "sRGB", "DT_COLORSPACE_LIN_REC2020" to "linear Rec2020 RGB")
                val works = listOf("DT_COLORSPACE_SRGB" to "sRGB", "DT_COLORSPACE_LIN_REC709" to "linear Rec709 RGB", "DT_COLORSPACE_LIN_REC2020" to "linear Rec2020 RGB")
                buildJsonArray {
                    for ((ty, n) in inputs) add(buildJsonObject { put("kind", "input"); put("type", ty); put("filename", ""); put("name", n); put("current", ty == t) })
                    for ((ty, n) in works) add(buildJsonObject { put("kind", "work"); put("type", ty); put("filename", ""); put("name", n); put("current", ty == tw) })
                }
            }
            "borders" to "set_aspect" -> {
                requireWritable()
                val v = (args["aspect"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail(EngineError.TYPE, "set_aspect takes {aspect: number}")
                val d = def(op)
                val cur = s.live()[op to priority]!!
                val p = if (v == 0.0 || v == -1.0) JsonObject(cur.params + ("aspect" to JsonPrimitive(v))) else setPath(d, cur.params, "aspect", JsonPrimitive(v))
                s.beginChange()
                s.addItem(op, priority, true, p)
                JsonObject(emptyMap())
            }
            "lens" to "use_latest_md" -> {
                requireWritable()
                val cur = s.live()[op to priority]!!
                val p = JsonObject(cur.params + ("md_version" to JsonPrimitive("DT_IOP_LENS_EMBEDDED_METADATA_VERSION_2")) + ("scale_md_v1" to JsonPrimitive(0.0)))
                s.beginChange()
                s.addItem(op, priority, true, p)
                JsonObject(emptyMap())
            }
            "temperature" to "set_wb" -> {
                requireWritable()
                val t = (args["temperature"] as? JsonPrimitive)?.doubleOrNull ?: fail(EngineError.ARG, "set_wb needs temperature")
                val tint = (args["tint"] as? JsonPrimitive)?.doubleOrNull ?: fail(EngineError.ARG, "set_wb needs tint")
                if (t !in FakeWhiteBalance.T_MIN..FakeWhiteBalance.T_MAX) fail(EngineError.RANGE, "temperature $t outside ${FakeWhiteBalance.T_MIN}..${FakeWhiteBalance.T_MAX}")
                if (tint !in FakeWhiteBalance.TINT_MIN..FakeWhiteBalance.TINT_MAX) fail(EngineError.RANGE, "tint $tint outside ${FakeWhiteBalance.TINT_MIN}..${FakeWhiteBalance.TINT_MAX}")
                val c = FakeWhiteBalance.toCoefficients(t, tint)
                // a drag on a derived slider groups like a drag on a bound one
                s.setParams(op, priority, mapOf("red" to JsonPrimitive(c[0]), "green" to JsonPrimitive(c[1]), "blue" to JsonPrimitive(c[2])), merge = true)
                JsonObject(emptyMap())
            }
            "temperature" to "list_presets", "lens" to "list_cameras", "denoiseprofile" to "list_profiles" -> JsonArray(emptyList())
            "lens" to "list_lenses" -> JsonArray(emptyList())
            "temperature" to "apply_preset", "lens" to "set_lens", "denoiseprofile" to "set_profile" -> {
                requireWritable()
                fail(EngineError.NOT_FOUND, "the fake engine has no presets, lenses or profiles")
            }
            "lut3d" to "set_file" -> {
                requireWritable()
                fail(EngineError.IO, "the fake engine cannot read LUT files")
            }
            else -> fail(EngineError.NOT_FOUND, "unknown action $op/$action")
        }
    }

    // ------------------------------------------------------------ 2.5 instances

    private fun Session.addInstance(op: String, priority: Int, params: JsonObject): Int {
        val d = def(op)
        val src = inst(op, priority)
        if (d.oneInstance) fail(EngineError.ARG, "${d.name} allows only one instance")
        val newPriority = insts.filter { it.op == op }.maxOf { it.priority } + 1
        val idx = insts.indexOf(src)
        beginChange()
        // desktop puts the new instance right above its source in the pipe
        insts = insts.toMutableList().also { it.add(idx + 1, Inst(op, newPriority, newPriority.toString())) }
        addItem(op, newPriority, true, params)
        return newPriority
    }

    override suspend fun instanceNew(op: String, priority: Int): Int = onEngine {
        val s = requireWritable()
        s.addInstance(op, priority, def(op).defaultParams)
    }

    override suspend fun instanceDuplicate(op: String, priority: Int): Int = onEngine {
        val s = requireWritable()
        def(op)
        s.inst(op, priority)
        s.addInstance(op, priority, s.live()[op to priority]!!.params)
    }

    override suspend fun instanceDelete(op: String, priority: Int) = onEngine {
        val s = requireWritable()
        def(op)
        val i = s.inst(op, priority)
        if (s.insts.count { it.op == op } <= 1) fail(EngineError.ARG, "the only instance of $op cannot be deleted")
        s.beginChange(truncate = false)
        val removedBelowEnd = s.items.take(s.end).count { it.op == op && it.priority == priority }
        s.items = s.items.filterNot { it.op == op && it.priority == priority }
        s.end -= removedBelowEnd
        s.insts = s.insts - i
        if (priority == 0) {
            // desktop renumbers: the remaining instance with the lowest priority becomes 0
            val lowest = s.insts.filter { it.op == op }.minBy { it.priority }
            s.insts = s.insts.map { if (it == lowest) it.copy(priority = 0) else it }
            s.items = s.items.map { if (it.op == op && it.priority == lowest.priority) it.copy(priority = 0) else it }
        }
    }

    override suspend fun instanceMove(op: String, priority: Int, direction: Int) = onEngine {
        val s = requireWritable()
        def(op)
        val i = s.inst(op, priority)
        if (direction != 1 && direction != -1) fail(EngineError.ARG, "direction must be +1 or -1")
        val idx = s.insts.indexOf(i)
        // move past the next module that is not hidden, as desktop does
        var j = idx + direction
        while (j in s.insts.indices && catalog[s.insts[j].op]!!.hidden) j += direction
        if (j !in s.insts.indices) fail(EngineError.ARG, "$op/$priority cannot move further")
        val list = s.insts.toMutableList()
        list.removeAt(idx)
        list.add(j, i)
        val cur = s.live()[op to priority]!!
        s.beginChange()
        s.insts = list
        // desktop switches the moved module on
        s.addItem(op, priority, true, cur.params)
    }

    override suspend fun instanceRename(op: String, priority: Int, name: String) = onEngine {
        val s = requireWritable()
        def(op)
        val i = s.inst(op, priority)
        val cur = s.live()[op to priority]!!
        s.beginChange()
        s.insts = s.insts.map { if (it == i) it.copy(instanceName = name) else it }
        s.addItem(op, priority, cur.enabled, cur.params)
    }

    // ------------------------------------------------------------ 2.6 history

    override suspend fun history(): History = onEngine {
        val s = requireSession()
        History.fromJson(
            History(s.end, s.items.mapIndexed { n, it ->
                HistoryItem(n, it.op, it.priority, catalog[it.op]?.name ?: it.op, it.instanceName, it.enabled, it.unknown)
            }).toJson()
        )
    }

    /*
     * history_goto moves the view of the history; it changes no edit. The
     * specification does not say whether it is an undo step or a mutating
     * call; here it is neither (the P2a report explains why): it seals the open
     * group, is not recorded for undo, and works in read-only sessions, where
     * the history can be looked at but nothing can be written.
     */
    override suspend fun historyGoto(end: Int) = onEngine {
        val s = requireSession()
        // as dtb_history_goto: ARG for an end outside 0..items (P2b)
        if (end !in 0..s.items.size) fail(EngineError.ARG, "history end $end outside 0..${s.items.size}")
        s.openGroup = null
        s.end = end
    }

    override suspend fun historyCompress() = onEngine {
        val s = requireWritable()
        s.beginChange()
        // as dt_history_compress_on_image (common/history.c): the items above the end are dropped, and of
        // the rest the last item of each instance is kept, disabled modules included ("keep disabled
        // modules as documented"). P2b: the fake dropped modules that end up off; the real engine keeps them.
        val upTo = s.items.take(s.end)
        val kept = ArrayList<Item>()
        val lastIndex = HashMap<Pair<String, Int>, Int>()
        upTo.forEachIndexed { n, it -> lastIndex[it.op to it.priority] = n }
        upTo.forEachIndexed { n, it -> if (lastIndex[it.op to it.priority] == n) kept += it }
        s.items = kept
        s.end = kept.size
    }

    override suspend fun historySeal() = onEngine {
        val s = requireSession()
        s.openGroup = null
    }

    override suspend fun undo() = onEngine {
        val s = requireWritable()
        s.histGroup = false; s.histGroupFirst = -1
        s.openGroup = null
        val prev = s.undo.removeLastOrNull() ?: fail(EngineError.STATE, "nothing to undo")
        s.redo.addLast(s.snapshot())
        s.restore(prev)
    }

    override suspend fun redo() = onEngine {
        val s = requireWritable()
        s.histGroup = false; s.histGroupFirst = -1
        s.openGroup = null
        val next = s.redo.removeLastOrNull() ?: fail(EngineError.STATE, "nothing to redo")
        s.undo.addLast(s.snapshot())
        s.restore(next)
    }

    override suspend fun undoState(): UndoState = onEngine {
        val s = requireSession()
        UndoState.fromJson(UndoState(s.undo.isNotEmpty() && !s.readOnly, s.redo.isNotEmpty() && !s.readOnly).toJson())
    }

    // ------------------------------------------------------------ 2.7 rendering

    private fun orientation(s: Session): Int {
        val l = s.live()[Pair("flip", 0)] ?: return 0
        if (!l.enabled) return 0
        val name = (l.params["orientation"] as? JsonPrimitive)?.content ?: return 0
        return catalog["flip"]?.fields?.get("orientation")?.enumValue(name)?.takeIf { it >= 0 } ?: 0
    }

    private fun orientation(s: Session, bypass: Set<Pair<String, Int>>): Int = if (("flip" to 0) in bypass) 0 else orientation(s)

    /**
     * 6.1: the instances a render leaves out, checked as the bridge checks them: NOT_FOUND for no such instance,
     * ARG for a module without an on/off switch.
     */
    private fun Session.bypassOf(options: RenderOptions): Set<Pair<String, Int>> {
        if (options.showMask != null) {
            // 6.8: refused as the bridge refuses it; the stand-in then draws the picture in grey without the
            // yellow mask (it renders no masks)
            val m = options.showMask
            inst(m.op, m.priority)
            val masks = (live()[m.op to m.priority]?.blend?.get("masks") as? JsonPrimitive)?.content ?: "off"
            if (masks == "off" || masks == "uniform") fail(EngineError.ARG, "${m.op} ${m.priority} has no mask to show (mask mode off or uniform)")
        }
        return options.bypass.map { b ->
            inst(b.op, b.priority)
            if (!def(b.op).canSwitch) fail(EngineError.ARG, "${b.op} cannot be left out: it has no on/off switch")
            b.op to b.priority
        }.toSet() + focusLeftOut()
    }

    /**
     * Brief P3h (engine-api.md 8): [block] sees the history end [RenderOptions.historyEnd] and the end goes back
     * afterwards, also after a failure or a cancel; nothing else of the session changes (the open group stays open).
     */
    private inline fun <T> Session.atEnd(options: RenderOptions, block: () -> T): T {
        val e = options.historyEnd ?: return block()
        if (e !in 0..items.size) fail(EngineError.ARG, "history_end $e outside 0..${items.size}")
        val saved = end
        end = e
        try {
            return block()
        } finally {
            end = saved
        }
    }

    override suspend fun renderSize(scale: Double): RenderSize = renderSize(scale, RenderOptions.NONE)

    override suspend fun renderSize(scale: Double, options: RenderOptions): RenderSize = onEngine {
        val s = requireSession()
        s.atEnd(options) {
            val bypass = s.bypassOf(options)
            if (!(scale > 0.0) || scale > 16.0) fail(EngineError.ARG, "scale $scale")
            val (w, h) = FakeRenderer(s.variant).processedSize(orientation(s, bypass), scale)
            RenderSize.fromJson(RenderSize(w, h).toJson())
        }
    }

    override suspend fun renderPreview(maxSize: Int): RenderedImage = renderPreview(maxSize, RenderOptions.NONE)

    override suspend fun renderPreview(maxSize: Int, options: RenderOptions): RenderedImage = onEngine {
        val s = requireSession()
        outOfMemoryCheck()
        s.atEnd(options) {
            val bypass = s.bypassOf(options)
            if (maxSize < 16) fail(EngineError.ARG, "max_size $maxSize")
            val r = FakeRenderer(s.variant)
            val o = orientation(s, bypass)
            val (fw, fh) = r.processedSize(o, 1.0)
            val scale = min(1.0, maxSize.toDouble() / max(fw, fh))
            val (w, h) = r.processedSize(o, scale)
            render(s, r, 0, 0, w, h, scale, o, timing.previewFixedMs, "preview", bypass).let { if (options.showMask != null) grey(it) else it }
        }
    }

    /** the stand-in's "show mask": the picture in grey (no mask drawn) */
    private fun grey(img: RenderedImage): RenderedImage {
        val px = img.rgba.copyOf()
        for (i in 0 until img.width * img.height) {
            val y = (0.3 * (px[4 * i].toInt() and 0xff) + 0.59 * (px[4 * i + 1].toInt() and 0xff) + 0.11 * (px[4 * i + 2].toInt() and 0xff)).roundToInt().toByte()
            px[4 * i] = y; px[4 * i + 1] = y; px[4 * i + 2] = y
        }
        return RenderedImage(img.width, img.height, px)
    }

    override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double): RenderedImage =
        renderRegion(x, y, w, h, scale, RenderOptions.NONE)

    override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double, options: RenderOptions): RenderedImage = onEngine {
        val s = requireSession()
        outOfMemoryCheck()
        s.atEnd(options) {
            val bypass = s.bypassOf(options)
            if (!(scale > 0.0) || scale > 16.0) fail(EngineError.ARG, "scale $scale")
            val r = FakeRenderer(s.variant)
            val o = orientation(s, bypass)
            val (fw, fh) = r.processedSize(o, scale)
            if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > fw || y + h > fh)
                fail(EngineError.ARG, "region $x,$y ${w}x$h outside ${fw}x$fh")
            if (w.toLong() * h > 64L * 1024 * 1024) fail(EngineError.ARG, "region too large")
            render(s, r, x, y, w, h, scale, o, timing.regionFixedMs, "full", bypass).let { if (options.showMask != null) grey(it) else it }
        }
    }

    private suspend fun render(s: Session, r: FakeRenderer, x: Int, y: Int, w: Int, h: Int, scale: Double, o: Int, fixedMs: Long, pipe: String? = null,
                               bypass: Set<Pair<String, Int>> = emptySet()): RenderedImage {
        val flag = AtomicBoolean(false)
        runningRender.set(flag)
        try {
            val live = s.live().mapValues { (k, l) -> if (k in bypass) l.copy(enabled = false) else l }
            val stages = s.insts.mapNotNull { i ->
                val l = live[i.op to i.priority]!!
                if (l.enabled) r.stageFor(i.op, l.params) else null
            }
            val n = w * h
            val totalMs = fixedMs + (timing.msPerMegapixel * n / 1_000_000.0).roundToInt()
            val perStage = if (stages.isEmpty()) totalMs else totalMs / (stages.size + 1)
            suspend fun checkpoint(ms: Long) {
                var left = ms
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (flag.get()) throw EngineException(EngineError.CANCELLED, "render cancelled")
                    if (left <= 0) break
                    val step = min(left, 5L)
                    Thread.sleep(step)
                    left -= step
                }
            }
            val coords = r.coordsFor(x, y, w, h, scale, o)
            val t0 = System.nanoTime()
            val px = r.scene(coords)
            checkpoint(perStage)
            val times = ArrayList<ModuleTime>()
            for (st in stages) {
                val ts = System.nanoTime()
                st.pixel?.apply(px, n, coords)
                checkpoint(perStage) // cancel takes effect between modules
                times += ModuleTime(st.op, 0, "", (System.nanoTime() - ts) / 1e6, (System.nanoTime() - ts) / 1e6, 1)
            }
            if (pipe != null) lastTimes = RenderTimes(pipe, (System.nanoTime() - t0) / 1e6, times)
            val toneMapped = stages.any { it.op == "sigmoid" || it.op == "filmicrgb" || it.op == "agx" }
            return RenderedImage(w, h, r.toRgba8(px, n, toneMapped))
        } finally {
            runningRender.compareAndSet(flag, null)
        }
    }

    override fun renderCancel() {
        runningRender.get()?.set(true)
    }

    override suspend fun cacheDrop() = onEngine { requireInit() }

    /** Brief P4a: the trim levels asked, in order (tests). */
    val memoryTrims: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList())
    /** Brief P4a: the next renders fail as the bridge's do when memory runs out, until a trim of level 2 (tests) */
    @Volatile var outOfMemoryRenders: Int = 0
    /** Brief P4a: whether a trim of level 2 ends [outOfMemoryRenders] (false: memory stays short) */
    @Volatile var trimFreesEnough: Boolean = true

    private fun outOfMemoryCheck() {
        if (outOfMemoryRenders > 0) { outOfMemoryRenders--; fail(EngineError.ENGINE, "out of memory") }
    }

    override suspend fun memoryInfo(): EngineMemory = onEngine { requireInit(); EngineMemory(pipesReady = if (session != null) 2 else 0) }

    override suspend fun memoryTrim(level: Int): EngineMemory = onEngine {
        requireInit()
        if (level !in 1..2) fail(EngineError.ARG, "level is 1 or 2")
        memoryTrims += level
        if (level == 2 && trimFreesEnough) outOfMemoryRenders = 0
        EngineMemory(pipesReady = if (level == 2) 0 else 2)
    }

    // ------------------------------------------------------------ 2.8 output

    override suspend fun xmpWrite(path: String) = onEngine {
        val s = requireWritable()
        val f = File(path)
        if (f.exists() && !check(readXmp(path)).ok) fail(EngineError.READ_ONLY, "$path fails the version guard; not overwritten")
        writeXmpOf(s, path)
    }

    private fun writeXmpOf(s: Session, path: String) {
        val entries = s.items.mapIndexed { n, it ->
            FakeXmp.Entry(n, it.op, catalog[it.op]!!.version, it.enabled, it.priority, it.instanceName, it.params)
        }
        val order = s.insts.mapIndexed { idx, i -> "${i.op}/${i.priority}" to (idx + 1) * 100 }.toMap()
        try {
            FakeXmp.write(File(path), entries, s.end, order)
        } catch (e: Exception) {
            fail(EngineError.IO, "cannot write $path: ${e.message}")
        }
    }

    override suspend fun export(path: String, options: ExportOptions) = export(path, options) {}

    /**
     * P3g: time each module of the stand-in export takes (ms), so the app's progress can be seen and cancelled
     * on the stand-in; 0 by default (tests set it).
     */
    @Volatile var exportMsPerModule: Long = 0

    private val exportCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var exporting = false

    /** P3g: as the real engine (engine-api.md section 7): one PROCESSING per switched-on instance. */
    override suspend fun export(path: String, options: ExportOptions, onProgress: (ExportProgress) -> Unit) = onEngine {
        val s = requireSession()
        if (options.format != ExportFormat.PNG) fail(EngineError.ARG, "the fake engine exports PNG only")
        exportCancelled.set(false)
        exporting = true
        try {
            onProgress(ExportProgress(ExportStep.PREPARING, 0, 0, 0.0))
            val live = s.live()
            val on = s.insts.filter { live[it.op to it.priority]?.enabled == true }
            val modules = maxOf(on.size, 1)
            for (k in 1..modules) {
                if (exportCancelled.get()) fail(EngineError.CANCELLED, "export cancelled")
                onProgress(ExportProgress(ExportStep.PROCESSING, k, modules, 0.05 + 0.85 * (k - 1) / modules))
                var left = exportMsPerModule
                while (left > 0 && !exportCancelled.get()) { val st = min(left, 5L); Thread.sleep(st); left -= st }
            }
            if (exportCancelled.get()) fail(EngineError.CANCELLED, "export cancelled")
            val r = FakeRenderer(s.variant)
            val o = orientation(s)
            val (fw, fh) = r.processedSize(o, 1.0)
            var scale = 1.0
            if (options.maxWidth > 0) scale = min(scale, options.maxWidth.toDouble() / fw)
            if (options.maxHeight > 0) scale = min(scale, options.maxHeight.toDouble() / fh)
            val (w, h) = r.processedSize(o, scale)
            val img = render(s, r, 0, 0, w, h, scale, o, 0)
            onProgress(ExportProgress(ExportStep.WRITING, modules, modules, 0.9))
            val f = File(path)
            val tmp = File(f.parentFile, f.name + ".tmp")
            try {
                tmp.writeBytes(Png.encode(img))
                if (exportCancelled.get()) { tmp.delete(); fail(EngineError.CANCELLED, "export cancelled") }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (e: EngineException) {
                throw e
            } catch (e: Exception) {
                tmp.delete()
                fail(EngineError.IO, "cannot write $path: ${e.message}")
            }
            onProgress(ExportProgress(ExportStep.DONE, modules, modules, 1.0))
        } finally {
            exporting = false
            exportCancelled.set(false)
        }
    }

    override fun exportCancel() {
        if (exporting) exportCancelled.set(true)
    }

    // ------------------------------------------------------------ 5 (P1b part 1)
    // Presets and styles live in memory for the engine's life (the real engine keeps them in
    // darktable's data.db in the configuration folder). The fake knows one built-in preset, for
    // exposure, and no shipped styles.

    private data class FPreset(
        val op: String, val name: String, val description: String, val builtin: Boolean,
        val opVersion: Int, val enabled: Boolean, val params: JsonObject,
    )

    private data class FStyleItem(
        val num: Int, val op: String, val priority: Int, val instanceName: String, val enabled: Boolean,
        val moduleVersion: Int, val blendVersion: Int, val params: JsonObject?,
    )

    private data class FStyle(val name: String, val description: String, val items: List<FStyleItem>)

    private val presetsDb = mutableListOf<FPreset>()
    private val stylesDb = sortedMapOf<String, FStyle>()
    private var lastTimes: RenderTimes? = null

    private fun builtinPresets(): List<FPreset> {
        val d = catalog["exposure"] ?: return emptyList()
        val p = runCatching { setPath(d, d.defaultParams, "exposure", JsonPrimitive(0.7)) }.getOrDefault(d.defaultParams)
        val exposure = listOf(FPreset("exposure", "_builtin_scene-referred default", "", true, d.version, true, p))
        // brief P4c: two of diffuse or sharpen's built-in presets, stored as darktable stores them (`_builtin_` prefix)
        val df = catalog["diffuse"] ?: return exposure
        fun deblur(iterations: Int, radius: Int) = runCatching {
            setPath(df, setPath(df, df.defaultParams, "iterations", JsonPrimitive(iterations)), "radius", JsonPrimitive(radius))
        }.getOrDefault(df.defaultParams)
        return exposure + listOf(
            FPreset("diffuse", "_builtin_lens deblur | soft", "", true, df.version, true, deblur(8, 8)),
            FPreset("diffuse", "_builtin_lens deblur | medium", "", true, df.version, true, deblur(16, 10)),
        )
    }

    private fun problemOf(op: String, moduleVersion: Int, blendVersion: Int): ItemProblem? {
        val d = catalog[op] ?: return ItemProblem.UNKNOWN_OPERATION
        if (moduleVersion > d.version) return ItemProblem.NEWER_MODULE_VERSION
        if (blendVersion > FAKE_BLEND_VERSION) return ItemProblem.NEWER_BLEND_VERSION
        return null
    }

    override suspend fun presets(op: String, priority: Int): List<Preset> = onEngine {
        requireInit()
        val d = def(op)
        val s = session
        val live = s?.let { it.inst(op, priority); it.live()[op to priority] }
        presetsDb.filter { it.op == op }
            .sortedWith(compareByDescending<FPreset> { it.builtin }.thenBy { it.name.lowercase() })
            .map { p ->
                Preset(
                    p.name, p.description, p.builtin, p.opVersion, p.opVersion == d.version, p.enabled,
                    autoapply = false, filter = false, match = PresetMatch(),
                    matches = if (s != null) true else null, fits = if (s != null) true else null,
                    active = live?.let { p.opVersion == d.version && it.params == p.params && it.enabled == p.enabled },
                )
            }
    }

    override suspend fun presetApply(op: String, priority: Int, name: String) = onEngine {
        val s = requireWritable()
        val d = def(op)
        s.inst(op, priority)
        val all = presetsDb.filter { it.op == op && it.name == name }
        if (all.isEmpty()) fail(EngineError.NOT_FOUND, "$op has no preset '$name'")
        val p = all.firstOrNull { it.opVersion == d.version } ?: fail(EngineError.ARG, "preset '$name' was stored for another version of $op")
        s.beginChange()
        s.addItem(op, priority, p.enabled, p.params)
    }

    private fun validName(n: String) = n.isNotEmpty() && '/' !in n

    override suspend fun presetStore(op: String, priority: Int, name: String, description: String, overwrite: Boolean) = onEngine {
        val s = requireSession()
        val d = def(op)
        s.inst(op, priority)
        if (!validName(name)) fail(EngineError.ARG, "a preset name must be non-empty without '/'")
        val existing = presetsDb.filter { it.op == op && it.name == name }
        if (existing.any { it.builtin }) fail(EngineError.ARG, "'$name' is a built-in preset of $op")
        if (existing.isNotEmpty() && !overwrite) fail(EngineError.ARG, "$op already has a preset '$name' (exists)")
        val l = s.live()[op to priority]!!
        val desc = if (existing.isNotEmpty() && description.isEmpty()) existing.first().description else description
        presetsDb.removeAll(existing)
        presetsDb += FPreset(op, name, desc, false, d.version, l.enabled, l.params)
    }

    override suspend fun presetRename(op: String, name: String, newName: String) = onEngine {
        requireInit()
        val all = presetsDb.filter { it.op == op && it.name == name }
        if (all.isEmpty()) fail(EngineError.NOT_FOUND, "$op has no preset '$name'")
        if (all.any { it.builtin }) fail(EngineError.ARG, "'$name' is a built-in preset")
        if (!validName(newName)) fail(EngineError.ARG, "a preset name must be non-empty without '/'")
        if (name == newName) return@onEngine
        if (presetsDb.any { it.op == op && it.name == newName }) fail(EngineError.ARG, "$op already has a preset '$newName' (exists)")
        presetsDb.replaceAll { if (it.op == op && it.name == name) it.copy(name = newName) else it }
    }

    override suspend fun presetDelete(op: String, name: String) = onEngine {
        requireInit()
        val all = presetsDb.filter { it.op == op && it.name == name }
        if (all.isEmpty()) fail(EngineError.NOT_FOUND, "$op has no preset '$name'")
        if (all.any { it.builtin }) fail(EngineError.ARG, "'$name' is a built-in preset")
        presetsDb.removeAll(all)
        Unit
    }

    private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun xmlUnesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    private fun xmlField(text: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.let { xmlUnesc(it) }

    // The fake's .dtpreset holds the parameters as JSON in <op_params>; the elements around them are darktable's.
    override suspend fun presetImport(path: String, overwrite: Boolean): PresetRef = onEngine {
        requireInit()
        val f = File(path)
        if (!f.isFile) fail(EngineError.IO, "cannot read $path")
        val text = f.readText()
        if (!text.contains("<darktable_preset")) fail(EngineError.IO, "$path is not a darktable preset file")
        val name = xmlField(text, "name") ?: fail(EngineError.IO, "$path is not a complete darktable preset file")
        val op = xmlField(text, "operation") ?: fail(EngineError.IO, "$path is not a complete darktable preset file")
        val ver = xmlField(text, "op_version")?.trim()?.toIntOrNull() ?: fail(EngineError.IO, "$path has no op_version")
        val bver = xmlField(text, "blendop_version")?.trim()?.toIntOrNull() ?: 0
        problemOf(op, ver, bver)?.let { fail(EngineError.ARG, "preset '$name' cannot be imported: ${it.json} (module $op version $ver)") }
        val existing = presetsDb.filter { it.op == op && it.name == name }
        if (existing.any { it.builtin }) fail(EngineError.ARG, "preset '$name' cannot be imported: $op has a built-in preset of that name")
        if (existing.isNotEmpty() && !overwrite) fail(EngineError.ARG, "preset '$name' cannot be imported: exists")
        val params = runCatching { parseEngineJson(xmlField(text, "op_params") ?: "") as JsonObject }.getOrNull()
            ?: catalog[op]!!.defaultParams
        presetsDb.removeAll(existing)
        presetsDb += FPreset(op, name, xmlField(text, "description") ?: "", false, ver, xmlField(text, "enabled")?.trim() != "0", params)
        PresetRef(op, name, ver, ver == catalog[op]!!.version)
    }

    override suspend fun presetExport(op: String, name: String, dir: String): String = onEngine {
        requireInit()
        val p = presetsDb.filter { it.op == op && it.name == name }.maxByOrNull { it.opVersion }
            ?: fail(EngineError.NOT_FOUND, "$op has no preset '$name'")
        val d = File(dir)
        if (!d.isDirectory) fail(EngineError.IO, "$dir is not a folder")
        val f = File(d, name.map { if (it in "/<>:\"\\|*?[]") '_' else it }.joinToString("") + ".dtpreset")
        try {
            f.writeText(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<darktable_preset version=\"2.0\"><preset>" +
                    "<name>${xmlEsc(p.name)}</name><description>${xmlEsc(p.description)}</description>" +
                    "<operation>${p.op}</operation><op_params>${xmlEsc(p.params.toString())}</op_params>" +
                    "<op_version>${p.opVersion}</op_version><enabled>${if (p.enabled) 1 else 0}</enabled>" +
                    "<blendop_version>$FAKE_BLEND_VERSION</blendop_version></preset></darktable_preset>\n"
            )
        } catch (e: Exception) {
            fail(EngineError.IO, "cannot write ${f.path}: ${e.message}")
        }
        f.path
    }

    private fun FStyle.toApi() = Style(
        name, description, false,
        items.map { StyleItem(it.num, it.op, it.priority, it.instanceName, it.enabled, it.moduleVersion, it.blendVersion, problemOf(it.op, it.moduleVersion, it.blendVersion)) },
        items.none { problemOf(it.op, it.moduleVersion, it.blendVersion) != null },
    )

    override suspend fun styles(): List<Style> = onEngine {
        requireInit()
        stylesDb.values.map { it.toApi() }
    }

    override suspend fun styleApply(name: String, mode: StyleMode?) = onEngine {
        val s = requireWritable()
        val st = stylesDb[name] ?: fail(EngineError.NOT_FOUND, "no style '$name'")
        val api = st.toApi()
        if (!api.usable)
            fail(EngineError.ARG, "style '$name' cannot be applied: " +
                api.items.filter { it.problem != null }.joinToString("; ") { "item ${it.num} '${it.op}' ${it.problem!!.json}" })
        s.beginChange()
        if (mode == StyleMode.OVERWRITE) {
            // desktop's overwrite: the default edit, then the style
            s.items = emptyList(); s.end = 0
            s.insts = catalog.modules.values.sortedBy { it.pipePosition }.map { Inst(it.op, 0, "") }
            applyDefaultEdit(s)
        }
        for (it in st.items.sortedWith(compareBy({ it.op }, { it.priority }))) {
            val prio = if (s.insts.any { i -> i.op == it.op && i.priority == it.priority }) it.priority else 0
            s.addItem(it.op, prio, it.enabled, it.params ?: catalog[it.op]!!.defaultParams)
        }
    }

    override suspend fun stylesImport(paths: List<String>, overwrite: Boolean): StyleImport = onEngine {
        requireInit()
        val ok = mutableListOf<StyleImport.Imported>()
        val no = mutableListOf<StyleImport.Refused>()
        for (p in paths) {
            val f = File(p)
            if (!f.isFile) { no += StyleImport.Refused(p, "io"); continue }
            val text = f.readText()
            val name = if (text.contains("<darktable_style")) xmlField(text, "name") else null
            if (name.isNullOrEmpty()) { no += StyleImport.Refused(p, "not_a_style"); continue }
            if (name in stylesDb && !overwrite) { no += StyleImport.Refused(p, "exists"); continue }
            val items = Regex("<plugin>(.*?)</plugin>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { m ->
                val x = m.groupValues[1]
                val op = xmlField(x, "operation") ?: ""
                val params = runCatching { parseEngineJson(xmlField(x, "op_params") ?: "") as JsonObject }.getOrNull()
                FStyleItem(
                    xmlField(x, "num")?.trim()?.toIntOrNull() ?: 0, op, xmlField(x, "multi_priority")?.trim()?.toIntOrNull() ?: 0,
                    xmlField(x, "multi_name") ?: "", xmlField(x, "enabled")?.trim() != "0",
                    xmlField(x, "module")?.trim()?.toIntOrNull() ?: 0, xmlField(x, "blendop_version")?.trim()?.toIntOrNull() ?: 0,
                    params?.takeIf { catalog[op] != null },
                )
            }.toList()
            stylesDb[name] = FStyle(name, xmlField(text, "description") ?: "", items)
            ok += StyleImport.Imported(p, name, stylesDb[name]!!.toApi().usable)
        }
        StyleImport(ok, no)
    }

    override suspend fun styleDelete(name: String) = onEngine {
        requireInit()
        stylesDb.remove(name) ?: fail(EngineError.NOT_FOUND, "no style '$name'")
        Unit
    }

    // R11 (brief P3l): the fake keeps shared bodies as they came, apart from its own presets and styles; versions
    // above 99 stand for "newer than this engine"
    private val sharedDb = sortedMapOf<String, JsonObject>()

    private fun sharedKey(kind: String, op: String, v: Int, name: String) = if (kind == "style") "style\u0000$name" else "$kind\u0000$op\u0000$v\u0000$name"

    override suspend fun sharedSnapshot(): kotlinx.serialization.json.JsonArray = onEngine {
        requireInit()
        kotlinx.serialization.json.JsonArray(sharedDb.values.map { b ->
            val kind = (b["kind"] as JsonPrimitive).content
            val row = (if (kind == "style") b["style"] else b["row"]) as JsonObject
            kotlinx.serialization.json.buildJsonObject {
                put("kind", JsonPrimitive(kind))
                put("operation", row["operation"] ?: JsonPrimitive(""))
                put("op_version", row["op_version"] ?: JsonPrimitive(0))
                put("name", row["name"] ?: JsonPrimitive(""))
                put("body", b)
            }
        })
    }

    override suspend fun sharedApply(body: JsonObject): Boolean = onEngine {
        requireInit()
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
        when ((body["kind"] as? JsonPrimitive)?.content) {
            "preset" -> {
                val row = body["row"] as? JsonObject ?: fail(EngineError.ARG, "a preset body needs a row")
                val name = str(row, "name") ?: fail(EngineError.ARG, "no name")
                val op = str(row, "operation") ?: fail(EngineError.ARG, "no operation")
                if (int(row, "op_version") > 99 || int(row, "blendop_version") > 99) return@onEngine false
                sharedDb[sharedKey("preset", op, int(row, "op_version"), name)] = body
            }
            "style" -> {
                val st = body["style"] as? JsonObject ?: fail(EngineError.ARG, "a style body needs a style")
                val name = str(st, "name") ?: fail(EngineError.ARG, "no name")
                val items = body["items"] as? kotlinx.serialization.json.JsonArray ?: fail(EngineError.ARG, "no items")
                if (items.any { int(it as? JsonObject, "module") > 99 || int(it as? JsonObject, "blendop_version") > 99 }) return@onEngine false
                sharedDb[sharedKey("style", "", 0, name)] = body
            }
            else -> fail(EngineError.ARG, "not a preset or style body")
        }
        true
    }

    override suspend fun sharedDelete(kind: String, operation: String, opVersion: Int, name: String) = onEngine {
        requireInit()
        sharedDb.remove(sharedKey(kind, operation, opVersion, name)) ?: fail(EngineError.NOT_FOUND, "no $kind '$name'")
        Unit
    }

    override suspend fun styleCreate(name: String, description: String, items: List<Int>?, overwrite: Boolean) = onEngine {
        val s = requireSession()
        if (name.isEmpty()) fail(EngineError.ARG, "a style name must be non-empty")
        if (name in stylesDb && !overwrite) fail(EngineError.ARG, "a style '$name' exists (exists)")
        if (s.items.any { it.unknown }) fail(EngineError.READ_ONLY, "the history holds items the engine could not load")
        val chosen = items?.toSet()
        if (chosen != null) {
            if (chosen.isEmpty()) fail(EngineError.ARG, "no history item chosen")
            chosen.firstOrNull { it < 0 || it >= s.end }?.let { fail(EngineError.ARG, "history num $it outside 0..${s.end - 1}") }
        }
        // one entry per module instance: its last chosen item, as desktop's dialog
        val last = LinkedHashMap<Pair<String, Int>, Int>()
        s.items.take(s.end).forEachIndexed { i, it -> if (chosen == null || i in chosen) last[it.op to it.priority] = i }
        if (last.isEmpty()) fail(EngineError.ARG, "the chosen history holds no module item")
        stylesDb[name] = FStyle(name, description, last.values.sorted().map { i ->
            val it = s.items[i]
            FStyleItem(i, it.op, it.priority, it.instanceName, it.enabled, catalog[it.op]!!.version, FAKE_BLEND_VERSION, it.params)
        })
    }

    // ------------------------------------------------------------ 9 (P3j): copy and paste of the history
    //
    // The stand-in keeps the copied photo's items (one per instance, its last item, as desktop's dialog lists them);
    // a paste adds them as history items: append on top (instances that do not exist are made), overwrite on an
    // empty history. Modules unsafe to copy (white balance, raw black/white point, orientation) are left out by a
    // plain copy, as darktable's IOP_FLAGS_UNSAFE_COPY.

    private class FClip(val items: List<Pair<Int, Item>>, val parts: Boolean, val order: Boolean, var selected: Set<Int>?, val source: String)
    private var clip: FClip? = null

    private fun Session.copyList(): List<Pair<Int, Item>> {
        val last = LinkedHashMap<Pair<String, Int>, Int>()
        items.forEachIndexed { i, it -> if (!it.unknown) last[it.op to it.priority] = i }
        return last.values.sorted().map { it to items[it] }
    }

    private fun copyItemOf(num: Int, it: Item, selected: Boolean? = null) = CopyItem(
        num, it.op, it.priority, if (it.instanceName.isEmpty()) it.op else "${it.op} ${it.instanceName}", it.instanceName,
        it.enabled, it.blend != null, it.op !in FAKE_UNSAFE_COPY, selected,
    )

    private fun checkSelection(sel: HistorySelection, listed: Set<Int>) {
        if (sel.items.isEmpty()) fail(EngineError.ARG, "no item chosen")
        (sel.items + sel.reset).firstOrNull { it !in listed }?.let { fail(EngineError.ARG, "\"items\": $it is not one of the listed items") }
    }

    override suspend fun historyCopyItems(): CopyItems = onEngine {
        val s = requireSession()
        CopyItems(s.copyList().map { (n, it) -> copyItemOf(n, it) }, "v5.0")
    }

    override suspend fun historyCopy(selection: HistorySelection?) = onEngine {
        val s = requireWritable()
        val list = s.copyList()
        selection?.let { checkSelection(it, list.map { p -> p.first }.toSet()) }
        clip = FClip(list, selection != null, selection?.moduleOrder ?: true, selection?.items?.toSet(), File(s.rawPath).absolutePath)
    }

    override suspend fun historyClipboard(): Clipboard? = onEngine {
        requireInit()
        clip?.let { c -> Clipboard(c.parts, c.order, c.items.map { (n, it) -> copyItemOf(n, it, c.selected?.contains(n) ?: true) }, "v5.0") }
    }

    private fun pasteOnto(s: Session, c: FClip, mode: PasteMode?) {
        val chosen = c.items.filter { (n, it) -> c.selected?.contains(n) ?: (c.parts || it.op !in FAKE_UNSAFE_COPY) }
        s.beginChange()
        if (mode == PasteMode.OVERWRITE) {
            s.items = emptyList(); s.end = 0
            s.insts = catalog.modules.values.sortedBy { it.pipePosition }.map { Inst(it.op, 0, "") }
        }
        for ((_, it) in chosen) {
            if (catalog[it.op] == null) continue
            if (s.insts.none { i -> i.op == it.op && i.priority == it.priority })
                s.insts = s.insts + Inst(it.op, it.priority, it.instanceName)
            s.addItem(it.op, it.priority, it.enabled, it.params, it.blend)
        }
    }

    private fun pasteSelection(c: FClip, selection: HistorySelection?) {
        if (selection != null) {
            checkSelection(selection, c.items.map { it.first }.toSet())
            c.selected = selection.items.toSet()
        }
    }

    override suspend fun historyPaste(mode: PasteMode?, selection: HistorySelection?) = onEngine {
        val s = requireWritable()
        val c = clip ?: fail(EngineError.STATE, "nothing copied")
        // as desktop: not onto the photo it was copied from
        if (File(s.rawPath).absolutePath == c.source) fail(EngineError.ARG, "this is the photo the history was copied from")
        pasteSelection(c, selection)
        pasteOnto(s, c, mode)
    }

    override suspend fun historyPasteFiles(targets: List<PasteTarget>, mode: PasteMode?, selection: HistorySelection?): List<PasteResult> = onEngine {
        requireInit()
        if (session != null) fail(EngineError.STATE, "close the open image first (save it to its XMP)")
        val c = clip ?: fail(EngineError.STATE, "nothing copied")
        pasteSelection(c, selection)
        targets.map { t ->
            try {
                // as open: the stand-in decodes nothing, so only an empty file is refused
                if (File(t.raw).let { it.isFile && it.length() == 0L }) fail(EngineError.IO, "cannot decode ${t.raw}")
                if (File(t.raw).absolutePath == c.source) fail(EngineError.ARG, "${t.raw} is the photo the history was copied from")
                val insts = catalog.modules.values.sortedBy { it.pipePosition }.map { Inst(it.op, 0, "") }
                val name = File(t.raw).name
                val s = Session(t.raw, false, (name.hashCode() and 0x7fffffff) % 6, samples[name], insts, emptyList(), 0, 0, emptyList())
                if (File(t.xmp).isFile) {
                    val text = readXmp(t.xmp)
                    if (!check(text).ok) fail(EngineError.READ_ONLY, "refusing to change ${t.xmp}: it failed the version guard")
                    loadXmp(s, FakeXmp.parse(text))
                } else applyDefaultEdit(s)
                pasteOnto(s, c, mode)
                writeXmpOf(s, t.xmp)
                PasteResult(t.raw, t.xmp, null, null)
            } catch (e: EngineException) {
                PasteResult(t.raw, t.xmp, e.error, e.detail)
            }
        }
    }

    // ------------------------------------------------------------ 6 (P1b part 2): pickers, histogram, coordinates
    //
    // The stand-in engine picks on its own picture (the preview it renders, 0..1 from the 8-bit values): a module's
    // "input" is the picture with that module left out, its "output" the picture with it in, both "rgb". The
    // global picker gives the picture's statistics. Coordinates: its only geometry is the orientation (flip).

    private fun area(req: PickRequest): DoubleArray {
        val a = req.area ?: req.point?.let { listOf(it[0], it[1], it[0], it[1]) }
            ?: fail(EngineError.ARG, "expected \"area\": [x0, y0, x1, y1] or \"point\": [x, y]")
        if (req.area != null && req.area.size != 4 || req.point != null && req.point.size != 2) fail(EngineError.ARG, "bad area or point")
        if (a.any { !it.isFinite() || it < 0.0 || it > 1.0 }) fail(EngineError.ARG, "area and point are normalised coordinates of the picture (0..1)")
        if (a[2] < a[0] || a[3] < a[1]) fail(EngineError.ARG, "area: x0 <= x1 and y0 <= y1")
        return a.toDoubleArray()
    }

    private suspend fun fakeStats(s: Session, a: DoubleArray, bypass: Set<Pair<String, Int>>): PickStats {
        val r = FakeRenderer(s.variant)
        val o = orientation(s, bypass)
        val (fw, fh) = r.processedSize(o, 1.0)
        val scale = min(1.0, 512.0 / max(fw, fh))
        val (w, h) = r.processedSize(o, scale)
        val img = render(s, r, 0, 0, w, h, scale, o, 0, null, bypass)
        val x0 = (a[0] * w).toInt().coerceIn(0, w - 1)
        val y0 = (a[1] * h).toInt().coerceIn(0, h - 1)
        val x1 = max(x0 + 1, (a[2] * w).toInt()).coerceIn(1, w)
        val y1 = max(y0 + 1, (a[3] * h).toInt()).coerceIn(1, h)
        val sum = DoubleArray(4)
        val mn = DoubleArray(4) { Double.MAX_VALUE }
        val mx = DoubleArray(4) { -Double.MAX_VALUE }
        for (y in y0 until y1) for (x in x0 until x1) for (c in 0..2) {
            val v = (img.rgba[4 * (y * w + x) + c].toInt() and 0xff) / 255.0
            sum[c] += v; mn[c] = min(mn[c], v); mx[c] = max(mx[c], v)
        }
        val n = ((x1 - x0) * (y1 - y0)).toDouble()
        sum[3] = 0.0; mn[3] = 0.0; mx[3] = 0.0
        return PickStats("rgb", sum.map { it / n }.toMutableList<Double?>().also { it[3] = 0.0 }, mn.toList(), mx.toList(), listOf(x0, y0, x1, y1))
    }

    override suspend fun colorPick(op: String?, priority: Int, request: PickRequest): PickResult = onEngine {
        val s = requireSession()
        if (op != null) { def(op); s.inst(op, priority) }
        val a = area(request)
        val bypass = s.bypassOf(request.options)
        val pipe = if (request.fullPipe) "full" else "preview"
        if (op == null) return@onEngine PickResult(pipe, null, fakeStats(s, a, bypass))
        val on = s.live()[op to priority]!!.enabled && (op to priority) !in bypass
        if (!on) fail(EngineError.ARG, "$op $priority is switched off (or left out): desktop's pickers only pick on modules that are on")
        PickResult(pipe, fakeStats(s, a, bypass + (op to priority)), fakeStats(s, a, bypass))
    }

    override suspend fun histogram(maxSize: Int, options: RenderOptions): Histogram = onEngine {
        val s = requireSession()
        val bypass = s.bypassOf(options)
        if (maxSize < 16) fail(EngineError.ARG, "max_size must be >= 16")
        val r = FakeRenderer(s.variant)
        val o = orientation(s, bypass)
        val (fw, fh) = r.processedSize(o, 1.0)
        val scale = min(1.0, maxSize.toDouble() / max(fw, fh))
        val (w, h) = r.processedSize(o, scale)
        val img = render(s, r, 0, 0, w, h, scale, o, 0, null, bypass)
        val counts = List(3) { LongArray(256) }
        for (i in 0 until w * h) for (c in 0..2) counts[c][img.rgba[4 * i + c].toInt() and 0xff]++
        Histogram(256, listOf("red", "green", "blue"), counts.map { it.toList() }, counts.maxOf { it.max() }, (w * h).toLong(), w, h)
    }

    override suspend fun coordsTransform(direction: CoordsDirection, points: List<Pair<Double, Double>>, options: RenderOptions): CoordsResult = onEngine {
        val s = requireSession()
        val bypass = s.bypassOf(options)
        val o = orientation(s, bypass)
        val r = FakeRenderer(s.variant)
        val (iw, ih) = r.processedSize(0, 1.0)
        val (pw, ph) = r.processedSize(o, 1.0)
        // darktable's orientation bits (common/image.h): 1 flip y, 2 flip x, 4 swap x and y, applied as flip.c does
        fun fwd(p: Pair<Double, Double>): Pair<Double, Double> {
            var (x, y) = p
            if (o and 2 != 0) x = 1.0 - x
            if (o and 1 != 0) y = 1.0 - y
            return if (o and 4 != 0) y to x else x to y
        }
        fun back(p: Pair<Double, Double>): Pair<Double, Double> {
            var (x, y) = if (o and 4 != 0) p.second to p.first else p
            if (o and 2 != 0) x = 1.0 - x
            if (o and 1 != 0) y = 1.0 - y
            return x to y
        }
        val out = points.map { if (direction == CoordsDirection.TO_PROCESSED) fwd(it) else back(it) }
        CoordsResult(out, iw, ih, pw, ph)
    }

    /** The stand-in's module actions that pick (6.3), on its own picker (see [colorPick]). */
    private suspend fun fakePickAction(s: Session, op: String, priority: Int, action: String, args: JsonObject): JsonElement {
        requireWritable()
        fun arr(k: String) = (args[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: fail(EngineError.TYPE, "$k takes numbers") }
        val req = PickRequest(area = arr("area"), point = arr("point"))
        val a = area(req)
        if (!s.live()[op to priority]!!.enabled) fail(EngineError.ARG, "$op $priority is switched off")
        val input = fakeStats(s, a, setOf(op to priority))
        val picked = PickResult("preview", input, fakeStats(s, a, emptySet())).toJson()
        val m = input.mean.map { it ?: 0.0 }
        fun lum(v: List<Double?>) = 0.2126 * (v[0] ?: 0.0) + 0.7152 * (v[1] ?: 0.0) + 0.0722 * (v[2] ?: 0.0)
        fun lab(y: Double) = if (y > 216.0 / 24389.0) 116.0 * Math.cbrt(y) - 16.0 else y * 24389.0 / 27.0
        fun yOf(l: Double) = if (l > 8.0) ((l + 16.0) / 116.0).pow(3) else l * 27.0 / 24389.0
        val cur = s.live()[op to priority]!!.params
        return when (op to action) {
            "temperature" to "wb_from_area" -> {
                val gnormal = if (m[1] > 0.001) 1.0 / m[1] else 1.0
                val c = (0..3).map { k -> ((if (m[k] > 0.001) 1.0 / m[k] else 1.0) / gnormal).coerceIn(0.0, 8.0) }.toMutableList()
                c[1] = 1.0
                val vals = linkedMapOf<String, JsonPrimitive>("red" to JsonPrimitive(c[0]), "green" to JsonPrimitive(c[1]), "blue" to JsonPrimitive(c[2]))
                if (cur.containsKey("preset")) vals["preset"] = JsonPrimitive(1)
                s.setParams(op, priority, vals, merge = false)
                buildJsonObject { put("picked", picked); put("coefficients", JsonArray(listOf(c[0], c[1], c[2], 1.0).map { JsonPrimitive(it) })) }
            }
            "exposure" to "spot" -> {
                val mode = (args["mode"] as? JsonPrimitive)?.content ?: "correction"
                if (mode != "correction" && mode != "measure") fail(EngineError.ARG, "mode is \"correction\" or \"measure\"")
                val target = (args["lightness"] as? JsonPrimitive)?.doubleOrNull ?: 50.0
                if (target !in 0.0..100.0) fail(EngineError.RANGE, "lightness 0..100")
                val y = lum(input.mean)
                val ev = (cur["exposure"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                buildJsonObject {
                    put("picked", picked)
                    put("input_lightness", lab(y))
                    if (mode == "measure") put("lightness", lab(y * 2.0.pow(ev)))
                    else {
                        put("lightness", target)
                        if (y > 1e-5) {
                            val e = ln(yOf(target) / y) / ln(2.0)
                            s.setParams(op, priority, mapOf("exposure" to JsonPrimitive(e)), merge = false)
                            put("exposure", e)
                        }
                    }
                }
            }
            "rgblevels" to "pick" -> {
                val which = (args["which"] as? JsonPrimitive)?.content
                if (which !in listOf("black", "grey", "white")) fail(EngineError.ARG, "which is \"black\", \"grey\" or \"white\"")
                val ch = (args["channel"] as? JsonPrimitive)?.intOrNull ?: 0
                if (ch !in 0..2) fail(EngineError.ARG, "channel 0..2")
                val lv = (0..2).map { k -> (cur.paramAt("levels[$ch][$k]") as JsonPrimitive).double }.toMutableList()
                val v = m[0]
                when (which) {
                    "black" -> lv[0] = if (v > lv[1]) lv[1] - 1.1920929E-7 else v
                    "grey" -> if (!(v < lv[0] || v > lv[2])) lv[1] = v
                    else -> lv[2] = if (v < lv[1]) lv[1] + 1.1920929E-7 else v
                }
                s.setParams(op, priority, (0..2).associate { "levels[$ch][$it]" to JsonPrimitive(lv[it]) }, merge = false)
                buildJsonObject { put("picked", picked); put("levels", JsonArray(lv.map { JsonPrimitive(it) })); put("changed", true) }
            }
            "rgbcurve" to "set_nodes_from_area" -> {
                val ch = (args["channel"] as? JsonPrimitive)?.intOrNull ?: 0
                if (ch !in 0..2) fail(EngineError.ARG, "channel 0..2")
                val d = def(op).defaultParams
                val n0 = (d.paramAt("curve_num_nodes[$ch]") as JsonPrimitive).int
                val nodes = (0 until n0).map { k -> (d.paramAt("curve_nodes[$ch][$k].x") as JsonPrimitive).double to (d.paramAt("curve_nodes[$ch][$k].y") as JsonPrimitive).double }.toMutableList()
                for (v in listOf(lum(input.min), lum(input.mean), lum(input.max))) {
                    val x = v.coerceIn(0.0, 1.0)
                    val at = nodes.indexOfFirst { it.first > x }.let { if (it < 0) nodes.size else it }
                    nodes.add(at, x to x)
                }
                if (nodes.size == 5) nodes.add(2, (nodes[1].first + (nodes[3].first - nodes[1].first) / 2) to (nodes[1].second + (nodes[3].second - nodes[1].second) / 2))
                val vals = linkedMapOf<String, JsonPrimitive>("curve_num_nodes[$ch]" to JsonPrimitive(nodes.size))
                nodes.forEachIndexed { k, (x, y) -> vals["curve_nodes[$ch][$k].x"] = JsonPrimitive(x); vals["curve_nodes[$ch][$k].y"] = JsonPrimitive(y) }
                s.setParams(op, priority, vals, merge = false)
                buildJsonObject { put("picked", picked); put("nodes", JsonArray(nodes.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) })) }
            }
            "channelmixerrgb" to "illuminant_from_area", "channelmixerrgb" to "spot" -> {
                if (action == "spot" && ((args["mode"] as? JsonPrimitive)?.content ?: "correction") != "correction")
                    fail(EngineError.ARG, "mode: only \"correction\" is built")
                // the stand-in: the illuminant's chromaticity is the picked colour's (its picture is not adapted)
                val sum = max(1e-6, m[0] + m[1] + m[2])
                val x = m[0] / sum; val y = m[1] / sum
                val vals = linkedMapOf<String, JsonPrimitive>("x" to JsonPrimitive(x), "y" to JsonPrimitive(y))
                if (cur.containsKey("illuminant")) vals["illuminant"] = JsonPrimitive("DT_ILLUMINANT_CUSTOM")
                s.setParams(op, priority, vals, merge = false)
                buildJsonObject { put("picked", picked); put("x", x); put("y", y); put("illuminant", "DT_ILLUMINANT_CUSTOM") }
            }
            else -> fail(EngineError.NOT_FOUND, "$op has no action '$action'")
        }
    }

    // ------------------------------------------------------------ 6.6 blending (as data; the stand-in does not blend)

    private val sceneChannels = listOf("g_in", "R_in", "G_in", "B_in", "Jz_in", "Cz_in", "hz_in", "g_out", "R_out", "G_out", "B_out", "Jz_out", "Cz_out", "hz_out")
    private val displayChannels = listOf("g_in", "R_in", "G_in", "B_in", "H_in", "S_in", "l_in", "g_out", "R_out", "G_out", "B_out", "H_out", "S_out", "l_out")
    private val labChannels = listOf("L_in", "a_in", "b_in", "C_in", "h_in", "L_out", "a_out", "b_out", "C_out", "h_out")

    private fun channelsOf(cs: String) = when (cs) { "lab" -> labChannels; "rgb_display" -> displayChannels; "rgb_scene" -> sceneChannels; else -> emptyList() }

    private fun parametricDefaults(cs: String) = JsonObject(channelsOf(cs).associateWith { ch ->
        buildJsonObject {
            put("active", false); put("invert", false)
            put("range", JsonArray(listOf(0.0, 0.0, 1.0, 1.0).map { JsonPrimitive(it) }))
            put("boost", if (cs == "rgb_scene" && (ch.startsWith("Jz") || ch.startsWith("Cz"))) -6.64385619 else 0.0)
        }
    })

    /** desktop's defaults (develop/blend.c _default_blendop_params); the stand-in blends in "rgb_scene" */
    private fun defaultBlend(supported: Boolean): JsonObject = buildJsonObject {
        put("supported", supported)
        if (!supported) return@buildJsonObject
        put("masks", "off"); put("mask_mode_flags", JsonArray(emptyList()))
        put("colorspace", "default"); put("effective_colorspace", "rgb_scene")
        put("mode", "normal"); put("reverse", false); put("parameter", 0.0); put("opacity", 100.0)
        put("combine", buildJsonObject { put("invert", false); put("mode", "exclusive") })
        put("feathering_radius", 0.0); put("feathering_guide", "input_after_blur"); put("blur_radius", 0.0)
        put("contrast", 0.0); put("brightness", 0.0); put("details", 0.0)
        put("parametric", parametricDefaults("rgb_scene"))
        put("raster", JsonNull); put("mask_id", JsonNull); put("mask_lock", false)
    }

    override suspend fun blendGet(op: String, priority: Int): Blend = onEngine {
        val s = requireSession()
        val d = def(op)
        s.inst(op, priority)
        Blend(s.live()[op to priority]?.blend ?: defaultBlend(d.supportsBlending))
    }

    private val maskModes = mapOf("off" to listOf<String>(), "uniform" to listOf("enabled"), "drawn" to listOf("enabled", "drawn"),
        "parametric" to listOf("enabled", "parametric"), "drawn_parametric" to listOf("enabled", "drawn", "parametric"),
        "raster" to listOf("enabled", "raster"), "flexi" to listOf("enabled", "flexi"))
    private val displayModes = listOf("normal", "average", "difference", "normal_bounded", "lighten", "addition", "screen", "darken",
        "subtract", "multiply", "overlay", "softlight", "hardlight", "vividlight", "linearlight", "pinlight")
    private fun modesOf(cs: String) = when (cs) {
        "lab" -> displayModes + listOf("lab_lightness", "lab_a", "lab_b", "lab_color", "hue", "color", "coloradjustment", "lightness", "chromaticity")
        "rgb_display" -> displayModes + listOf("rgb_red", "rgb_green", "rgb_blue", "hsv_value", "hsv_color", "hue", "color", "coloradjustment", "lightness", "chromaticity")
        else -> listOf("normal", "average", "difference", "multiply", "divide", "addition", "subtract", "geometric_mean", "harmonic_mean",
            "rgb_red", "rgb_green", "rgb_blue", "lightness", "chromaticity")
    }

    override suspend fun blendSet(op: String, priority: Int, values: Map<String, JsonElement>) = onEngine {
        val s = requireWritable()
        val d = def(op)
        s.inst(op, priority)
        if (!d.supportsBlending) fail(EngineError.ARG, "$op has no blending")
        if (values.isEmpty()) fail(EngineError.ARG, "blend_set takes an object {path: value, ...}")
        val b = (s.live()[op to priority]?.blend ?: defaultBlend(true)).toMutableMap()
        fun str(k: String, v: JsonElement) = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(EngineError.TYPE, "$k takes a name")
        fun bool(k: String, v: JsonElement) = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fail(EngineError.TYPE, "$k takes true or false")
        fun num(k: String, v: JsonElement, lo: Double, hi: Double): Double {
            val x = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail(EngineError.TYPE, "$k takes a number")
            if (x < lo || x > hi) fail(EngineError.RANGE, "$k: $x outside $lo..$hi")
            return x
        }
        fun cs() = (b["effective_colorspace"] as JsonPrimitive).content
        val ordered = values.entries.sortedBy { if (it.key == "colorspace") 0 else 1 }
        for ((k, v) in ordered) {
            when {
                k == "masks" -> { val m = str(k, v); val f = maskModes[m] ?: fail(EngineError.ARG, "masks: unknown mode '$m'")
                    if (m == "raster") fail(EngineError.ARG, "masks: raster needs a raster mask source")
                    b["masks"] = JsonPrimitive(m); b["mask_mode_flags"] = JsonArray(f.map { JsonPrimitive(it) }) }
                k == "colorspace" -> { val c = str(k, v)
                    if (c !in listOf("default", "lab", "rgb_display", "rgb_scene")) fail(EngineError.ARG, "colorspace '$c' is not offered for $op")
                    val eff = if (c == "default") "rgb_scene" else c
                    if (eff != cs()) { b["parametric"] = parametricDefaults(eff); b["mode"] = JsonPrimitive("normal"); b["reverse"] = JsonPrimitive(false); b["parameter"] = JsonPrimitive(0.0) }
                    b["colorspace"] = JsonPrimitive(c); b["effective_colorspace"] = JsonPrimitive(eff) }
                k == "mode" -> { val m = str(k, v); if (m !in modesOf(cs())) fail(EngineError.ARG, "mode '$m' is not offered in this blend colour space"); b["mode"] = JsonPrimitive(m) }
                k == "reverse" -> b["reverse"] = JsonPrimitive(bool(k, v))
                k == "opacity" -> b[k] = JsonPrimitive(num(k, v, 0.0, 100.0))
                k == "parameter" -> b[k] = JsonPrimitive(num(k, v, -18.0, 18.0))
                k == "feathering_radius" -> b[k] = JsonPrimitive(num(k, v, 0.0, 250.0))
                k == "blur_radius" -> b[k] = JsonPrimitive(num(k, v, 0.0, 100.0))
                k == "contrast" || k == "brightness" || k == "details" -> b[k] = JsonPrimitive(num(k, v, -1.0, 1.0))
                k == "feathering_guide" -> { val g = str(k, v)
                    if (g !in listOf("input_before_blur", "output_before_blur", "input_after_blur", "output_after_blur")) fail(EngineError.ARG, "feathering_guide: unknown '$g'")
                    b[k] = JsonPrimitive(g) }
                k == "combine.invert" -> b["combine"] = JsonObject((b["combine"] as JsonObject) + ("invert" to JsonPrimitive(bool(k, v))))
                k == "combine.mode" -> { val m = str(k, v); if (m != "inclusive" && m != "exclusive") fail(EngineError.ARG, "combine.mode: inclusive or exclusive")
                    b["combine"] = JsonObject((b["combine"] as JsonObject) + ("mode" to JsonPrimitive(m))) }
                k == "mask_lock" -> b[k] = JsonPrimitive(bool(k, v))
                k.startsWith("parametric.") -> {
                    val parts = k.removePrefix("parametric.").split(".", limit = 2)
                    val chans = (b["parametric"] as JsonObject).toMutableMap()
                    val ch = chans[parts[0]] as? JsonObject
                    if (ch == null || parts.size < 2) fail(EngineError.NOT_FOUND, "no parametric channel in '$k'")
                    val c = ch.toMutableMap()
                    when (parts[1]) {
                        "active", "invert" -> c[parts[1]] = JsonPrimitive(bool(k, v))
                        "boost" -> { val lo = (ch["boost"] as JsonPrimitive).double.coerceAtMost(0.0); c["boost"] = JsonPrimitive(num(k, v, lo, lo + 18.0)) }
                        "range" -> { val a = (v as? JsonArray)?.takeIf { it.size == 4 } ?: fail(EngineError.TYPE, "$k takes [a, b, c, d]")
                            val r = a.map { num(k, it, 0.0, 1.0) }
                            if (!(r[0] <= r[1] && r[1] <= r[2] && r[2] <= r[3])) fail(EngineError.RANGE, "$k: the points must be in order")
                            c["range"] = JsonArray(r.map { JsonPrimitive(it) })
                            if (r != listOf(0.0, 0.0, 1.0, 1.0)) c["active"] = JsonPrimitive(true) }
                        else -> fail(EngineError.NOT_FOUND, "no field '${parts[1]}' of a parametric channel")
                    }
                    chans[parts[0]] = JsonObject(c)
                    b["parametric"] = JsonObject(chans)
                }
                else -> fail(EngineError.NOT_FOUND, "no blend setting '$k'")
            }
        }
        val blend = JsonObject(b)
        val key = op to priority
        val cur = s.live()[key]!!
        val canMerge = s.openGroup == key && s.end == s.items.size && s.items.lastOrNull()?.let { it.op == op && it.priority == priority } == true
        if (canMerge) s.items = s.items.dropLast(1) + s.items.last().copy(enabled = true, blend = blend)
        else { s.beginChange(); s.addItem(op, priority, true, cur.params, blend); s.openGroup = key }
    }

    // ------------------------------------------------------------ 6.7 drawn masks (as data; nothing is rendered)
    //
    // The stand-in keeps forms, groups and the instances' masks as data, with undo, and checks arguments as the
    // bridge does; it draws no mask on its picture, keeps a brush stroke's samples as its points (the bridge
    // simplifies them as desktop's brush tool does) and records no history item for a form no instance uses
    // (the bridge records one for darktable's mask manager). Its XMP does not carry masks.

    private fun Session.form(id: Int) = forms.firstOrNull { it.id == id }

    private fun Session.rootOf(op: String, priority: Int): Int? =
        ((live()[op to priority]?.blend?.get("mask_id")) as? JsonPrimitive)?.intOrNull

    private fun Session.treeHas(root: Int?, id: Int, depth: Int = 0): Boolean {
        if (root == null || depth > 8) return false
        if (root == id) return true
        val g = form(root) ?: return false
        val members = (g.geometry["members"] as? JsonArray)?.map { (it as JsonObject)["id"]!!.jsonPrimitive.int } ?: return false
        return members.any { it == id || treeHas(it, id, depth + 1) }
    }

    private fun pos(g: JsonObject, k: String, need: Boolean): JsonArray? {
        val v = g[k] ?: if (need) fail(EngineError.ARG, "geometry: \"$k\" missing") else return null
        val a = v as? JsonArray ?: fail(EngineError.TYPE, "geometry: \"$k\" takes [x, y]")
        if (a.size != 2) fail(EngineError.TYPE, "geometry: \"$k\" takes [x, y]")
        a.forEach { val x = (it as? JsonPrimitive)?.doubleOrNull ?: fail(EngineError.TYPE, "geometry: \"$k\" takes numbers")
            if (x < -1.0 || x > 2.0) fail(EngineError.RANGE, "geometry: \"$k\" outside -1..2") }
        return a
    }

    private fun num(g: JsonObject, k: String, lo: Double, hi: Double) {
        val v = g[k] ?: return
        val x = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail(EngineError.TYPE, "geometry: \"$k\" takes a number")
        if (x < lo || x > hi) fail(EngineError.RANGE, "geometry: \"$k\" $x outside $lo..$hi")
    }

    /** checked as the bridge checks it; returns the geometry the form keeps */
    private fun checkGeometry(type: String, g: JsonObject): JsonObject {
        when (type) {
            "circle" -> { pos(g, "center", true); num(g, "radius", 0.0005, 1.0); num(g, "border", 0.0005, 1.0) }
            "ellipse" -> { pos(g, "center", true); num(g, "rotation", -360.0, 360.0); num(g, "border", 0.001, 1.0) }
            "gradient" -> { pos(g, "anchor", true); num(g, "rotation", -360.0, 360.0); num(g, "compression", 0.001, 1.0); num(g, "steepness", 0.0, 1.0) }
            "path", "brush" -> {
                val stroke = g["stroke"] as? JsonArray
                if (type == "brush" && stroke != null) {
                    if (stroke.isEmpty()) fail(EngineError.ARG, "geometry: \"stroke\" takes [[x, y], ...]")
                    return buildJsonObject { put("points", JsonArray(stroke.map { buildJsonObject { put("corner", it) } })) }
                }
                val pts = g["points"] as? JsonArray
                val need = if (type == "brush") 2 else 3
                if (pts == null || pts.size < need) fail(EngineError.ARG, "geometry: \"points\" takes at least $need points")
                pts.forEach { p -> pos(p as? JsonObject ?: fail(EngineError.TYPE, "geometry: a point is not an object"), "corner", true) }
            }
            else -> fail(EngineError.ARG, "type is circle, ellipse, gradient, path or brush")
        }
        return g
    }

    override suspend fun masks(): List<MaskForm> = onEngine {
        val s = requireSession()
        s.forms.map { f ->
            val users = s.insts.filter { s.treeHas(s.rootOf(it.op, it.priority), f.id) }
                .map { MaskUser(it.op, it.priority, s.rootOf(it.op, it.priority) == f.id) }
            MaskForm(f.id, f.type, f.name, 7, f.geometry, users, retouch = f.retouch)
        }
    }

    override suspend fun maskCreate(type: String, geometry: JsonObject, name: String?): Int = onEngine {
        val s = requireWritable()
        val g = checkGeometry(type, geometry)
        s.beginChange(truncate = false)
        val id = s.nextFormId++
        val n = name?.takeIf { it.isNotEmpty() } ?: "$type #${s.forms.count { it.type == type } + 1}"
        s.forms = s.forms + FakeForm(id, type, n, g)
        id
    }

    /** a history item for the first instance whose mask holds [id], as the bridge records one */
    private fun Session.recordFor(id: Int) {
        val user = insts.firstOrNull { treeHas(rootOf(it.op, it.priority), id) } ?: return
        val cur = live()[user.op to user.priority]!!
        addItem(user.op, user.priority, true, cur.params, cur.blend)
    }

    override suspend fun maskUpdate(id: Int, geometry: JsonObject) = onEngine {
        val s = requireWritable()
        val f = s.form(id) ?: fail(EngineError.NOT_FOUND, "no form $id")
        if (f.type == "group") fail(EngineError.ARG, "form $id is a group")
        // a parametric element: the changed settings on top of its own (a channel stays, engine-api.md 6.9)
        val g = if (f.type == "parametric") checkParametric(JsonObject(f.geometry.filterKeys { it in listOf("channel", "in", "out", "boost", "show_output") } + geometry))
            else checkGeometry(f.type, geometry)
        s.beginChange()
        s.forms = s.forms.map { if (it.id == id) it.copy(geometry = g) else it }
        s.recordFor(id)
    }

    override suspend fun maskRename(id: Int, name: String) = onEngine {
        val s = requireWritable()
        s.form(id) ?: fail(EngineError.NOT_FOUND, "no form $id")
        if (name.isEmpty()) fail(EngineError.ARG, "empty name")
        s.beginChange()
        s.forms = s.forms.map { if (it.id == id) it.copy(name = name) else it }
        s.recordFor(id)
    }

    private fun withoutMember(g: JsonObject, id: Int): JsonObject =
        JsonObject(g + ("members" to JsonArray((g["members"] as JsonArray).filter { (it as JsonObject)["id"]!!.jsonPrimitive.int != id })))

    override suspend fun maskDelete(id: Int) = onEngine {
        val s = requireWritable()
        s.form(id) ?: fail(EngineError.NOT_FOUND, "no form $id")
        s.insts.firstOrNull { s.rootOf(it.op, it.priority) == id }?.let { fail(EngineError.ARG, "form $id is the mask of ${it.op} ${it.priority}: switch its mask off first") }
        s.beginChange()
        s.recordFor(id)
        s.forms = s.forms.filter { it.id != id }.map { if (it.type == "group") it.copy(geometry = withoutMember(it.geometry, id)) else it }
    }

    private fun Session.maskInstance(op: String, priority: Int): Live {
        val d = def(op)
        inst(op, priority)
        if (!d.supportsBlending) fail(EngineError.ARG, "$op has no drawn masks")
        return live()[op to priority]!!
    }

    private fun memberJson(id: Int, m: MaskMember, base: JsonObject? = null): JsonObject {
        m.opacity?.let { if (it < 0.0 || it > 1.0) fail(EngineError.RANGE, "opacity $it outside 0..1") }
        return buildJsonObject {
            put("id", id)
            put("inverse", m.inverse ?: (base?.get("inverse") as? JsonPrimitive)?.booleanOrNull ?: false)
            put("opacity", m.opacity ?: (base?.get("opacity") as? JsonPrimitive)?.doubleOrNull ?: 1.0)
            put("disabled", m.disabled ?: (base?.get("disabled") as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }

    override suspend fun maskAttach(op: String, priority: Int, id: Int, member: MaskMember) = onEngine {
        val s = requireWritable()
        val cur = s.maskInstance(op, priority)
        s.form(id) ?: fail(EngineError.NOT_FOUND, "no form $id")
        val root = s.rootOf(op, priority)
        if (root == id || (root != null && (s.form(root)!!.geometry["members"] as JsonArray).any { (it as JsonObject)["id"]!!.jsonPrimitive.int == id }))
            fail(EngineError.ARG, "form $id is already in $op's mask")
        val m = memberJson(id, member)
        s.beginChange()
        var blend = (cur.blend ?: defaultBlend(true)).toMutableMap()
        val rid = root ?: s.nextFormId++.also { gid ->
            s.forms = s.forms + FakeForm(gid, "group", "group `$op'", buildJsonObject {
                put("flexi", true); put("operator", "maximum"); put("invert", false); put("bypass", false); put("opacity", 1.0)
                put("group_name", ""); put("members", JsonArray(emptyList()))
            })
            blend["mask_id"] = JsonPrimitive(gid)
        }
        // as the bridge (dtb_mask_attach, desktop's _blendop_mask_enable): ENABLED | FLEXI added to the mode's flags; a
        // combination blend_get has no name for is "other" (drawn mode with a shape: drawn + flexi)
        val flags = (((blend["mask_mode_flags"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()) + listOf("enabled", "flexi")).distinct()
        blend["masks"] = JsonPrimitive(maskModes.entries.firstOrNull { it.value.toSet() == flags.toSet() }?.key ?: "other")
        blend["mask_mode_flags"] = JsonArray(listOf("enabled", "drawn", "parametric", "raster", "flexi").filter { it in flags }.map { JsonPrimitive(it) })
        s.forms = s.forms.map { if (it.id == rid) it.copy(geometry = JsonObject(it.geometry + ("members" to JsonArray((it.geometry["members"] as JsonArray) + m)))) else it }
        s.addItem(op, priority, true, cur.params, JsonObject(blend))
    }

    override suspend fun maskDetach(op: String, priority: Int, id: Int) = onEngine {
        val s = requireWritable()
        val cur = s.maskInstance(op, priority)
        val root = s.rootOf(op, priority)
        val g = root?.let { s.form(it) }
        if (g == null || (g.geometry["members"] as JsonArray).none { (it as JsonObject)["id"]!!.jsonPrimitive.int == id })
            fail(EngineError.NOT_FOUND, "form $id is not a member of $op $priority's mask")
        s.beginChange()
        s.forms = s.forms.map { if (it.id == root) it.copy(geometry = withoutMember(it.geometry, id)) else it }
        s.addItem(op, priority, true, cur.params, cur.blend)
    }

    override suspend fun maskMemberSet(op: String, priority: Int, id: Int, member: MaskMember) = onEngine {
        val s = requireWritable()
        val cur = s.maskInstance(op, priority)
        val root = s.rootOf(op, priority)
        val g = root?.let { s.form(it) }
        val old = (g?.geometry?.get("members") as? JsonArray)?.map { it as JsonObject }?.firstOrNull { it["id"]!!.jsonPrimitive.int == id }
            ?: fail(EngineError.NOT_FOUND, "form $id is not a member of $op $priority's mask")
        val m = memberJson(id, member, old)
        s.beginChange()
        s.forms = s.forms.map { f -> if (f.id == root) f.copy(geometry = JsonObject(f.geometry + ("members" to JsonArray((f.geometry["members"] as JsonArray).map { e -> if ((e as JsonObject)["id"]!!.jsonPrimitive.int == id) m else e })))) else f }
        s.addItem(op, priority, true, cur.params, cur.blend)
    }

    override suspend fun maskGroupCreate(memberIds: List<Int>, name: String?): Int = onEngine {
        val s = requireWritable()
        memberIds.forEach { s.form(it) ?: fail(EngineError.NOT_FOUND, "no form $it") }
        s.beginChange(truncate = false)
        val id = s.nextFormId++
        s.forms = s.forms + FakeForm(id, "group", name?.takeIf { it.isNotEmpty() } ?: "group #${s.forms.count { it.type == "group" } + 1}", buildJsonObject {
            put("flexi", true); put("operator", "maximum"); put("invert", false); put("bypass", false); put("opacity", 1.0); put("group_name", "")
            put("members", JsonArray(memberIds.map { memberJson(it, MaskMember()) }))
        })
        id
    }

    override suspend fun maskGroupSet(id: Int, settings: JsonObject) = onEngine {
        val s = requireWritable()
        val g = s.form(id) ?: fail(EngineError.NOT_FOUND, "no form $id")
        if (g.type != "group") fail(EngineError.ARG, "form $id is not a flexi group")
        if (settings.isEmpty()) fail(EngineError.ARG, "no settings")
        val geo = g.geometry.toMutableMap()
        for ((k, v) in settings) when (k) {
            "operator" -> { val o = (v as? JsonPrimitive)?.content
                if (o !in listOf("maximum", "screen", "minimum", "product", "sum", "difference", "exclusion")) fail(EngineError.ARG, "operator: maximum, screen, minimum, product, sum, difference or exclusion")
                geo["operator"] = JsonPrimitive(o) }
            "invert", "bypass" -> geo[k] = JsonPrimitive((v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fail(EngineError.TYPE, "$k takes true or false"))
            "opacity" -> { val x = (v as? JsonPrimitive)?.doubleOrNull ?: fail(EngineError.TYPE, "opacity takes a number"); if (x < 0 || x > 1) fail(EngineError.RANGE, "opacity 0..1"); geo[k] = JsonPrimitive(x) }
            "name" -> geo["group_name"] = JsonPrimitive((v as? JsonPrimitive)?.content ?: fail(EngineError.ARG, "name"))
            else -> fail(EngineError.NOT_FOUND, "no group setting '$k'")
        }
        s.beginChange()
        s.forms = s.forms.map { if (it.id == id) it.copy(geometry = JsonObject(geo)) else it }
        s.recordFor(id)
    }

    // ------------------------------------------------------------ 6.9 (P3d stage 2a), as data

    override suspend fun historyGroupBegin() = onEngine {
        val s = requireWritable()
        if (s.histGroup) fail(EngineError.STATE, "a history group is already open")
        s.openGroup = null
        s.histGroup = true; s.histGroupFirst = -1
    }

    override suspend fun historyGroupEnd(keep: Boolean) = onEngine {
        val s = requireSession()
        if (!s.histGroup) fail(EngineError.STATE, "no history group is open")
        val changed = s.histGroupFirst >= 0
        s.histGroup = false; s.histGroupFirst = -1; s.openGroup = null
        if (changed && !keep) s.undo.removeLastOrNull()?.let { s.restore(it) }
    }

    private fun Session.members(g: Int): List<JsonObject> =
        ((form(g)?.geometry?.get("members")) as? JsonArray)?.map { it as JsonObject } ?: emptyList()

    private fun Session.setMembers(g: Int, m: List<JsonObject>) {
        forms = forms.map { if (it.id == g) it.copy(geometry = JsonObject(it.geometry + ("members" to JsonArray(m)))) else it }
    }

    private fun Session.flexiGroup(id: Int): FakeForm {
        val g = form(id) ?: fail(EngineError.NOT_FOUND, "no form $id")
        if (g.type != "group") fail(EngineError.ARG, "form $id is not a flexi group")
        return g
    }

    private fun idOf(m: JsonObject) = m["id"]!!.jsonPrimitive.int

    /** a history item for the first instance whose mask holds group [g] (none: the mask manager's, not kept here) */
    private fun Session.recordGroup(g: Int) = recordFor(g)

    private fun checkParametric(g: JsonObject): JsonObject {
        val ch = (g["channel"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(EngineError.ARG, "geometry: \"channel\" missing")
        if (ch !in listOf("g", "R", "G", "B", "Jz", "Cz", "hz", "H", "S", "l", "L", "a", "b", "C", "h")) fail(EngineError.ARG, "channel '$ch'")
        fun range(k: String): JsonArray {
            val a = g[k] as? JsonArray ?: return JsonArray(listOf(0.0, 0.0, 1.0, 1.0).map { JsonPrimitive(it) })
            val v = a.map { (it as? JsonPrimitive)?.doubleOrNull ?: fail(EngineError.TYPE, "\"$k\" takes numbers") }
            if (v.size != 4) fail(EngineError.TYPE, "\"$k\" takes [a, b, c, d]")
            if (v.any { it < 0 || it > 1 } || v.zipWithNext().any { (x, y) -> x > y }) fail(EngineError.RANGE, "\"$k\": 0..1 and in order")
            return a
        }
        val inR = range("in"); val outR = range("out")
        fun active(a: JsonArray) = a.map { it.jsonPrimitive.double } != listOf(0.0, 0.0, 1.0, 1.0)
        return buildJsonObject {
            put("colorspace", "rgb_scene"); put("channel", ch); put("in", inR); put("out", outR)
            put("boost_supported", ch in listOf("g", "R", "G", "B", "Jz", "Cz"))
            put("in_active", active(inR)); put("out_active", active(outR))
            put("boost", (g["boost"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
            put("show_output", (g["show_output"] as? JsonPrimitive)?.booleanOrNull ?: false)
            put("whole_range", !active(inR) && !active(outR))
        }
    }

    override suspend fun maskAdd(op: String, priority: Int, type: String, geometry: JsonObject, name: String?, group: Int?, member: MaskMember): Int = onEngine {
        val s = requireWritable()
        val cur = s.maskInstance(op, priority)
        val g = if (type == "parametric") checkParametric(geometry) else checkGeometry(type, geometry)
        val root = s.rootOf(op, priority)
        if (group != null && group > 0) {
            s.flexiGroup(group)
            if (!s.treeHas(root, group)) fail(EngineError.ARG, "group $group is not in $op $priority's mask")
        }
        val m = memberJson(0, member)
        s.beginChange()
        val id = s.nextFormId++
        val n = name?.takeIf { it.isNotEmpty() } ?: "$type #${s.forms.count { it.type == type } + 1}"
        s.forms = s.forms + FakeForm(id, type, n, g)
        val blend = (cur.blend ?: defaultBlend(true)).toMutableMap()
        val target = if (group != null && group > 0) group else root ?: s.nextFormId++.also { gid ->
            s.forms = s.forms + FakeForm(gid, "group", "group `$op'", buildJsonObject {
                put("flexi", true); put("operator", "maximum"); put("invert", false); put("bypass", false); put("opacity", 1.0)
                put("group_name", ""); put("members", JsonArray(emptyList()))
            })
            blend["mask_id"] = JsonPrimitive(gid)
        }
        val flags = (((blend["mask_mode_flags"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()) + listOf("enabled", "flexi")).distinct()
        blend["masks"] = JsonPrimitive(maskModes.entries.firstOrNull { it.value.toSet() == flags.toSet() }?.key ?: "other")
        blend["mask_mode_flags"] = JsonArray(listOf("enabled", "drawn", "parametric", "raster", "flexi").filter { it in flags }.map { JsonPrimitive(it) })
        s.setMembers(target, s.members(target) + JsonObject(m + ("id" to JsonPrimitive(id))))
        s.addItem(op, priority, true, cur.params, JsonObject(blend))
        id
    }

    override suspend fun maskMemberMove(group: Int, id: Int, toGroup: Int, position: Int) = onEngine {
        val s = requireWritable()
        s.flexiGroup(group); s.flexiGroup(toGroup)
        val m = s.members(group).firstOrNull { idOf(it) == id } ?: fail(EngineError.NOT_FOUND, "form $id is not a member of group $group")
        if (toGroup != group) {
            if (s.members(toGroup).any { idOf(it) == id }) fail(EngineError.ARG, "form $id is already a member of group $toGroup")
            if (id == toGroup || s.treeHas(id, toGroup)) fail(EngineError.ARG, "a group cannot go into itself")
        }
        val n = s.members(toGroup).size - (if (toGroup == group) 1 else 0)
        if (position < -1 || position > n) fail(EngineError.RANGE, "position $position outside -1..$n")
        val pos = if (position < 0) n else position
        if (toGroup == group && s.members(group).indexOfFirst { idOf(it) == id } == pos) return@onEngine
        s.beginChange()
        s.setMembers(group, s.members(group).filter { idOf(it) != id })
        s.setMembers(toGroup, s.members(toGroup).toMutableList().also { it.add(pos, m) })
        s.recordGroup(group)
    }

    override suspend fun maskMemberRemove(group: Int, id: Int) = onEngine {
        val s = requireWritable()
        s.flexiGroup(group)
        if (s.members(group).none { idOf(it) == id }) fail(EngineError.NOT_FOUND, "form $id is not a member of group $group")
        s.beginChange()
        s.recordGroup(group)
        s.setMembers(group, s.members(group).filter { idOf(it) != id })
    }

    override suspend fun maskMemberUpdate(group: Int, id: Int, member: MaskMember) = onEngine {
        val s = requireWritable()
        s.flexiGroup(group)
        val old = s.members(group).firstOrNull { idOf(it) == id } ?: fail(EngineError.NOT_FOUND, "form $id is not a member of group $group")
        if (member.toJson().isEmpty()) fail(EngineError.ARG, "no settings")
        if (s.form(id)?.type == "group" && (member.inverse != null || member.opacity != null))
            fail(EngineError.ARG, "form $id is a group: its invert and opacity are its own")
        val m = memberJson(id, member, old)
        s.beginChange()
        s.setMembers(group, s.members(group).map { if (idOf(it) == id) m else it })
        s.recordGroup(group)
    }

    override suspend fun maskGroupWrap(group: Int, ids: List<Int>, operator: String?): Int = onEngine {
        val s = requireWritable()
        s.flexiGroup(group)
        val op = operator?.takeIf { it.isNotEmpty() } ?: "maximum"
        if (op !in listOf("maximum", "screen", "minimum", "product", "sum", "difference", "exclusion")) fail(EngineError.ARG, "operator '$op'")
        if (ids.isEmpty()) fail(EngineError.ARG, "members: a non-empty array of form ids")
        if (ids.toSet().size != ids.size) fail(EngineError.ARG, "a form is listed twice")
        val members = s.members(group)
        ids.forEach { i -> if (members.none { idOf(it) == i }) fail(EngineError.NOT_FOUND, "form $i is not a member of group $group") }
        s.beginChange()
        val gid = s.nextFormId++
        val picked = members.filter { idOf(it) in ids }
        s.forms = s.forms + FakeForm(gid, "group", "group #${s.forms.count { it.type == "group" } + 1}", buildJsonObject {
            put("flexi", true); put("operator", op); put("invert", false); put("bypass", false); put("opacity", 1.0)
            put("group_name", ""); put("members", JsonArray(picked))
        })
        val top = members.indexOfLast { idOf(it) in ids }
        val out = ArrayList<JsonObject>()
        members.forEachIndexed { k, m ->
            if (idOf(m) !in ids) out += m
            if (k == top) out += memberJson(gid, MaskMember())
        }
        s.setMembers(group, out)
        s.recordGroup(group)
        gid
    }

    override suspend fun renderTimes(): RenderTimes = onEngine {
        requireSession()
        lastTimes ?: RenderTimes(null, 0.0, emptyList())
    }

    // ---- 6.10 (P3m): retouch's own shapes, kept as the bridge keeps them: forms plus the module's rt_forms table

    private fun rtShort(c: String?, prefix: String) = c?.removePrefix(prefix)?.lowercase() ?: "unknown"
    private fun JsonObject.rtStr(k: String) = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.rtNum(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    private fun JsonObject.rtInt(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
    private fun JsonObject.rtRgb(k: String) = (this[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 } ?: listOf(0.0, 0.0, 0.0)

    private fun Session.rtParams(op: String, priority: Int): JsonObject {
        if (op != "retouch") fail(EngineError.ARG, "$op is not retouch")
        inst(op, priority)
        return live()[op to priority]!!.params
    }

    private fun rtTable(p: JsonObject) = (p["rt_forms"] as JsonArray).map { it as JsonObject }

    override suspend fun retouch(op: String, priority: Int): RetouchState = onEngine {
        val s = requireSession()
        val p = s.rtParams(op, priority)
        val shapes = rtTable(p).mapIndexedNotNull { i, e ->
            val id = e.rtInt("formid")
            val f = if (id > 0) s.form(id) else null
            f?.let {
                RetouchShape(id, i, f.type, f.name, f.geometry["source"] != null, f.geometry, f.opacity, e.rtInt("scale"),
                    rtShort(e.rtStr("algorithm"), "DT_IOP_RETOUCH_"), rtShort(e.rtStr("blur_type"), "DT_IOP_RETOUCH_BLUR_"), e.rtNum("blur_radius"),
                    rtShort(e.rtStr("fill_mode"), "DT_IOP_RETOUCH_FILL_"), e.rtRgb("fill_color"), e.rtNum("fill_brightness"))
            }
        }
        RetouchState(if (shapes.isEmpty()) null else RT_GROUP, shapes.size, rtTable(p).size, rtShort(p.rtStr("algorithm"), "DT_IOP_RETOUCH_"),
            p.rtInt("num_scales"), p.rtInt("curr_scale"), p.rtInt("merge_from_scale"), rtShort(p.rtStr("blur_type"), "DT_IOP_RETOUCH_BLUR_"),
            p.rtNum("blur_radius"), rtShort(p.rtStr("fill_mode"), "DT_IOP_RETOUCH_FILL_"), p.rtRgb("fill_color"), p.rtNum("fill_brightness"), shapes)
    }

    private fun rtAlgo(a: String) = JsonPrimitive("DT_IOP_RETOUCH_" + a.uppercase())

    override suspend fun retouchAdd(op: String, priority: Int, type: String, geometry: JsonObject, source: Pair<Double, Double>?, algorithm: String?): Int = onEngine {
        val s = requireWritable()
        val d = def(op)
        var p = s.rtParams(op, priority)
        if (type !in listOf("circle", "ellipse", "path", "brush")) fail(EngineError.ARG, "type is circle, ellipse, path or brush")
        val algo = algorithm ?: rtShort(p.rtStr("algorithm"), "DT_IOP_RETOUCH_")
        if (algo !in RetouchState.ALGORITHMS) fail(EngineError.ARG, "algorithm: unknown name '$algo'")
        val clone = algo == "clone" || algo == "heal"
        if (clone && source == null) fail(EngineError.ARG, "clone and heal need a source")
        val g = checkGeometry(type, geometry)
        val k = rtTable(p).indexOfFirst { it.rtInt("formid") <= 0 }
        if (k < 0) fail(EngineError.RANGE, "retouch holds ${rtTable(p).size} shapes already")
        s.beginChange()
        val id = s.nextFormId++
        val geo = if (clone) JsonObject(g + ("source" to JsonArray(listOf(JsonPrimitive(source!!.first), JsonPrimitive(source.second))))) else g
        s.forms = s.forms + FakeForm(id, type, "$type #${s.forms.count { it.type == type } + 1}", geo, retouch = true)
        p = setPath(d, p, "algorithm", rtAlgo(algo))
        val e = "rt_forms[$k]."
        p = setPath(d, p, e + "formid", JsonPrimitive(id))
        p = setPath(d, p, e + "scale", JsonPrimitive(p.rtInt("curr_scale")))
        p = setPath(d, p, e + "algorithm", rtAlgo(algo))
        p = setPath(d, p, e + "distort_mode", JsonPrimitive(2))
        if (algo == "blur") {
            p = setPath(d, p, e + "blur_type", JsonPrimitive(p.rtStr("blur_type")!!))
            p = setPath(d, p, e + "blur_radius", JsonPrimitive(p.rtNum("blur_radius")))
        } else if (algo == "fill") {
            p = setPath(d, p, e + "fill_mode", JsonPrimitive(p.rtStr("fill_mode")!!))
            p.rtRgb("fill_color").forEachIndexed { c, v -> p = setPath(d, p, e + "fill_color[$c]", JsonPrimitive(v)) }
            p = setPath(d, p, e + "fill_brightness", JsonPrimitive(p.rtNum("fill_brightness")))
        }
        s.addItem(op, priority, true, p)
        id
    }

    override suspend fun retouchShapeSet(op: String, priority: Int, id: Int, changes: JsonObject) = onEngine {
        val s = requireWritable()
        val d = def(op)
        var p = s.rtParams(op, priority)
        val f = s.form(id)?.takeIf { it.retouch } ?: fail(EngineError.NOT_FOUND, "form $id is not a shape of retouch $priority")
        val k = rtTable(p).indexOfFirst { it.rtInt("formid") == id }.takeIf { it >= 0 } ?: fail(EngineError.NOT_FOUND, "form $id has no entry")
        val e = "rt_forms[$k]."
        val old = rtShort(rtTable(p)[k].rtStr("algorithm"), "DT_IOP_RETOUCH_")
        var algo = old
        var geo = f.geometry
        var opacity = f.opacity
        var shape = false
        changes["algorithm"]?.let { a ->
            algo = (a as? JsonPrimitive)?.content ?: fail(EngineError.TYPE, "algorithm: a name")
            if (algo !in RetouchState.ALGORITHMS) fail(EngineError.ARG, "algorithm: unknown name '$algo'")
            if ((algo == "clone" || algo == "heal") != (old == "clone" || old == "heal")) fail(EngineError.ARG, "a $old shape cannot become $algo")
            p = setPath(d, p, e + "algorithm", rtAlgo(algo)); p = setPath(d, p, "algorithm", rtAlgo(algo))
        }
        for ((key, v) in changes) {
            val prim = v as? JsonPrimitive
            when (key) {
                "algorithm" -> {}
                "geometry" -> { geo = JsonObject(checkGeometry(f.type, v as? JsonObject ?: fail(EngineError.TYPE, "geometry: an object")) + (f.geometry["source"]?.let { mapOf("source" to it) } ?: emptyMap())); shape = true }
                "source" -> { if (f.geometry["source"] == null) fail(EngineError.ARG, "source: shape $id has none"); geo = JsonObject(geo + ("source" to v)); shape = true }
                "opacity" -> { opacity = (prim?.doubleOrNull ?: fail(EngineError.TYPE, "opacity: a number")).also { if (it !in 0.0..1.0) fail(EngineError.RANGE, "opacity") }.coerceIn(0.05, 1.0); shape = true }
                "blur_type", "blur_radius" -> {
                    if (algo != "blur") fail(EngineError.ARG, "$key: shape $id is not a blur shape")
                    val x = if (key == "blur_type") JsonPrimitive("DT_IOP_RETOUCH_BLUR_" + (prim?.content ?: "").uppercase()) else prim!!
                    if (key == "blur_radius" && (x.doubleOrNull ?: -1.0) !in 0.1..200.0) fail(EngineError.RANGE, "blur_radius")
                    p = setPath(d, p, e + key, x); p = setPath(d, p, key, x)
                }
                "fill_mode", "fill_brightness" -> {
                    if (algo != "fill") fail(EngineError.ARG, "$key: shape $id is not a fill shape")
                    val x = if (key == "fill_mode") JsonPrimitive("DT_IOP_RETOUCH_FILL_" + (prim?.content ?: "").uppercase()) else prim!!
                    p = setPath(d, p, e + key, x); p = setPath(d, p, key, x)
                }
                "fill_color" -> {
                    if (algo != "fill") fail(EngineError.ARG, "$key: shape $id is not a fill shape")
                    val c = (v as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: fail(EngineError.TYPE, "fill_color") } ?: fail(EngineError.TYPE, "fill_color")
                    if (c.size != 3 || c.any { it !in 0.0..1.0 }) fail(EngineError.RANGE, "fill_color")
                    c.forEachIndexed { i, x -> p = setPath(d, p, e + "fill_color[$i]", JsonPrimitive(x)); p = setPath(d, p, "fill_color[$i]", JsonPrimitive(x)) }
                }
                else -> fail(EngineError.NOT_FOUND, "no field '$key'")
            }
        }
        if (shape) {
            s.beginChange()
            s.forms = s.forms.map { if (it.id == id) it.copy(geometry = geo, opacity = opacity) else it }
            s.addItem(op, priority, true, p)
        } else if (p != s.live()[op to priority]!!.params) {
            s.setParams(op, priority, emptyMap(), merge = true)
            s.items = s.items.dropLast(1) + s.items.last().copy(params = p)
        }
    }

    override suspend fun retouchShapeRemove(op: String, priority: Int, id: Int) = onEngine {
        val s = requireWritable()
        val d = def(op)
        var p = s.rtParams(op, priority)
        s.form(id)?.takeIf { it.retouch } ?: fail(EngineError.NOT_FOUND, "form $id is not a shape of retouch $priority")
        val table = rtTable(p)
        val blank = (d.defaultParams["rt_forms"] as JsonArray)[0]
        val kept = table.filter { it.rtInt("formid") != id }
        p = JsonObject(p + ("rt_forms" to JsonArray(kept + List(table.size - kept.size) { blank })))
        s.beginChange()
        s.forms = s.forms.filter { it.id != id }
        s.addItem(op, priority, true, p)
    }

    override suspend fun retouchSelect(op: String, priority: Int, id: Int) = onEngine {
        val s = requireWritable()
        val d = def(op)
        var p = s.rtParams(op, priority)
        val e = rtTable(p).firstOrNull { it.rtInt("formid") == id } ?: fail(EngineError.NOT_FOUND, "form $id is not a shape of retouch $priority")
        val algo = rtShort(e.rtStr("algorithm"), "DT_IOP_RETOUCH_")
        p = setPath(d, p, "algorithm", rtAlgo(algo))
        val keys = when (algo) { "blur" -> listOf("blur_type", "blur_radius"); "fill" -> listOf("fill_mode", "fill_brightness"); else -> emptyList() }
        for (k in keys) p = setPath(d, p, k, e[k] as JsonPrimitive)
        if (algo == "fill") e.rtRgb("fill_color").forEachIndexed { i, x -> p = setPath(d, p, "fill_color[$i]", JsonPrimitive(x)) }
        if (p != s.live()[op to priority]!!.params) {
            s.setParams(op, priority, emptyMap(), merge = true)
            s.items = s.items.dropLast(1) + s.items.last().copy(params = p)
        }
    }

    // ------------------------------------------------------------ P4c: retouch's focus (a stand-in)

    /** The focused retouch instance and whether it is paused; null when none has the focus. */
    private var rtFocus: Triple<String, Int, Boolean>? = null

    /** Calls to [retouchFocus] by action, for tests. */
    val retouchFocusCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * The modules left out while retouch has the focus: crop instances that are on (the stand-in renders no crop, so
     * the picture keeps its size; the frame is crop's box).
     */
    private fun Session.focusLeftOut(): List<Pair<String, Int>> {
        val f = rtFocus?.takeIf { !it.third } ?: return emptyList()
        if (live()[f.first to f.second] == null) return emptyList()
        return live().filter { (k, l) -> k.first == "crop" && l.enabled }.keys.toList()
    }

    override suspend fun retouchFocus(op: String, priority: Int, action: String): RetouchFocus = onEngine {
        val s = requireSession()
        s.inst(op, priority)
        if (op != "retouch") fail(EngineError.ARG, "$op is not retouch")
        retouchFocusCalls.add(action)
        val ours = rtFocus?.let { it.first == op && it.second == priority } == true
        when (action) {
            "begin" -> rtFocus = Triple(op, priority, false)
            "end" -> if (ours) rtFocus = null
            "pause" -> if (ours) rtFocus = Triple(op, priority, true)
            "resume" -> if (ours) rtFocus = Triple(op, priority, false)
            "state" -> {}
            else -> fail(EngineError.ARG, "action: begin, end, pause, resume or state")
        }
        val f = rtFocus?.takeIf { it.first == op && it.second == priority }
        val out = s.focusLeftOut()
        val frame = out.firstOrNull()?.let { k ->
            val p = s.live()[k]!!.params
            fun v(n: String, d: Double) = (p[n] as? JsonPrimitive)?.doubleOrNull ?: d
            val l = v("cx", 0.0); val t = v("cy", 0.0); val r = v("cw", 1.0); val b = v("ch", 1.0)
            listOf(l to t, r to t, r to b, l to b)
        }
        RetouchFocus(f != null, f?.third == true, out.map { InstanceRef(it.first, it.second) }, frame)
    }

    // ------------------------------------------------------------ 6.11 (P3n): rotate and perspective (a stand-in)

    /** The stand-in's structure: lines as fractions of the picture; no geometry (the real engine runs ashift.c). */
    private class FakePersp(val op: String, val priority: Int) {
        var focused = false
        var paused = false
        var method = "none"
        var lines = mutableListOf<MutableList<Double>>()  // x1, y1, x2, y2
        var kinds = mutableListOf<String>()
        var drawnSaved = mutableListOf<List<Double>>()
        var press: Pair<Double, Double>? = null
        var dragLine = -1
        var dragEnd = -1     // 0: p1, 1: p2
        var area = false
        var areaSelect = true
        var cropDrag = false
        var crop = listOf(0.0, 1.0, 0.0, 1.0)
        var message: String? = null
    }

    private var fp: FakePersp? = null

    private fun FakePersp.vertical() = kinds.count { it == "vertical_used" }
    private fun FakePersp.horizontal() = kinds.count { it == "horizontal_used" }

    private fun FakePersp.state(p: JsonObject, enabled: Boolean, handled: Boolean, near: Pair<Double, Double>?, r: Double): PerspectiveState {
        fun num(k: String) = (p[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val drawn = method == "rectangle" || method == "drawn"
        val ls = lines.indices.filter { !drawn || kinds[it].endsWith("_used") }.map { i ->
            val l = lines[i]
            val k = kinds[i]
            val color = when (k) { "vertical_used" -> "green"; "vertical_outlier" -> "red"; "horizontal_used" -> "blue"; "horizontal_outlier" -> "yellow"; else -> "grey" }
            GuideLine(l[0], l[1], l[2], l[3], k, color, near != null && distTo(l, near) <= r)
        }
        val hs = if (!drawn) emptyList() else lines.take(if (method == "rectangle") 2 else lines.size).flatMap { l ->
            listOf(GuideHandle(l[0], l[1], false), GuideHandle(l[2], l[3], false))
        }
        val cm = when ((p["cropmode"] as? JsonPrimitive)?.content) { "ASHIFT_CROP_OFF" -> 0; "ASHIFT_CROP_ASPECT" -> 2; else -> 1 }
        val clip = if (focused && enabled && cm != 0) listOf(crop[0] to crop[2], crop[0] to crop[3], crop[1] to crop[3], crop[1] to crop[2]) else null
        return PerspectiveState(focused && !paused, enabled, method, method == "lines_found", method == "rectangle", method == "drawn", method != "none",
            vertical(), horizontal(), false, handled, ls, hs, if (area) press else null, areaSelect, clip, crop,
            num("rotation"), num("lensshift_v"), num("lensshift_h"), num("shear"), cm, drawnSaved.size, message)
    }

    private fun distTo(l: List<Double>, q: Pair<Double, Double>): Double {
        val dx = l[2] - l[0]; val dy = l[3] - l[1]
        val t = (((q.first - l[0]) * dx + (q.second - l[1]) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(l[0] + t * dx - q.first, l[1] + t * dy - q.second)
    }

    private fun drawnKind(l: List<Double>) = if (kotlin.math.abs(l[2] - l[0]) > kotlin.math.abs(l[3] - l[1])) "horizontal_used" else "vertical_used"

    override suspend fun ashift(op: String, priority: Int, request: JsonObject): PerspectiveState = onEngine {
        val s = requireSession()
        if (op != "ashift") fail(EngineError.ARG, "$op is not rotate and perspective")
        val d = def(op)
        s.inst(op, priority)
        fun str(k: String) = (request[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun dbl(k: String) = (request[k] as? JsonPrimitive)?.doubleOrNull
        val action = str("action") ?: fail(EngineError.ARG, "expected { \"action\": ... }")
        if (action !in listOf("state", "pause", "resume") && s.readOnly) fail(EngineError.READ_ONLY, "read-only")
        var f = fp?.takeIf { it.op == op && it.priority == priority }
        if (f == null) {
            if (action != "begin") fail(EngineError.STATE, "call \"begin\" first")
            f = FakePersp(op, priority).also { fp = it }
        }
        f.message = null
        var p = s.live()[op to priority]!!.params
        var enabled = s.live()[op to priority]!!.enabled
        var handled = false
        val r = (dbl("radius") ?: 0.02).coerceAtLeast(1e-6)
        fun item() { s.beginChange(); s.addItem(op, priority, true, p); enabled = true }
        fun clean() { f.method = "none"; f.lines.clear(); f.kinds.clear() }
        fun needFocus() { if (!f.focused || f.paused) fail(EngineError.STATE, "call \"begin\" first") }
        var near: Pair<Double, Double>? = null
        when (action) {
            "begin" -> { f.focused = true; f.paused = false }
            "pause" -> if (f.focused) f.paused = true
            "resume" -> f.paused = false
            "end" -> { f.paused = false; if (f.method == "drawn") f.drawnSaved = f.lines.map { it.toList() }.toMutableList(); clean(); f.focused = false }
            "state" -> {}
            "structure" -> {
                needFocus()
                val m = str("method")
                val enhance = str("enhance") ?: "none"
                if (enhance !in listOf("none", "edges", "detail", "both")) fail(EngineError.ARG, "enhance: none, edges, detail or both")
                if (m !in listOf("lines_found", "rectangle", "drawn")) fail(EngineError.ARG, "method: lines_found, rectangle or drawn")
                val was = f.method
                if (was == "drawn") f.drawnSaved = f.lines.map { it.toList() }.toMutableList()
                clean()
                if (!(was == m && (m != "lines_found" || enhance == "none"))) {
                    f.method = m!!
                    when (m) {
                        "lines_found" -> {
                            val extra = if (enhance == "none") 0 else 1
                            val v = listOf(0.18, 0.35, 0.62, 0.81 + 0.02 * extra)
                            v.forEach { x -> f.lines += mutableListOf(x, 0.15, x + 0.03, 0.85); f.kinds += "vertical_used" }
                            f.lines += mutableListOf(0.5, 0.3, 0.6, 0.6); f.kinds += "vertical_outlier"
                            listOf(0.22, 0.5, 0.78).forEach { y -> f.lines += mutableListOf(0.1, y, 0.9, y - 0.02); f.kinds += "horizontal_used" }
                            f.lines += mutableListOf(0.2, 0.4, 0.5, 0.48); f.kinds += "horizontal_outlier"
                            f.lines += mutableListOf(0.3, 0.9, 0.36, 0.95); f.kinds += "other"
                        }
                        "rectangle" -> {
                            val q = listOf(listOf(0.2, 0.2, 0.2, 0.8), listOf(0.8, 0.2, 0.8, 0.8), listOf(0.2, 0.2, 0.8, 0.2), listOf(0.2, 0.8, 0.8, 0.8))
                            q.forEachIndexed { i, l -> f.lines += l.toMutableList(); f.kinds += if (i < 2) "vertical_used" else "horizontal_used" }
                        }
                        else -> f.drawnSaved.forEach { l -> f.lines += l.toMutableList(); f.kinds += drawnKind(l) }
                    }
                }
                item()
            }
            "fit" -> {
                needFocus()
                val axis = str("axis")
                val variant = str("variant") ?: "plain"
                if (axis !in listOf("vertical", "horizontal", "both")) fail(EngineError.ARG, "axis: vertical, horizontal or both")
                if (variant !in listOf("plain", "rotation", "shift", "no_shear")) fail(EngineError.ARG, "variant: plain, rotation, shift or no_shear")
                if (variant == "no_shear" && axis != "both") fail(EngineError.ARG, "no_shear is a variant of both")
                val enough = (axis == "horizontal" || f.vertical() >= 2) && (axis == "vertical" || f.horizontal() >= 2)
                if (f.lines.isEmpty() || !enough) f.message = "not enough structure for automatic correction\nminimum 2 lines in each relevant direction"
                else {
                    val rot = variant != "shift"
                    val shift = variant != "rotation"
                    if (rot) p = setPath(d, p, "rotation", JsonPrimitive(if (axis == "horizontal") -0.8 else 1.2))
                    if (shift && axis != "horizontal") p = setPath(d, p, "lensshift_v", JsonPrimitive(-0.21))
                    if (shift && axis != "vertical") p = setPath(d, p, "lensshift_h", JsonPrimitive(0.05))
                    if (axis == "both" && variant == "plain") p = setPath(d, p, "shear", JsonPrimitive(0.004))
                    f.crop = if (((p["cropmode"] as? JsonPrimitive)?.content ?: "") == "ASHIFT_CROP_OFF") listOf(0.0, 1.0, 0.0, 1.0) else listOf(0.04, 0.96, 0.05, 0.95)
                }
                item()
            }
            "pointer" -> {
                needFocus()
                val x = dbl("x") ?: fail(EngineError.ARG, "x")
                val y = dbl("y") ?: fail(EngineError.ARG, "y")
                val button = dbl("button")?.toInt() ?: 1
                val shift = (request["shift"] as? JsonPrimitive)?.booleanOrNull ?: false
                near = x to y
                when (str("event")) {
                    "press" -> {
                        f.press = x to y
                        val drawn = f.method == "rectangle" || f.method == "drawn"
                        if (shift && f.lines.isNotEmpty()) { f.area = true; f.areaSelect = button == 1; handled = true }
                        else if (f.lines.isEmpty() && f.method != "drawn") {
                            val cm = (p["cropmode"] as? JsonPrimitive)?.content
                            if (cm == "ASHIFT_CROP_ASPECT") { f.cropDrag = true; handled = true }
                        } else if (drawn && button == 1) {
                            for (i in f.lines.indices) for (e in 0..1) if (f.dragLine < 0 && kotlin.math.hypot(f.lines[i][2 * e] - x, f.lines[i][2 * e + 1] - y) <= r) { f.dragLine = i; f.dragEnd = e }
                            if (f.dragLine < 0 && f.method == "drawn") {
                                f.lines += mutableListOf(x, y, x, y); f.kinds += "vertical_used"; f.dragLine = f.lines.size - 1; f.dragEnd = 1
                            }
                            handled = f.dragLine >= 0
                        } else if (f.method == "drawn" && button == 3) {
                            val i = f.lines.indices.firstOrNull { distTo(f.lines[it], x to y) <= r }
                            if (i != null) { f.lines.removeAt(i); f.kinds.removeAt(i); handled = true }
                        } else if (f.method == "lines_found") {
                            for (i in f.lines.indices) if (f.kinds[i] != "other" && distTo(f.lines[i], x to y) <= r) {
                                val dir = f.kinds[i].substringBefore('_')
                                f.kinds[i] = dir + if (button == 1) "_used" else "_outlier"; handled = true
                            }
                        }
                    }
                    "move" -> {
                        val i = f.dragLine
                        if (i >= 0) { f.lines[i][2 * f.dragEnd] = x; f.lines[i][2 * f.dragEnd + 1] = y
                            if (f.method == "rectangle") { // the horizontal sides follow the corners
                                f.lines[2] = mutableListOf(f.lines[0][0], f.lines[0][1], f.lines[1][0], f.lines[1][1])
                                f.lines[3] = mutableListOf(f.lines[0][2], f.lines[0][3], f.lines[1][2], f.lines[1][3])
                            }
                            handled = true }
                        else if (f.cropDrag && f.press != null) {
                            val dx = x - f.press!!.first; val dy = y - f.press!!.second
                            val w = f.crop[1] - f.crop[0]; val h = f.crop[3] - f.crop[2]
                            val l = (f.crop[0] + dx).coerceIn(0.0, 1.0 - w); val t = (f.crop[2] + dy).coerceIn(0.0, 1.0 - h)
                            f.crop = listOf(l, l + w, t, t + h); f.press = x to y; handled = true
                        }
                    }
                    "release" -> {
                        if (f.area && f.press != null) {
                            val (ax, ay) = f.press!!
                            val (x0, x1) = minOf(ax, x) to maxOf(ax, x); val (y0, y1) = minOf(ay, y) to maxOf(ay, y)
                            for (i in f.lines.indices) {
                                val l = f.lines[i]
                                if (f.kinds[i] != "other" && minOf(l[0], l[2]) >= x0 && maxOf(l[0], l[2]) <= x1 && minOf(l[1], l[3]) >= y0 && maxOf(l[1], l[3]) <= y1)
                                    f.kinds[i] = f.kinds[i].substringBefore('_') + if (f.areaSelect) "_used" else "_outlier"
                            }
                            handled = true
                        } else if (f.dragLine >= 0) {
                            if (f.method == "drawn") f.kinds[f.dragLine] = drawnKind(f.lines[f.dragLine])
                            if (f.method == "drawn") f.drawnSaved = f.lines.map { it.toList() }.toMutableList()
                            item(); handled = true
                        } else if (f.cropDrag) { item(); handled = true }
                        else if (f.method == "drawn" && button == 3) { f.drawnSaved = f.lines.map { it.toList() }.toMutableList(); item() }
                        f.area = false; f.cropDrag = false; f.dragLine = -1; f.press = null
                    }
                    else -> fail(EngineError.ARG, "event: move, press or release")
                }
            }
            "changed" -> {
                needFocus()
                val cm = (p["cropmode"] as? JsonPrimitive)?.content
                f.crop = when (cm) { "ASHIFT_CROP_OFF" -> listOf(0.0, 1.0, 0.0, 1.0); "ASHIFT_CROP_ASPECT" -> listOf(0.1, 0.9, 0.1, 0.9); else -> listOf(0.04, 0.96, 0.05, 0.95) }
            }
            else -> fail(EngineError.ARG, "unknown action '$action'")
        }
        f.state(p, enabled, handled, near, r)
    }
}

/** White balance coefficients <-> temperature and tint for the fake (not darktable's model). */
internal object FakeWhiteBalance {
    const val T_MIN = 1901.0
    const val T_MAX = 25000.0
    const val TINT_MIN = 0.135
    const val TINT_MAX = 2.326
    /** the schema's defaults for the sample raw */
    private const val T0 = 5105.2392578125
    private const val TINT0 = 1.0506824254989624
    private val C0 = FakeRenderer.CAMERA_WB
    private const val K = 0.9

    fun toCoefficients(t: Double, tint: Double): DoubleArray {
        val s = (t / T0).pow(K)
        return doubleArrayOf(C0[0] * s, C0[1] * tint / TINT0, C0[2] / s)
    }

    fun fromCoefficients(p: JsonObject): Pair<Double, Double> {
        val r = (p["red"] as? JsonPrimitive)?.doubleOrNull ?: C0[0]
        val g = (p["green"] as? JsonPrimitive)?.doubleOrNull ?: C0[1]
        val b = (p["blue"] as? JsonPrimitive)?.doubleOrNull ?: C0[2]
        if (r <= 0 || b <= 0) return T0 to TINT0
        val s = ((r / C0[0]) / (b / C0[2])).pow(0.5)
        val t = (T0 * s.pow(1.0 / K)).coerceIn(T_MIN, T_MAX)
        val tint = (TINT0 * g / C0[1]).coerceIn(TINT_MIN, TINT_MAX)
        return t to tint
    }
}

/** Minimal PNG writer (RGBA8) for the fake export. */
internal object Png {
    fun encode(img: RenderedImage): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until img.height) {
            raw.write(0)
            raw.write(img.rgba, y * img.width * 4, img.width * 4)
        }
        val z = java.io.ByteArrayOutputStream()
        DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val len = data.size
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            val t = type.toByteArray()
            out.write(t); out.write(data)
            val crc = CRC32().apply { update(t); update(data) }.value
            out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        }
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(img.width).putInt(img.height)
            .put(8).put(6).put(0).put(0).put(0).array()
        chunk("IHDR", ihdr)
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}

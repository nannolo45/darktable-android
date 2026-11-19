// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.fake

import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.EngineError
import app.dtandroid.engine.api.EngineException
import app.dtandroid.engine.api.EngineInfo
import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.Exif
import app.dtandroid.engine.api.ExportFormat
import app.dtandroid.engine.api.ExportOptions
import app.dtandroid.engine.api.History
import app.dtandroid.engine.api.HistoryItem
import app.dtandroid.engine.api.ImageClass
import app.dtandroid.engine.api.ImageInfo
import app.dtandroid.engine.api.ModuleInstance
import app.dtandroid.engine.api.ParamPath
import app.dtandroid.engine.api.RenderSize
import app.dtandroid.engine.api.RenderedImage
import app.dtandroid.engine.api.UndoState
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
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
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
    )

    private data class Snapshot(val insts: List<Inst>, val items: List<Item>, val end: Int)

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
        /** instance whose param_set calls currently merge into the last history item */
        var openGroup: Pair<String, Int>? = null
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
    private data class Live(val enabled: Boolean, val params: JsonObject, val inHistory: Boolean)

    private fun Session.live(upTo: Int = end): Map<Pair<String, Int>, Live> {
        val m = LinkedHashMap<Pair<String, Int>, Live>()
        for (i in insts) {
            val d = catalog[i.op]!!
            m[i.op to i.priority] = Live(d.defaultEnabled || !d.canSwitch, d.defaultParams, false)
        }
        for (it in items.take(upTo)) {
            val k = it.op to it.priority
            if (k in m) m[k] = Live(it.enabled || !catalog[it.op]!!.canSwitch, it.params, true)
        }
        // P2b: as the bridge, "in_history" means the instance has an item anywhere in the history list,
        // also above the current end
        for (it in items) {
            val k = it.op to it.priority
            m[k]?.let { l -> if (!l.inHistory) m[k] = l.copy(inHistory = true) }
        }
        return m
    }

    private fun Session.snapshot() = Snapshot(insts, items, end)

    private fun Session.restore(s: Snapshot) { insts = s.insts; items = s.items; end = s.end; openGroup = null }

    /** Start a new history group: seal, record the undo state, discard history above `end`. */
    private fun Session.beginChange(truncate: Boolean = true) {
        openGroup = null
        undo.addLast(snapshot())
        while (undo.size > UNDO_LIMIT) undo.removeFirst()
        redo.clear()
        if (truncate && end < items.size) items = items.take(end)
    }

    private fun Session.addItem(op: String, priority: Int, enabled: Boolean, params: JsonObject) {
        val name = inst(op, priority).instanceName
        items = items + Item(op, priority, name, enabled, params)
        end = items.size
    }

    // ------------------------------------------------------------ 2.1 lifecycle

    override suspend fun init(paths: EnginePaths, threads: Int) = onEngine {
        if (initialised) fail(EngineError.STATE, "engine already initialised")
        if (threads < 0) fail(EngineError.ARG, "threads must be >= 0")
        initThreads = if (threads == 0) Runtime.getRuntime().availableProcessors() else threads
        this.threads = initThreads
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
            "temperature" to "get_wb" -> {
                val p = s.live()[op to priority]!!.params
                val (t, tint) = FakeWhiteBalance.fromCoefficients(p)
                buildJsonObject { put("temperature", t); put("tint", tint) }
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
        s.openGroup = null
        val prev = s.undo.removeLastOrNull() ?: fail(EngineError.STATE, "nothing to undo")
        s.redo.addLast(s.snapshot())
        s.restore(prev)
    }

    override suspend fun redo() = onEngine {
        val s = requireWritable()
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

    override suspend fun renderSize(scale: Double): RenderSize = onEngine {
        val s = requireSession()
        if (!(scale > 0.0) || scale > 16.0) fail(EngineError.ARG, "scale $scale")
        val (w, h) = FakeRenderer(s.variant).processedSize(orientation(s), scale)
        RenderSize.fromJson(RenderSize(w, h).toJson())
    }

    override suspend fun renderPreview(maxSize: Int): RenderedImage = onEngine {
        val s = requireSession()
        if (maxSize < 16) fail(EngineError.ARG, "max_size $maxSize")
        val r = FakeRenderer(s.variant)
        val o = orientation(s)
        val (fw, fh) = r.processedSize(o, 1.0)
        val scale = min(1.0, maxSize.toDouble() / max(fw, fh))
        val (w, h) = r.processedSize(o, scale)
        render(s, r, 0, 0, w, h, scale, o, timing.previewFixedMs)
    }

    override suspend fun renderRegion(x: Int, y: Int, w: Int, h: Int, scale: Double): RenderedImage = onEngine {
        val s = requireSession()
        if (!(scale > 0.0) || scale > 16.0) fail(EngineError.ARG, "scale $scale")
        val r = FakeRenderer(s.variant)
        val o = orientation(s)
        val (fw, fh) = r.processedSize(o, scale)
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > fw || y + h > fh)
            fail(EngineError.ARG, "region $x,$y ${w}x$h outside ${fw}x$fh")
        if (w.toLong() * h > 64L * 1024 * 1024) fail(EngineError.ARG, "region too large")
        render(s, r, x, y, w, h, scale, o, timing.regionFixedMs)
    }

    private suspend fun render(s: Session, r: FakeRenderer, x: Int, y: Int, w: Int, h: Int, scale: Double, o: Int, fixedMs: Long): RenderedImage {
        val flag = AtomicBoolean(false)
        runningRender.set(flag)
        try {
            val live = s.live()
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
            val px = r.scene(coords)
            checkpoint(perStage)
            for (st in stages) {
                st.pixel?.apply(px, n, coords)
                checkpoint(perStage) // cancel takes effect between modules
            }
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

    // ------------------------------------------------------------ 2.8 output

    override suspend fun xmpWrite(path: String) = onEngine {
        val s = requireWritable()
        val f = File(path)
        if (f.exists() && !check(readXmp(path)).ok) fail(EngineError.READ_ONLY, "$path fails the version guard; not overwritten")
        val entries = s.items.mapIndexed { n, it ->
            FakeXmp.Entry(n, it.op, catalog[it.op]!!.version, it.enabled, it.priority, it.instanceName, it.params)
        }
        val order = s.insts.mapIndexed { idx, i -> "${i.op}/${i.priority}" to (idx + 1) * 100 }.toMap()
        try {
            FakeXmp.write(f, entries, s.end, order)
        } catch (e: Exception) {
            fail(EngineError.IO, "cannot write $path: ${e.message}")
        }
    }

    override suspend fun export(path: String, options: ExportOptions) = onEngine {
        val s = requireSession()
        if (options.format != ExportFormat.PNG) fail(EngineError.ARG, "the fake engine exports PNG only")
        val r = FakeRenderer(s.variant)
        val o = orientation(s)
        val (fw, fh) = r.processedSize(o, 1.0)
        var scale = 1.0
        if (options.maxWidth > 0) scale = min(scale, options.maxWidth.toDouble() / fw)
        if (options.maxHeight > 0) scale = min(scale, options.maxHeight.toDouble() / fh)
        val (w, h) = r.processedSize(o, scale)
        val img = render(s, r, 0, 0, w, h, scale, o, 0)
        try {
            val f = File(path)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(Png.encode(img))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            fail(EngineError.IO, "cannot write $path: ${e.message}")
        }
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

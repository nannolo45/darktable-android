// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Brief P4b (engine-api.md 11, `dtb_init_ex`): how the engine starts. [opencl]: OpenCL may be started later
 * ([DarktableEngine.openclSet]); darktable itself always starts without it. [resourceLevel]/[resourceValues]: darktable's
 * resource level and its four numbers (darktablerc `resource_<level>`); null = darktable's own choice.
 * [pipeCacheFraction]: the bridge pipelines (full, preview) may cache available memory / n bytes; 0 = darktable's.
 * [inputsKept]: decoded inputs the image cache keeps; 0 = darktable's.
 */
data class EngineStartOptions(
    val opencl: Boolean = false,
    val resourceLevel: String? = null,
    val resourceValues: String? = null,
    val pipeCacheFraction: Pair<Int, Int> = 0 to 0,
    val inputsKept: Int = 0,
    /** where darktable keeps its compiled OpenCL kernels; null = under its cache folder */
    val openclKernelDir: String? = null,
) {
    fun toJson(): String = buildJsonObject {
        put("opencl", opencl)
        resourceLevel?.let { put("resource_level", it) }
        resourceValues?.let { put("resource_values", it) }
        if (pipeCacheFraction != (0 to 0)) putJsonArray("pipe_cache_fraction") { add(JsonPrimitive(pipeCacheFraction.first)); add(JsonPrimitive(pipeCacheFraction.second)) }
        if (inputsKept > 0) put("inputs_kept", inputsKept)
        openclKernelDir?.let { put("opencl_kernel_dir", it) }
    }.toString()

    companion object { val DEFAULT = EngineStartOptions() }
}

/** One OpenCL device darktable found (`dtb_opencl_info` "devices"). */
data class OpenClDevice(val name: String, val platform: String, val version: String, val memory: Long, val unified: Boolean, val disabled: Boolean)

/**
 * `dtb_opencl_info`: [built] the engine has OpenCL; [allowed] the start allowed it; [started] darktable's OpenCL was
 * started; [inited] a usable device was found; [running] OpenCL is used for the next render (on, not stopped after
 * errors). [lastRender]: "none", "cpu", "gpu" or "fallback" (an OpenCL error, darktable ran the render again on the CPU).
 */
data class OpenClInfo(
    val built: Boolean = false, val allowed: Boolean = false, val started: Boolean = false, val library: String? = null,
    val inited: Boolean = false, val enabled: Boolean = false, val running: Boolean = false, val stopped: Boolean = false,
    val errorCount: Int = 0, val devices: List<OpenClDevice> = emptyList(),
    val renders: Long = 0, val rendersGpu: Long = 0, val rendersFallback: Long = 0, val lastRender: String = "none",
) {
    /** The devices darktable may use. */
    val usableDevices: List<OpenClDevice> get() = devices.filter { !it.disabled }

    companion object {
        val NONE = OpenClInfo()

        fun fromJson(e: JsonElement): OpenClInfo {
            val o = e as? JsonObject ?: return NONE
            fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
            fun n(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val devs = (o["devices"] as? JsonArray)?.mapNotNull { d ->
                val x = d as? JsonObject ?: return@mapNotNull null
                OpenClDevice(s(x, "name") ?: "?", s(x, "platform") ?: "?", s(x, "version") ?: "?",
                    (x["memory"] as? JsonPrimitive)?.longOrNull ?: 0, (x["unified"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    (x["disabled"] as? JsonPrimitive)?.booleanOrNull ?: false)
            } ?: emptyList()
            return OpenClInfo(b("built"), b("allowed"), b("started"), s(o, "library"), b("inited"), b("enabled"), b("running"),
                b("stopped"), n("error_count").toInt(), devs, n("renders"), n("renders_gpu"), n("renders_fallback"), s(o, "last_render") ?: "none")
        }
    }
}

/** `dtb_resources_info`: what darktable made of the start-up options (bytes). -1 = not reported. */
data class EngineResources(
    val level: String? = null, val values: String? = null, val totalMemory: Long = -1, val availableMemory: Long = -1,
    val singlebufferMemory: Long = -1, val mipmapMemory: Long = -1, val pipeCacheFraction: List<Long> = emptyList(),
    val pipeCacheLimit: List<Long> = emptyList(), val inputsKept: Long = -1, val workerThreads: Long = -1,
    val pipeFullHits: Long = -1, val pipeFullTests: Long = -1, val pipePreviewHits: Long = -1, val pipePreviewTests: Long = -1,
    /** how many times the image cache decoded a RAW (full input) / made a downscaled input, since the start */
    val mipmapFullDecodes: Long = -1, val mipmapFDecodes: Long = -1,
) {
    fun describe(): String {
        fun mb(v: Long) = if (v < 0) "?" else (v / 1048576).toString()
        return "resource level $level ($values): darktable counts ${mb(totalMemory)} MB, plans with ${mb(availableMemory)} MB; " +
            "pipeline caches up to ${pipeCacheLimit.joinToString(" + ") { mb(it) }} MB (full + preview); " +
            "decoded photos kept $inputsKept; thumbnail cache ${mb(mipmapMemory)} MB"
    }

    companion object {
        val NONE = EngineResources()

        fun fromJson(e: JsonElement): EngineResources {
            val o = e as? JsonObject ?: return NONE
            fun n(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: -1L
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun l(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
            return EngineResources(s("level"), s("values"), n("total_memory"), n("available_memory"), n("singlebuffer_memory"),
                n("mipmap_memory"), l("pipe_cache_fraction"), l("pipe_cache_limit"), n("inputs_kept"), n("worker_threads"),
                n("pipe_full_hits"), n("pipe_full_tests"), n("pipe_preview_hits"), n("pipe_preview_tests"),
                n("mipmap_full_decodes"), n("mipmap_f_decodes"))
        }
    }
}

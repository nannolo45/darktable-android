// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.speed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Brief P4b: Settings › processing. [gpu]: "use the GPU (OpenCL)", off by default; on only after the comparison
 * against the CPU passed ([GpuCheck]). [memory]: "processing memory", used at the next start. [gpuNote]: the last
 * result of switching the GPU on (shown under the switch). Kept in `<file>` as JSON, written whole; [file] null keeps it
 * in memory (tests).
 */
class ProcessingSettings(private val file: File?) {
    data class Values(val gpu: Boolean = false, val memory: ProcessingResources.Choice = ProcessingResources.Choice.NORMAL, val gpuNote: String? = null)

    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values.asStateFlow()

    private fun load(): Values {
        val f = file?.takeIf { it.isFile } ?: return Values()
        val o = runCatching { Json.parseToJsonElement(f.readText()) as JsonObject }.getOrNull() ?: return Values()
        return Values(
            (o["gpu"] as? JsonPrimitive)?.booleanOrNull ?: false,
            ProcessingResources.Choice.fromKey((o["memory"] as? JsonPrimitive)?.contentOrNull),
            (o["gpu_note"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    @Synchronized
    fun update(change: (Values) -> Values) {
        val v = change(_values.value)
        file?.let { f ->
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(buildJsonObject {
                put("gpu", v.gpu)
                put("memory", v.memory.key)
                v.gpuNote?.let { put("gpu_note", it) }
            }.toString())
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("cannot write ${f.name}") }
        }
        _values.value = v
    }
}

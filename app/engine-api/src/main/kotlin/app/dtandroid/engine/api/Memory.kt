// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Brief P4a (engine-api.md 10, `dtb_memory_info`): what the engine holds, in bytes. [pipeFull]/[pipePreview]: the two
 * pipelines' cached intermediate buffers; [mipmapFull]/[mipmapF]/[mipmapThumbs]: darktable's image cache per level;
 * [rss]: the process's resident memory as the engine read it. -1 = not reported.
 */
data class EngineMemory(
    val pipeFull: Long = -1, val pipeFullPeak: Long = -1, val pipePreview: Long = -1, val pipePreviewPeak: Long = -1,
    val pipesReady: Int = -1,
    val mipmapFull: Long = -1, val mipmapFullQuota: Long = -1, val mipmapF: Long = -1, val mipmapFQuota: Long = -1,
    val mipmapThumbs: Long = -1, val mipmapThumbsQuota: Long = -1,
    val rss: Long = -1,
) {
    /** What the engine's caches hold together (pipelines and image cache), bytes; -1 when unknown. */
    val engineBytes: Long get() = listOf(pipeFull, pipePreview, mipmapFull, mipmapF, mipmapThumbs).filter { it >= 0 }.takeIf { it.isNotEmpty() }?.sum() ?: -1

    fun describe(): String {
        fun mb(v: Long) = if (v < 0) "?" else String.format(java.util.Locale.ROOT, "%.1f", v / 1048576.0)
        return "pipelines ${mb(pipeFull)} + ${mb(pipePreview)} MB (peak ${mb(pipeFullPeak)} + ${mb(pipePreviewPeak)}), " +
            "image cache full ${mb(mipmapFull)} / ${mb(mipmapFullQuota)} MB, f ${mb(mipmapF)} / ${mb(mipmapFQuota)} MB, " +
            "thumbs ${mb(mipmapThumbs)} / ${mb(mipmapThumbsQuota)} MB, process RSS ${mb(rss)} MB"
    }

    companion object {
        val NONE = EngineMemory()

        fun fromJson(e: JsonElement): EngineMemory {
            val o = e as? JsonObject ?: return NONE
            fun n(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: -1L
            return EngineMemory(n("pipe_full"), n("pipe_full_peak"), n("pipe_preview"), n("pipe_preview_peak"), n("pipes_ready").toInt(),
                n("mipmap_full"), n("mipmap_full_quota"), n("mipmap_f"), n("mipmap_f_quota"), n("mipmap_thumbs"), n("mipmap_thumbs_quota"), n("rss"))
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Status words of the working set, as the PC agent and LPL use them (PLAN.md 4.1). */
enum class PhotoStatus(val word: String) {
    DOWNLOADING("downloading"),
    OPENING("opening"),
    /** copied by a batch job, not opened yet (the same word LPL's PC agent uses) */
    READY("ready"),
    EDITING("editing"),
    DIRTY("dirty"),
    SYNCING("syncing"),
    SYNCED("synced"),
    ERROR("error");

    companion object {
        fun of(word: String?): PhotoStatus? = entries.firstOrNull { it.word == word }
    }
}

/** `GET /api/photos/{id}/checkout`. */
data class Checkout(
    val id: Long,
    val filename: String,
    val size: Long,
    val mtime: Double,
    val serverTime: Double,
    /** R17: the versions of the photo (`versions` of the answer); null = the server does not know versions */
    val versions: List<ServerVersion>? = null,
    /** R17: the version LPL shows for the photo (`cover` of the answer); 0 when absent */
    val cover: Int = 0,
)

/** R17: one entry of `/checkout`'s `versions`; [n] 0 is the photo's own edit. */
data class ServerVersion(val n: Int, val name: String, val mtime: Double, val size: Long, val hasFile: Boolean = true)

/** A job from the heartbeat's answer. [ids] empty with [all] = every photo this device holds. */
data class Job(
    val id: Long, val kind: String, val ids: List<Long>, val all: Boolean,
    /** `payload.open` of an edit job: false = copy the photos, do not open the darkroom */
    val open: Boolean = true,
)

/** [cancel] is null for a server that sends none (an old one): then nothing changes. */
data class HeartbeatAnswer(
    val jobs: List<Job>, val serverTime: Double, val cancel: Cancel? = null,
    /** R18: `server_caps` of the answer; null = the key is absent (an older server, or none announced) */
    val serverCaps: Set<String>? = null,
)

/** R16: a step of a download or a sync, with the share done when known. */
data class Step(val step: String, val progress: Double? = null) {
    companion object {
        const val DOWNLOADING = "downloading"
        const val SENDING_EDIT = "sending edit"
        const val RENDERING_PREVIEW = "rendering preview"
        const val SENDING_PREVIEW = "sending preview"
        /** R18: an export of the photo is being uploaded */
        const val EXPORTING = "exporting"
    }
}

/** `POST /api/photos/{id}/xmp`'s answer: the XMP as LPL stored it, and whether LPL merged its own metadata into it. */
class XmpAnswer(val merged: Boolean, val xmp: ByteArray)

/** One line of `state.working_set` in the heartbeat (same shape as the PC agent's). */
data class WorkingSetLine(
    val id: Long,
    val filename: String,
    val status: PhotoStatus,
    val checkoutTs: Double,
    val syncedTs: Double,
    val error: String,
    /** R16: what the device is doing with the photo now (`downloading`, `sending edit`, `rendering preview`, `sending preview`) */
    val step: String? = null,
    /** R16: the share of [step] done, 0 to 1, when known */
    val progress: Double? = null,
    /** R17: the version being edited when it is not the photo's own (n >= 1); only sent to a server with versions */
    val version: Int? = null,
)

/** The agent state sent with every heartbeat (PLAN.md 4.1). */
data class AgentState(
    val workingSet: List<WorkingSetLine>,
    /** the darkroom has a photo open */
    val darktableRunning: Boolean,
    val bridgeAlive: Boolean = true,
    /** app version + engine version */
    val version: String,
    val message: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("working_set", buildJsonArray {
            for (l in workingSet) add(buildJsonObject {
                put("id", l.id)
                put("filename", l.filename)
                put("status", l.status.word)
                put("checkout_ts", plain(l.checkoutTs))
                put("synced_ts", plain(l.syncedTs))
                put("error", l.error)
                // R16: only while a step runs; servers without R16 ignore them
                l.step?.let { put("step", it) }
                l.progress?.let { put("progress", it.coerceIn(0.0, 1.0)) }
                l.version?.let { put("version", it) }
            })
        })
        put("darktable_running", darktableRunning)
        put("bridge_alive", bridgeAlive)
        put("version", version)
        put("message", message)
    }
}

/** A number as JSON without an exponent (`1795070000.5`, not `1.7950700005E9`). */
internal fun plain(x: Double): JsonPrimitive = JsonPrimitive(java.math.BigDecimal.valueOf(x))

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
internal fun JsonElement?.long(): Long? = num()?.takeIf { it == Math.floor(it) && it >= 0 && it < 1e15 }?.toLong()
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** R18: the answer to an export upload: LPL's id of it and where the file was stored (relative to the library, or absolute). */
data class ExportAnswer(val exportId: Long, val path: String)

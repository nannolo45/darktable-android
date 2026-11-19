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
)

/** A job from the heartbeat's answer. [ids] empty with [all] = every photo this device holds. */
data class Job(val id: Long, val kind: String, val ids: List<Long>, val all: Boolean)

data class HeartbeatAnswer(val jobs: List<Job>, val serverTime: Double)

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

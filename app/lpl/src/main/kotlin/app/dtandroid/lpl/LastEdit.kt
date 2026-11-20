// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Request R15 (brief P2f deliverable 7): who edited a photo last and what changed, as LightPhotoLibrary
 * reports it (`GET /api/photos/{id}` carries `last_edit`; `GET /api/photos/{id}/edits` lists them). Every
 * field except [time] is optional on the server's side; unknown fields are ignored. A server without R15
 * gives no event, and the app then shows no line.
 */
data class LastEdit(
    /** seconds since 1970 */
    val time: Double,
    /** `agent`, `outside` (changed outside LightPhotoLibrary), `import` or `local`; null when not given */
    val source: String? = null,
    val agentId: String? = null,
    val device: String? = null,
    val deviceOs: String? = null,
    /** the server's own short text of the modules ("exposure, color balance rgb") */
    val summary: String? = null,
) {
    /**
     * The one line the list and the darkroom show: "last edited on Desktop · 18 Nov · exposure, color balance
     * rgb"; for an edit made outside LightPhotoLibrary that is said instead of a device.
     */
    fun line(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now(), thisDevice: String? = null): String {
        val who = when {
            source == "outside" -> "changed outside LightPhotoLibrary"
            source == "import" -> "imported"
            !device.isNullOrBlank() -> "last edited on ${if (thisDevice != null && agentId == thisDevice) "this phone" else device}"
            else -> "last edited"
        }
        return listOfNotNull(who, whenText(zone, now), summary?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
    }

    private fun whenText(zone: ZoneId, now: Instant): String {
        val t = Instant.ofEpochMilli((time * 1000).toLong()).atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        return when {
            t.toLocalDate() == today -> "today " + t.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
            t.year == today.year -> t.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
            else -> t.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("time", time)
        source?.let { put("source", it) }
        agentId?.let { put("agent_id", it) }
        device?.let { put("device", it) }
        deviceOs?.let { put("device_os", it) }
        summary?.let { put("summary", it) }
    }

    companion object {
        /** An event as the server sends it; null without a usable `time` (or for JSON null). */
        fun fromJson(e: JsonElement?): LastEdit? {
            val o = e as? JsonObject ?: return null
            val t = o["time"].num() ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return LastEdit(t, s("source"), s("agent_id"), s("device"), s("device_os"), s("summary"))
        }
    }
}

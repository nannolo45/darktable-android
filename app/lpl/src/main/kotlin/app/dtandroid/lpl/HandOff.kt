// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import java.net.URLDecoder

/** A checked `lpldt://edit` link (PLAN.md 4.3 R1): what to open, in LPL's order. */
data class HandOff(
    val server: ServerOrigin,
    /** LPL's order, never changed; at most [MAX_IDS]; [start] is among them */
    val ids: List<Long>,
    val start: Long,
    val series: Long? = null,
    val title: String? = null,
    /** R17: the version of [start] to open first (0 = the photo's own edit); null = LPL's cover, as before */
    val version: Int? = null,
) {
    companion object {
        const val MAX_IDS = 200
        const val MAX_LENGTH = 16_384
        const val MAX_TITLE = 200

        /**
         * Parse and check a hand-off link. The link is untrusted input (any web page or app can send
         * one), so every part is checked: scheme `lpldt`, host `edit`, nothing but a query; `server` an
         * http or https origin (host and optional port only); `ids` 1 to 200 positive numbers (repeats
         * dropped, the first kept); `start` a number, which is put first if it is not in `ids` (the list
         * is then cut to 200); `series` an optional number; `title` optional text, control characters
         * removed, at most 200 characters. Each parameter at most once; unknown parameters are ignored.
         */
        fun parse(link: String): HandOffResult {
            if (link.length > MAX_LENGTH) return HandOffResult.Invalid("the link is too long")
            val prefix = "lpldt://edit"
            if (!link.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return HandOffResult.Invalid("not an edit link")
            val rest = link.substring(prefix.length)
            val query = when {
                rest.startsWith("?") -> rest.substring(1)
                rest.startsWith("/?") -> rest.substring(2)
                else -> return HandOffResult.Invalid("not an edit link")
            }
            if ('#' in query) return HandOffResult.Invalid("unexpected part after the parameters")
            val params = HashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val name = if (eq < 0) part else part.substring(0, eq)
                val raw = if (eq < 0) "" else part.substring(eq + 1)
                if (name !in KNOWN) continue
                if (name in params) return HandOffResult.Invalid("the parameter \"$name\" appears twice")
                val value = try {
                    URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    return HandOffResult.Invalid("the parameter \"$name\" is not correctly encoded")
                }
                params[name] = value
            }
            val serverText = params["server"] ?: return HandOffResult.Invalid("the link names no server")
            val server = ServerOrigin.parseExact(serverText)
                ?: return HandOffResult.Invalid("the server in the link is not an http or https address of the form scheme://host:port")
            val idsText = params["ids"] ?: return HandOffResult.Invalid("the link names no photos")
            val parts = idsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return HandOffResult.Invalid("the link names no photos")
            if (parts.size > MAX_IDS) return HandOffResult.Invalid("the link names more than $MAX_IDS photos")
            val ids = ArrayList<Long>()
            for (p in parts) {
                val n = number(p) ?: return HandOffResult.Invalid("\"${p.take(20)}\" is not a photo number")
                if (n !in ids) ids += n
            }
            val start = params["start"]?.let { number(it) ?: return HandOffResult.Invalid("the start photo is not a number") } ?: ids.first()
            val list = if (start in ids) ids else (listOf(start) + ids).take(MAX_IDS)
            val series = params["series"]?.takeIf { it.isNotEmpty() }?.let { number(it) ?: return HandOffResult.Invalid("the series is not a number") }
            val title = params["title"]?.let { t -> t.filterNot { it.isISOControl() }.trim().take(MAX_TITLE).ifEmpty { null } }
            val version = params["version"]?.takeIf { it.isNotEmpty() }?.let { v ->
                if (v.length > 3 || !v.all { it in '0'..'9' }) return HandOffResult.Invalid("the version is not a number") else v.toInt()
            }
            return HandOffResult.Ok(HandOff(server, list, start, series, title, version))
        }

        private val KNOWN = setOf("server", "ids", "start", "series", "title", "version")

        /** A positive whole number of at most 15 digits, digits only. */
        private fun number(s: String): Long? {
            if (s.isEmpty() || s.length > 15 || !s.all { it in '0'..'9' }) return null
            return s.toLong().takeIf { it > 0 }
        }
    }
}

sealed interface HandOffResult {
    data class Ok(val handOff: HandOff) : HandOffResult
    data class Invalid(val reason: String) : HandOffResult
}

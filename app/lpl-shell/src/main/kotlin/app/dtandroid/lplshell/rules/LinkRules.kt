// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

/**
 * The hand-off to the darktable app (PLAN.md 4.2 and 4.3, request R1): what
 * the shell starts is built from these parts only. The Android intent is
 * `ACTION_VIEW` with data [data], package [PACKAGE], category BROWSABLE and
 * the new-task flag; nothing else from the link is carried over.
 */
data class HandOff(
    /** the link's query, unchanged (already percent-encoded) */
    val query: String,
    /** names of the intent attributes the link carried and the shell dropped (component, flags, extras, ...) */
    val stripped: List<String> = emptyList(),
) {
    val data: String get() = "$SCHEME://$HOST?$query"

    companion object {
        const val SCHEME = "lpldt"
        const val HOST = "edit"
        const val PACKAGE = "app.dtandroid"
        /** R1 links carry at most 200 ids and a title; anything far longer is not one */
        const val MAX_LENGTH = 16 * 1024
    }
}

/** What to do with a main-frame navigation in the web view. */
sealed interface LinkDecision {
    /** same origin as the saved server: load it in the web view */
    data object Stay : LinkDecision
    /** another http or https origin: open it in the phone's default browser */
    data class OpenOutside(val url: String) : LinkDecision
    /** "Edit on this phone": start the darktable app */
    data class HandOffTo(val handOff: HandOff) : LinkDecision
    /** not loaded, not passed on */
    data class Refuse(val reason: String) : LinkDecision
}

object LinkRules {

    fun decide(saved: Origin, url: String): LinkDecision {
        if (url.length > HandOff.MAX_LENGTH) return LinkDecision.Refuse("too long")
        val colon = url.indexOf(':')
        val scheme = if (colon > 0) url.substring(0, colon).lowercase() else ""
        return when (scheme) {
            "http", "https" -> {
                val o = Origin.of(url) ?: return LinkDecision.Refuse("not a valid address")
                if (o == saved) LinkDecision.Stay else LinkDecision.OpenOutside(url)
            }
            "intent" -> parseIntentLink(url).fold()
            HandOff.SCHEME -> parseLpldtLink(url).fold()
            "about" -> if (url == "about:blank") LinkDecision.Stay else LinkDecision.Refuse("about page")
            else -> LinkDecision.Refuse("scheme $scheme")
        }
    }

    private fun Result<HandOff>.fold(): LinkDecision =
        fold({ LinkDecision.HandOffTo(it) }, { LinkDecision.Refuse(it.message ?: "refused") })

    /** `lpldt://edit?<query>` (a trailing slash after `edit` is allowed). */
    fun parseLpldtLink(url: String): Result<HandOff> {
        val prefix = HandOff.SCHEME + "://"
        if (!url.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return refuse("not an lpldt:// link")
        return editQuery(url.substring(prefix.length)).map { HandOff(it) }
    }

    /**
     * `intent://edit?<query>#Intent;scheme=lpldt;package=app.dtandroid;...;end`,
     * read the way Android reads intent links (`Intent.parseUri`), but nothing
     * of the link is used except the query: the scheme must be `lpldt` and the
     * package `app.dtandroid` (both required, each once); `S.browser_fallback_url`
     * is ignored; component, action, categories, flags, other extras and a
     * selector (`SEL` and everything after it) are dropped.
     */
    fun parseIntentLink(url: String): Result<HandOff> {
        val prefix = "intent:"
        if (!url.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return refuse("not an intent: link")
        val marker = url.lastIndexOf("#Intent;")
        if (marker < 0) return refuse("no #Intent; part")
        val dataPart = url.substring(prefix.length, marker)
        if (!dataPart.startsWith("//")) return refuse("no //edit in the link")
        val query = editQuery(dataPart.substring(2)).getOrElse { return Result.failure(it) }

        val tokens = url.substring(marker + "#Intent;".length).split(';')
        if (tokens.last() != "end" || tokens.dropLast(1).contains("end")) return refuse("does not end with ;end")
        var scheme: String? = null
        var pkg: String? = null
        var inSelector = false
        val stripped = ArrayList<String>()
        for (token in tokens.dropLast(1)) {
            if (inSelector) continue
            if (token == "SEL") {
                inSelector = true
                stripped += "SEL"
                continue
            }
            val eq = token.indexOf('=')
            if (eq <= 0) return refuse("malformed attribute")
            val key = token.substring(0, eq)
            val value = percentDecode(token.substring(eq + 1)) ?: return refuse("malformed value of $key")
            when (key) {
                "scheme" -> {
                    if (scheme != null) return refuse("scheme given twice")
                    scheme = value
                }
                "package" -> {
                    if (pkg != null) return refuse("package given twice")
                    pkg = value
                }
                "S.browser_fallback_url" -> Unit
                else -> stripped += key
            }
        }
        if (scheme != HandOff.SCHEME) return refuse("scheme is not ${HandOff.SCHEME}")
        if (pkg != HandOff.PACKAGE) return refuse("package is not ${HandOff.PACKAGE}")
        return Result.success(HandOff(query, stripped))
    }

    /** After `scheme://`: exactly `edit`, an optional `/`, then `?` and a non-empty query without a fragment. */
    private fun editQuery(afterSlashes: String): Result<String> {
        if (!afterSlashes.startsWith(HandOff.HOST)) return refuse("host is not ${HandOff.HOST}")
        var rest = afterSlashes.substring(HandOff.HOST.length)
        if (rest.startsWith("/")) rest = rest.substring(1)
        if (!rest.startsWith("?")) return refuse("no query after edit")
        val query = rest.substring(1)
        if (query.isEmpty()) return refuse("empty query")
        if (!isValidQuery(query)) return refuse("characters not allowed in the query")
        return Result.success(query)
    }

    private const val QUERY_CHARS = "-._~!$&'()*+,;=:@/?"

    /** RFC 3986 query characters with well-formed percent escapes. */
    fun isValidQuery(q: String): Boolean {
        var i = 0
        while (i < q.length) {
            val c = q[i]
            when {
                c == '%' -> {
                    if (i + 2 >= q.length || !q[i + 1].isHex() || !q[i + 2].isHex()) return false
                    i += 3
                    continue
                }
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in QUERY_CHARS -> Unit
                else -> return false
            }
            i++
        }
        return true
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Percent-decoding as UTF-8; null when an escape is malformed or the bytes are not UTF-8. */
    fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length || !s[i + 1].isHex() || !s[i + 2].isHex()) return null
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        val bytes = out.toByteArray()
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun refuse(reason: String): Result<Nothing> = Result.failure(IllegalArgumentException(reason))
}

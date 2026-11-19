// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One HTTP answer, as far as the check needs it. */
data class HttpAnswer(val status: Int, val body: String)

/** Result of checking an address before it is saved (brief P2c item 2). */
sealed interface CheckResult {
    /** the server publishes a web app manifest naming LightPhotoLibrary */
    data object Lpl : CheckResult
    /** the server answers but does not look like LightPhotoLibrary: warn, let the user continue */
    data object NotLpl : CheckResult
    /** no answer at all (no route, refused, timeout, certificate not trusted) */
    data class Unreachable(val reason: String) : CheckResult
}

/**
 * The first-start check, independent of how HTTP is done: [fetch] returns the
 * answer to a GET of a URL (redirects not followed), or throws when the server
 * cannot be reached.
 *
 * 1. `GET <origin>/manifest.webmanifest`; LPL if it names LightPhotoLibrary.
 * 2. Otherwise `GET <origin>/`; if the page links a manifest on the same
 *    origin (`<link rel="manifest" href=...>`), fetch that and look again.
 * 3. Any HTTP answer means the server is there; only an exception on the
 *    first request means it cannot be reached.
 */
object ServerCheck {
    const val NAME = "LightPhotoLibrary"

    fun run(origin: Origin, fetch: (String) -> HttpAnswer): CheckResult {
        val first = try {
            fetch(origin.url + "/manifest.webmanifest")
        } catch (e: Exception) {
            return CheckResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }
        if (first.status == 200 && manifestNamesLpl(first.body)) return CheckResult.Lpl
        try {
            val page = fetch(origin.startUrl)
            if (page.status == 200) {
                val href = manifestHref(page.body)?.let { resolveSameOrigin(origin, it) }
                if (href != null) {
                    val m = fetch(href)
                    if (m.status == 200 && manifestNamesLpl(m.body)) return CheckResult.Lpl
                }
            }
        } catch (e: Exception) {
            // the server answered the first request; a failure here only means it is not recognised
        }
        return CheckResult.NotLpl
    }

    /** `name` or `short_name` of a web app manifest contains LightPhotoLibrary (case and spaces ignored). */
    fun manifestNamesLpl(json: String): Boolean {
        val o = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return false
        val want = NAME.lowercase()
        return listOf("name", "short_name").any { k ->
            val v = o[k] as? JsonPrimitive
            v != null && v.isString && v.content.lowercase().filterNot { it.isWhitespace() }.contains(want)
        }
    }

    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val REL_MANIFEST = Regex("\\brel\\s*=\\s*[\"']?manifest\\b", RegexOption.IGNORE_CASE)
    private val HREF = Regex("\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)

    /** The `href` of the page's `<link rel="manifest">`, if any. */
    fun manifestHref(html: String): String? {
        for (m in LINK.findAll(html)) {
            val tag = m.value
            if (!REL_MANIFEST.containsMatchIn(tag)) continue
            val h = HREF.find(tag) ?: continue
            return (h.groupValues[2].ifEmpty { null } ?: h.groupValues[3].ifEmpty { null } ?: h.groupValues[4]).trim()
        }
        return null
    }

    /** An absolute URL for [href] on [origin], or null when it points elsewhere. */
    fun resolveSameOrigin(origin: Origin, href: String): String? {
        if (href.isEmpty() || href.any { it.isWhitespace() || it == '\\' }) return null
        return when {
            href.startsWith("//") -> null
            href.contains("://") -> href.takeIf { Origin.of(it) == origin }
            href.startsWith("/") -> origin.url + href
            href.contains(':') -> null
            else -> origin.startUrl + href.removePrefix("./")
        }
    }
}

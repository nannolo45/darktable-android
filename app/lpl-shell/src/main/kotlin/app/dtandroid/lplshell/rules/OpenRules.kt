// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

/**
 * Brief P2c item 5: the exported action `app.dtandroid.lplshell.OPEN` carries a
 * full URL. Any app can send it, so the shell loads it only when it is a
 * well-formed http or https URL on the saved server's origin; otherwise the
 * start page is opened instead.
 */
object OpenRules {
    const val ACTION_OPEN = "app.dtandroid.lplshell.OPEN"
    const val ACTION_CHANGE_ADDRESS = "app.dtandroid.lplshell.CHANGE_ADDRESS"
    const val MAX_LENGTH = 8 * 1024

    /** The URL to load, or null when it must be ignored. */
    fun accept(saved: Origin?, url: String?): String? {
        if (saved == null || url == null || url.isEmpty() || url.length > MAX_LENGTH) return null
        if (url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
        // strict parse: java.net.URI refuses characters a browser would quietly fix up
        val uri = try {
            java.net.URI(url)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.isOpaque || uri.rawUserInfo != null || uri.host == null) return null
        if (Origin.of(url) != saved) return null
        return url
    }
}

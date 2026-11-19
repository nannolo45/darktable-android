// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

/**
 * The origin (scheme, host, port) of an http or https address. The shell keeps
 * its web view on one origin: the saved server's.
 *
 * Pure Kotlin (no Android classes), so the rules are plain JVM unit tests.
 */
data class Origin(val scheme: String, val host: String, val port: Int) {

    /** `http://192.168.1.20:8080`: no trailing slash, default port left out. */
    val url: String
        get() = buildString {
            append(scheme).append("://")
            append(if (':' in host) "[$host]" else host)
            if (port != defaultPort(scheme)) append(':').append(port)
        }

    /** The page the shell opens first. */
    val startUrl: String get() = "$url/"

    override fun toString() = url

    companion object {
        fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        private val HOST_NAME = Regex("[a-z0-9_.-]+")
        private val IPV6 = Regex("[0-9a-f:.]+")

        /**
         * The origin of an http or https URL, or null when the URL has another
         * scheme, carries a user name or password (`http://a@b/`), or its host or
         * port is not valid. Only the part before the path is looked at, so this
         * works on any URL the web view reports.
         */
        fun of(url: String): Origin? {
            val sep = url.indexOf("://")
            if (sep <= 0) return null
            val scheme = url.substring(0, sep).lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = url.substring(sep + 3)
            // a backslash ends the host like a slash does in browsers, so it must end it here too
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, end)
            if (authority.isEmpty() || '@' in authority) return null
            if (authority.any { it.isWhitespace() || it.isISOControl() }) return null
            val host: String
            val portText: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                host = authority.substring(1, close).lowercase()
                if (host.isEmpty() || !IPV6.matches(host) || ':' !in host) return null
                val after = authority.substring(close + 1)
                portText = when {
                    after.isEmpty() -> null
                    after.startsWith(":") -> after.substring(1)
                    else -> return null
                }
            } else {
                val parts = authority.split(':')
                if (parts.size > 2) return null
                host = parts[0].lowercase()
                if (host.isEmpty() || !HOST_NAME.matches(host) || host.startsWith(".") || host.contains("..")) return null
                portText = parts.getOrNull(1)
            }
            val port = when {
                portText.isNullOrEmpty() -> defaultPort(scheme)
                portText.length > 5 || !portText.all { it in '0'..'9' } -> return null
                else -> portText.toInt().takeIf { it in 1..65535 } ?: return null
            }
            return Origin(scheme, host, port)
        }
    }
}

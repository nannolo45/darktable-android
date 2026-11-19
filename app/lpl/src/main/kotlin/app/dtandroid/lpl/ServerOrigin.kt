// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

/**
 * Where an LPL server is: scheme (http or https), host and port, nothing else. This is how the app
 * names a server everywhere: in the stored list, in the hand-off link's `server` and in every request.
 * The same rules as the shell's address check (P2c), written again here because the shell is a
 * separate app module.
 */
data class ServerOrigin(val scheme: String, val host: String, val port: Int) {

    /** `http://192.0.2.10:8080`: no trailing slash, the default port left out. */
    val url: String
        get() = buildString {
            append(scheme).append("://")
            append(if (':' in host) "[$host]" else host)
            if (port != defaultPort(scheme)) append(':').append(port)
        }

    val https: Boolean get() = scheme == "https"

    /** A name usable as a folder name: `http_192.0.2.10_8080`. */
    val key: String get() = "${scheme}_${host.replace(':', '-')}_$port"

    override fun toString() = url

    companion object {
        fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        private val HOST_NAME = Regex("[a-z0-9_.-]+")
        private val IPV6 = Regex("[0-9a-f:.]+")

        /**
         * The origin of exactly `scheme://host[:port]`, optionally with one trailing slash, or null.
         * Anything else (a path, a query, a fragment, a user name or password, another scheme, an
         * invalid host or port) is refused. Used for the hand-off link's `server`, which LPL fills with
         * `location.origin`.
         */
        fun parseExact(text: String): ServerOrigin? {
            val sep = text.indexOf("://")
            if (sep <= 0) return null
            val rest = text.substring(sep + 3).removeSuffix("/")
            if (rest.any { it == '/' || it == '?' || it == '#' || it == '\\' }) return null
            return of(text.substring(0, sep) + "://" + rest)
        }

        /**
         * The origin at the start of an http or https URL (what comes after the host is ignored), or
         * null when the scheme is another, the URL carries a user name or password, or the host or the
         * port is invalid.
         */
        fun of(url: String): ServerOrigin? {
            val sep = url.indexOf("://")
            if (sep <= 0) return null
            val scheme = url.substring(0, sep).lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = url.substring(sep + 3)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, end)
            if (authority.isEmpty() || '@' in authority) return null
            if (authority.any { it.isWhitespace() || it.isISOControl() || it == '%' }) return null
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
                if (host.isEmpty() || host.length > 253 || !HOST_NAME.matches(host) || host.startsWith(".") || host.contains("..")) return null
                portText = parts.getOrNull(1)
            }
            val port = when {
                portText.isNullOrEmpty() -> defaultPort(scheme)
                portText.length > 5 || !portText.all { it in '0'..'9' } -> return null
                else -> portText.toInt().takeIf { it in 1..65535 } ?: return null
            }
            return ServerOrigin(scheme, host, port)
        }

        /**
         * What the user typed when adding a server, as the addresses to try, in order. `192.0.2.10:8080`
         * (no scheme) gives https first and then http (PLAN.md 3.7: prefer HTTPS when the server offers it);
         * an address with a scheme gives exactly that. A trailing slash or a path is dropped, a user name
         * or password is refused (the password has its own field).
         */
        fun candidates(input: String): AddressInput {
            val text = input.trim()
            if (text.isEmpty()) return AddressInput.Invalid(AddressProblem.EMPTY)
            if (text.any { it.isWhitespace() || it.isISOControl() }) return AddressInput.Invalid(AddressProblem.SPACES)
            val sep = text.indexOf("://")
            if (sep >= 0) {
                val scheme = text.substring(0, sep).lowercase()
                if (scheme != "http" && scheme != "https") return AddressInput.Invalid(AddressProblem.SCHEME)
            }
            val afterScheme = if (sep >= 0) text.substring(sep + 3) else text
            val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) afterScheme.length else it }
            if ('@' in afterScheme.substring(0, authorityEnd)) return AddressInput.Invalid(AddressProblem.USER_INFO)
            return if (sep >= 0) {
                of(text)?.let { AddressInput.Ok(listOf(it)) } ?: AddressInput.Invalid(AddressProblem.NOT_AN_ADDRESS)
            } else {
                val https = of("https://$text")
                val http = of("http://$text") ?: return AddressInput.Invalid(AddressProblem.NOT_AN_ADDRESS)
                // without a port, https would mean port 443 and http port 80: try both
                AddressInput.Ok(listOfNotNull(https, http))
            }
        }
    }
}

enum class AddressProblem { EMPTY, SPACES, SCHEME, USER_INFO, NOT_AN_ADDRESS }

sealed interface AddressInput {
    data class Ok(val tries: List<ServerOrigin>) : AddressInput
    data class Invalid(val problem: AddressProblem) : AddressInput
}

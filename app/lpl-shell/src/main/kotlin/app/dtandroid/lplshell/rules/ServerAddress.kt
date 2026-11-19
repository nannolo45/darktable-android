// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

/** What the user typed as the server address, normalised to an origin, or why it cannot be used. */
sealed interface AddressResult {
    data class Ok(val origin: Origin) : AddressResult
    data class Invalid(val problem: AddressProblem) : AddressResult
}

enum class AddressProblem { EMPTY, SPACES, SCHEME, USER_INFO, NOT_AN_ADDRESS }

object ServerAddress {
    /**
     * Brief P2c item 2: `192.168.1.20:8080` becomes `http://192.168.1.20:8080`;
     * a trailing slash, a path, a query and a fragment are removed; scheme and
     * host are lower case; the default port is left out. Only http and https.
     * A user name or password in the address is refused: the shell never keeps
     * the LPL password (the page signs in itself).
     */
    fun normalise(input: String): AddressResult {
        val text = input.trim()
        if (text.isEmpty()) return AddressResult.Invalid(AddressProblem.EMPTY)
        if (text.any { it.isWhitespace() || it.isISOControl() }) return AddressResult.Invalid(AddressProblem.SPACES)
        val sep = text.indexOf("://")
        val withScheme = if (sep < 0) {
            "http://$text"
        } else {
            val scheme = text.substring(0, sep).lowercase()
            if (scheme != "http" && scheme != "https") return AddressResult.Invalid(AddressProblem.SCHEME)
            text
        }
        val authorityEnd = withScheme.substring(withScheme.indexOf("://") + 3)
            .let { r -> r.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) r.length else it } }
        if ('@' in withScheme.substring(withScheme.indexOf("://") + 3).take(authorityEnd)) {
            return AddressResult.Invalid(AddressProblem.USER_INFO)
        }
        val origin = Origin.of(withScheme) ?: return AddressResult.Invalid(AddressProblem.NOT_AN_ADDRESS)
        return AddressResult.Ok(origin)
    }
}

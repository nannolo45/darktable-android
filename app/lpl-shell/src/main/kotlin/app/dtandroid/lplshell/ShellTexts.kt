// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell

import app.dtandroid.lplshell.rules.AddressProblem

/** Every text of the two native screens and the message bar: short, plain, lower case like the darkroom's labels. */
object ShellTexts {
    const val SETUP_TITLE = "lightphotolibrary"
    const val SETUP_LABEL = "server address"
    const val SETUP_HINT = "for example http://192.168.1.20:8080"
    const val CONNECT = "connect"
    const val CHECKING = "checking…"
    const val CANCEL = "cancel"
    const val NOT_LPL = "the server answers, but it does not look like lightphotolibrary."
    const val USE_ANYWAY = "use it anyway"
    const val CHANGE = "change"

    const val UNREACHABLE_TITLE = "cannot reach the server"
    const val UNREACHABLE_HELP = "check that this phone is on the same network as the server and that the server is running."
    const val UNTRUSTED_HELP = "the server's certificate is not trusted by this phone."
    const val TRY_AGAIN = "try again"
    const val TRYING = "trying…"
    const val CHANGE_ADDRESS = "change address"

    const val DARKTABLE_MISSING = "darktable for android is not installed, or it cannot open this link yet."
    const val GET_IT = "get it"
    const val NO_BROWSER = "no app on this phone can open this link."
    const val DOWNLOAD_STARTED = "downloading "
    const val DOWNLOAD_UNSUPPORTED = "this download cannot be saved from here."
    const val CLOSE = "close"

    /** where "get it" leads: this repository's releases */
    const val RELEASES_URL = "https://github.com/nannolo45/darktable-android/releases"

    fun problem(p: AddressProblem) = when (p) {
        AddressProblem.EMPTY -> "type the server's address."
        AddressProblem.SPACES -> "the address cannot contain spaces."
        AddressProblem.SCHEME -> "the address must start with http:// or https://."
        AddressProblem.USER_INFO -> "leave out the user name and password; sign in on the page."
        AddressProblem.NOT_AN_ADDRESS -> "this is not a server address."
    }
}

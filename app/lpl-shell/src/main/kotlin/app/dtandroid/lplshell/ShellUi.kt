// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.dtandroid.lplshell.rules.Origin

/** What covers the web view. */
sealed interface Screen {
    /** the page, nothing native on top */
    data object Web : Screen

    /** first start, or "change address" */
    data class Setup(val cancelable: Boolean) : Screen

    /**
     * The server did not answer. [checking] is set when this came from the
     * address check (the address is not saved yet): "try again" checks again.
     */
    data class Unreachable(
        val address: String,
        val untrusted: Boolean = false,
        val retrying: Boolean = false,
        val checking: Origin? = null,
    ) : Screen
}

sealed interface SetupStatus {
    data object Idle : SetupStatus
    data object Checking : SetupStatus
    data class Problem(val text: String) : SetupStatus
    /** the server answers but is not recognised; the user may continue */
    data class NotLpl(val origin: Origin) : SetupStatus
}

/** A short line at the bottom, over the page. */
data class Message(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** State of the native parts, read by the Compose screens; changed on the main thread only. */
class ShellUi {
    var onChange: () -> Unit = {}

    private var screenState by mutableStateOf<Screen>(Screen.Web)
    var screen: Screen
        get() = screenState
        set(v) {
            screenState = v
            onChange()
        }

    var setupText by mutableStateOf("")
    var setupStatus by mutableStateOf<SetupStatus>(SetupStatus.Idle)

    private var messageState by mutableStateOf<Message?>(null)
    var message: Message?
        get() = messageState
        set(v) {
            messageState = v
            onChange()
        }
}

/** What the native screens can ask the activity to do. */
interface ShellActions {
    fun connect()
    fun cancelSetup()
    fun useAnyway(origin: Origin)
    fun backToAddress()
    fun tryAgain()
    fun changeAddress()
    fun closeMessage()
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

/**
 * Brief P4a deliverable 2: what the list's server line (the sync bar) and the sync notification say about the
 * connection, honestly: connected; "retrying" while a call is being repeated or work waits and the app tries again by
 * itself; "waiting for connection" when the phone has no network (Android runs the sync when one comes); otherwise the
 * failure in plain words.
 */
object LinkWords {
    const val CONNECTED = "connected"
    const val NOT_CONTACTED = "not contacted yet"
    const val WAITING_FOR_CONNECTION = "waiting for connection"

    /**
     * [lastError]: the last call's failure (null = it worked); [contacted]: whether any call was made this run;
     * [retrying]: a call being repeated now; [online]: whether the phone has a network; [workWaiting]: edits, exports or
     * downloads that the app will try again by itself.
     */
    fun connection(lastError: LplException?, contacted: Boolean, retrying: LplAgent.Retrying?, online: Boolean, workWaiting: Boolean): String = when {
        !online && (lastError != null || workWaiting) -> WAITING_FOR_CONNECTION
        retrying != null -> "retrying (attempt ${retrying.attempt + 1})"
        lastError == null -> if (contacted) CONNECTED else NOT_CONTACTED
        lastError.transient && workWaiting -> lastError.userText + " · retrying"
        else -> lastError.userText
    }

    /** The sync notification's second line: null while nothing is wrong. */
    fun notice(anyUnreachable: Boolean, retrying: Boolean, online: Boolean): String? = when {
        !online -> WAITING_FOR_CONNECTION
        retrying -> "retrying"
        anyUnreachable -> "server not reachable · retrying"
        else -> null
    }
}

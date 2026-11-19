// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** What went wrong with an LPL call, in classes the app can act on. */
enum class LplFailure {
    /** no answer: no route, refused, name unknown, connection dropped */
    UNREACHABLE,
    /** the server did not answer in time */
    TIMEOUT,
    /** the secure connection could not be made (certificate not trusted, not a TLS server) */
    TLS,
    /** 401: password missing or wrong */
    UNAUTHORIZED,
    /** 404: the photo is not in the library (any more) or its file is missing */
    NOT_FOUND,
    /** 3xx: redirects are never followed */
    REDIRECT,
    /** 400 and other 4xx: the server refused what was sent */
    REFUSED,
    /** 5xx */
    SERVER,
    /** an answer that is not what LPL sends (not JSON, missing fields, a download of the wrong length) */
    BAD_ANSWER,
    /** stopped by the app */
    CANCELLED,
    /** the local file could not be written */
    LOCAL,
}

/**
 * An LPL call failed. [message] names the call (method and path) and what happened; it never
 * contains the password, the token or a URL with credentials. [userText] is what the user sees.
 */
class LplException(val failure: LplFailure, message: String, val status: Int = 0, cause: Throwable? = null) :
    Exception(message, cause) {

    /** Whether trying again later can help without the user doing anything. */
    val transient: Boolean
        get() = failure == LplFailure.UNREACHABLE || failure == LplFailure.TIMEOUT ||
            (failure == LplFailure.SERVER && status in setOf(502, 503, 504))

    val userText: String get() = UserText.of(this)

    companion object {
        fun fromIo(label: String, e: IOException): LplException = when (e) {
            is SocketTimeoutException -> LplException(LplFailure.TIMEOUT, "$label: no answer in time", cause = e)
            is InterruptedIOException -> LplException(LplFailure.CANCELLED, "$label: interrupted", cause = e)
            is SSLException -> LplException(LplFailure.TLS, "$label: secure connection failed (${e.javaClass.simpleName})", cause = e)
            is UnknownHostException -> LplException(LplFailure.UNREACHABLE, "$label: server name not found", cause = e)
            is ConnectException, is NoRouteToHostException -> LplException(LplFailure.UNREACHABLE, "$label: connection refused or no route", cause = e)
            else -> LplException(LplFailure.UNREACHABLE, "$label: connection failed (${e.javaClass.simpleName})", cause = e)
        }
    }
}

/** Short, plain words for the user (brief P2e). Never a raw exception. */
object UserText {
    fun of(e: LplException): String = when (e.failure) {
        LplFailure.UNREACHABLE, LplFailure.TIMEOUT -> "LightPhotoLibrary is not reachable right now"
        LplFailure.TLS -> "the secure connection to the server failed (certificate not trusted?)"
        LplFailure.UNAUTHORIZED -> "wrong password for LightPhotoLibrary"
        LplFailure.NOT_FOUND -> "this photo is gone from the library"
        LplFailure.REDIRECT -> "the server sent the app elsewhere; the app does not follow that"
        LplFailure.REFUSED -> "LightPhotoLibrary refused the request"
        LplFailure.SERVER -> "LightPhotoLibrary had a problem (server error)"
        LplFailure.BAD_ANSWER -> "the server's answer was not what LightPhotoLibrary sends"
        LplFailure.CANCELLED -> "stopped"
        LplFailure.LOCAL -> "the phone's storage could not be written (full?)"
    }

    const val NEWER_DARKTABLE = "edited with a newer darktable: view only, never sent back"
    const val WAITING = "waiting to sync"
}

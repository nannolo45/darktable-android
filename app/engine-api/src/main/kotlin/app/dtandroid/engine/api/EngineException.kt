// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

/** Error codes of docs/design/engine-api.md section 1, with the C bridge's numbers. */
enum class EngineError(val code: Int) {
    STATE(-1),
    ARG(-2),
    NOT_FOUND(-3),
    TYPE(-4),
    RANGE(-5),
    IO(-6),
    READ_ONLY(-7),
    CANCELLED(-8),
    ENGINE(-9);

    companion object {
        fun of(code: Int): EngineError = entries.firstOrNull { it.code == code } ?: ENGINE
    }
}

/** Every failing engine call throws this (engine-api.md section 1). */
class EngineException(val error: EngineError, val detail: String) : Exception("${error.name}: $detail") {
    val code: Int get() = error.code

    /**
     * engine-api.md 5.1 (P1b, E1): `open` refused a file that holds no picture darktable can decode (junk, empty,
     * truncated, unsupported). Not true for the other IO answers (a missing file, an unreadable XMP).
     */
    val isUndecodableFile: Boolean get() = error == EngineError.IO && detail.startsWith(UNDECODABLE_PREFIX)

    /** Brief P4a: the engine (or the app, for the picture's buffer) ran out of memory during the call. */
    val isOutOfMemory: Boolean get() = error == EngineError.ENGINE && detail.contains("out of memory", ignoreCase = true)

    companion object {
        /** the start of the bridge's message for an undecodable file (engine-api.md 5.1) */
        const val UNDECODABLE_PREFIX = "cannot decode "
    }
}

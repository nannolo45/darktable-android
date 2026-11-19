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
class EngineException(val error: EngineError, message: String) : Exception("${error.name}: $message") {
    val code: Int get() = error.code
}

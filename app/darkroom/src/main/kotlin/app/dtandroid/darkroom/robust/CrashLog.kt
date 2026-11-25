// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.robust

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

/** One entry of the crash log. [time] in ms since 1970 (UTC). */
data class CrashEntry(val time: Long, val kind: String, val text: String, val appVersion: String = "")

/**
 * Brief P4a deliverable 4: the local crash log, in the app's own storage. Nothing here sends anything anywhere.
 *
 * One file, one JSON object per line (`{"t":…,"k":"…","v":"…","x":"…"}`), appended. A line cut off by a dying process
 * is skipped when read. Capped at [maxEntries] entries and [maxBytes] bytes: the oldest go first. One entry's text is
 * cut to [maxText] characters.
 *
 * The uncaught-exception handler ([install]) only appends (no reading, no trimming: the process is dying); the cap is
 * applied by the next [append] or [entries] call, so the file can be over [maxBytes] by at most one entry in between.
 *
 * What goes in: the kind ([KIND_CRASH], [KIND_NATIVE], [KIND_ANR], …), the thread, the exception and its stack, the
 * exit reason Android gives, and bridge errors the app showed. No photo content; file names only as far as an
 * exception's own message carries them (the app keeps those names anyway).
 */
class CrashLog(
    val file: File,
    private val appVersion: String = "",
    private val maxEntries: Int = 200,
    private val maxBytes: Long = 1L shl 20,
    private val maxText: Int = 32_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val _count = MutableStateFlow(-1)

    /** How many entries the log holds (-1 until it was first read); the about page shows it. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** Add an entry and apply the cap. Never throws: a log that cannot be written is not worth a crash. */
    fun append(kind: String, text: String, time: Long = now()) {
        synchronized(lock) {
            runCatching {
                appendRaw(kind, text, time)
                trimLocked()
            }
        }
    }

    /**
     * For the dying thread: one append and an fsync, nothing else (no JSON library, no reading). Never throws.
     */
    fun appendNow(kind: String, text: String, time: Long = now()) {
        runCatching { synchronized(lock) { appendRaw(kind, text, time) } }
    }

    private fun appendRaw(kind: String, text: String, time: Long) {
        file.parentFile?.mkdirs()
        val line = "{\"t\":$time,\"k\":${quote(kind)},\"v\":${quote(appVersion)},\"x\":${quote(text.take(maxText))}}\n"
        // a line left unfinished by a process that died while writing it: start on a fresh line
        val torn = file.length() > 0 && java.io.RandomAccessFile(file, "r").use { f -> f.seek(f.length() - 1); f.read() != '\n'.code }
        FileOutputStream(file, true).use { out ->
            if (torn) out.write('\n'.code)
            out.write(line.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** The entries, oldest first. Applies the cap. */
    fun entries(): List<CrashEntry> = synchronized(lock) {
        runCatching { trimLocked() }
        read()
    }

    fun clear() {
        synchronized(lock) {
            file.delete()
            _count.value = 0
        }
    }

    /** The whole log as text for "copy": newest first, one block per entry. */
    fun asText(): String = format(entries().asReversed())

    private fun read(): List<CrashEntry> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<CrashEntry>()
        file.useLines(Charsets.UTF_8) { lines ->
            for (l in lines) {
                if (l.isBlank()) continue
                val o = runCatching { json.parseToJsonElement(l) as? JsonObject }.getOrNull() ?: continue
                val t = (o["t"] as? JsonPrimitive)?.longOrNull ?: continue
                val k = (o["k"] as? JsonPrimitive)?.content ?: continue
                out += CrashEntry(t, k, (o["x"] as? JsonPrimitive)?.content ?: "", (o["v"] as? JsonPrimitive)?.content ?: "")
            }
        }
        _count.value = out.size
        return out
    }

    private fun trimLocked() {
        if (!file.isFile) { _count.value = 0; return }
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        var keep = lines.takeLast(maxEntries)
        var size = keep.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }
        while (size > maxBytes && keep.isNotEmpty()) {
            size -= keep.first().toByteArray(Charsets.UTF_8).size + 1L
            keep = keep.drop(1)
        }
        if (keep.size != lines.size || file.length() != size) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(keep.joinToString("") { it + "\n" }, Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        _count.value = keep.size
    }

    /**
     * Write Kotlin and Java exceptions that nobody caught to the log before the process dies, then hand them to the
     * handler that was there before (Android's, which ends the process). Call once, early (Application.onCreate).
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            appendNow(KIND_CRASH, "thread \"${t.name}\"\n" + stackOf(e))
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        const val KIND_CRASH = "crash"
        const val KIND_NATIVE = "native crash"
        const val KIND_ANR = "not responding (ANR)"
        const val KIND_LOW_MEMORY = "stopped by Android: low memory"
        const val KIND_RESOURCES = "stopped by Android: too many resources"
        const val KIND_START = "start failed"
        const val KIND_BRIDGE = "engine error shown"
        const val KIND_RENDER_OOM = "render out of memory"

        fun stackOf(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

        /** A JSON string literal (the dying thread must not need the JSON library). */
        fun quote(s: String): String {
            val b = StringBuilder(s.length + 16).append('"')
            for (ch in s) when {
                ch == '"' -> b.append("\\\"")
                ch == '\\' -> b.append("\\\\")
                ch == '\n' -> b.append("\\n")
                ch == '\r' -> b.append("\\r")
                ch == '\t' -> b.append("\\t")
                ch < ' ' -> b.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                else -> b.append(ch)
            }
            return b.append('"').toString()
        }

        /** An entry's heading: "2026-11-25 03:12:09 UTC · crash · 0.4.1". */
        fun heading(e: CrashEntry): String {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.ROOT)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return f.format(java.util.Date(e.time)) + " · " + e.kind + if (e.appVersion.isNotEmpty()) " · " + e.appVersion else ""
        }

        fun format(newestFirst: List<CrashEntry>): String =
            if (newestFirst.isEmpty()) "crash log: empty\n"
            else newestFirst.joinToString("\n") { heading(it) + "\n" + it.text.trimEnd() + "\n" }

        /**
         * Whether a message the darkroom shows came from the engine bridge: [app.dtandroid.engine.api.EngineException]'s
         * message is "<ERROR>: <detail>" with one of the bridge's error names.
         */
        fun isBridgeMessage(m: String?): Boolean =
            m != null && app.dtandroid.engine.api.EngineError.entries.any { m.startsWith(it.name + ": ") }
    }
}

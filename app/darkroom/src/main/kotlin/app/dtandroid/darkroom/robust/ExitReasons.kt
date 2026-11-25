// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.robust

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.InputStream

/**
 * Brief P4a deliverable 4: on start, how earlier runs of the app ended, from Android's `ApplicationExitInfo` (API 30
 * and later; older Android has no such record and nothing is read). Native crashes, ANRs, kills for low memory or
 * excessive resource use and failed starts are added to the [CrashLog]. A Java/Kotlin crash is already in the log
 * (written by [CrashLog.install] before the process died); it is added from here only when that entry is missing.
 *
 * The time of the newest exit already read is kept in `<log>.exits`, so each exit is added once.
 */
object ExitReasons {
    /** An exit as the log needs it (built from ApplicationExitInfo, or by a test). */
    data class Exit(
        val time: Long,
        val reason: Int,
        val status: Int,
        val description: String?,
        val pssKb: Long,
        val rssKb: Long,
        val importance: Int,
        val trace: ByteArray?,
    )

    // ApplicationExitInfo.REASON_* (constant values of the platform API, so this compiles and tests below API 30)
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9

    /** Read the exits Android recorded since the last call and add the ones that matter to [log]. Returns how many were added. */
    fun collect(context: Context, log: CrashLog): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val exits = runCatching { read(context) }.getOrElse { e ->
            log.append(CrashLog.KIND_START, "could not read how the app ended last time: ${e.javaClass.simpleName}")
            return 0
        }
        return addNew(exits, log, File(log.file.path + ".exits"))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun read(context: Context): List<Exit> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(null, 0, 16).map { i: ApplicationExitInfo ->
            val keepTrace = i.reason == REASON_ANR || i.reason == REASON_CRASH_NATIVE
            Exit(i.timestamp, i.reason, i.status, i.description, i.pss, i.rss, i.importance,
                if (keepTrace) runCatching { i.traceInputStream?.use { readCapped(it, 512 * 1024) } }.getOrNull() else null)
        }
    }

    /** The pure part: which of [exits] are new (after the time kept in [seenFile]) and what each entry says. */
    fun addNew(exits: List<Exit>, log: CrashLog, seenFile: File): Int {
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val fresh = exits.filter { it.time > seen }.sortedBy { it.time }
        if (fresh.isEmpty()) return 0
        val logged = if (fresh.any { it.reason == REASON_CRASH }) log.entries().filter { it.kind == CrashLog.KIND_CRASH }.map { it.time } else emptyList()
        var added = 0
        for (x in fresh) {
            val kind = kindOf(x.reason) ?: continue
            // the uncaught-exception handler wrote it a moment before Android recorded the exit
            if (x.reason == REASON_CRASH && logged.any { it in (x.time - 30_000)..(x.time + 5_000) }) continue
            log.append(kind, describe(x), x.time)
            added++
        }
        runCatching { seenFile.parentFile?.mkdirs(); seenFile.writeText(fresh.maxOf { it.time }.toString()) }
        return added
    }

    fun kindOf(reason: Int): String? = when (reason) {
        REASON_CRASH -> CrashLog.KIND_CRASH
        REASON_CRASH_NATIVE -> CrashLog.KIND_NATIVE
        REASON_ANR -> CrashLog.KIND_ANR
        REASON_LOW_MEMORY -> CrashLog.KIND_LOW_MEMORY
        REASON_EXCESSIVE_RESOURCE_USAGE -> CrashLog.KIND_RESOURCES
        REASON_INITIALIZATION_FAILURE -> CrashLog.KIND_START
        else -> null // the user closed it, Android stopped it in the background, an update, …: not a fault
    }

    fun describe(x: Exit): String = buildString {
        append("as Android recorded it at the end of the run")
        if (!x.description.isNullOrBlank()) append(": ").append(x.description)
        append('\n')
        if (x.reason == REASON_CRASH_NATIVE && x.status > 0) append("signal ${x.status}${signalName(x.status)}\n")
        append("memory at the end: ${x.pssKb / 1024} MB (PSS), ${x.rssKb / 1024} MB (RSS)")
        append(if (x.importance == 100) "; the app was on the screen\n" else "; the app was in the background\n")
        val t = x.trace
        if (t != null && t.isNotEmpty()) {
            if (x.reason == REASON_ANR) append(anrText(t)) else append(nativeText(t))
        }
    }

    private fun signalName(s: Int) = when (s) { 6 -> " (SIGABRT)"; 7 -> " (SIGBUS)"; 9 -> " (SIGKILL)"; 11 -> " (SIGSEGV)"; 4 -> " (SIGILL)"; 8 -> " (SIGFPE)"; else -> "" }

    /** An ANR trace is text: the main thread's part first (where the app hung), the rest cut. */
    fun anrText(trace: ByteArray, max: Int = 12_000): String {
        val s = trace.decodeToString()
        val main = Regex("(?ms)^\"main\".*?(?=^\\s*$|\\z)").find(s)?.value
        val body = (main ?: s).take(max)
        return "trace (main thread):\n$body\n"
    }

    /**
     * A native crash's trace is a tombstone in Android's binary format (API 31 and later); the app has no decoder for
     * it. Its readable strings (abort message, library and function names of the backtrace) are kept, up to [max].
     */
    fun nativeText(trace: ByteArray, max: Int = 12_000): String {
        val sb = StringBuilder()
        val run = StringBuilder()
        fun flush() { if (run.length >= 6 && sb.length < max) sb.append(run).append('\n'); run.setLength(0) }
        for (b in trace) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) run.append(c.toChar()) else flush()
        }
        flush()
        return "readable parts of the native trace (${trace.size} bytes):\n" + sb.take(max) + "\n"
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray = input.readNBytes(max)
}

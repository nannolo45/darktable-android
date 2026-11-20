// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine

import java.util.Locale

/**
 * Brief P2f deliverable 4: the time each module took in a render. Since P1b the timings page takes the
 * entries from the engine (DarktableEngine.renderTimes); [parse] still reads darktable's own "-d perf"
 * lines (`dt_show_times_f` in src/common/darktable.c, called per module by src/develop/pixelpipe_hb.c):
 *
 *     12.3456 [dev_pixelpipe] took 0.123 secs (0.456 CPU) [export] processed `exposure' on CPU, blended on CPU
 *     12.3460 [dev_pixelpipe] took 0.010 secs (0.010 CPU) initing base buffer [export]
 *
 * The bridge starts darktable with "-d perf" when DTB_DT_DEBUG=perf (engine/src/core.c); the JNI layer
 * keeps the process's output (NativeBridge.logSince).
 */
object ModuleTimes {
    /** One module's time in one render. [module] is the operation and darktable's instance suffix. */
    data class Entry(val module: String, val wallMs: Double, val cpuMs: Double)

    private val processed = Regex("""\[dev_pixelpipe] took ([0-9.]+) secs \(([0-9.]+) CPU\) \[[^\]]*] processed `([^']+)'""")
    private val base = Regex("""\[dev_pixelpipe] took ([0-9.]+) secs \(([0-9.]+) CPU\) initing base buffer""")

    /** The modules a render ran, in the order darktable finished them (input first). */
    fun parse(log: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (line in log.lineSequence()) {
            processed.find(line)?.let { m ->
                out += Entry(m.groupValues[3].trim(), m.groupValues[1].toDouble() * 1000, m.groupValues[2].toDouble() * 1000)
                return@let
            } ?: base.find(line)?.let { m ->
                out += Entry("(input)", m.groupValues[1].toDouble() * 1000, m.groupValues[2].toDouble() * 1000)
            }
        }
        return out
    }

    /** "module ms" pairs, slowest first, at most [top] (the rest summed in one line). */
    fun table(entries: List<Entry>, top: Int = 12): String {
        if (entries.isEmpty()) return "no per-module times (nothing re-ran, or the engine gave none)"
        val sorted = entries.sortedByDescending { it.wallMs }
        val total = entries.sumOf { it.wallMs }
        val parts = sorted.take(top).map { String.format(Locale.ROOT, "%s %.1f", it.module, it.wallMs) }.toMutableList()
        if (sorted.size > top) parts += String.format(Locale.ROOT, "%d others %.1f", sorted.size - top, sorted.drop(top).sumOf { it.wallMs })
        return String.format(Locale.ROOT, "sum %.1f ms over %d modules: ", total, entries.size) + parts.joinToString("; ")
    }
}

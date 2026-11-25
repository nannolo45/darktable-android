// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.speed

import java.util.Locale

/**
 * Brief P4e: darktable's -d perf -d tiling -d opencl -d memory output of one render (engine `renderTrace`) as a compact
 * per-module table: module, where it ran (CPU or GPU), milliseconds, tiled or not. darktable writes one line per module
 * (develop/pixelpipe_hb.c, dt_show_times_f):
 * `[dev_pixelpipe] took 0.012 secs (0.034 CPU) [full] processed `exposure' on GPU with tiling, blended on GPU`.
 * Modules taken from the pipeline's cache do not appear (after an exposure change: those before exposure).
 */
object PerfLog {
    data class Step(val pipe: String, val module: String, val device: String, val ms: Double, val tiled: Boolean)

    private val STEP = Regex("""\[dev_pixelpipe] took ([0-9.]+) secs \([0-9.]+ CPU\) \[([^\]]*)] processed `([^']*)' on (GPU|CPU|)( with tiling)?""")
    private val TROUBLE = Regex("""error|fail|out of memory|fallback|insufficient|can't|cannot|couldn't|could not""", RegexOption.IGNORE_CASE)

    fun steps(log: String): List<Step> = log.lineSequence().mapNotNull { l ->
        STEP.find(l)?.let { m ->
            Step(m.groupValues[2], m.groupValues[3], m.groupValues[4].ifEmpty { "?" }, m.groupValues[1].toDouble() * 1000.0, m.groupValues[5].isNotEmpty())
        }
    }.toList()

    /** The table under a [title] line; null log: a line saying nothing was caught. */
    fun table(title: String, log: String?): String = buildString {
        append("per module, ").append(title).append(":\n")
        if (log == null) { append("  (no per-module log caught)\n"); return@buildString }
        val steps = steps(log)
        if (steps.isEmpty()) append("  (no module lines in darktable's log)\n")
        val pipes = steps.map { it.pipe }.distinct()
        for (s in steps) {
            append(String.format(Locale.ROOT, "  %-18s %-3s %8.1f ms%s%s\n", s.module, s.device, s.ms,
                if (s.tiled) "  tiled" else "", if (pipes.size > 1) "  [${s.pipe}]" else ""))
        }
        if (steps.isNotEmpty()) {
            append(String.format(Locale.ROOT, "  sum %.1f ms; on GPU %.1f ms, on CPU %.1f ms; tiled: %s\n",
                steps.sumOf { it.ms }, steps.filter { it.device == "GPU" }.sumOf { it.ms }, steps.filter { it.device == "CPU" }.sumOf { it.ms },
                steps.filter { it.tiled }.joinToString { it.module }.ifEmpty { "none" }))
        }
        val other = log.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !STEP.containsMatchIn(it) }.toList()
        val tiling = other.filter { it.contains("tiling", ignoreCase = true) }
        val trouble = other.filter { TROUBLE.containsMatchIn(it) }
        val memory = other.filter { it.contains("memory", ignoreCase = true) && it !in trouble && it !in tiling }
        fun lines(label: String, l: List<String>, n: Int) {
            if (l.isEmpty()) return
            append("  ").append(label).append(" (").append(l.size).append(" lines):\n")
            for (x in l.take(n)) append("    ").append(x.take(LINE_MAX)).append('\n')
        }
        lines("tiling", tiling, 4)
        lines("OpenCL trouble", trouble, 6)
        lines("memory", memory, 4)
    }

    private const val LINE_MAX = 200
}

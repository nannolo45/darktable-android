// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine

import java.io.File

/**
 * Which CPUs the engine should run on (engine-api.md 2.1: "the JNI layer finds the fast cores; the
 * bridge only applies the list").
 *
 * Gate G0 measured on the target phone that 4 threads on the fast cores were up to six times faster
 * for interactive steps than all 8 (PLAN.md 3.8): darktable splits work evenly, so the slow cores
 * hold everybody up. The fast cores are found from each CPU's highest clock
 * (`/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`): every CPU of the slowest cluster is
 * left out when there is more than one cluster. When all CPUs have the same clock, or the clocks
 * cannot be read (emulators often hide them), every CPU is used and nothing is pinned.
 */
data class FastCores(
    /** every CPU the process may use, in order */
    val all: List<Int>,
    /** highest clock per CPU in kHz, null where unreadable */
    val maxKhz: Map<Int, Long?>,
    /** the CPUs the engine runs on */
    val chosen: List<Int>,
) {
    /** true when [chosen] is a strict subset and the engine threads must be pinned */
    val pin: Boolean get() = chosen.size < all.size

    fun describe(): String = buildString {
        append("${all.size} CPUs")
        val clusters = maxKhz.values.filterNotNull().distinct().sorted()
        if (clusters.isNotEmpty()) append(", max clocks ${clusters.joinToString(" / ") { "%.2f GHz".format(it / 1e6) }}")
        else append(", clocks not readable")
        append("; engine on CPUs ${chosen.joinToString(",")}")
        append(if (pin) " (pinned)" else " (not pinned: all CPUs)")
    }

    companion object {
        fun detect(cpuRoot: File = File("/sys/devices/system/cpu")): FastCores {
            val n = Runtime.getRuntime().availableProcessors()
            val all = parseCpuList(runCatching { File(cpuRoot, "online").readText() }.getOrNull())
                ?.takeIf { it.isNotEmpty() } ?: (0 until n).toList()
            val khz = all.associateWith { c ->
                runCatching { File(cpuRoot, "cpu$c/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }
            return FastCores(all, khz, choose(all, khz))
        }

        /** Leave out the slowest cluster when the clocks are known and differ. */
        fun choose(all: List<Int>, khz: Map<Int, Long?>): List<Int> {
            if (all.isEmpty() || khz.values.any { it == null }) return all
            val lowest = khz.values.filterNotNull().minOrNull() ?: return all
            val fast = all.filter { (khz[it] ?: lowest) > lowest }
            return if (fast.isEmpty()) all else fast
        }

        /** "0-3,5,7-8" -> [0,1,2,3,5,7,8]; null for unreadable text. */
        fun parseCpuList(text: String?): List<Int>? {
            if (text.isNullOrBlank()) return null
            val out = ArrayList<Int>()
            for (part in text.trim().split(',')) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val dash = p.indexOf('-')
                if (dash > 0) {
                    val a = p.substring(0, dash).toIntOrNull() ?: return null
                    val b = p.substring(dash + 1).toIntOrNull() ?: return null
                    for (i in a..b) out += i
                } else out += p.toIntOrNull() ?: return null
            }
            return out
        }
    }
}

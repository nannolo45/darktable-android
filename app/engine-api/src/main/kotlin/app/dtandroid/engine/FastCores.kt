// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine

import java.io.File

/**
 * Which CPUs of the device are its fast cores (engine-api.md 2.1: "the JNI layer finds the fast cores;
 * the bridge only applies the list"). Brief P2g: this says what the device has, not what this process
 * may use at the moment; [EnginePinning] chooses among the CPUs allowed now and never stops the start.
 * (Moved from engine-jni to engine-api in P2g so the choice is tested on the JVM; same package.)
 *
 * Gate G0 measured on the target phone that 4 threads on the fast cores were up to six times faster
 * for interactive steps than all 8 (PLAN.md 3.8): darktable splits work evenly, so the slow cores
 * hold everybody up. The fast cores are found from each CPU's highest clock
 * (`/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`): every CPU of the slowest cluster is
 * left out when there is more than one cluster. When all CPUs have the same clock, or the clocks
 * cannot be read (emulators often hide them), every CPU is used and nothing is pinned.
 */
data class FastCores(
    /** every CPU of the device that is online, in order */
    val all: List<Int>,
    /** highest clock per CPU in kHz, null where unreadable */
    val maxKhz: Map<Int, Long?>,
    /** the fast cores: the CPUs the engine should run on when the process may use them */
    val chosen: List<Int>,
) {
    /** true when [chosen] is a strict subset: there are slower cores to keep the engine off */
    val pin: Boolean get() = chosen.size < all.size

    /** true when every CPU's highest clock could be read */
    val clocksKnown: Boolean get() = maxKhz.isNotEmpty() && maxKhz.values.none { it == null }

    /** The device's CPUs, without what the engine does with them (see [Pinning.describe]). */
    fun describe(): String = buildString {
        append("${all.size} CPUs")
        val clusters = maxKhz.values.filterNotNull().distinct().sorted()
        if (clusters.isNotEmpty()) append(", max clocks ${clusters.joinToString(" / ") { "%.2f GHz".format(java.util.Locale.ROOT, it / 1e6) }}")
        else append(", clocks not readable")
        if (pin) append("; fast cores ${formatCpuList(chosen)}")
    }

    companion object {
        fun detect(cpuRoot: File = File("/sys/devices/system/cpu")): FastCores {
            val n = Runtime.getRuntime().availableProcessors()
            val all = parseCpuList(runCatching { File(cpuRoot, "online").readText() }.getOrNull())
                ?.takeIf { it.isNotEmpty() } ?: (0 until n).toList()
            val khz = all.associateWith { c ->
                // emulators often report 0: treated as unknown
                runCatching { File(cpuRoot, "cpu$c/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()?.takeIf { it > 0 }
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

        /** [0,1,2,3,5,7,8] -> "0-3,5,7-8" (the kernel's own way of writing a CPU list). */
        fun formatCpuList(cpus: Collection<Int>): String {
            val s = cpus.toSortedSet().toList()
            if (s.isEmpty()) return "none"
            val out = ArrayList<String>()
            var a = s[0]; var b = s[0]
            for (c in s.drop(1)) {
                if (c == b + 1) { b = c; continue }
                out += if (a == b) "$a" else "$a-$b"; a = c; b = c
            }
            out += if (a == b) "$a" else "$a-$b"
            return out.joinToString(",")
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

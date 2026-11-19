// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine

import app.dtandroid.engine.api.DarktableEngine
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * What the engine's threads run on, and why (brief P2g). [describe] is the plain-words line the
 * start report and the timings page show.
 */
data class Pinning(
    /** the CPUs the engine threads are pinned to; empty = not pinned */
    val cpus: List<Int>,
    /** why: "fast cores" or the reason it is not pinned, in plain words */
    val why: String,
    /** the CPUs this process could use when this was decided; null = not readable */
    val allowed: List<Int>?,
) {
    val pinned: Boolean get() = cpus.isNotEmpty()

    fun describe(): String =
        if (pinned) "pinned to CPUs ${FastCores.formatCpuList(cpus)} ($why)" else "not pinned: $why"

    companion object {
        val NONE = Pinning(emptyList(), "not decided yet", null)
    }
}

/**
 * Brief P2g: pinning the engine to the fast cores can never stop the engine from starting.
 *
 * Android limits the CPUs a process may use by its state (its cpuset: top app, foreground,
 * background, restricted). A process started in the background (a link, a job, a restore) may be
 * allowed the slow cores only at that moment. The bridge refuses a CPU that the engine thread could
 * not use at dtb_init ("CPU 4 is not available to this process"); before P2g that refusal ended the
 * start with "the engine did not start".
 *
 * Now the fast cores are chosen among the CPUs the process may use at that moment ([plan]); if none
 * of them is allowed, or the engine refuses anyway, the engine runs unpinned with its own thread
 * count ([apply]). Nothing here throws (except cancellation); the result says what happened.
 */
object EnginePinning {

    /** What [plan] decided: the CPUs to pin to (empty = do not pin) and why. */
    data class Plan(val cpus: List<Int>, val why: String)

    /**
     * Choose the CPUs to pin to.
     * @param cores the device's fast cores ([FastCores.detect])
     * @param allowed the CPUs this process may use now; null when that cannot be read
     * @param atStart the CPUs the engine thread could use when the engine started (the bridge refuses
     *   any other, engine/src/core.c dtb_set_thread_cpus); null when not known
     */
    fun plan(cores: FastCores, allowed: List<Int>?, atStart: List<Int>? = null): Plan {
        if (!cores.pin) {
            return Plan(emptyList(),
                if (!cores.clocksKnown) "the CPUs' clocks cannot be read, so the fast cores are not known; the engine uses every CPU it may"
                else "all CPUs are equally fast; the engine uses every CPU it may")
        }
        val fast = cores.chosen
        var usable = fast
        if (allowed != null) usable = usable.filter { it in allowed }
        if (atStart != null) usable = usable.filter { it in atStart }
        val fastText = FastCores.formatCpuList(fast)
        if (usable.isEmpty()) {
            val now = when {
                allowed != null && fast.none { it in allowed } -> "Android lets the app use only CPUs ${FastCores.formatCpuList(allowed)} at the moment"
                atStart != null -> "the engine started while Android let the app use only CPUs ${FastCores.formatCpuList(atStart)}"
                else -> "they are not available to the app"
            }
            return Plan(emptyList(), "the fast cores ($fastText) are not available: $now (this happens when the app is started in the background); the engine uses every CPU it may")
        }
        return if (usable.size == fast.size) Plan(usable, "the fast cores")
        else Plan(usable, "the fast cores the app may use now; ${FastCores.formatCpuList(fast - usable.toSet())} not available")
    }

    /**
     * Apply [plan] through the engine (each call goes through the engine's own queue). Never throws
     * for an engine error: a refusal leaves the engine unpinned with its start-up thread count.
     *
     * @param previous what was applied before; undoing an earlier pinning restores the bridge's
     *   start-up CPU set (dtb_set_thread_cpus with an empty list). When nothing was pinned before,
     *   the CPU set is left alone: the kernel's own set may by now be wider than the start-up one.
     */
    suspend fun apply(engine: DarktableEngine, plan: Plan, allowed: List<Int>?, previous: Pinning = Pinning.NONE): Pinning {
        if (plan.cpus.isEmpty()) {
            unpin(engine, previous)
            return Pinning(emptyList(), plan.why, allowed)
        }
        return try {
            engine.setThreads(plan.cpus.size)
            engine.setThreadCpus(plan.cpus)
            Pinning(plan.cpus, plan.why, allowed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // the bridge refused (or failed half-way): back to the start-up threads, without an error
            unpin(engine, previous, force = true)
            Pinning(emptyList(), "the engine refused CPUs ${FastCores.formatCpuList(plan.cpus)} (${e.message ?: e.javaClass.simpleName}); the engine uses every CPU it may", allowed)
        }
    }

    private suspend fun unpin(engine: DarktableEngine, previous: Pinning, force: Boolean = false) {
        if (previous.pinned || force) runCatching { engine.setThreadCpus(emptyList()) }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        runCatching { engine.setThreads(0) }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    }

    /** [plan] then [apply]. */
    suspend fun pin(engine: DarktableEngine, cores: FastCores, allowed: List<Int>?, atStart: List<Int>?, previous: Pinning = Pinning.NONE): Pinning =
        apply(engine, plan(cores, allowed, atStart), allowed, previous)

    /**
     * The CPUs a thread may use, from a /proc status file ("Cpus_allowed_list:"). For the process:
     * /proc/self/status (its main thread, which is never pinned, so it shows the process's cpuset now).
     * For the calling thread: /proc/thread-self/status. Null when the file or the line is unreadable.
     */
    fun readAllowed(status: File = File("/proc/self/status")): List<Int>? =
        runCatching { parseAllowed(status.readText()) }.getOrNull()

    fun parseAllowed(statusText: String): List<Int>? {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("Cpus_allowed_list:") } ?: return null
        return FastCores.parseCpuList(line.substringAfter(':'))?.takeIf { it.isNotEmpty() }
    }
}

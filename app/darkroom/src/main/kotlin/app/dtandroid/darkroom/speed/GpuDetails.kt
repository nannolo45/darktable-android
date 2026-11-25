// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.speed

import app.dtandroid.engine.api.OpenClDiagnostics
import app.dtandroid.engine.api.OpenClInfo
import java.io.File
import java.util.Locale

/**
 * Brief P4d: the app's side of finding the phone's OpenCL library, before darktable starts its OpenCL.
 * [load] is `System.loadLibrary` on the phone (a fake in tests): a library it loads sits in the app's linker
 * namespace, so the bridge is asked to try its file name first ([Probe.preferred]). [files] reads the linker's
 * public library lists and the process's map of loaded files (only lines naming OpenCL are kept).
 */
class OpenClLibraries(private val load: (String) -> Unit, private val files: Files = Files.SYSTEM) {
    interface Files {
        fun list(dir: String): List<String>?
        fun read(path: String): String?

        companion object {
            val SYSTEM = object : Files {
                override fun list(dir: String) = File(dir).list()?.sorted()
                override fun read(path: String) = runCatching { File(path).readText() }.getOrNull()
            }
        }
    }

    /** [error] null = loaded. */
    data class Try(val name: String, val error: String?)

    data class Probe(
        val tries: List<Try>,
        /** file names the bridge should try first, in order */
        val preferred: List<String>,
        /** "file: line" for every line naming OpenCL in a public.libraries*.txt; "file: not readable" */
        val publicLibraries: List<String>,
        /** the OpenCL files mapped into the process after the tries */
        val mapped: List<String>,
    )

    fun probe(): Probe {
        val tries = NAMES.map { n ->
            val err = try { load(n); null } catch (t: Throwable) { "${t.javaClass.simpleName}: ${t.message ?: "no message"}" }
            Try(n, err)
        }
        val public = mutableListOf<String>()
        for (dir in PUBLIC_DIRS) {
            val names = files.list(dir) ?: continue
            for (f in names.filter { it.startsWith("public.libraries") && it.endsWith(".txt") }) {
                val path = "$dir/$f"
                val text = files.read(path)
                if (text == null) public += "$path: not readable"
                else text.lineSequence().filter { it.contains("opencl", ignoreCase = true) }.forEach { public += "$path: ${it.trim()}" }
            }
        }
        val mapped = files.read("/proc/self/maps")?.lineSequence()
            ?.mapNotNull { l -> l.substringAfter('/', "").takeIf { it.isNotEmpty() }?.let { "/$it" } }
            ?.filter { it.contains("opencl", ignoreCase = true) }?.distinct()?.toList() ?: emptyList()
        return Probe(tries, tries.filter { it.error == null }.map { "lib${it.name}.so" }, public, mapped)
    }

    companion object {
        /** `System.loadLibrary` names, in order: the standard name, then Qualcomm's own */
        val NAMES = listOf("OpenCL", "OpenCL_adreno")
        val PUBLIC_DIRS = listOf("/system/etc", "/vendor/etc", "/product/etc", "/system_ext/etc")
    }
}

/** Brief P4d: the text "copy GPU details" puts on the clipboard, and the one-line reason under the switch. */
object GpuDetails {
    private val DECISION = Regex(
        """\*\*\*|NOT active|not FULL_PROFILE|platforms? detected|found \d+ platform|no devices found|no opencl platform|""" +
            """could not get|could not build|failed to compile|could not open|could not create|no suitable|no working opencl|""" +
            """opencl library '|DEVICE: |FINALLY|successfully initialized""", RegexOption.IGNORE_CASE)

    /** The lines of darktable's OpenCL log that say what it decided, with the compiler's messages after a failed build. */
    fun decisions(log: String?): List<String> {
        if (log == null) return emptyList()
        val out = mutableListOf<String>()
        var after = 0
        for (raw in log.lineSequence()) {
            val l = raw.trimEnd()
            if (l.isBlank()) continue
            when {
                DECISION.containsMatchIn(l) -> {
                    out += l.trim()
                    if (l.contains("could not build", true) || l.contains("failed to compile", true)) after = 12
                }
                after > 0 -> { out += "    " + l.trim(); after-- }
            }
        }
        return out
    }

    /** Why darktable found no GPU it can use, in one line; null when it found one. */
    fun reason(d: OpenClDiagnostics, i: OpenClInfo?): String? {
        if (i != null && i.usableDevices.isNotEmpty()) return null
        if (!d.built) return "this engine was built without OpenCL"
        if (!d.started) return "darktable's OpenCL was not started"
        val loaded = d.tries.filter { it.loaded }
        if (loaded.isEmpty() && d.loaded == null)
            return "no OpenCL library could be opened (" + d.tries.take(3).joinToString("; ") { "${it.name}: ${it.error ?: "?"}" } + ")"
        val chosen = d.tries.firstOrNull { it.name == d.chosen }
        if (chosen != null && !chosen.symbol) return "${chosen.name} opened but has no clGetPlatformIDs"
        if (chosen != null && (chosen.platforms ?: 0) == 0L)
            return "${chosen.name} opened but reports no OpenCL platform (clGetPlatformIDs status ${chosen.status})"
        val lines = decisions(d.initLog)
        val first = lines.firstOrNull { it.contains("***") || it.contains("NOT active") || it.contains("FULL_PROFILE") ||
            it.contains("could not build") || it.contains("failed to compile") || it.contains("no devices found") }
        if (first != null) return "darktable did not use the device: ${first.trim().removePrefix("***").removeSuffix("***").trim()}"
        if (d.platforms.isNotEmpty() && d.platforms.all { it.devices.isEmpty() })
            return "the OpenCL platform ${d.platforms.joinToString { it.name }} reports no device"
        return "darktable found no GPU it can use (library ${d.loaded ?: d.chosen}); see \"copy GPU details\""
    }

    private fun mb(v: Long) = v / 1048576

    fun text(header: List<String>, note: String?, probe: OpenClLibraries.Probe?, d: OpenClDiagnostics, i: OpenClInfo?): String = buildString {
        appendLine("dt-android GPU details (brief P4d)")
        header.forEach { appendLine(it) }
        appendLine("result: ${note ?: "-"}")
        appendLine("reason: ${reason(d, i) ?: "darktable uses ${i?.usableDevices?.joinToString { it.name }}"}")
        appendLine()
        appendLine("== the app's System.loadLibrary")
        if (probe == null) appendLine("  not run")
        probe?.tries?.forEach { appendLine("  ${it.name}: ${it.error ?: "loaded"}") }
        probe?.let { appendLine("  asked the engine to try first: ${it.preferred.ifEmpty { listOf("-") }.joinToString()}") }
        appendLine("== public.libraries lines naming OpenCL")
        probe?.publicLibraries?.ifEmpty { listOf("none found") }?.forEach { appendLine("  $it") }
        appendLine("== OpenCL files loaded in the app")
        probe?.mapped?.ifEmpty { listOf("none") }?.forEach { appendLine("  $it") }
        appendLine("== libraries the engine tried (in order)")
        if (d.tries.isEmpty()) appendLine("  none")
        d.tries.forEachIndexed { k, t ->
            val what = when {
                !t.loaded -> "not opened: ${t.error}"
                !t.symbol -> "opened, no clGetPlatformIDs: ${t.error}"
                else -> "opened, clGetPlatformIDs status ${t.status}, platforms ${t.platforms}"
            }
            appendLine("  ${k + 1}. ${t.name} (${t.from}): $what")
        }
        appendLine("  handed to darktable: ${d.chosen ?: "none (darktable's own search)"}; darktable opened: ${d.loaded ?: "none"}")
        appendLine("== what the library reports")
        if (d.platforms.isEmpty()) appendLine("  no platform")
        for (p in d.platforms) {
            appendLine("  platform ${p.name} | vendor ${p.vendor} | ${p.version} | ${p.profile} | clGetDeviceIDs status ${p.devicesStatus}")
            for (v in p.devices) {
                appendLine("    device ${v.name} | ${v.type} | vendor ${v.vendor} | ${v.version} | driver ${v.driver} | ${v.cVersion}")
                appendLine(String.format(Locale.ROOT, "      available %s, image support %s, little endian %s, memory %d MB, largest buffer %d MB",
                    v.available, v.imageSupport, v.littleEndian, mb(v.globalMemory), mb(v.maxAlloc)))
                appendLine("      extensions: ${v.extensions}")
            }
        }
        appendLine("== darktable's state")
        if (i == null) appendLine("  unknown")
        else appendLine("  started ${i.started}, a device usable ${i.inited}, enabled ${i.enabled}, running ${i.running}, " +
            "devices ${i.devices.joinToString { "${it.name} (${it.platform}, ${it.version}, ${mb(it.memory)} MB${if (it.disabled) ", disabled" else ""})" }.ifEmpty { "none" }}")
        appendLine("== darktable's decisions (from its OpenCL log)")
        decisions(d.initLog).ifEmpty { listOf("none") }.forEach { appendLine("  $it") }
        appendLine("== darktable's OpenCL log (-d opencl -d verbose)${if (d.initLogCut) ", cut by the engine at 256 KiB" else ""}")
        val log = d.initLog ?: "none"
        if (log.length <= LOG_HEAD + LOG_TAIL) appendLine(log.trimEnd())
        else {
            appendLine(log.take(LOG_HEAD).trimEnd())
            appendLine("[… ${log.length - LOG_HEAD - LOG_TAIL} characters left out …]")
            appendLine(log.takeLast(LOG_TAIL).trimEnd())
        }
    }

    /** the log's start and end in the copied text (the decisions above come from all of it) */
    const val LOG_HEAD = 24_000
    const val LOG_TAIL = 16_000
}

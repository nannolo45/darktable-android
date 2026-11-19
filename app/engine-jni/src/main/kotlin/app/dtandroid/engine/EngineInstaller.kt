// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks the engine tree packed into the APK (assets/engine/engine.zip:
 * libraries, module plugins, darktable's data, the lens database) into the
 * app's private storage, and loads its libraries from there (experiment S4).
 *
 * assets/engine/engine.id names the packed tree; the tree is unpacked again
 * only when it changes (a new APK).
 */
class EngineInstaller(private val ctx: Context) {
    val engineDir = File(ctx.filesDir, "engine")
    private val stamp = File(engineDir, ".unpacked-id")

    data class Unpack(val fresh: Boolean, val ms: Long, val files: Int, val bytes: Long)
    data class Loads(val total: Int, val failures: List<String>)

    fun packedId(): String =
        ctx.assets.open("engine/engine.id").bufferedReader().use { it.readText().trim() }

    fun unpack(log: (String) -> Unit): Unpack {
        val id = packedId()
        if (stamp.isFile && stamp.readText().trim() == id) {
            val (files, bytes) = measure(engineDir)
            return Unpack(false, 0, files, bytes)
        }
        log("unpacking engine $id")
        val t0 = System.nanoTime()
        val tmp = File(ctx.filesDir, "engine.tmp")
        tmp.deleteRecursively()
        engineDir.deleteRecursively()
        tmp.mkdirs()
        val root = tmp.canonicalPath + File.separator
        var files = 0
        var bytes = 0L
        ZipInputStream(ctx.assets.open("engine/engine.zip").buffered(1 shl 16)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(tmp, e.name)
                require(out.canonicalPath.startsWith(root)) { "bad entry ${e.name}" }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered(1 shl 16).use { bytes += zin.copyTo(it, 1 shl 16) }
                    files++
                }
            }
        }
        check(tmp.renameTo(engineDir)) { "cannot move $tmp to $engineDir" }
        stamp.writeText(id)
        return Unpack(true, (System.nanoTime() - t0) / 1_000_000, files, bytes)
    }

    /** Load lib/load-order.txt, dependencies first, by absolute path. */
    fun loadLibraries(log: (String) -> Unit): Loads {
        val order = File(engineDir, "lib/load-order.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val failures = mutableListOf<String>()
        for (rel in order) {
            try {
                System.load(File(engineDir, rel).absolutePath)
            } catch (e: Throwable) {
                failures += "$rel: ${e.message ?: e}"
                log("FAILED to load $rel: ${e.message}")
            }
        }
        log("loaded ${order.size - failures.size} of ${order.size} engine libraries")
        return Loads(order.size, failures)
    }

    /** Every plugin library darktable may load: lib/darktable/ except libdarktable.so. */
    fun pluginFiles(): List<File> =
        File(engineDir, "lib/darktable").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so") && it.name != "libdarktable.so" }
            .sortedBy { it.path }
            .toList()

    private fun measure(dir: File): Pair<Int, Long> {
        var n = 0
        var b = 0L
        dir.walkTopDown().filter { it.isFile }.forEach { n++; b += it.length() }
        return n to b
    }
}

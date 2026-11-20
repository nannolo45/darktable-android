// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Brief P2f deliverable 8: spektrafilm's data pack, carried in the APK (assets/spektrafilm/, packed by
 * app-engine/build.gradle.kts) and unpacked once per pack into the folder darktable reads a hand-installed
 * pack from: <XDG_DATA_HOME>/darktable/spektrafilm (src/common/spektra_fetch.c, `_data_pack_dir`). The app
 * sets XDG_DATA_HOME to files/home/.local/share before darktable starts (Engine.prepare). On desktop the
 * module downloads the pack on first use; the engine here cannot (curl without TLS, PLAN.md).
 *
 * A pack already there with the same id is kept; a different id replaces the hand-install folder's files
 * (the "packs" folder below it, which darktable's own fetcher would own, is left alone). Data licence:
 * CC BY-SA 4.0 (its LICENSE is unpacked next to it).
 */
object SpektrafilmPack {
    data class State(val id: String?, val bytes: Long, val text: String)

    @Volatile var state: State = State(null, 0, "not checked")
        private set

    private const val MARK = ".dt-android-pack-id"

    fun dir(ctx: Context) = File(ctx.filesDir, "home/.local/share/darktable/spektrafilm")

    /** Unpack when needed; never throws (a failure is reported in [state], spektrafilm then shows its own notice). */
    fun install(ctx: Context): State {
        state = try { installOrThrow(ctx) } catch (e: Exception) { State(null, 0, "spektrafilm data could not be unpacked: $e") }
        return state
    }

    private fun installOrThrow(ctx: Context): State {
        val am = ctx.assets
        val id = runCatching { am.open("spektrafilm/spektrafilm.id").use { it.readBytes().decodeToString().trim() } }.getOrNull()
            ?: return State(null, 0, "this build carries no spektrafilm data")
        val dest = dir(ctx)
        val mark = File(dest, MARK)
        if (mark.isFile && mark.readText().trim() == id) return State(id, size(dest), "spektrafilm data $id (already unpacked)")
        val tmp = File(dest.parentFile, "spektrafilm.unpacking").apply { deleteRecursively(); mkdirs() }
        am.open("spektrafilm/spektrafilm.zip").use { input ->
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) throw java.io.IOException("bad entry $name")
                    val f = File(tmp, name)
                    if (e.isDirectory) { f.mkdirs(); continue }
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                }
            }
        }
        runCatching { am.open("spektrafilm/LICENSE").use { i -> File(tmp, "LICENSE").outputStream().use { i.copyTo(it) } } }
        File(tmp, MARK).writeText(id + "\n")
        // replace the hand-install folder's own files; keep darktable's downloads ("packs")
        dest.mkdirs()
        dest.listFiles().orEmpty().filter { it.name != "packs" }.forEach { it.deleteRecursively() }
        tmp.listFiles().orEmpty().forEach { f -> if (!f.renameTo(File(dest, f.name))) throw java.io.IOException("cannot move ${f.name} into place") }
        tmp.deleteRecursively()
        return State(id, size(dest), "spektrafilm data $id unpacked")
    }

    private fun size(d: File) = d.walkTopDown().filter { it.isFile && !it.path.contains("/packs/") }.sumOf { it.length() }
}

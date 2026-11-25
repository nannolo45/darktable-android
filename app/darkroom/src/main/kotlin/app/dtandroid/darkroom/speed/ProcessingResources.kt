// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.speed

import app.dtandroid.engine.api.EngineStartOptions

/**
 * Brief P4b deliverable 3: darktable's memory budget for this phone, from its total memory and the user's "processing
 * memory" choice. Applied when the engine starts (`dtb_init_ex`).
 *
 * darktable's model (common/darktable.c): a resource level is four numbers, fractions of 1024 of the memory darktable
 * counts (MemTotal, less 1 GB below 8 GB): (1) memory a pipeline plans with, against which darktable decides whether a
 * module must tile, at least 512 MB; (2) the single-buffer size; (3) the thumbnail cache; (4) the share of an OpenCL
 * device's memory. Desktop's "default" level is 512 8 128 700: half the machine for processing, an eighth for
 * thumbnails. A pipeline's cache of intermediate buffers may hold (1) / n bytes, n = 8 for desktop's main darkroom
 * pipe and 32 for its preview pipe (develop/pixelpipe_hb.c); the image cache keeps (worker threads + 1) decoded inputs,
 * 8 on a machine with 8 GB and 4 threads (common/mipmap_cache.c).
 *
 * What differs on a phone: Android, the app's own interface and (with OpenCL) the GPU driver share the same memory and
 * there is no swap; a background app holding gigabytes is stopped first. The numbers below give darktable less than
 * desktop's half, keep two to four decoded photos (enough to go back and forth in a series without decoding the RAW
 * again, measured in bridge test C35), and keep darktable's own pipeline cache fractions. They are choices, not
 * measurements on the phone: the owner's "measure speed" and the crash log (stops for low memory) are where they are
 * checked.
 */
object ProcessingResources {
    /** The user's setting (Settings › processing › "processing memory"). */
    enum class Choice(val key: String, val label: String) {
        NORMAL("normal", "normal"), MORE("more", "more"), LESS("less", "less");

        companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: NORMAL }
    }

    /** Phones by total memory (as Android reports it: a little below the memory printed on the box). */
    enum class RamClass(val label: String) { LARGE("12 GB and more"), MEDIUM("6 to 8 GB"), SMALL("4 GB and less") }

    /** From this total (bytes, `ActivityManager.MemoryInfo.totalMem`) a phone counts as [RamClass.LARGE]: a 12 GB phone reports about 11 GiB. */
    const val LARGE_FROM_BYTES = 9L shl 30

    /** From this total a phone counts as [RamClass.MEDIUM]: a 6 GB phone reports about 5.6 GiB. */
    const val MEDIUM_FROM_BYTES = (11L shl 30) / 2

    /** darktable's resource level names (darktableconfig.xml.in `resourcelevel`). */
    const val LEVEL_DEFAULT = "default"
    const val LEVEL_SMALL = "small"

    /**
     * 12 GB phone, normal: 384/1024 (about 4.2 GB of 11) to plan with, desktop's single buffer (8), a quarter of
     * desktop's thumbnail cache (32: the app keeps its own thumbnails as files), desktop's OpenCL share (700).
     * Less than desktop's 512 because the GPU driver (unified memory) and Android need the rest.
     */
    const val VALUES_LARGE_NORMAL = "384 8 32 700"
    /** 12 GB phone, more: desktop's own processing and single-buffer numbers. */
    const val VALUES_LARGE_MORE = "512 8 64 700"
    /** 12 GB phone, less: a quarter of the memory; darktable tiles big exports earlier. */
    const val VALUES_LARGE_LESS = "256 8 32 700"
    /** 6 to 8 GB phone, normal: a quarter (about 1.4 to 1.9 GB). */
    const val VALUES_MEDIUM_NORMAL = "256 8 32 700"
    const val VALUES_MEDIUM_MORE = "384 8 32 700"
    /** 6 to 8 GB phone, less: desktop's "small" processing share and single buffer. */
    const val VALUES_MEDIUM_LESS = "160 4 16 400"
    /** Smaller phones, normal: desktop's "small" level (128 4 64 400) with a smaller thumbnail cache; darktable's floor of 512 MB applies. */
    const val VALUES_SMALL_NORMAL = "128 4 16 400"
    const val VALUES_SMALL_MORE = "192 4 32 400"
    const val VALUES_SMALL_LESS = "96 4 16 400"

    /** darktable's own pipeline cache fractions: main darkroom pipe 8, preview pipe 32 (develop/pixelpipe_hb.c). */
    val PIPE_CACHE_DESKTOP = 8 to 32
    /** Half of those caches, for "less" and small phones: re-renders after a change recompute more modules. */
    val PIPE_CACHE_HALF = 16 to 64

    /** Decoded photos kept (the open one included): going back to the last two photos of a series needs no RAW decode. */
    const val INPUTS_LARGE = 3
    const val INPUTS_LARGE_MORE = 4
    /** Going back to the previous photo needs no RAW decode. */
    const val INPUTS_MEDIUM = 2
    /** Only the open photo: every switch decodes the RAW again (about 0.5 s on the emulator, P4a). */
    const val INPUTS_MIN = 1

    data class Plan(val ramClass: RamClass, val choice: Choice, val options: EngineStartOptions) {
        fun describe(): String = "${ramClass.label} phone, processing memory ${choice.label}: darktable resource level " +
            "${options.resourceLevel} (${options.resourceValues}), pipeline caches 1/${options.pipeCacheFraction.first} and " +
            "1/${options.pipeCacheFraction.second}, ${options.inputsKept} decoded photos kept"
    }

    fun ramClass(totalBytes: Long): RamClass = when {
        totalBytes >= LARGE_FROM_BYTES -> RamClass.LARGE
        totalBytes >= MEDIUM_FROM_BYTES -> RamClass.MEDIUM
        else -> RamClass.SMALL
    }

    /** The start-up options for a phone with [totalBytes] of memory. [opencl]: OpenCL may be started later. */
    fun plan(totalBytes: Long, choice: Choice, opencl: Boolean = true): Plan {
        val c = ramClass(totalBytes)
        val (level, values, pipes, inputs) = when (c) {
            RamClass.LARGE -> when (choice) {
                Choice.NORMAL -> Quad(LEVEL_DEFAULT, VALUES_LARGE_NORMAL, PIPE_CACHE_DESKTOP, INPUTS_LARGE)
                Choice.MORE -> Quad(LEVEL_DEFAULT, VALUES_LARGE_MORE, PIPE_CACHE_DESKTOP, INPUTS_LARGE_MORE)
                Choice.LESS -> Quad(LEVEL_DEFAULT, VALUES_LARGE_LESS, PIPE_CACHE_HALF, INPUTS_MEDIUM)
            }
            RamClass.MEDIUM -> when (choice) {
                Choice.NORMAL -> Quad(LEVEL_DEFAULT, VALUES_MEDIUM_NORMAL, PIPE_CACHE_DESKTOP, INPUTS_MEDIUM)
                Choice.MORE -> Quad(LEVEL_DEFAULT, VALUES_MEDIUM_MORE, PIPE_CACHE_DESKTOP, INPUTS_LARGE)
                Choice.LESS -> Quad(LEVEL_SMALL, VALUES_MEDIUM_LESS, PIPE_CACHE_HALF, INPUTS_MEDIUM)
            }
            RamClass.SMALL -> when (choice) {
                Choice.NORMAL -> Quad(LEVEL_SMALL, VALUES_SMALL_NORMAL, PIPE_CACHE_HALF, INPUTS_MEDIUM)
                Choice.MORE -> Quad(LEVEL_SMALL, VALUES_SMALL_MORE, PIPE_CACHE_DESKTOP, INPUTS_MEDIUM)
                Choice.LESS -> Quad(LEVEL_SMALL, VALUES_SMALL_LESS, PIPE_CACHE_HALF, INPUTS_MIN)
            }
        }
        return Plan(c, choice, EngineStartOptions(opencl, level, values, pipes, inputs))
    }

    private data class Quad(val level: String, val values: String, val pipes: Pair<Int, Int>, val inputs: Int)
}

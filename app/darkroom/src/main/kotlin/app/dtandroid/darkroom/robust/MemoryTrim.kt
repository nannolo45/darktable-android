// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.robust

import app.dtandroid.engine.api.RenderedImage

/**
 * Brief P4a deliverable 3: what to give back for each of Android's memory signals (`ComponentCallbacks2.onTrimMemory`
 * levels, and `onLowMemory`). The open photo's edit is never part of it: the engine keeps its history and undo at
 * every level (engine-api.md 10); snapshots and the open preview stay.
 */
object MemoryTrim {
    /**
     * [engineLevel]: 0 nothing, 1 the engine's cached intermediate buffers and unused image cache entries, 2 also its
     * pipelines and the open image's decoded input (the next render reads the RAW again, about a second on the phone).
     * [thumbnailsKept]: the filmstrip keeps the thumbnails of this many photos around the open one (null: all).
     */
    data class Plan(val engineLevel: Int, val thumbnailsKept: Int?)

    // ComponentCallbacks2.TRIM_MEMORY_* (values of the platform API)
    const val RUNNING_MODERATE = 5
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15
    const val UI_HIDDEN = 20
    const val BACKGROUND = 40
    const val MODERATE = 60
    const val COMPLETE = 80
    /** what the app passes for `onLowMemory` */
    const val LOW_MEMORY = 1000

    fun plan(level: Int): Plan = when {
        // the app is in the background and Android is about to stop processes: give back all that comes back by itself
        level >= BACKGROUND -> Plan(engineLevel = 2, thumbnailsKept = 1)
        // the app left the screen: the engine's intermediate buffers and the far thumbnails go; a return is still quick
        level >= UI_HIDDEN -> Plan(engineLevel = 1, thumbnailsKept = 10)
        // on the screen, and the phone is short of memory: as much as can go without losing the edit
        level >= RUNNING_CRITICAL -> Plan(engineLevel = 2, thumbnailsKept = 5)
        level >= RUNNING_LOW -> Plan(engineLevel = 1, thumbnailsKept = 20)
        level >= RUNNING_MODERATE -> Plan(engineLevel = 1, thumbnailsKept = null)
        else -> Plan(engineLevel = 0, thumbnailsKept = null)
    }

    /** The thumbnails of the [keep] photos nearest to [index] (the open one first). */
    fun keepNear(thumbs: Map<Int, RenderedImage>, index: Int, keep: Int): Map<Int, RenderedImage> =
        if (thumbs.size <= keep) thumbs
        else thumbs.keys.sortedWith(compareBy({ kotlin.math.abs(it - index) }, { it })).take(keep).associateWith { thumbs.getValue(it) }
}

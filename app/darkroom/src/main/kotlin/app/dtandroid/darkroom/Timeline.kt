// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

/**
 * Brief P2f deliverable 1: a small record of when things happened (done, XMP posted, preview render
 * start and end, open requested, first picture, real preview), so the wait after "done" can be shown
 * with numbers. Kept in memory only, at most [MAX] events; tests read it, the app logs nothing from it.
 */
object Timeline {
    data class Event(val name: String, val nanos: Long)

    private const val MAX = 400
    private val events = ArrayList<Event>()

    @Synchronized fun mark(name: String) {
        if (events.size >= MAX) events.removeAt(0)
        events += Event(name, System.nanoTime())
    }

    @Synchronized fun clear() = events.clear()

    @Synchronized fun events(): List<Event> = events.toList()

    /** The first event called [name] at or after [fromNanos], or null. */
    @Synchronized fun first(name: String, fromNanos: Long = Long.MIN_VALUE): Event? =
        events.firstOrNull { it.name == name && it.nanos >= fromNanos }

    /** "name +123 ms" lines relative to the first event. */
    @Synchronized fun text(): String {
        val t0 = events.firstOrNull()?.nanos ?: return ""
        return events.joinToString("\n") { "+${(it.nanos - t0) / 1_000_000} ms\t${it.name}" }
    }
}

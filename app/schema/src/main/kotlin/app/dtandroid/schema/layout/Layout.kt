// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema.layout

/**
 * A darktable module layout ("module groups" preset of the `modulegroups` lib
 * module): the options, the quick access panel and the module groups.
 *
 * darktable stores it as one string (syntax comment above
 * `_preset_retrieve_old_layout_updated` in `src/libs/modulegroups.c`):
 *
 *     OPTIONSꬹQUICKꬹGROUP_1ꬹGROUP_2...
 *     OPTIONS : show_search(0|1) | full_active(0|1)
 *     QUICK   : show(0|1) | | | entry | entry ...
 *     GROUP   : name | icon | (reserved) | module | module ...
 *
 * [LayoutCodec] reads and writes that string exactly as `_preset_from_string`
 * and `_preset_to_string` do, including their quirks (see there).
 */
data class Layout(
    /** "show search line" */
    val showSearch: Boolean = true,
    /** "show all history modules in active group": the active group lists modules in the history, not only those on */
    val fullActive: Boolean = false,
    /** "show quick access panel" */
    val quickAccessShown: Boolean = false,
    /** quick access entries: darktable's action paths (`exposure/exposure`, `agx/curve/contrast`, `lens` for a module's on-off) */
    val quickAccess: List<String> = emptyList(),
    val groups: List<LayoutGroup> = emptyList(),
)

/** A module group: its name, the name of its icon and the operation names of its modules. */
data class LayoutGroup(val name: String, val icon: String, val modules: List<String>)

/** The icons darktable offers for a group (`_manage_editor_group_icon_popup`); unknown names draw as "basic". */
object GroupIcons {
    val all = listOf("basic", "active", "color", "correct", "effect", "favorites", "tone", "grading", "technical")
    /** The icon darktable draws for a stored name (`_buttons_get_icon_fct`). */
    fun drawn(name: String) = if (name in all) name else "basic"
}

object LayoutCodec {
    /** darktable's block separator, U+AB39 */
    const val BLOCK = "ꬹ"

    /**
     * `_preset_from_string`. Quirks kept on purpose, so the app reads a string as desktop does:
     * - search is shown unless the first option is exactly "0"; the active group shows the
     *   history only if the second option is exactly "1";
     * - quick access counts as shown only if its block starts with "1" *and* has at least
     *   one entry (darktable tests for more than three fields);
     * - repeated quick access entries are read once (darktable #19830);
     * - a group block with fewer than three fields is dropped; its third field is ignored.
     * Module names and entries are kept as they are, known or not.
     */
    fun parse(text: String): Layout {
        val gr = gSplit(text, BLOCK)
        var showSearch = true
        var fullActive = false
        if (gr.isNotEmpty()) {
            val o = gSplit(gr[0], "|")
            if (o.getOrNull(0) == "0") showSearch = false
            if (o.size > 1 && o[1] == "1") fullActive = true
        }
        var quickShown = false
        val quick = ArrayList<String>()
        if (gr.size > 1) {
            val q = gSplit(gr[1], "|")
            if (q.size > 3 && q[0] == "1") quickShown = true
            for (j in 3 until q.size) if (q[j] !in quick) quick += q[j]
        }
        val groups = ArrayList<LayoutGroup>()
        for (i in 2 until gr.size) {
            val g = gSplit(gr[i], "|")
            if (g.size > 2) groups += LayoutGroup(g[0], g[1], g.subList(3, g.size).toList())
        }
        return Layout(showSearch, fullActive, quickShown, quick, groups)
    }

    /** `_preset_to_string`. */
    fun write(l: Layout): String = buildString {
        append(if (l.showSearch) '1' else '0').append('|').append(if (l.fullActive) '1' else '0')
        append(BLOCK).append(if (l.quickAccessShown) '1' else '0').append("||")
        for (e in l.quickAccess) append('|').append(e)
        for (g in l.groups) {
            append(BLOCK).append(g.name).append('|').append(g.icon).append('|')
            for (m in g.modules) append('|').append(m)
        }
    }

    /** `g_strsplit`: an empty string gives no fields (Kotlin's split gives one empty field). */
    private fun gSplit(s: String, sep: String): List<String> = if (s.isEmpty()) emptyList() else s.split(sep)
}

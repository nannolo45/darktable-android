// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema.layout

import app.dtandroid.schema.Binding
import app.dtandroid.schema.Control
import app.dtandroid.schema.ModuleSchema
import app.dtandroid.schema.UiSchema

/**
 * Quick access entries and the control schema.
 *
 * darktable names a quick access entry by the action path of a widget
 * (`_action_id` in `src/libs/modulegroups.c`): the module's operation, then the
 * action ids of the sections the widget sits in, then the widget's own action id
 * (`agx/curve/contrast`). An entry of the operation alone (`lens`) is the module's
 * on-off button. The extractor records each widget's action path without the
 * module (S5, `action_path`), so an entry resolves by comparing `<op>/<action_path>`;
 * no label or parameter name is guessed.
 */
object QuickAccess {

    enum class Kind {
        /** the module's on-off button */
        ON_OFF,
        /** a slider, list or switch the app can edit through a parameter */
        CONTROL,
        /** known to darktable but not usable in the app yet: shown disabled with its name */
        UNAVAILABLE,
    }

    /**
     * One entry of a layout's quick access list, resolved against the schema.
     * [treeIndex] orders an entry within its module as the module's panel does (desktop
     * shows quick access widgets in the order of the module's own interface, on-off first).
     */
    data class Entry(
        val id: String,
        val op: String,
        val module: ModuleSchema?,
        val control: Control?,
        val kind: Kind,
        /** darktable's widget name: the control's label, with its section in front ("curve - contrast") */
        val label: String,
        /** why an entry is [Kind.UNAVAILABLE] */
        val reason: String?,
        /**
         * whether desktop darktable shows this entry in its quick access panel; null when the
         * schema cannot tell (no widget with this action path was recorded by the extractor)
         */
        val shownOnDesktop: Boolean?,
        val treeIndex: Int,
    ) {
        val mapped get() = kind != Kind.UNAVAILABLE
    }

    /** Kinds of widgets desktop does not offer in "add widget" (GTK buttons, `_build_menu_from_actions`). */
    private val buttonKinds = setOf("button", "togglebutton", "colorpicker_button")

    fun resolve(schema: UiSchema, id: String): Entry {
        val parts = id.split("/")
        val op = parts[0]
        val m = schema.modules[op]
        if (m == null || !m.hasPanel) {
            return Entry(id, op, m, null, Kind.UNAVAILABLE, id,
                if (m == null) "module \"$op\" is not part of darktable ${schema.darktableVersion}" else "module \"$op\" has no panel",
                shownOnDesktop = false, treeIndex = Int.MAX_VALUE)
        }
        if (m.deprecated) {
            return Entry(id, op, m, null, Kind.UNAVAILABLE, label(m, parts, null),
                "deprecated module: desktop does not show it in quick access", shownOnDesktop = false, treeIndex = Int.MAX_VALUE)
        }
        if (parts.size == 1) {
            return Entry(id, op, m, null, Kind.ON_OFF, "on-off", null, shownOnDesktop = true, treeIndex = -1)
        }
        val path = parts.drop(1).joinToString("/")
        val order = m.allControls().map { it.id }.toList()
        val cid = m.actionPaths.entries.firstOrNull { it.value == path }?.key
            ?: return Entry(id, op, m, null, Kind.UNAVAILABLE, label(m, parts, null),
                "the control schema has no widget of ${m.name} with this action path (darktable ${schema.darktableVersion})",
                shownOnDesktop = null, treeIndex = Int.MAX_VALUE)
        val c = m.control(cid)
        val idx = order.indexOf(cid)
        val lab = label(m, parts, c)
        val kind = m.actionKinds[cid] ?: "unknown"
        val (k, reason) = when (c) {
            is Control.Slider, is Control.ComboBox, is Control.Toggle -> {
                val b = when (c) {
                    is Control.Slider -> c.binding
                    is Control.ComboBox -> c.binding
                    is Control.Toggle -> c.binding
                    else -> Binding.None
                }
                if (b is Binding.None) Kind.UNAVAILABLE to "no parameter: desktop computes this value itself; needs engine support (module_action)"
                else Kind.CONTROL to null
            }
            else -> Kind.UNAVAILABLE to when (kind) {
                "graph", "other" -> "graph: hand-built editor not available yet (tier 2)"
                "tabs" -> "notebook tabs: not available in quick access yet"
                in buttonKinds -> "button: not available yet"
                else -> "$kind: not available yet"
            }
        }
        return Entry(id, op, m, c, k, lab, reason, shownOnDesktop = true, treeIndex = idx)
    }

    /** darktable's `widget_name` (`_basics_init_item`): with a section, "section - label". */
    private fun label(m: ModuleSchema, parts: List<String>, c: Control?): String {
        val own = c?.label?.takeIf { it.isNotBlank() } ?: parts.last()
        return if (parts.size > 2) "${parts[1]} - $own" else own
    }

    /** A widget the "add widget" list offers: its entry id and darktable's menu label (`_action_label`). */
    data class Candidate(val id: String, val op: String, val moduleName: String, val label: String, val recommended: Boolean)

    /**
     * Everything "add widget" offers (`_build_menu_from_actions`): per module that is not
     * hidden and not deprecated, its on-off button and every widget with an action that is
     * not a button, in the order of the module's panel; modules by name.
     */
    fun candidates(schema: UiSchema, recommended: Collection<String>): List<Candidate> {
        val rec = recommended.toSet()
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (m in schema.modules.values.filter { it.hasPanel && !it.deprecated }.sortedBy { it.name.lowercase() }) {
            out += Candidate(m.op, m.op, m.name, "${m.name} - on-off", m.op in rec)
            for (c in m.allControls()) {
                val path = m.actionPaths[c.id] ?: continue
                if (m.actionKinds[c.id] in buttonKinds) continue
                val id = "${m.op}/$path"
                if (!seen.add(id)) continue // a few widgets share an action path; the first in the panel is the one entries resolve to
                val parts = path.split("/")
                val own = c.label?.takeIf { it.isNotBlank() } ?: parts.last()
                val lab = (listOf(m.name) + parts.dropLast(1) + own).joinToString(" - ")
                out += Candidate(id, m.op, m.name, lab, id in rec)
            }
        }
        return out
    }
}

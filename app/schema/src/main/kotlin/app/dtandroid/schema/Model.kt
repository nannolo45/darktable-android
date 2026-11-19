// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

/**
 * The control model the darkroom builds its module panels from: what
 * `schema/ui-schema.json` (docs/experiments/S5-control-schema.md) records for
 * each module, plus the hand corrections in `schema/overrides/`.
 */
data class UiSchema(
    val darktableVersion: String,
    /** the image the schema was extracted from; one schema describes one image class (S5 section 4) */
    val imageDescription: String,
    val modules: Map<String, ModuleSchema>,
)

data class ParamField(
    val name: String,
    val type: String,
    val enumValues: Map<String, Int>,
)

data class ModuleSchema(
    val op: String,
    val name: String,
    val version: Int,
    /** darktable's default module groups, for example ["basic", "technical"] */
    val defaultGroups: List<String>,
    val deprecated: Boolean,
    /** false for the 7 modules that have no panel on desktop */
    val hasPanel: Boolean,
    val controls: List<Control>,
    val visibility: Map<Int, Rule>,
    val sensitivity: Map<Int, Rule>,
    val hiddenInEveryState: Set<Int>,
    val insensitiveInEveryState: Set<Int>,
    /** scalar parameter fields (outside arrays) by name */
    val params: Map<String, ParamField>,
    /** names of the override files applied to this module */
    val overrides: List<String> = emptyList(),
    /** position in darktable's default pipeline order (the module list shows the highest on top) */
    val pipePosition: Int = 0,
    /** search aliases darktable matches besides the name (`dt_iop_get_localized_aliases`), comma separated */
    val aliases: String = "",
    /**
     * darktable's action path of each control that has one, by control id, without the
     * module (`curve/contrast` in AgX): the quick access panel stores `<op>/<path>`.
     */
    val actionPaths: Map<Int, String> = emptyMap(),
    /** the extractor's kind of each control with an action path (slider, combobox, button, graph, ...) */
    val actionKinds: Map<Int, String> = emptyMap(),
    /** brief P2f: an aspect list made usable by an override (crop), with the parameters it drives */
    val aspect: AspectSpec? = null,
) {
    /** Every control in tree order. */
    fun allControls(): Sequence<Control> = sequence {
        suspend fun SequenceScope<Control>.walk(cs: List<Control>) {
            for (c in cs) { yield(c); walk(c.children) }
        }
        walk(controls)
    }

    fun control(id: Int): Control? = allControls().firstOrNull { it.id == id }

    /** The chain of containers from the top of the panel down to control [id], the control last; empty if absent. */
    fun pathTo(id: Int): List<Control> {
        fun find(cs: List<Control>, acc: List<Control>): List<Control>? {
            for (c in cs) {
                val here = acc + c
                if (c.id == id) return here
                find(c.children, here)?.let { return it }
            }
            return null
        }
        return find(controls, emptyList()) ?: emptyList()
    }

    /** Controls a user can change: bound sliders, lists and switches, and override buttons. */
    val editableCount: Int
        get() = allControls().count { it.isEditable }
}

/** How a control reads and writes its value. */
sealed interface Binding {
    /** A parameter by its path (`exposure`, `lift[1]`). */
    data class Param(val path: String) : Binding

    /**
     * A value darktable's panel derives (white balance temperature and tint):
     * read through `module_action(get)`, written through `module_action(set)`
     * with the other fields of the same action kept (override files only).
     */
    data class Action(val get: String, val set: String, val field: String, val fields: List<String>) : Binding

    /** Not editable: the schema has no parameter for it (S5 "unbound"). */
    data object None : Binding

    /**
     * Brief P2f: crop's aspect list (override `aspect_list`): an entry sets the ratio parameters and
     * reshapes the frame as desktop does ([AspectSpec], [CropAspect]).
     */
    data object Aspect : Binding
}

sealed class Control {
    abstract val id: Int
    abstract val label: String?
    abstract val tooltip: String?
    abstract val hiddenByDefault: Boolean
    open val children: List<Control> get() = emptyList()
    open val isEditable: Boolean get() = false

    data class Slider(
        override val id: Int,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
        val binding: Binding,
        val softMin: Double,
        val softMax: Double,
        val hardMin: Double,
        val hardMax: Double,
        val default: Double,
        val step: Double,
        val digits: Int,
        val factor: Double,
        val offset: Double,
        /** unit text after the number, " EV", "%", "°" */
        val format: String,
        /** what desktop showed for the default value, for checking [ValueFormat] */
        val textDefault: String?,
        /** true when the parameter is an integer */
        val integer: Boolean,
        /**
         * The track's colour stops as desktop sets them when the panel is built (P1c), in the order
         * desktop set them; empty for a plain slider.
         */
        val gradient: List<GradientStop> = emptyList(),
        /** desktop recomputes the stops while editing (P1c); the app shows them as extracted for now */
        val gradientRecomputed: Boolean = false,
        /** desktop's fill from the zero point to the value ("fill feedback") */
        val fillFeedback: Boolean = true,
    ) : Control() {
        override val isEditable get() = binding !is Binding.None
    }

    /**
     * One colour stop of a slider (bauhaus.c, dt_bauhaus_slider_set_stop): [position] in 0..1 of the
     * hard range, colour as the 0..1 RGB values desktop gives cairo.
     */
    data class GradientStop(val position: Double, val red: Double, val green: Double, val blue: Double)

    data class Entry(val label: String, val value: Int, val sensitive: Boolean)

    data class ComboBox(
        override val id: Int,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
        val binding: Binding,
        val entries: List<Entry>,
        /** position desktop showed when the panel was walked; used for interface-only lists */
        val currentPos: Int,
        /** "enum", "bool" or "int": how an entry's value is written */
        val paramType: String?,
    ) : Control() {
        override val isEditable get() = binding !is Binding.None
    }

    data class Toggle(
        override val id: Int,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
        val binding: Binding,
        val default: Boolean,
    ) : Control() {
        override val isEditable get() = binding !is Binding.None
    }

    /** A section heading (darktable's `dt_section_label`). */
    data class Section(
        override val id: Int,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
    ) : Control()

    data class Collapsible(
        override val id: Int,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
        val expanded: Boolean,
        override val children: List<Control>,
    ) : Control()

    /** A page of [Tabs] or [Stack]. */
    data class Page(
        override val id: Int,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
        val visibleChild: Boolean,
        override val children: List<Control>,
    ) : Control()

    data class Tabs(
        override val id: Int,
        override val hiddenByDefault: Boolean,
        val pages: List<Page>,
    ) : Control() {
        override val label: String? get() = null
        override val tooltip: String? get() = null
        override val children: List<Control> get() = pages
    }

    /** Pages of which one is shown, chosen by a list (visibility rules on the pages). */
    data class Stack(
        override val id: Int,
        override val hiddenByDefault: Boolean,
        val pages: List<Page>,
    ) : Control() {
        override val label: String? get() = null
        override val tooltip: String? get() = null
        override val children: List<Control> get() = pages
    }

    /** Horizontal box or grid: its children are laid out one per line on a phone. */
    data class Group(
        override val id: Int,
        override val hiddenByDefault: Boolean,
        override val children: List<Control>,
    ) : Control() {
        override val label: String? get() = null
        override val tooltip: String? get() = null
    }

    data class Label(
        override val id: Int,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
    ) : Control()

    /**
     * Something the generic renderer does not build: buttons without an
     * override, colour pickers, graphs and other custom widgets (Tier 2 and 3).
     */
    data class Unsupported(
        override val id: Int,
        val kind: String,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
        val actionPath: String?,
    ) : Control()

    /** Icon buttons that set a parameter by a table (override files; orientation's four buttons). */
    data class ButtonRow(
        override val id: Int,
        override val label: String?,
        override val tooltip: String?,
        override val hiddenByDefault: Boolean,
        val buttons: List<MapButton>,
    ) : Control() {
        override val isEditable get() = buttons.isNotEmpty()
    }

    data class MapButton(
        val actionPath: String,
        /** icon meaning in the icon registry */
        val icon: String,
        val label: String,
        val param: String,
        /** current enum constant -> new constant */
        val map: Map<String, String>,
    )
}

/**
 * A visibility or sensitivity rule from the schema: the control is shown when
 * every condition holds. Conditions name a parameter or an interface-only
 * control; `table` rules list the observed combinations.
 */
data class Rule(
    val node: Int,
    val conditions: List<Condition>,
    val table: Table?,
    /** false when the extractor's own check found states the rule mispredicts; such rules never hide */
    val verified: Boolean,
)

data class Condition(
    /** parameter name, or null for an interface-only control */
    val param: String?,
    val control: Int?,
    val allowed: Set<Int>,
    /** values the extractor saw; outside these the rule says nothing (S5 condition 3) */
    val observed: Set<Int>,
)

/** Variables are parameter names or `control <id>` for interface-only controls. */
data class Table(val variables: List<String>, val visibleFor: Set<List<Int>>, val hiddenFor: Set<List<Int>>)

/**
 * Brief P2f: crop's aspect list as `schema/overrides/crop.json` describes it: the entries with their
 * ratio (d:n, as in src/iop/crop.c; 0:0 freehand, 1:0 the image's own ratio), the parameters the list
 * writes and the frame's four margins (left, top, right, bottom as fractions of the input).
 */
data class AspectSpec(
    val controlId: Int,
    val ratioD: String,
    val ratioN: String,
    val left: String,
    val top: String,
    val right: String,
    val bottom: String,
    val entries: List<AspectEntry>,
    val flipLabel: String,
    val flipIcon: String,
)

data class AspectEntry(val label: String, val d: Int, val n: Int)

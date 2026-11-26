// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** The values visibility and sensitivity rules look at. Null means "not known": the rule does not hide. */
interface RuleInputs {
    fun param(name: String): Int?
    fun control(id: Int): Int?
}

/**
 * Rule inputs from a module instance's parameters (as `modules()` gives them:
 * enums by constant name, bools as booleans) and from the interface-only lists
 * of the schema, which stay at the position desktop showed.
 */
class ParamRuleInputs(private val module: ModuleSchema, private val params: JsonObject) : RuleInputs {
    override fun param(name: String): Int? {
        val p = params[name] as? JsonPrimitive ?: return null
        return when {
            p.isString -> module.params[name]?.enumValues?.get(p.content)
            p.booleanOrNull != null -> if (p.booleanOrNull == true) 1 else 0
            else -> p.doubleOrNull?.takeIf { it.isFinite() }?.toInt()
        }
    }

    override fun control(id: Int): Int? {
        // brief P2j: a list the app computes from the parameters shows what desktop would show
        (module.computed[id] as? ComputedList)?.let { return it.ruleValue(params) }
        val c = module.control(id) as? Control.ComboBox ?: return null
        return c.entries.getOrNull(c.currentPos)?.value
    }
}

object Rules {
    /**
     * Whether [rule] holds. Following S5 (conditions 3 and 4): a value outside
     * what the extractor observed, a combination a table does not list, an
     * unknown input or an unverified rule never hides anything.
     */
    fun holds(rule: Rule, inputs: RuleInputs): Boolean {
        if (!rule.verified) return true
        val t = rule.table
        if (t != null) {
            val tuple = t.variables.map { v ->
                val id = v.removePrefix("control ").toIntOrNull()
                (if (v.startsWith("control ") && id != null) inputs.control(id) else inputs.param(v)) ?: return true
            }
            return when (tuple) {
                in t.visibleFor -> true
                in t.hiddenFor -> false
                else -> true
            }
        }
        return rule.conditions.all { c ->
            val v = (if (c.param != null) inputs.param(c.param) else c.control?.let { inputs.control(it) }) ?: return@all true
            if (c.observed.isNotEmpty() && v !in c.observed) true else v in c.allowed
        }
    }

    /** Whether a control is shown: by its rule, else hidden only if desktop hid it in every observed state. */
    fun isVisible(m: ModuleSchema, c: Control, inputs: RuleInputs): Boolean {
        if (c.id in m.hidden) return false
        val r = m.visibility[c.id]
        // brief P6a: a slider the engine's answer shows or hides (white balance's finetune: desktop shows it only for a
        // camera preset with fine tuning, which the extractor's sweep never met) is decided by that answer
        if (((c as? Control.Slider)?.binding as? Binding.Action)?.hide == true) return r?.let { holds(it, inputs) } ?: true
        return r?.let { holds(it, inputs) } ?: (c.id !in m.hiddenInEveryState)
    }

    /** Whether a control can be used (darktable greys some out). */
    fun isSensitive(m: ModuleSchema, c: Control, inputs: RuleInputs): Boolean {
        val r = m.sensitivity[c.id]
        return r?.let { holds(it, inputs) } ?: (c.id !in m.insensitiveInEveryState)
    }
}

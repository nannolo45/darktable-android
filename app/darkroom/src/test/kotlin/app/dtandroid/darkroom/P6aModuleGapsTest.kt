// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.schema.Control
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Brief P6a (JVM): white balance's settings list on the stand-in engine (its camera: daylight with fine tuning -2..2,
 * cloudy and shade without): the current entry, one history item per choice, the finetune slider shown with the
 * preset's limits only for a preset with fine tuning, "from image area" arming the picker, "user modified" refused.
 */
class P6aModuleGapsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val wb = InstanceKey("temperature", 0)

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        try { withTimeout(10_000) { while (!runCatching(cond).getOrDefault(false)) delay(5) } } catch (e: Exception) { throw AssertionError("timed out: $what") }

    private fun DarkroomController.entries() =
        schema.modules.getValue("temperature").actionLists.getValue(9).let { l -> l to l.entries(state.value.actionLists[wb to 9]) }

    private fun DarkroomController.current(): String? = entries().let { (l, e) -> e.getOrNull(l.currentIndex(e))?.let(l::label) }
    private fun DarkroomController.index(name: String) = entries().let { (l, e) -> e.indexOfFirst { l.label(it) == name } }

    @Test fun choosingAPresetIsOneHistoryItemAndShowsItsFineTuning() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        c.toggleExpanded(wb)
        waitFor("list") { c.entries().second.size == 8 }
        assertEquals("as shot", c.current())
        val finetune = c.schema.modules.getValue("temperature").control(10) as Control.Slider
        // as shot: no fine tuning, the slider is hidden (its value is null)
        assertTrue(c.state.value.actionValues[wb]?.get("finetune_min").let { it == null || it is kotlinx.serialization.json.JsonNull })
        val n0 = c.state.value.history.items.size
        c.chooseActionList(wb, 9, c.index("daylight"))
        waitFor("daylight current") { c.current() == "daylight" }
        assertEquals("one history item", n0 + 1, c.state.value.history.items.size)
        // the finetune slider: the preset's tunings as its limits
        waitFor("finetune range") { (c.state.value.actionValues[wb]?.get("finetune_min") as? JsonPrimitive)?.doubleOrNull == -2.0 }
        val ranged = c.withActionRange(c.state.value, wb, finetune)
        assertEquals(-2.0, ranged.hardMin, 0.0)
        assertEquals(2.0, ranged.hardMax, 0.0)
        assertEquals(0.0, ranged.default, 0.0)
        c.setSlider(wb, finetune.binding, 1.0, false, finetune.id)
        waitFor("tuning 1") { (c.state.value.actionValues[wb]?.get("tuning") as? JsonPrimitive)?.doubleOrNull == 1.0 }
        assertEquals("daylight", c.current())
        assertEquals(n0 + 2, c.state.value.history.items.size)
        // a preset without fine tuning: the slider has no value again
        c.chooseActionList(wb, 9, c.index("cloudy"))
        waitFor("cloudy current") { c.current() == "cloudy" }
        assertTrue(c.state.value.actionValues[wb]?.get("finetune_min") is kotlinx.serialization.json.JsonNull)
        assertEquals(n0 + 3, c.state.value.history.items.size)
        // back to as shot
        c.chooseActionList(wb, 9, c.index("as shot"))
        waitFor("as shot current") { c.current() == "as shot" }
    }

    @Test fun fromImageAreaArmsThePickerAndUserModifiedIsRefused() = runBlocking {
        val (c, _) = TestSetup.controller(tmp.root)
        c.toggleExpanded(wb)
        waitFor("list") { c.entries().second.size == 8 }
        val (l, e) = c.entries()
        assertTrue(l.isSection(e[4]))
        assertFalse(l.isEnabled(e[2]))
        c.chooseActionList(wb, 9, c.index("from image area"))
        assertTrue("armed", c.pickerArmed(c.state.value, wb, 4))
        waitFor("picked") { c.state.value.picker?.readout?.isNotEmpty() == true }
        val n = c.state.value.history.items.size
        c.chooseActionList(wb, 9, c.index("user modified"))
        waitFor("message") { c.state.value.message != null }
        assertNotNull(c.state.value.message)
        assertEquals("nothing written", n, c.state.value.history.items.size)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.darkroom.ui.DarkroomScreen
import app.dtandroid.schema.Control
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots of the darkroom on the JVM (Robolectric native graphics), for
 * looking at, not as golden files: written to build/outputs/screenshots and
 * published by CI. Each test also checks a few facts about what it shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotBase {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    abstract val layout: DarkroomLayout

    private val outDir = File(System.getProperty("dta.screenshots") ?: "build/outputs/screenshots").apply { mkdirs() }

    protected fun show(index: Int = 0, filmstrip: Boolean = false, tokens: DtTokens = DtTokens.load(), setup: suspend DarkroomController.() -> Unit = {}): DarkroomController {
        val (c, _) = TestSetup.controller(tmp.root, index, filmstrip)
        runBlocking { c.setup() }
        compose.setContent {
            DtTheme(tokens) { DarkroomScreen(c, onBack = {}, onDone = {}, layout = layout) }
        }
        settle(c)
        return c
    }

    protected fun settle(c: DarkroomController) {
        compose.waitForIdle()
        runBlocking { c.renderNow() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    protected fun capture(name: String) {
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE

    @Test
    fun collapsedList() {
        show()
        // demosaic and the colour profiles have no power icon (can_switch false)
        compose.onNodeWithContentDescription("Turn off sigmoid").assertExists()
        assertEquals(0, compose.onAllNodesWithContentDescription("Turn off output color profile").fetchSemanticsNodes().size)
        capture("phone-01-collapsed")
    }

    @Test
    fun expandedModule() {
        val c = show { toggleExpanded(InstanceKey("exposure", 0)) }
        settle(c)
        compose.onNodeWithContentDescription("Instances of exposure").assertExists()
        capture("phone-02-expanded-exposure")
    }

    @Test
    fun instancesMenu() {
        val c = show {
            toggleExpanded(InstanceKey("exposure", 0))
            setOverlay(Overlay.Instances(InstanceKey("exposure", 0)))
        }
        settle(c)
        compose.onNode(hasText("delete")).assertExists()
        capture("phone-03-instances-menu")
    }

    @Test
    fun history() {
        val c = show {
            val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
            setSlider(InstanceKey("exposure", 0), e.binding, 1.2, false)
            setEnabled(InstanceKey("sigmoid", 0), false)
            setEnabled(InstanceKey("sigmoid", 0), true)
            historyGoto(state.value.history.items.size - 1)
            setView(BottomView.HISTORY)
        }
        settle(c)
        capture("phone-04-history")
    }

    @Test
    fun readOnlyBanner() {
        show(index = InterfacePreview.READ_ONLY_INDEX)
        compose.onNodeWithContentDescription("read only").assertExists()
        capture("phone-05-read-only")
    }

    @Test
    fun alternativePalette() {
        val alt = javaClass.classLoader!!.getResourceAsStream("dtandroid/theme/alt-tokens.json")!!.readBytes().decodeToString()
        val c = show(tokens = DtTokens.load(alt)) { toggleExpanded(InstanceKey("exposure", 0)) }
        settle(c)
        capture("phone-06-alternative-palette")
    }

    @Test
    fun quickPanel() {
        show { selectGroup(GroupTab.Quick) }
        capture("phone-07-quick")
    }

    @Test
    fun tabsModule() {
        val c = show {
            selectGroup(GroupTab.Configured("color"))
            toggleExpanded(InstanceKey("colorbalancergb", 0))
        }
        settle(c)
        capture("phone-08-tabs-color-balance-rgb")
    }

    @Test
    fun listSheet() {
        val c = show {
            toggleExpanded(InstanceKey("sigmoid", 0))
            val list = TestSetup.schema.modules.getValue("sigmoid").allControls().filterIsInstance<Control.ComboBox>().first()
            setOverlay(Overlay.ListEntries(InstanceKey("sigmoid", 0), list.id))
        }
        settle(c)
        capture("phone-09-list-sheet")
    }

    @Test
    fun filmstripOpen() {
        show(index = 2, filmstrip = true)
        capture("phone-10-filmstrip")
    }

    @Test
    fun exactEntry() {
        val c = show {
            toggleExpanded(InstanceKey("exposure", 0))
            val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
            setOverlay(Overlay.NumberEntry(InstanceKey("exposure", 0), e.id))
        }
        settle(c)
        capture("phone-11-exact-entry")
    }

    @Test
    fun orientationButtons() {
        val c = show { toggleExpanded(InstanceKey("flip", 0)) }
        settle(c)
        compose.onNodeWithContentDescription("rotate 90 degrees clockwise").performClick()
        settle(c)
        assertEquals("ORIENTATION_ROTATE_CW_90_DEG",
            (c.state.value.modules.first { it.op == "flip" }.params["orientation"] as kotlinx.serialization.json.JsonPrimitive).content)
        capture("phone-12-orientation-rotated")
    }

    @Test
    fun expandToggles() {
        val c = show()
        compose.onNode(hasContentDescription("sigmoid")).performClick()
        compose.waitForIdle()
        assertEquals(InstanceKey("sigmoid", 0), c.state.value.expanded)
        compose.onNode(hasContentDescription("sigmoid")).performClick()
        compose.waitForIdle()
        assertEquals(null, c.state.value.expanded)
    }
}

@Config(sdk = [35], qualifiers = "w360dp-h740dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NarrowPhoneScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.PHONE

    @Test
    fun narrowPhone() {
        val c = show { toggleExpanded(InstanceKey("sigmoid", 0)) }
        settle(c)
        capture("phone-13-narrow-360dp")
    }
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletScreenshots : ScreenshotBase() {
    override val layout = DarkroomLayout.TABLET

    @Test
    fun collapsedList() {
        show(filmstrip = true)
        compose.onNodeWithContentDescription("Instances of sigmoid").assertExists() // header icons always visible
        capture("tablet-01-collapsed")
    }

    @Test
    fun expandedModule() {
        val c = show(filmstrip = true) { toggleExpanded(InstanceKey("exposure", 0)) }
        settle(c)
        capture("tablet-02-expanded-exposure")
    }

    @Test
    fun instancesMenu() {
        val c = show(filmstrip = true) { setOverlay(Overlay.Instances(InstanceKey("exposure", 0))) }
        settle(c)
        compose.onNode(hasText("duplicate instance")).assertExists()
        capture("tablet-03-instances-menu")
    }

    @Test
    fun history() {
        val c = show(filmstrip = true) {
            val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
            setSlider(InstanceKey("exposure", 0), e.binding, -0.5, false)
            instanceDuplicate(InstanceKey("exposure", 0))
            historyGoto(state.value.history.items.size - 1)
        }
        settle(c)
        capture("tablet-04-history")
    }

    @Test
    fun readOnlyBanner() {
        show(index = InterfacePreview.READ_ONLY_INDEX, filmstrip = true)
        compose.onNodeWithContentDescription("read only").assertExists()
        capture("tablet-05-read-only")
    }
}

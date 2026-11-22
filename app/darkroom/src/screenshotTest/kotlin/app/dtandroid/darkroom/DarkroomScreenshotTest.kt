// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.darkroom.ui.DarkroomScreen
import app.dtandroid.darkroom.ui.ImportFiles
import app.dtandroid.darkroom.ui.LayoutFiles
import app.dtandroid.schema.Control
import app.dtandroid.ui.theme.DtTheme
import app.dtandroid.ui.theme.DtTokens
import app.dtandroid.ui.theme.Skin
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

    /**
     * The darkroom after [setup]. P2a's screenshots start on the active modules ([group]); darktable's own
     * opening group (the default layout's second group) is shown with `group = null`.
     */
    protected fun show(index: Int = 0, filmstrip: Boolean = false, tokens: DtTokens = DtTokens.load(), group: GroupTab? = GroupTab.Active,
                       setup: suspend DarkroomController.() -> Unit = {}): DarkroomController {
        val (c, _) = TestSetup.controller(tmp.root, index, filmstrip)
        if (group != null) c.selectGroup(group)
        runBlocking { c.setup() }
        compose.setContent {
            DtTheme(tokens) { DarkroomScreen(c, onBack = {}, onDone = {}, layout = layout, layoutFiles = NoFiles, importFiles = NoImport) }
        }
        settle(c)
        return c
    }

    /** The app shell's file picker stands in as a no-op, so the editor shows "import…" and "export…" as in the app. */
    private object NoFiles : LayoutFiles {
        override fun pickImport() = Unit
        override fun export(fileName: String, bytes: ByteArray) = Unit
    }

    /** brief P3b: the file picker for styles and presets, as a no-op, so the sheets show "import…" as in the app */
    private object NoImport : ImportFiles {
        override fun pickImport() = Unit
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

    // brief PUI2a: the modern skin (tokens only; the existing screenshots above stay on the darktable skin)
    private val modern get() = DtTokens.load(Skin.MODERN)

    @Test
    fun modernModuleList() {
        val c = show(tokens = modern) { toggleExpanded(InstanceKey("exposure", 0)) }
        settle(c)
        compose.onNodeWithContentDescription("Instances of exposure").assertExists()
        capture("phone-20-modern-modules")
    }

    @Test
    fun modernHistory() {
        val c = show(tokens = modern) {
            val e = TestSetup.schema.modules.getValue("exposure").allControls().filterIsInstance<Control.Slider>().first { it.label == "exposure" }
            setSlider(InstanceKey("exposure", 0), e.binding, 1.2, false)
            setEnabled(InstanceKey("sigmoid", 0), false)
            setEnabled(InstanceKey("sigmoid", 0), true)
            historyGoto(state.value.history.items.size - 1)
            setView(BottomView.HISTORY)
        }
        settle(c)
        capture("phone-21-modern-history")
    }

    @Test
    fun modernSheet() {
        val c = show(tokens = modern) {
            toggleExpanded(InstanceKey("exposure", 0))
            setOverlay(Overlay.Instances(InstanceKey("exposure", 0)))
        }
        settle(c)
        compose.onNode(hasText("delete")).assertExists()
        capture("phone-22-modern-sheet")
    }

    @Test
    fun quickPanel() {
        // P2a showed an invented quick panel here (with white balance temperature); now darktable's
        // default layout's quick access panel: sigmoid first, color calibration, no white balance
        show { selectGroup(GroupTab.Quick) }
        compose.onNodeWithContentDescription("go to the full version of the sigmoid module").assertExists()
        assertEquals(0, compose.onAllNodesWithText("white balance").fetchSemanticsNodes().size)
        capture("phone-07-quick")
    }

    @Test
    fun quickPanelFurtherDown() {
        show { selectGroup(GroupTab.Quick) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("go to the full version of the color calibration module"))
        compose.waitForIdle()
        capture("phone-14-quick-color-calibration")
    }

    @Test
    fun defaultLayout() {
        show(group = null)
        for (d in listOf("active modules", "quick access panel", "group base", "group color", "group correct", "group effect", "search modules"))
            compose.onNodeWithContentDescription(d).assertExists()
        capture("phone-15-default-layout-color-group")
    }

    @Test
    fun searchLine() {
        val c = show { setSearch("col") }
        // every module whose name contains "col", last in the pipe on top; color calibration sits
        // early in the pipe, so the lazy list composes it only once scrolled to
        val found = c.visibleModules(c.state.value).map { it.op }
        org.junit.Assert.assertTrue("search \"col\" lists: $found", "channelmixerrgb" in found && "colorbalancergb" in found)
        capture("phone-16-search")
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("color calibration"))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("color calibration").assertExists()
    }

    @Test
    fun layoutsMenu() {
        show { setOverlay(Overlay.Layouts) }
        compose.onNode(hasText("manage module layouts…")).assertExists()
        compose.onNode(hasText("search only")).assertExists()
        capture("phone-17-layouts-menu")
    }

    @Test
    fun editorBuiltin() {
        show { openLayoutEditor() }
        compose.onNode(hasText("this is a built-in read-only preset. duplicate it if you want to make changes")).assertExists()
        capture("phone-18-editor-builtin")
    }

    @Test
    fun editorUserLayout() {
        val ctl = show {
            openLayoutEditor()
            layoutEditor.applyName(LayoutEditor.NameAction.DUPLICATE, "my layout")
            layoutEditor.addWidget("agx/curve/contrast")
        }
        // one "move group down" per group of the duplicated layout (the editor's column composes all of them)
        val groups = ctl.layoutEditor.state.value.layout.groups.size
        assertEquals(4, groups)
        assertEquals(groups, compose.onAllNodesWithContentDescription("move group down").fetchSemanticsNodes().size)
        compose.onNode(hasText("import…")).assertExists()
        compose.onNode(hasText("export…")).assertExists()
        capture("phone-19-editor-user-layout")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("module groups"))
        compose.waitForIdle()
        capture("phone-20-editor-groups")
    }

    @Test
    fun editorAddWidget() {
        show {
            openLayoutEditor()
            layoutEditor.applyName(LayoutEditor.NameAction.DUPLICATE, "my layout")
            layoutEditor.ask(LayoutEditor.Dialog.AddWidget)
        }
        compose.onNode(hasText("recommended")).assertExists()
        capture("phone-21-editor-add-widget")
    }

    @Test
    fun editorAddModule() {
        show {
            openLayoutEditor()
            layoutEditor.applyName(LayoutEditor.NameAction.DUPLICATE, "my layout")
            layoutEditor.ask(LayoutEditor.Dialog.AddModule(0))
        }
        capture("phone-22-editor-add-module")
    }

    @Test
    fun importedLayout() {
        val desk = javaClass.classLoader!!.getResourceAsStream("dtpreset/desktop-export.dtpreset")!!.readBytes()
        show {
            openLayoutEditor()
            layoutEditor.import(listOf("modulegroups_probe layout.dtpreset" to desk))
            closeLayoutEditor()
            chooseLayout("probe layout")
            selectGroup(GroupTab.Quick)
        }
        compose.onNodeWithContentDescription("group my edits").assertExists()
        capture("phone-23-imported-layout")
    }

    @Test
    fun tabsModule() {
        val c = show {
            selectGroup(GroupTab.Group(1)) // "color" in the default layout
            toggleExpanded(InstanceKey("colorbalancergb", 0))
        }
        settle(c)
        capture("phone-08-tabs-color-balance-rgb")
    }

    /**
     * P1c: color balance rgb's "4 ways" tab, whose hue, chroma and luminance sliders carry colour stops
     * in desktop's panel: their tracks show the gradient from the schema, with marker and value.
     */
    @Test
    fun sliderColours() {
        val c = show {
            selectGroup(GroupTab.Group(1)) // "color" in the default layout
            toggleExpanded(InstanceKey("colorbalancergb", 0))
        }
        settle(c)
        compose.onAllNodesWithText("4 ways")[0].performClick()
        settle(c)
        val fourWays = TestSetup.schema.modules.getValue("colorbalancergb").allControls()
            .filterIsInstance<Control.Page>().first { it.label == "4 ways" }
        fun all(c: Control): List<Control> = listOf(c) + c.children.flatMap { all(it) }
        val coloured = all(fourWays).filterIsInstance<Control.Slider>().filter { it.gradient.isNotEmpty() }
        org.junit.Assert.assertTrue("4 ways has sliders with colour stops: ${coloured.size}", coloured.size >= 8)
        // the first hue slider is on screen
        compose.onAllNodesWithContentDescription("hue")[0].assertExists()
        capture("phone-24-slider-colours-4-ways")
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
    fun sliderDragChangesTheValueAndSealsOneStep() {
        val c = show { toggleExpanded(InstanceKey("exposure", 0)) }
        settle(c)
        val n0 = c.state.value.history.items.size
        compose.onNode(hasContentDescription("exposure") and hasStateDescription("+0.700 EV"))
            .performTouchInput { swipeRight(startX = centerX, endX = right, durationMillis = 400) }
        settle(c)
        Thread.sleep(300)
        settle(c)
        val v = (c.state.value.modules.first { it.op == "exposure" }.params["exposure"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
        org.junit.Assert.assertTrue("exposure after the drag: $v", v > 1.5)
        assertEquals("one drag is one history step", n0 + 1, c.state.value.history.items.size)
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

    @Test
    fun defaultLayout() {
        show(filmstrip = true, group = null)
        compose.onNodeWithContentDescription("group color").assertExists()
        capture("tablet-06-default-layout")
    }

    @Test
    fun quickPanel() {
        show(filmstrip = true) { selectGroup(GroupTab.Quick) }
        capture("tablet-07-quick")
    }

    @Test
    fun editor() {
        show {
            openLayoutEditor()
            layoutEditor.applyName(LayoutEditor.NameAction.DUPLICATE, "my layout")
        }
        capture("tablet-08-editor")
    }
}

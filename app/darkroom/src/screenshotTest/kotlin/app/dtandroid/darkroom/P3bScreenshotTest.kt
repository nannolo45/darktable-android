// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.dtandroid.darkroom.ui.DarkroomLayout
import app.dtandroid.engine.api.StyleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Brief P3b deliverable 7: the presets list of a module, the style tree (favourites and recently used on top), the
 * search, a style's content with the two modes, the import result and its question, the forms to store a preset and
 * create a style; phone (sheets) and tablet (the lists beside the photo).
 */
abstract class P3bScreens : ScreenshotBase() {
    private val exposure = InstanceKey("exposure", 0)
    private val builtin = "_builtin_scene-referred default"

    /** Styles made by the test, named as darktable's shipped ones and as a user's pack would be. */
    private fun files(c: DarkroomController): List<Pair<String, ByteArray>> {
        val exp = c.state.value.modules.first { it.op == "exposure" }
        val cb = c.state.value.modules.first { it.op == "colorbalancergb" }
        val out = ArrayList<Pair<String, ByteArray>>()
        for (i in 1..12) out += "look $i.dtstyle" to P3bFiles.style("pack|look %02d".format(i), if (i % 3 == 0) "a test look" else "",
            listOf(P3bFiles.item(exp, mapOf("exposure" to JsonPrimitive(0.1 * i))), P3bFiles.item(cb, mapOf("vibrance" to JsonPrimitive(0.02 * i)))))
        for (i in 1..30) out += "camera $i.dtstyle" to P3bFiles.style("_l10n_darktable|_l10n_camera styles|Generic|Camera ${100 + i}", "",
            listOf(P3bFiles.item(exp)))
        out += "mono.dtstyle" to P3bFiles.style("monochrome", "black and white", listOf(P3bFiles.item(cb, mapOf("saturation_global" to JsonPrimitive(-1.0)))))
        return out
    }

    private fun showStyles(extra: suspend DarkroomController.() -> Unit = {}): DarkroomController {
        val c = show(filmstrip = layout == DarkroomLayout.TABLET) {
            stylesPresets.import(files(this), tmp.newFolder())
            stylesPresets.setPage(null)
            stylesPresets.toggleFavorite("monochrome")
            stylesPresets.applyStyle("pack|look 03", StyleMode.APPEND)
            stylesPresets.setNote(null)
            stylesPresets.toggleOpen("pack")
            setOverlay(Overlay.Styles)
            extra()
        }
        settle(c)
        return c
    }

    protected fun presetList(name: String) {
        val c = show {
            toggleExpanded(exposure)
            stylesPresets.loadPresets(exposure)
            stylesPresets.storePreset(exposure, "look|warm", "a test preset")
            stylesPresets.storePreset(exposure, "soft", "")
            stylesPresets.applyPreset(exposure, builtin)
            stylesPresets.setNote(null)
            setOverlay(Overlay.Presets(exposure))
        }
        settle(c)
        compose.onNodeWithText("store new preset…").assertExists()
        compose.onNodeWithContentDescription("preset scene-referred default").assertExists()
        compose.onNodeWithContentDescription("folder look").assertExists()
        capture("$name-p3b-01-presets")
        // a folder opened by touch
        compose.onNodeWithContentDescription("folder look").performClick()
        settle(c)
        compose.onNodeWithContentDescription("preset look | warm").assertExists()
        capture("$name-p3b-02-presets-folder-open")
    }

    protected fun styleTree(name: String) {
        val c = showStyles()
        compose.onNodeWithText("favorites").assertExists()
        compose.onNodeWithText("recently used").assertExists()
        compose.onNodeWithContentDescription("folder darktable").assertExists()
        capture("$name-p3b-03-style-tree")
        c.stylesPresets.setSearch("look 1")
        settle(c)
        compose.onNodeWithContentDescription("style pack | look 10").assertExists()
        capture("$name-p3b-04-style-search")
    }

    protected fun styleDetail(name: String) {
        val c = showStyles { stylesPresets.setPage(SpPage.StyleDetail("pack|look 07")) }
        compose.onNodeWithText("apply").assertExists()
        compose.onNode(hasText("append")).assertExists()
        compose.onNode(hasText("overwrite")).assertExists()
        capture("$name-p3b-05-style-detail")
        // "apply" by touch: one undo step
        val n = c.state.value.history.items.size
        compose.onNodeWithText("apply").performScrollTo().performClick()
        settle(c)
        val end = System.currentTimeMillis() + 10_000
        while (c.state.value.history.items.size == n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(n + 2, c.state.value.history.items.size)
        settle(c)
        capture("$name-p3b-06-style-applied")
    }

    protected fun importResult(name: String) {
        val c = show(filmstrip = layout == DarkroomLayout.TABLET) {
            val exp = state.value.modules.first { it.op == "exposure" }
            stylesPresets.import(listOf(
                "film look.dtstyle" to P3bFiles.style("film|look", "", listOf(P3bFiles.item(exp))),
                "newer module.dtstyle" to P3bFiles.style("film|newer", "", listOf(P3bFiles.Item("exposure", exp.version + 1, null))),
                "readme.txt" to "text".toByteArray(),
                "bright.dtpreset" to P3bFiles.preset("bright", "exposure", exp.version, null),
                "unknown.dtpreset" to P3bFiles.preset("x", "p3bnosuchmodule", 1, null),
            ), tmp.newFolder())
            setOverlay(Overlay.Styles)
        }
        settle(c)
        assertTrue(c.stylesPresets.state.value.page is SpPage.ImportResult)
        compose.onNodeWithText("readme.txt").assertExists()
        capture("$name-p3b-07-import-result")
        // the question for a name that exists
        val again = CoroutineScope(Dispatchers.Default).async {
            c.stylesPresets.import(listOf("film look.dtstyle" to P3bFiles.style("film|look", "", emptyList()),
                "film look 2.dtstyle" to P3bFiles.style("film|newer", "", emptyList())), tmp.newFolder())
        }
        val end = System.currentTimeMillis() + 10_000
        while (c.stylesPresets.state.value.page !is SpPage.ImportAsk && System.currentTimeMillis() < end) Thread.sleep(10)
        settle(c)
        compose.onNodeWithText("overwrite style?").assertExists()
        capture("$name-p3b-08-import-question")
        c.stylesPresets.answer(ImportAnswer.CANCEL, true)
        runBlocking { again.await() }
    }

    protected fun forms(name: String) {
        val c = show {
            toggleExpanded(exposure)
            setOverlay(Overlay.Presets(exposure))
            stylesPresets.setPage(SpPage.PresetStore)
        }
        settle(c)
        compose.onNodeWithText("store").assertExists()
        capture("$name-p3b-09-store-preset")
        c.setOverlay(Overlay.Styles)
        c.stylesPresets.setPage(SpPage.StyleCreate)
        settle(c)
        compose.onNodeWithText("create new style").assertExists()
        capture("$name-p3b-10-create-style")
    }
}

@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneP3bScreenshots : P3bScreens() {
    override val layout = DarkroomLayout.PHONE

    @Test fun presets() = presetList("phone")
    @Test fun styles() = styleTree("phone")
    @Test fun detail() = styleDetail("phone")
    @Test fun importResults() = importResult("phone")
    @Test fun forms() = forms("phone")
}

@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletP3bScreenshots : P3bScreens() {
    override val layout = DarkroomLayout.TABLET

    @Test fun presets() = presetList("tablet")
    @Test fun styles() = styleTree("tablet")
    @Test fun detail() = styleDetail("tablet")
    @Test fun importResults() = importResult("tablet")
    @Test fun forms() = forms("tablet")
}

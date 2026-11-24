// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.EnginePaths
import app.dtandroid.engine.api.ParamValue
import app.dtandroid.engine.api.PasteMode
import app.dtandroid.engine.fake.FakeEngine
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
import java.io.File

/**
 * Brief P3j: the selective copy sheet's defaults (the modules a plain copy takes ticked, those unsafe to copy not),
 * the remembered paste mode, a paste onto the open photo, and a paste onto several photos of the series on the
 * stand-in engine (their XMP written, the photo source told, the darkroom's photo open again).
 */
class P3jCopyPasteTest {
    @get:Rule val tmp = TemporaryFolder()

    private suspend fun waitFor(what: String, ms: Long = 20_000, cond: () -> Boolean) =
        withTimeout(ms) { while (!cond()) delay(10) }.also { assertTrue(what, cond()) }

    private suspend fun exposureOf(c: DarkroomController): Double =
        c.engineRead { e -> (e.modules().first { it.op == "exposure" && it.priority == 0 }.params["exposure"] as JsonPrimitive).doubleOrNull!! }

    @Test fun copySheetTicksWhatAPlainCopyTakes() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root)
        c.engineChange { it.paramSet("exposure", 0, "exposure", ParamValue.of(0.8)) }
        c.copyPaste.openCopySheet()
        val sheet = c.copyPaste.state.value.sheet
        assertNotNull("the sheet opened", sheet)
        val byOp = sheet!!.lines.associateBy { it.item.op }
        assertTrue("exposure is ticked", byOp["exposure"]!!.checked)
        for (op in listOf("temperature", "rawprepare", "flip")) byOp[op]?.let { assertFalse("$op is not ticked", it.checked) }
        assertTrue(sheet.lines.all { it.checked == it.item.safe })
        c.copyPaste.selectAll(false)
        assertEquals(null, c.copyPaste.selectionOf(c.copyPaste.state.value.sheet!!))
        c.copyPaste.toggle(byOp["exposure"]!!.item.num)
        c.copyPaste.copySelected()
        val cb = c.copyPaste.state.value.clipboard
        assertNotNull(cb)
        assertTrue(cb!!.parts)
        assertEquals(listOf("exposure"), cb.items.filter { it.selected == true }.map { it.op })
    }

    @Test fun pasteModeIsRemembered() = runBlocking<Unit> {
        val f = File(tmp.root, "settings/paste-mode.txt")
        val (c, _) = TestSetup.controller(File(tmp.root, "a"))
        assertEquals(PasteMode.APPEND, c.copyPaste.state.value.mode)
        c.copyPaste.modeFile = f
        c.copyPaste.setMode(PasteMode.OVERWRITE)
        val (c2, _) = TestSetup.controller(File(tmp.root, "b"))
        c2.copyPaste.modeFile = f
        assertEquals(PasteMode.OVERWRITE, c2.copyPaste.state.value.mode)
    }

    @Test fun pasteOntoTheOpenPhotoAndOntoSeveralPhotos() = runBlocking<Unit> {
        val (c, _) = TestSetup.controller(tmp.root)
        val left = mutableListOf<String>()
        c.source = object : PhotoSource {
            override suspend fun prepare(item: SeriesItem, progress: (String, Float?) -> Unit) = item
            override fun left(item: SeriesItem) { synchronized(left) { left += item.fileName } }
        }
        c.copyPaste.paste()
        assertEquals(CopyPaste.NOTHING_COPIED, c.state.value.message)
        c.engineChange { it.paramSet("exposure", 0, "exposure", ParamValue.of(0.8)) }
        c.copyPaste.copy()
        assertNotNull(c.copyPaste.state.value.clipboard)
        c.copyPaste.paste()
        assertEquals("this is the photo the history was copied from", c.state.value.message)

        // onto the open photo of another index
        val items = c.state.value.series.items
        assertTrue("the series has at least three photos", items.size >= 3)
        c.openPhoto(1)
        waitFor("photo 2 open") { c.state.value.info != null && c.state.value.index == 1 }
        c.copyPaste.paste(PasteMode.APPEND)
        assertEquals(0.8, exposureOf(c), 1e-6)

        // onto photos 3 and 4 while photo 2 stays open in the darkroom (photo 1 is the one copied from: refused, as desktop)
        c.copyPaste.choosePhotos()
        c.copyPaste.togglePhoto(2)
        c.copyPaste.togglePhoto(3)
        c.copyPaste.pasteOntoChosen()
        waitFor("the paste ends") { c.copyPaste.state.value.job?.running == false }
        val job = c.copyPaste.state.value.job!!
        assertEquals(job.message, 2, job.done)
        assertEquals(0, job.failed)
        waitFor("the darkroom's photo is open again") { c.state.value.info != null && c.state.value.index == 1 }
        assertTrue(left.containsAll(listOf(items[2].fileName, items[3].fileName)))

        // each pasted XMP opens with the copied exposure on a fresh engine
        val e = InterfacePreview.engine(TestSetup.schemaText, FakeEngine.Timing.NONE)
        e.init(EnginePaths(tmp.root.path, tmp.root.path, tmp.root.path, tmp.root.path, tmp.root.path))
        for (i in listOf(2, 3)) {
            assertTrue("XMP of photo ${i + 1} written", File(items[i].xmpPath).isFile)
            e.open(items[i].rawPath, items[i].xmpPath)
            val v = (e.modules().first { it.op == "exposure" && it.priority == 0 }.params["exposure"] as JsonPrimitive).doubleOrNull!!
            assertEquals(0.8, v, 1e-6)
            e.close()
        }
    }
}

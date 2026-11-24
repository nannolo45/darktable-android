// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Behaviour every [DarktableEngine] must show, written against
 * docs/design/engine-api.md sections 1 and 2 only (no pixel parity). The fake
 * engine runs it on the JVM; the JNI engine is meant to run the same class on
 * the emulator later, with its own [newEngine], [sampleRaw] and
 * [writeXmpWithUnknownModule].
 */
abstract class DarktableEngineContract {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A started engine is not expected: the contract calls [DarktableEngine.init] itself. */
    abstract fun newEngine(): DarktableEngine

    /** A RAW file the engine can open. */
    abstract fun sampleRaw(): String

    /** Write an XMP for [sampleRaw] whose history uses a module the engine does not know. */
    abstract fun writeXmpWithUnknownModule(file: File)

    protected lateinit var engine: DarktableEngine

    /**
     * Start [engine] for one test. The default calls [DarktableEngine.init] with a fresh folder for
     * everything (enough for the fake). A real engine needs its unpacked data and module folders, and
     * darktable is not known to survive a second start in one process (G0 report, open question 1):
     * the JNI suite overrides this to start the engine once per process.
     */
    protected open suspend fun startEngine(e: DarktableEngine) {
        val d = tmp.newFolder("engine")
        e.init(EnginePaths(d.path, d.path, d.path, d.path, d.path))
    }

    /** End one test. The default shuts the engine down; the JNI suite closes the session and restores threads. */
    protected open suspend fun stopEngine(e: DarktableEngine) = e.shutdown()

    @Before
    fun startEngineForTest() = runBlocking<Unit> {
        engine = newEngine()
        startEngine(engine)
    }

    @After
    fun stopEngineAfterTest() = runBlocking<Unit> { stopEngine(engine) }

    protected fun expect(code: EngineError, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected $code")
        } catch (e: EngineException) {
            assertEquals("${e.message}", code, e.error)
        }
    }

    private suspend fun instance(op: String, priority: Int = 0) =
        engine.modules().first { it.op == op && it.priority == priority }

    private suspend fun exposureValue(priority: Int = 0) =
        (instance("exposure", priority).params["exposure"] as JsonPrimitive).doubleOrNull!!

    private suspend fun openSample() = engine.open(sampleRaw(), null)

    private fun meanOf(img: RenderedImage): Double {
        var s = 0L
        for (i in 0 until img.width * img.height) for (c in 0..2) s += img.rgba[i * 4 + c].toInt() and 0xff
        return s.toDouble() / (img.width * img.height * 3)
    }

    // ----------------------------------------------------------------- 2.1, 2.3

    @Test
    fun infoAndStateErrors() = runBlocking<Unit> {
        val info = engine.engineInfo()
        assertEquals(1, info.apiVersion)
        assertTrue(info.threads >= 1)
        expect(EngineError.STATE) { engine.modules() }
        expect(EngineError.STATE) { engine.imageInfo() }
        // P2b: dtbridge.h is the truth here: n <= 0 restores the count of init, a larger n is clamped
        val initial = info.threads
        engine.setThreads(1)
        assertEquals(1, engine.engineInfo().threads)
        engine.setThreads(initial + 100)
        assertEquals(initial, engine.engineInfo().threads)
        engine.setThreads(1)
        engine.setThreads(0)
        assertEquals(initial, engine.engineInfo().threads)
        // an empty CPU list removes the pinning; a CPU id the process cannot use is refused
        engine.setThreadCpus(emptyList())
        expect(EngineError.ARG) { engine.setThreadCpus(listOf(-1)) }
    }

    @Test
    fun openGivesImageInfoAndModulesInPipelineOrder() = runBlocking<Unit> {
        openSample()
        val info = engine.imageInfo()
        assertTrue(info.width > 0 && info.height > 0)
        assertFalse(info.readOnly)
        val mods = engine.modules()
        assertTrue(mods.size > 50)
        assertEquals(mods.map { it.iopOrder }.sorted(), mods.map { it.iopOrder })
        val byOp = mods.associateBy { it.op }
        for (op in listOf("demosaic", "colorin", "colorout", "rawprepare")) assertFalse(op, byOp.getValue(op).canSwitch)
        for (op in listOf("exposure", "sigmoid", "colorbalancergb")) assertTrue(op, byOp.getValue(op).canSwitch)
        assertTrue(byOp.getValue("flip").oneInstance)
        assertTrue(byOp.getValue("exposure").params.containsKey("exposure"))
        engine.close()
        expect(EngineError.STATE) { engine.modules() }
    }

    // ----------------------------------------------------------------- 2.4

    @Test
    fun paramSetChangesValueSwitchesOnAndGroupsUntilSealed() = runBlocking<Unit> {
        openSample()
        engine.setEnabled("colorbalancergb", 0, false)
        val n0 = engine.history().items.size
        engine.paramSet("colorbalancergb", 0, "vibrance", ParamValue.of(0.1))
        engine.paramSet("colorbalancergb", 0, "vibrance", ParamValue.of(0.2))
        engine.paramSet("colorbalancergb", 0, "vibrance", ParamValue.of(0.3))
        var h = engine.history()
        assertEquals("consecutive param_set calls merge", n0 + 1, h.items.size)
        assertEquals(h.items.size, h.end)
        val cb = instance("colorbalancergb")
        assertTrue("param_set switches the module on", cb.enabled)
        assertEquals(0.3, (cb.params["vibrance"] as JsonPrimitive).doubleOrNull!!, 1e-6)
        engine.historySeal()
        engine.paramSet("colorbalancergb", 0, "vibrance", ParamValue.of(0.4))
        h = engine.history()
        assertEquals("a sealed group is not extended", n0 + 2, h.items.size)
        assertTrue(h.items.last().enabled)
        assertEquals("colorbalancergb", h.items.last().op)
    }

    @Test
    fun paramErrors() = runBlocking<Unit> {
        openSample()
        expect(EngineError.TYPE) { engine.paramSet("exposure", 0, "exposure", ParamValue.of(true)) }
        expect(EngineError.RANGE) { engine.paramSet("exposure", 0, "exposure", ParamValue.of(100.0)) }
        expect(EngineError.NOT_FOUND) { engine.paramSet("exposure", 0, "no_such_field", ParamValue.of(1.0)) }
        expect(EngineError.NOT_FOUND) { engine.paramSet("no_such_module", 0, "x", ParamValue.of(1.0)) }
        expect(EngineError.NOT_FOUND) { engine.paramSet("exposure", 7, "exposure", ParamValue.of(1.0)) }
        expect(EngineError.TYPE) { engine.paramSet("exposure", 0, "mode", ParamValue.of(1)) }
        expect(EngineError.RANGE) { engine.paramSet("exposure", 0, "mode", ParamValue.enum("NO_SUCH_CONSTANT")) }
        engine.paramSet("exposure", 0, "mode", ParamValue.enum("EXPOSURE_MODE_DEFLICKER"))
        assertEquals("EXPOSURE_MODE_DEFLICKER", (instance("exposure").params["mode"] as JsonPrimitive).content)
        engine.paramSet("exposure", 0, "compensate_exposure_bias", ParamValue.of(true))
    }

    @Test
    fun paramsSetIsOneStep() = runBlocking<Unit> {
        openSample()
        val n0 = engine.history().items.size
        engine.paramsSet("sigmoid", 0, mapOf("middle_grey_contrast" to ParamValue.of(2.0), "contrast_skewness" to ParamValue.of(0.5)))
        assertEquals(n0 + 1, engine.history().items.size)
        val p = instance("sigmoid").params
        assertEquals(2.0, (p["middle_grey_contrast"] as JsonPrimitive).doubleOrNull!!, 1e-6)
        assertEquals(0.5, (p["contrast_skewness"] as JsonPrimitive).doubleOrNull!!, 1e-6)
        // nothing changes when one value is refused
        expect(EngineError.RANGE) {
            engine.paramsSet("sigmoid", 0, mapOf("middle_grey_contrast" to ParamValue.of(2.5), "contrast_skewness" to ParamValue.of(9.0)))
        }
        assertEquals(2.0, (instance("sigmoid").params["middle_grey_contrast"] as JsonPrimitive).doubleOrNull!!, 1e-6)
    }

    @Test
    fun setEnabledAndCanSwitch() = runBlocking<Unit> {
        openSample()
        expect(EngineError.ARG) { engine.setEnabled("demosaic", 0, false) }
        engine.setEnabled("exposure", 0, false)
        assertFalse(instance("exposure").enabled)
        assertFalse(engine.history().items.last().enabled)
        engine.setEnabled("exposure", 0, true)
        assertTrue(instance("exposure").enabled)
    }

    @Test
    fun moduleResetRestoresDefaults() = runBlocking<Unit> {
        openSample()
        val before = instance("colorbalancergb").params
        engine.paramSet("colorbalancergb", 0, "contrast", ParamValue.of(0.3))
        assertNotEquals(before, instance("colorbalancergb").params)
        engine.moduleReset("colorbalancergb", 0)
        assertEquals(before, instance("colorbalancergb").params)
    }

    @Test
    fun moduleActions() = runBlocking<Unit> {
        openSample()
        expect(EngineError.NOT_FOUND) { engine.moduleAction("exposure", 0, "no_such_action") }
        engine.moduleAction("temperature", 0, "set_wb", buildJsonObject { put("temperature", 6500.0); put("tint", 1.0) })
        val wb = engine.moduleAction("temperature", 0, "get_wb") as JsonObject
        assertEquals(6500.0, (wb["temperature"] as JsonPrimitive).doubleOrNull!!, 1.0)
        assertEquals(1.0, (wb["tint"] as JsonPrimitive).doubleOrNull!!, 0.01)
    }

    // ----------------------------------------------------------------- 2.5

    @Test
    fun instancesNewDuplicateMoveRenameDelete() = runBlocking<Unit> {
        openSample()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(1.5))
        val p1 = engine.instanceNew("exposure", 0)
        assertEquals(1, p1)
        val p2 = engine.instanceDuplicate("exposure", 0)
        assertEquals(2, p2)
        assertEquals(1.5, exposureValue(p2), 1e-6)
        assertEquals(3, engine.modules().count { it.op == "exposure" })
        expect(EngineError.ARG) { engine.instanceNew("flip", 0) }
        expect(EngineError.ARG) { engine.instanceDuplicate("temperature", 0) }

        val order = { runBlocking { engine.modules().filter { it.op == "exposure" }.map { it.priority } } }
        val before = order()
        engine.instanceMove("exposure", before.first(), +1)
        assertNotEquals(before, order())

        engine.instanceRename("exposure", p1, "shadows lift")
        assertEquals("shadows lift", instance("exposure", p1).instanceName)

        engine.instanceDelete("exposure", p1)
        assertEquals(2, engine.modules().count { it.op == "exposure" })
        assertTrue(engine.history().items.none { it.op == "exposure" && it.instanceName == "shadows lift" })
        // deleting the base instance renumbers: one instance has priority 0 again
        engine.instanceDelete("exposure", 0)
        val left = engine.modules().filter { it.op == "exposure" }
        assertEquals(listOf(0), left.map { it.priority })
        expect(EngineError.ARG) { engine.instanceDelete("exposure", 0) }
    }

    // ----------------------------------------------------------------- 2.6

    @Test
    fun historyGotoAndDiscardAbove() = runBlocking<Unit> {
        openSample()
        val start = engine.history().items.size
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(1.0)); engine.historySeal()
        val modsAt1 = engine.modules()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(2.0)); engine.historySeal()
        engine.paramSet("colorbalancergb", 0, "vibrance", ParamValue.of(0.5)); engine.historySeal()
        assertEquals(start + 3, engine.history().items.size)

        engine.historyGoto(start + 1)
        assertEquals(start + 1, engine.history().end)
        assertEquals(start + 3, engine.history().items.size)
        // P2b: "in_history" means an item anywhere in the list, also above the end (the bridge's meaning):
        // color balance rgb's item is above the end now; everything else equals the state at that step
        val atGoto = engine.modules()
        assertTrue(atGoto.first { it.op == "colorbalancergb" }.inHistory)
        assertEquals(modsAt1.map { it.copy(inHistory = false) }, atGoto.map { it.copy(inHistory = false) })
        // P2b: the bridge answers ARG for an end outside 0..items (the real engine is the truth here)
        expect(EngineError.ARG) { engine.historyGoto(start + 10) }

        engine.historyGoto(0)
        assertEquals(0, engine.history().end)
        engine.historyGoto(start + 1)
        // going to a step is not an undo step: undo goes back over the last change
        val undoBefore = engine.undoState()
        assertTrue(undoBefore.canUndo)

        // a change from an earlier step discards the items above it
        engine.paramSet("sigmoid", 0, "middle_grey_contrast", ParamValue.of(2.0))
        val h = engine.history()
        assertEquals(start + 2, h.items.size)
        assertEquals(h.items.size, h.end)
        assertEquals("sigmoid", h.items.last().op)
    }

    @Test
    fun compressKeepsTheResult() = runBlocking<Unit> {
        openSample()
        for (v in listOf(0.5, 1.0, 1.5)) { engine.paramSet("exposure", 0, "exposure", ParamValue.of(v)); engine.historySeal() }
        engine.paramSet("colorbalancergb", 0, "vibrance", ParamValue.of(0.2)); engine.historySeal()
        engine.setEnabled("colorbalancergb", 0, false)
        // what compress must keep: which modules are on, and the parameters of those that are on
        val result = { runBlocking { engine.modules().associate { (it.op to it.priority) to (it.enabled to it.params.takeIf { _ -> it.enabled }) } } }
        val mods = result()
        val n = engine.history().items.size
        engine.historyCompress()
        val h = engine.history()
        assertTrue(h.items.size < n)
        assertEquals(h.items.size, h.end)
        assertEquals(1, h.items.count { it.op == "exposure" })
        // P2b: darktable's compress keeps disabled modules (dt_history_compress_on_image, "keep disabled modules
        // as documented"); the real engine is the truth here, the contract said they were dropped
        assertEquals("a module that ends up off keeps one item, off", listOf(false), h.items.filter { it.op == "colorbalancergb" }.map { it.enabled })
        assertEquals(mods, result())
    }

    @Test
    fun undoRedoRestoreExactly() = runBlocking<Unit> {
        openSample()
        assertFalse(engine.undoState().canUndo)
        val states = ArrayList<Pair<List<ModuleInstance>, History>>()
        states += engine.modules() to engine.history()
        for (i in 1..55) {
            engine.paramSet("exposure", 0, "exposure", ParamValue.of(i * 0.01))
            engine.paramSet("exposure", 0, "exposure", ParamValue.of(i * 0.02))
            engine.historySeal()
            states += engine.modules() to engine.history()
        }
        engine.instanceNew("exposure", 0)
        states += engine.modules() to engine.history()
        assertTrue(engine.undoState().canUndo)
        assertFalse(engine.undoState().canRedo)
        // at least 50 steps back, each equal to the recorded state
        for (k in states.size - 2 downTo states.size - 52) {
            engine.undo()
            assertEquals("modules after undo to state $k", states[k].first, engine.modules())
            assertEquals("history after undo to state $k", states[k].second, engine.history())
        }
        assertTrue(engine.undoState().canRedo)
        engine.redo()
        assertEquals(states[states.size - 51].first, engine.modules())
        // a new change clears redo
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(-1.0))
        assertFalse(engine.undoState().canRedo)
    }

    // ----------------------------------------------------------------- 2.2, read-only

    @Test
    fun guardAndReadOnlySession() = runBlocking<Unit> {
        val xmp = File(tmp.root, "guard.xmp")
        writeXmpWithUnknownModule(xmp)
        val c = engine.xmpCheck(xmp.path)
        assertFalse(c.ok)
        assertTrue(c.problems.any { it.reason == XmpProblemReason.UNKNOWN_OPERATION && it.engineVersion == null })
        expect(EngineError.IO) { engine.xmpCheck(File(tmp.root, "missing.xmp").path) }

        engine.open(sampleRaw(), xmp.path)
        assertTrue(engine.imageInfo().readOnly)
        engine.renderPreview(256) // it still renders
        val original = xmp.readText()
        val refused: List<Pair<String, suspend () -> Unit>> = listOf(
            "param_set" to { engine.paramSet("exposure", 0, "exposure", ParamValue.of(1.0)) },
            "params_set" to { engine.paramsSet("exposure", 0, mapOf("exposure" to ParamValue.of(1.0))) },
            "set_enabled" to { engine.setEnabled("exposure", 0, false) },
            "module_reset" to { engine.moduleReset("exposure", 0) },
            "set_wb" to { engine.moduleAction("temperature", 0, "set_wb", buildJsonObject { put("temperature", 5000.0); put("tint", 1.0) }) },
            "instance_new" to { engine.instanceNew("exposure", 0) },
            "instance_duplicate" to { engine.instanceDuplicate("exposure", 0) },
            "instance_delete" to { engine.instanceDelete("exposure", 0) },
            "instance_move" to { engine.instanceMove("exposure", 0, 1) },
            "instance_rename" to { engine.instanceRename("exposure", 0, "x") },
            "history_compress" to { engine.historyCompress() },
            "undo" to { engine.undo() },
            "redo" to { engine.redo() },
            "xmp_write" to { engine.xmpWrite(xmp.path) },
            "xmp_write elsewhere" to { engine.xmpWrite(File(tmp.root, "other.xmp").path) },
        )
        for ((name, call) in refused) {
            try {
                call(); fail("$name was not refused")
            } catch (e: EngineException) {
                assertEquals(name, EngineError.READ_ONLY, e.error)
            }
        }
        assertEquals("the XMP was not rewritten", original, xmp.readText())
        // looking at earlier steps is allowed: it changes nothing that could be written.
        // P1c: every XMP entry is an item, the unknown one flagged, end <= items, and every position
        // can be shown
        val h = engine.history()
        val end = h.end
        assertTrue("end ${h.end} > ${h.items.size} items", end in 0..h.items.size)
        assertTrue("fewer items than XMP entries", h.items.size >= c.entries)
        val unknown = h.items.filter { it.unknown }
        assertEquals("unknown items", 1, unknown.size)
        assertEquals(c.problems.first { it.reason == XmpProblemReason.UNKNOWN_OPERATION }.operation, unknown[0].op)
        for (k in 0..h.items.size) {
            engine.historyGoto(k)
            assertEquals(k, engine.history().end)
        }
        engine.historyGoto(0)
        assertEquals(0, engine.history().end)
        engine.historyGoto(end)
        assertEquals(h, engine.history())
        assertFalse(File(tmp.root, "other.xmp").exists())
    }

    @Test
    fun openWithoutXmpIsTheDefaultEditEveryTime() = runBlocking<Unit> {
        openSample()
        val modules = engine.modules()
        val history = engine.history()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(1.5)); engine.historySeal()
        engine.instanceDuplicate("exposure", 0)
        engine.historyGoto(engine.history().end - 1) // items above the end, too
        val xmp = File(tmp.root, "edited.xmp")
        engine.xmpWrite(xmp.path)
        engine.close()
        openSample()
        assertEquals("history after reopening without XMP", history, engine.history())
        assertEquals("modules after reopening without XMP", modules, engine.modules())
        engine.close()
        // with an XMP: exactly that XMP's edit, again after a changed session
        engine.open(sampleRaw(), xmp.path)
        val withXmp = engine.history()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(-2.0)); engine.historySeal()
        engine.close()
        engine.open(sampleRaw(), xmp.path)
        assertEquals("history after reopening with the XMP", withXmp, engine.history())
        engine.close()
        openSample()
        assertEquals("history without XMP after a session on an XMP", history, engine.history())
    }

    @Test
    fun xmpWriteRefusesToOverwriteAFileThatFailsTheGuard() = runBlocking<Unit> {
        openSample()
        val xmp = File(tmp.root, "foreign.xmp")
        writeXmpWithUnknownModule(xmp)
        val original = xmp.readText()
        expect(EngineError.READ_ONLY) { engine.xmpWrite(xmp.path) }
        assertEquals(original, xmp.readText())
    }

    @Test
    fun xmpWriteAndReopenKeepsTheHistory() = runBlocking<Unit> {
        openSample()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(1.25)); engine.historySeal()
        val dup = engine.instanceDuplicate("exposure", 0)
        engine.paramSet("exposure", dup, "exposure", ParamValue.of(-0.5)); engine.historySeal()
        val h = engine.history()
        val xmp = File(tmp.root, "edit.xmp")
        engine.xmpWrite(xmp.path)
        assertTrue(engine.xmpCheck(xmp.path).ok)
        engine.close()
        engine.open(sampleRaw(), xmp.path)
        assertFalse(engine.imageInfo().readOnly)
        assertEquals(h.items.map { it.op to it.priority }, engine.history().items.map { it.op to it.priority })
        assertEquals(h.end, engine.history().end)
        assertEquals(1.25, exposureValue(0), 1e-4)
        assertEquals(-0.5, exposureValue(dup), 1e-4)
    }

    // ----------------------------------------------------------------- 2.7

    @Test
    fun renderShapesAndErrors() = runBlocking<Unit> {
        openSample()
        val full = engine.renderSize(1.0)
        val info = engine.imageInfo()
        assertEquals(info.width, full.w); assertEquals(info.height, full.h)
        val half = engine.renderSize(0.5)
        assertEquals(full.w / 2.0, half.w.toDouble(), 1.0)
        val p = engine.renderPreview(512)
        assertTrue(maxOf(p.width, p.height) <= 512)
        assertEquals(p.width * p.height * 4, p.rgba.size)
        for (i in 0 until p.width * p.height) assertEquals(-1, p.rgba[i * 4 + 3].toInt())
        val r = engine.renderRegion(10, 20, 64, 48, 0.25)
        assertEquals(64, r.width); assertEquals(48, r.height)
        expect(EngineError.ARG) { engine.renderRegion(0, 0, half.w + 1, 10, 0.5) }
        expect(EngineError.ARG) { engine.renderRegion(-1, 0, 10, 10, 0.5) }
    }

    @Test
    fun exposureMakesThePictureBrighter() = runBlocking<Unit> {
        openSample()
        val a = meanOf(engine.renderPreview(256))
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(2.0))
        val b = meanOf(engine.renderPreview(256))
        assertTrue("mean $a -> $b", b > a + 5)
        engine.historyGoto(0)
        engine.renderPreview(256)
    }

    @Test
    fun renderCancelFromAnotherThread() = runBlocking<Unit> {
        openSample()
        val full = engine.renderSize(1.0)
        val job = async(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { engine.renderRegion(0, 0, minOf(full.w, 2000), minOf(full.h, 1500), 1.0) }
        }
        delay(40)
        engine.renderCancel()
        val res = job.await()
        val e = res.exceptionOrNull()
        assertTrue("expected CANCELLED, got $res", e is EngineException && e.error == EngineError.CANCELLED)
        // the next render works
        val p = engine.renderPreview(128)
        assertTrue(p.width > 0)
    }

    // ---------------------------------------------------------------- P1b part 1 (engine-api.md section 5)
    // Presets and styles persist in the engine's configuration folder: every name made here is unique
    // and removed again.

    private val stamp = System.nanoTime().toString(36)

    /** darktable's example style "day for twilight" (darktable, GPL-3.0, data/styles), renamed, with [op] as its first module. */
    private fun publicStyle(name: String, op: String = "exposure", moduleVersion: Int = 6): String = """<?xml version="1.0" encoding="UTF-8"?>
<darktable_style version="1.0"><info><name>$name</name><description>contract test</description></info><style><plugin><num>10</num><module>$moduleVersion</module><operation>$op</operation><op_params>00000000000080b9feff7fbf00004842000080c000000000</op_params><enabled>1</enabled><blendop_params>gz08eJxjYGBgYAFiCQYYOOHEgAZY0QWAgBGLGANDgz0Ej1Q+dlAx68oBEMbFxwX+AwGIBgCbGCeh</blendop_params><blendop_version>13</blendop_version><multi_priority>0</multi_priority><multi_name>twilight</multi_name><multi_name_hand_edited>1</multi_name_hand_edited></plugin></style></darktable_style>
"""

    @Test
    fun openRefusesAnEmptyFile() = runBlocking<Unit> {
        val f = tmp.newFile("EMPTY_0001.CR2")
        val e = runCatching { engine.open(f.path, null) }.exceptionOrNull()
        assertTrue("$e", e is EngineException && e.error == EngineError.IO && e.isUndecodableFile)
        // another IO answer of open is not the undecodable case
        val x = runCatching { engine.open(sampleRaw(), File(tmp.root, "missing.xmp").path) }.exceptionOrNull()
        assertTrue("$x", x is EngineException && x.error == EngineError.IO && !x.isUndecodableFile)
        expect(EngineError.STATE) { engine.imageInfo() }
        expect(EngineError.STATE) { engine.renderPreview(128) }
        openSample() // a real image still opens afterwards
    }

    @Test
    fun builtinPresetAppliesAsOneHistoryItem() = runBlocking<Unit> {
        openSample()
        val list = engine.presets("exposure", 0)
        val p = list.firstOrNull { it.builtin && it.usable && it.fits == true && it.active == false }
            ?: list.first { it.builtin && it.usable }
        assertTrue("built-in presets come first", list.indexOfFirst { !it.builtin }.let { it == -1 || list.take(it).all { x -> x.builtin } })
        val before = engine.history()
        engine.presetApply("exposure", 0, p.name)
        val after = engine.history()
        assertEquals(before.items.size + 1, after.items.size)
        assertEquals(after.items.size, after.end)
        assertEquals(true, engine.presets("exposure", 0).first { it.name == p.name }.active)
        engine.undo()
        assertEquals(before.items.size, engine.history().items.size)
        expect(EngineError.ARG) { engine.presetRename("exposure", p.name, "x$stamp") }
        expect(EngineError.ARG) { engine.presetDelete("exposure", p.name) }
        expect(EngineError.ARG) { engine.presetStore("exposure", 0, p.name, "", overwrite = true) }
        expect(EngineError.NOT_FOUND) { engine.presetApply("exposure", 0, "no such preset $stamp") }
        expect(EngineError.NOT_FOUND) { engine.presets("nosuchmodule$stamp", 0) }
    }

    @Test
    fun userPresetStoreApplyRenameExportImportDelete() = runBlocking<Unit> {
        openSample()
        val name = "contract $stamp"
        try {
            engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.37))
            engine.historySeal()
            engine.presetStore("exposure", 0, name, "made by the contract suite")
            val stored = engine.presets("exposure", 0).first { it.name == name }
            assertFalse(stored.builtin)
            assertEquals(true, stored.active)
            assertEquals(true, stored.fits)
            assertTrue(stored.usable)
            expect(EngineError.ARG) { engine.presetStore("exposure", 0, name) }
            expect(EngineError.ARG) { engine.presetStore("exposure", 0, "") }
            engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.55))
            engine.historySeal()
            engine.presetStore("exposure", 0, name, overwrite = true)
            engine.moduleReset("exposure", 0)
            engine.presetApply("exposure", 0, name)
            assertEquals(0.55, (instance("exposure").params.paramAt("exposure") as JsonPrimitive).doubleOrNull!!, 1e-6)
            val dir = tmp.newFolder("presets")
            val file = engine.presetExport("exposure", name, dir.path)
            assertTrue(File(file).isFile)
            engine.presetRename("exposure", name, "$name renamed")
            expect(EngineError.NOT_FOUND) { engine.presetApply("exposure", 0, name) }
            engine.presetDelete("exposure", "$name renamed")
            expect(EngineError.NOT_FOUND) { engine.presetDelete("exposure", "$name renamed") }
            val ref = engine.presetImport(file)
            assertEquals("exposure", ref.op)
            assertEquals(name, ref.name)
            assertTrue(ref.usable)
            expect(EngineError.ARG) { engine.presetImport(file) }
            engine.presetImport(file, overwrite = true)
            engine.moduleReset("exposure", 0)
            engine.presetApply("exposure", 0, name)
            assertEquals(0.55, (instance("exposure").params.paramAt("exposure") as JsonPrimitive).doubleOrNull!!, 1e-6)
            // a preset of a module the engine does not know is refused
            val unknown = File(dir, "unknown.dtpreset")
            unknown.writeText(File(file).readText().replace("<operation>exposure</operation>", "<operation>nosuchmodule</operation>")
                .replace(name, "$name unknown"))
            val e = runCatching { engine.presetImport(unknown.path) }.exceptionOrNull()
            assertTrue("$e", e is EngineException && e.error == EngineError.ARG && "unknown_operation" in (e.message ?: ""))
            unknown.writeText("not a preset")
            expect(EngineError.IO) { engine.presetImport(unknown.path) }
        } finally {
            runCatching { engine.presetDelete("exposure", name) }
            runCatching { engine.presetDelete("exposure", "$name renamed") }
        }
    }

    @Test
    fun stylesImportCreateApplyDelete() = runBlocking<Unit> {
        val dir = tmp.newFolder("styles")
        val ok = File(dir, "a.dtstyle").apply { writeText(publicStyle("contract a $stamp")) }
        val unknown = File(dir, "b.dtstyle").apply { writeText(publicStyle("contract b $stamp", op = "nosuchmodule")) }
        val newer = File(dir, "c.dtstyle").apply { writeText(publicStyle("contract c $stamp", moduleVersion = 99)) }
        val junk = File(dir, "d.dtstyle").apply { writeText("junk") }
        val created = "contract created $stamp"
        try {
            val r = engine.stylesImport(listOf(ok.path, unknown.path, newer.path, junk.path, File(dir, "missing.dtstyle").path))
            assertEquals(r.toString(), 3, r.imported.size)
            assertEquals(setOf("not_a_style", "io"), r.refused.map { it.reason }.toSet())
            assertEquals(listOf(true, false, false), r.imported.map { it.usable })
            assertEquals(listOf("exists"), engine.stylesImport(listOf(ok.path)).refused.map { it.reason })
            assertEquals(1, engine.stylesImport(listOf(ok.path), overwrite = true).imported.size)
            val all = engine.styles()
            val b = all.first { it.name == "contract b $stamp" }
            assertFalse(b.usable)
            assertEquals(ItemProblem.UNKNOWN_OPERATION, b.items.first().problem)
            assertEquals(ItemProblem.NEWER_MODULE_VERSION, all.first { it.name == "contract c $stamp" }.items.first().problem)

            openSample()
            val modules0 = engine.modules()
            val history0 = engine.history()
            for (n in listOf("contract b $stamp", "contract c $stamp")) {
                val e = runCatching { engine.styleApply(n, StyleMode.APPEND) }.exceptionOrNull()
                assertTrue("$e", e is EngineException && e.error == EngineError.ARG)
            }
            assertEquals(modules0, engine.modules())
            assertEquals(history0, engine.history())
            expect(EngineError.NOT_FOUND) { engine.styleApply("no such style $stamp") }

            // a style made from the history, applied to the default edit, carries the change
            engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.8))
            engine.historySeal()
            val h = engine.history()
            engine.styleCreate(created, "contract", listOf(h.end - 1))
            expect(EngineError.ARG) { engine.styleCreate(created) }
            expect(EngineError.ARG) { engine.styleCreate("$created bad", items = listOf(h.end + 3)) }
            assertEquals(listOf("exposure"), engine.styles().first { it.name == created }.items.map { it.op })
            engine.close()
            openSample()
            val n0 = engine.history().items.size
            engine.styleApply(created, StyleMode.APPEND)
            assertEquals(0.8, (instance("exposure").params.paramAt("exposure") as JsonPrimitive).doubleOrNull!!, 1e-6)
            assertTrue(engine.history().items.size > n0)
            engine.undo()
            assertEquals(n0, engine.history().items.size)
            engine.styleApply(created, StyleMode.OVERWRITE)
            assertEquals(0.8, (instance("exposure").params.paramAt("exposure") as JsonPrimitive).doubleOrNull!!, 1e-6)
            engine.styleApply("contract a $stamp", StyleMode.APPEND)
        } finally {
            for (n in listOf("contract a $stamp", "contract b $stamp", "contract c $stamp", created)) runCatching { engine.styleDelete(n) }
        }
        expect(EngineError.NOT_FOUND) { engine.styleDelete(created) }
    }

    @Test
    fun renderTimesListTheLastRender() = runBlocking<Unit> {
        openSample()
        assertEquals(null, engine.renderTimes().pipe)
        engine.renderPreview(256)
        val t = engine.renderTimes()
        assertEquals("preview", t.pipe)
        assertTrue("${t.modules}", t.modules.isNotEmpty())
        assertTrue(t.modules.sumOf { it.ms } <= t.totalMs + 1.0)
        val s = engine.renderSize(0.25)
        engine.renderRegion(0, 0, s.w, s.h, 0.25)
        assertEquals("full", engine.renderTimes().pipe)
    }

    @Test
    fun moduleActionsForControlsOutsideTheParameters() = runBlocking<Unit> {
        openSample()
        val profiles = engine.moduleAction("colorin", 0, "list_profiles") as kotlinx.serialization.json.JsonArray
        fun current(kind: String) = profiles.map { it as JsonObject }.filter {
            (it["kind"] as JsonPrimitive).content == kind && (it["current"] as JsonPrimitive).content == "true"
        }
        assertEquals(1, current("input").size)
        assertEquals(1, current("work").size)
        engine.moduleAction("borders", 0, "set_aspect", buildJsonObject { put("aspect", -1.0) })
        assertEquals(-1.0, (instance("borders").params.paramAt("aspect") as JsonPrimitive).doubleOrNull!!, 0.0)
        engine.moduleAction("borders", 0, "set_aspect", buildJsonObject { put("aspect", 0.0) })
        assertEquals(0.0, (instance("borders").params.paramAt("aspect") as JsonPrimitive).doubleOrNull!!, 0.0)
        expect(EngineError.RANGE) { engine.moduleAction("borders", 0, "set_aspect", buildJsonObject { put("aspect", 5.0) }) }
        engine.moduleAction("lens", 0, "use_latest_md")
        val lens = instance("lens").params
        assertEquals("DT_IOP_LENS_EMBEDDED_METADATA_VERSION_2", (lens.paramAt("md_version") as JsonPrimitive).content)
        assertEquals(0.0, (lens.paramAt("scale_md_v1") as JsonPrimitive).doubleOrNull!!, 0.0)
        val wb = engine.moduleAction("temperature", 0, "get_wb") as JsonObject
        assertTrue(wb.containsKey("tuning") && wb.containsKey("preset_index"))
    }

    // ----------------------------------------------------------------- 6 (P1b part 2)

    @Test
    fun aBypassRenderLeavesTheEditAlone() = runBlocking<Unit> {
        openSample()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.5))
        engine.historySeal()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(1.0))
        engine.historySeal()
        engine.undo()
        val modules0 = engine.modules()
        val history0 = engine.history()
        val undo0 = engine.undoState()
        assertTrue(undo0.canRedo)
        val xmpA = File(tmp.root, "bypass-a.xmp")
        engine.xmpWrite(xmpA.path)
        val plain = engine.renderPreview(256)
        val off = RenderOptions.bypass("exposure", 0)
        val bypassed = engine.renderPreview(256, off)
        val size = engine.renderSize(0.25, off)
        val region = engine.renderRegion(0, 0, size.w, size.h, 0.25, off)
        assertEquals(modules0, engine.modules())
        assertEquals(history0, engine.history())
        assertEquals(undo0, engine.undoState())
        val xmpB = File(tmp.root, "bypass-b.xmp")
        engine.xmpWrite(xmpB.path)
        assertTrue("the XMP is byte-equal after bypass renders", xmpA.readBytes().contentEquals(xmpB.readBytes()))
        assertTrue("the next plain render is the one from before", plain.rgba.contentEquals(engine.renderPreview(256).rgba))
        // the picture equals the one with the instance switched off
        engine.setEnabled("exposure", 0, false)
        val switchedOff = engine.renderPreview(256)
        val switchedOffRegion = engine.renderRegion(0, 0, size.w, size.h, 0.25)
        engine.undo()
        assertTrue("bypass preview = switched off", bypassed.rgba.contentEquals(switchedOff.rgba))
        assertTrue("bypass region = switched off", region.rgba.contentEquals(switchedOffRegion.rgba))
        assertNotEquals(meanOf(plain), meanOf(bypassed), 1e-9)
        // the redo step from before is still there (the switch-off and its undo replaced it: a fresh one)
        expect(EngineError.NOT_FOUND) { engine.renderPreview(256, RenderOptions.bypass("nosuchmodule", 0)) }
        expect(EngineError.ARG) { engine.renderPreview(256, RenderOptions.bypass("colorin", 0)) }
        assertEquals(engine.renderSize(0.25), engine.renderSize(0.25, RenderOptions.NONE))
    }

    /**
     * Brief P3h (engine-api.md 8): a render of another history position equals historyGoto + render + historyGoto
     * back, and leaves the history, its end, the undo state, the XMP and the open item as they were.
     */
    @Test
    fun aRenderOfAHistoryPositionEqualsGotoAndBack() = runBlocking<Unit> {
        openSample()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.8))
        engine.historySeal()
        engine.setEnabled("exposure", 0, false)
        engine.historySeal()
        engine.setEnabled("exposure", 0, true)
        engine.historySeal()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(-0.4)) // left open
        val history0 = engine.history()
        val undo0 = engine.undoState()
        val n = history0.items.size
        val xmpA = File(tmp.root, "at-a.xmp")
        engine.xmpWrite(xmpA.path)
        val ends = (listOf(0, 1, n / 2, n - 1, n)).distinct()
        val at = ends.associateWith { e ->
            val o = RenderOptions.at(e)
            val size = engine.renderSize(0.25, o)
            Triple(engine.renderPreview(256, o), size, engine.renderRegion(0, 0, size.w, size.h, 0.25, o))
        }
        assertEquals(history0, engine.history())
        assertEquals(undo0, engine.undoState())
        val xmpB = File(tmp.root, "at-b.xmp")
        engine.xmpWrite(xmpB.path)
        assertTrue("the XMP is byte-equal after renders of other positions", xmpA.readBytes().contentEquals(xmpB.readBytes()))
        // the open item is still open: a further change of the same value merges into it
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(-0.3))
        assertEquals("the open item stayed open", n, engine.history().items.size)
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(-0.4))
        for (e in ends) {
            engine.historyGoto(e)
            val size = engine.renderSize(0.25)
            val prev = engine.renderPreview(256)
            val region = engine.renderRegion(0, 0, size.w, size.h, 0.25)
            engine.historyGoto(n)
            val (p, s, r) = at.getValue(e)
            assertEquals("size at $e", size, s)
            assertTrue("preview at $e = goto + render + goto back", prev.rgba.contentEquals(p.rgba))
            assertTrue("region at $e = goto + render + goto back", region.rgba.contentEquals(r.rgba))
        }
        assertNotEquals("the original differs from the edit", meanOf(at.getValue(0).first), meanOf(at.getValue(n).first), 1e-9)
        expect(EngineError.ARG) { engine.renderPreview(256, RenderOptions.at(n + 1)) }
        expect(EngineError.ARG) { engine.renderPreview(256, RenderOptions.at(-1)) }
        assertEquals(n, engine.history().end)
    }

    @Test
    fun aRedoStepSurvivesBypassRenders() = runBlocking<Unit> {
        openSample()
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.7))
        engine.historySeal()
        engine.undo()
        engine.renderPreview(256, RenderOptions.bypass("exposure", 0))
        assertTrue(engine.undoState().canRedo)
        engine.redo()
        assertEquals(0.7, exposureValue(), 1e-6)
    }

    @Test
    fun anUndoBackGivesTheXmpOfThatState() = runBlocking<Unit> {
        openSample()
        val a = File(tmp.root, "undo-a.xmp")
        engine.xmpWrite(a.path)
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.6))
        engine.historySeal()
        val b = File(tmp.root, "undo-b.xmp")
        engine.xmpWrite(b.path)
        engine.undo()
        val c = File(tmp.root, "undo-c.xmp")
        engine.xmpWrite(c.path)
        fun edit(f: File) = f.readText().replace(Regex("""\s*darktable:change_timestamp="[^"]*""""), "")
        assertEquals("after an undo the XMP is the one of that state, apart from the change timestamp", edit(a), edit(c))
        engine.redo()
        val d = File(tmp.root, "undo-d.xmp")
        engine.xmpWrite(d.path)
        assertTrue("redo back to the last state: byte-equal", b.readBytes().contentEquals(d.readBytes()))
    }

    @Test
    fun colorPickersAndHistogram() = runBlocking<Unit> {
        openSample()
        val history0 = engine.history()
        val area = PickRequest.area(0.4, 0.35, 0.62, 0.55)
        val global = engine.colorPick(null, 0, area)
        val out = global.output!!
        assertEquals("preview", global.pipe)
        assertTrue("$out", out.mean.take(3).all { it != null && it in 0.0..1.0 })
        assertTrue(out.min[0]!! <= out.mean[0]!! && out.mean[0]!! <= out.max[0]!!)
        val e = engine.colorPick("exposure", 0, area)
        assertTrue("exposure picks its input and output: $e", e.input != null && e.output != null)
        assertEquals("rgb", e.input!!.colorspace)
        val p = engine.colorPick("exposure", 0, PickRequest.point(0.5, 0.5).copy(denoise = true))
        assertTrue(p.input != null)
        expect(EngineError.NOT_FOUND) { engine.colorPick("nosuchmodule", 0, area) }
        expect(EngineError.ARG) { engine.colorPick("exposure", 0, PickRequest(area = listOf(0.5, 0.5, 0.2, 0.2))) }
        expect(EngineError.ARG) { engine.colorPick("exposure", 0, PickRequest()) }
        engine.setEnabled("exposure", 0, false)
        expect(EngineError.ARG) { engine.colorPick("exposure", 0, area) }
        engine.undo()
        val h = engine.histogram(512)
        assertEquals(256, h.bins)
        assertEquals(3, h.counts.size)
        for (c in h.counts) assertEquals(h.pixels, c.sum())
        assertEquals(h.counts.maxOf { it.max() }, h.max)
        assertTrue(h.width <= 512 && h.height <= 512)
        // pickers and histograms change nothing in the edit
        assertEquals(history0, engine.history())
    }

    @Test
    fun moduleActionsThatPick() = runBlocking<Unit> {
        openSample()
        val area = buildJsonObject { put("area", kotlinx.serialization.json.JsonArray(listOf(0.4, 0.35, 0.62, 0.55).map { JsonPrimitive(it) })) }
        val n0 = engine.history().items.size
        val wb = engine.moduleAction("temperature", 0, "wb_from_area", area) as JsonObject
        val c = (wb["coefficients"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).doubleOrNull!! }
        assertEquals(1.0, c[1], 0.0)
        assertEquals(c[0], (instance("temperature").params.paramAt("red") as JsonPrimitive).doubleOrNull!!, 1e-6)
        // exposure: correct the area to 50 %, then measuring it gives 50 % back
        engine.moduleAction("exposure", 0, "spot", JsonObject(area + mapOf("mode" to JsonPrimitive("correction"), "lightness" to JsonPrimitive(50.0))))
        val m = engine.moduleAction("exposure", 0, "spot", JsonObject(area + mapOf("mode" to JsonPrimitive("measure")))) as JsonObject
        assertEquals(50.0, (m["lightness"] as JsonPrimitive).doubleOrNull!!, 0.05)
        expect(EngineError.ARG) { engine.moduleAction("exposure", 0, "spot", JsonObject(area + mapOf("mode" to JsonPrimitive("sideways")))) }
        // rgblevels' black picker
        engine.setEnabled("rgblevels", 0, true)
        val lv = engine.moduleAction("rgblevels", 0, "pick", JsonObject(area + mapOf("which" to JsonPrimitive("black")))) as JsonObject
        val black = ((lv["levels"] as kotlinx.serialization.json.JsonArray)[0] as JsonPrimitive).doubleOrNull!!
        assertEquals(black, (instance("rgblevels").params.paramAt("levels[0][0]") as JsonPrimitive).doubleOrNull!!, 1e-6)
        // rgbcurve: nodes from the area
        engine.setEnabled("rgbcurve", 0, true)
        val rc = engine.moduleAction("rgbcurve", 0, "set_nodes_from_area", area) as JsonObject
        val nodes = rc["nodes"] as kotlinx.serialization.json.JsonArray
        assertTrue("${nodes.size} nodes", nodes.size == 5 || nodes.size == 6)
        assertEquals(nodes.size, (instance("rgbcurve").params.paramAt("curve_num_nodes[0]") as JsonPrimitive).content.toInt())
        // one history item per action that changed the edit (and one per switch)
        assertEquals(n0 + 6, engine.history().items.size)
    }

    @Test
    fun coordinatesBetweenOriginalAndPicture() = runBlocking<Unit> {
        openSample()
        val pts = listOf(0.2 to 0.3, 0.5 to 0.5, 0.8 to 0.7)
        val f = engine.coordsTransform(CoordsDirection.TO_PROCESSED, pts)
        assertTrue(f.originalWidth > 0 && f.processedWidth > 0)
        val b = engine.coordsTransform(CoordsDirection.TO_ORIGINAL, f.points.map { it!! })
        for ((p, q) in pts.zip(b.points)) {
            assertEquals(p.first, q!!.first, 1.0 / f.originalWidth)
            assertEquals(p.second, q.second, 1.0 / f.originalHeight)
        }
        // turned clockwise: (x, y) of the picture = (1 - y, x) of the picture without the orientation
        engine.paramSet("flip", 0, "orientation", ParamValue.enum("ORIENTATION_ROTATE_CW_90_DEG"))
        engine.historySeal()
        val t = engine.coordsTransform(CoordsDirection.TO_PROCESSED, pts)
        val n = engine.coordsTransform(CoordsDirection.TO_PROCESSED, pts, RenderOptions.bypass("flip", 0))
        assertEquals(t.processedWidth, n.processedHeight)
        for ((a, c) in t.points.zip(n.points)) {
            assertEquals(1.0 - c!!.second, a!!.first, 1.5 / t.processedWidth)
            assertEquals(c.first, a.second, 1.5 / t.processedHeight)
        }
    }

    @Test
    fun blendingGetSetValidateAndGroup() = runBlocking<Unit> {
        openSample()
        val b0 = engine.blendGet("exposure", 0)
        assertTrue(b0.supported)
        assertEquals("off", b0.masks)
        assertEquals("normal", b0.mode)
        assertEquals(100.0, b0.opacity!!, 0.0)
        val cs = b0.effectiveColorspace!!
        assertTrue(cs, cs == "rgb_scene" || cs == "rgb_display")
        val h0 = engine.history()
        val m0 = engine.modules()
        expect(EngineError.RANGE) { engine.blendSet("exposure", 0, mapOf("opacity" to JsonPrimitive(150.0))) }
        expect(EngineError.ARG) { engine.blendSet("exposure", 0, mapOf("opacity" to JsonPrimitive(50.0), "mode" to JsonPrimitive("lab_a"))) }
        expect(EngineError.NOT_FOUND) { engine.blendSet("exposure", 0, mapOf("nosuch" to JsonPrimitive(1))) }
        expect(EngineError.TYPE) { engine.blendSet("exposure", 0, mapOf("opacity" to JsonPrimitive("x"))) }
        expect(EngineError.RANGE) {
            engine.blendSet("exposure", 0, mapOf("parametric.g_in.range" to kotlinx.serialization.json.JsonArray(listOf(0.5, 0.2, 0.6, 0.9).map { JsonPrimitive(it) })))
        }
        assertEquals(h0, engine.history())
        assertEquals(m0, engine.modules())
        // a slider drag on opacity: one history item until sealed, merged with a parameter change of the instance
        engine.blendSet("exposure", 0, mapOf("masks" to JsonPrimitive("uniform"), "opacity" to JsonPrimitive(50.0)))
        engine.blendSet("exposure", 0, mapOf("opacity" to JsonPrimitive(60.0)))
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.9))
        engine.historySeal()
        assertEquals(h0.items.size + 1, engine.history().items.size)
        val b1 = engine.blendGet("exposure", 0)
        assertEquals("uniform", b1.masks)
        assertEquals(60.0, b1.opacity!!, 1e-6)
        engine.blendSet("exposure", 0, mapOf("masks" to JsonPrimitive("parametric"),
            "parametric.g_in.range" to kotlinx.serialization.json.JsonArray(listOf(0.0, 0.2, 0.6, 0.9).map { JsonPrimitive(it) }),
            "combine.invert" to JsonPrimitive(true)))
        engine.historySeal()
        val g = engine.blendGet("exposure", 0).parametric.getValue("g_in")
        assertTrue(g.active)
        assertEquals(listOf(0.0, 0.2, 0.6, 0.9), g.range.map { Math.round(it * 1000) / 1000.0 })
        assertTrue(engine.blendGet("exposure", 0).combineInvert)
        // a colour space change resets the channels and the mode, as desktop's
        engine.blendSet("exposure", 0, mapOf("mode" to JsonPrimitive("multiply")))
        engine.historySeal()
        engine.blendSet("exposure", 0, mapOf("colorspace" to JsonPrimitive("lab")))
        engine.historySeal()
        val lab = engine.blendGet("exposure", 0)
        assertEquals("normal", lab.mode)
        assertTrue(lab.parametric.containsKey("L_in"))
        engine.undo()
        assertEquals("multiply", engine.blendGet("exposure", 0).mode)
        expect(EngineError.NOT_FOUND) { engine.blendGet("nosuchmodule", 0) }
    }

    private fun geo(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject

    @Test
    fun drawnMasksCreateAttachShareEditDelete() = runBlocking<Unit> {
        openSample()
        val n0 = engine.masks().size
        val c = engine.maskCreate("circle", geo("""{"center":[0.3,0.35],"radius":0.12,"border":0.05}"""))
        val e = engine.maskCreate("ellipse", geo("""{"center":[0.7,0.3],"radius":[0.15,0.08],"rotation":30}"""))
        val g = engine.maskCreate("gradient", geo("""{"anchor":[0.5,0.8],"rotation":10}"""))
        val p = engine.maskCreate("path", geo("""{"points":[{"corner":[0.1,0.6]},{"corner":[0.3,0.55]},{"corner":[0.35,0.9]}]}"""))
        val b = engine.maskCreate("brush", geo("""{"stroke":[[0.55,0.55],[0.6,0.57],[0.65,0.6],[0.7,0.62]]}"""), name = "a stroke")
        val all = engine.masks()
        assertEquals(n0 + 5, all.size)
        assertEquals(setOf("circle", "ellipse", "gradient", "path", "brush"), all.filter { it.id in setOf(c, e, g, p, b) }.map { it.type }.toSet())
        assertEquals("a stroke", all.first { it.id == b }.name)
        assertTrue(all.first { it.id == c }.usedBy.isEmpty())
        val h0 = engine.history().items.size
        for (id in listOf(c, e, g, p, b)) engine.maskAttach("exposure", 0, id)
        assertEquals("one history item per attach", h0 + 5, engine.history().items.size)
        val blend = engine.blendGet("exposure", 0)
        assertEquals("flexi", blend.masks)
        val root = blend.maskId!!
        assertEquals(listOf(c, e, g, p, b), engine.masks().first { it.id == root }.members)
        // one form in two instances' masks
        val np = engine.instanceNew("exposure", 0)
        engine.maskAttach("exposure", np, c, MaskMember(inverse = true))
        assertEquals(2, engine.masks().first { it.id == c }.usedBy.size)
        engine.maskMemberSet("exposure", 0, e, MaskMember(opacity = 0.6))
        engine.maskGroupSet(root, buildJsonObject { put("operator", "screen") })
        assertEquals("screen", (engine.masks().first { it.id == root }.geometry["operator"] as JsonPrimitive).content)
        // edit, rename, detach, delete, and the refusals
        engine.maskUpdate(c, geo("""{"center":[0.4,0.4],"radius":0.2}"""))
        engine.maskRename(c, "renamed")
        assertEquals("renamed", engine.masks().first { it.id == c }.name)
        engine.maskDetach("exposure", 0, g)
        engine.maskDelete(g)
        assertFalse(engine.masks().any { it.id == g })
        expect(EngineError.ARG) { engine.maskDelete(root) }
        expect(EngineError.ARG) { engine.maskAttach("exposure", 0, c) }
        expect(EngineError.NOT_FOUND) { engine.maskAttach("exposure", 0, 424242) }
        expect(EngineError.ARG) { engine.maskUpdate(c, geo("""{"radius":0.2}""")) }
        expect(EngineError.RANGE) { engine.maskUpdate(c, geo("""{"center":[0.4,0.4],"radius":5}""")) }
        expect(EngineError.ARG) { engine.maskCreate("triangle", geo("{}")) }
        expect(EngineError.RANGE) { engine.maskMemberSet("exposure", 0, c, MaskMember(opacity = 3.0)) }
        expect(EngineError.ARG) { engine.maskGroupSet(root, buildJsonObject { put("operator", "mean") }) }
        // a group of forms
        val grp = engine.maskGroupCreate(listOf(c, e))
        assertEquals(listOf(c, e), engine.masks().first { it.id == grp }.members)
        // undo takes the last change back
        engine.undo()
        assertFalse(engine.masks().any { it.id == grp })
    }

    @Test
    fun maskDisplayRender() = runBlocking<Unit> {
        openSample()
        val show = RenderOptions(showMask = InstanceRef("exposure", 0))
        expect(EngineError.ARG) { engine.renderPreview(256, show) }
        val c = engine.maskCreate("circle", geo("""{"center":[0.3,0.35],"radius":0.1,"border":0.02}"""))
        engine.maskAttach("exposure", 0, c)
        val plain = engine.renderPreview(256)
        val h0 = engine.history()
        val shown = engine.renderPreview(256, show)
        assertEquals(plain.width, shown.width)
        assertFalse(plain.rgba.contentEquals(shown.rgba))
        // far from the circle the overlay is grey
        val x = (0.85 * shown.width).toInt(); val y = (0.85 * shown.height).toInt()
        val i = 4 * (y * shown.width + x)
        val r = shown.rgba[i].toInt() and 0xff; val g = shown.rgba[i + 1].toInt() and 0xff; val b = shown.rgba[i + 2].toInt() and 0xff
        assertTrue("grey outside the mask: $r $g $b", kotlin.math.abs(r - g) <= 3 && kotlin.math.abs(g - b) <= 3)
        val s = engine.renderSize(0.25)
        engine.renderRegion(0, 0, s.w, s.h, 0.25, show)
        assertEquals(h0, engine.history())
        assertTrue(plain.rgba.contentEquals(engine.renderPreview(256).rgba))
    }

    /**
     * Brief P3j (engine-api.md 9): copy the history of photo A, paste it onto the open photo (one undo step) and onto
     * photos that are not open (their XMP written); a selective copy takes only the chosen items; append keeps the
     * target's own items; a target whose XMP fails the version guard is left alone.
     */
    @Test
    fun copyAndPasteTheHistoryBetweenPhotos() = runBlocking<Unit> {
        openSample()
        expect(EngineError.STATE) { engine.historyPaste() }
        engine.paramSet("exposure", 0, "exposure", ParamValue.of(0.8))
        engine.historySeal()
        val items = engine.historyCopyItems().items
        // the instance's newest entry: as desktop's list, an instance listed under two names (an automatic preset's
        // name, then none) has two entries
        val exp = items.filter { it.op == "exposure" && it.priority == 0 }.maxBy { it.num }
        assertTrue("exposure is safe to copy", exp.safe)
        items.firstOrNull { it.op == "temperature" }?.let { assertFalse("white balance is not safe to copy", it.safe) }
        expect(EngineError.ARG) { engine.historyCopy(HistorySelection(listOf(99999))) }
        engine.historyCopy(HistorySelection(listOf(exp.num)))
        expect(EngineError.ARG) { engine.historyPaste() } // as desktop: not onto the photo it was copied from
        val clip = engine.historyClipboard()!!
        assertTrue(clip.parts)
        assertEquals(listOf(exp.num), clip.items.filter { it.selected == true }.map { it.num })
        expect(EngineError.STATE) { engine.historyPasteFiles(emptyList()) }
        engine.close()

        val raw = File(sampleRaw())
        val b = File(tmp.root, "paste-b." + raw.extension).also { raw.copyTo(it, overwrite = true) }
        val bx = File(tmp.root, "paste-b.xmp")
        val d = File(tmp.root, "paste-d." + raw.extension).also { raw.copyTo(it, overwrite = true) }
        val dx = File(tmp.root, "paste-d.xmp").also { writeXmpWithUnknownModule(it) }
        val dText = dx.readText()
        val r = engine.historyPasteFiles(listOf(PasteTarget(b.path, bx.path), PasteTarget(d.path, dx.path)), PasteMode.APPEND)
        assertEquals(null, r[0].error)
        assertEquals(EngineError.READ_ONLY, r[1].error)
        assertEquals("the refused XMP is unchanged", dText, dx.readText())
        engine.open(b.path, bx.path)
        assertEquals(0.8, exposureValue(), 1e-4)
        val bHistory = engine.history()
        engine.close()

        // onto the open photo: append keeps its own items, one undo step takes the paste back
        engine.open(b.path, bx.path)
        engine.paramSet("vignette", 0, "brightness", ParamValue.of(-0.4))
        engine.historySeal()
        val before = engine.history()
        engine.historyPaste(PasteMode.APPEND)
        val after = engine.history()
        assertTrue("the paste added an item", after.items.size > before.items.size)
        assertEquals(before.items, after.items.take(before.items.size))
        engine.undo()
        assertEquals(before, engine.history())
        engine.redo()
        val px = File(tmp.root, "paste-b2.xmp")
        engine.xmpWrite(px.path)
        engine.close()
        engine.open(b.path, px.path)
        assertEquals(after, engine.history())
        assertTrue(bHistory.items.isNotEmpty())
    }
}

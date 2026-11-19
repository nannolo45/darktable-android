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
        assertEquals(modsAt1, engine.modules())
        expect(EngineError.RANGE) { engine.historyGoto(start + 10) }

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
        assertTrue("a module that ends up off is dropped", h.items.none { it.op == "colorbalancergb" })
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
        // looking at earlier steps is allowed: it changes nothing that could be written
        val end = engine.history().end
        engine.historyGoto(0)
        assertEquals(0, engine.history().end)
        engine.historyGoto(end)
        assertFalse(File(tmp.root, "other.xmp").exists())
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
}

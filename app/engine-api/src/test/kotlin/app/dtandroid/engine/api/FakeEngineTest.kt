// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.engine.fake.FakeModuleCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What is specific to the fake: its use of the schema, its sidecar and its picture. */
class FakeEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val catalog by lazy { FakeModuleCatalog.fromSchemaJson(TestSchema.text) }

    private fun engine() = FakeEngine(catalog, FakeEngine.Timing.NONE).also {
        runBlocking { it.init(EnginePaths("a", "b", "c", "d", "e")) }
    }

    @Test
    fun catalogTakesModulesAndImageDefaultsFromTheSchema() {
        assertEquals(97, catalog.modules.size)
        val exposure = catalog["exposure"]!!
        assertEquals(7, exposure.version)
        // the slider's default for this raw (+0.7 EV) wins over the introspection default (0)
        assertEquals(0.7, (exposure.defaultParams["exposure"] as JsonPrimitive).content.toDouble(), 1e-6)
        assertEquals("EXPOSURE_MODE_MANUAL", (exposure.defaultParams["mode"] as JsonPrimitive).content)
        assertFalse(catalog["demosaic"]!!.canSwitch)
        assertTrue(catalog["flip"]!!.oneInstance)
        // arrays of structs: retouch has 300 forms, each with a 3-element fill colour
        val forms = catalog["retouch"]!!.defaultParams["rt_forms"] as JsonArray
        assertEquals(300, forms.size)
        assertEquals(3, ((forms[0] as JsonObject)["fill_color"] as JsonArray).size)
        // plain arrays
        assertEquals(4, (catalog["colorbalance"]!!.defaultParams["lift"] as JsonArray).size)
    }

    @Test
    fun newerModuleVersionMakesTheSessionReadOnly() = runBlocking<Unit> {
        val e = engine()
        val xmp = File(tmp.root, "newer.xmp")
        xmp.writeText(
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description xmlns:darktable="http://darktable.sf.net/" darktable:xmp_version="5"><darktable:history><rdf:Seq>
              <rdf:li darktable:num="0" darktable:operation="exposure" darktable:modversion="99" darktable:enabled="1"/>
              </rdf:Seq></darktable:history></rdf:Description></rdf:RDF></x:xmpmeta>"""
        )
        val c = e.xmpCheck(xmp.path)
        assertFalse(c.ok)
        assertEquals(XmpProblemReason.NEWER_MODULE_VERSION, c.problems.single().reason)
        assertEquals(7, c.problems.single().engineVersion)
        e.open(File(tmp.root, "x.RAF").path, xmp.path)
        assertTrue(e.imageInfo().readOnly)
        assertFalse(e.undoState().canUndo)
    }

    @Test
    fun orientationSwapsTheProcessedSize() = runBlocking<Unit> {
        val e = engine()
        e.open(File(tmp.root, "x.RAF").path)
        val a = e.imageInfo()
        assertTrue(a.width > a.height)
        e.paramSet("flip", 0, "orientation", ParamValue.enum("ORIENTATION_ROTATE_CW_90_DEG"))
        val b = e.imageInfo()
        assertEquals(a.width, b.height)
        assertEquals(a.height, b.width)
        val p = e.renderPreview(300)
        assertTrue(p.height > p.width)
    }

    @Test
    fun whiteBalanceChangesTheColours() = runBlocking<Unit> {
        val e = engine()
        e.open(File(tmp.root, "x.RAF").path)
        fun redBlue(img: RenderedImage): Double {
            var r = 0L; var b = 0L
            for (i in 0 until img.width * img.height) { r += img.rgba[i * 4].toInt() and 0xff; b += img.rgba[i * 4 + 2].toInt() and 0xff }
            return r.toDouble() / b
        }
        val before = redBlue(e.renderPreview(200))
        e.moduleAction("temperature", 0, "set_wb", kotlinx.serialization.json.buildJsonObject {
            put("temperature", JsonPrimitive(9000.0)); put("tint", JsonPrimitive(1.05))
        })
        assertTrue(redBlue(e.renderPreview(200)) > before)
    }

    @Test
    fun exportWritesAPng() = runBlocking<Unit> {
        val e = engine()
        e.open(File(tmp.root, "x.RAF").path)
        val f = File(tmp.root, "out.png")
        e.export(f.path, ExportOptions(format = ExportFormat.PNG, maxWidth = 300))
        val bytes = f.readBytes()
        assertEquals(0x89.toByte(), bytes[0]); assertEquals('P'.code.toByte(), bytes[1])
        try {
            e.export(File(tmp.root, "out.jpg").path, ExportOptions(format = ExportFormat.JPEG)); org.junit.Assert.fail()
        } catch (x: EngineException) { assertEquals(EngineError.ARG, x.error) }
    }

    @Test
    fun parsesTheSpecificationsJsonShapes() {
        val m = ModuleInstance.listFromJson(parseEngineJson(
            """[{"op":"exposure","name":"exposure","priority":0,"instance_name":"","iop_order":2500,"enabled":true,
            "can_switch":true,"default_enabled":false,"hidden":false,"deprecated":false,"one_instance":false,
            "supports_blending":true,"version":7,"in_history":true,"params":{"exposure":0.7,"mode":"EXPOSURE_MODE_MANUAL","x":null}}]"""
        ))
        assertEquals(1, m.size); assertEquals(2500, m[0].iopOrder)
        val c = XmpCheck.fromJson(parseEngineJson(
            """{"ok":false,"xmp_version":5,"entries":3,"problems":[{"num":2,"operation":"x","modversion":3,"engine_version":null,"reason":"unknown_operation"}]}"""
        ))
        assertEquals(null, c.problems[0].engineVersion)
        val h = History.fromJson(parseEngineJson("""{"end":1,"items":[{"num":0,"op":"exposure","priority":0,"name":"exposure","instance_name":"","enabled":true}]}"""))
        assertEquals(1, h.end)
        try { History.fromJson(parseEngineJson("""{"items":[]}""")); org.junit.Assert.fail() } catch (x: EngineException) { assertEquals(EngineError.ENGINE, x.error) }
        assertEquals(listOf(ParamPath.Step.Field("curve"), ParamPath.Step.Index(2), ParamPath.Step.Field("x")), ParamPath.parse("curve[2].x"))
        assertEquals("equalizer_y[0][0]", ParamPath.template("equalizer_y[0][3]"))
        try { ParamPath.parse("a..b"); org.junit.Assert.fail() } catch (x: EngineException) { assertEquals(EngineError.ARG, x.error) }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.lpl.SharedHash
import app.dtandroid.lpl.SharedKey
import app.dtandroid.lpl.SharedKind
import app.dtandroid.lpl.SharedLayout
import app.dtandroid.schema.layout.BuiltinLayouts
import app.dtandroid.schema.layout.DtPreset
import app.dtandroid.schema.layout.LayoutCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Brief P3l deliverable 2: a layout's R11 body carries `op_params` byte for byte as desktop stores it. The reference is
 * a row desktop darktable wrote: `desktop-export.dtpreset` was exported by darktable 04477d3's own
 * dt_presets_save_to_file from its data.presets row (tools/layouts/verify-with-darktable.sh, brief P2d), so its decoded
 * op_params are that row's bytes; and the built-in layouts, which P2d showed byte-identical to what darktable stores.
 */
class P3lLayoutBodyTest {
    private val desktop = File("../schema/src/test/resources/dtpreset/desktop-export.dtpreset").readBytes()

    private fun opParams(b: JsonObject) = ((b["row"] as JsonObject)["op_params"] as JsonPrimitive).content

    @Test fun theBodyOfADesktopRowHasDesktopsBytes() {
        val f = DtPreset.read(desktop)
        val (name, text) = DtPreset.readLayout(desktop)
        // the app keeps the layout as LayoutCodec reads and writes it: the same string
        val stored = LayoutCodec.write(LayoutCodec.parse(text))
        assertEquals(text, stored)
        val body = SharedLayout.body(name, stored)
        assertEquals("op_params as desktop stores them", SharedHash.hex(f.opParams), opParams(body))
        assertArrayEquals(f.opParams, SharedHash.unhex(opParams(body)))
        assertEquals(DtPreset.OP_VERSION, f.opVersion)
        assertEquals(SharedKey(SharedKind.LAYOUT, f.operation, f.opVersion, f.name), SharedKey.of(body))
        // and back: the layout string the app stores again
        assertEquals(text, SharedLayout.params(body))
    }

    @Test fun builtinLayoutsMapByteForByte() {
        val b = BuiltinLayouts.fromResources()
        val presets = b.presets(b.defaultWorkflow)
        check(presets.isNotEmpty())
        for (p in presets) {
            val body = SharedLayout.body(p.name, p.params)
            assertEquals(p.name, SharedHash.hex(p.params.toByteArray(Charsets.UTF_8)), opParams(body))
            assertEquals(p.params, SharedLayout.params(body))
            assertEquals(LayoutCodec.write(LayoutCodec.parse(p.params)), SharedLayout.params(body))
        }
    }

    @Test fun theHashCoversNameOperationVersionAndBytesOnly() {
        val (name, text) = DtPreset.readLayout(desktop)
        val a = SharedLayout.local(name, text)
        val row = a.body["row"] as JsonObject
        val other = JsonObject(a.body + ("row" to JsonObject(row + ("exposure_max" to JsonPrimitive("100000000")) + ("description" to JsonPrimitive("x")))))
        assertEquals("columns outside the core do not change the hash", a.hash, SharedHash.hash(other))
        assertEquals(SharedHash.sha256("""{"kind":"layout","row":{"name":${SharedHash.canonical(JsonPrimitive(name))},"op_params":"${opParams(a.body)}","op_version":1,"operation":"modulegroups"}}"""), a.hash)
    }
}

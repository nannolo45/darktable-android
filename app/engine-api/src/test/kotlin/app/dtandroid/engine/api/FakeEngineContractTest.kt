// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import app.dtandroid.engine.fake.FakeEngine
import java.io.File

/** The engine contract (engine-api.md section 4, "for FakeEngine") run against the fake. */
class FakeEngineContractTest : DarktableEngineContract() {
    override fun newEngine(): DarktableEngine = FakeEngine.fromSchemaJson(TestSchema.text)

    override fun sampleRaw(): String = File(tmp.root, "SAMPLE_0001.RAF").apply { writeText("not a raw") }.path

    override fun writeXmpWithUnknownModule(file: File) {
        file.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <rdf:Description rdf:about="" xmlns:darktable="http://darktable.sf.net/" darktable:xmp_version="5" darktable:history_end="2">
              <darktable:history><rdf:Seq>
               <rdf:li darktable:num="0" darktable:operation="exposure" darktable:enabled="1" darktable:modversion="7"/>
               <rdf:li darktable:num="1" darktable:operation="futuremodule" darktable:enabled="1" darktable:modversion="1"/>
              </rdf:Seq></darktable:history>
             </rdf:Description></rdf:RDF></x:xmpmeta>
            """.trimIndent()
        )
    }
}

object TestSchema {
    val text: String by lazy {
        val path = System.getProperty("dta.schema") ?: "../../schema/ui-schema.json"
        File(path).readText()
    }
}

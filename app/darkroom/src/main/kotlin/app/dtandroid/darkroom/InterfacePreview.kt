// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import app.dtandroid.engine.api.Exif
import app.dtandroid.engine.fake.FakeEngine
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The interface preview's sample series: twelve invented photos for the fake
 * engine (no photo files: the fake generates the picture). Photo 5 comes with
 * a sidecar from a "newer darktable", so it opens read-only.
 */
object InterfacePreview {
    const val SERIES_NAME = "Sample series"
    const val COUNT = 12
    const val READ_ONLY_INDEX = 4

    fun fileName(i: Int) = "SAMPLE_%04d.RAF".format(i + 1)

    /** Exif and a few starting edits so the photos differ. */
    val samples: Map<String, FakeEngine.SampleInfo> = (0 until COUNT).associate { i ->
        val exposure = listOf(1.0 / 250, 1.0 / 125, 1.0 / 60, 1.0 / 500, 1.0 / 30, 1.0 / 1000)[i % 6]
        val aperture = listOf(2.8, 4.0, 5.6, 2.0, 8.0, 1.4)[i % 6]
        val iso = listOf(400.0, 200.0, 800.0, 160.0, 1600.0, 100.0)[i % 6]
        val start = when (i % 4) {
            1 -> listOf("colorbalancergb" to mapOf("vibrance" to JsonPrimitive(0.2)))
            2 -> listOf("exposure" to mapOf("exposure" to JsonPrimitive(0.3)))
            else -> emptyList()
        }
        fileName(i) to FakeEngine.SampleInfo(
            exif = Exif("Generic", "Camera", "23 mm f/1.4", iso, aperture, exposure, 23.0, "2026-06-01T18:%02d:00".format(i)),
            startEdit = start,
        )
    }

    /** The series with sidecar paths in [dir]; writes the read-only photo's foreign sidecar if missing. */
    fun series(dir: File): Series {
        dir.mkdirs()
        val items = (0 until COUNT).map { i ->
            SeriesItem(fileName(i), File(dir, fileName(i)).path, File(dir, fileName(i) + ".xmp").path)
        }
        val ro = File(items[READ_ONLY_INDEX].xmpPath)
        if (!ro.exists()) ro.writeText(NEWER_XMP)
        return Series(SERIES_NAME, items)
    }

    fun engine(schemaText: String, timing: FakeEngine.Timing = FakeEngine.Timing.REALISTIC) =
        FakeEngine.fromSchemaJson(schemaText, timing, samples)

    /** A sidecar as a newer darktable might write it: color balance rgb in a version this engine does not know. */
    private val NEWER_XMP = """
        <?xml version="1.0" encoding="UTF-8"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
         <rdf:Description rdf:about="" xmlns:darktable="http://darktable.sf.net/" darktable:xmp_version="5" darktable:history_end="3">
          <darktable:history><rdf:Seq>
           <rdf:li darktable:num="0" darktable:operation="exposure" darktable:enabled="1" darktable:modversion="7" darktable:multi_priority="0" darktable:multi_name=""/>
           <rdf:li darktable:num="1" darktable:operation="sigmoid" darktable:enabled="1" darktable:modversion="3" darktable:multi_priority="0" darktable:multi_name=""/>
           <rdf:li darktable:num="2" darktable:operation="colorbalancergb" darktable:enabled="1" darktable:modversion="99" darktable:multi_priority="0" darktable:multi_name=""/>
          </rdf:Seq></darktable:history>
         </rdf:Description></rdf:RDF></x:xmpmeta>
    """.trimIndent()
}

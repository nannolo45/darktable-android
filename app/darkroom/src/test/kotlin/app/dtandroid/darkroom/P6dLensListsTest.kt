// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brief P6d (JVM): the pure parts of lens correction by hand. The value lists are checked against the output of
 * tools/p6d-lens-lists-reference.c, a transcription of _lens_set() of iop/lens.cc (the lists as the combobox shows them,
 * the current value first); the app leaves out an entry that reads like one already listed and entries of 0 or less.
 */
class P6dLensListsTest {
    private fun lens(minF: Double, maxF: Double, minA: Double?, maxA: Double? = null) =
        LensInfo("M", "L", minF, maxF, minA?.toFloat()?.toDouble(), maxA?.toFloat()?.toDouble())

    /** the reference's lines: "[current] a b c ..." */
    private fun expected(line: String): List<String> =
        line.trim().split(' ').map { it.trim('[', ']') }.distinct().filter { it != "0.0" }

    private fun texts(e: List<LensValueEntry>) = e.map { it.text }

    // ---- the focal lengths: a zoom and a prime, clipped to the lens

    @Test fun zoom18to55() {
        assertEquals(expected("[35] 18 20 24 28 30 31 35 38 40 43 45 50 55"), texts(LensValues.focalEntries(lens(18.0, 55.0, 3.5, 5.6), 35.0)))
    }

    @Test fun zoomWhoseMinimumIsNotInTheTableTakesTheTablesPlace() {
        // 17.5 reads "18" (one value above 10 mm has no decimals); the lens's own 17 is listed
        assertEquals(expected("[18] 17 18 20 24 28 30 31 35 38 40 43 45 50 55 60 70"), texts(LensValues.focalEntries(lens(17.0, 70.0, 2.8, 22.0), 17.5)))
    }

    @Test fun zoomWhoseMaximumIsNotInTheTableEndsAtTheValueBelowIt() {
        // desktop's insertion of the maximum never applies: 22 mm is not listed for a 10-22 mm zoom
        assertEquals(expected("[12] 10 12 14 15 16 17 18 20"), texts(LensValues.focalEntries(lens(10.0, 22.0, 3.5, 22.0), 12.0)))
        assertEquals(expected("[60] 52 55 60"), texts(LensValues.focalEntries(lens(52.0, 65.0, 2.0, 16.0), 60.0)))
    }

    @Test fun zoomWhoseEndsAreTableValues() {
        assertEquals(expected("[200] 70 75 77 80 85 90 100 105 110 120 135 150 200 210 240 250 300"), texts(LensValues.focalEntries(lens(70.0, 300.0, 4.0, 32.0), 200.0)))
    }

    @Test fun primeLensesHaveTheirOneFocalLength() {
        assertEquals(listOf("100"), texts(LensValues.focalEntries(lens(100.0, 100.0, 2.8, 32.0), 100.0)))
        assertEquals(listOf("4.5"), texts(LensValues.focalEntries(lens(4.5, 4.5, 2.8, 22.0), 4.5)))
        assertEquals(listOf("52"), texts(LensValues.focalEntries(lens(52.0, 52.0, 1.8, 16.0), 52.0)))
    }

    @Test fun noLensListsTheWholeTable() {
        val e = texts(LensValues.focalEntries(null, 50.0))
        assertEquals("50", e.first())
        assertTrue(e.containsAll(listOf("4.5", "8.0", "24", "300", "1600")))
        assertFalse("0 is not a focal length", e.contains("0.0"))
    }

    // ---- the apertures: from the lens's widest opening to f/90

    @Test fun apertureStartsAtTheLensWidestOpening() {
        val all = "3.5 4.0 4.5 5.0 5.6 6.3 7.1 8.0 9.0 10 11 13 14 16 18 20 22 25 29 32 38 45 50 54 64 90"
        assertEquals(expected("[5.6] $all"), texts(LensValues.apertureEntries(lens(18.0, 55.0, 3.5, 5.6), 5.6)))
        assertEquals(expected("[4.0] 2.0 2.2 2.5 2.8 3.2 3.4 4.0 4.5 5.0 5.6 6.3 7.1 8.0 9.0 10 11 13 14 16 18 20 22 25 29 32 38 45 50 54 64 90"),
            texts(LensValues.apertureEntries(lens(52.0, 65.0, 2.0, 16.0), 4.0)))
    }

    @Test fun apertureListsOneValueOnceWhenTheLimitIsAFloatOfATableValue() {
        // Lensfun's 2.8 is a float; desktop's list reads "2.8 2.8", the app lists it once
        val e = texts(LensValues.apertureEntries(lens(100.0, 100.0, 2.8, 32.0), 2.8))
        assertEquals(expected("[2.8] 2.8 2.8 3.2 3.4 4.0 4.5 5.0 5.6 6.3 7.1 8.0 9.0 10 11 13 14 16 18 20 22 25 29 32 38 45 50 54 64 90"), e)
        assertEquals(e.size, e.toSet().size)
    }

    @Test fun apertureOfALensWithoutFNumbersHasNoZero() {
        val e = texts(LensValues.apertureEntries(lens(24.0, 70.0, null), 4.0))
        assertEquals("4.0", e.first())
        assertEquals("0.70", e[1])
        assertFalse(e.contains("0.0"))
        assertEquals("90", e.last())
    }

    // ---- the distance: 0.25 m, times the square root of 2, to 1000 m

    @Test fun distancesDoubleByRootTwo() {
        assertEquals(
            expected("[2.5] 0.25 0.35 0.50 0.71 1.00 1.4 2.0 2.8 4.0 5.7 8.0 11 16 23 32 45 64 91 128 181 256 362 512 724 1000"),
            texts(LensValues.distanceEntries(2.5)),
        )
    }

    @Test fun distanceListHas25StepsFrom025To1000() {
        val t = LensValues.distanceTable()
        assertEquals(25, t.size)
        assertEquals(0.25, t.first(), 0.0)
        assertEquals(1000.0, t.last(), 0.0)
    }

    // ---- the text of a number

    @Test fun precisionIsDesktops() {
        assertEquals(1, LensValues.precision(0.0))
        assertEquals(5, LensValues.precision(0.0009))
        assertEquals(4, LensValues.precision(0.005))
        assertEquals(3, LensValues.precision(0.05))
        assertEquals(2, LensValues.precision(0.25))
        assertEquals(1, LensValues.precision(4.5))
        assertEquals(0, LensValues.precision(24.0))
        assertEquals("4.5", LensValues.text(4.5))
        assertEquals("8.0", LensValues.text(8.0))
        assertEquals("0.25", LensValues.text(0.25))
        assertEquals("100", LensValues.text(100.0))
        assertEquals("2.8", LensValues.text(2.799999952316284))
    }

    @Test fun gFormatDropsTrailingZeros() {
        assertEquals("100", LensValues.g(100.0))
        assertEquals("2.8", LensValues.g(2.799999952316284))
        assertEquals("4.5", LensValues.g(4.5))
        assertEquals("1.62", LensValues.g(1.6200000047683716))
        assertEquals("0", LensValues.g(0.0))
    }

    @Test fun lensLabelsAreDesktops() {
        val zoom = LensInfo("Canon", "Canon EF-S 18-55mm", 18.0, 55.0, 3.5, 5.6, 1.6, "rectilinear", listOf("Canon EF-S"))
        assertEquals("18-55mm", zoom.focalText)
        assertEquals("3.5-5.6", zoom.apertureText)
        assertEquals("Canon, Canon EF-S 18-55mm", zoom.title)
        assertTrue(zoom.details.contains("mount: Canon EF-S"))
        val prime = LensInfo("Canon", "Canon EF 100mm", 100.0, 100.0, 2.8, 32.0)
        assertEquals("100mm", prime.focalText)
        assertEquals("2.8-32", prime.apertureText)
        assertNull(LensInfo("X", "Y", 50.0, 50.0, null, null).apertureText)
    }

    // ---- typed values

    @Test fun typedValues() {
        assertEquals(17.5, LensValues.parseTyped("17.5")!!, 0.0)
        assertEquals(17.5, LensValues.parseTyped(" 17,5 ")!!, 0.0)
        assertEquals(0.5, LensValues.parseTyped(".5")!!, 0.0)
        assertEquals(1000.0, LensValues.parseTyped("1000")!!, 0.0)
        for (bad in listOf("", " ", "abc", "0", "0.0", "-5", "1e3", "NaN", "Infinity", "5d", "0x10", "1.2.3", "17 mm", "1000000.5", "5f")) assertNull("'$bad'", LensValues.parseTyped(bad))
    }

    // ---- the pickers: groups by maker and the search

    private val cameras = listOf(
        LensCamera("Sony", "ILCE-7M3", "full frame", "Sony E", 1.0),
        LensCamera("Canon", "Canon EOS 40D", null, "Canon EF-S", 1.6),
        LensCamera("Canon", "Canon EOS 5D Mark II", null, "Canon EF", 1.0),
        LensCamera("Sony", "ILCE-7M3", "APS-C crop", "Sony E", 1.5),
        LensCamera("Nikon Corporation", "Nikon D90", null, "Nikon F AF", 1.5),
    )

    @Test fun camerasAreGroupedByMakerAndSortedInAGroup() {
        val g = LensPicker.cameras(cameras, "")
        assertEquals(listOf("Canon", "Nikon Corporation", "Sony"), g.map { it.maker })
        assertEquals(listOf("ILCE-7M3 (APS-C crop)", "ILCE-7M3 (full frame)"), g.last().items.map { it.label })
        assertEquals(listOf("Canon EOS 40D", "Canon EOS 5D Mark II"), g.first().items.map { it.label })
    }

    @Test fun searchMatchesMakerModelAndVariantAsYouType() {
        fun found(q: String) = LensPicker.cameras(cameras, q).flatMap { it.items }.map { it.label }
        assertEquals(listOf("Canon EOS 40D", "Canon EOS 5D Mark II"), found("canon"))
        assertEquals(listOf("Canon EOS 5D Mark II"), found("5d mark"))
        assertEquals(listOf("Canon EOS 5D Mark II"), found("  MARK   5D "))
        assertEquals(listOf("ILCE-7M3 (APS-C crop)"), found("sony aps"))
        assertEquals(listOf("ILCE-7M3 (APS-C crop)", "ILCE-7M3 (full frame)"), found("7m3"))
        assertEquals(listOf("Nikon D90"), found("corporation d90"))
        assertTrue(found("zzz").isEmpty())
        assertEquals(5, found("").size)
    }

    @Test fun lensesAreGroupedAndSearchedToo() {
        val l = listOf(
            LensInfo("Sigma", "Sigma 18-35mm f/1.8 DC HSM | A", 18.0, 35.0, 1.8, 16.0),
            LensInfo("Canon", "Canon EF 100mm f/2.8 Macro USM", 100.0, 100.0, 2.8, 32.0),
            LensInfo("Sigma", "Sigma 4.5mm f/2.8 EX DC HSM circular fisheye", 4.5, 4.5, 2.8, 22.0),
        )
        assertEquals(listOf("Canon", "Sigma"), LensPicker.lenses(l, "").map { it.maker })
        assertEquals(listOf("Sigma 4.5mm f/2.8 EX DC HSM circular fisheye"), LensPicker.lenses(l, "fisheye").flatMap { it.items }.map { it.model })
        assertEquals(2, LensPicker.lenses(l, "sigma dc").flatMap { it.items }.size)
        // a name the database lists twice (other mounts) is one row
        assertEquals(3, LensPicker.lenses(l + l[1], "").flatMap { it.items }.size)
        assertEquals(listOf("Sigma 18-35mm f/1.8 DC HSM | A", "Sigma 4.5mm f/2.8 EX DC HSM circular fisheye"), LensPicker.lenses(l, "").last().items.map { it.model })
        assertEquals("18-35mm · f/1.8-16", lensSubForTest(l[0]))
    }

    private fun lensSubForTest(l: LensInfo) = listOfNotNull(l.focalText, l.apertureText?.let { "f/$it" }).joinToString(" · ")

    // ---- what the engine gives

    @Test fun stateAndListsParse() {
        val st = LensPicker.parseState(Json.parseToJsonElement("""{"has_been_set":true,"camera_param":"Canon EOS 40D","lens_param":"x",
            "exif":{"maker":"Canon","model":"Canon EOS 40D","lens":"EF 100mm"},
            "camera":{"maker":"Canon","model":"Canon EOS 40D","variant":null,"mount":"Canon EF-S","crop_factor":1.6},
            "lens":{"maker":"Canon","model":"Canon EF 100mm f/2.8 Macro USM","min_focal":100,"max_focal":100,"min_aperture":2.8,"max_aperture":32,
                    "crop_factor":1.6,"type":"rectilinear","mounts":["Canon EF","Canon EF-S"]},
            "focal":100,"aperture":2.8,"distance":1000,"crop":1.6}"""))!!
        assertTrue(st.hasBeenSet)
        assertEquals("Canon, Canon EOS 40D", st.cameraTitle)
        assertNull(st.cameraHint)
        assertEquals("Canon, Canon EF 100mm f/2.8 Macro USM", st.lensTitle)
        assertEquals(listOf("Canon EF", "Canon EF-S"), st.lens!!.mounts)
        assertEquals("2.8-32", st.lens!!.apertureText)
    }

    @Test fun unknownCameraAndLensShowWhatTheFileSays() {
        val st = LensPicker.parseState(Json.parseToJsonElement("""{"has_been_set":false,"camera_param":"X-T3","lens_param":"SIGMA 18-50mm F2.8",
            "exif":{"maker":"FUJIFILM","model":"X-T3","lens":"SIGMA 18-50mm F2.8 DC DN"},"camera":null,"lens":null,
            "focal":18,"aperture":2.8,"distance":1000,"crop":1.5}"""))!!
        assertEquals("FUJIFILM, X-T3", st.cameraTitle)
        assertEquals("This camera is not in the Lensfun database. Tap to pick one.", st.cameraHint)
        assertEquals("SIGMA 18-50mm F2.8", st.lensTitle)
        assertNotNull(st.lensHint)
        assertTrue(st.lensHint!!.startsWith("No matching lens in the Lensfun database"))
        assertNull(st.cameraForLensList)
        val none = st.copy(exifMaker = "", exifModel = "", cameraParam = "", lensParam = "")
        assertEquals("No camera information found. Tap to pick one.", none.cameraHint)
        assertEquals("SIGMA 18-50mm F2.8 DC DN", none.lensTitle)
    }
}

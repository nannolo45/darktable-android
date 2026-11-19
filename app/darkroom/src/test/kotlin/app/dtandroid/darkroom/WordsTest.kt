// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsTest {
    @Test fun countsAreCorrectInNumber() {
        assertEquals("1 photo", Words.count(1, "photo"))
        assertEquals("3 photos", Words.count(3, "photo"))
        assertEquals("no photos", Words.count(0, "photo"))
        assertEquals("2 copies", Words.count(2, "copy", "copies"))
    }
}

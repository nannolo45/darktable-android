// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import app.dtandroid.engine.ModuleTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brief P2f: darktable's "-d perf" lines as `dt_show_times_f` prints them. */
class ModuleTimesTest {
    private val log = """
        |    12.3456 [dev_pixelpipe] took 0.010 secs (0.020 CPU) initing base buffer [export]
        |    12.4000 [dev_pixelpipe] took 0.250 secs (0.900 CPU) [export] processed `demosaic' on CPU
        |    12.4100 [dev_pixelpipe] took 0.004 secs (0.012 CPU) [export] processed `exposure' on CPU, blended on CPU
        |    12.5000 [dev_pixelpipe] took 0.081 secs (0.300 CPU) [export] processed `colorbalancergb 1' on CPU with tiling
        |    12.5001 some other line
    """.trimMargin()

    @Test fun parsesModulesAndInput() {
        val e = ModuleTimes.parse(log)
        assertEquals(listOf("(input)", "demosaic", "exposure", "colorbalancergb 1"), e.map { it.module })
        assertEquals(250.0, e[1].wallMs, 1e-9)
        assertEquals(900.0, e[1].cpuMs, 1e-9)
    }

    @Test fun tableIsSlowestFirst() {
        val t = ModuleTimes.table(ModuleTimes.parse(log), top = 2)
        assertTrue(t, t.startsWith("sum 345.0 ms over 4 modules: demosaic 250.0; colorbalancergb 1 81.0; 2 others 14.0"))
        assertTrue(ModuleTimes.table(emptyList()).startsWith("no per-module times"))
    }
}

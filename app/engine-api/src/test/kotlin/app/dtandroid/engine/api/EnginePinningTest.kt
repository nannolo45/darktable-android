// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import app.dtandroid.engine.EnginePinning
import app.dtandroid.engine.FastCores
import app.dtandroid.engine.Pinning
import app.dtandroid.engine.fake.FakeEngine
import app.dtandroid.engine.fake.FakeModuleCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P2g: the choice of CPUs among those the process may use, and a start with an engine that
 * refuses the pinning. The device in these tests has 8 CPUs, 4 slow ones (0-3) and 4 fast ones (4-7).
 */
class EnginePinningTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val catalog by lazy { FakeModuleCatalog.fromSchemaJson(TestSchema.text) }

    private val eight = (0..7).toList()
    private val bigLittle = FastCores(eight, eight.associateWith { if (it < 4) 1_800_000L else if (it < 7) 2_420_000L else 2_840_000L },
        FastCores.choose(eight, eight.associateWith { if (it < 4) 1_800_000L else if (it < 7) 2_420_000L else 2_840_000L }))

    private fun engine(threads: Int = 8, cpusAtInit: Set<Int>? = null) = FakeEngine(catalog, FakeEngine.Timing.NONE).also {
        it.cpusAtInit = cpusAtInit
        runBlocking { it.init(EnginePaths("a", "b", "c", "d", "e"), threads) }
    }

    @Test
    fun fastCoresFoundFromTheClocks() {
        assertEquals(listOf(4, 5, 6, 7), bigLittle.chosen)
        assertTrue(bigLittle.pin)
        assertEquals(listOf(0, 1), FastCores.choose(listOf(0, 1), mapOf(0 to null, 1 to null)))
        assertEquals("0-3,5,7-8", FastCores.formatCpuList(listOf(8, 0, 1, 2, 3, 5, 7)))
        assertEquals("none", FastCores.formatCpuList(emptyList()))
    }

    @Test
    fun allFastCoresAllowed() {
        val p = EnginePinning.plan(bigLittle, allowed = eight, atStart = eight)
        assertEquals(listOf(4, 5, 6, 7), p.cpus)
        assertEquals("the fast cores", p.why)
    }

    @Test
    fun fastCoresPartlyAllowed() {
        // e.g. Android's "restricted" or "foreground" set leaves out the fastest core(s)
        val p = EnginePinning.plan(bigLittle, allowed = (0..5).toList(), atStart = null)
        assertEquals(listOf(4, 5), p.cpus)
        assertTrue(p.why, p.why.contains("6-7 not available"))
    }

    @Test
    fun noFastCoreAllowed() {
        // the owner's case: the process started while Android allowed the slow cores only
        val p = EnginePinning.plan(bigLittle, allowed = (0..3).toList(), atStart = (0..3).toList())
        assertTrue(p.cpus.isEmpty())
        assertTrue(p.why, p.why.contains("only CPUs 0-3"))
    }

    @Test
    fun allowedNowButNotAtStart() {
        // back in the foreground the process may use 0-7, but the engine started with 0-3: the bridge would refuse
        val p = EnginePinning.plan(bigLittle, allowed = eight, atStart = (0..3).toList())
        assertTrue(p.cpus.isEmpty())
        assertTrue(p.why, p.why.contains("engine started while Android let the app use only CPUs 0-3"))
    }

    @Test
    fun allowedListUnreadable() {
        // nothing to choose among: the fast cores are tried, and the bridge has the last word
        val p = EnginePinning.plan(bigLittle, allowed = null, atStart = null)
        assertEquals(listOf(4, 5, 6, 7), p.cpus)
    }

    @Test
    fun clocksUnreadableOrEqual() {
        val unknown = FastCores(eight, eight.associateWith { null }, eight)
        assertTrue(EnginePinning.plan(unknown, eight).cpus.isEmpty())
        assertTrue(EnginePinning.plan(unknown, eight).why.contains("clocks cannot be read"))
        val equal = FastCores(eight, eight.associateWith { 2_000_000L }, eight)
        assertTrue(EnginePinning.plan(equal, eight).why.contains("equally fast"))
    }

    @Test
    fun parsesTheProcStatusLine() {
        val text = "Name:\tdt-engine\nCpus_allowed:\tff\nCpus_allowed_list:\t0-3,6\nMems_allowed:\t1\n"
        assertEquals(listOf(0, 1, 2, 3, 6), EnginePinning.parseAllowed(text))
        assertNull(EnginePinning.parseAllowed("Name:\tx\n"))
        assertNull(EnginePinning.parseAllowed("Cpus_allowed_list:\t\n"))
        assertNull(EnginePinning.readAllowed(File(tmp.root, "missing")))
        val f = tmp.newFile("status").apply { writeText(text) }
        assertEquals(listOf(0, 1, 2, 3, 6), EnginePinning.readAllowed(f))
    }

    @Test
    fun pinsWhenTheEngineAccepts() = runBlocking {
        val e = engine(cpusAtInit = eight.toSet())
        val p = EnginePinning.pin(e, bigLittle, allowed = eight, atStart = eight)
        assertTrue(p.pinned)
        assertEquals(listOf(4, 5, 6, 7), e.threadCpus)
        assertEquals(4, e.threadCount)
        assertEquals("pinned to CPUs 4-7 (the fast cores)", p.describe())
    }

    /** The acceptance test: the stand-in engine refuses the pinning, the start goes on unpinned without an error. */
    @Test
    fun startWithAnEngineThatRefusesThePinning() = runBlocking {
        val e = engine(threads = 4, cpusAtInit = (0..3).toSet())
        // the list of allowed CPUs said 0-7 (or could not be read), but the engine started on 0-3 only
        val p = EnginePinning.pin(e, bigLittle, allowed = null, atStart = null)
        assertFalse(p.pinned)
        assertTrue(p.describe(), p.describe().startsWith("not pinned: the engine refused CPUs 4-7 (") && p.describe().contains("CPU 4 is not available to this process"))
        // unpinned, with the start-up thread count, and the engine works
        assertEquals(emptyList<Int>(), e.threadCpus)
        assertEquals(4, e.threadCount)
        assertEquals(4, e.engineInfo().threads)
        e.setThreads(2)
        assertEquals(2, e.engineInfo().threads)
    }

    @Test
    fun repinAfterRefusalAndUndoAnEarlierPinning() = runBlocking {
        val e = engine(cpusAtInit = eight.toSet())
        val first = EnginePinning.pin(e, bigLittle, allowed = eight, atStart = eight)
        assertEquals(listOf(4, 5, 6, 7), e.threadCpus)
        // back in the foreground, Android lets the app use the slow cores only: the pinning is undone
        val second = EnginePinning.pin(e, bigLittle, allowed = (0..3).toList(), atStart = eight, previous = first)
        assertFalse(second.pinned)
        assertEquals(emptyList<Int>(), e.threadCpus)
        assertEquals(8, e.threadCount)
        // and later pinned again
        val third = EnginePinning.pin(e, bigLittle, allowed = eight, atStart = eight, previous = second)
        assertTrue(third.pinned)
        assertEquals(listOf(4, 5, 6, 7), e.threadCpus)
    }

    @Test
    fun unpinnedPlanLeavesTheCpuSetAloneWhenNothingWasPinned() = runBlocking {
        val e = engine(cpusAtInit = eight.toSet())
        val p = EnginePinning.apply(e, EnginePinning.Plan(emptyList(), "test"), eight, Pinning.NONE)
        assertFalse(p.pinned)
        assertEquals("not pinned: test", p.describe())
        assertEquals(8, e.threadCount)
    }
}

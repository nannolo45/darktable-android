// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The app-private layout store against LayoutStore's documented behaviour (made-up layouts). */
class FileLayoutStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val builtinsA = listOf("workflow: scene-referred" to "1|0ꬹ1|||a/x", "modules: all" to "1|0ꬹ0||ꬹg|basic||a")
    private val builtinsB = listOf("workflow: scene-referred" to "1|0ꬹ1|||b/y", "modules: all" to "1|0ꬹ0||ꬹg|basic||b")

    private fun store(file: File = File(tmp.root, "layouts.json")) = FileLayoutStore(
        file, { wf -> if (wf == "B") builtinsB else builtinsA }, "A", "workflow: scene-referred",
    )

    private fun <T> expectFailure(block: suspend () -> T) = runBlocking {
        try { block(); fail("expected LayoutStoreException") } catch (e: LayoutStoreException) { assertTrue(e.message!!.isNotEmpty()) }
    }

    @Test
    fun listsBuiltinsFirstThenUserLayoutsByName() = runBlocking {
        val s = store()
        s.write("zebra", "1|0ꬹ0||")
        s.write("Alpha", "0|0ꬹ0||")
        s.write("éclair", "1|1ꬹ0||")
        assertEquals(listOf("modules: all", "workflow: scene-referred", "Alpha", "zebra", "éclair"), s.list().map { it.name })
        assertEquals(listOf(true, true, false, false, false), s.list().map { it.writeProtected })
    }

    @Test
    fun builtinsCannotBeChanged() {
        val s = store()
        expectFailure { s.write("modules: all", "x") }
        expectFailure { s.delete("modules: all") }
        expectFailure { s.rename("modules: all", "mine") }
        runBlocking { assertEquals("1|0ꬹ0||ꬹg|basic||a", s.read("modules: all")!!.params) }
    }

    @Test
    fun writeReplacesReadsBackAndSurvivesAReopen() = runBlocking {
        val f = File(tmp.root, "layouts.json")
        val s = store(f)
        s.write("mine", "1|0ꬹ1|||exposure/exposureꬹa|tone||exposure")
        s.write("mine", "1|0ꬹ0||ꬹa|tone||exposure|unknownmodule")
        s.setCurrent("mine")
        val again = store(f)
        assertEquals("1|0ꬹ0||ꬹa|tone||exposure|unknownmodule", again.read("mine")!!.params)
        assertEquals("mine", again.current())
        assertNull(again.read("nothing"))
        assertTrue(f.isFile && !File(f.path + ".tmp").exists())
    }

    @Test
    fun currentFallsBackAsDarktableDoes() = runBlocking {
        val s = store()
        assertEquals("workflow: scene-referred", s.current())
        s.write("mine", "1|0ꬹ0||")
        s.setCurrent("mine")
        s.rename("mine", "ours")
        assertEquals("ours", s.current())
        s.delete("ours")
        assertEquals("workflow: scene-referred", s.current())
        expectFailure { s.setCurrent("does not exist") }
    }

    @Test
    fun renameRefusesTakenNames() {
        val s = store()
        runBlocking { s.write("one", "1|0ꬹ0||"); s.write("two", "0|0ꬹ0||") }
        expectFailure { s.rename("one", "two") }
        expectFailure { s.rename("one", "workflow: scene-referred") }
        expectFailure { s.rename("one", "") }
        expectFailure { s.write("", "1|0ꬹ0||") }
    }

    @Test
    fun workflowSelectsTheBuiltinVariant() = runBlocking {
        val s = store()
        assertEquals("A", s.workflow())
        s.setWorkflow("B")
        assertEquals("1|0ꬹ1|||b/y", s.read("workflow: scene-referred")!!.params)
        assertEquals("B", store().workflow())
    }

    @Test
    fun damagedFileIsReportedNotIgnored() {
        val f = File(tmp.root, "layouts.json").apply { writeText("{not json") }
        expectFailure { store(f).list() }
    }
}

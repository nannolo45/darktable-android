// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P6b (R19, PLAN.md 4.6): the phone's LUT folder (path rules, import, removal marks, the chooser's levels) and
 * its exchange with the stub server's R19 ([StubLuts], written from the contract), a PC played through the stub's own
 * calls: upload with base, download of new and changed files, conflicts and the three choices, removal, .gmz,
 * automatic sync off.
 */
class P6bLutsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl().also { it.r19 = true; it.agentNames["pc-1"] = "Studio PC" }
    private val o get() = stub.origin

    @After fun stop() = stub.close()

    private val cubeA = "TITLE \"a\"\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n".toByteArray()
    private val cubeB = String(cubeA).replace("\"a\"", "\"b\"").toByteArray()
    private val cubeC = String(cubeA).replace("\"a\"", "\"c\"").toByteArray()
    private fun sha(b: ByteArray) = StubLuts.sha256(b)

    inner class Device(name: String = "Phone") {
        val servers = ServerStore(File(tmp.root, "$name/lpl"), MemorySecretBox()).also { it.put(o, name, null) }
        val settings = SyncSettings.inMemory()
        val agent = LplAgent(
            servers, WorkStore(File(tmp.root, "$name/lpl/work")), FakeEditor(), { "test" },
            clientFor = { origin -> LplClient(origin, { null }, settings = ClientSettings(backoffMs = listOf(1, 1), connectTimeoutMs = 500, readTimeoutMs = 3_000), sleep = {}) },
            settings = settings,
        )
        val luts get() = agent.luts
        val folder get() = agent.luts.folder
        val id get() = servers.installId
        suspend fun start() { agent.heartbeat(); agent.heartbeat() }
        suspend fun pass(transfer: Boolean = true) = luts.pass(o, transfer, throwing = true)
        val view get() = luts.views.value[o]!!
        fun add(path: String, bytes: ByteArray, replace: Boolean = false): LutImport {
            val sub = path.substringBeforeLast('/', "")
            return folder.import(path.substringAfterLast('/'), sub, { bytes.inputStream() }, replace)
        }
        fun bytes(path: String) = folder.fileOf(path)!!.readBytes()
    }

    // ------------------------------------------------------------------ path rules

    @Test fun pathRulesRefuseEscapesAndAbsolutePaths() {
        for (bad in listOf("", "/abs.cube", "C:/x.cube", "a\\b.cube", "../x.cube", "a/../x.cube", "a//x.cube", "./x.cube", "a/", "x.txt",
            "x.cube.txt", "a\u0001.cube", "x".repeat(600) + ".cube"))
            assertNotNull("'$bad' must be refused", LutPaths.problem(bad))
        for (good in listOf("x.cube", "X.CUBE", "film/Kodak 2383.3dl", "hald/identity.PNG", "g/pack.gmz", "ä/ß.cube"))
            assertNull("'$good' is allowed", LutPaths.problem(good))
    }

    @Test fun whatThisDarktableReads() {
        assertTrue(LutPaths.usable("a.cube") && LutPaths.usable("A.CUBE") && LutPaths.usable("x.3DL") && LutPaths.usable("h.png"))
        // desktop lists these but its _calculate_clut() reads only these spellings
        assertFalse(LutPaths.usable("Warm.Cube"))
        assertTrue(LutPaths.notUsableReason("Warm.Cube")!!.contains("spelt"))
        assertTrue(LutPaths.notUsableReason("pack.gmz")!!.contains("G'MIC"))
        assertFalse(LutPaths.wanted("dir/pack.GMZ"))
    }

    @Test fun keepBothNamesTheDeviceAndCounts() {
        assertEquals("film/warm (Phone).cube", LutPaths.keepBothName("film/warm.cube", "Phone") { false })
        val taken = setOf("warm (Phone).cube", "warm (Phone 2).cube")
        assertEquals("warm (Phone 3).cube", LutPaths.keepBothName("warm.cube", "Phone") { it in taken })
    }

    // ------------------------------------------------------------------ the folder

    @Test fun importAsksBeforeReplacingAndRefusesWhatIsNotALut() {
        val d = Device()
        assertEquals(LutImport.Added("film/a.cube"), d.add("film/a.cube", cubeA))
        assertEquals(LutImport.Exists("film/a.cube"), d.add("film/a.cube", cubeB))
        assertTrue(d.bytes("film/a.cube").contentEquals(cubeA))
        assertEquals(LutImport.Added("film/a.cube"), d.add("film/a.cube", cubeB, replace = true))
        assertTrue(d.bytes("film/a.cube").contentEquals(cubeB))
        assertTrue(d.folder.import("notes.txt", "", { cubeA.inputStream() }) is LutImport.Refused)
        assertTrue(d.folder.import("x.cube", "../up", { cubeA.inputStream() }) is LutImport.Refused)
        assertNull(d.folder.fileOf("../escape.cube"))
    }

    @Test fun chooserLevelsShowFoldersAndGreyedFiles() {
        val d = Device()
        d.add("top.cube", cubeA); d.add("film/a.cube", cubeA); d.add("film/b.3dl", cubeA); d.add("film/pack.gmz", cubeA)
        d.add("film/old/c.png", cubeA)
        val root = d.folder.level("")
        assertEquals(listOf("film"), root.folders)
        assertEquals(listOf("top.cube"), root.files.map { it.path })
        val film = d.folder.level("film")
        assertEquals(listOf("film/old"), film.folders)
        assertEquals(listOf("film/a.cube", "film/b.3dl", "film/pack.gmz"), film.files.map { it.path })
        assertFalse(film.files.first { it.path.endsWith(".gmz") }.usable)
    }

    // ------------------------------------------------------------------ with LightPhotoLibrary

    @Test fun capsAreAnnouncedOnlyOnceTheServerListsLuts() = runBlocking {
        val d = Device()
        d.agent.heartbeat()
        assertFalse(stub.heartbeats[d.id]!!.contains("\"luts\""))
        d.agent.heartbeat()
        assertTrue(stub.heartbeats[d.id]!!.contains("\"luts\""))
    }

    @Test fun aPhoneFileGoesUpWithAnEmptyBaseAndIsReported() = runBlocking {
        val d = Device(); d.start()
        d.add("film/a.cube", cubeA)
        d.pass()
        assertEquals(sha(cubeA), stub.luts.get("film/a.cube")!!.sha)
        assertEquals("film/a.cube ${d.id}  200", stub.luts.puts.single().replace(" - ", "  "))
        assertEquals(LutSyncState.SYNCED, d.view.states["film/a.cube"])
        val rep = stub.luts.reports[d.id]!!
        assertEquals("on this phone", (rep["folder"] as JsonPrimitive).content)
        val f = (rep["files"] as JsonObject)["film/a.cube"] as JsonObject
        assertEquals(sha(cubeA), (f["sha256"] as JsonPrimitive).content)
        // nothing changed: nothing sent again
        d.pass()
        assertEquals(1, stub.luts.puts.size)
    }

    @Test fun aPcFileArrivesAndAChangeOnThePcReplacesAnUnchangedPhoneCopy() = runBlocking {
        val d = Device(); d.start()
        stub.luts.put("pc-1", "pc/warm.cube", cubeA, sha(cubeA), null)
        d.pass()
        assertTrue(d.bytes("pc/warm.cube").contentEquals(cubeA))
        assertEquals(LutSyncState.SYNCED, d.view.states["pc/warm.cube"])
        stub.luts.put("pc-1", "pc/warm.cube", cubeB, sha(cubeB), null)  // the PC changed it: newest wins
        d.pass()
        assertTrue(d.bytes("pc/warm.cube").contentEquals(cubeB))
        assertEquals(0, stub.luts.puts.count { " ${d.id} " in it })
    }

    @Test fun changedOnBothSidesIsAConflictAndMineSendsWithTheServersHash() = runBlocking {
        val d = Device(); d.start()
        stub.luts.put("pc-1", "w.cube", cubeA, sha(cubeA), null)
        d.pass()
        d.add("w.cube", cubeB, replace = true)
        stub.luts.put("pc-1", "w.cube", cubeC, sha(cubeC), null)
        d.pass()
        assertEquals(LutSyncState.CONFLICT, d.view.states["w.cube"])
        assertEquals(sha(cubeC), d.view.conflicts.single().serverSha)
        assertEquals("Studio PC", d.view.conflicts.single().serverDevice)
        assertTrue(d.bytes("w.cube").contentEquals(cubeB))  // nothing replaced while in conflict
        d.pass()
        assertEquals(LutSyncState.CONFLICT, d.view.states["w.cube"])
        assertNull(d.luts.resolve(o, "w.cube", "mine"))
        assertEquals(sha(cubeB), stub.luts.get("w.cube")!!.sha)
        assertTrue(stub.luts.puts.last().contains(" ${sha(cubeC)} 200"))
        assertTrue(d.view.conflicts.isEmpty())
        assertEquals(LutSyncState.SYNCED, d.view.states["w.cube"])
    }

    @Test fun aStaleBaseGets409AndTheirsTakesTheServersFile() = runBlocking {
        val d = Device(); d.start()
        d.add("w.cube", cubeA)
        stub.luts.put("pc-1", "w.cube", cubeC, sha(cubeC), null)  // LPL has another file at the path the phone never sent
        d.pass()
        assertTrue(stub.luts.puts.any { it.startsWith("w.cube ${d.id}  409") || it == "w.cube ${d.id}  409" })
        assertEquals(LutSyncState.CONFLICT, d.view.states["w.cube"])
        assertNull(d.luts.resolve(o, "w.cube", "theirs"))
        assertTrue(d.bytes("w.cube").contentEquals(cubeC))
        assertEquals(LutSyncState.SYNCED, d.view.states["w.cube"])
    }

    @Test fun keepBothRenamesMineUploadsItAndTakesTheirs() = runBlocking {
        val d = Device(); d.start()
        stub.luts.put("pc-1", "film/w.cube", cubeA, sha(cubeA), null)
        d.pass()
        d.add("film/w.cube", cubeB, replace = true)
        stub.luts.put("pc-1", "film/w.cube", cubeC, sha(cubeC), null)
        d.pass()
        assertNull(d.luts.resolve(o, "film/w.cube", "both"))
        assertTrue(d.bytes("film/w.cube").contentEquals(cubeC))
        assertTrue(d.bytes("film/w (Phone).cube").contentEquals(cubeB))
        assertEquals(sha(cubeB), stub.luts.get("film/w (Phone).cube")!!.sha)
        assertEquals(sha(cubeC), stub.luts.get("film/w.cube")!!.sha)
        assertTrue(d.view.conflicts.isEmpty())
    }

    @Test fun removeIsThePhonesOnlyAndIsNotUndoneBySyncButGetBringsItBack() = runBlocking {
        val d = Device(); d.start()
        stub.luts.put("pc-1", "a.cube", cubeA, sha(cubeA), null)
        d.pass()
        assertTrue(d.luts.remove("a.cube"))
        assertFalse(d.folder.has("a.cube"))
        d.pass()
        assertFalse("removed here: not downloaded again", d.folder.has("a.cube"))
        assertNotNull(stub.luts.get("a.cube"))  // LPL keeps it
        assertEquals("removed", ((stub.luts.reports[d.id]!!["files"] as JsonObject)["a.cube"] as JsonObject)["state"].let { (it as JsonPrimitive).content })
        assertEquals(LutSyncState.REMOVED, d.view.states["a.cube"])
        assertNotNull(d.luts.onServer("a.cube"))
        assertNull(d.luts.fetch("a.cube"))
        assertTrue(d.bytes("a.cube").contentEquals(cubeA))
        d.pass()
        assertTrue(d.folder.has("a.cube"))
    }

    @Test fun aFileRemovedFromLplStaysOnThePhone() = runBlocking {
        val d = Device(); d.start()
        stub.luts.put("pc-1", "a.cube", cubeA, sha(cubeA), null)
        d.pass()
        stub.luts.remove("a.cube")
        d.pass()
        assertTrue(d.folder.has("a.cube"))
        assertNull(d.luts.onServer("a.cube"))
    }

    @Test fun gmzFilesAreNotTakenByThePhone() = runBlocking {
        val d = Device(); d.start()
        stub.luts.put("pc-1", "pack.gmz", cubeA, sha(cubeA), null)
        stub.luts.put("pc-1", "ok.cube", cubeA, sha(cubeA), null)
        d.pass()
        assertFalse(d.folder.has("pack.gmz"))
        assertTrue(d.folder.has("ok.cube"))
    }

    @Test fun withAutomaticSyncOffAPassOnlyLooks() = runBlocking {
        val d = Device(); d.start()
        d.settings.setAutoSync(false)
        d.add("mine.cube", cubeA)
        stub.luts.put("pc-1", "pc.cube", cubeB, sha(cubeB), null)
        d.luts.pass(o, throwing = true)  // the default follows the setting
        assertTrue(stub.luts.puts.none { " ${d.id} " in it })
        assertFalse(d.folder.has("pc.cube"))
        assertEquals(LutSyncState.ONLY_LPL, d.view.states["pc.cube"])
        assertEquals(LutSyncState.LOCAL, d.view.states["mine.cube"])
        d.luts.pass(o, transfer = true, throwing = true)  // "sync now"
        assertTrue(d.folder.has("pc.cube"))
        assertNotNull(stub.luts.get("mine.cube"))
    }

    @Test fun anOlderServerIsLeftAlone() = runBlocking {
        stub.r19 = false
        val d = Device(); d.start()
        d.add("mine.cube", cubeA)
        d.pass()
        assertFalse(d.view.active)
        assertTrue(stub.seen("GET", "/api/luts").isEmpty())
    }

    @Test fun theFolderCountsItsChangesForTheEngine() {
        val d = Device()
        val v0 = d.folder.version.value
        d.add("a.cube", cubeA)
        d.luts.remove("a.cube")
        assertEquals(v0 + 2, d.folder.version.value)
    }
}

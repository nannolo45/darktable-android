// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Brief P3f (versions, app side) on the app's LPL code: file names and numbers (gaps, 100 and over, never reused),
 * the XMP's version name and virgin duplicates, and the sync of versions against the stub server (R17): download of
 * every version, posting per version, `409 version_exists`, deletion, a server without the capability, the R1 link.
 */
class P3fVersionsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl().also { it.r17 = true }
    private val editor = FakeEditor()
    private lateinit var servers: ServerStore
    private lateinit var agent: LplAgent
    private val o get() = stub.origin

    private fun newAgent() = LplAgent(
        servers, WorkStore(File(tmp.root, "lpl/work")), editor, { "test" },
        clientFor = { origin -> LplClient(origin, { servers.password(origin) }, settings = ClientSettings(backoffMs = listOf(1, 1)), sleep = {}) },
    )

    @Before fun setUp() {
        servers = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        servers.put(o, "Phone", null)
        agent = newAgent()
        stub.add(StubLpl.Photo(21, "IMG_01.RAF", fakeRaw(100_000, 21), rating = 3))
    }

    @After fun stop() = stub.close()

    private fun dir() = agent.work.folder(o, 21)
    private fun xmp(n: Int) = VersionFiles.file(dir(), "IMG_01.RAF", n)
    private fun put(n: Int, body: String = "x") = xmp(n).writeText(StubLpl.lplXmp(1).decodeToString().replace("</rdf:RDF>", "<!-- $body --></rdf:RDF>"))

    // ------------------------------------------------------------------ names and numbers

    @Test fun namesHaveTheNumberBeforeTheExtension() {
        assertEquals("IMG_01.RAF.xmp", VersionFiles.xmpName("IMG_01.RAF", 0))
        assertEquals("IMG_01_01.RAF.xmp", VersionFiles.xmpName("IMG_01.RAF", 1))
        assertEquals("IMG_01_99.RAF.xmp", VersionFiles.xmpName("IMG_01.RAF", 99))
        assertEquals("IMG_01_100.RAF.xmp", VersionFiles.xmpName("IMG_01.RAF", 100))
        assertEquals("IMG_01_1000.RAF.xmp", VersionFiles.xmpName("IMG_01.RAF", 1000))
        assertEquals(2, VersionFiles.numberOf("IMG_01.RAF", "IMG_01_02.RAF.xmp"))
        assertEquals(100, VersionFiles.numberOf("IMG_01.RAF", "IMG_01_100.RAF.xmp"))
        assertEquals(0, VersionFiles.numberOf("IMG_01.RAF", "IMG_01.RAF.xmp"))
        // not what darktable writes: one digit, a leading zero from 100, zero, letters, another photo, no .xmp
        for (bad in listOf("IMG_01_2.RAF.xmp", "IMG_01_0100.RAF.xmp", "IMG_01_00.RAF.xmp", "IMG_01_ab.RAF.xmp", "IMG_02_02.RAF.xmp", "IMG_01_02.RAF", "IMG_01_02.CR2.xmp", "IMG_01__02.RAF.xmp"))
            assertNull(bad, VersionFiles.numberOf("IMG_01.RAF", bad))
    }

    @Test fun numbersListGapsAndNeverReuseADeletedNumber() {
        val d = tmp.newFolder("w")
        File(d, "IMG_01.RAF").writeText("raw")
        for (n in listOf(0, 1, 3, 100)) VersionFiles.file(d, "IMG_01.RAF", n).writeText("<x/>")
        assertEquals(listOf(0, 1, 3, 100), VersionFiles.numbers(d, "IMG_01.RAF"))
        assertEquals("highest in use plus one, gaps are not filled", 101, VersionFiles.next(d, "IMG_01.RAF"))
        // deleting the highest does not free its number
        val f = VersionFolder(d, "IMG_01.RAF")
        val n = f.reserve()
        assertEquals(101, n)
        VersionFiles.file(d, "IMG_01.RAF", 101).writeText("<x/>")
        assertEquals(VersionResult.Done(101), f.delete(101))
        assertEquals(102, f.reserve())
        // a file that is the sidecar of another RAW of the folder is not a version of this one
        File(d, "IMG_01_05.RAF").writeText("another raw")
        VersionFiles.file(d, "IMG_01.RAF", 5).writeText("<x/>")
        assertFalse(5 in VersionFiles.numbers(d, "IMG_01.RAF"))
    }

    @Test fun version0CannotBeDeletedFromTheApp() {
        val d = tmp.newFolder("w0")
        VersionFiles.file(d, "A.RAF", 0).writeText("<x/>")
        assertTrue(VersionFolder(d, "A.RAF").delete(0) is VersionResult.Refused)
        assertTrue(VersionFiles.file(d, "A.RAF", 0).isFile)
    }

    // ------------------------------------------------------------------ the XMP

    private val darktableXmp = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 4.4.0-Exiv2">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:darktable="http://darktable.sf.net/"
   xmp:Rating="4"
   darktable:xmp_version="5"
   darktable:history_end="2"
   darktable:iop_order_version="4"
   darktable:version_name="old name">
   <dc:subject><rdf:Bag><rdf:li>sunset</rdf:li></rdf:Bag></dc:subject>
   <darktable:history>
    <rdf:Seq>
     <rdf:li darktable:num="0" darktable:operation="exposure" darktable:enabled="1" darktable:modversion="6"/>
     <rdf:li darktable:num="1" darktable:operation="filmicrgb" darktable:enabled="1" darktable:modversion="7"/>
    </rdf:Seq>
   </darktable:history>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>""".toByteArray()

    @Test fun versionNameIsReadSetAndRemoved() {
        assertEquals("old name", XmpMeta.versionName(darktableXmp))
        val renamed = XmpMeta.withVersionName(darktableXmp, "  for print ")!!
        assertEquals("for print", XmpMeta.versionName(renamed))
        // everything else is kept
        val t = renamed.decodeToString()
        assertTrue(t.contains("xmp:Rating=\"4\"") && t.contains("sunset") && t.contains("filmicrgb") && t.contains("darktable:history_end=\"2\""))
        assertEquals("", XmpMeta.versionName(XmpMeta.withVersionName(renamed, "")!!))
        assertNull(XmpMeta.versionName("not xml".toByteArray()))
        assertNull(XmpMeta.withVersionName("not xml".toByteArray(), "x"))
    }

    @Test fun virginDuplicateKeepsRatingAndTagsButNoHistory() {
        assertEquals(true, XmpMeta.hasEdits(darktableXmp))
        val v = XmpMeta.withoutHistory(darktableXmp)!!
        val t = v.decodeToString()
        assertEquals(false, XmpMeta.hasEdits(v))
        assertFalse(t.contains("filmicrgb") || t.contains("history_end") || t.contains("iop_order_version") || t.contains("version_name"))
        assertTrue(t.contains("xmp:Rating=\"4\"") && t.contains("sunset") && t.contains("xmp_version"))
    }

    @Test fun xmpFromAServerIsNeverTrustedWithADoctype() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x:xmpmeta xmlns:x="adobe:ns:meta/">&e;</x:xmpmeta>""".toByteArray()
        assertNull(XmpMeta.hasEdits(evil))
    }

    @Test fun finishMakesADuplicateWithoutTheNameAndAVirginWithoutHistory() {
        val d = tmp.newFolder("w2")
        val f = VersionFolder(d, "IMG_01.RAF")
        val n = f.reserve()
        f.file(n).writeBytes(darktableXmp) // what the engine's xmp_write wrote
        assertEquals(VersionResult.Done(1), f.finish(n, virgin = false))
        assertEquals(VersionRow(1, "", true), f.rows().single { it.n == 1 })
        val m = f.reserve()
        f.file(m).writeBytes(darktableXmp)
        assertEquals(VersionResult.Done(2), f.finish(m, virgin = true))
        assertEquals(VersionRow(2, "", false), f.rows().single { it.n == 2 })
    }

    @Test fun renameRefusesAFileThatFailsTheVersionGuard() = runBlocking {
        val d = tmp.newFolder("w3")
        val f = VersionFolder(d, "IMG_01.RAF")
        f.file(1).writeBytes(darktableXmp)
        val before = f.file(1).readBytes()
        assertEquals(VersionResult.ReadOnly, f.rename(1, "x") { false })
        assertArrayEquals(before, f.file(1).readBytes())
        assertEquals(VersionResult.Done(1), f.rename(1, "x") { true })
        assertEquals("x", f.rows().single { it.n == 1 }.name)
    }

    // ------------------------------------------------------------------ the server side

    @Test fun capsSayVersionsAndCheckoutListsThem() {
        assertTrue("versions" in LplClient.CAPS)
        agent.client(o).heartbeat("a", "Phone", agent.state(o))
        assertTrue(stub.heartbeatLog.last().contains("\"versions\""))
        stub.photos[21]!!.versions[2] = StubLpl.Ver(StubLpl.lplXmp(5), 1.0, "bw")
        val co = agent.client(o).checkout(21, "a")
        assertEquals(listOf(0, 2), co.versions!!.map { it.n })
        assertEquals("bw", co.versions!!.last().name)
        stub.r17 = false
        assertNull("an older server lists none", agent.client(o).checkout(21, "a").versions)
        stub.r17 = true
        assertNull("a device that did not name itself is told nothing new", agent.client(o).checkout(21).versions)
    }

    @Test fun takingAPhotoDownloadsEveryVersion() = runBlocking {
        val ph = stub.photos[21]!!
        ph.versions[1] = StubLpl.Ver(StubLpl.lplXmp(1), 1.0)
        ph.versions[3] = StubLpl.Ver(StubLpl.lplXmp(3), 1.0)
        val r = agent.take(o, 21)
        assertTrue(r.versionsCap)
        assertEquals(listOf(0, 1, 3), VersionFiles.numbers(dir(), "IMG_01.RAF"))
        assertEquals(listOf(1, 3), r.versions.map { it.n })
        assertFalse("nothing to send yet", agent.isDirty(r))
        // the number 2 was never used here, but the next version is 4
        assertEquals(4, VersionFiles.next(dir(), "IMG_01.RAF"))
    }

    @Test fun aVersionMadeOnThePhoneIsPostedWithItsNumberAndPreview() = runBlocking {
        agent.take(o, 21)
        put(1, "copy")
        agent.versionMade(o, 21, 1)
        assertTrue(agent.isDirty(agent.record(o, 21)!!))
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        val ph = stub.photos[21]!!
        assertEquals(listOf("post 1", "preview 1"), ph.versionLog.toList())
        assertTrue(ph.versions[1]!!.xmp.decodeToString().contains("copy"))
        assertNotNull(ph.versions[1]!!.preview)
        assertFalse(agent.isDirty(agent.record(o, 21)!!))
        // nothing changed: nothing is sent again
        agent.sync(o, 21)
        assertEquals(2, ph.versionLog.size)
        // an edit of the version goes again, and only that version
        put(1, "edited")
        agent.sync(o, 21)
        assertEquals(listOf("post 1", "preview 1", "post 1", "preview 1"), ph.versionLog.toList())
        assertEquals("version 0 was not sent", 0, ph.xmpPosts)
        val sent = stub.seen("POST", "/api/photos/21/xmp").map { it.path }
        assertTrue(sent.all { "version=1" in it && "checkout_ts=" in it && "agent=" in it })
    }

    @Test fun anEditInTheDarkroomOfAVersionIsWrittenToItsOwnFileAndSent() = runBlocking {
        agent.take(o, 21)
        put(2, "v2")
        agent.versionMade(o, 21, 2)
        editor.openRaw = agent.work.raw(agent.record(o, 21)!!)
        editor.unsavedChanges = true
        // the fake writes the file it is asked for: the app's editor writes the open version's own file
        agent.sync(o, 21)
        assertTrue(editor.writes.isNotEmpty())
        editor.openRaw = null
    }

    @Test fun a409RenamesTheLocalFileToNextAndPostsAgain() = runBlocking {
        agent.take(o, 21)
        // another device made version 1 meanwhile
        stub.photos[21]!!.versions[1] = StubLpl.Ver(StubLpl.lplXmp(9), System.currentTimeMillis() / 1000.0 + 60)
        put(1, "mine")
        agent.versionMade(o, 21, 1)
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        val ph = stub.photos[21]!!
        assertEquals("the other device's version 1 is untouched", true, !ph.versions[1]!!.xmp.decodeToString().contains("mine"))
        assertTrue("mine is version 2 on the server", ph.versions[2]!!.xmp.decodeToString().contains("mine"))
        assertFalse(xmp(1).exists())
        assertTrue(xmp(2).readText().contains("mine"))
        assertEquals(listOf(2), agent.record(o, 21)!!.versions.map { it.n })
        assertEquals(409, stub.seen("POST", "/api/photos/21/xmp").first { "version=1" in it.path }.status)
        // never reuses 1 or 2
        assertTrue(VersionFiles.next(dir(), "IMG_01.RAF") >= 3)
    }

    @Test fun a409WaitsWhileThePhotoIsOpenInTheDarkroom() = runBlocking {
        agent.take(o, 21)
        stub.photos[21]!!.versions[1] = StubLpl.Ver(StubLpl.lplXmp(9), System.currentTimeMillis() / 1000.0 + 60)
        put(1, "mine")
        agent.versionMade(o, 21, 1)
        editor.openRaw = agent.work.raw(agent.record(o, 21)!!)
        assertEquals(SyncOutcome.WAITING, agent.sync(o, 21))
        assertTrue("its file keeps the number the darkroom knows", xmp(1).isFile)
        editor.openRaw = null
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        assertTrue(stub.photos[21]!!.versions.containsKey(2))
    }

    @Test fun deletingAVersionDeletesItOnLplAndNeverBringsItBack() = runBlocking {
        agent.take(o, 21)
        put(1); agent.versionMade(o, 21, 1)
        agent.sync(o, 21)
        assertTrue(agent.deleteVersion(o, 21, 1))
        assertFalse(xmp(1).exists())
        assertEquals(listOf(1), agent.record(o, 21)!!.deletedVersions)
        agent.sync(o, 21)
        assertFalse(stub.photos[21]!!.versions.containsKey(1))
        assertEquals(emptyList<Int>(), agent.record(o, 21)!!.deletedVersions)
        assertEquals("the number is not used again", 2, VersionFiles.next(dir(), "IMG_01.RAF"))
        // version 0 is refused
        assertFalse(agent.deleteVersion(o, 21, 0))
    }

    @Test fun aVersionNeverSentIsJustRemovedWithoutACallToLpl() = runBlocking {
        agent.take(o, 21)
        put(1); agent.versionMade(o, 21, 1)
        assertTrue(agent.deleteVersion(o, 21, 1))
        assertEquals(emptyList<Int>(), agent.record(o, 21)!!.deletedVersions)
        assertTrue(stub.seen("DELETE", "/api/").isEmpty())
    }

    @Test fun anUnsyncedLocalVersionIsNeverReplacedByTheServers() = runBlocking {
        val ph = stub.photos[21]!!
        ph.versions[1] = StubLpl.Ver(StubLpl.lplXmp(1), 1.0)
        agent.take(o, 21)
        put(1, "local edit")
        stub.r17 = true
        // LPL changed it too: the local edit wins until it is sent (then LPL's rule applies)
        ph.versions[1]!!.xmp = StubLpl.lplXmp(5)
        agent.take(o, 21)
        assertTrue(xmp(1).readText().contains("local edit"))
    }

    @Test fun aServerWithoutTheCapabilityKeepsLocalVersionsLocal() = runBlocking {
        stub.r17 = false
        val r = agent.take(o, 21)
        assertFalse(r.versionsCap)
        assertFalse("no duplicate while connected to such a server", agent.canMakeVersion(o, 21))
        put(1, "local"); agent.versionMade(o, 21, 1)
        assertFalse("a local version is not an unsynced edit", agent.isDirty(agent.record(o, 21)!!))
        assertEquals(SyncOutcome.SYNCED, agent.sync(o, 21))
        assertTrue(stub.seen("POST", "/api/photos/21/xmp").none { "version" in it.path })
        assertEquals("version 0 is exactly as before", 0, stub.photos[21]!!.xmpPosts)
        assertTrue(xmp(1).isFile)
    }

    @Test fun withAServerThatKnowsVersionsADuplicateIsOffered() = runBlocking {
        agent.take(o, 21)
        assertTrue(agent.canMakeVersion(o, 21))
        assertTrue(agent.versionsSynced(o, 21))
    }

    @Test fun versionStateSurvivesARestart() = runBlocking {
        agent.take(o, 21)
        put(1); agent.versionMade(o, 21, 1); agent.sync(o, 21)
        assertTrue(agent.deleteVersion(o, 21, 1))
        val again = newAgent()
        val r = again.record(o, 21)!!
        assertTrue(r.versionsCap)
        assertEquals(listOf(1), r.deletedVersions)
    }

    @Test fun theHeartbeatNamesTheOpenVersion() = runBlocking {
        agent.take(o, 21)
        val line = agent.state(o).toJson().toString()
        assertFalse("no version field for the photo's own edit", line.substringBefore("darktable_running").contains("\"version\":"))
        val ed = object : EditorPort by editor { override fun openVersion(raw: File) = 2; override fun isOpen(raw: File) = true; override fun darkroomOpen() = true }
        val a = LplAgent(servers, agent.work, ed, { "t" })
        a.javaClass // the second agent reads the same folder
        assertTrue(a.state(o).toJson().toString().contains("\"version\":2"))
    }

    // ------------------------------------------------------------------ the R1 link

    @Test fun theLinkMayNameTheVersionToOpenFirst() {
        fun parse(extra: String) = HandOff.parse("lpldt://edit?server=" + java.net.URLEncoder.encode("http://192.168.1.2:8000", "UTF-8") + "&ids=21,22&start=21" + extra)
        assertNull((parse("") as HandOffResult.Ok).handOff.version)
        assertEquals(2, (parse("&version=2") as HandOffResult.Ok).handOff.version)
        assertEquals(0, (parse("&version=0") as HandOffResult.Ok).handOff.version)
        assertTrue(parse("&version=x") is HandOffResult.Invalid)
        assertTrue(parse("&version=1&version=2") is HandOffResult.Invalid)
        assertTrue(parse("&version=12345") is HandOffResult.Invalid)
    }
}

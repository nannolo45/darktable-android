// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The client and the agent against a real LightPhotoLibrary server (brief P2e item 9, second point).
 * Runs only when a server is given; otherwise it is reported as skipped:
 *
 *     gradle :lpl:test --tests '*RealServerRun*' -Pdta.lplUrl=http://127.0.0.1:18790 \
 *         -Pdta.lplPassword=<the test server's password> -Pdta.lplLog=<file>
 *
 * The server must be a throwaway instance on test photos: the run changes a rating, posts XMPs and
 * previews and creates a job. Every call is written to the log with its result; the password never is.
 * Besides the agent calls it uses two calls of LPL's web interface to set up cases, marked "(setup)":
 * `POST /api/meta` (a rating changed in LPL after the checkout, so the XMP post must merge) and
 * `POST /api/jobs` / `GET /api/jobs` (a job addressed to this device, and its state afterwards).
 */
class RealServerRun {
    @get:Rule val tmp = TemporaryFolder()
    private val url = System.getProperty("dta.lplUrl")
    private val password = System.getProperty("dta.lplPassword")?.takeIf { it.isNotEmpty() }
    private val logFile = System.getProperty("dta.lplLog")?.let { File(it) }
    private val log = ArrayList<String>()

    private fun note(s: String) {
        val line = s.let { if (password != null) it.replace(password, "<password>") else it }
        log += line
        println(line)
    }

    private fun setup(method: String, path: String, body: String?): String {
        val c = URL(url + path).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        password?.let { c.setRequestProperty("x-lpl-token", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() } ?: ""
        note("(setup) $method $path -> $code")
        return text
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    @Test fun everyAgentCallAgainstTheRealServer() {
        assumeTrue("no real LPL server given (-Pdta.lplUrl)", url != null)
        try {
            run()
        } catch (e: Throwable) {
            note("FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            logFile?.let { f -> f.parentFile?.mkdirs(); f.writeText(log.joinToString("\n") + "\n") }
        }
    }

    private fun run() = runBlocking {
        val origin = ServerOrigin.parseExact(url!!)!!
        val calls = ArrayList<String>()
        fun client(token: String?) = LplClient(origin, { token }, log = { calls += it; note("  call: $it") })
        note("server: ${origin.url} (local test instance), password set: ${password != null}")

        // --- adding the server
        val noPw = client(null).check(null)
        note("check without password -> $noPw")
        if (password != null) {
            assertEquals(ServerCheckResult.Lpl(true, false, null), noPw)
            val wrong = client("wrong-password-for-test").check("wrong-password-for-test")
            note("check with a wrong password -> $wrong")
            assertEquals(ServerCheckResult.Lpl(true, false, null), wrong)
        }
        val good = client(password).check(password)
        note("check with the right password -> $good")
        assertTrue(good is ServerCheckResult.Lpl && good.passwordOk)
        val c = client(password)

        // --- wrong password on an agent call
        if (password != null) {
            try { client("wrong-password-for-test").checkout(1); error("expected 401") } catch (e: LplException) {
                note("checkout with a wrong password -> ${e.failure} (user sees: \"${e.userText}\")")
                assertEquals(LplFailure.UNAUTHORIZED, e.failure)
            }
        }

        // --- find the photos (checkout of ids 1..30, as the app does for filmstrip names)
        val photos = (1L..30L).mapNotNull { id -> try { c.checkout(id) } catch (e: LplException) { null } }
        note("photos found by checkout: " + photos.joinToString { "${it.id}=${it.filename} (${it.size} bytes)" })
        val rawId = System.getProperty("dta.lplRawId")?.toLongOrNull()
            ?: photos.firstOrNull { it.filename.lowercase().endsWith(".cr2") }?.id ?: photos.first().id
        val co = c.checkout(rawId)
        note("checkout $rawId -> filename ${co.filename}, size ${co.size}, mtime ${co.mtime}, server_time ${co.serverTime}")

        // --- photo gone
        try { c.checkout(999_999); error("expected 404") } catch (e: LplException) {
            note("checkout 999999 -> ${e.failure} (user sees: \"${e.userText}\")")
            assertEquals(LplFailure.NOT_FOUND, e.failure)
        }

        // --- original: complete, then interrupted and resumed
        val full = File(tmp.root, "full.part")
        c.downloadOriginal(rawId, full, co.size)
        note("original complete: ${full.length()} bytes, sha256 ${sha(full.readBytes())}")
        assertEquals(co.size, full.length())
        val part = File(tmp.root, "resume.part")
        var stopAt = co.size / 3
        try {
            c.downloadOriginal(rawId, part, co.size, cancelled = { part.isFile && part.length() >= stopAt })
        } catch (e: LplException) {
            note("download stopped on purpose at ${part.length()} bytes -> ${e.failure}")
        }
        val before = part.length()
        c.downloadOriginal(rawId, part, co.size)
        note("resumed from $before: now ${part.length()} bytes, sha256 ${sha(part.readBytes())}, same as the complete download: ${part.readBytes().contentEquals(full.readBytes())}")
        assertArrayEquals(full.readBytes(), part.readBytes())
        assertTrue("a Range request was made", calls.any { it.contains("(Range from $before)") && it.contains("-> 206") })
        // a part file longer than... the whole file already on disk: 416 path
        val done = File(tmp.root, "done.part"); full.copyTo(done)
        c.downloadOriginal(rawId, done, -1)
        note("download with the whole file already on disk and no size known -> ${done.length()} bytes")

        // --- XMP
        val x0 = c.getXmp(rawId)
        note("xmp get -> ${x0.size} bytes")
        val edited = x0.decodeToString().replace("</rdf:RDF>", "<!-- p2e real-server run edit --></rdf:RDF>").toByteArray()
        val a1 = c.postXmp(rawId, edited, co.serverTime)
        note("xmp post with checkout_ts=${LplClient.formatTs(co.serverTime)} -> merged=${a1.merged}, ${a1.xmp.size} bytes back")
        assertFalse(a1.merged)

        // rating changed in LPL after the checkout: the next post must come back merged
        Thread.sleep(1100)
        setup("POST", "/api/meta", """{"ids":[$rawId],"rating":4}""")
        val a2 = c.postXmp(rawId, edited, co.serverTime)
        val rating = Regex("xmp:Rating=\"(-?\\d+)\"|<xmp:Rating>(-?\\d+)<").find(a2.xmp.decodeToString())?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        note("xmp post after LPL changed the rating -> merged=${a2.merged}, rating in the answer: $rating")
        assertTrue(a2.merged)
        assertEquals("4", rating)
        // without a checkout time LPL never merges
        val a3 = c.postXmp(rawId, edited, 0.0)
        note("xmp post with checkout_ts=0 -> merged=${a3.merged}")

        // invalid XMP
        try { c.postXmp(rawId, "not xml".toByteArray(), co.serverTime); error("expected 400") } catch (e: LplException) {
            note("xmp post of something that is not an XMP -> ${e.failure} ${e.status}")
        }

        // --- preview
        val jpeg = tinyJpeg()
        c.postPreview(rawId, jpeg)
        note("preview post (${jpeg.size} bytes JPEG) -> ok")
        try { c.postPreview(rawId, byteArrayOf(1, 2, 3)); error("expected 400") } catch (e: LplException) {
            note("preview post of something that is not an image -> ${e.failure} ${e.status}")
        }

        // --- heartbeat and jobs
        val installId = "android-00000000-0000-4000-8000-000000000e2e"
        val state = AgentState(listOf(WorkingSetLine(rawId, co.filename, PhotoStatus.DIRTY, co.serverTime, 0.0, "")), false, true, "p2e-run / darktable test", "")
        val h1 = c.heartbeat(installId, "Phone", state)
        note("heartbeat -> ${h1.jobs.size} jobs, server_time ${h1.serverTime}")
        setup("POST", "/api/jobs", """{"kind":"edit","payload":{"ids":[$rawId]}}""")
        val h2 = c.heartbeat(installId, "Phone", state)
        note("heartbeat after an unaddressed job was created -> ${h2.jobs.size} jobs (an android agent only takes jobs addressed to it)")
        assertTrue(h2.jobs.isEmpty())
        val created = setup("POST", "/api/jobs", """{"kind":"sync","agent_id":"$installId","payload":{"ids":[$rawId]}}""")
        note("  (setup) answer: $created")
        val h3 = c.heartbeat(installId, "Phone", state)
        note("heartbeat after a job addressed to this device -> jobs ${h3.jobs}")
        assertEquals(1, h3.jobs.size)
        assertEquals("sync", h3.jobs[0].kind)
        assertEquals(listOf(rawId), h3.jobs[0].ids)
        val h4 = c.heartbeat(installId, "Phone", state)
        note("next heartbeat -> ${h4.jobs.size} jobs (handed out once)")
        c.jobDone(h3.jobs[0].id, ok = true)
        val jobs = setup("GET", "/api/jobs", null)
        val st = (kotlinx.serialization.json.Json.parseToJsonElement(jobs) as kotlinx.serialization.json.JsonObject)["jobs"].arr()
            ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }?.firstOrNull { it["id"].long() == h3.jobs[0].id }?.get("status").str()
        note("job ${h3.jobs[0].id} after jobs/done -> status $st")
        assertEquals("done", st)
        val agents = setup("GET", "/api/agents", null)
        note("  LPL lists the device: name Phone ${agents.contains("\"name\":\"Phone\"") || agents.contains("\"name\": \"Phone\"")}, os android ${agents.contains("\"os\":\"android\"") || agents.contains("\"os\": \"android\"")}, dirty in its working set ${agents.contains("dirty")}")

        // --- the agent end to end: take, edit, sync (XMP then preview), with a stand-in darkroom
        val box = MemorySecretBox()
        val servers = ServerStore(File(tmp.root, "lpl"), box)
        servers.put(origin, "Phone", password)
        val editor = FakeEditor().apply { previewBytes = tinyJpeg() }
        val agent = LplAgent(servers, WorkStore(File(tmp.root, "work")), editor, { "p2e-run / darktable test" },
            clientFor = { o -> LplClient(o, { servers.password(o) }, log = { note("  agent call: $it") }) })
        val r = agent.take(origin, rawId)
        note("agent take -> ${r.filename} ${r.status}, ${agent.work.raw(r).length()} bytes, read-only ${r.readOnly}")
        editor.openRaw = agent.work.raw(r); agent.opened(origin, rawId)
        editor.edit(agent.work.xmp(r))
        editor.openRaw = null; agent.left(origin, rawId)
        note("agent after an edit -> ${agent.record(origin, rawId)!!.status}")
        val outcome = agent.sync(origin, rawId)
        val after = agent.record(origin, rawId)!!
        note("agent sync -> $outcome, status ${after.status}, preview pending ${after.previewPending}")
        assertEquals(SyncOutcome.SYNCED, outcome)
        assertFalse(after.previewPending)
        val hb = agent.heartbeat()
        note("agent heartbeat -> ${hb.size} jobs")
        note("done: every call answered as expected")
    }

    /** A real 16 x 16 JPEG (grey), made here so the run needs no image file. */
    private fun tinyJpeg(): ByteArray {
        val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }
}

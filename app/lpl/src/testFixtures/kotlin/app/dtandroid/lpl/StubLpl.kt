// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A stand-in for LightPhotoLibrary's agent calls, written for the tests (no LPL code): it answers the
 * calls of PLAN.md 4.1 the way the real server does as far as the app depends on it (written down in
 * `docs/experiments/P2e-lpl-client.md`, "LPL as the app sees it"). Plain HTTP/1.1 on 127.0.0.1, one
 * connection per request. Used by the JVM tests of `app/lpl` and by the app's instrumented tests.
 *
 * Knobs: [password] (401 without the right `x-lpl-token`), [dropOriginalAfter] (the next download of an
 * original stops after that many bytes, as a broken mobile connection would), [redirect] (every answer is a
 * redirect to another host), [stop]/[start] (server gone and back on the same port).
 */
class StubLpl(port: Int = 0) : AutoCloseable {
    class Photo(
        val id: Long,
        val filename: String,
        @Volatile var bytes: ByteArray,
        @Volatile var mtime: Double = 1_790_000_000.0,
        /** when LPL's own rating/labels/tags last changed (the merge rule compares it with `checkout_ts`) */
        @Volatile var metaMtime: Double = 0.0,
        @Volatile var rating: Int = 0,
        @Volatile var xmp: ByteArray? = null,
        @Volatile var preview: ByteArray? = null,
        @Volatile var previewPosts: Int = 0,
        @Volatile var xmpPosts: Int = 0,
        @Volatile var lastCheckoutTs: String? = null,
        /** R15: the `agent` of the last XMP post */
        @Volatile var lastAgent: String? = null,
        /** R15: the edit events, newest first (only with [StubLpl.r15]) */
        val edits: MutableList<String> = Collections.synchronizedList(ArrayList()),
    ) {
        /** R17: the versions n >= 1 (only with [StubLpl.r17]) */
        val versions: MutableMap<Int, Ver> = Collections.synchronizedMap(java.util.TreeMap())
        /** R18 (P3i): the exports received (only with [StubLpl.r18]) */
        val exports: MutableList<Exp> = Collections.synchronizedList(ArrayList())
        /** R17: what the server did with versions, in order (`post 2`, `delete 2`, `preview 2`) */
        val versionLog: MutableList<String> = Collections.synchronizedList(ArrayList())
    }

    /** R18: one export as the server stored it. */
    class Exp(val id: Long, val version: Int, val name: String, val agent: String?, val bytes: ByteArray)

    /** R17: one version on the server: its XMP, when the server first got it, and its preview. */
    class Ver(@Volatile var xmp: ByteArray, val created: Double, @Volatile var name: String = "", @Volatile var preview: ByteArray? = null, @Volatile var posts: Int = 0)

    class JobRec(val id: Long, val agentId: String?, val kind: String, val ids: List<Long>, val all: Boolean = false, val open: Boolean? = null) {
        @Volatile var status = "queued"
        /** brief P2k: the body of the `done` call that ended it */
        @Volatile var doneBody: String? = null
    }

    /** One request as the stub saw it: method, path with query, status, and the headers the tests check. */
    data class Seen(val method: String, val path: String, val status: Int, val token: String?, val range: String?, val ifRange: String?, val bodyBytes: Int)

    @Volatile var password: String = ""
    @Volatile var dropOriginalAfter: Long = -1
    @Volatile var redirect: Boolean = false
    /** every answer waits this long first (a slow network), in ms */
    @Volatile var delayMs: Long = 0
    /** how many of the next requests are dropped without an answer (connection closed) */
    @Volatile var dropNext: Int = 0
    /** brief P2f: answer as a server with R15 (edit events; `GET /api/photos/{id}` with `last_edit`); off = an older server */
    @Volatile var r15: Boolean = false
    /** brief P2f: answer `GET /api/auth/token` (R14); off = 404 as an older server */
    @Volatile var r14: Boolean = false

    /** R17 (P3f): answer as a server with versions (`versions` in `/checkout`, the `version` parameter, 409 `version_exists`); off = an older server */
    @Volatile var r17: Boolean = false
    /** R17: the version numbers each agent was told at its last checkout (`<agent> <photo>` to numbers), as LPL notes them for 409 */
    val checkoutNs: MutableMap<String, Set<Int>> = Collections.synchronizedMap(HashMap())

    /** R18 (P3i): receive exports (`POST /api/photos/{id}/exports`) and answer `server_caps` to a device that announces caps; off = an older server */
    @Volatile var r18: Boolean = false
    private val exportSeq = AtomicLong(0)

    /** brief P3l (R11): answer `/api/shared...` from [shared] and list `shared` in `server_caps`; off = an older server */
    @Volatile var r11: Boolean = false
    /** device names of agents that never sent a heartbeat (a PC played by a test) */
    val agentNames: MutableMap<String, String> = Collections.synchronizedMap(HashMap())
    val shared = StubShared { id ->
        agentNames[id] ?: heartbeats[id]?.let { Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) } ?: id
    }

    /** brief P2k: answer as a server with batches (R17): a `cancel` in every heartbeat answer; off = an older server, no `cancel` */
    @Volatile var batch: Boolean = false
    /** brief P2k: what the next answers ask the device to stop (the whole list every time) */
    val cancelJobs: MutableSet<Long> = Collections.synchronizedSet(LinkedHashSet())
    val cancelPhotos: MutableSet<Long> = Collections.synchronizedSet(LinkedHashSet())
    /** brief P2k: an original stops after [stallAfter] bytes (not for a resumed one) until the gate is opened (or 10 s) */
    @Volatile var stallAfter: Long = -1
    /** only for this photo (-1 = any) */
    @Volatile var stallPhoto: Long = -1
    @Volatile var stallGate: java.util.concurrent.CountDownLatch? = null
    /** brief P2k: every heartbeat body in order, with the time it came (ms) */
    val heartbeatLog: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** brief P2k: what the server saw in order: `hb`, or `done <job> <status>` */
    val events: MutableList<String> = Collections.synchronizedList(ArrayList())

    val photos = Collections.synchronizedMap(LinkedHashMap<Long, Photo>())
    val jobs = Collections.synchronizedList(ArrayList<JobRec>())
    val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())
    /** the last heartbeat body per agent id (JSON text) */
    val heartbeats = Collections.synchronizedMap(LinkedHashMap<String, String>())
    val heartbeatCount = AtomicLong()
    private val jobSeq = AtomicLong(100)

    @Volatile private var socket: ServerSocket? = null
    /** false between [stop] and [start]: a connection that still gets through is closed without an answer */
    @Volatile private var running = false
    var port: Int = port
        private set

    val origin: ServerOrigin get() = ServerOrigin("http", "127.0.0.1", port)
    val url: String get() = origin.url

    init { start() }

    fun start() {
        val s = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        s.reuseAddress = true
        port = s.localPort
        socket = s
        running = true
        thread(isDaemon = true, name = "stub-lpl-$port") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: SocketException) { break }
                if (s.isClosed) { runCatching { c.close() }; break }
                thread(isDaemon = true) { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
    }

    /** The server is gone: connections are refused until [start]. */
    fun stop() { running = false; socket?.close(); socket = null }

    override fun close() = stop()

    fun add(p: Photo): Photo { photos[p.id] = p; return p }

    fun queueJob(agentId: String?, kind: String, ids: List<Long>, all: Boolean = false, open: Boolean? = null): JobRec =
        JobRec(jobSeq.incrementAndGet(), agentId, kind, ids, all, open).also { jobs += it }

    fun seen(method: String, prefix: String) = synchronized(seen) { seen.filter { it.method == method && it.path.startsWith(prefix) } }

    // ------------------------------------------------------------------ HTTP

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private fun serve(c: Socket) {
        c.soTimeout = 15_000
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val req = read(input) ?: return
        if (!running) return
        if (delayMs > 0) Thread.sleep(delayMs)
        if (dropNext > 0) { dropNext--; return }
        val token = req.headers["x-lpl-token"]
        fun record(status: Int) {
            seen += Seen(req.method, req.path + (if (req.query.isEmpty()) "" else "?" + req.query.entries.joinToString("&") { "${it.key}=${it.value}" }),
                status, token, req.headers["range"], req.headers["if-range"], req.body.size)
        }
        if (redirect) {
            record(307)
            respond(out, 307, "text/plain", ByteArray(0), mapOf("Location" to "http://192.0.2.99:9/elsewhere"))
            return
        }
        val p = req.path
        if (password.isNotEmpty() && p.startsWith("/api/") && p != "/api/auth" && p != "/api/login" && token != password) {
            record(401); json(out, 401, """{"detail":"login required"}"""); return
        }
        val m = Regex("^/api/photos/(\\d+)/(checkout|original|xmp|preview)$").find(p)
        val detail = Regex("^/api/photos/(\\d+)(/edits)?$").find(p)
        when {
            r14 && req.method == "GET" && p == "/api/auth/token" -> { record(200); json(out, 200, """{"required":${password.isNotEmpty()},"ok":true}""") }
            r15 && req.method == "GET" && detail != null -> {
                val ph = photos[detail.groupValues[1].toLong()]
                if (ph == null) { record(404); json(out, 404, """{"detail":"photo not found"}"""); return }
                record(200)
                if (detail.groupValues[2].isNotEmpty()) json(out, 200, """{"edits":[${synchronized(ph.edits) { ph.edits.joinToString(",") }}]}""")
                else json(out, 200, """{"id":${ph.id},"filename":"${ph.filename}","last_edit":${synchronized(ph.edits) { ph.edits.firstOrNull() } ?: "null"},"extra":{"ignored":true}}""")
            }
            req.method == "GET" && p == "/api/auth" -> { record(200); json(out, 200, """{"required":${password.isNotEmpty()},"ok":false}""") }
            req.method == "GET" && p == "/api/status" -> { record(200); json(out, 200, """{"photos":${photos.size},"version":"stub","mode":"nas","server_time":${now()}}""") }
            req.method == "POST" && p == "/api/agents/heartbeat" -> { record(200); heartbeat(out, req) }
            r18 && req.method == "POST" && Regex("^/api/photos/\\d+/exports$").matches(p) -> {
                val ph = photos[p.split('/')[3].toLong()]
                if (ph == null) { record(404); json(out, 404, """{"detail":"photo not found"}"""); return }
                val name = (req.query["name"] ?: "").substringAfterLast('/')
                val want = req.query["size"]?.toLongOrNull()
                if (!Regex("(?i).+\\.(jpe?g|png|tiff?|webp)$").matches(name) || req.body.isEmpty()) { record(400); json(out, 400, """{"detail":"bad name or empty"}"""); return }
                if (want != null && want != req.body.size.toLong()) { record(400); json(out, 400, """{"detail":"size mismatch"}"""); return }
                val e = Exp(exportSeq.incrementAndGet(), req.query["version"]?.toIntOrNull() ?: 0, name, req.query["agent"], req.body)
                ph.exports += e
                record(200)
                json(out, 200, """{"export_id":${e.id},"path":"exports/2024/$name"}""")
            }
            req.method == "POST" && Regex("^/api/jobs/\\d+/done$").matches(p) -> {
                val id = p.split('/')[3].toLong()
                val st = Regex("\"status\"\\s*:\\s*\"(\\w+)\"").find(req.body.decodeToString())?.groupValues?.get(1) ?: "done"
                synchronized(jobs) { jobs.firstOrNull { it.id == id } }?.let { it.status = st; it.doneBody = req.body.decodeToString() }
                events += "done $id $st"
                record(200); json(out, 200, """{"ok":true}""")
            }
            m != null -> {
                val ph = photos[m.groupValues[1].toLong()]
                if (ph == null) { record(404); json(out, 404, """{"detail":"photo not found"}"""); return }
                when (m.groupValues[2] to req.method) {
                    "checkout" to "GET" -> {
                        record(200)
                        json(out, 200, """{"id":${ph.id},"relpath":"2024/${ph.filename}","filename":"${ph.filename}","size":${ph.bytes.size},"mtime":${ph.mtime},"meta_mtime":${ph.metaMtime},"server_time":${now()}${versionsJson(ph, req.query["agent"])}}""")
                    }
                    "original" to "GET" -> original(out, req, ph, ::record)
                    "xmp" to "GET" -> {
                        val v = req.query["version"]?.toIntOrNull() ?: 0
                        if (r17 && v > 0) {
                            val ver = ph.versions[v]
                            if (ver == null) { record(404); json(out, 404, """{"detail":"version not found"}"""); return }
                            record(200); respond(out, 200, "application/rdf+xml", ver.xmp)
                        } else { record(200); respond(out, 200, "application/rdf+xml", ph.xmp ?: lplXmp(ph.rating)) }
                    }
                    "xmp" to "DELETE" -> {
                        val v = req.query["version"]?.toIntOrNull() ?: 0
                        if (!r17 || v <= 0) { record(400); json(out, 400, """{"detail":"version 0 is not deleted"}"""); return }
                        if (ph.versions.remove(v) == null) { record(404); json(out, 404, """{"detail":"version not found"}"""); return }
                        ph.versionLog += "delete $v"
                        record(200); json(out, 200, """{"ok":true}""")
                    }
                    "xmp" to "POST" -> {
                        val body = req.body
                        val v = if (r17) req.query["version"]?.toIntOrNull() ?: 0 else 0
                        if (v > 0) {
                            if (!body.decodeToString().contains("x:xmpmeta")) { record(400); json(out, 400, """{"detail":"not a valid XMP file"}"""); return }
                            val ts = req.query["checkout_ts"]?.toDoubleOrNull() ?: 0.0
                            val have = ph.versions[v]
                            // as LPL: the number exists and the poster was not told about it at its last checkout
                            val agent = req.query["agent"]?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                            if (have != null && v !in (checkoutNs["$agent ${ph.id}"] ?: emptySet())) {
                                val next = (ph.versions.keys.maxOrNull() ?: 0) + 1
                                record(409); json(out, 409, """{"error":"version_exists","next":$next}"""); return
                            }
                            if (have != null) { have.xmp = body; have.posts++ } else ph.versions[v] = Ver(body, now(), posts = 1)
                            ph.versionLog += "post $v"
                            record(200)
                            respond(out, 200, "application/rdf+xml", body, mapOf("X-LPL-Merged" to "0"))
                            return
                        }
                        if (!body.decodeToString().contains("x:xmpmeta")) { record(400); json(out, 400, """{"detail":"not a valid XMP file"}"""); return }
                        val ts = req.query["checkout_ts"]?.toDoubleOrNull() ?: 0.0
                        ph.lastCheckoutTs = req.query["checkout_ts"]
                        ph.lastAgent = req.query["agent"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        if (r15) {
                            val agent = ph.lastAgent
                            val dev = agent?.let { a -> heartbeats[a]?.let { Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) } }
                            ph.edits.add(0, """{"id":${ph.edits.size + 1},"photo_id":${ph.id},"time":${now()},"source":"agent","agent_id":${agent?.let { "\"$it\"" } ?: "null"},""" +
                                """"device":${dev?.let { "\"$it\"" } ?: "null"},"device_os":"android","modules":[],"changes":null,"summary":"exposure"}""")
                        }
                        val merged = ph.metaMtime > ts && ts > 0
                        val stored = if (merged) withRating(body, ph.rating) else body
                        ph.xmp = stored
                        ph.xmpPosts++
                        record(200)
                        respond(out, 200, "application/rdf+xml", stored, mapOf("X-LPL-Merged" to if (merged) "1" else "0"))
                    }
                    "preview" to "POST" -> {
                        val b = req.body
                        val pv = if (r17) req.query["version"]?.toIntOrNull() ?: 0 else 0
                        if (pv > 0) {
                            val ver = ph.versions[pv]
                            if (ver == null) { record(404); json(out, 404, """{"detail":"version not found"}"""); return }
                            ver.preview = b; ph.versionLog += "preview $pv"
                            record(200); json(out, 200, """{"ok":true}"""); return
                        }
                        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) { record(400); json(out, 400, """{"detail":"bad image"}"""); return }
                        ph.preview = b; ph.previewPosts++
                        record(200); json(out, 200, """{"ok":true}""")
                    }
                    else -> { record(405); json(out, 405, """{"detail":"Method Not Allowed"}""") }
                }
            }
            r11 && p.startsWith("/api/shared") -> sharedRoute(out, req, ::record)
            else -> { record(404); json(out, 404, """{"detail":"Not Found"}""") }
        }
    }

    private fun sharedRoute(out: OutputStream, req: Req, record: (Int) -> Unit) {
        val p = req.path
        val agent = req.query["agent"] ?: ""
        fun body() = kotlinx.serialization.json.Json.parseToJsonElement(req.body.decodeToString()) as kotlinx.serialization.json.JsonObject
        fun answer(r: Pair<Int, kotlinx.serialization.json.JsonObject>) { record(r.first); json(out, r.first, r.second.toString()) }
        val one = Regex("^/api/shared/(\\d+)$").find(p)
        val res = Regex("^/api/shared/(\\d+)/resolve$").find(p)
        when {
            req.method == "GET" && p == "/api/shared" -> answer(200 to shared.list(agent, req.query["since"]?.toLongOrNull() ?: 0))
            req.method == "PUT" && p == "/api/shared/state" -> { shared.state(agent, body()); answer(200 to kotlinx.serialization.json.JsonObject(emptyMap())) }
            req.method == "PUT" && p == "/api/shared" -> answer(shared.put(agent, body()))
            req.method == "GET" && one != null -> shared.get(one.groupValues[1].toLong())?.let { answer(200 to it) }
                ?: answer(404 to kotlinx.serialization.json.JsonObject(mapOf("detail" to kotlinx.serialization.json.JsonPrimitive("no such item"))))
            req.method == "DELETE" && one != null -> answer(shared.delete(agent, one.groupValues[1].toLong(), req.query["base_rev"]?.toIntOrNull() ?: -1,
                req.query["changed_at"]?.toDoubleOrNull() ?: System.currentTimeMillis() / 1000.0))
            req.method == "POST" && res != null -> {
                val b = body()
                answer(shared.resolve(res.groupValues[1].toLong(), (b["agent"] as kotlinx.serialization.json.JsonPrimitive).content,
                    (b["choice"] as kotlinx.serialization.json.JsonPrimitive).content))
            }
            else -> { record(404); json(out, 404, """{"detail":"Not Found"}""") }
        }
    }

    private fun heartbeat(out: OutputStream, req: Req) {
        val body = req.body.decodeToString()
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
        val os = Regex("\"os\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        heartbeats[id] = body
        heartbeatLog += body
        events += "hb"
        heartbeatCount.incrementAndGet()
        // as LPL: an android agent only takes jobs addressed to it
        val mine = synchronized(jobs) {
            jobs.filter { it.status == "queued" && (it.agentId == id || (os != "android" && it.agentId == null)) }.onEach { it.status = "taken" }
        }
        val js = mine.joinToString(",") { j ->
            val payload = if (j.all) """{"all":true}""" else """{"ids":[${j.ids.joinToString(",")}]${j.open?.let { ",\"open\":$it" } ?: ""}}"""
            """{"id":${j.id},"kind":"${j.kind}","payload":$payload}"""
        }
        val cancel = if (batch) ""","cancel":{"jobs":[${synchronized(cancelJobs) { cancelJobs.joinToString(",") }}],"photos":[${synchronized(cancelPhotos) { cancelPhotos.joinToString(",") }}]}""" else ""
        // R18: only to a device that announced caps; `exports` only when the stub is a server with R18
        val announced = Regex("\"caps\"\\s*:\\s*\\[[^\\]]*\\]").containsMatchIn(body)
        val serverCaps = if (announced && (r18 || r11)) ""","server_caps":[${listOfNotNull("\"batch\"".takeIf { batch }, "\"versions\"".takeIf { r17 }, "\"exports\"".takeIf { r18 }, "\"shared\"".takeIf { r11 }).joinToString(",")}]""" else ""
        json(out, 200, """{"jobs":[$js],"server_time":${now()}$cancel$serverCaps}""")
    }

    private fun original(out: OutputStream, req: Req, ph: Photo, record: (Int) -> Unit) {
        val all = ph.bytes
        val size = all.size.toLong()
        val etag = "\"${ph.mtime.toLong()}-$size\""
        val range = req.headers["range"]
        var start = 0L
        var end = size - 1
        var partial = false
        if (range != null && (req.headers["if-range"] == null || req.headers["if-range"] == etag)) {
            val r = Regex("^bytes=(\\d*)-(\\d*)$").find(range.trim())
            if (r != null) {
                val a = r.groupValues[1]; val b = r.groupValues[2]
                if (a.isEmpty()) { start = maxOf(0, size - b.toLong()) } else { start = a.toLong(); if (b.isNotEmpty()) end = minOf(end, b.toLong()) }
                if (start >= size) {
                    record(416)
                    respond(out, 416, "text/plain", ByteArray(0), mapOf("Content-Range" to "bytes */$size"))
                    return
                }
                partial = true
            }
        }
        val slice = all.copyOfRange(start.toInt(), (end + 1).toInt())
        val headers = linkedMapOf("Accept-Ranges" to "bytes", "ETag" to etag)
        if (partial) headers["Content-Range"] = "bytes $start-$end/$size"
        record(if (partial) 206 else 200)
        val drop = dropOriginalAfter
        if (drop >= 0) {
            dropOriginalAfter = -1
            // announce the whole length, send part of it, close: a connection that breaks off
            head(out, if (partial) 206 else 200, "application/octet-stream", slice.size.toLong(), headers)
            out.write(slice, 0, minOf(drop.toInt(), slice.size))
            out.flush()
            return
        }
        if (stallAfter in 0 until slice.size && !partial && (stallPhoto < 0 || stallPhoto == ph.id)) {
            head(out, 200, "application/octet-stream", slice.size.toLong(), headers)
            val n = stallAfter.toInt()
            stallAfter = -1
            out.write(slice, 0, n)
            out.flush()
            stallGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            out.write(slice, n, slice.size - n)
            out.flush()
            return
        }
        respond(out, if (partial) 206 else 200, "application/octet-stream", slice, headers)
    }

    // ------------------------------------------------------------------ helpers

    private fun read(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
            if (head.size() > 65536) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.first().split(' ')
        val target = parts[1]
        val headers = lines.drop(1).associate { l -> l.substringBefore(':').trim().lowercase() to l.substringAfter(':').trim() }
        val n = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(n)
        var off = 0
        while (off < n) { val k = input.read(body, off, n - off); if (k < 0) break; off += k }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return Req(parts[0], path, query, headers, body)
    }

    private fun head(out: OutputStream, status: Int, type: String, length: Long, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        head(out, status, type, body.size.toLong(), headers)
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, status: Int, text: String) = respond(out, status, "application/json", text.toByteArray())

    private fun now() = System.currentTimeMillis() / 1000.0

    /** R17: `,"versions":[...]` for a checkout (version 0 first), empty for an older server */
    private fun versionsJson(ph: Photo, agent: String?): String {
        if (!r17 || agent == null) return ""
        val vs = synchronized(ph.versions) { ph.versions.entries.toList() }
        checkoutNs["${java.net.URLDecoder.decode(agent, "UTF-8")} ${ph.id}"] = setOf(0) + vs.map { it.key }
        val items = listOf("""{"n":0,"name":"","mtime":${ph.mtime},"size":${(ph.xmp ?: lplXmp(ph.rating)).size}}""") +
            vs.map { """{"n":${it.key},"name":"${it.value.name}","mtime":${it.value.created},"size":${it.value.xmp.size},"has_file":true}""" }
        return ""","versions":[${items.joinToString(",")}]"""
    }

    companion object {
        /** The XMP LPL makes from its own metadata when the NAS has no sidecar (rating only, here). */
        fun lplXmp(rating: Int): ByteArray = """<?xml version="1.0" encoding="UTF-8"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating"/>
</rdf:RDF></x:xmpmeta>
""".toByteArray()

        /** LPL's merge as far as the tests need it: its own rating replaces the editor's. */
        fun withRating(xmp: ByteArray, rating: Int): ByteArray {
            val t = xmp.decodeToString()
            val r = Regex("xmp:Rating=\"-?\\d+\"")
            return (if (r.containsMatchIn(t)) t.replace(r, "xmp:Rating=\"$rating\"")
                    else t.replaceFirst("rdf:about=\"\"", "rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:Rating=\"$rating\"")).toByteArray()
        }
    }
}

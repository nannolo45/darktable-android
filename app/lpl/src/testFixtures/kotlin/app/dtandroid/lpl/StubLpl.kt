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
    )

    class JobRec(val id: Long, val agentId: String?, val kind: String, val ids: List<Long>, val all: Boolean = false) {
        @Volatile var status = "queued"
    }

    /** One request as the stub saw it: method, path with query, status, and the headers the tests check. */
    data class Seen(val method: String, val path: String, val status: Int, val token: String?, val range: String?, val ifRange: String?, val bodyBytes: Int)

    @Volatile var password: String = ""
    @Volatile var dropOriginalAfter: Long = -1
    @Volatile var redirect: Boolean = false
    /** how many of the next requests are dropped without an answer (connection closed) */
    @Volatile var dropNext: Int = 0

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

    fun queueJob(agentId: String?, kind: String, ids: List<Long>, all: Boolean = false): JobRec =
        JobRec(jobSeq.incrementAndGet(), agentId, kind, ids, all).also { jobs += it }

    fun seen(method: String, prefix: String) = synchronized(seen) { seen.filter { it.method == method && it.path.startsWith(prefix) } }

    // ------------------------------------------------------------------ HTTP

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private fun serve(c: Socket) {
        c.soTimeout = 15_000
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val req = read(input) ?: return
        if (!running) return
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
        when {
            req.method == "GET" && p == "/api/auth" -> { record(200); json(out, 200, """{"required":${password.isNotEmpty()},"ok":false}""") }
            req.method == "GET" && p == "/api/status" -> { record(200); json(out, 200, """{"photos":${photos.size},"version":"stub","mode":"nas","server_time":${now()}}""") }
            req.method == "POST" && p == "/api/agents/heartbeat" -> { record(200); heartbeat(out, req) }
            req.method == "POST" && Regex("^/api/jobs/\\d+/done$").matches(p) -> {
                val id = p.split('/')[3].toLong()
                val st = Regex("\"status\"\\s*:\\s*\"(\\w+)\"").find(req.body.decodeToString())?.groupValues?.get(1) ?: "done"
                synchronized(jobs) { jobs.firstOrNull { it.id == id } }?.status = st
                record(200); json(out, 200, """{"ok":true}""")
            }
            m != null -> {
                val ph = photos[m.groupValues[1].toLong()]
                if (ph == null) { record(404); json(out, 404, """{"detail":"photo not found"}"""); return }
                when (m.groupValues[2] to req.method) {
                    "checkout" to "GET" -> {
                        record(200)
                        json(out, 200, """{"id":${ph.id},"relpath":"2024/${ph.filename}","filename":"${ph.filename}","size":${ph.bytes.size},"mtime":${ph.mtime},"meta_mtime":${ph.metaMtime},"server_time":${now()}}""")
                    }
                    "original" to "GET" -> original(out, req, ph, ::record)
                    "xmp" to "GET" -> { record(200); respond(out, 200, "application/rdf+xml", ph.xmp ?: lplXmp(ph.rating)) }
                    "xmp" to "POST" -> {
                        val body = req.body
                        if (!body.decodeToString().contains("x:xmpmeta")) { record(400); json(out, 400, """{"detail":"not a valid XMP file"}"""); return }
                        val ts = req.query["checkout_ts"]?.toDoubleOrNull() ?: 0.0
                        ph.lastCheckoutTs = req.query["checkout_ts"]
                        val merged = ph.metaMtime > ts && ts > 0
                        val stored = if (merged) withRating(body, ph.rating) else body
                        ph.xmp = stored
                        ph.xmpPosts++
                        record(200)
                        respond(out, 200, "application/rdf+xml", stored, mapOf("X-LPL-Merged" to if (merged) "1" else "0"))
                    }
                    "preview" to "POST" -> {
                        val b = req.body
                        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) { record(400); json(out, 400, """{"detail":"bad image"}"""); return }
                        ph.preview = b; ph.previewPosts++
                        record(200); json(out, 200, """{"ok":true}""")
                    }
                    else -> { record(405); json(out, 405, """{"detail":"Method Not Allowed"}""") }
                }
            }
            else -> { record(404); json(out, 404, """{"detail":"Not Found"}""") }
        }
    }

    private fun heartbeat(out: OutputStream, req: Req) {
        val body = req.body.decodeToString()
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
        val os = Regex("\"os\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        heartbeats[id] = body
        heartbeatCount.incrementAndGet()
        // as LPL: an android agent only takes jobs addressed to it
        val mine = synchronized(jobs) {
            jobs.filter { it.status == "queued" && (it.agentId == id || (os != "android" && it.agentId == null)) }.onEach { it.status = "taken" }
        }
        val js = mine.joinToString(",") { j ->
            val payload = if (j.all) """{"all":true}""" else """{"ids":[${j.ids.joinToString(",")}]}"""
            """{"id":${j.id},"kind":"${j.kind}","payload":$payload}"""
        }
        json(out, 200, """{"jobs":[$js],"server_time":${now()}}""")
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

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A minimal HTTP/1.1 server for the instrumented tests, started by the test on
 * 127.0.0.1 inside the emulator. Written here (about 100 lines) instead of
 * adding a library: the tests need GET, a few fixed pages, a request log and
 * "Connection: close", nothing more.
 */
class StubServer(port: Int = 0, private val lpl: Boolean = true) : AutoCloseable {
    private val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val origin: String get() = "http://127.0.0.1:$port"
    /** "GET /path?query" for every request, in order */
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** the User-Agent header of the last request for "/" */
    @Volatile var lastUserAgent: String? = null

    init {
        thread(isDaemon = true, name = "stub-server") {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (e: SocketException) { break }
                thread(isDaemon = true) { s.use { handle(it) } }
            }
        }
    }

    override fun close() = socket.close()

    fun count(pathAndQuery: String) = synchronized(requests) { requests.count { it == "GET $pathAndQuery" } }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
        }
    }

    private fun handle(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val c = h.indexOf(':')
            if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrElse(0) { "" }
        val target = parts.getOrElse(1) { "/" }
        requests += "$method $target"
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        if (path == "/") lastUserAgent = headers["user-agent"]
        val (status, type, body) = route(path, query)
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = s.getOutputStream()
        out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun page(title: String, body: String) = Triple(
        "200 OK", "text/html; charset=utf-8",
        """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
           |<title>$title</title></head><body style="margin:0">$body</body></html>""".trimMargin(),
    )

    private fun route(path: String, query: String): Triple<String, String, String> = when (path) {
        "/" -> page("start", "<p id=start>start page</p>")
        "/other" -> page("other", "<p>other page</p>")
        "/manifest.webmanifest" ->
            if (lpl) Triple("200 OK", "application/manifest+json", """{"name":"LightPhotoLibrary","short_name":"LPL","start_url":"/"}""")
            else Triple("404 Not Found", "text/plain", "not found")
        // one link covering the whole page, so a tap anywhere follows it
        "/tap" -> {
            val to = URLDecoder.decode(query.substringAfter("to="), "UTF-8").replace("&", "&amp;").replace("\"", "&quot;")
            page("tap", """<a id=link href="$to" style="position:fixed;left:0;top:0;width:100%;height:100%;display:block">link</a>""")
        }
        // history states pushed by the page on each tap, as LPL's sheets and views do (R9);
        // pushed on a tap because browsers skip states pushed without user activation on back
        "/history" -> page("history", """<button id=b style="position:fixed;left:0;top:0;width:100%;height:100%"
            | onclick="var n=(history.state&&history.state.n||0)+1;history.pushState({n:n},'','?s='+n)">push</button>
            |""".trimMargin())
        // a file input covering the whole page; the chosen names end up in the title
        "/upload" -> page("upload", """<input id=f type=file multiple accept="image/*,.raf"
            | style="position:fixed;left:0;top:0;width:100%;height:100%;opacity:0.01"
            | onchange="document.title='files:'+this.files.length+':'+Array.from(this.files).map(f=>f.name+'/'+f.size).join(',')">
            |""".trimMargin())
        else -> Triple("404 Not Found", "text/plain", "not found")
    }
}

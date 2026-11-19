// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One request to an LPL server. [path] starts with `/` and never carries credentials. */
class HttpRequest(
    val method: String,
    val origin: ServerOrigin,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
) {
    /** What error texts and logs show: method and path, never the token, never a credential. */
    val label: String get() = "$method ${path.substringBefore('?')}"
}

/** An answer. Header names are lower case. [body] is the error body for statuses of 400 and above. */
class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: InputStream) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/** How requests go out. The app and the tests use [UrlConnectionTransport]; a test may count or break requests. */
interface HttpTransport {
    /** Send [req] and give the answer to [handle] while the connection is open. Throws IOException when there is no answer. */
    fun <T> exchange(req: HttpRequest, handle: (HttpResponse) -> T): T
}

/**
 * Requests through `java.net.HttpURLConnection` (on Android and on the JVM). Redirects are never
 * followed, so the token header can never reach another host; a redirect answer is reported as such.
 * No caches, no cookies.
 */
class UrlConnectionTransport : HttpTransport {
    override fun <T> exchange(req: HttpRequest, handle: (HttpResponse) -> T): T {
        val c = URL(req.origin.url + req.path).openConnection() as? HttpURLConnection
            ?: throw IOException("not an http connection")
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = req.connectTimeoutMs
            c.readTimeout = req.readTimeoutMs
            c.requestMethod = req.method
            c.setRequestProperty("Accept-Encoding", "identity")
            for ((k, v) in req.headers) c.setRequestProperty(k, v)
            val body = req.body
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", req.contentType ?: "application/octet-stream")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            if (status < 0) throw IOException("no valid HTTP answer")
            val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }
            val stream = (if (status >= 400) c.errorStream else c.inputStream) ?: InputStream.nullInputStream()
            return stream.use { handle(HttpResponse(status, headers, it)) }
        } finally {
            c.disconnect()
        }
    }
}

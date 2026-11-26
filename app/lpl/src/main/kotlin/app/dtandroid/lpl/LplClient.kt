// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Timeouts and retries of the client, all explicit (brief P2e deliverable 1). */
data class ClientSettings(
    val connectTimeoutMs: Int = 5_000,
    /** JSON calls and uploads */
    val readTimeoutMs: Int = 20_000,
    /** a heartbeat that takes longer than this is skipped; the next one follows 2 s later */
    val heartbeatTimeoutMs: Int = 5_000,
    /** gap allowed between two blocks of a download before it counts as stalled (it then resumes) */
    val downloadReadTimeoutMs: Int = 30_000,
    /** further attempts after a transient failure (no answer, timeout, 502/503/504) for calls that can be repeated */
    val retries: Int = 2,
    /** wait before attempt n+1, in ms */
    val backoffMs: List<Long> = listOf(500, 2_000),
    /** a download that breaks off resumes this many times within one call */
    val downloadResumes: Int = 5,
    /** brief P3i: how long the answer to an export upload may take after the last byte (the server writes the file first) */
    val exportReadTimeoutMs: Int = 60_000,
)

/** Result of checking a server before it is added. */
sealed interface ServerCheckResult {
    /** LPL answered; [passwordRequired] tells whether it asks for one; [passwordOk] whether the given one was accepted */
    data class Lpl(val passwordRequired: Boolean, val passwordOk: Boolean, val photos: Long?) : ServerCheckResult
    /** something answers there, but not LPL */
    data object NotLpl : ServerCheckResult
    data class Failed(val error: LplException) : ServerCheckResult
}

/**
 * The client for LPL's agent calls (PLAN.md 4.1), one per server. Blocking: call it from a background
 * thread. [token] gives the password each time it is needed (it is kept encrypted elsewhere) and is
 * sent only in the `x-lpl-token` header, only to [origin]. Nothing here logs; [log], when given,
 * receives one line per call with method, path, status and duration, never a header or a body.
 */
class LplClient(
    val origin: ServerOrigin,
    private val token: () -> String?,
    private val transport: HttpTransport = UrlConnectionTransport(),
    val settings: ClientSettings = ClientSettings(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: ((String) -> Unit)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Brief P4a: told before each automatic repeat after a failure that can pass (attempt 1 = the first repeat, the wait
     * before it in ms), so the app can say "retrying" while it waits. Set by [LplAgent].
     */
    @Volatile var onRetry: ((attempt: Int, waitMs: Long, e: LplException) -> Unit)? = null

    private fun backoff(attempt: Int, e: LplException) {
        val wait = settings.backoffMs.getOrElse(attempt) { settings.backoffMs.lastOrNull() ?: 0L }
        onRetry?.invoke(attempt + 1, wait, e)
        sleep(wait)
    }

    // ------------------------------------------------------------------ calls of PLAN.md 4.1

    /** `POST /api/agents/heartbeat`. Not repeated: the next heartbeat follows anyway. */
    fun heartbeat(
        installId: String, deviceName: String, state: AgentState, transfers: JsonArray = JsonArray(emptyList()),
        extraCaps: List<String> = emptyList(),
    ): HeartbeatAnswer {
        val body = buildBody(installId, deviceName, state, transfers, extraCaps)
        val o = callJson("POST", "/api/agents/heartbeat", body, retries = 0, readTimeoutMs = settings.heartbeatTimeoutMs)
        val jobs = o["jobs"].arr()?.mapNotNull { e ->
            val j = e as? JsonObject ?: return@mapNotNull null
            val id = j["id"].long() ?: return@mapNotNull null
            val kind = j["kind"].str() ?: return@mapNotNull null
            val payload = j["payload"] as? JsonObject
            val ids = payload?.get("ids").arr()?.mapNotNull { it.long() ?: it.str()?.toLongOrNull() } ?: emptyList()
            Job(id, kind, ids, payload?.get("all").bool() == true, payload?.get("open").bool() != false)
        } ?: throw LplException(LplFailure.BAD_ANSWER, "POST /api/agents/heartbeat: no job list in the answer")
        val c = o["cancel"] as? JsonObject
        val cancel = c?.let { Cancel(it["jobs"].arr().orEmpty().mapNotNull { x -> x.long() }.toSet(), it["photos"].arr().orEmpty().mapNotNull { x -> x.long() }.toSet()) }
        val caps = o["server_caps"].arr()?.mapNotNull { it.str() }?.toSet()
        return HeartbeatAnswer(jobs, o["server_time"].num() ?: 0.0, cancel, caps)
    }

    private fun buildBody(installId: String, deviceName: String, state: AgentState, transfers: JsonArray, extraCaps: List<String>): ByteArray =
        JsonObject(mapOf(
            "id" to kotlinx.serialization.json.JsonPrimitive(installId),
            "name" to kotlinx.serialization.json.JsonPrimitive(deviceName),
            // exactly "android": that keeps jobs meant for a PC away from the phone (LPL hand-off notes, R3)
            "os" to kotlinx.serialization.json.JsonPrimitive("android"),
            "state" to state.toJson(),
            // brief P2k: what this device can do besides the old exchange; an old server ignores both
            "caps" to kotlinx.serialization.json.buildJsonArray { for (c in CAPS + extraCaps) add(kotlinx.serialization.json.JsonPrimitive(c)) },
            "transfers" to transfers,
        )).toString().toByteArray()

    /** `GET /api/photos/{id}/checkout`: file name, size and the server's time, which becomes `checkout_ts`. */
    fun checkout(id: Long, agentId: String? = null): Checkout {
        // R17: with `agent=<id>` of an agent whose heartbeat caps say `versions`, the answer lists the versions (and LPL
        // notes which numbers this agent was told about, for `409 version_exists`)
        val o = callJson("GET", "/api/photos/$id/checkout" + (agentId?.let { "?agent=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""))
        val name = o["filename"].str()?.takeIf { it.isNotEmpty() }
            ?: throw LplException(LplFailure.BAD_ANSWER, "GET /api/photos/$id/checkout: no file name")
        return Checkout(
            id = id,
            filename = name,
            size = o["size"].long() ?: -1L,
            mtime = o["mtime"].num() ?: 0.0,
            serverTime = o["server_time"].num()
                ?: throw LplException(LplFailure.BAD_ANSWER, "GET /api/photos/$id/checkout: no server time"),
            cover = o["cover"].long()?.toInt() ?: 0,
            sha256 = o["sha256"].str()?.lowercase()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) },
            versions = o["versions"].arr()?.mapNotNull { e ->
                val v = e as? JsonObject ?: return@mapNotNull null
                val n = v["n"].long()?.toInt() ?: return@mapNotNull null
                ServerVersion(n, v["name"].str() ?: "", v["mtime"].num() ?: 0.0, v["size"].long() ?: -1L, v["has_file"].bool() != false)
            },
        )
    }

    /** `GET /api/photos/{id}/xmp`: LPL's sidecar for the photo (made from its metadata when the NAS has none). */
    fun getXmp(id: Long, version: Int = 0): ByteArray = call("GET", "/api/photos/$id/xmp${versionQuery(version, "?")}", retries = settings.retries) { r ->
        r.body.readBytes().also { if (it.isEmpty()) throw LplException(LplFailure.BAD_ANSWER, "GET /api/photos/$id/xmp: empty") }
    }

    /** R17: `version=n` for a version other than 0; nothing for 0, so the calls of an older server are unchanged. */
    private fun versionQuery(version: Int, lead: String) = if (version > 0) "${lead}version=$version" else ""

    /** R17: `POST /api/photos/{id}/cover?version=n`: the version LPL shows for the photo. Returns the cover LPL now has. */
    fun setCover(id: Long, version: Int): Int =
        callJson("POST", "/api/photos/$id/cover?version=$version", ByteArray(0))["cover"].long()?.toInt() ?: version

    /** R17: `DELETE /api/photos/{id}/xmp?version=n` (never 0): LPL moves the file to its backup folder. */
    fun deleteVersion(id: Long, version: Int) {
        require(version > 0) { "version 0 is not deleted" }
        call("DELETE", "/api/photos/$id/xmp?version=$version", retries = 0) { r -> r.body.readBytes() }
    }

    /**
     * `POST /api/photos/{id}/xmp?checkout_ts=…`: the edit goes back. LPL answers with the XMP it stored;
     * `X-LPL-Merged: 1` means it put its own newer rating, labels and tags into it, and the app must take
     * that file. Repeated after a transient failure: posting the same edit twice stores the same file.
     */
    fun postXmp(id: Long, xmp: ByteArray, checkoutTs: Double, agentId: String? = null, version: Int = 0): XmpAnswer {
        // R15: `agent` names the posting device (the heartbeat's id); a server without R15 ignores it
        val path = "/api/photos/$id/xmp?checkout_ts=${formatTs(checkoutTs)}" +
            (agentId?.let { "&agent=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "") + versionQuery(version, "&")
        return call("POST", path, xmp, "application/rdf+xml", retries = settings.retries) { r ->
            val merged = r.header("x-lpl-merged") == "1"
            val body = r.body.readBytes()
            if (body.isEmpty()) throw LplException(LplFailure.BAD_ANSWER, "POST /api/photos/$id/xmp: empty answer")
            XmpAnswer(merged, body)
        }
    }

    /**
     * R15: the photo's last edit (`GET /api/photos/{id}`, its `last_edit`). Null when the server has no R15
     * (the field is absent or null), and when it answers 404 for the call. Other failures are thrown.
     */
    fun lastEdit(id: Long): LastEdit? = try {
        LastEdit.fromJson(callJson("GET", "/api/photos/$id")["last_edit"])
    } catch (e: LplException) {
        if (e.failure == LplFailure.NOT_FOUND) null else throw e
    }

    /** R15: the photo's edits, newest first (`GET /api/photos/{id}/edits`); empty when the server has no R15. */
    fun edits(id: Long, limit: Int? = null): List<LastEdit> = try {
        callJson("GET", "/api/photos/$id/edits" + (limit?.let { "?limit=$it" } ?: ""))["edits"].arr()?.mapNotNull { LastEdit.fromJson(it) } ?: emptyList()
    } catch (e: LplException) {
        if (e.failure == LplFailure.NOT_FOUND) emptyList() else throw e
    }

    /** R18: `GET /api/photos/{id}/exports`, newest first. Repeated after a transient failure (it changes nothing). */
    fun listExports(id: Long): List<ServerExport> = call("GET", "/api/photos/$id/exports", retries = settings.retries) { r ->
        val o = parseObject("GET /api/photos/$id/exports", r.body.readBytes())
        o["exports"].arr().orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            ServerExport(x["id"].long() ?: return@mapNotNull null, x["version"].long()?.toInt() ?: 0, x["name"].str() ?: "",
                x["path"].str() ?: "", x["size"].long() ?: -1L, x["agent"].str())
        }
    }

    /**
     * R18: `POST /api/photos/{id}/exports?agent=&version=&name=&size=` with the file as the body, streamed; [onBytes]
     * gets the bytes sent so far. LPL has no resumable upload: a failure that can pass (no answer, 502/503/504) repeats
     * the whole file, up to [ClientSettings.retries] more times; `size` makes the server refuse a cut-off body.
     *
     * Brief P4a: never two copies on the server. [known] is what the photo's export list held before the first attempt
     * (null: do not check). When an attempt fails without an answer, the server may have stored the file all the same:
     * the list is read, and an export that [ServerExport.isUpload] says is this one is taken as the answer instead of
     * sending the file again. [checkFirst]: look before the first attempt too (an upload of an earlier run that may have
     * arrived). [claimed]: exports other uploads of this device already took as theirs.
     */
    fun postExport(
        id: Long, agentId: String, version: Int, name: String, file: File, onBytes: (Long) -> Unit = {},
        known: Set<Long>? = null, claimed: Set<Long> = emptySet(), checkFirst: Boolean = false,
    ): ExportAnswer {
        val enc = { t: String -> java.net.URLEncoder.encode(t, "UTF-8") }
        val path = "/api/photos/$id/exports?agent=${enc(agentId)}&version=$version&name=${enc(name)}&size=${file.length()}"
        fun arrived(): ExportAnswer? = known?.let { k ->
            listExports(id).firstOrNull { it.isUpload(agentId, version, name, file.length(), k, claimed) }?.let { ExportAnswer(it.id, it.path) }
        }
        if (checkFirst) arrived()?.let { log?.invoke("POST /api/photos/$id/exports: already stored by an earlier attempt (export ${it.exportId})"); return it }
        var attempt = 0
        while (true) {
            try {
                return callOnce("POST", path, null, "application/octet-stream", settings.exportReadTimeoutMs, true, file, onBytes) { r ->
                    val o = parseObject("POST /api/photos/$id/exports", r.body.readBytes())
                    ExportAnswer(o["export_id"].long() ?: throw LplException(LplFailure.BAD_ANSWER, "POST /api/photos/$id/exports: no export_id in the answer"), o["path"].str() ?: "")
                }
            } catch (e: LplException) {
                if (!e.transient) throw e
                // the answer may be what was lost: look before sending the file again
                arrived()?.let { log?.invoke("POST /api/photos/$id/exports: the answer was lost, the server has it (export ${it.exportId})"); return it }
                if (attempt >= settings.retries) throw e
                backoff(attempt, e)
                attempt++
            }
        }
    }

    /** `POST /api/photos/{id}/preview`: the engine's JPEG becomes LPL's preview of the photo. */
    fun postPreview(id: Long, jpeg: ByteArray, version: Int = 0) {
        callJson("POST", "/api/photos/$id/preview${versionQuery(version, "?")}", jpeg, "image/jpeg")
    }

    /** `POST /api/jobs/{id}/done` with `done` or `failed`. */
    fun jobDone(jobId: Long, ok: Boolean) = jobDone(jobId, if (ok) "done" else "failed")

    /**
     * `POST /api/jobs/{id}/done` with `done`, `failed` or `stopped` (brief P2k), and for a batch job the four
     * lists of photo ids (`arrived`, `present`, `failed`, `skipped`).
     */
    fun jobDone(jobId: Long, status: String, lists: Map<String, List<Long>>? = null) {
        val body = JsonObject(buildMap {
            put("status", kotlinx.serialization.json.JsonPrimitive(status))
            lists?.forEach { (k, v) -> put(k, kotlinx.serialization.json.buildJsonArray { v.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }
        }).toString().toByteArray()
        callJson("POST", "/api/jobs/$jobId/done", body)
    }

    /**
     * `GET /api/photos/{id}/original` into [part], resuming what is already there with
     * `Range: bytes=<length>-` and `If-Range` (the ETag of the first answer, kept in `<part>.etag`), so a
     * changed file on the NAS is not glued onto old bytes. A download that breaks off resumes up to
     * [ClientSettings.downloadResumes] times. When it returns, [part] holds the whole file (its length is
     * [expectedSize] when that is known). [progress] gets bytes on disk and the total; [cancelled] is
     * asked between blocks.
     */
    fun downloadOriginal(
        id: Long,
        part: File,
        expectedSize: Long,
        progress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ) {
        val etagFile = File(part.path + ".etag")
        var attempts = 0
        var restarted = false
        while (true) {
            if (cancelled()) throw LplException(LplFailure.CANCELLED, "GET /api/photos/$id/original: cancelled")
            val have = if (part.isFile) part.length() else 0L
            if (expectedSize > 0 && have == expectedSize) { progress(have, expectedSize); return }
            if (expectedSize > 0 && have > expectedSize) { part.delete(); etagFile.delete(); continue }
            try {
                val done = downloadOnce(id, part, etagFile, have, expectedSize, progress, cancelled)
                if (done) return
                // 416 or a mismatch: start again from nothing, once
                if (restarted) throw LplException(LplFailure.BAD_ANSWER, "GET /api/photos/$id/original: the server's file does not match the expected size")
                restarted = true
                part.delete(); etagFile.delete()
            } catch (e: LplException) {
                if (!e.transient || attempts >= settings.downloadResumes) throw e
                attempts++
                backoff(attempts - 1, e)
            }
        }
    }

    /** One request; true when [part] is complete afterwards, false when the download has to start again. */
    private fun downloadOnce(
        id: Long, part: File, etagFile: File, have: Long, expectedSize: Long,
        progress: (Long, Long) -> Unit, cancelled: () -> Boolean,
    ): Boolean {
        val label = "GET /api/photos/$id/original"
        val headers = HashMap<String, String>()
        token()?.takeIf { it.isNotEmpty() }?.let { headers["x-lpl-token"] = it }
        if (have > 0) {
            headers["Range"] = "bytes=$have-"
            etagFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["If-Range"] = it }
        }
        val req = HttpRequest("GET", origin, "/api/photos/$id/original", headers, null, null,
            settings.connectTimeoutMs, settings.downloadReadTimeoutMs)
        val t0 = System.nanoTime()
        return try {
            transport.exchange(req) { r ->
                log?.invoke("$label${if (have > 0) " (Range from $have)" else ""} -> ${r.status} (${ms(t0)} ms to the answer)")
                when (r.status) {
                    200, 206 -> {
                        val append = r.status == 206
                        if (append) {
                            val start = r.header("content-range")?.let { Regex("bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                            if (start != have) throw LplException(LplFailure.BAD_ANSWER, "$label: range answer starts at $start, not at $have")
                        } else {
                            // brief P4a: a server that does not answer Range (or whose file changed, If-Range) sends it all: start again
                            if (have > 0) log?.invoke("$label: the server sent the whole file instead of the rest (Range not answered or file changed); downloading it again")
                            r.header("etag")?.let { e -> writeSmall(etagFile, e) } ?: etagFile.delete()
                        }
                        val total = when {
                            expectedSize > 0 -> expectedSize
                            append -> r.header("content-range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                            else -> r.header("content-length")?.toLongOrNull() ?: -1L
                        }
                        var done = if (append) have else 0L
                        try {
                            part.parentFile?.mkdirs()
                            RandomAccessFile(part, "rw").use { f ->
                                f.setLength(done)
                                f.seek(done)
                                val buf = ByteArray(1 shl 16)
                                var lastReport = 0L
                                var lastSync = done
                                while (true) {
                                    if (cancelled()) throw LplException(LplFailure.CANCELLED, "$label: cancelled")
                                    val n = r.body.read(buf)
                                    if (n < 0) break
                                    f.write(buf, 0, n)
                                    done += n
                                    if (done - lastReport >= (1 shl 18)) { progress(done, total); lastReport = done }
                                    // brief P4a: what is on disk is on disk, so a resume after the phone died starts from real bytes
                                    if (done - lastSync >= SYNC_EVERY) { f.fd.sync(); lastSync = done }
                                }
                                f.fd.sync()
                            }
                        } catch (e: LplException) {
                            throw e
                        } catch (e: IOException) {
                            if (isLocal(e, part)) throw LplException(LplFailure.LOCAL, "$label: cannot write the file (${e.javaClass.simpleName})", cause = e)
                            throw e
                        }
                        progress(done, total)
                        log?.invoke("$label: $done bytes on disk after ${ms(t0)} ms")
                        when {
                            expectedSize > 0 && done == expectedSize -> true
                            expectedSize > 0 && done < expectedSize -> throw LplException(LplFailure.UNREACHABLE, "$label: the download broke off at $done of $expectedSize bytes")
                            expectedSize > 0 -> false
                            // brief P4a: no size from checkout: the answer's own length decides whether the file is whole
                            total in 1 until done -> false
                            total > 0 && done < total -> throw LplException(LplFailure.UNREACHABLE, "$label: the download broke off at $done of $total bytes")
                            else -> true
                        }
                    }
                    416 -> false
                    else -> throw statusError(label, r)
                }
            }
        } catch (e: LplException) {
            throw e
        } catch (e: IOException) {
            log?.invoke("$label: ${e.javaClass.simpleName} after ${ms(t0)} ms")
            throw LplException.fromIo(label, e)
        }
    }

    private fun isLocal(e: IOException, part: File): Boolean =
        e.message?.contains(part.name) == true || e.message?.contains("No space", ignoreCase = true) == true ||
            e.message?.contains("ENOSPC") == true

    private fun writeSmall(f: File, text: String) = runCatching { f.parentFile?.mkdirs(); f.writeText(text) }

    // ------------------------------------------------------------------ adding a server

    /**
     * Is LPL there, does it want a password, is [password] right? `GET /api/auth` (answered without
     * a password, tells whether one is required), then `GET /api/status` with the password.
     */
    fun check(password: String?): ServerCheckResult {
        val auth = try {
            call("GET", "/api/auth", retries = 0, withToken = false) { r -> parseObject("GET /api/auth", r.body.readBytes()) }
        } catch (e: LplException) {
            return when (e.failure) {
                LplFailure.NOT_FOUND, LplFailure.BAD_ANSWER, LplFailure.REFUSED, LplFailure.REDIRECT, LplFailure.UNAUTHORIZED -> ServerCheckResult.NotLpl
                else -> ServerCheckResult.Failed(e)
            }
        }
        val required = auth["required"].bool() ?: return ServerCheckResult.NotLpl
        val probe = LplClient(origin, { password }, transport, settings, sleep, log)
        // R14: a call that only checks the password; an older server answers 404 and the status call decides
        try {
            val t = probe.callJson("GET", "/api/auth/token", retries = 0)
            return ServerCheckResult.Lpl(t["required"].bool() ?: required, t["ok"].bool() ?: true, null)
        } catch (e: LplException) {
            when (e.failure) {
                LplFailure.UNAUTHORIZED -> return ServerCheckResult.Lpl(required, false, null)
                LplFailure.NOT_FOUND -> {}
                else -> return ServerCheckResult.Failed(e)
            }
        }
        return try {
            val st = probe.callJson("GET", "/api/status", retries = 0)
            ServerCheckResult.Lpl(required, true, st["photos"].long())
        } catch (e: LplException) {
            if (e.failure == LplFailure.UNAUTHORIZED) ServerCheckResult.Lpl(required, false, null) else ServerCheckResult.Failed(e)
        }
    }

    // ------------------------------------------------------------------ plumbing

    internal fun callJson(
        method: String, path: String, body: ByteArray? = null, contentType: String = "application/json",
        retries: Int = if (method == "GET") settings.retries else 0, readTimeoutMs: Int = settings.readTimeoutMs,
    ): JsonObject = call(method, path, body, contentType, retries, readTimeoutMs) { r ->
        parseObject("$method ${path.substringBefore('?')}", r.body.readBytes())
    }

    /**
     * Brief P3l (R11): a JSON call whose answer may be one of the [allow]ed error statuses (409 `changed_elsewhere`):
     * the status and the parsed object. Never repeated (PUT, DELETE and POST change the server).
     */
    internal fun callJsonStatus(method: String, path: String, body: ByteArray? = null, allow: Set<Int> = setOf(409)): Pair<Int, JsonObject> =
        callOnce(method, path, body, if (body != null) "application/json" else null, settings.readTimeoutMs, true, allow = allow) { r ->
            r.status to parseObject("$method ${path.substringBefore('?')}", r.body.readBytes())
        }

    /**
     * Brief P6b (R19): one call with extra headers (`x-sha256`, `x-base-sha256`), an optional body file streamed, and
     * error statuses [allow]ed through to [handle] (409 `changed_elsewhere`, 404). Never repeated here.
     */
    internal fun <T> callRaw(
        method: String, path: String, headers: Map<String, String> = emptyMap(), bodyFile: File? = null,
        allow: Set<Int> = emptySet(), readTimeoutMs: Int = settings.exportReadTimeoutMs, handle: (HttpResponse) -> T,
    ): T = callOnce(method, path, null, if (bodyFile != null) "application/octet-stream" else null, readTimeoutMs, true,
        bodyFile, null, allow, headers, handle)

    internal fun parseAnswer(label: String, bytes: ByteArray): JsonObject = parseObject(label, bytes)

    private fun parseObject(label: String, bytes: ByteArray): JsonObject =
        try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw LplException(LplFailure.BAD_ANSWER, "$label: the answer is not a JSON object")

    private fun <T> call(
        method: String, path: String, body: ByteArray? = null, contentType: String? = null,
        retries: Int = 0, readTimeoutMs: Int = settings.readTimeoutMs, withToken: Boolean = true,
        handle: (HttpResponse) -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return callOnce(method, path, body, contentType, readTimeoutMs, withToken, handle = handle)
            } catch (e: LplException) {
                if (!e.transient || attempt >= retries) throw e
                backoff(attempt, e)
                attempt++
            }
        }
    }

    private fun <T> callOnce(
        method: String, path: String, body: ByteArray?, contentType: String?, readTimeoutMs: Int, withToken: Boolean,
        bodyFile: File? = null, onSent: ((Long) -> Unit)? = null, allow: Set<Int> = emptySet(),
        extraHeaders: Map<String, String> = emptyMap(),
        handle: (HttpResponse) -> T,
    ): T {
        val headers = HashMap<String, String>(extraHeaders)
        if (withToken) token()?.takeIf { it.isNotEmpty() }?.let { headers["x-lpl-token"] = it }
        val req = HttpRequest(method, origin, path, headers, body, contentType, settings.connectTimeoutMs, readTimeoutMs, bodyFile, onSent)
        val t0 = System.nanoTime()
        return try {
            transport.exchange(req) { r ->
                log?.invoke("${req.label} -> ${r.status} (${ms(t0)} ms)")
                if (r.status !in 200..299 && r.status !in allow) throw statusError(req.label, r)
                handle(r)
            }
        } catch (e: LplException) {
            throw e
        } catch (e: IOException) {
            log?.invoke("${req.label}: ${e.javaClass.simpleName} after ${ms(t0)} ms")
            throw LplException.fromIo(req.label, e)
        }
    }

    private fun statusError(label: String, r: HttpResponse): LplException {
        // LPL's errors are {"detail": "..."}; the detail is kept (short) for the log, never shown raw
        val text = runCatching { r.body.readNBytes(400).decodeToString() }.getOrDefault("")
        val detail = Regex("\"detail\"\\s*:\\s*\"([^\"]{0,120})").find(text)?.groupValues?.get(1) ?: ""
        val s = r.status
        val what = if (detail.isNotEmpty()) ": $detail" else ""
        if (s == 409 && "version_exists" in text) {
            // R17: {"error":"version_exists","next":3}
            val next = Regex("\"next\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            return LplException(LplFailure.VERSION_EXISTS, "$label: 409 version_exists", s, next = next)
        }
        return when {
            s in 300..399 -> LplException(LplFailure.REDIRECT, "$label: redirect $s, not followed", s)
            s == 401 || s == 403 -> LplException(LplFailure.UNAUTHORIZED, "$label: $s$what", s)
            s == 404 -> LplException(LplFailure.NOT_FOUND, "$label: 404$what", s)
            s in 400..499 -> LplException(LplFailure.REFUSED, "$label: $s$what", s)
            else -> LplException(LplFailure.SERVER, "$label: $s$what", s)
        }
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    companion object {
        /** `checkout_ts` as the PC agent sends it: the server's float time, full precision. */
        /** `caps` of every heartbeat (brief P2k): `batch` = reports transfers, `cancel` = acts on Stop, `open` = honours `payload.open` */
        val CAPS = listOf("batch", "cancel", "open", "versions", "exports")

        /** brief P4a: a download in progress is flushed to storage after this many bytes */
        const val SYNC_EVERY = 4L shl 20

        fun formatTs(ts: Double): String = java.math.BigDecimal.valueOf(ts).toPlainString()
    }
}

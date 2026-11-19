// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Encrypts small secrets for storage. On the phone the key lives in the Android keystore and never
 * leaves it (app-engine's `KeystoreSecretBox`); tests use an in-memory key.
 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    /** throws when the data was not sealed with this box's key (for example after a restore to another phone) */
    fun open(sealed: ByteArray): ByteArray
}

/** A server the user added (brief P2e deliverable 2). The password is not here: [ServerStore.password]. */
data class ServerEntry(
    val origin: ServerOrigin,
    /** this device's name as LPL shows it ("Phone"); chosen by the user, never the hardware model */
    val deviceName: String,
    val hasPassword: Boolean,
)

/**
 * The servers the user added and the install id, in `<dir>/servers.json`. Stored as a list although
 * one server is enough for now. The password is sealed with [box] and only the sealed bytes are on
 * disk; the file is written atomically.
 */
class ServerStore(private val dir: File, private val box: SecretBox) {
    private val file = File(dir, "servers.json")
    private val idFile = File(dir, "install-id")
    private val json = Json { ignoreUnknownKeys = true }

    private data class Stored(val entry: ServerEntry, val sealed: String?)

    private var stored: List<Stored> = load()
    private val plain = HashMap<ServerOrigin, String?>()

    /** `android-<uuid>`, made once and kept (PLAN.md 4.1). */
    val installId: String by lazy {
        idFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { ID.matches(it) } ?: run {
            val id = "android-" + UUID.randomUUID()
            dir.mkdirs()
            atomicWrite(idFile, id.toByteArray())
            id
        }
    }

    @Synchronized
    fun list(): List<ServerEntry> = stored.map { it.entry }

    @Synchronized
    fun get(origin: ServerOrigin): ServerEntry? = stored.firstOrNull { it.entry.origin == origin }?.entry

    /** Add or replace a server. An empty password means none. */
    @Synchronized
    fun put(origin: ServerOrigin, deviceName: String, password: String?) {
        val name = cleanName(deviceName) ?: throw IllegalArgumentException("device name missing")
        val pw = password?.takeIf { it.isNotEmpty() }
        val sealed = pw?.let { Base64.getEncoder().encodeToString(box.seal(it.toByteArray())) }
        stored = stored.filter { it.entry.origin != origin } + Stored(ServerEntry(origin, name, pw != null), sealed)
        plain[origin] = pw
        save()
    }

    @Synchronized
    fun rename(origin: ServerOrigin, deviceName: String) {
        val name = cleanName(deviceName) ?: throw IllegalArgumentException("device name missing")
        stored = stored.map { if (it.entry.origin == origin) it.copy(entry = it.entry.copy(deviceName = name)) else it }
        save()
    }

    @Synchronized
    fun remove(origin: ServerOrigin) {
        stored = stored.filter { it.entry.origin != origin }
        plain.remove(origin)
        save()
    }

    /**
     * The password for [origin], or null when it has none. Throws [IllegalStateException] when the
     * sealed password cannot be opened (the keystore key is gone): the user must enter it again.
     */
    @Synchronized
    fun password(origin: ServerOrigin): String? {
        if (origin in plain) return plain[origin]
        val s = stored.firstOrNull { it.entry.origin == origin } ?: return null
        val pw = s.sealed?.let {
            try {
                String(box.open(Base64.getDecoder().decode(it)))
            } catch (e: Exception) {
                throw IllegalStateException("the saved password cannot be read on this phone; enter it again")
            }
        }
        plain[origin] = pw
        return pw
    }

    private fun load(): List<Stored> {
        val o = runCatching { json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["servers"] as? JsonArray)?.mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            val origin = s["origin"].str()?.let { ServerOrigin.parseExact(it) } ?: return@mapNotNull null
            val name = s["device_name"].str()?.let { cleanName(it) } ?: return@mapNotNull null
            val sealed = s["password"].str()
            Stored(ServerEntry(origin, name, sealed != null), sealed)
        } ?: emptyList()
    }

    private fun save() {
        val o = buildJsonObject {
            put("servers", buildJsonArray {
                for (s in stored) add(buildJsonObject {
                    put("origin", s.entry.origin.url)
                    put("device_name", s.entry.deviceName)
                    put("password", s.sealed?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            })
        }
        dir.mkdirs()
        atomicWrite(file, o.toString().toByteArray())
    }

    companion object {
        private val ID = Regex("android-[0-9a-f-]{36}")
        const val MAX_NAME = 40

        /** A device name as LPL will show it: trimmed, no control characters, at most 40 characters; null if empty. */
        fun cleanName(name: String): String? = name.filterNot { it.isISOControl() }.trim().take(MAX_NAME).ifEmpty { null }
    }
}

/** Write [f] through a temporary file and a rename, so it is either the old or the new content. */
fun atomicWrite(f: File, bytes: ByteArray) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.outputStream().use { it.write(bytes); it.fd.sync() }
    if (!tmp.renameTo(f)) {
        f.delete()
        if (!tmp.renameTo(f)) throw java.io.IOException("cannot replace ${f.name}")
    }
}

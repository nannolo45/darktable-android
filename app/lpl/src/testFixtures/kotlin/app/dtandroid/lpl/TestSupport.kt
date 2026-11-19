// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a key that lives only in memory: the tests' stand-in for the Android keystore. */
class MemorySecretBox(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return c.doFinal(sealed.copyOfRange(12, sealed.size))
    }
}

/**
 * The darkroom and engine as the agent sees them, for tests without an engine: [openRaw] is the photo
 * "open in the darkroom"; [edit] makes the next [writeIfOpen] write a changed XMP; an XMP containing
 * `newer-module` fails the version guard; previews are tiny JPEG-looking files.
 */
class FakeEditor : EditorPort {
    @Volatile var openRaw: File? = null
    @Volatile var busy = false
    @Volatile var edits = 0
    /** what a rendered preview contains (a few JPEG-looking bytes; a real JPEG for a real server) */
    @Volatile var previewBytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    val reloads = java.util.Collections.synchronizedList(ArrayList<File>())
    val renders = java.util.Collections.synchronizedList(ArrayList<File>())
    val writes = java.util.Collections.synchronizedList(ArrayList<File>())

    /** Simulate one edit in the darkroom on [xmp] (what the engine's xmp_write would produce). */
    fun edit(xmp: File) {
        edits++
        val t = if (xmp.isFile) xmp.readText() else StubLpl.lplXmp(0).decodeToString()
        xmp.writeText(t.replace("</rdf:RDF>", "<!-- edit $edits --></rdf:RDF>"))
    }

    override suspend fun xmpReadOnly(xmp: File) = xmp.isFile && xmp.readText().contains("newer-module")
    override suspend fun writeIfOpen(raw: File, xmp: File) { if (openRaw == raw) writes += xmp }
    override suspend fun reloadIfOpen(raw: File, xmp: File) { if (openRaw == raw) reloads += xmp }
    override suspend fun renderPreview(raw: File, xmp: File, out: File): Boolean {
        if (busy) return false
        renders += raw
        out.writeBytes(previewBytes)
        return true
    }
    override fun isOpen(raw: File) = openRaw == raw
    override fun darkroomOpen() = openRaw != null
}

/** Bytes that stand for a RAW file in tests: deterministic, [size] long. */
fun fakeRaw(size: Int, seed: Int = 1): ByteArray = ByteArray(size) { ((it * 31 + seed * 7) xor (it shr 7)).toByte() }

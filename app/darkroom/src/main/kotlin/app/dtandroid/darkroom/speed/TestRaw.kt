// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.speed

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest

/**
 * Brief P4b: the fixed photo of "measure speed" and of the GPU check: `images/mire1.cr2` of darktable's public test set
 * (darktable-org/darktable-tests, the commit the CI tests use, tools/engine-test/darktable-tests.pin), the same RAW the
 * emulator and bridge tests render. Fetched once into the app's own storage when the owner asks for it, checked by size
 * and SHA-256, never sent anywhere.
 */
class TestRaw(private val dir: File, private val url: String = URL_MIRE1, private val open: (String) -> InputStream = { URL(it).openStream() }) {
    val file: File get() = File(dir, NAME)

    /** The file is there and is the right one (size only: the hash was checked when it was fetched). */
    fun present(): Boolean = file.isFile && file.length() == SIZE

    /** Fetch it unless present; throws [IOException] with a plain reason. */
    fun ensure(progress: (Long) -> Unit = {}): File {
        if (present()) return file
        dir.mkdirs()
        val part = File(dir, "$NAME.part")
        part.delete()
        val md = MessageDigest.getInstance("SHA-256")
        try {
            open(url).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var n = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r); md.update(buf, 0, r)
                        n += r
                        if (n > SIZE) throw IOException("the test photo is larger than expected")
                        progress(n)
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw IOException("the test photo could not be fetched: ${e.message}", e)
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (part.length() != SIZE || hex != SHA256) {
            part.delete()
            throw IOException("the fetched test photo is not the expected file (${part.length()} bytes, SHA-256 $hex)")
        }
        if (!part.renameTo(file)) throw IOException("the test photo could not be stored")
        return file
    }

    companion object {
        const val NAME = "mire1.cr2"
        const val SIZE = 15_855_600L
        const val SHA256 = "578dc1b45add995be167802f9f874a95de3113d2f133cc86d2cf931448f76f3f"
        /** darktable-tests at the pinned commit 4404dec2013aeaae2148d1a84708997b360100ae */
        const val URL_MIRE1 = "https://raw.githubusercontent.com/darktable-org/darktable-tests/4404dec2013aeaae2148d1a84708997b360100ae/images/mire1.cr2"
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import java.io.File
import java.security.MessageDigest

/**
 * Brief P4a deliverable 1: a downloaded original is checked before it is moved into place (and so before the engine
 * can open it): its length must be the size `/checkout` gave, and its SHA-256 the one `/checkout` gave, when the server
 * gives one (LPL does not today; see the P4a report). The file is read in blocks, never whole.
 */
object DownloadCheck {
    /** Null when [f] is fine; otherwise what is wrong, in words for the log. */
    fun problem(f: File, expectedSize: Long, expectedSha256: String?): String? {
        if (!f.isFile) return "the file is missing"
        if (expectedSize > 0 && f.length() != expectedSize) return "${f.length()} bytes instead of $expectedSize"
        if (expectedSha256 != null) {
            val got = sha256(f)
            if (!got.equals(expectedSha256, ignoreCase = true)) return "checksum ${got.take(12)}… instead of ${expectedSha256.take(12)}…"
        }
        return null
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

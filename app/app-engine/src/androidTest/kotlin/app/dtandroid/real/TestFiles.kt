// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.dtandroid.engine.JniEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The public test RAW and XMP the CI script pushes to /data/local/tmp (instrumentation arguments
 * `raw` and `xmp`), copied once into app storage; the started engine; a results folder the script
 * pulls with run-as (files/p2b-test/).
 */
object TestFiles {
    private val instr get() = InstrumentationRegistry.getInstrumentation()
    val ctx get() = instr.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    val out: File by lazy { File(ctx.filesDir, "p2b-test").apply { mkdirs() } }
    private val dir: File by lazy { File(ctx.filesDir, "p2b-input").apply { mkdirs() } }

    private fun copyFromShell(src: String, dst: File) {
        if (dst.isFile && dst.length() > 0) return
        val pfd: ParcelFileDescriptor = instr.uiAutomation.executeShellCommand("cat $src")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> dst.outputStream().use { input.copyTo(it) } }
        assertTrue("could not copy $src (empty)", dst.length() > 0)
    }

    /** The test RAW in app storage (read-only copy). */
    val raw: File by lazy {
        val src = args.getString("raw") ?: error("instrumentation argument 'raw' missing")
        File(dir, File(src).name).also { copyFromShell(src, it); it.setWritable(false, false) }
    }

    /** The public test edit for [raw] (darktable-tests), outside the RAW's sidecar name. */
    val xmp: File by lazy {
        val src = args.getString("xmp") ?: error("instrumentation argument 'xmp' missing")
        File(dir, "test-edit.xmp").also { copyFromShell(src, it) }
    }

    /** [xmp] with its first history entry's operation renamed to one no darktable knows. */
    fun writeXmpWithUnknownModule(f: File) {
        val text = xmp.readText()
        val m = Regex("""darktable:operation="([a-z0-9_]+)"""").find(text) ?: error("no operation in the test XMP")
        f.writeText(text.replaceRange(m.range, "darktable:operation=\"dtandroidunknownmodule\""))
    }

    fun record(file: String, key: String, value: Any) = File(out, file).appendText("$key\t$value\n")

    /** The app's engine, started as the app starts it (unpack, load, init, fast cores). */
    fun engine(): JniEngine = runBlocking {
        AppEngine.start(ctx)
        withTimeout(600_000) {
            while (AppEngine.status.value !is AppEngine.Status.Ready) {
                (AppEngine.status.value as? AppEngine.Status.Failed)?.let { error("engine did not start: ${it.text}") }
                delay(50)
            }
        }
        AppEngine.engine
    }

    fun md5(f: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 16)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

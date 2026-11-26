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

    /** Brief P2f: the public spektrafilm case of darktable-tests (RAW and its XMP), or null when not given. */
    val spektrafilm: Pair<File, File>? by lazy {
        val r = args.getString("sfraw") ?: return@lazy null
        val x = args.getString("sfxmp") ?: return@lazy null
        File(dir, File(r).name).also { copyFromShell(r, it); it.setWritable(false, false) } to
            File(dir, "spektrafilm-test.xmp").also { copyFromShell(x, it) }
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

    /** Run a shell command as the shell user (brief P2e: start the background job now) and return its output. */
    fun shell(cmd: String): String {
        val pfd: ParcelFileDescriptor = instr.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /**
     * Whether the input method service says its keyboard is shown. The window insets are not used: in p2b run 45799493184
     * (P5cTapTest.t4) they said "no keyboard" for 90 s while the service had `mInputShown=true` and the panel had no height.
     */
    fun imeShown(): Boolean = shell("dumpsys input_method").contains("mInputShown=true")

    /**
     * Brief P6c: one attempt to close the soft keyboard the module search opened. On the emulator's 320 x 640 screen the
     * keyboard leaves the panel no height and the module list composes no row (P5cTapTest.t3 failed on CI that way in runs
     * 45723595363 and 45775913088: the tree dump showed the search field focused, every panel node 0 px high, the
     * keyboard's 235 px missing from the layout). hideSoftInputFromWindow alone sometimes does not close it, so each
     * attempt also takes the focus off the field and asks the window's insets controller; every 10th attempt, while the
     * input method service still says the keyboard is shown, the Back key (which closes a shown keyboard before
     * anything else) is sent. Called for as long as the module's row is missing, not gated on a keyboard check (the
     * window insets did not report the keyboard in run 45799493184, t4).
     */
    fun closeKeyboard(sc: androidx.test.core.app.ActivityScenario<*>?, attempt: Int) {
        sc?.onActivity {
            it.currentFocus?.clearFocus()
            it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
            if (android.os.Build.VERSION.SDK_INT >= 30) it.window.decorView.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        if (attempt >= 10 && attempt % 10 == 0 && imeShown()) instr.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }

    /** What the input method service says about the keyboard, for the results file when a wait timed out. */
    fun imeDiagnosis(): String = runCatching { shell("dumpsys input_method").lineSequence()
        .filter { l -> listOf("mInputShown", "mImeWindowVis", "mCurFocusedWindow", "mShowRequested", "mCurId").any { l.contains(it) } }
        .joinToString(" | ") { it.trim() } }.getOrElse { "dumpsys failed: ${it.message}" }

    fun md5(f: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 16)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

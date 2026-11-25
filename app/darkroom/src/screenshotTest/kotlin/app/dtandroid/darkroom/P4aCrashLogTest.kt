// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dtandroid.darkroom.robust.CrashLog
import app.dtandroid.darkroom.robust.ExitReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.File

/**
 * Brief P4a deliverable 4 and 5: the crash log. An uncaught exception ends up in the log before the thread dies; exits
 * recorded by Android (a native crash, an ANR, a low-memory kill; simulated here) are added once on the next start;
 * the cap keeps the newest entries; a line cut off by a dying process is skipped; bridge errors are recognised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P4aCrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun log(maxEntries: Int = 200, maxBytes: Long = 1L shl 20) = CrashLog(File(tmp.root, "logs/crash-log.jsonl"), "0.4-test", maxEntries, maxBytes)

    @Test fun anUncaughtExceptionIsWrittenBeforeTheThreadDies() {
        val l = log()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var handedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn = e }
        try {
            l.install()
            val t = Thread({ throw IllegalStateException("boom in a test") }, "p4a-test-thread")
            t.start(); t.join()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
        val e = l.entries().single()
        assertEquals(CrashLog.KIND_CRASH, e.kind)
        assertEquals("0.4-test", e.appVersion)
        assertTrue(e.text, e.text.startsWith("thread \"p4a-test-thread\""))
        assertTrue(e.text, "java.lang.IllegalStateException: boom in a test" in e.text)
        assertTrue("stack kept", "at " in e.text)
        // Android's handler (here the one before) still gets the exception, so the process ends as before
        assertTrue(handedOn is IllegalStateException)
    }

    @Test fun capKeepsTheNewestEntries() {
        val l = log(maxEntries = 200)
        repeat(230) { l.append("crash", "entry $it", time = 1_000L + it) }
        val all = l.entries()
        assertEquals(200, all.size)
        assertEquals("entry 30", all.first().text)
        assertEquals("entry 229", all.last().text)
        assertEquals(200, l.count.value)
        // bytes: 1 MB in the app; here 4 kB with 1 kB entries -> 3 or 4 kept
        val b = CrashLog(File(tmp.root, "b.jsonl"), "", 200, 4096)
        repeat(10) { b.append("crash", "x".repeat(1000) + it) }
        val kept = b.entries()
        assertTrue("${kept.size}", kept.size in 3..4)
        assertTrue(b.file.length() <= 4096)
        assertTrue(kept.last().text.endsWith("9"))
    }

    @Test fun aCutOffLineIsSkippedAndTextIsEscaped() {
        val l = log()
        l.append("crash", "first \"quoted\"\n\ttab \\ back\u0001")
        l.file.appendText("{\"t\":5,\"k\":\"crash\",\"x\":\"half writ") // the process died in the middle of a line
        l.appendNow("crash", "third")
        val e = l.entries()
        assertEquals(listOf("first \"quoted\"\n\ttab \\ back\u0001", "third"), e.map { it.text })
    }

    @Test fun clearEmptiesAndCopyIsNewestFirst() {
        val l = log()
        l.append("crash", "older", 1_000)
        l.append(CrashLog.KIND_ANR, "newer", 2_000)
        val text = l.asText()
        assertTrue(text, text.indexOf("newer") < text.indexOf("older"))
        assertTrue(text, text.startsWith("1970-01-01 00:00:02 UTC · not responding (ANR) · 0.4-test"))
        l.clear()
        assertTrue(l.entries().isEmpty())
        assertEquals("crash log: empty\n", l.asText())
    }

    @Test fun bridgeMessagesAreRecognised() {
        assertTrue(CrashLog.isBridgeMessage("IO: cannot decode /x/IMG_1.RAF"))
        assertTrue(CrashLog.isBridgeMessage("ENGINE: out of memory"))
        assertFalse(CrashLog.isBridgeMessage("photo saved"))
        assertFalse(CrashLog.isBridgeMessage(null))
    }

    @Test fun simulatedExitsAreAddedOnceOnTheNextStart() {
        val l = log()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val am = shadowOf(ctx.getSystemService(ActivityManager::class.java))
        val tomb = byteArrayOf(0x0a, 0x12) + "Abort message: 'dt_iop_x: bad'".toByteArray() + byteArrayOf(0, 1, 2) + "libdarktable.so (dt_dev_pixelpipe_process+120)".toByteArray()
        val anr = ("----- pid 1 -----\n\"main\" prio=5 tid=1 Blocked\n  at app.dtandroid.Foo.wait(Foo.kt:3)\n\n" +
            "\"other\" prio=5 tid=2 Runnable\n  at x.y\n").toByteArray()
        am.addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setTimestamp(10_000).setReason(ExitReasons.REASON_CRASH_NATIVE)
            .setStatus(6).setDescription("crash").setPss(800 * 1024).setRss(900 * 1024).setImportance(100).setTraceInputStream(tomb.inputStream()).build())
        am.addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setTimestamp(20_000).setReason(ExitReasons.REASON_ANR)
            .setDescription("user request after error: Input dispatching timed out").setImportance(100).setTraceInputStream(anr.inputStream()).build())
        am.addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setTimestamp(30_000).setReason(ExitReasons.REASON_LOW_MEMORY)
            .setImportance(400).setPss(2_000 * 1024).build())
        // the user swiped the app away: not a fault, not logged
        am.addApplicationExitInfo(ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setTimestamp(40_000).setReason(10 /* USER_REQUESTED */).build())

        assertEquals(3, ExitReasons.collect(ctx, l))
        val e = l.entries()
        assertEquals(listOf(CrashLog.KIND_NATIVE, CrashLog.KIND_ANR, CrashLog.KIND_LOW_MEMORY), e.map { it.kind })
        assertEquals(listOf(10_000L, 20_000L, 30_000L), e.map { it.time })
        assertTrue(e[0].text, "signal 6 (SIGABRT)" in e[0].text)
        assertTrue(e[0].text, "Abort message: 'dt_iop_x: bad'" in e[0].text && "dt_dev_pixelpipe_process" in e[0].text)
        assertTrue(e[0].text, "800 MB (PSS)" in e[0].text && "on the screen" in e[0].text)
        assertTrue(e[1].text, "\"main\" prio=5 tid=1 Blocked" in e[1].text && "\"other\"" !in e[1].text)
        assertTrue(e[2].text, "in the background" in e[2].text)
        // the next start reads the same records again: nothing is added twice
        assertEquals(0, ExitReasons.collect(ctx, l))
        assertEquals(3, l.entries().size)
    }

    @Test fun aJavaCrashThatTheHandlerWroteIsNotAddedTwice() {
        val l = log()
        l.append(CrashLog.KIND_CRASH, "thread \"main\"\njava.lang.NullPointerException", 50_000)
        val seen = File(tmp.root, "seen")
        val crash = ExitReasons.Exit(50_400, ExitReasons.REASON_CRASH, 0, "crash", 0, 0, 100, null)
        val other = ExitReasons.Exit(90_000, ExitReasons.REASON_CRASH, 0, "crash", 0, 0, 100, null)
        assertEquals(1, ExitReasons.addNew(listOf(crash, other), l, seen))
        assertEquals(listOf(50_000L, 90_000L), l.entries().map { it.time })
    }
}

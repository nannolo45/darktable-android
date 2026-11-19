// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import app.dtandroid.darkroom.Timeline
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Brief P2f: a time limit per test (run 44596781567's emulator job ran for 105 minutes without an end), and on
 * any failure the stack of every thread of the app and the darkroom's timeline in
 * files/p2b-test/failure-<test>.txt, so a test that waits forever says where.
 */
object HangGuard {
    fun rule(minutes: Long): TestRule = RuleChain
        .outerRule(object : TestWatcher() {
            override fun failed(e: Throwable, d: Description) {
                runCatching {
                    val sb = StringBuilder("${d.displayName}: $e\n\n## timeline\n${Timeline.text()}\n\n## threads\n")
                    for ((t, st) in Thread.getAllStackTraces()) {
                        sb.append("\"${t.name}\" ${t.state}\n")
                        st.take(40).forEach { sb.append("    at ").append(it).append('\n') }
                    }
                    File(TestFiles.out, "failure-${d.methodName}.txt").writeText(sb.toString())
                }
            }
        })
        .around(Timeout.builder().withTimeout(minutes, TimeUnit.MINUTES).withLookingForStuckThread(true).build())
}

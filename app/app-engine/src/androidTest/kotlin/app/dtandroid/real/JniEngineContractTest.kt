// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dtandroid.engine.api.DarktableEngine
import app.dtandroid.engine.api.DarktableEngineContract
import org.junit.runner.RunWith
import java.io.File

/**
 * The engine contract suite (engine-api's test fixtures, the same class the fake engine passes on
 * the JVM) against the real engine over JNI, on a public test RAW (brief P2b, deliverable 2).
 *
 * Only the lifecycle differs: darktable starts once per process (as in the app), so "init" is the
 * app's start and "shutdown" closes the session and restores the app's thread settings.
 */
@RunWith(AndroidJUnit4::class)
class JniEngineContractTest : DarktableEngineContract() {
    override fun newEngine(): DarktableEngine = TestFiles.engine()

    override suspend fun startEngine(e: DarktableEngine) {
        runCatching { e.close() }
    }

    override suspend fun stopEngine(e: DarktableEngine) {
        runCatching { e.close() }
        val cores = (AppEngine.status.value as AppEngine.Status.Ready).cores
        e.setThreads(if (cores.pin) cores.chosen.size else 0)
        e.setThreadCpus(if (cores.pin) cores.chosen else emptyList())
    }

    override fun sampleRaw(): String = TestFiles.raw.path

    override fun writeXmpWithUnknownModule(file: File) = TestFiles.writeXmpWithUnknownModule(file)
}

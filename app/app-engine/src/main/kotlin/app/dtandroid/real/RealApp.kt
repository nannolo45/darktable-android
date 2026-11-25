// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Application
import app.dtandroid.darkroom.robust.CrashLog
import app.dtandroid.darkroom.robust.ExitReasons
import kotlinx.coroutines.launch

/** Starts the engine as soon as the process starts, so unpacking overlaps with the first screen (also when the background sync job starts the process). */
class RealApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppHost.init(this)
        // brief P4a: uncaught exceptions are written to the crash log before the process dies; how the last runs
        // ended (native crashes, ANRs, low-memory kills) is read from Android and added, off the main thread
        AppHost.crashLog.install()
        Thread({ ExitReasons.collect(this, AppHost.crashLog) }, "dta-exit-reasons").start()
        AppEngine.start(this)
        // brief P4a: a failed engine start is logged too (the start page says it; the log keeps it)
        AppEngine.scope.launch {
            AppEngine.status.collect { st -> if (st is AppEngine.Status.Failed) AppHost.crashLog.append(CrashLog.KIND_START, st.text) }
        }
    }
}

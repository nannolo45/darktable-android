// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Application

/** Starts the engine as soon as the process starts, so unpacking overlaps with the first screen (also when the background sync job starts the process). */
class RealApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppHost.init(this)
        AppEngine.start(this)
    }
}

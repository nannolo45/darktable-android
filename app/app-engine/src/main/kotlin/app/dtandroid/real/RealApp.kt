// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Application

/** Starts the engine as soon as the process starts, so unpacking overlaps with the first screen. */
class RealApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppEngine.start(this)
    }
}

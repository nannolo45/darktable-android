// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The background job of brief P2e item 5: when edits are waiting to sync, Android runs it once a network
 * is available (and again, with back-off, while something is left). It only syncs: no heartbeat, no
 * polling, no downloads. Android's JobScheduler (part of the platform; no library), kept across restarts
 * of the phone.
 */
class SyncJob : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        AppHost.init(application)
        running = AppEngine.scope.launch {
            val done = runCatching { AppHost.agent.syncPending() }.getOrDefault(false)
            jobFinished(params, !done)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    companion object {
        private const val ID = 4201

        /** Ask Android to run the job when a network is available. Scheduling it again replaces the earlier request. */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(ID, ComponentName(ctx, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(info) }
        }
    }
}

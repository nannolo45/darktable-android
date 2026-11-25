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
 * The background job of brief P2e item 5 (brief P2i: only while "sync automatically" is on): when edits are waiting to sync, Android runs it once a network
 * is available (and again, with back-off, while something is left). It only syncs: no heartbeat, no
 * polling, no downloads. Android's JobScheduler (part of the platform; no library), kept across restarts
 * of the phone.
 */
class SyncJob : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        AppHost.init(application)
        running = AppEngine.scope.launch {
            // brief P2i: with automatic syncing off the job sends nothing (and is not scheduled again)
            val done = runCatching { AppHost.agent.syncPendingAutomatically() ?: true }.getOrDefault(false)
            // brief P3l: shared styles, presets and layouts with the same pass (it sends nothing while automatic sync is off)
            runCatching { AppHost.agent.shared.passAll() }
            // brief P3i: exports that waited for their server go out with the sync (asked for by the user, so also with automatic syncing off)
            val exported = runCatching { AppHost.agent.sendQueuedExports(); AppHost.agent.queuedExports() == 0 }.getOrDefault(false)
            jobFinished(params, !(done && exported))
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    companion object {
        private const val ID = 4201
        /** brief P4a: the same job, kept apart, as a safety net while the sync service runs */
        private const val SAFETY_ID = 4202
        /** how long after the service started the safety net may run (only matters if the process died) */
        private const val SAFETY_DELAY_MS = 10 * 60_000L

        /**
         * Brief P4a: while the sync service runs, a second request for this job that Android only runs after
         * [SAFETY_DELAY_MS] and with a network: a process killed in the middle of an upload or a download then still has
         * its work taken up (sync, queued exports) without the user opening the app. The service cancels it when it ends
         * with nothing left. Running it while the work is already done costs one look at the queue.
         */
        fun scheduleSafetyNet(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(SAFETY_ID, ComponentName(ctx, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(SAFETY_DELAY_MS)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(info) }
        }

        fun cancelSafetyNet(ctx: Context) {
            runCatching { ctx.getSystemService(JobScheduler::class.java)?.cancel(SAFETY_ID) }
        }

        /** Ask Android to run the job when a network is available. Scheduling it again replaces the earlier request. */
        fun schedule(ctx: Context) {
            // brief P2i: the background job is the app syncing by itself: not while automatic syncing is off
            AppHost.init(ctx.applicationContext as android.app.Application)
            if (!AppHost.agent.autoSync && AppHost.agent.queuedExports() == 0) return
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

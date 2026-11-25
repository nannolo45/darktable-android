// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.real

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.dtandroid.lpl.BackgroundSync
import app.dtandroid.lpl.SendCounter
import app.dtandroid.lpl.SyncNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Brief P3k (PLAN.md 3.7): LightPhotoLibrary transfers go on when the app leaves the screen. A foreground service of
 * the `dataSync` type that runs only while there is LPL work in flight: edits waiting to be sent (XMP, preview,
 * versions), exports waiting for their server, batch downloads already started. While it runs it heartbeats every
 * 2 s (PLAN.md 4.1) unless the app's own loop does; jobs handed out then are dealt with as in [AppHost.background]
 * (sync and release of photos not open now, the rest kept for the app). It never polls for new work: it stops when
 * nothing is left ([BackgroundSync.Next.STOP_DONE]) or nothing can progress ([BackgroundSync.Next.STOP_HANDOVER]:
 * then the background job [SyncJob] retries when a network is there), with a last heartbeat.
 *
 * The transfers themselves run in the process's scope (the agent's own code); this service keeps the process alive,
 * starts the sync passes and shows one notification. Its own channel and id: an export keeps [ExportService]'s
 * notification, both can show at once and neither replaces the other.
 */
class SyncService : Service() {
    private var run: Job? = null
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppHost.init(application)
        if (intent?.action == ACTION_STOP) AppHost.agent.stopBatches()  // the same as the list's "stop" (P2k)
        channel(this)
        val started = runCatching {
            startForeground(NOTIFICATION_ID, build(this, SyncNotice.of(null, 0, 0, 0, null)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        if (started.isFailure) {
            android.util.Log.w(TAG, "sync service could not go to the foreground: ${started.exceptionOrNull()}")
            SyncJob.schedule(this)
            stopSelf()
            return START_NOT_STICKY
        }
        lastStartId = startId
        if (run?.isActive != true) run = AppEngine.scope.launch { loop() }
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        val nm = getSystemService(NotificationManager::class.java)
        val counter = SendCounter()
        var pass: Job? = null
        var leftBefore = 0
        var attempted = false
        var stalled = 0
        var passWasRunning = false
        var beats = 0
        var lastPass = -PASS_BEATS
        trace("started")
        while (true) {
            AppHost.serviceHeartbeat()
            val w0 = AppHost.syncWork()
            if (w0.autoSync) counter.update(AppHost.waitingClosed())
            // one sync pass at a time, at most every ~10 s (as the foreground loop looks at queued exports)
            if (pass?.isActive != true && beats - lastPass >= PASS_BEATS && ((w0.autoSync && w0.editsClosed > 0) || w0.exports > 0)) {
                attempted = true
                lastPass = beats
                leftBefore = w0.editsClosed + w0.exports
                pass = AppEngine.scope.launch {
                    runCatching { AppHost.agent.syncPendingAutomatically() }
                    if (AppHost.agent.queuedExports() > 0) runCatching { AppHost.agent.sendQueuedExports() }
                    // brief P3l: the sync pass also sends shared styles, presets and layouts changed on this phone
                    runCatching { AppHost.agent.shared.passAll() }
                }
                passWasRunning = true
            }
            beats++
            val steps = AppHost.agent.steps.value.values
            // brief P4a: say so when the connection is gone or a call is being repeated
            val link = app.dtandroid.lpl.LinkWords.notice(AppHost.waitingServerUnreachable(), AppHost.agent.retrying.value.isNotEmpty(), AppHost.online.value)
            val notice = SyncNotice.of(SyncNotice.sum(AppHost.agent.batches.value.values), counter.total, counter.done, w0.exports,
                steps.firstOrNull { it.progress != null }?.progress, link)
            titles += notice.title
            nm.notify(NOTIFICATION_ID, build(this, notice))
            delay(AppHost.HEARTBEAT_MS)
            val passRunning = pass?.isActive == true
            if (passWasRunning && !passRunning) {
                // a pass ended: did it move anything?
                val now = AppHost.syncWork()
                stalled = if (now.editsClosed + now.exports < leftBefore) 0 else stalled + 1
            }
            passWasRunning = passRunning
            val w = AppHost.syncWork().let {
                it.copy(
                    inFlight = it.inFlight || passRunning,
                    unreachable = attempted && !passRunning && AppHost.waitingServerUnreachable(),
                    stalledPasses = stalled,
                )
            }
            when (val next = BackgroundSync.next(w)) {
                BackgroundSync.Next.RUN -> continue
                else -> {
                    trace("stopping: $next ($w)")
                    lastStop = next.name
                    // a last heartbeat, so LPL sees the final states
                    runCatching { AppHost.serviceHeartbeat() }
                    if (next == BackgroundSync.Next.STOP_HANDOVER) SyncJob.schedule(this)
                    // only if no start came in meanwhile (onPause, "done", a batch): that one's work is looked at again;
                    // the notification goes with the service
                    if (stopSelfResult(lastStartId)) return
                    trace("a new start came in: going on")
                    stalled = 0; attempted = false
                }
            }
        }
    }

    /** Android 15 ends a dataSync service after its daily time limit: hand the rest to the background job. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        run?.cancel()
        lastStop = "TIMEOUT"
        SyncJob.schedule(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        run?.cancel()
        running = false
        trace("stopped")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "lpl-sync"
        const val NOTIFICATION_ID = 4302
        const val ACTION_STOP = "app.dtandroid.real.SYNC_STOP_DOWNLOADS"
        private const val TAG = "dt-android"
        private const val PASS_BEATS = 5

        /** Whether the service runs now, how often it was created, why it last stopped, its notification titles (tests, trace). */
        @Volatile var running = false
            private set
        val created = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var lastStop: String? = null
            private set
        val titles: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

        private fun trace(s: String) = android.util.Log.i(TAG, "lpl sync service: $s")

        fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "LightPhotoLibrary sync", NotificationManager.IMPORTANCE_LOW))
        }

        fun build(ctx: Context, n: SyncNotice): Notification {
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(ctx.applicationInfo.icon)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, ((n.fraction ?: 0f) * 100).toInt(), n.fraction == null)
            if (n.canStop) {
                val stop = PendingIntent.getService(ctx, 2, Intent(ctx, SyncService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, ctx.applicationInfo.icon), "Stop", stop).build())
            }
            return b.build()
        }

        /**
         * Ask Android to start the service. Allowed while an activity of the app is visible; from the background
         * Android 12 and later refuse it (ForegroundServiceStartNotAllowedException): then the background job takes
         * the waiting syncs and false is returned.
         */
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, SyncService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31) is an IllegalStateException; minSdk is 29
            android.util.Log.w(TAG, "sync service not started (app in the background): $e")
            SyncJob.schedule(ctx)
            false
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "sync service not started: $e")
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        created.incrementAndGet()
    }
}

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
import app.dtandroid.darkroom.ExportJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Brief P3i: keeps the process alive while an export runs, so it goes on with the app in the background or the
 * screen off. A foreground service of the `dataSync` type with an Android notification: "photo n of N", a progress
 * bar and a cancel action. The export itself runs in the darkroom controller's scope (the process's), not here; this
 * service only shows it and stops when the job is over. Started by [AppHost] when a job starts running.
 */
class ExportService : Service() {
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppHost.init(application)
        val exports = AppHost.controller.exports
        if (intent?.action == ACTION_CANCEL) exports.cancel()
        channel(this)
        startForeground(NOTIFICATION_ID, build(this, exports.job.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watch == null) {
            watch = AppEngine.scope.launch {
                exports.job.collect { j ->
                    if (j == null || !j.running) {
                        // the end stays as an ordinary notification
                        stopForeground(STOP_FOREGROUND_DETACH)
                        if (j != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(this@ExportService, j))
                        else getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                        stopSelf()
                        watch?.cancel()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(this@ExportService, j))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 stops a dataSync service after its time limit: the export goes on in the process, the notification stays. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        watch?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "export"
        const val NOTIFICATION_ID = 4301
        const val ACTION_CANCEL = "app.dtandroid.real.EXPORT_CANCEL"

        fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Export", NotificationManager.IMPORTANCE_LOW))
        }

        /** The notification of [job] (null = just started): see [ExportJob.notice]. */
        fun build(ctx: Context, job: ExportJob?): Notification {
            val n = job?.notice
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(ctx.applicationInfo.icon)
                .setContentTitle(n?.title ?: "Exporting")
                .setContentText(n?.text ?: "")
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(n?.ongoing ?: true)
                .setAutoCancel(n?.ongoing == false)
            if (n == null || n.ongoing) {
                b.setProgress(100, n?.percent ?: 0, n == null)
                val cancel = PendingIntent.getService(ctx, 1, Intent(ctx, ExportService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, ctx.applicationInfo.icon), "Cancel", cancel).build())
            }
            return b.build()
        }

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, ExportService::class.java)) }
                .onFailure { android.util.Log.w("dt-android", "export service not started: $it") }
        }
    }
}

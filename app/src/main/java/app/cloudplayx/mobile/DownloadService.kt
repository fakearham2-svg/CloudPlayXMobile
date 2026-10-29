package app.cloudplayx.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the process alive and shows a progress notification while downloads/extractions run, so Android does not
 * freeze them when the app is in the background. It owns no transfer logic: that is [Jobs.center].
 * The service stops itself as soon as nothing is queued or running.
 */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 42

        fun ensureRunning(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
            } catch (e: Exception) {
                // Android may refuse a foreground service start from the background. Transfers still run
                // while the process is alive; they just have no notification.
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { main.post { refresh() } }
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads and transfers", NotificationManager.IMPORTANCE_LOW))
        Jobs.center.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Jobs.center.removeListener(listener)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground() {
        val n = build(Jobs.center.snapshot().filter { it.isActive })
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, n)
            foreground = true
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun refresh() {
        val active = Jobs.center.snapshot().filter { it.isActive }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(active))
    }

    private fun build(active: List<Job>): Notification {
        val running = active.filter { it.state == JobState.RUNNING }
        val total = running.sumOf { it.total }
        val done = running.sumOf { it.done }
        val pct = Fmt.percent(done, total)
        val open = PendingIntent.getActivity(this, 0, Intent(this, LibraryActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            active.isEmpty() -> "Finishing…"
            else -> "${active.size} transfer(s)" + (if (total > 0) " · $pct%" else "") +
                (running.firstOrNull()?.let { " · ${it.title}" } ?: "")
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("CloudPlayX")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, pct, total <= 0)
            .build()
    }
}

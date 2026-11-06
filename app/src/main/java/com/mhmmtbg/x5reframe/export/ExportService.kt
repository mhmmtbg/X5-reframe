package com.mhmmtbg.x5reframe.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mhmmtbg.x5reframe.MainActivity
import com.mhmmtbg.x5reframe.R

/** Dışa aktarım sürerken uygulamayı ayakta tutan ön plan hizmeti ve bildirimleri. */
class ExportService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        val n = progressNotification(this, "Dışa aktarım hazırlanıyor", 0f, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_PROGRESS, n)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "x5reframe:export").apply { acquire(4 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) ExportManager.cancelAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2
        private const val ACTION_CANCEL = "cancel"
        private var lastUpdate = 0L

        private fun channel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dışa aktarım", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun openApp(c: Context) = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun progressNotification(c: Context, title: String, fraction: Float, queued: Int): Notification {
            val cancel = PendingIntent.getService(
                c, 1, Intent(c, ExportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText("%${(fraction * 100).toInt()}" + if (queued > 0) " · sırada $queued iş" else "")
                .setProgress(1000, (fraction * 1000).toInt(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(c))
                .addAction(Notification.Action.Builder(null, "İptal", cancel).build())
                .build()
        }

        fun update(c: Context, title: String, fraction: Float, queued: Int) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 700) return
            lastUpdate = now
            c.getSystemService(NotificationManager::class.java).notify(ID_PROGRESS, progressNotification(c, "Dışa aktarılıyor: $title", fraction, queued))
        }

        fun finished(c: Context, title: String, uri: Uri?, ok: Boolean, text: String) {
            channel(c)
            val b = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (ok) "Hazır: $title" else "Dışa aktarılamadı: $title")
                .setContentText(text)
                .setAutoCancel(true)
            if (uri != null) {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                b.setContentIntent(PendingIntent.getActivity(c, 2, view, PendingIntent.FLAG_IMMUTABLE))
            } else b.setContentIntent(openApp(c))
            runCatching { c.getSystemService(NotificationManager::class.java).notify(ID_DONE + (System.currentTimeMillis() % 1000).toInt(), b.build()) }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ExportService::class.java))
        }
    }
}

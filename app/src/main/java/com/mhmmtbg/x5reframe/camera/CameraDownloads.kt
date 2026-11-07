package com.mhmmtbg.x5reframe.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.mhmmtbg.x5reframe.R

/** İndirme kuyruğu: kameradan albüm klasörüne (SD kart klasörü olarak seçilen yer). */
object CameraDownloads {
    enum class Status { WAITING, RUNNING, DONE, FAILED, CANCELLED }

    data class Item(val file: CamFile, val status: Status = Status.WAITING, val bytes: Long = 0, val error: String? = null)

    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    /** Anlık hız (MB/sn) */
    var speedMBs by mutableStateOf(0f)
        private set

    @Volatile internal var cancelAll = false

    val active get() = items.any { it.status == Status.WAITING || it.status == Status.RUNNING }

    fun enqueue(context: Context, files: List<CamFile>) {
        val have = items.filter { it.status == Status.WAITING || it.status == Status.RUNNING }.map { it.file.url }.toSet()
        val add = files.filter { it.url !in have }.map { Item(it) }
        if (add.isEmpty()) return
        items = items.filter { it.status == Status.WAITING || it.status == Status.RUNNING } + add
        cancelAll = false
        context.startForegroundService(Intent(context, CameraDownloadService::class.java))
    }

    fun cancel() { cancelAll = true }

    internal fun update(url: String, f: (Item) -> Item) { items = items.map { if (it.file.url == url) f(it) else it } }
    internal fun setSpeed(v: Float) { speedMBs = v }

    /** İndirilecek klasör: albüm için seçilen klasör (yazma izni gerekir). */
    fun target(context: Context): DocumentFile? =
        com.mhmmtbg.x5reframe.project.LrvFinder.savedFolder(context)?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.canWrite() }

    /** Dosya klasörde tam olarak var mı (boyutu aynı). */
    fun exists(dir: DocumentFile?, f: CamFile): Boolean =
        dir?.findFile(f.name)?.let { it.length() == f.size || f.size <= 0 } ?: false
}

/** İndirmeler sürerken uygulamayı ayakta tutar; telefon kilitlense de aktarım sürer. */
class CameraDownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kameradan aktarım", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, note("Kameradan aktarım hazırlanıyor", 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "x5reframe:camera")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) CameraDownloads.cancel()
        if (worker?.isAlive != true) worker = Thread(::run, "camera-download").also { it.start() }
        return START_NOT_STICKY
    }

    private fun run() {
        val dir = CameraDownloads.target(this)
        while (!CameraDownloads.cancelAll) {
            val item = CameraDownloads.items.firstOrNull { it.status == CameraDownloads.Status.WAITING } ?: break
            val f = item.file
            CameraDownloads.update(f.url) { it.copy(status = CameraDownloads.Status.RUNNING, error = null) }
            try {
                if (dir == null) throw OscException("Albüm klasörü seçilmedi ya da yazılamıyor")
                download(dir, f)
                CameraDownloads.update(f.url) { it.copy(status = CameraDownloads.Status.DONE, bytes = f.size) }
            } catch (e: InterruptedException) {
                CameraDownloads.update(f.url) { it.copy(status = CameraDownloads.Status.CANCELLED) }
            } catch (e: Throwable) {
                CameraDownloads.update(f.url) { it.copy(status = CameraDownloads.Status.FAILED, error = e.message ?: e.javaClass.simpleName) }
            }
        }
        if (CameraDownloads.cancelAll) {
            CameraDownloads.items.filter { it.status == CameraDownloads.Status.WAITING }.forEach { w ->
                CameraDownloads.update(w.file.url) { it.copy(status = CameraDownloads.Status.CANCELLED) }
            }
        }
        val done = CameraDownloads.items.count { it.status == CameraDownloads.Status.DONE }
        val failed = CameraDownloads.items.count { it.status == CameraDownloads.Status.FAILED }
        getSystemService(NotificationManager::class.java).notify(ID_DONE, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("Kameradan aktarım bitti")
            .setContentText("$done dosya indirildi" + if (failed > 0) " · $failed hata" else "")
            .setContentIntent(openApp()).setAutoCancel(true).build())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun download(dir: DocumentFile, f: CamFile) {
        if (CameraDownloads.exists(dir, f)) return
        val partName = f.name + ".part"
        var part = dir.findFile(partName) ?: dir.createFile("application/octet-stream", partName)
            ?: throw OscException("Dosya oluşturulamadı")
        var offset = part.length()
        val client = CameraLink.client()
        var attempts = 0
        var t0 = System.currentTimeMillis(); var b0 = offset
        while (true) {
            try {
                client.download(f.url, offset, { append ->
                    contentResolver.openOutputStream(part.uri, if (append) "wa" else "wt") ?: throw OscException("Yazılamıyor")
                }, { CameraDownloads.cancelAll }) { bytes ->
                    CameraDownloads.update(f.url) { it.copy(bytes = bytes) }
                    val now = System.currentTimeMillis()
                    if (now - t0 > 1000) {
                        CameraDownloads.setSpeed((bytes - b0) / 1e6f / ((now - t0) / 1000f)); t0 = now; b0 = bytes
                        val i = CameraDownloads.items.indexOfFirst { it.file.url == f.url } + 1
                        val n = CameraDownloads.items.size
                        getSystemService(NotificationManager::class.java).notify(ID,
                            note("$i/$n · ${f.name}", if (f.size > 0) bytes.toFloat() / f.size else 0f))
                    }
                }
                break
            } catch (e: InterruptedException) {
                throw e
            } catch (e: java.io.IOException) {
                // Wi-Fi kopmaları: kaldığı yerden birkaç kez yeniden dene
                if (++attempts > 5) throw e
                Thread.sleep(2000L * attempts)
                part = dir.findFile(partName) ?: part
                offset = part.length()
            }
        }
        if (f.size > 0 && part.length() != f.size) throw OscException("Eksik indirildi (${part.length()} / ${f.size})")
        dir.findFile(f.name)?.delete()
        if (!part.renameTo(f.name)) throw OscException("Yeniden adlandırılamadı")
    }

    private fun openApp() = PendingIntent.getActivity(this, 0,
        Intent(this, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun note(title: String, fraction: Float): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, CameraDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title)
            .setContentText("%${(fraction * 100).toInt()} · ${"%.1f".format(CameraDownloads.speedMBs)} MB/sn")
            .setProgress(1000, (fraction * 1000).toInt(), false).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp()).addAction(Notification.Action.Builder(null, "İptal", cancel).build()).build()
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "camera"
        private const val ID = 21
        private const val ID_DONE = 22
        private const val ACTION_CANCEL = "cancel"
    }
}

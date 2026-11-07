package com.mhmmtbg.x5reframe.media

import android.content.Context
import android.net.Uri
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.insv.ClipKind
import com.mhmmtbg.x5reframe.model.ViewParams
import java.io.File
import java.security.MessageDigest

/**
 * Önizleme kopyaları (proxy): 4K / HDR gibi ağır normal videolar için uzun kenarı 960 px, SDR, H.264
 * hafif bir kopya. Düzenleyici bununla akıcı oynar; dışa aktarım her zaman asıl dosyadan yapılır.
 */
object Proxies {
    const val LONG_SIDE = 960

    private fun dir(context: Context) = File(context.filesDir, "proxy").apply { mkdirs() }

    fun fileFor(context: Context, uri: Uri): File {
        val h = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(dir(context), "$h.mp4")
    }

    /** Bu klip için önizleme kopyası gerekli mi (yalnızca normal video). */
    fun needed(clip: ClipInfo): Boolean =
        clip.kind == ClipKind.FLAT && (maxOf(clip.width, clip.height) > 2200 || clip.hdrTransfer != 0 || clip.frameRate > 61f)

    fun enabled(context: Context) = context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("proxies", true)

    /** Hazır önizleme kopyası (yoksa null). */
    fun ready(context: Context, clip: ClipInfo): ClipInfo? {
        val f = fileFor(context, clip.uri)
        if (!f.exists() || f.length() < 1024) return null
        return runCatching { ClipInfo.load(context, Uri.fromFile(f)) }.getOrNull()
    }

    /** Önizleme kopyasını üret (arka plan iş parçacığında). */
    fun build(context: Context, clip: ClipInfo, cancelled: () -> Boolean, progress: (Float) -> Unit): File? {
        val f = fileFor(context, clip.uri)
        val tmp = File(f.parentFile, f.name + ".part")
        val a = clip.displayAspect
        val w: Int; val h: Int
        if (a >= 1f) { w = LONG_SIDE; h = (LONG_SIDE / a).toInt() / 2 * 2 } else { h = LONG_SIDE; w = (LONG_SIDE * a).toInt() / 2 * 2 }
        var ok = false
        val done = java.util.concurrent.CountDownLatch(1)
        val ex = Exporter(context, Exporter.Output(w, h, false, 3_000_000, true, false, null, minOf(clip.frameRate, 30f), target = tmp),
            listOf(Exporter.Job(clip, 0, clip.durationUs, { listOf(ViewParams()) })), object : Exporter.Listener {
                override fun onProgress(fraction: Float, speed: Float) = progress(fraction)
                override fun onDone(uri: Uri, seconds: Float) { ok = true; done.countDown() }
                override fun onFailed(message: String) { done.countDown() }
            })
        val watcher = Thread { while (done.count > 0) { if (cancelled()) ex.cancelled = true; Thread.sleep(200) } }.apply { isDaemon = true; start() }
        ex.run()
        watcher.interrupt()
        if (!ok) { tmp.delete(); return null }
        tmp.renameTo(f)
        return f
    }

    fun clear(context: Context): Long {
        val files = dir(context).listFiles().orEmpty()
        val n = files.sumOf { it.length() }
        files.forEach { it.delete() }
        return n
    }
}

package com.mhmmtbg.x5reframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Network
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Kameradaki çekimlerin küçük resimleri ve süreleri. Dosya indirilmez: videoda LRV'nin (yoksa asıl dosyanın)
 * yalnızca baş/son parçaları HTTP aralık istekleriyle okunur, fotoğrafta EXIF küçük resmi alınır.
 * Balık gözü görüntü düz bir görünüme çevrilir. Sonuçlar diskte saklanır.
 */
class CamThumbs(private val context: Context, private val network: Network?) {
    class Info(val bitmap: Bitmap?, val durationMs: Long)

    val loaded = mutableStateMapOf<String, Info>()
    private val pool = Executors.newFixedThreadPool(3)
    private val queued = HashSet<String>()
    private val dir = File(context.cacheDir, "camthumbs").apply { mkdirs() }

    private fun key(f: CamFile) = f.name + "_" + f.size

    /** Küçük resmi iste (önbellekte yoksa arka planda hazırlanır). */
    fun request(f: CamFile, lrv: CamFile?) {
        val k = key(f)
        synchronized(queued) { if (loaded.containsKey(k) || !queued.add(k)) return }
        pool.execute {
            val info = runCatching { load(f, lrv, k) }.getOrElse { Info(null, -1) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { loaded[k] = info }
        }
    }

    fun get(f: CamFile): Info? = loaded[key(f)]

    private fun load(f: CamFile, lrv: CamFile?, k: String): Info {
        val jpg = File(dir, "$k.jpg"); val meta = File(dir, "$k.txt")
        if (jpg.exists() && meta.exists()) return Info(BitmapFactory.decodeFile(jpg.absolutePath), meta.readText().toLongOrNull() ?: -1)
        val (raw, dur) = if (f.isPhoto) photoThumb(f) to 0L else videoFrame(lrv ?: f)
        val flat = raw?.let { b -> (if (b.width >= b.height * 1.6f) dewarp(b, b.width / 2, b.height) else centerSquare(b)).also { if (it !== b) b.recycle() } }
        flat?.let { b -> jpg.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
        meta.writeText(dur.toString())
        return Info(flat, dur)
    }

    private fun videoFrame(f: CamFile): Pair<Bitmap?, Long> {
        val src = HttpRangeSource(f.url, network, f.size)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(src)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
            val b = r.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 480)
                ?: r.getFrameAtTime(0)
            b to dur
        } finally { runCatching { r.release() }; src.close() }
    }

    private fun photoThumb(f: CamFile): Bitmap? {
        // EXIF küçük resmi dosyanın başındadır
        val head = HttpRangeSource.get(f.url, network, 0, minOf(f.size.takeIf { it > 0 } ?: Long.MAX_VALUE, 256L * 1024) - 1)
        val ex = runCatching { android.media.ExifInterface(head.inputStream()) }.getOrNull()
        ex?.thumbnailBitmap?.let { return it }
        // Küçük resim yoksa ve dosya küçükse tamamını çöz
        if (f.size in 1..12_000_000) {
            val all = HttpRangeSource.get(f.url, network, 0, f.size - 1)
            return BitmapFactory.decodeByteArray(all, 0, all.size, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
        return null
    }

    private fun centerSquare(b: Bitmap): Bitmap {
        val s = minOf(b.width, b.height)
        val c = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        return Bitmap.createScaledBitmap(c, 320, 320, true).also { if (it !== c) c.recycle() }
    }

    /**
     * Sol yarıdaki balık gözünden (eşit uzaklık, ~200°) 100° görüş açılı düz görüntü.
     */
    private fun dewarp(b: Bitmap, halfW: Int, h: Int, out: Int = 320): Bitmap {
        val src = IntArray(halfW * h)
        b.getPixels(src, 0, halfW, 0, 0, halfW, h)
        val dst = IntArray(out * out)
        val cx = halfW / 2f; val cy = h / 2f
        val radius = minOf(halfW, h) / 2f
        val half = Math.toRadians(100.0).toFloat()      // merceğin yarı görüş açısı
        val k = tan(Math.toRadians(52.0)).toFloat()
        for (y in 0 until out) for (x in 0 until out) {
            val px = (x / (out - 1f) * 2 - 1) * k
            val py = (y / (out - 1f) * 2 - 1) * k
            val n = sqrt(px * px + py * py + 1)
            val theta = kotlin.math.acos(1 / n)
            val phi = atan2(py, px)
            val r = theta / half * radius
            val sx = (cx + r * cos(phi)).toInt(); val sy = (cy + r * sin(phi)).toInt()
            dst[y * out + x] = if (sx in 0 until halfW && sy in 0 until h) src[sy * halfW + sx] else 0xFF000000.toInt()
        }
        return Bitmap.createBitmap(dst, out, out, Bitmap.Config.ARGB_8888)
    }

    fun shutdown() { pool.shutdownNow() }
}

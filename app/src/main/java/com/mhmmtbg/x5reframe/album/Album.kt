package com.mhmmtbg.x5reframe.album

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Albümdeki bir çekim. */
data class AlbumItem(
    val uri: Uri,
    val name: String,
    val lrv: Uri?,
    /** Çekim zamanı (ms) */
    val takenAt: Long,
    val sizeBytes: Long,
    val is360: Boolean,
    val isPhoto: Boolean,
)

/** Kamera klasörünü ve dışa aktarılanları tarar; küçük resimleri önbellekler. */
object Album {
    private val nameDate = Regex("""(\d{8})_(\d{6})""")
    private val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    /** Dosya adından çekim zamanı: VID_20260926_141617_00_142.insv */
    fun takenAt(name: String, fallback: Long): Long =
        nameDate.find(name)?.let { m -> runCatching { fmt.parse(m.groupValues[1] + m.groupValues[2])?.time }.getOrNull() } ?: fallback

    /** Seçilen klasörü ve iki seviye alt klasörünü (DCIM/Camera01) tara. */
    fun scanCamera(context: Context, tree: Uri): List<AlbumItem> {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        val dirs = ArrayList<DocumentFile>()
        fun walk(d: DocumentFile, depth: Int) {
            dirs += d
            if (depth > 0) runCatching { d.listFiles() }.getOrDefault(emptyArray()).filter { it.isDirectory }.forEach { walk(it, depth - 1) }
        }
        walk(root, 2)
        val out = ArrayList<AlbumItem>()
        for (d in dirs) {
            val files = runCatching { d.listFiles() }.getOrDefault(emptyArray()).filter { it.isFile }
            val byName = files.associateBy { (it.name ?: "").lowercase() }
            for (f in files) {
                val n = f.name ?: continue
                val lower = n.lowercase()
                val video = lower.endsWith(".insv")
                val photo = lower.endsWith(".insp")
                if (!video && !photo) continue
                // Çift lensli eski kayıtlarda ikinci iz (_10_) ayrı dosyadır; yalnız ilkini göster.
                if (video && lower.contains("_10_")) continue
                val lrv = if (video) byName[com.mhmmtbg.x5reframe.project.LrvFinder.lrvName(n).lowercase()]?.uri else null
                out += AlbumItem(f.uri, n, lrv, takenAt(n, f.lastModified()), f.length(), true, photo)
            }
        }
        return out.sortedByDescending { it.takenAt }
    }

    /** Uygulamanın dışa aktardığı videolar (Filmler/X5Reframe). */
    fun scanExports(context: Context): List<AlbumItem> {
        val out = ArrayList<AlbumItem>()
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE)
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("%X5Reframe%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    out += AlbumItem(uri, c.getString(1) ?: "", null, c.getLong(2) * 1000, c.getLong(3), false, false)
                }
            }
        }
        return out
    }

    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val durations = HashMap<String, Long>()

    fun cachedThumb(item: AlbumItem): Bitmap? = mem.get(item.uri.toString())
    fun duration(item: AlbumItem): Long? = synchronized(durations) { durations[item.uri.toString()] }

    /**
     * Küçük resim. 360 çekimlerde LRV'deki ön lensin merkezinden kare bir kesit alınır
     * (balık gözünün ortası neredeyse düzdür); disk önbelleğine yazılır.
     */
    fun thumbnail(context: Context, item: AlbumItem): Bitmap? {
        val key = item.uri.toString()
        mem.get(key)?.let { return it }
        val cacheFile = File(File(context.cacheDir, "thumbs").apply { mkdirs() }, "${key.hashCode()}_${item.sizeBytes}.jpg")
        val durFile = File(cacheFile.path + ".dur")
        if (cacheFile.exists()) {
            BitmapFactory.decodeFile(cacheFile.path)?.let { b ->
                mem.put(key, b)
                durFile.takeIf { it.exists() }?.readText()?.toLongOrNull()?.let { d -> synchronized(durations) { durations[key] = d } }
                return b
            }
        }
        val bmp: Bitmap? = when {
            item.isPhoto -> runCatching { photoThumb(context, item.uri) }.getOrNull()
            item.is360 -> item.lrv?.let { lrv -> frame(context, lrv, key)?.let { full -> centerOfFrontLens(full).also { if (it !== full) full.recycle() } } }
            else -> frame(context, item.uri, key)?.let { full -> scaleCrop(full, 320, 320) }
        }
        if (bmp != null) {
            mem.put(key, bmp)
            runCatching { cacheFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            duration(item)?.let { runCatching { durFile.writeText(it.toString()) } }
        }
        return bmp
    }

    private fun frame(context: Context, uri: Uri, key: String): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { synchronized(durations) { durations[key] = it * 1000 } }
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 320)
        } catch (t: Throwable) { null } finally { runCatching { r.release() } }
    }

    /** Yan yana iki balık gözü: sol yarının merkezinden kare kesit. */
    private fun centerOfFrontLens(b: Bitmap): Bitmap {
        val half = b.width / 2
        val side = (minOf(half, b.height) * 0.55f).toInt()
        val x = half / 2 - side / 2
        val y = b.height / 2 - side / 2
        val crop = Bitmap.createBitmap(b, x.coerceAtLeast(0), y.coerceAtLeast(0), side, side)
        return Bitmap.createScaledBitmap(crop, 320, 320, true).also { if (it !== crop) crop.recycle() }
    }

    private fun scaleCrop(b: Bitmap, w: Int, h: Int): Bitmap {
        val side = minOf(b.width, b.height)
        val crop = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(crop, w, h, true)
    }

    /** .insp: JPEG içinde iki balık gözü yan yana. */
    private fun photoThumb(context: Context, uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val b = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return centerOfFrontLens(b).also { if (it !== b) b.recycle() }
    }
}

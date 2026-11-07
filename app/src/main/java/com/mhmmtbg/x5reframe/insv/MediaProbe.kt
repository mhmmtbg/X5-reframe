package com.mhmmtbg.x5reframe.insv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Insta360 olmayan dosyalar: telefonun çözebildiği her video (MP4, MOV, MKV, WebM, 3GP;
 * H.264 / H.265 / VP9 / AV1) ve fotoğraflar (JPEG, PNG, WebP, HEIC).
 */
object MediaProbe {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp", "gif")

    fun isImage(context: Context, uri: Uri): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (type != null && type.startsWith("image/")) return true
        if (type != null && type.startsWith("video/")) return false
        val name = (uri.lastPathSegment ?: "").substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return name in IMAGE_EXT
    }

    fun load(context: Context, uri: Uri): ClipInfo = if (isImage(context, uri)) loadImage(context, uri) else loadVideo(context, uri)

    private fun loadImage(context: Context, uri: Uri): ClipInfo {
        var w = 0; var h = 0
        // Yalnızca başlık okunur (EXIF yönü uygulanmış boyut)
        runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                w = info.size.width; h = info.size.height
                throw StopDecode()
            }
        }
        if (w <= 0 || h <= 0) throw IllegalStateException("Fotoğraf okunamadı")
        return ClipInfo(uri, "Fotoğraf", null, emptyList(), null, w, h, ClipInfo.IMAGE_MAX_US, 30f, kind = ClipKind.IMAGE)
    }

    private class StopDecode : RuntimeException()

    /** GPU doku sınırına sığacak şekilde (uzun kenar en fazla [maxSide]) çöz; EXIF yönü uygulanır. */
    fun decodeImage(context: Context, uri: Uri, maxSide: Int = 4096): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            if (long > maxSide) {
                val s = maxSide.toFloat() / long
                d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            }
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = false
        }

    private fun loadVideo(context: Context, uri: Uri): ClipInfo {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("dosya açılamadı")
        pfd.use {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(it.fileDescriptor)
                var video = -1; var audio: Int? = null
                var vf: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && video < 0) { video = i; vf = f }
                    else if (mime.startsWith("audio/") && audio == null) audio = i
                }
                val f = vf ?: throw IllegalStateException("Dosyada video yok ya da biçim desteklenmiyor")
                val w = f.getInteger(MediaFormat.KEY_WIDTH); val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                var dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                var fps = 30f
                if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30f) }
                }
                var rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                val transfer = if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                // Kapsayıcı bazı bilgileri vermezse (ör. MKV / WebM) MediaMetadataRetriever'a sor
                if (dur <= 0 || !f.containsKey(MediaFormat.KEY_ROTATION) || !f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val r = MediaMetadataRetriever()
                    runCatching {
                        r.setDataSource(it.fileDescriptor)
                        if (dur <= 0) dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                        if (!f.containsKey(MediaFormat.KEY_ROTATION)) rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.let { c -> if (c in 1f..240f) fps = c }
                    }
                    runCatching { r.release() }
                }
                if (dur <= 0) throw IllegalStateException("Videonun süresi okunamadı")
                // Değişken kare hızlı (telefon) videolarında bildirilen değer kaba olabilir; makul aralığa sınırla.
                fps = fps.coerceIn(1f, 240f)
                val hdr = if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) transfer else 0
                return ClipInfo(uri, "Video", null, listOf(video), audio, w, h, dur, fps,
                    kind = ClipKind.FLAT, rotation = ((rotation % 360) + 360) % 360, hdrTransfer = hdr)
            } finally { ex.release() }
        }
    }
}

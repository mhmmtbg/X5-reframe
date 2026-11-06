package com.mhmmtbg.x5reframe.insv

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.FileInputStream

/** Bir .insv klibinin dışa aktarım için gereken her şeyi. */
class ClipInfo(
    val uri: Uri,
    val cameraType: String,
    val rig: Rig,
    /** Video izlerinin extractor içindeki indisleri; sıra = videoTrackOrder. */
    val videoTracks: List<Int>,
    val audioTrack: Int?,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val frameRate: Float,
    /** Aynı çekimin düşük çözünürlüklü önizleme dosyası (yan yana iki lens). */
    val lrv: Lrv? = null,
) {
    data class Lrv(val uri: Uri, val videoTrack: Int, val width: Int, val height: Int)

    companion object {
        // ExtraMetadata alan etiketleri (telemetry-parser extra_info.rs)
        private const val TAG_CAMERA_TYPE = 2
        private const val TAG_WINDOW_CROP = 27
        private const val TAG_OFFSET_V3 = 54
        private const val TAG_MULTI_TRACK_ORDER = 80
        private const val TRACK0_STREAM10 = 1L
        private val TRACK_ORDER_BODIES = setOf(
            "Insta360 X4", "Insta360 X5", "Insta360 X4 Air", "Insta360 X6", "Antigravity A1", "Insta360 DM"
        )

        /** LRV dosyasını doğrula: tek video izi, 2:1 yan yana. Uymazsa null. */
        fun probeLrv(context: Context, uri: Uri): Lrv? = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(pfd.fileDescriptor)
                    (0 until ex.trackCount).firstNotNullOfOrNull { i ->
                        val f = ex.getTrackFormat(i)
                        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                            val w = f.getInteger(MediaFormat.KEY_WIDTH); val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                            if (w == 2 * h) Lrv(uri, i, w, h) else null
                        } else null
                    }
                } finally { ex.release() }
            }
        }.getOrNull()

        fun load(context: Context, uri: Uri, lrvUri: Uri? = null): ClipInfo {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("dosya açılamadı")
            pfd.use {
                val meta = FileInputStream(it.fileDescriptor).channel.use { ch ->
                    val tr = Trailer.open(ch)
                    val raw = tr.read(Trailer.ID_METADATA) ?: throw IllegalStateException("metadata kaydı yok")
                    Proto(raw).fields()
                }
                val camera = meta.string(TAG_CAMERA_TYPE) ?: "?"
                val offset = meta.string(TAG_OFFSET_V3)
                    ?: throw IllegalStateException("Bu klipte lens kalibrasyonu (offset_v3) yok")
                val crop = meta.message(TAG_WINDOW_CROP)?.let { m ->
                    val sw = (m.long(1) ?: 0).toInt()
                    val sh = (m.long(2) ?: 0).toInt()
                    val dw = (m.long(3) ?: 0).toInt()
                    val dh = (m.long(4) ?: 0).toInt()
                    if (sw > 0 && sh > 0 && dw > 0 && dh > 0)
                        WindowCrop(sw, sh, dw, dh, (m.long(5) ?: 0).toInt(), (m.long(6) ?: 0).toInt())
                    else null
                }

                val ex = MediaExtractor()
                ex.setDataSource(it.fileDescriptor)
                val video = ArrayList<Int>()
                var audio: Int? = null
                var w = 0; var h = 0; var dur = 0L; var fps = 30f
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        video += i
                        w = f.getInteger(MediaFormat.KEY_WIDTH)
                        h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION))
                        if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                                .getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                        }
                    } else if (mime.startsWith("audio/") && audio == null) {
                        audio = i
                    }
                }
                ex.release()
                if (video.size != 2) {
                    throw IllegalStateException(
                        "Şimdilik yalnızca iki video izli 360 .insv dosyaları destekleniyor (bu dosyada ${video.size} iz var). " +
                            "LRV önizleme dosyası yerine VID_..._00_xxx.insv dosyasını seçin."
                    )
                }
                val reversed = camera in TRACK_ORDER_BODIES && meta.long(TAG_MULTI_TRACK_ORDER) == TRACK0_STREAM10
                val rig = Rig.fromOffsetV3(offset, crop, reversed)
                val lrv = lrvUri?.let { probeLrv(context, it) }
                return ClipInfo(uri, camera, rig, video, audio, w, h, dur, fps, lrv)
            }
        }
    }
}

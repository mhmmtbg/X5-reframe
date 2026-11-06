package com.mhmmtbg.x5reframe.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.model.ViewParams
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Düz video dışa aktarımı: GPU'da kadrajla, donanım kodlayıcıyla yaz, sesi olduğu gibi kopyala. */
class Exporter(
    private val context: Context,
    private val clip: ClipInfo,
    private val settings: Settings,
    private val sceneAt: (Long) -> List<ViewParams>,
    private val listener: Listener,
    private val baseAt: (Long) -> DoubleArray? = { null },
) {
    data class Settings(
        val width: Int,
        val height: Int,
        val hevc: Boolean,
        val bitrate: Int,
        val startUs: Long,
        val endUs: Long,
        val includeAudio: Boolean = true,
        /** 360° equirect çıktı: küresel video bilgisi eklenir. */
        val spherical: Boolean = false,
        /** 1 / dikiş mesafesi (m). */
        val stitchInv: Float = 0f,
        /** Çoklu açı bölmeleri (normalize x, y, w, h). */
        val layoutRects: List<FloatArray> = listOf(floatArrayOf(0f, 0f, 1f, 1f)),
    )

    interface Listener {
        /** @param speed gerçek zamana göre hız (1.0 = video süresi kadar) */
        fun onProgress(fraction: Float, speed: Float)
        fun onDone(uri: Uri, seconds: Float)
        fun onFailed(message: String)
    }

    @Volatile var cancelled = false

    private val info = MediaCodec.BufferInfo()
    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var audioFormat: MediaFormat? = null

    fun run() {
        val t0 = SystemClock.elapsedRealtime()
        var outUri: Uri? = null
        var egl: EglCore? = null
        var program: ReframeProgram? = null
        var source: DualFrameSource? = null
        val inPfd = context.contentResolver.openFileDescriptor(clip.uri, "r")!!
        try {
            val mime = if (settings.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            checkSupported(mime, settings.width, settings.height)

            outUri = createOutput()
            val outPfd = context.contentResolver.openFileDescriptor(outUri, "rw")!!
            // 360° çıktıda önce geçici dosyaya yazılır (moov kutusu düzenlenebilsin diye).
            val tmp = if (settings.spherical) java.io.File(context.cacheDir, "export360.mp4").also { it.delete() } else null
            muxer = if (tmp != null) MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            clip.audioTrack?.takeIf { settings.includeAudio }?.let { a ->
                val ex = MediaExtractor()
                ex.setDataSource(inPfd.fileDescriptor)
                audioFormat = ex.getTrackFormat(a)
                ex.release()
            }

            encoder = createEncoder(mime)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            egl = EglCore()
            val win = egl.createWindowSurface(inputSurface)
            egl.makeCurrent(win)
            program = ReframeProgram(clip.rig)
            program.stitchInvDistance = settings.stitchInv
            source = DualFrameSource(inPfd.fileDescriptor, clip, realtime = false)

            val frameDur = (1_000_000 / clip.frameRate).toLong()
            val start = settings.startUs
            val end = settings.endUs.coerceAtMost(clip.durationUs)
            val total = (end - start).coerceAtLeast(1)

            openAudio(inPfd.fileDescriptor, start)
            var pts = source.seekAndShow(start, frameDur)
            var frames = 0
            var lastReport = 0L
            while (pts in 0..end && !cancelled) {
                program.drawScene(settings.width, settings.height, settings.layoutRects, sceneAt(pts), source, baseAt(pts))
                egl.setPresentationTime(win, (pts - start).coerceAtLeast(0) * 1000)
                egl.swap(win)
                frames++
                drain(false)
                writeAudioUpTo(pts, start, end)

                val now = SystemClock.elapsedRealtime()
                if (now - lastReport > 250) {
                    lastReport = now
                    val done = (pts - start).toFloat() / total
                    val elapsed = (now - t0) / 1000f
                    listener.onProgress(done.coerceIn(0f, 1f), if (elapsed > 0) ((pts - start) / 1e6f) / elapsed else 0f)
                }
                pts = source.next(true)
            }
            if (cancelled) throw InterruptedException("İptal edildi")

            encoder.signalEndOfInputStream()
            drain(true)
            if (!muxerStarted) throw IllegalStateException("Kodlayıcı çıktı üretmedi")

            writeAudioUpTo(end, start, end)
            closeAudio()

            muxer.stop()
            muxer.release()
            if (tmp != null) {
                java.io.RandomAccessFile(tmp, "rw").use { raf -> sphericalTagged = SphericalMetadata.inject(raf.channel) }
                java.io.FileInputStream(tmp).use { inp ->
                    java.io.FileOutputStream(outPfd.fileDescriptor).use { out -> inp.copyTo(out, 1 shl 20) }
                }
                tmp.delete()
            }
            outPfd.close()
            egl.releaseSurface(win)
            inputSurface.release()
            publish(outUri)
            listener.onProgress(1f, 0f)
            listener.onDone(outUri, (SystemClock.elapsedRealtime() - t0) / 1000f)
        } catch (t: Throwable) {
            runCatching { if (::muxer.isInitialized) muxer.release() }
            outUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            listener.onFailed(if (t is InterruptedException) "İptal edildi" else (t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { closeAudio() }
            runCatching { if (::encoder.isInitialized) { encoder.stop(); encoder.release() } }
            runCatching { source?.release() }
            runCatching { program?.release() }
            runCatching { egl?.release() }
            runCatching { inPfd.close() }
        }
    }

    private fun createEncoder(mime: String): MediaCodec {
        fun format(withRate: Boolean) = MediaFormat.createVideoFormat(mime, settings.width, settings.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, clip.frameRate.roundToInt())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 1) // gerçek zamanlı değil: olabildiğince hızlı
            if (withRate) setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }
        val codec = MediaCodec.createEncoderByType(mime)
        return try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        }
    }

    private fun checkSupported(mime: String, w: Int, h: Int) {
        val ok = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } &&
                runCatching { ci.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(w, h) }.getOrDefault(false)
        }
        if (!ok) throw IllegalStateException("Bu telefonun kodlayıcısı ${w}x$h ${if (settings.hevc) "H.265" else "H.264"} desteklemiyor")
    }

    private fun drain(endOfStream: Boolean) {
        var tries = 0
        while (true) {
            val i = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++tries > 500) return
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    audioFormat?.let { audioTrack = muxer.addTrack(it) }
                    muxer.start()
                    muxerStarted = true
                }
                i >= 0 -> {
                    val buf = encoder.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, buf, info)
                        videoWritten++
                    }
                    encoder.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // Ses: yeniden kodlanmadan, görüntüyle iç içe (interleaved) yazılır.
    private var audioEx: MediaExtractor? = null
    private var audioBuf: ByteBuffer? = null
    private var audioPending = false
    private val audioInfo = MediaCodec.BufferInfo()

    private fun openAudio(fd: java.io.FileDescriptor, start: Long) {
        if (!settings.includeAudio) return
        val a = clip.audioTrack ?: return
        val ex = MediaExtractor()
        ex.setDataSource(fd)
        ex.selectTrack(a)
        ex.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val fmt = ex.getTrackFormat(a)
        val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024
        audioBuf = ByteBuffer.allocate(size)
        audioEx = ex
    }

    /** Başlangıçtan [limitUs] konumuna kadar olan ses örneklerini yazar. */
    private fun writeAudioUpTo(limitUs: Long, start: Long, end: Long) {
        val ex = audioEx ?: return
        val buf = audioBuf ?: return
        if (!muxerStarted || audioTrack < 0) return
        while (true) {
            if (!audioPending) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) { closeAudio(); return }
                val t = ex.sampleTime
                if (t > end) { closeAudio(); return }
                audioInfo.set(0, n, t - start, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                audioPending = true
                ex.advance()
                if (t < start) { audioPending = false; continue }
            }
            if (audioInfo.presentationTimeUs + start > limitUs) return
            buf.position(0); buf.limit(audioInfo.size)
            muxer.writeSampleData(audioTrack, buf, audioInfo)
            audioWritten++
            audioPending = false
        }
    }

    private fun closeAudio() {
        audioEx?.release()
        audioEx = null
    }

    /** Test ve teşhis için: 360° bilgisi eklendi mi. */
    var sphericalTagged = false
        private set

    /** Test ve teşhis için: yazılan ses örneği sayısı. */
    var audioWritten = 0
        private set

    /** Test ve teşhis için: yazılan görüntü karesi sayısı. */
    var videoWritten = 0
        private set

    private fun createOutput(): Uri {
        val name = "X5_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/X5Reframe")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return context.contentResolver.insert(collection, values) ?: throw IllegalStateException("Çıktı dosyası oluşturulamadı")
    }

    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
    }
}

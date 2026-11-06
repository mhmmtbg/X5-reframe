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
import android.os.ParcelFileDescriptor
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

/**
 * Dışa aktarım: bir ya da birden çok klip parçasını sırayla GPU'da kadrajlar, donanım kodlayıcıyla
 * tek bir MP4'e yazar. Ses 1× hızdaki parçalarda yeniden kodlanmadan kopyalanır.
 */
class Exporter(
    private val context: Context,
    private val output: Output,
    private val jobs: List<Job>,
    private val listener: Listener,
) {
    /** Tüm çıktı için ortak ayarlar. */
    data class Output(
        val width: Int,
        val height: Int,
        val hevc: Boolean,
        val bitrate: Int,
        val includeAudio: Boolean = true,
        /** 360° equirect çıktı: küresel video bilgisi eklenir. */
        val spherical: Boolean = false,
    )

    /** Tek bir klip parçası. */
    class Job(
        val clip: ClipInfo,
        val startUs: Long,
        val endUs: Long,
        val sceneAt: (Long) -> List<ViewParams>,
        val baseAt: (Long) -> DoubleArray? = { null },
        val layoutRects: List<FloatArray> = listOf(floatArrayOf(0f, 0f, 1f, 1f)),
        val stitchInv: Float = 0f,
        /** 0,25..4: kaynak zamanın akış hızı. */
        val speed: Double = 1.0,
        /** Siyahtan açılma / siyaha kararma süreleri. */
        val fadeInUs: Long = 0,
        val fadeOutUs: Long = 0,
        val layers: List<com.mhmmtbg.x5reframe.model.Layer> = emptyList(),
    ) {
        val outputDurationUs: Long get() = ((endUs - startUs) / speed).toLong()
    }

    /** Eski tek klip arayüzü (testler ve basit kullanım). */
    data class Settings(
        val width: Int,
        val height: Int,
        val hevc: Boolean,
        val bitrate: Int,
        val startUs: Long,
        val endUs: Long,
        val includeAudio: Boolean = true,
        val spherical: Boolean = false,
        val stitchInv: Float = 0f,
        val layoutRects: List<FloatArray> = listOf(floatArrayOf(0f, 0f, 1f, 1f)),
    )

    constructor(
        context: Context, clip: ClipInfo, s: Settings, sceneAt: (Long) -> List<ViewParams>, listener: Listener,
        baseAt: (Long) -> DoubleArray? = { null },
    ) : this(
        context, Output(s.width, s.height, s.hevc, s.bitrate, s.includeAudio, s.spherical),
        listOf(Job(clip, s.startUs, s.endUs, sceneAt, baseAt, s.layoutRects, s.stitchInv)), listener,
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

    /** Test ve teşhis için. */
    var sphericalTagged = false
        private set
    var audioWritten = 0
        private set
    var videoWritten = 0
        private set

    fun run() {
        val t0 = SystemClock.elapsedRealtime()
        var outUri: Uri? = null
        var egl: EglCore? = null
        val pfds = ArrayList<ParcelFileDescriptor>()
        try {
            require(jobs.isNotEmpty()) { "Dışa aktarılacak parça yok" }
            val mime = if (output.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            checkSupported(mime, output.width, output.height)

            outUri = createOutput()
            val outPfd = context.contentResolver.openFileDescriptor(outUri, "rw")!!
            // 360° çıktıda önce geçici dosyaya yazılır (moov kutusu düzenlenebilsin diye).
            val tmp = if (output.spherical) java.io.File(context.cacheDir, "export360.mp4").also { it.delete() } else null
            muxer = if (tmp != null) MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Ses biçimi: sesli ilk parçanın AAC izi (X5 klipleri aynı biçimde).
            if (output.includeAudio) {
                for (j in jobs) {
                    val a = j.clip.audioTrack ?: continue
                    context.contentResolver.openFileDescriptor(j.clip.uri, "r")!!.use { pfd ->
                        val ex = MediaExtractor(); ex.setDataSource(pfd.fileDescriptor)
                        audioFormat = ex.getTrackFormat(a); ex.release()
                    }
                    break
                }
            }

            val fps = jobs.first().clip.frameRate
            val frameDur = (1_000_000 / fps).toLong()
            encoder = createEncoder(mime, fps)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            egl = EglCore()
            val win = egl.createWindowSurface(inputSurface)
            egl.makeCurrent(win)
            val overlay = com.mhmmtbg.x5reframe.gl.OverlayRenderer()

            val totalOut = jobs.sumOf { it.outputDurationUs }.coerceAtLeast(1)
            var timeline = 0L
            var lastReport = 0L

            for (job in jobs) {
                if (cancelled) break
                val pfd = context.contentResolver.openFileDescriptor(job.clip.uri, "r")!!
                pfds += pfd
                val program = ReframeProgram(job.clip.rig).apply { stitchInvDistance = job.stitchInv }
                val source = DualFrameSource(pfd.fileDescriptor, job.clip, realtime = false)
                try {
                    val audio = if (output.includeAudio && job.speed == 1.0) openAudio(pfd, job) else null
                    val end = job.endUs.coerceAtMost(job.clip.durationUs)
                    val nFrames = (((end - job.startUs) / job.speed) / frameDur).toLong().coerceAtLeast(1)
                    if (source.seekAndShow(job.startUs, frameDur) < 0) continue
                    for (k in 0 until nFrames) {
                        if (cancelled) break
                        val tSrc = job.startUs + (k * frameDur * job.speed).toLong()
                        // Hedef kaynak zamanına kadar ilerle (hızlandırmada kare atlanır, yavaşlatmada aynı kare tekrar çizilir).
                        var eos = false
                        while (source.currentPts + frameDur <= tSrc) {
                            if (source.next { it + frameDur > tSrc } < 0) { eos = true; break }
                        }
                        if (eos) break
                        val tOut = k * frameDur
                        program.fade = fadeFactor(job, tOut)
                        val pts = source.currentPts
                        program.drawScene(output.width, output.height, job.layoutRects, job.sceneAt(pts), source, job.baseAt(pts))
                        overlay.draw(output.width, output.height, job.layers, pts)
                        egl.setPresentationTime(win, (timeline + tOut) * 1000)
                        egl.swap(win)
                        drain(false)
                        audio?.writeUpTo(pts, timeline)

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport > 250) {
                            lastReport = now
                            val done = (timeline + tOut).toFloat() / totalOut
                            val elapsed = (now - t0) / 1000f
                            listener.onProgress(done.coerceIn(0f, 1f), if (elapsed > 0) ((timeline + tOut) / 1e6f) / elapsed else 0f)
                        }
                    }
                    audio?.writeUpTo(end, timeline)
                    audio?.close()
                    timeline += nFrames * frameDur
                } finally {
                    runCatching { source.release() }
                    runCatching { program.release() }
                }
            }
            overlay.release()
            if (cancelled) throw InterruptedException("İptal edildi")

            encoder.signalEndOfInputStream()
            drain(true)
            if (!muxerStarted) throw IllegalStateException("Kodlayıcı çıktı üretmedi")
            pending.forEach { (b, i) -> muxer.writeSampleData(audioTrack, b, i); audioWritten++ }
            pending.clear()

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
            runCatching { if (::encoder.isInitialized) { encoder.stop(); encoder.release() } }
            runCatching { egl?.release() }
            pfds.forEach { runCatching { it.close() } }
        }
    }

    private fun fadeFactor(job: Job, tOut: Long): Float {
        val dur = job.outputDurationUs
        var f = 1f
        if (job.fadeInUs > 0 && tOut < job.fadeInUs) f = minOf(f, tOut.toFloat() / job.fadeInUs)
        if (job.fadeOutUs > 0 && dur - tOut < job.fadeOutUs) f = minOf(f, (dur - tOut).toFloat() / job.fadeOutUs)
        return f.coerceIn(0f, 1f)
    }

    private fun createEncoder(mime: String, fps: Float): MediaCodec {
        fun format(withRate: Boolean) = MediaFormat.createVideoFormat(mime, output.width, output.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, output.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt())
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
        if (!ok) throw IllegalStateException("Bu telefonun kodlayıcısı ${w}x$h ${if (output.hevc) "H.265" else "H.264"} desteklemiyor")
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
                    // Muxer başlamadan önce okunan ses örnekleri
                    pending.forEach { (b, inf) -> muxer.writeSampleData(audioTrack, b, inf); audioWritten++ }
                    pending.clear()
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

    /** Muxer başlamadan gelen ses örnekleri. */
    private val pending = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()

    /** Bir parçanın sesi: yeniden kodlanmadan, görüntüyle iç içe yazılır. */
    private inner class AudioCopy(private val ex: MediaExtractor, private val job: Job, maxSize: Int) {
        private val buf = ByteBuffer.allocate(maxSize)
        private val ai = MediaCodec.BufferInfo()
        private var hasPending = false
        private var done = false

        /** Kaynak zamanı [limitUs]'ya kadar olan örnekleri, [timeline] kaymasıyla yazar. */
        fun writeUpTo(limitUs: Long, timeline: Long) {
            if (done || audioTrack < 0 && muxerStarted) return
            while (true) {
                if (!hasPending) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { done = true; return }
                    val t = ex.sampleTime
                    ex.advance()
                    if (t > job.endUs) { done = true; return }
                    if (t < job.startUs) continue
                    ai.set(0, n, timeline + (t - job.startUs), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    hasPending = true
                }
                if (ai.presentationTimeUs - timeline + job.startUs > limitUs) return
                buf.position(0); buf.limit(ai.size)
                if (muxerStarted) {
                    muxer.writeSampleData(audioTrack, buf, ai)
                    audioWritten++
                } else {
                    val copy = ByteBuffer.allocate(ai.size).put(buf).also { it.flip() }
                    pending += copy to MediaCodec.BufferInfo().also { it.set(0, ai.size, ai.presentationTimeUs, ai.flags) }
                }
                hasPending = false
            }
        }

        fun close() = ex.release()
    }

    private fun openAudio(pfd: ParcelFileDescriptor, job: Job): AudioCopy? {
        if (audioFormat == null) return null
        val a = job.clip.audioTrack ?: return null
        val ex = MediaExtractor()
        ex.setDataSource(pfd.fileDescriptor)
        ex.selectTrack(a)
        ex.seekTo(job.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val fmt = ex.getTrackFormat(a)
        val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024
        return AudioCopy(ex, job, size)
    }

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

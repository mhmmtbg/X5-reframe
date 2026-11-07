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
import android.opengl.GLES30
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
        /** Müzik / ses seviyesi: verilirse ses çözülüp karıştırılır ve AAC olarak yeniden kodlanır. */
        val mix: Mix? = null,
        /** Çıktı kare hızı; null = ilk klibin kare hızı. */
        val fps: Float? = null,
    )

    data class Mix(val music: MusicSpec?, val originalVolume: Float)

    /** Tek bir klip parçası. */
    class Job(
        val clip: ClipInfo,
        val startUs: Long,
        val endUs: Long,
        val sceneAt: (Long) -> List<ViewParams>,
        val baseAt: (Long) -> DoubleArray? = { null },
        val layoutRects: List<FloatArray> = listOf(floatArrayOf(0f, 0f, 1f, 1f)),
        val stitchInv: Float = 0f,
        /** 0,1..10: kaynak zamanın akış hızı. */
        val speed: Double = 1.0,
        /** Siyahtan açılma / siyaha kararma süreleri. */
        val fadeInUs: Long = 0,
        val fadeOutUs: Long = 0,
        val layers: List<com.mhmmtbg.x5reframe.model.Layer> = emptyList(),
        val color: FloatArray? = null,
        val look: com.mhmmtbg.x5reframe.model.FlatLook = com.mhmmtbg.x5reframe.model.FlatLook(),
        /** Geriye oynat (kare blokları ileri çözülüp ters sırayla yazılır). */
        val reverse: Boolean = false,
        val curve: com.mhmmtbg.x5reframe.model.SpeedCurve = com.mhmmtbg.x5reframe.model.SpeedCurve.NONE,
    ) {
        val effEndUs: Long get() = endUs.coerceAtMost(clip.durationUs)
        val map: com.mhmmtbg.x5reframe.model.SpeedMap by lazy { com.mhmmtbg.x5reframe.model.SpeedMap(effEndUs - startUs, speed, curve) }
        val outputDurationUs: Long get() = map.outputUs
        /** Orijinal ses perde korunarak verilebilir mi. */
        val audible: Boolean get() = !reverse && curve == com.mhmmtbg.x5reframe.model.SpeedCurve.NONE && AudioMixer.hasAudioAt(speed)
        /** Çıktı zamanı → kaynak zamanı. */
        fun sourceAt(outUs: Long): Long = if (reverse) effEndUs - 1 - map.sourceAt(outUs) else startUs + map.sourceAt(outUs)
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

    companion object {
        /** Geriye oynatmada bir blokta tutulan kare sayısı (çıktı çözünürlüğünde doku). */
        const val BLOCK = 8
    }

    /**
     * Karışım yolu: istenmişse ya da sesler olduğu gibi kopyalanamıyorsa (farklı biçim, AAC olmayan ses,
     * sessiz klip / fotoğraf, hız değişimi) ses çözülüp tek AAC ize karıştırılır.
     */
    private var mixSpec: Mix? = null

    private fun audioCopyable(): Boolean {
        val fmts = jobs.map { j ->
            if (j.speed != 1.0 || j.reverse || j.curve != com.mhmmtbg.x5reframe.model.SpeedCurve.NONE) return false
            val a = j.clip.audioTrack ?: return jobs.none { it.clip.audioTrack != null }
            runCatching {
                context.contentResolver.openFileDescriptor(j.clip.uri, "r")!!.use { pfd ->
                    val ex = MediaExtractor()
                    try { ex.setDataSource(pfd.fileDescriptor); ex.getTrackFormat(a) } finally { ex.release() }
                }
            }.getOrNull() ?: return false
        }
        if (fmts.isEmpty()) return true
        fun key(f: MediaFormat) = Triple(f.getString(MediaFormat.KEY_MIME), f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        val k0 = runCatching { key(fmts[0]) }.getOrNull() ?: return false
        return k0.first == MediaFormat.MIMETYPE_AUDIO_AAC && fmts.all { runCatching { key(it) }.getOrNull() == k0 }
    }

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
            mixSpec = output.mix ?: if (output.includeAudio && !audioCopyable()) Mix(null, 1f) else null
            val mime = if (output.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            checkSupported(mime, output.width, output.height)

            outUri = createOutput()
            val outPfd = context.contentResolver.openFileDescriptor(outUri, "rw")!!
            // 360° çıktıda önce geçici dosyaya yazılır (moov kutusu düzenlenebilsin diye).
            val tmp = if (output.spherical) java.io.File(context.cacheDir, "export360.mp4").also { it.delete() } else null
            muxer = if (tmp != null) MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val fpsHint = output.fps ?: jobs.first().clip.frameRate
            val frameDurHint = (1_000_000 / fpsHint).toLong()
            val totalOutUs = jobs.sumOf { (it.outputDurationUs / frameDurHint).coerceAtLeast(1) * frameDurHint }
            if (mixSpec != null) {
                // Karışım yolu: AAC kodlayıcının biçimini almak için ilk sesi önceden üret.
                var off = 0L
                val segs = jobs.map { j ->
                    val d = (j.outputDurationUs / frameDurHint).coerceAtLeast(1) * frameDurHint
                    AudioMixer.Seg(j.clip.uri, if (output.includeAudio && j.audible) j.clip.audioTrack else null, j.startUs, off, d, j.speed).also { off += d }
                }
                val ms = mixSpec!!
                mixer = AudioMixer(context, segs, ms.music, if (output.includeAudio) ms.originalVolume else 0f, totalOutUs)
                aac = AacEncoder()
                mixTotalUs = totalOutUs
                var guard = 0
                while (aac!!.outputFormat == null && guard++ < 50) produceAudio(minOf(totalOutUs, (guard + 1) * 21_333L))
                audioFormat = aac!!.outputFormat
            } else if (output.includeAudio) {
                for (j in jobs) {
                    val a = j.clip.audioTrack ?: continue
                    context.contentResolver.openFileDescriptor(j.clip.uri, "r")!!.use { pfd ->
                        val ex = MediaExtractor(); ex.setDataSource(pfd.fileDescriptor)
                        audioFormat = ex.getTrackFormat(a); ex.release()
                    }
                    break
                }
            }

            val fps = output.fps ?: jobs.first().clip.frameRate
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
                val program = Sources.program(job.clip, highQuality = true).apply {
                    stitchInvDistance = job.stitchInv
                    color = job.color
                    look = job.look
                    dynamicStitch = context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("dynamic_stitch", true)
                }
                val source = Sources.open(context, job.clip, pfd.fileDescriptor, realtime = false)
                try {
                    val audio = if (mixSpec == null && output.includeAudio && job.speed == 1.0 && !job.reverse && job.curve == com.mhmmtbg.x5reframe.model.SpeedCurve.NONE) openAudio(pfd, job) else null
                    val end = job.effEndUs
                    val nFrames = (job.outputDurationUs / frameDur).coerceAtLeast(1)
                    if (job.reverse) {
                        val p = Progress(t0, totalOut) { timeline }
                        renderReverse(job, source, program, overlay, win, egl, frameDur, nFrames, timeline, p)
                        timeline += nFrames * frameDur
                        continue
                    }
                    if (source.seekAndShow(job.startUs, frameDur) < 0) continue
                    var stitchPts = -1L
                    for (k in 0 until nFrames) {
                        if (cancelled) break
                        val tSrc = job.sourceAt(k * frameDur)
                        // Hedef kaynak zamanına kadar ilerle (hızlandırmada kare atlanır, yavaşlatmada aynı kare tekrar çizilir).
                        var eos = false
                        while (source.currentPts + frameDur <= tSrc) {
                            if (source.next { it + frameDur > tSrc } < 0) { eos = true; break }
                        }
                        if (eos) break
                        val tOut = k * frameDur
                        program.fade = fadeFactor(job, tOut)
                        val pts = source.currentPts
                        program.frameTime = tOut / 1e6f
                        if (pts != stitchPts) { program.updateStitch(source, 3, 0.5f); stitchPts = pts }
                        program.drawScene(output.width, output.height, job.layoutRects, job.sceneAt(pts), source, job.baseAt(pts))
                        overlay.draw(output.width, output.height, job.layers, pts)
                        egl.setPresentationTime(win, (timeline + tOut) * 1000)
                        egl.swap(win)
                        drain(false)
                        audio?.writeUpTo(pts, timeline)
                        if (mixer != null) produceAudio(timeline + tOut)

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
            if (mixer != null) {
                produceAudio(timeline)
                aac!!.finish(::onAacOutput)
            }
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
            runCatching { mixer?.close() }
            runCatching { aac?.release() }
            runCatching { if (::encoder.isInitialized) { encoder.stop(); encoder.release() } }
            runCatching { egl?.release() }
            pfds.forEach { runCatching { it.close() } }
        }
    }

    private inner class Progress(val t0: Long, val totalOut: Long, val base: () -> Long) {
        var last = 0L
        fun report(tOut: Long) {
            val now = SystemClock.elapsedRealtime()
            if (now - last < 250) return
            last = now
            val done = (base() + tOut).toFloat() / totalOut
            val elapsed = (now - t0) / 1000f
            listener.onProgress(done.coerceIn(0f, 1f), if (elapsed > 0) ((base() + tOut) / 1e6f) / elapsed else 0f)
        }
    }

    /**
     * Geriye oynatma: çıktı kareleri [BLOCK]'luk bloklar halinde, her blok kaynağın ilgili aralığı
     * ileri çözülerek çerçeve tamponlarına çizilir, sonra ters sırayla kodlayıcıya yazılır.
     */
    private fun renderReverse(
        job: Job, source: FrameSource, program: ReframeProgram, overlay: com.mhmmtbg.x5reframe.gl.OverlayRenderer,
        win: android.opengl.EGLSurface, egl: EglCore, frameDur: Long, nFrames: Long, timeline: Long, progress: Progress,
    ) {
        val block = BLOCK
        val w = output.width; val h = output.height
        val fbos = IntArray(block); val texs = IntArray(block)
        GLES30.glGenFramebuffers(block, fbos, 0)
        GLES30.glGenTextures(block, texs, 0)
        for (i in 0 until block) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texs[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texs[i], 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val blit = com.mhmmtbg.x5reframe.gl.Blit()
        val pts = LongArray(block)
        try {
            var k0 = 0L
            while (k0 < nFrames && !cancelled) {
                val k1 = minOf(nFrames, k0 + block)
                // Bu bloktaki çıktı karelerinin kaynak zamanları azalan sıradadır; artan sırayla çöz.
                val times = (k0 until k1).map { job.sourceAt(it * frameDur) }
                val order = times.indices.sortedBy { times[it] }
                if (source.seekAndShow(times[order.first()], frameDur) < 0) break
                for (idx in order) {
                    val t = times[idx]
                    while (source.currentPts + frameDur <= t) { if (source.next { it + frameDur > t } < 0) break }
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[idx])
                    program.fade = 1f
                    program.frameTime = (k0 + idx) * frameDur / 1e6f
                    program.drawScene(w, h, job.layoutRects, job.sceneAt(source.currentPts), source, job.baseAt(source.currentPts))
                    pts[idx] = source.currentPts
                }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                for (idx in times.indices) {
                    val k = k0 + idx
                    val tOut = k * frameDur
                    blit.draw(texs[idx], w, h, fadeFactor(job, tOut))
                    overlay.draw(w, h, job.layers, pts[idx])
                    egl.setPresentationTime(win, (timeline + tOut) * 1000)
                    egl.swap(win)
                    drain(false)
                    if (mixer != null) produceAudio(timeline + tOut)
                    progress.report(tOut)
                }
                k0 = k1
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            blit.release()
            GLES30.glDeleteFramebuffers(block, fbos, 0)
            GLES30.glDeleteTextures(block, texs, 0)
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

    // ---- Karışım yolu ----
    private var mixer: AudioMixer? = null
    private var aac: AacEncoder? = null
    private var mixTotalUs = 0L
    private val pcm = ShortArray(1024 * 2)

    /** Zaman çizelgesinde [untilUs]'ya kadar karışık sesi üret ve kodla. */
    private fun produceAudio(untilUs: Long) {
        val m = mixer ?: return
        val enc = aac ?: return
        val target = minOf(untilUs, mixTotalUs)
        while (m.producedUs < target) {
            val left = ((target - m.producedUs) * MIX_RATE / 1_000_000).toInt().coerceIn(1, 1024)
            m.render(left, pcm)
            enc.feed(pcm, left, ::onAacOutput)
        }
    }

    private fun onAacOutput(b: ByteBuffer, i: MediaCodec.BufferInfo) {
        if (muxerStarted && audioTrack >= 0) {
            muxer.writeSampleData(audioTrack, b, i); audioWritten++
        } else {
            val copy = ByteBuffer.allocate(i.size).put(b).also { it.flip() }
            pending += copy to MediaCodec.BufferInfo().also { it.set(0, i.size, i.presentationTimeUs, i.flags) }
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

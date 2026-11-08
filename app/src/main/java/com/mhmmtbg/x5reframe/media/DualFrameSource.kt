package com.mhmmtbg.x5reframe.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.ClipInfo
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Tek bir video izini donanım çözücüsüyle bir Surface'e çözer (senkron API). */
class TrackDecoder(fd: FileDescriptor, track: Int, private val surface: Surface, realtime: Boolean, toneMapToSdr: Boolean = false) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val format: MediaFormat
    private var inputEos = false
    var outputEos = false
        private set
    var lastPts = -1L
        private set
    /** Çözücü henüz çıktı vermediyse flush() codec-specific veriyi kaybettirir; bu durumda flush yapılmaz. */
    private var gotOutput = false
    private var queuedInput = false

    init {
        extractor.setDataSource(fd)
        extractor.selectTrack(track)
        format = extractor.getTrackFormat(track)
        // Önizleme: gerçek zamanlı öncelik; dışa aktarım: olabildiğince hızlı.
        format.setInteger(MediaFormat.KEY_PRIORITY, if (realtime) 0 else 1)
        // HDR: Android 13+ kod çözücüden SDR'ye ton eşlemesi iste (desteklemezse yok sayılır, shader eşler).
        if (toneMapToSdr && android.os.Build.VERSION.SDK_INT >= 33) {
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        // Hız: çoğu donanım kod çözücüsü saatini kare hızına göre ayarlar. Dışa aktarımda en yüksek hızı,
        // önizlemede (hızlandırılmış oynatma için) 2 katı payı iste; desteklemeyen kod çözücüde ayarsız aç.
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, if (realtime) 120 else Short.MAX_VALUE.toInt())
        var c = createCodec(format)
        try {
            c.configure(format, surface, null, 0)
        } catch (e: Exception) {
            runCatching { c.release() }
            format.removeKey(MediaFormat.KEY_OPERATING_RATE)
            c = createCodec(format)
            c.configure(format, surface, null, 0)
        }
        codec = c
        codec.start()
    }

    /** Kod çözücünün gerçekten verdiği aktarım eğrisi (ton eşlemesi yapıldıysa SDR). */
    @Volatile var outputTransfer = -1
        private set

    private fun createCodec(f: MediaFormat): MediaCodec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)

    /** Sıradaki çıktı tamponunun indisini döner, akış bittiyse -1. */
    fun nextOutput(): Int {
        if (outputEos) return -1
        var idle = 0
        val t0 = System.nanoTime()
        var waits = 0
        while (true) {
            // Kod çözücü uzun süre hiç çıktı vermiyorsa (kaynak tükenmiş / takılmış) sonsuza dek bekleme
            if (++waits % 500 == 0 && System.nanoTime() - t0 > 12_000_000_000L) {
                android.util.Log.e("TrackDecoder", "12 sn çıktı yok (giriş bitti=$inputEos, son pts=$lastPts)")
                throw IllegalStateException("Video çözücü yanıt vermiyor")
            }
            // Boş giriş tamponlarının hepsini doldur: kod çözücü önden çalışsın, çıktı beklerken boşta kalmasın
            var fed = 0
            while (!inputEos && fed < 8) {
                val ii = codec.dequeueInputBuffer(if (fed == 0) 1000 else 0)
                if (ii < 0) break
                val buf = codec.getInputBuffer(ii)!!
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) {
                    codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEos = true
                } else {
                    codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                    extractor.advance()
                    queuedInput = true
                }
                fed++
            }
            val oi = codec.dequeueOutputBuffer(info, 2000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                gotOutput = true
                outputTransfer = runCatching { codec.outputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrDefault(0)
            }
            if (oi >= 0) {
                gotOutput = true
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && info.size == 0) {
                    codec.releaseOutputBuffer(oi, false)
                    outputEos = true
                    return -1
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                lastPts = info.presentationTimeUs
                return oi
            }
            if (inputEos && ++idle > 2000) { // ~4 sn çıktı yok: akış bitmiş say
                outputEos = true
                return -1
            }
        }
    }

    fun release(index: Int, render: Boolean) = codec.releaseOutputBuffer(index, render)

    fun seek(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // Başlangıçta (henüz giriş/çıkış yokken) flush gereksiz ve zararlı.
        if (gotOutput || queuedInput) {
            if (gotOutput) codec.flush() else { codec.stop(); codec.configure(format, surface, null, 0); codec.start() }
        }
        queuedInput = false
        inputEos = false
        outputEos = false
        lastPts = -1
    }

    fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        extractor.release()
    }
}

/**
 * İki lens izini eşzamanlı çözer ve OES dokularına aktarır.
 * GL bağlamı geçerli olan iş parçacığından oluşturulmalı ve kullanılmalıdır.
 */
class DualFrameSource(fd: FileDescriptor, private val clip: ClipInfo, realtime: Boolean = true) : FrameSource {
    val textures = IntArray(2) { ReframeProgram.createOesTexture() }
    val texMatrices = Array(2) { FloatArray(16) }
    private val callbackThread = HandlerThread("st-callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val surfaceTextures = Array(2) { SurfaceTexture(textures[it]) }
    private val surfaces = Array(2) { Surface(surfaceTextures[it]) }
    @Volatile private var latches = arrayOf(CountDownLatch(1), CountDownLatch(1))
    private val decoders: Array<TrackDecoder>

    override var hasFrame = false
        private set
    override var currentPts = 0L
        private set
    override var lastRendered = false
        private set
    private val fullRect = floatArrayOf(1f, 0f, 1f, 0f)

    private val rig = clip.rig ?: throw IllegalArgumentException("360° klip değil")
    override fun lensTexture(lens: Int) = textures[rig.lenses[lens].videoTrackOrder]
    override fun lensTexMatrix(lens: Int) = texMatrices[rig.lenses[lens].videoTrackOrder]
    override fun lensSubRect(lens: Int) = fullRect

    init {
        for (i in 0..1) {
            surfaceTextures[i].setDefaultBufferSize(clip.width, clip.height)
            surfaceTextures[i].setOnFrameAvailableListener({ latches[i].countDown() }, callbackHandler)
        }
        decoders = Array(2) { TrackDecoder(fd, clip.videoTracks[it], surfaces[it], realtime) }
    }

    override fun seek(us: Long) {
        decoders.forEach { it.seek(us) }
    }

    /**
     * Sıradaki eş kare çiftini çözer. render=true ise dokulara aktarır.
     * @return karenin zaman damgası (µs) ya da akış bittiyse -1
     */
    /** [renderIf] karenin zaman damgasına bakarak dokuya aktarılıp aktarılmayacağına karar verir. */
    override fun next(renderIf: (Long) -> Boolean): Long {
        var o0 = decoders[0].nextOutput()
        var o1 = decoders[1].nextOutput()
        // İki izin zaman damgalarını hizala (normalde zaten eşittir).
        var guard = 0
        while (o0 >= 0 && o1 >= 0 && abs(decoders[0].lastPts - decoders[1].lastPts) > 1000 && guard++ < 30) {
            if (decoders[0].lastPts < decoders[1].lastPts) {
                decoders[0].release(o0, false); o0 = decoders[0].nextOutput()
            } else {
                decoders[1].release(o1, false); o1 = decoders[1].nextOutput()
            }
        }
        if (o0 < 0 || o1 < 0) {
            if (o0 >= 0) decoders[0].release(o0, false)
            if (o1 >= 0) decoders[1].release(o1, false)
            return -1
        }
        val pts = decoders[0].lastPts
        val render = renderIf(pts)
        lastRendered = render
        if (render) {
            latches = arrayOf(CountDownLatch(1), CountDownLatch(1))
            decoders[0].release(o0, true)
            decoders[1].release(o1, true)
            for (i in 0..1) {
                latches[i].await(500, TimeUnit.MILLISECONDS)
                surfaceTextures[i].updateTexImage()
                surfaceTextures[i].getTransformMatrix(texMatrices[i])
            }
            hasFrame = true
        } else {
            decoders[0].release(o0, false)
            decoders[1].release(o1, false)
        }
        currentPts = pts
        return pts
    }

    override fun release() {
        decoders.forEach { it.close() }
        surfaces.forEach { it.release() }
        surfaceTextures.forEach { it.release() }
        callbackThread.quitSafely()
        android.opengl.GLES30.glDeleteTextures(2, textures, 0)
    }
}

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
class TrackDecoder(fd: FileDescriptor, track: Int, surface: Surface, realtime: Boolean) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputEos = false
    var outputEos = false
        private set
    var lastPts = -1L
        private set

    init {
        extractor.setDataSource(fd)
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        // Önizleme: gerçek zamanlı öncelik; dışa aktarım: olabildiğince hızlı.
        format.setInteger(MediaFormat.KEY_PRIORITY, if (realtime) 0 else 1)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    /** Sıradaki çıktı tamponunun indisini döner, akış bittiyse -1. */
    fun nextOutput(): Int {
        if (outputEos) return -1
        var idle = 0
        while (true) {
            if (!inputEos) {
                val ii = codec.dequeueInputBuffer(1000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 2000)
            if (oi >= 0) {
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
        codec.flush()
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

    override fun lensTexture(lens: Int) = textures[clip.rig.lenses[lens].videoTrackOrder]
    override fun lensTexMatrix(lens: Int) = texMatrices[clip.rig.lenses[lens].videoTrackOrder]
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

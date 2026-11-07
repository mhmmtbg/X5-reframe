package com.mhmmtbg.x5reframe.media

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.Rig
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lens görüntülerini GL dokularına getiren kaynak.
 * - [DualFrameSource]: .insv, her lens ayrı HEVC izinde (dışa aktarım, tam kalite)
 * - [SideBySideFrameSource]: .lrv, iki lens tek H.264 karede yan yana (hızlı önizleme)
 */
interface FrameSource {
    val hasFrame: Boolean
    val currentPts: Long
    val lastRendered: Boolean

    /** Lens i'nin dokusu, dönüşüm matrisi ve doku içindeki alt bölgesi (ölçekU, kaymaU, ölçekV, kaymaV). */
    fun lensTexture(lens: Int): Int
    fun lensTexMatrix(lens: Int): FloatArray
    fun lensSubRect(lens: Int): FloatArray

    /** Düz videoda shader'ın ton eşlemesi yapması gereken HDR eğrisi (0 = SDR). */
    val hdrTransfer: Int get() = 0

    fun seek(us: Long)
    fun next(render: Boolean): Long = next { render }
    fun next(renderIf: (Long) -> Boolean): Long
    fun seekAndShow(targetUs: Long, frameDurUs: Long): Long {
        seek(targetUs)
        while (true) {
            val pts = next { it + frameDurUs > targetUs }
            if (pts < 0) return -1
            if (lastRendered) return pts
        }
    }
    fun release()
}

/** Tek izli, yan yana iki fisheye içeren LRV önizleme dosyası. */
class SideBySideFrameSource(fd: FileDescriptor, track: Int, width: Int, height: Int) : FrameSource {
    private val texture = ReframeProgram.createOesTexture()
    private val texMatrix = FloatArray(16)
    private val callbackThread = HandlerThread("st-lrv").apply { start() }
    private val surfaceTexture = SurfaceTexture(texture)
    private val surface = Surface(surfaceTexture)
    @Volatile private var latch = CountDownLatch(1)
    private val decoder: TrackDecoder
    private val subs = arrayOf(floatArrayOf(0.5f, 0f, 1f, 0f), floatArrayOf(0.5f, 0.5f, 1f, 0f))

    override var hasFrame = false
        private set
    override var currentPts = 0L
        private set
    override var lastRendered = false
        private set

    init {
        surfaceTexture.setDefaultBufferSize(width, height)
        surfaceTexture.setOnFrameAvailableListener({ latch.countDown() }, Handler(callbackThread.looper))
        decoder = TrackDecoder(fd, track, surface, realtime = true)
    }

    override fun lensTexture(lens: Int) = texture
    override fun lensTexMatrix(lens: Int) = texMatrix
    override fun lensSubRect(lens: Int) = subs[lens]

    override fun seek(us: Long) = decoder.seek(us)

    override fun next(renderIf: (Long) -> Boolean): Long {
        val o = decoder.nextOutput()
        if (o < 0) return -1
        val pts = decoder.lastPts
        val render = renderIf(pts)
        lastRendered = render
        if (render) {
            latch = CountDownLatch(1)
            decoder.release(o, true)
            latch.await(500, TimeUnit.MILLISECONDS)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            hasFrame = true
        } else {
            decoder.release(o, false)
        }
        currentPts = pts
        return pts
    }

    override fun release() {
        decoder.close()
        surface.release()
        surfaceTexture.release()
        callbackThread.quitSafely()
        android.opengl.GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    companion object {
        /** LRV'de lens sırası kalibrasyon sırasıyla aynıdır: sol = lens 0. */
        fun lensOrderOk(rig: Rig) = rig.lenses.size == 2
    }
}

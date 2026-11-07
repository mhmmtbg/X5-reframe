package com.mhmmtbg.x5reframe.media

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.ClipInfo
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Düz (normal) video: tek iz, tek doku. Döndürme bilgisi kod çözücü tarafından yüzeye çizilirken
 * uygulanır ve SurfaceTexture dönüşüm matrisine yansır.
 */
class FlatFrameSource(fd: FileDescriptor, clip: ClipInfo, realtime: Boolean = true) : FrameSource {
    private val texture = ReframeProgram.createOesTexture()
    private val texMatrix = FloatArray(16)
    private val callbackThread = HandlerThread("st-flat").apply { start() }
    private val surfaceTexture = SurfaceTexture(texture)
    private val surface = Surface(surfaceTexture)
    @Volatile private var latch = CountDownLatch(1)
    private val decoder: TrackDecoder
    private val full = floatArrayOf(1f, 0f, 1f, 0f)
    private val clipHdr = clip.hdrTransfer

    override var hasFrame = false
        private set
    override var currentPts = 0L
        private set
    override var lastRendered = false
        private set

    /** Kod çözücü ton eşlemesi yapmadıysa shader'ın uygulayacağı HDR eğrisi. */
    override val hdrTransfer: Int
        get() {
            if (clipHdr == 0) return 0
            val out = decoder.outputTransfer
            return if (out == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) 0 else clipHdr
        }

    init {
        surfaceTexture.setDefaultBufferSize(clip.width, clip.height)
        surfaceTexture.setOnFrameAvailableListener({ latch.countDown() }, Handler(callbackThread.looper))
        decoder = TrackDecoder(fd, clip.videoTracks[0], surface, realtime, toneMapToSdr = clipHdr != 0)
    }

    override fun lensTexture(lens: Int) = texture
    override fun lensTexMatrix(lens: Int) = texMatrix
    override fun lensSubRect(lens: Int) = full

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
}

/**
 * Fotoğraf klibi: görüntü bir kez dokuya yüklenir, zaman sanal olarak [frameDurUs] adımlarla ilerler.
 * Böylece oynatıcı ve dışa aktarım fotoğrafı bir video gibi işler.
 */
class ImageFrameSource(bitmap: Bitmap, private val durationUs: Long, private val frameDurUs: Long) : FrameSource {
    private val texture = ReframeProgram.createOesTexture()
    private val texMatrix = FloatArray(16)
    private val st = SurfaceTexture(texture)
    private val full = floatArrayOf(1f, 0f, 1f, 0f)
    private var nextPts = 0L

    override val hasFrame = true
    override var currentPts = 0L
        private set
    override var lastRendered = true
        private set

    init {
        st.setDefaultBufferSize(bitmap.width, bitmap.height)
        // Kare yüzeye gerçekten ulaşınca dokuya al (donanım tuvali eşzamansız çizer).
        val cb = HandlerThread("st-image").apply { start() }
        val ready = CountDownLatch(1)
        st.setOnFrameAvailableListener({ ready.countDown() }, Handler(cb.looper))
        val surface = Surface(st)
        val canvas = surface.lockHardwareCanvas()
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        surface.unlockCanvasAndPost(canvas)
        ready.await(2, TimeUnit.SECONDS)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        surface.release()
        cb.quitSafely()
    }

    override fun lensTexture(lens: Int) = texture
    override fun lensTexMatrix(lens: Int) = texMatrix
    override fun lensSubRect(lens: Int) = full

    override fun seek(us: Long) {
        nextPts = (us.coerceIn(0, durationUs) / frameDurUs) * frameDurUs
    }

    override fun next(renderIf: (Long) -> Boolean): Long {
        if (nextPts >= durationUs) return -1
        val pts = nextPts
        nextPts += frameDurUs
        lastRendered = renderIf(pts)
        currentPts = pts
        return pts
    }

    override fun release() {
        st.release()
        android.opengl.GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

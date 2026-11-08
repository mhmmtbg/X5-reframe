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
class ImageFrameSource(private var bitmap: Bitmap?, private val durationUs: Long, private val frameDurUs: Long) : FrameSource {
    private val texture = ReframeProgram.createOesTexture()
    private val texMatrix = FloatArray(16)
    private val st = SurfaceTexture(texture)
    private val full = floatArrayOf(1f, 0f, 1f, 0f)
    private var nextPts = 0L
    /** Görüntü yüzeye ulaştı mı / dokuya alındı mı (arayüz meşgulken çizim saniyeler sürebilir). */
    @Volatile private var arrived = false
    private var latched = false
    private val callbackThread = HandlerThread("st-image").apply { start() }
    /** Üretici yüzey: kare gelene kadar açık kalır (erken kapatılırsa kare hiç ulaşmayabilir). */
    private val producer = Surface(st)

    override val hasFrame = true
    override var currentPts = 0L
        private set
    override var lastRendered = true
        private set

    init {
        val bmp = bitmap!!
        st.setDefaultBufferSize(bmp.width, bmp.height)
        st.setOnFrameAvailableListener({ arrived = true }, Handler(callbackThread.looper))
        // Görüntü kendi GL bağlamımızla yüzeye çizilir (donanım tuvali arayüz meşgulken saniyelerce gecikebiliyordu)
        val win = runCatching { drawWithGl(bmp) }.onFailure { android.util.Log.w("ImageFrameSource", "GL ile çizilemedi: ${it.message}") }.getOrNull()
        if (win == null) {
            val canvas = producer.lockHardwareCanvas()
            canvas.drawBitmap(bmp, 0f, 0f, null)
            producer.unlockCanvasAndPost(canvas)
        }
        val until = System.nanoTime() + 3_000_000_000L
        while (!arrived && System.nanoTime() < until) Thread.sleep(2)
        poll()
        // Pencere yüzeyi ancak kare alındıktan sonra kapatılır (kapanış kuyruktaki kareyi siler)
        win?.let { android.opengl.EGL14.eglDestroySurface(android.opengl.EGL14.eglGetCurrentDisplay(), it) }
    }

    /** Geçerli EGL bağlamıyla üretici yüzeye bir pencere yüzeyi açıp bit eşlemi çizer. */
    private fun drawWithGl(bmp: Bitmap): android.opengl.EGLSurface {
        val dpy = android.opengl.EGL14.eglGetCurrentDisplay()
        val ctx = android.opengl.EGL14.eglGetCurrentContext()
        check(ctx != android.opengl.EGL14.EGL_NO_CONTEXT) { "GL bağlamı yok" }
        val draw = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW)
        val read = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_READ)
        val id = IntArray(1)
        android.opengl.EGL14.eglQueryContext(dpy, ctx, android.opengl.EGL14.EGL_CONFIG_ID, id, 0)
        val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
        android.opengl.EGL14.eglChooseConfig(dpy, intArrayOf(android.opengl.EGL14.EGL_CONFIG_ID, id[0], android.opengl.EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        check(n[0] > 0) { "EGL yapılandırması yok" }
        val win = android.opengl.EGL14.eglCreateWindowSurface(dpy, cfgs[0], producer, intArrayOf(android.opengl.EGL14.EGL_NONE), 0)
        check(win != android.opengl.EGL14.EGL_NO_SURFACE) { "pencere yüzeyi açılamadı" }
        val tex = IntArray(1)
        try {
            android.opengl.EGL14.eglMakeCurrent(dpy, win, win, ctx)
            // GL satırları aşağıdan yukarı: dikey çevrilmiş bit eşlem yüklenir ki yüzeyde düz görünsün
            val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { preScale(1f, -1f) }, false)
            android.opengl.GLES30.glGenTextures(1, tex, 0)
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, tex[0])
            android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D, android.opengl.GLES30.GL_TEXTURE_MIN_FILTER, android.opengl.GLES30.GL_LINEAR)
            android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D, android.opengl.GLES30.GL_TEXTURE_MAG_FILTER, android.opengl.GLES30.GL_LINEAR)
            android.opengl.GLUtils.texImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, flipped, 0)
            if (flipped !== bmp) flipped.recycle()
            val blit = com.mhmmtbg.x5reframe.gl.Blit()
            blit.draw(tex[0], bmp.width, bmp.height)
            blit.release()
            android.opengl.EGL14.eglSwapBuffers(dpy, win)
            return win
        } catch (t: Throwable) {
            android.opengl.EGL14.eglMakeCurrent(dpy, draw, read, ctx)
            android.opengl.EGL14.eglDestroySurface(dpy, win)
            throw t
        } finally {
            android.opengl.GLES30.glDeleteTextures(1, tex, 0)
            android.opengl.EGL14.eglMakeCurrent(dpy, draw, read, ctx)
        }
    }

    override fun poll(): Boolean {
        if (latched) return true
        if (!arrived) return false
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        latched = true
        producer.release()
        bitmap?.recycle(); bitmap = null
        callbackThread.quitSafely()
        return true
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
        if (!latched) { producer.release(); callbackThread.quitSafely(); bitmap?.recycle(); bitmap = null }
        st.release()
        android.opengl.GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

package com.mhmmtbg.x5reframe.media

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Surface
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.model.ViewParams

/**
 * Önizleme oynatıcı. Tüm GL ve çözücü işleri tek bir arka plan iş parçacığında yürür.
 * Görünüm değişince yalnızca yeniden çizilir; kare çözülmez.
 */
class Player(
    context: Context,
    private val clip: ClipInfo,
    private val listener: Listener,
) {
    interface Listener {
        fun onPosition(us: Long)
        fun onPlayingChanged(playing: Boolean)
        fun onError(message: String)
    }

    /** Görüntülenecek kadrajı veren fonksiyon (anahtar kareler zamana bağlı). */
    @Volatile var viewProvider: (Long) -> ViewParams = { ViewParams() }

    private val thread = HandlerThread("player").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(clip.uri, "r")!!

    private lateinit var egl: EglCore
    private lateinit var program: ReframeProgram
    private lateinit var source: DualFrameSource
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    @Volatile var playing = false
        private set
    private var playStartWall = 0L
    private var playStartPts = 0L
    private val frameDurUs = (1_000_000 / clip.frameRate).toLong()

    private val audio: AudioPreview? = clip.audioTrack?.let { AudioPreview(pfd.fileDescriptor, it) }

    @Volatile private var pendingSeek = -1L
    @Volatile private var redrawQueued = false
    private var released = false

    init {
        handler.post {
            try {
                egl = EglCore()
                program = ReframeProgram(clip.rig)
                source = DualFrameSource(pfd.fileDescriptor, clip)
                source.seekAndShow(0, frameDurUs)
                drawNow()
                postPosition(source.currentPts)
            } catch (t: Throwable) {
                error(t)
            }
        }
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) = handler.post {
        if (released) return@post
        try {
            if (window != EGL14.EGL_NO_SURFACE) egl.releaseSurface(window)
            window = egl.createWindowSurface(surface)
            winW = width; winH = height
            drawNow()
        } catch (t: Throwable) { error(t) }
    }

    fun resizeSurface(width: Int, height: Int) = handler.post { winW = width; winH = height; drawNow() }

    /** SurfaceHolder.surfaceDestroyed'den çağrılır; yüzey serbest kalana kadar bekler. */
    fun detachSurface() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            if (::egl.isInitialized && window != EGL14.EGL_NO_SURFACE) {
                egl.makeNothingCurrent()
                egl.releaseSurface(window)
            }
            window = EGL14.EGL_NO_SURFACE
            done.countDown()
        }
        done.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun setMuted(m: Boolean) { audio?.muted = m }

    fun requestRedraw() {
        if (playing || redrawQueued) return
        redrawQueued = true
        handler.post { redrawQueued = false; drawNow() }
    }

    fun play() = handler.post {
        if (playing || released || !::source.isInitialized) return@post
        playing = true
        playStartWall = SystemClock.elapsedRealtime()
        playStartPts = source.currentPts
        audio?.start(source.currentPts)
        main.post { listener.onPlayingChanged(true) }
        handler.post(loop)
    }

    fun pause() {
        playing = false
        handler.post { audio?.stop() }
        main.post { listener.onPlayingChanged(false) }
    }

    fun seekTo(us: Long) {
        pendingSeek = us.coerceIn(0, clip.durationUs)
        handler.post(seekTask)
    }

    private val seekTask = Runnable {
        val target = pendingSeek
        if (target < 0 || released || !::source.isInitialized) return@Runnable
        pendingSeek = -1
        try {
            val pts = source.seekAndShow(target, frameDurUs)
            drawNow()
            if (pts >= 0) postPosition(pts)
            if (playing) {
                playStartWall = SystemClock.elapsedRealtime(); playStartPts = source.currentPts
                audio?.start(source.currentPts)
            }
        } catch (t: Throwable) { error(t) }
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!playing || released) return
            try {
                val pts = source.next(true)
                if (pts < 0) {
                    playing = false
                    audio?.stop()
                    main.post { listener.onPlayingChanged(false) }
                    return
                }
                // Ses çalıyorsa ana saat sestir; yoksa duvar saati.
                val audioPos = audio?.positionUs()
                val wait = if (audioPos != null) {
                    (pts - audioPos) / 1000
                } else {
                    playStartWall + (pts - playStartPts) / 1000 - SystemClock.elapsedRealtime()
                }
                if (wait > 0) Thread.sleep(wait.coerceAtMost(100))
                drawNow()
                postPosition(pts)
                handler.post(this)
            } catch (t: Throwable) { error(t) }
        }
    }

    private fun drawNow() {
        if (window == EGL14.EGL_NO_SURFACE || !::source.isInitialized || !source.hasFrame) return
        egl.makeCurrent(window)
        val view = viewProvider(source.currentPts)
        program.draw(winW, winH, view, source.textures, source.texMatrices)
        egl.swap(window)
    }

    /** O anki kareden küçük resim üretir (proje listesi için). */
    fun captureThumbnail(w: Int, h: Int, onReady: (android.graphics.Bitmap) -> Unit) = handler.post {
        if (released || !::source.isInitialized || !source.hasFrame) return@post
        runCatching {
            val pb = egl.createPbuffer(w, h)
            egl.makeCurrent(pb)
            program.draw(w, h, viewProvider(source.currentPts), source.textures, source.texMatrices)
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            if (window != EGL14.EGL_NO_SURFACE) egl.makeCurrent(window) else egl.makeNothingCurrent()
            egl.releaseSurface(pb)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            // GL satırları aşağıdan yukarı okur.
            val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h, m, false)
            onReady(flipped)
        }
    }

    private fun postPosition(us: Long) = main.post { listener.onPosition(us) }

    private fun error(t: Throwable) {
        playing = false
        val msg = t.message ?: t.javaClass.simpleName
        main.post { listener.onError(msg) }
    }

    fun release() {
        playing = false
        handler.post {
            released = true
            runCatching { audio?.stop() }
            runCatching { if (::source.isInitialized) source.release() }
            runCatching { if (::program.isInitialized) program.release() }
            runCatching {
                if (::egl.isInitialized) {
                    if (window != EGL14.EGL_NO_SURFACE) egl.releaseSurface(window)
                    egl.release()
                }
            }
            runCatching { pfd.close() }
            thread.quitSafely()
        }
    }

    @Suppress("unused")
    private fun clear() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }
}

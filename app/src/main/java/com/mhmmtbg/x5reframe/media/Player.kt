package com.mhmmtbg.x5reframe.media

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
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
    @Volatile var sceneProvider: (Long) -> List<ViewParams> = { listOf(ViewParams()) }
    /** Çoklu açı düzeninin bölmeleri (normalize dikdörtgenler). */
    @Volatile var layoutProvider: () -> List<FloatArray> = { listOf(floatArrayOf(0f, 0f, 1f, 1f)) }
    /** Üst katmanlar. */
    @Volatile var layersProvider: () -> List<com.mhmmtbg.x5reframe.model.Layer> = { emptyList() }
    private var overlay: com.mhmmtbg.x5reframe.gl.OverlayRenderer? = null

    /** Sabitleme temel dönüşü (yoksa null). */
    @Volatile var baseProvider: (Long) -> DoubleArray? = { null }

    private val thread = HandlerThread("player").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(clip.uri, "r")!!
    /** Varsa hafif LRV önizleme dosyası; yoksa asıl .insv kullanılır. */
    private val lrvPfd: ParcelFileDescriptor? = clip.lrv?.let { runCatching { context.contentResolver.openFileDescriptor(it.uri, "r") }.getOrNull() }
    val usingLrv get() = lrvPfd != null

    private lateinit var egl: EglCore
    private lateinit var program: ReframeProgram
    private lateinit var source: FrameSource
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    @Volatile var playing = false
        private set
    private var playStartNs = 0L
    private var playStartPts = 0L
    val stats = FrameStats()
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
                overlay = com.mhmmtbg.x5reframe.gl.OverlayRenderer()
                val lrv = clip.lrv
                source = if (lrvPfd != null && lrv != null) {
                    SideBySideFrameSource(lrvPfd.fileDescriptor, lrv.videoTrack, lrv.width, lrv.height)
                } else {
                    DualFrameSource(pfd.fileDescriptor, clip)
                }
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
    fun setVolume(v: Float) { audio?.volume = v }

    /** 1 / dikiş mesafesi (m); 0 = sonsuz. */
    fun setStitchInv(v: Float) = handler.post { if (::program.isInitialized) { program.stitchInvDistance = v; drawNow() } }

    fun requestRedraw() {
        if (playing || redrawQueued) return
        redrawQueued = true
        handler.post { redrawQueued = false; drawNow() }
    }

    fun play() = handler.post {
        if (playing || released || !::source.isInitialized) return@post
        playing = true
        playStartNs = System.nanoTime()
        playStartPts = source.currentPts
        stats.pause()
        audio?.start(source.currentPts)
        main.post { listener.onPlayingChanged(true) }
        handler.post(loop)
    }

    fun pause() {
        playing = false
        stats.pause()
        handler.post { audio?.stop() }
        main.post { listener.onPlayingChanged(false) }
    }

    /** Bir kare ileri / geri (duraklatılmışken). */
    fun step(forward: Boolean) {
        if (playing) pause()
        if (forward) {
            handler.post {
                if (released || !::source.isInitialized) return@post
                runCatching {
                    val pts = source.next(true)
                    if (pts >= 0) { drawNow(); postPosition(pts) }
                }.onFailure { error(it) }
            }
        } else {
            handler.post { if (::source.isInitialized) seekTo((source.currentPts - frameDurUs).coerceAtLeast(0)) }
        }
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
                playStartNs = System.nanoTime(); playStartPts = source.currentPts
                stats.pause()
                audio?.start(source.currentPts)
            }
        } catch (t: Throwable) { error(t) }
    }

    /**
     * Oynatma saati (µs, klip zamanı). Ses çalıyorsa donanım zaman damgasından ara değerlenmiş
     * ses konumu; yoksa duvar saati. Her ikisi de sürekli ilerler, sıçramaz.
     */
    private fun clockUs(): Long =
        audio?.positionUs() ?: (playStartPts + (System.nanoTime() - playStartNs) / 1000)

    private val loop = object : Runnable {
        override fun run() {
            if (!playing || released) return
            try {
                // Geç kalan kareleri göstermeden atla (en fazla 6 art arda), böylece görüntü saati yakalar.
                var pts: Long
                var skipped = 0
                while (true) {
                    pts = source.next { p -> skipped >= 6 || p + frameDurUs >= clockUs() }
                    if (pts < 0 || source.lastRendered) break
                    skipped++
                    stats.frameDropped()
                }
                if (pts < 0) {
                    playing = false
                    audio?.stop()
                    stats.pause()
                    main.post { listener.onPlayingChanged(false) }
                    return
                }
                // Karenin zamanı gelene kadar bekle (erken çözüldüyse).
                val waitUs = pts - clockUs()
                if (waitUs > 1000) Thread.sleep((waitUs / 1000).coerceAtMost(100), ((waitUs % 1000) * 1000).toInt())
                drawNow()
                stats.frameShown()
                postPosition(pts)
                handler.post(this)
            } catch (t: Throwable) { error(t) }
        }
    }

    private fun drawNow() {
        if (window == EGL14.EGL_NO_SURFACE || !::source.isInitialized || !source.hasFrame) return
        egl.makeCurrent(window)
        val t = source.currentPts
        program.drawScene(winW, winH, layoutProvider(), sceneProvider(t), source, baseProvider(t))
        val layers = layersProvider()
        overlay?.retain(layers)
        overlay?.draw(winW, winH, layers, t)
        egl.swap(window)
    }

    /** O anki kareden küçük resim üretir (proje listesi için). */
    fun captureThumbnail(w: Int, h: Int, onReady: (android.graphics.Bitmap) -> Unit) = handler.post {
        if (released || !::source.isInitialized || !source.hasFrame) return@post
        runCatching {
            val pb = egl.createPbuffer(w, h)
            egl.makeCurrent(pb)
            program.drawScene(w, h, layoutProvider(), sceneProvider(source.currentPts), source, baseProvider(source.currentPts))
            overlay?.draw(w, h, layersProvider(), source.currentPts)
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
            runCatching { overlay?.release() }
            runCatching {
                if (::egl.isInitialized) {
                    if (window != EGL14.EGL_NO_SURFACE) egl.releaseSurface(window)
                    egl.release()
                }
            }
            runCatching { pfd.close() }
            runCatching { lrvPfd?.close() }
            thread.quitSafely()
        }
    }

    @Suppress("unused")
    private fun clear() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }
}

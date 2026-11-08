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
    /** Normal videoda hafif önizleme kopyası (varsa kareler ve ses buradan okunur). */
    private val preview: ClipInfo? = null,
) {
    /** Karelerin okunduğu klip: önizleme kopyası ya da asıl dosya. */
    private val readClip = preview ?: clip
    val usingProxy get() = preview != null
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

    /** Renk düzenlemesi (null = yok). */
    @Volatile var colorProvider: () -> FloatArray? = { null }

    /** Çıktı en/boy oranı ve tuval kipi (kadraj ayarlanırken çerçevenin etrafı görünür). */
    @Volatile var aspectProvider: () -> Float = { 16f / 9f }
    @Volatile var canvasProvider: () -> Boolean = { false }

    /** Parçanın çıkış noktası (sona kadar süren katmanların çıkış animasyonu için). */
    @Volatile var outProvider: () -> Long = { Long.MAX_VALUE }

    /**
     * Önizleme hızı: kaynak zamanındaki hız çarpanı (sabit hız veya hız eğrisi) ve parçanın yönü.
     * Eğrili parçalarda ses dışa aktarımdaki gibi kapalıdır; sabit hızda perde korunarak hızlanır.
     */
    @Volatile var rateProvider: (Long) -> Double = { 1.0 }
    @Volatile var curvedProvider: () -> Boolean = { false }
    @Volatile var reverseProvider: () -> Boolean = { false }
    /** Parçanın kaynak aralığı (ters oynatmada başa / sona gelince durmak için). */
    @Volatile var rangeProvider: () -> LongRange = { 0L..Long.MAX_VALUE }
    private var rate = 1.0
    /** Ters oynatmada son gösterilen hedef. */
    private var revTarget = -1L

    /** Düz klip yerleşimi. */
    @Volatile var lookProvider: () -> com.mhmmtbg.x5reframe.model.FlatLook = { com.mhmmtbg.x5reframe.model.FlatLook() }
    private val appContext = context.applicationContext

    /** Sabitleme temel dönüşü (yoksa null). */
    @Volatile var baseProvider: (Long) -> DoubleArray? = { null }

    // Görüntü iş parçacığı ekran önceliğinde: arayüz yükü kare zamanlamasını bozmasın
    private val thread = HandlerThread("player", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(readClip.uri, "r")!!
    /** Varsa hafif LRV önizleme dosyası; yoksa asıl .insv kullanılır. */
    private val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val lrvPfd: ParcelFileDescriptor? = clip.lrv?.takeIf { clip.is360 }?.takeIf { prefs.getBoolean("prefer_lrv", true) || clip.videoTracks.size < 2 }?.let { runCatching { context.contentResolver.openFileDescriptor(it.uri, "r") }.getOrNull() }
    val usingLrv get() = lrvPfd != null

    private lateinit var egl: EglCore
    private lateinit var program: ReframeProgram
    private lateinit var source: FrameSource
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    @Volatile var playing = false
        private set
    /** Oynatma hızı, işaretli (ters oynatmada eksi); zaman çizelgesi kaymasını tahmin etmek için. */
    val signedRate: Double get() = if (reverseProvider()) -rate else rate
    private var playStartNs = 0L
    private var playStartPts = 0L
    val stats = FrameStats()
    private val frameDurUs = (1_000_000 / clip.frameRate).toLong()

    private val audio: AudioPreview? = readClip.audioTrack?.let { AudioPreview(pfd.fileDescriptor, it) }

    @Volatile private var pendingSeek = -1L
    @Volatile private var redrawQueued = false
    private var stitchPts = -1L
    private var released = false

    init {
        handler.post {
            try {
                egl = EglCore()
                program = Sources.program(clip).apply { dynamicStitch = prefs.getBoolean("dynamic_stitch", true) }
                overlay = com.mhmmtbg.x5reframe.gl.OverlayRenderer()
                source = Sources.open(appContext, readClip, pfd.fileDescriptor, realtime = true, lrvFd = lrvPfd?.fileDescriptor)
                android.util.Log.i("X5Player", "kaynak açıldı: ${source.javaClass.simpleName} ${clip.kind}")
                source.seekAndShow(0, frameDurUs)
                android.util.Log.i("X5Player", "ilk kare: ${source.currentPts}")
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

    fun setDynamicStitch(on: Boolean) = handler.post {
        if (::program.isInitialized) { program.dynamicStitch = on; program.resetStitch(); stitchPts = -1; drawNow() }
    }

    fun requestRedraw() {
        if (playing || redrawQueued) return
        redrawQueued = true
        handler.post { redrawQueued = false; drawNow() }
    }

    fun play() = handler.post {
        if (playing || released || !::source.isInitialized) return@post
        val range = rangeProvider()
        if (reverseProvider() && source.currentPts <= range.first + frameDurUs * 2) {
            // Ters parçada baştaysak sondan başla
            runCatching { source.seekAndShow((range.last - frameDurUs).coerceAtLeast(0), frameDurUs); drawNow(); postPosition(source.currentPts) }
        }
        playing = true
        rate = rateProvider(source.currentPts).coerceIn(0.05, 16.0)
        android.util.Log.i("X5Player", "oynat pts=${source.currentPts} hız=$rate ters=${reverseProvider()}")
        anchor(source.currentPts)
        revTarget = -1
        stats.pause()
        main.post { listener.onPlayingChanged(true) }
        handler.post(loop)
    }

    /** Saati [pts]'ye bağla ve sesi o andaki hıza göre (yeniden) başlat. */
    private fun anchor(pts: Long) {
        playStartNs = System.nanoTime()
        playStartPts = pts
        audioActive = !reverseProvider() && !curvedProvider() && rate in 0.25..4.0
        if (audioActive) audio?.start(pts, rate.toFloat()) else audio?.stop()
    }
    private var audioActive = false

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
        val t0 = System.nanoTime()
        try {
            if (kotlin.math.abs(target - source.currentPts) > 1_000_000) program.resetStitch()
            val pts = source.seekAndShow(target, frameDurUs)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > 300 || pts < 0) android.util.Log.i("X5Player", "arama $target → $pts, $ms ms")
            drawNow()
            if (pts >= 0) postPosition(pts)
            if (playing) {
                rate = rateProvider(source.currentPts).coerceIn(0.05, 16.0)
                revTarget = -1
                anchor(source.currentPts)
                stats.pause()
            }
        } catch (t: Throwable) { error(t) }
    }

    /**
     * Oynatma saati (µs, klip zamanı). Ses çalıyorsa donanım zaman damgasından ara değerlenmiş
     * ses konumu; yoksa duvar saati. Her ikisi de sürekli ilerler, sıçramaz.
     */
    private fun clockUs(): Long {
        val el = (System.nanoTime() - playStartNs) / 1000
        return if (reverseProvider()) playStartPts - (el * rate).toLong() else playStartPts + (el * rate).toLong()
    }

    /**
     * Saati sese yumuşakça yaklaştır: ses saati ilk anlarda gecikmeli başlar ve sıçramalıdır;
     * doğrudan kullanmak görüntüde takılma yaratır. Büyük farkta (ses yeniden başladıysa) bir kerede eşitlenir.
     */
    private fun syncToAudio() {
        if (!audioActive || reverseProvider()) return
        val a = audio?.positionUs() ?: return
        val err = a - clockUs()
        if (kotlin.math.abs(err) > 400_000) return // ses henüz oturmadı / yeniden başlıyor
        playStartPts += err / 8
    }

    private var loopFrames = 0
    /** Oynatırken karenin ekranda görüneceği an (System.nanoTime); 0 = hemen. */
    private var presentAtNs = 0L
    private var lastLogNs = 0L

    /** Hız eğrisinde hız değiştikçe saati yeni hıza bağla (sıçrama olmadan). */
    private fun updateRate(pts: Long) {
        val r = rateProvider(pts).coerceIn(0.05, 16.0)
        if (kotlin.math.abs(r - rate) < 0.02 * rate) return
        val now = clockUs()
        rate = r
        playStartNs = System.nanoTime(); playStartPts = now
        // Sabit hızlı parçada hız değiştiyse sesi yeni hızla sürdür
        if (audioActive && !curvedProvider()) audio?.start(now, rate.toFloat())
    }

    private fun stopAtEnd() {
        playing = false
        audio?.stop()
        stats.pause()
        main.post { listener.onPlayingChanged(false) }
    }

    /** Ters oynatma: her adımda saatin gösterdiği (geriye akan) konuma atlanır. */
    private fun reverseStep() {
        val range = rangeProvider()
        val target = clockUs()
        if (target <= range.first) { stopAtEnd(); return }
        // Aynı kareyi yeniden çözme
        if (revTarget >= 0 && revTarget - target < frameDurUs) {
            Thread.sleep(4); handler.post(loop); return
        }
        val pts = source.seekAndShow(target, frameDurUs)
        if (pts < 0) { stopAtEnd(); return }
        revTarget = target
        drawNow()
        stats.frameShown()
        postPosition(pts)
        updateRate(pts)
        handler.post(loop)
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!playing || released) return
            try {
                if (reverseProvider()) { reverseStep(); return }
                // Geç kalan kareleri göstermeden atla (en fazla 6 art arda), böylece görüntü saati yakalar.
                var pts: Long
                var skipped = 0
                while (true) {
                    pts = source.next { p -> skipped >= 6 || p + frameDurUs >= clockUs() }
                    if (pts < 0 || source.lastRendered) break
                    skipped++
                    stats.frameDropped()
                }
                if (pts < 0) { android.util.Log.i("X5Player", "akış sonu"); stopAtEnd(); return }
                syncToAudio()
                if (++loopFrames % 60 == 0 || System.nanoTime() - lastLogNs > 3_000_000_000L) {
                    lastLogNs = System.nanoTime()
                    android.util.Log.i("X5Player", "oynatma pts=$pts saat=${clockUs()} hız=$rate atlanan=$skipped")
                }
                // Karenin zamanı gelene kadar bekle (erken çözüldüyse). Son ~8 ms beklenmez: kare, gösterileceği
                // an (sunum zamanı) bildirilerek ekrana verilir; ekran yenilemesine denk gelir, titreme olmaz.
                val waitUs = ((pts - clockUs()) / rate).toLong()
                val sleepUs = waitUs - 8_000
                if (sleepUs > 1000) Thread.sleep((sleepUs / 1000).coerceAtMost(100), ((sleepUs % 1000) * 1000).toInt())
                presentAtNs = System.nanoTime() + ((pts - clockUs()) / rate * 1000).toLong().coerceIn(0, 40_000_000)
                drawNow()
                presentAtNs = 0L
                stats.frameShown()
                postPosition(pts)
                updateRate(pts)
                handler.post(this)
            } catch (t: Throwable) { error(t) }
        }
    }

    private fun drawNow() {
        if (window == EGL14.EGL_NO_SURFACE || !::source.isInitialized || !source.hasFrame) return
        egl.makeCurrent(window)
        val t = source.currentPts
        if (t != stitchPts) {
            // Oynatırken birkaç karede bir, dururken her yeni karede ölç.
            // Oynarken GPU'yu bekletmeyen eşzamansız okuma; dururken bu karenin sonucu hemen gerekir.
            program.stitch?.async = playing
            // Telefon ısınınca dikiş ölçümü seyrekleşir
            val hot = (thermalTick++ % 60 == 0).let { if (it) { thermalHot = power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE }; thermalHot }
            program.updateStitch(source, if (playing) (if (hot) 30 else 10) else 1, if (playing) 0.4f else 0.7f)
            stitchPts = t
        }
        program.color = colorProvider()
        program.frameTime = t / 1e6f
        program.look = lookProvider()
        updatePerson(t)
        // Çıktı çerçevesi pencerenin ortasında (tuval kipinde küçültülmüş, etrafı görünür)
        val canvas = canvasProvider() && !clip.is360 && layoutProvider().size <= 1
        val fr = com.mhmmtbg.x5reframe.ui.FrameGeometry.frameRect(winW.toFloat(), winH.toFloat(), aspectProvider(), canvas)
        val fx = fr[0].toInt(); val fw = fr[2].toInt(); val fh = fr[3].toInt()
        val fy = winH - fr[1].toInt() - fh
        GLES30.glViewport(0, 0, winW, winH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (canvas) program.drawCanvas(winW, winH, fx, fy, fw, fh, sceneProvider(t).first(), source, baseProvider(t))
        else program.drawScene(fw, fh, layoutProvider(), sceneProvider(t), source, baseProvider(t), fx, fy)
        val layers = layersProvider()
        overlay?.retain(layers)
        overlay?.draw(fw, fh, layers, t, outProvider(), fx, fy)
        // Zaman damgası pencerede kalıcıdır: dururken "şimdi" verilir ki eski bir zaman kalmasın
        runCatching { egl.setPresentationTime(window, if (presentAtNs > 0) presentAtNs else System.nanoTime()) }
        egl.swap(window)
    }

    private val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private var thermalTick = 0
    private var thermalHot = false

    private var personMask: com.mhmmtbg.x5reframe.person.PersonMask? = null
    private var personPts = -1L
    private var personTick = 0

    /** Kişiyi ayır açıksa maskeyi güncelle (oynarken her 3 karede bir, dururken her karede). */
    private fun updatePerson(t: Long) {
        val lk = program.look
        if (lk.person == null || clip.is360) { program.personMask = 0; return }
        val pm = personMask ?: runCatching { com.mhmmtbg.x5reframe.person.PersonMask(clip.displayAspect, stream = true) }
            .onFailure { error(it) }.getOrNull()?.also { personMask = it } ?: return
        if (t != personPts && (!playing || personTick++ % (if (thermalHot) 6 else 3) == 0)) {
            runCatching { pm.update(program, source) }
            personPts = t
        }
        program.personMask = if (pm.valid) pm.texture else 0
    }

    /**
     * O anki görüntünün histogramı (kırmızı, yeşil, mavi; her biri 64 kutu, en yüksek kutu = 1).
     * Küçük bir yüzeye çizilip okunur; renk düzenlemesi dahildir.
     */
    fun requestHistogram(onReady: (FloatArray) -> Unit) = handler.post {
        if (released || !::source.isInitialized || !source.hasFrame) return@post
        runCatching {
            val w = 160; val h = 90
            val pb = egl.createPbuffer(w, h)
            egl.makeCurrent(pb)
            program.color = colorProvider()
            program.look = lookProvider()
            program.drawScene(w, h, layoutProvider(), sceneProvider(source.currentPts), source, baseProvider(source.currentPts))
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            if (window != EGL14.EGL_NO_SURFACE) egl.makeCurrent(window) else egl.makeNothingCurrent()
            egl.releaseSurface(pb)
            val hist = FloatArray(192)
            for (i in 0 until w * h) {
                for (ch in 0..2) hist[ch * 64 + ((buf.get(i * 4 + ch).toInt() and 255) shr 2)] += 1f
            }
            for (ch in 0..2) {
                // Uçlardaki kırpılmış değerler grafiği ezmesin diye ölçek ortadaki kutulardan
                val m = (1 until 63).maxOf { hist[ch * 64 + it] }.coerceAtLeast(1f)
                for (k in 0 until 64) hist[ch * 64 + k] = (hist[ch * 64 + k] / m).coerceAtMost(1f)
            }
            main.post { onReady(hist) }
        }
    }

    /**
     * O anki kareyi her renk ayarıyla ayrı ayrı küçük çizer (filtre / LUT önizlemeleri).
     * Kadraj, yerleşim ve katmanlar o anki haliyle; yalnızca renk değişir.
     */
    fun renderVariants(colors: List<FloatArray?>, w: Int, h: Int, onReady: (List<android.graphics.Bitmap>) -> Unit) = handler.post {
        if (released || !::source.isInitialized || !source.hasFrame) return@post
        runCatching {
            val pb = egl.createPbuffer(w, h)
            egl.makeCurrent(pb)
            program.look = lookProvider()
            val t = source.currentPts
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            val out = colors.map { col ->
                program.color = col
                program.drawScene(w, h, layoutProvider(), sceneProvider(t), source, baseProvider(t))
                buf.rewind()
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                buf.rewind()
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h, android.graphics.Matrix().apply { preScale(1f, -1f) }, false).also { bmp.recycle() }
            }
            program.color = colorProvider()
            if (window != EGL14.EGL_NO_SURFACE) egl.makeCurrent(window) else egl.makeNothingCurrent()
            egl.releaseSurface(pb)
            main.post { onReady(out) }
        }.onFailure { android.util.Log.w("X5Player", "önizleme çizilemedi: ${it.message}") }
    }

    /** O anki kareden küçük resim üretir (proje listesi için). */
    fun captureThumbnail(w: Int, h: Int, onReady: (android.graphics.Bitmap) -> Unit) = handler.post {
        if (released || !::source.isInitialized || !source.hasFrame) return@post
        runCatching {
            val pb = egl.createPbuffer(w, h)
            egl.makeCurrent(pb)
            program.drawScene(w, h, layoutProvider(), sceneProvider(source.currentPts), source, baseProvider(source.currentPts))
            overlay?.draw(w, h, layersProvider(), source.currentPts, outProvider())
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
            runCatching { personMask?.release() }
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

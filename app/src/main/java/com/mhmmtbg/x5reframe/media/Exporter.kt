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
        val format: Format = Format.MP4,
        /** Galeri yerine bu dosyaya yaz (önizleme kopyaları için). */
        val target: java.io.File? = null,
    )

    /** Çıktı biçimi: video, hareketli GIF ya da yalnızca ses (M4A). */
    enum class Format { MP4, GIF, AUDIO }

    data class Mix(
        val music: MusicSpec?, val originalVolume: Float,
        val voices: List<AudioMixer.VoiceSpec> = emptyList(), val duck: Boolean = false, val denoise: Boolean = false,
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
        val curvePoints: List<Double>? = null,
        /** Önceki parçadan bu parçaya örtüşmeli geçiş (çapraz geçiş, kaydırma…): tür ve süre. */
        val crossIn: com.mhmmtbg.x5reframe.project.Transition = com.mhmmtbg.x5reframe.project.Transition.CUT,
        val crossInUs: Long = 0,
        /** Parçanın orijinal ses seviyesi ve yükselme / alçalma. */
        val volume: Float = 1f,
        val audioFadeInUs: Long = 0,
        val audioFadeOutUs: Long = 0,
    ) {
        val effEndUs: Long get() = endUs.coerceAtMost(clip.durationUs)
        val map: com.mhmmtbg.x5reframe.model.SpeedMap by lazy { com.mhmmtbg.x5reframe.model.SpeedMap(effEndUs - startUs, speed, curve, curvePoints) }
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
        /** Bilgi notu (ör. telefon ısındığı için yavaşlatıldı). */
        fun onNote(text: String) {}
    }

    @Volatile var cancelled = false

    /** Kare çıkışı (video ya da GIF); [run] içinde kurulur. */
    private var emitFrame: (Long) -> Unit = {}

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
            if (j.speed != 1.0 || j.reverse || j.curve != com.mhmmtbg.x5reframe.model.SpeedCurve.NONE || j.crossInUs > 0 ||
                j.volume != 1f || j.audioFadeInUs > 0 || j.audioFadeOutUs > 0) return false
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
        val gif = output.format == Format.GIF
        val audioOnly = output.format == Format.AUDIO
        try {
            require(jobs.isNotEmpty()) { "Dışa aktarılacak parça yok" }
            mixSpec = if (gif) null else if (audioOnly) (output.mix ?: Mix(null, 1f)) else output.mix ?: if (output.includeAudio && !audioCopyable()) Mix(null, 1f) else null
            val mime = if (output.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            if (!gif && !audioOnly) checkSupported(mime, output.width, output.height)

            outUri = createOutput()
            val outPfd = output.target?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE) }
                ?: context.contentResolver.openFileDescriptor(outUri, "rw")!!
            // 360° çıktıda önce geçici dosyaya yazılır (moov kutusu düzenlenebilsin diye).
            val tmp = if (output.spherical && !gif && !audioOnly) java.io.File(context.cacheDir, "export360.mp4").also { it.delete() } else null
            if (!gif) muxer = if (tmp != null) MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val fpsHint = output.fps ?: jobs.first().clip.frameRate
            val frameDurHint = (1_000_000 / fpsHint).toLong()
            val plan = plan(frameDurHint)
            val totalOutUs = plan.totalFrames * frameDurHint
            if (mixSpec != null) {
                // Karışım yolu: AAC kodlayıcının biçimini almak için ilk sesi önceden üret.
                val segs = jobs.mapIndexed { i, j ->
                    AudioMixer.Seg(j.clip.uri, if (output.includeAudio && j.audible) j.clip.audioTrack else null, j.startUs,
                        plan.start[i] * frameDurHint, plan.frames[i] * frameDurHint, j.speed, j.volume, j.audioFadeInUs, j.audioFadeOutUs)
                }
                val ms = mixSpec!!
                mixer = AudioMixer(context, segs, ms.music, if (output.includeAudio) ms.originalVolume else 0f, totalOutUs, ms.voices, ms.duck, ms.denoise)
                aac = AacEncoder()
                mixTotalUs = totalOutUs
                var guard = 0
                while (aac!!.outputFormat == null && guard++ < 50) produceAudio(minOf(totalOutUs, (guard + 1) * 21_333L))
                audioFormat = aac!!.outputFormat
            } else if (output.includeAudio && !gif) {
                for (j in jobs) {
                    val a = j.clip.audioTrack ?: continue
                    context.contentResolver.openFileDescriptor(j.clip.uri, "r")!!.use { pfd ->
                        val ex = MediaExtractor(); ex.setDataSource(pfd.fileDescriptor)
                        audioFormat = ex.getTrackFormat(a); ex.release()
                    }
                    break
                }
            }

            if (audioOnly) {
                // Yalnızca ses: karışım zaman çizelgesi boyunca üretilip M4A'ya yazılır
                val af = audioFormat ?: throw IllegalStateException("Projede ses yok")
                audioTrack = muxer.addTrack(af)
                muxer.start(); muxerStarted = true
                pending.forEach { (b, i) -> muxer.writeSampleData(audioTrack, b, i); audioWritten++ }
                pending.clear()
                var tUs = 0L
                var last = 0L
                while (tUs < totalOutUs && !cancelled) {
                    tUs = minOf(totalOutUs, tUs + 200_000)
                    produceAudio(tUs)
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 250) { last = now; listener.onProgress(tUs.toFloat() / totalOutUs, (tUs / 1e6f) / ((now - t0) / 1000f).coerceAtLeast(0.01f)) }
                }
                if (cancelled) throw InterruptedException("İptal edildi")
                aac!!.finish(::onAacOutput)
                muxer.stop(); muxer.release()
                outPfd.close()
                publish(outUri)
                listener.onProgress(1f, 0f)
                listener.onDone(outUri, (SystemClock.elapsedRealtime() - t0) / 1000f)
                return
            }

            val fps = output.fps ?: jobs.first().clip.frameRate
            val frameDur = (1_000_000 / fps).toLong()
            val inputSurface = if (gif) null else {
                encoder = createEncoder(mime, fps)
                encoder.createInputSurface().also { encoder.start() }
            }

            val gl = EglCore()
            egl = gl
            val win = if (inputSurface != null) gl.createWindowSurface(inputSurface) else gl.createPbuffer(output.width, output.height)
            gl.makeCurrent(win)
            val overlay = com.mhmmtbg.x5reframe.gl.OverlayRenderer()
            // GIF: kareler okunup palete indirilir (en fazla ~15 kare/sn)
            val gifOut = if (gif) java.io.BufferedOutputStream(java.io.FileOutputStream(outPfd.fileDescriptor), 1 shl 16) else null
            val gifStepUs = (1_000_000 / (output.fps ?: 15f).coerceAtMost(25f)).toLong()
            val gifEnc = gifOut?.let { GifEncoder(it, output.width, output.height, (gifStepUs / 10_000).toInt().coerceAtLeast(2)) }
            val gifPix = if (gif) java.nio.ByteBuffer.allocateDirect(output.width * output.height * 4).order(java.nio.ByteOrder.nativeOrder()) else null
            val gifRow = ByteArray(if (gif) output.width * output.height * 4 else 0)
            var nextGifUs = 0L
            val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            var frameNo = 0
            var hot = false
            /** Çizilen kareyi çıktıya ver (video kodlayıcıya ya da GIF'e). */
            emitFrame = { tOutUs ->
                // Isınma: telefon çok ısınırsa kapanmasın diye kareler arasında kısa mola
                if (++frameNo % 30 == 0) {
                    val wasHot = hot
                    hot = power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
                    if (hot && !wasHot) listener.onNote("Telefon ısındı; dışa aktarım yavaşlatıldı")
                }
                if (hot) Thread.sleep(25)
                if (gifEnc != null) {
                    if (tOutUs >= nextGifUs) {
                        nextGifUs += gifStepUs
                        gifPix!!.rewind()
                        GLES30.glReadPixels(0, 0, output.width, output.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, gifPix)
                        val rowB = output.width * 4
                        for (y in 0 until output.height) { gifPix.position((output.height - 1 - y) * rowB); gifPix.get(gifRow, y * rowB, rowB) }
                        gifEnc.addFrame(gifRow)
                    }
                } else {
                    gl.setPresentationTime(win, tOutUs * 1000)
                    gl.swap(win)
                    drain(false)
                }
            }

            val totalOut = (plan.totalFrames * frameDur).coerceAtLeast(1)
            val progress = Progress(t0, totalOut) { 0L }
            val transition = if (plan.overlap.any { it > 0 }) com.mhmmtbg.x5reframe.gl.TransitionProgram() else null
            val fbo = if (transition != null) Array(2) { com.mhmmtbg.x5reframe.gl.Fbo(output.width, output.height) } else null
            var cur: JobRun? = null
            try {
                for (i in jobs.indices) {
                    if (cancelled) break
                    val run = cur ?: JobRun(jobs[i], frameDur, overlay, pfds)
                    cur = null
                    val job = run.job
                    val start = plan.start[i] * frameDur
                    val n = plan.overlap.getOrElse(i + 1) { 0 }
                    if (job.reverse) {
                        renderReverse(job, run.source, run.program, overlay, win, gl, frameDur, plan.frames[i], start, Progress(t0, totalOut) { start })
                        run.close()
                        continue
                    }
                    val audio = if (mixSpec == null && output.includeAudio && job.speed == 1.0 && job.curve == com.mhmmtbg.x5reframe.model.SpeedCurve.NONE) openAudio(run.pfd, job) else null
                    // Bu parçanın baştaki k0 karesi önceki geçişte çizildi
                    val k0 = plan.overlap[i].toLong()
                    for (k in k0 until plan.frames[i] - n) {
                        if (cancelled) break
                        val pts = run.frame(k) ?: break
                        emitFrame(start + k * frameDur)
                        audio?.writeUpTo(pts, start)
                        if (mixer != null) produceAudio(start + k * frameDur)
                        progress.report(start + k * frameDur)
                    }
                    if (n > 0 && !cancelled) {
                        // Örtüşmeli geçiş: A'nın son n karesi ile B'nin ilk n karesi birleştirilir
                        val nextRun = JobRun(jobs[i + 1], frameDur, overlay, pfds)
                        val type = jobs[i + 1].crossIn
                        for (q in 0 until n) {
                            if (cancelled) break
                            val ka = plan.frames[i] - n + q
                            fbo!![0].bind(); val pa = run.frame(ka)
                            fbo[1].bind(); nextRun.frame(q.toLong())
                            com.mhmmtbg.x5reframe.gl.Fbo.unbind()
                            transition!!.draw(fbo[0].tex, fbo[1].tex, output.width, output.height, (q + 0.5f) / n, type)
                            val tOut = start + ka * frameDur
                            emitFrame(tOut)
                            if (pa != null) audio?.writeUpTo(pa, start)
                            if (mixer != null) produceAudio(tOut)
                            progress.report(tOut)
                        }
                        cur = nextRun
                    }
                    audio?.writeUpTo(job.effEndUs, start)
                    audio?.close()
                    run.close()
                }
            } finally {
                runCatching { cur?.close() }
                transition?.release()
                fbo?.forEach { it.release() }
            }
            val timeline = totalOut
            overlay.release()
            if (cancelled) throw InterruptedException("İptal edildi")
            if (gifEnc != null) {
                gifEnc.finish(); gifOut.close()
                outPfd.close()
                egl.releaseSurface(win)
                publish(outUri)
                listener.onProgress(1f, 0f)
                listener.onDone(outUri, (SystemClock.elapsedRealtime() - t0) / 1000f)
                return
            }

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
            inputSurface?.release()
            publish(outUri)
            listener.onProgress(1f, 0f)
            listener.onDone(outUri, (SystemClock.elapsedRealtime() - t0) / 1000f)
        } catch (t: Throwable) {
            runCatching { if (::muxer.isInitialized) muxer.release() }
            val target = output.target
            if (target != null) runCatching { target.delete() }
            else outUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            listener.onFailed(if (t is InterruptedException) "İptal edildi" else (t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { mixer?.close() }
            runCatching { aac?.release() }
            runCatching { if (::encoder.isInitialized) { encoder.stop(); encoder.release() } }
            runCatching { egl?.release() }
            pfds.forEach { runCatching { it.close() } }
        }
    }

    /** Parçaların çıktıdaki başlangıç kareleri, kare sayıları ve önceki parçayla örtüşmeleri. */
    private class Plan(val start: LongArray, val frames: LongArray, val overlap: IntArray) {
        val totalFrames: Long get() = if (start.isEmpty()) 0 else start.last() + frames.last()
    }

    private fun plan(frameDur: Long): Plan {
        val frames = LongArray(jobs.size) { (jobs[it].outputDurationUs / frameDur).coerceAtLeast(1) }
        val overlap = IntArray(jobs.size) { i ->
            val j = jobs[i]
            if (i == 0 || j.crossInUs <= 0 || j.reverse || jobs[i - 1].reverse) 0
            else (j.crossInUs / frameDur).toInt().coerceIn(1, (minOf(frames[i - 1], frames[i]) / 2).toInt().coerceAtLeast(1))
        }
        val start = LongArray(jobs.size)
        for (i in 1 until jobs.size) start[i] = start[i - 1] + frames[i - 1] - overlap[i]
        return Plan(start, frames, overlap)
    }

    /** Bir parçanın kare kaynağı + çizim programı; kareleri istenen çıktı sırasına göre çizer. */
    private inner class JobRun(val job: Job, private val frameDur: Long, private val overlay: com.mhmmtbg.x5reframe.gl.OverlayRenderer,
                               pfds: MutableList<ParcelFileDescriptor>) : AutoCloseable {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(job.clip.uri, "r")!!.also { pfds += it }
        val program = Sources.program(job.clip, highQuality = true).apply {
            stitchInvDistance = job.stitchInv
            color = job.color
            look = job.look
            dynamicStitch = context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("dynamic_stitch", true)
        }
        val source: FrameSource = Sources.open(context, job.clip, pfd.fileDescriptor, realtime = false)
        private var started = false
        private var stitchPts = -1L
        private val person = if (job.look.person != null && !job.clip.is360)
            runCatching { com.mhmmtbg.x5reframe.person.PersonMask(job.clip.displayAspect, stream = true) }.getOrNull() else null

        /** k. çıktı karesini o an bağlı çerçeve tamponuna çiz; kaynak bittiyse null. */
        fun frame(k: Long): Long? {
            val tSrc = job.sourceAt(k * frameDur)
            if (!started) { if (source.seekAndShow(tSrc, frameDur) < 0) return null; started = true }
            while (source.currentPts + frameDur <= tSrc) {
                if (source.next { it + frameDur > tSrc } < 0) return null
            }
            val pts = source.currentPts
            program.fade = fadeFactor(job, k * frameDur)
            program.frameTime = k * frameDur / 1e6f
            if (pts != stitchPts) {
                program.updateStitch(source, 3, 0.5f)
                person?.let { pm -> runCatching { pm.update(program, source) }; program.personMask = if (pm.valid) pm.texture else 0 }
                stitchPts = pts
            }
            program.drawScene(output.width, output.height, job.layoutRects, job.sceneAt(pts), source, job.baseAt(pts))
            overlay.draw(output.width, output.height, job.layers, pts, job.effEndUs)
            return pts
        }

        override fun close() {
            runCatching { person?.release() }
            runCatching { source.release() }
            runCatching { program.release() }
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
                    overlay.draw(w, h, job.layers, pts[idx], job.effEndUs)
                    emitFrame(timeline + tOut)
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
        output.target?.let { return Uri.fromFile(it) }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val (name, mimeType, dir, collection) = when (output.format) {
            Format.MP4 -> listOf("X5_$stamp.mp4", "video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            Format.GIF -> listOf("X5_$stamp.gif", "image/gif", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            Format.AUDIO -> listOf("X5_$stamp.m4a", "audio/mp4", Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name as String)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType as String)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/X5Reframe")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection as Uri, values) ?: throw IllegalStateException("Çıktı dosyası oluşturulamadı")
    }

    private fun publish(uri: Uri) {
        if (output.target != null) return
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }
}

package com.mhmmtbg.x5reframe.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Exporter
import com.mhmmtbg.x5reframe.media.MusicSpec
import com.mhmmtbg.x5reframe.model.EditState
import com.mhmmtbg.x5reframe.project.ProjectAudio
import com.mhmmtbg.x5reframe.project.Segment
import com.mhmmtbg.x5reframe.project.Transition
import com.mhmmtbg.x5reframe.stab.StabMode
import com.mhmmtbg.x5reframe.stab.Stabilizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.LinkedBlockingQueue

/**
 * Dışa aktarım kuyruğu. İşler ön plan hizmetinde sırayla çalışır; ekran kapansa ya da düzenleyiciden
 * çıkılsa da devam eder. Arayüz durumu [state] üzerinden izler.
 */
object ExportManager {

    /** Kuyruğa giren bir dışa aktarım isteği (düzenlemelerin o anki kopyası). */
    data class Request(
        val id: Long,
        val title: String,
        val segments: List<Segment>,
        /** Çıktı ayarlarının alındığı düzenleme (oran, kalite, kodek, ses). */
        val output: EditState,
        val audio: ProjectAudio,
        val bitrateFactor: Float,
    ) {
        val durationUs: Long get() = segments.sumOf { it.outputDurationUs }
    }

    sealed interface Status {
        data object Idle : Status
        data class Running(val request: Request, val fraction: Float, val speed: Float, val queued: Int) : Status
        data class Done(val request: Request, val uri: Uri, val seconds: Float, val queued: Int) : Status
        data class Failed(val request: Request, val message: String, val queued: Int) : Status
    }

    private val _state = MutableStateFlow<Status>(Status.Idle)
    val state: StateFlow<Status> = _state

    private val queue = LinkedBlockingQueue<Request>()
    @Volatile private var current: Exporter? = null
    @Volatile private var currentReq: Request? = null
    private var worker: Thread? = null
    private var nextId = 1L

    val queuedCount: Int get() = queue.size

    @Synchronized
    fun enqueue(context: Context, title: String, segments: List<Segment>, output: EditState, audio: ProjectAudio, bitrateFactor: Float): Request {
        val r = Request(nextId++, title, segments, output, audio, bitrateFactor)
        queue.put(r)
        val app = context.applicationContext
        val intent = Intent(app, ExportService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        ensureWorker(app)
        return r
    }

    fun cancelCurrent() { current?.cancelled = true }

    fun cancelAll() {
        queue.clear()
        cancelCurrent()
    }

    @Synchronized
    private fun ensureWorker(context: Context) {
        if (worker?.isAlive == true) return
        worker = Thread({ loop(context) }, "export-queue").also { it.start() }
    }

    private fun loop(context: Context) {
        while (true) {
            val req = queue.poll() ?: break
            currentReq = req
            run(context, req)
            current = null
            currentReq = null
        }
        ExportService.stop(context)
    }

    private fun run(context: Context, req: Request) {
        val o = req.output
        val (w, h) = o.outputSize()
        val baseMbps = if (o.hevc) o.quality.mbpsAvc * 0.7f else o.quality.mbpsAvc.toFloat()
        val bitrate = (baseMbps * req.bitrateFactor * 1_000_000).toInt()
        val spherical = req.segments.all { it.edit.copy(aspect = o.aspect).isSpherical }
        val au = req.audio
        val mix = if (au.needsMix) Exporter.Mix(
            au.musicUri?.let { MusicSpec(it, au.musicVolume, au.loop, if (au.fade) 1_500_000 else 0, if (au.fade) 2_000_000 else 0) },
            au.originalVolume,
        ) else null
        val output = Exporter.Output(w, h, o.hevc, bitrate, o.includeAudio, spherical, mix)
        _state.value = Status.Running(req, 0f, 0f, queue.size)

        val jobs = runCatching { buildJobs(context, req.segments) }.getOrElse {
            _state.value = Status.Failed(req, it.message ?: "Klip açılamadı", queue.size); return
        }
        val ex = Exporter(context, output, jobs, object : Exporter.Listener {
            override fun onProgress(fraction: Float, speed: Float) {
                _state.value = Status.Running(req, fraction, speed, queue.size)
                ExportService.update(context, req.title, fraction, queue.size)
            }
            override fun onDone(uri: Uri, seconds: Float) {
                _state.value = Status.Done(req, uri, seconds, queue.size)
                ExportService.finished(context, req.title, uri, true, "${"%.1f".format(req.durationUs / 1e6)} sn video ${"%.0f".format(seconds)} sn'de hazır")
            }
            override fun onFailed(message: String) {
                _state.value = if (message == CANCELLED) Status.Idle else Status.Failed(req, message, queue.size)
                if (message != CANCELLED) ExportService.finished(context, req.title, null, false, message)
            }
        })
        current = ex
        ex.run()
    }

    const val CANCELLED = "İptal edildi"

    /** Parçalardan dışa aktarım işlerini kurar (klipleri açar, sabitleyiciyi hazırlar). */
    fun buildJobs(context: Context, segs: List<Segment>): List<Exporter.Job> {
        val cache = HashMap<String, ClipInfo>()
        val fade = 400_000L
        return segs.mapIndexed { i, seg ->
            val clip = cache.getOrPut(seg.clipUri.toString()) { ClipInfo.load(context, seg.clipUri, seg.lrvUri) }
            val e = seg.edit
            val stab = if (e.stabMode == StabMode.OFF) null else clip.imu?.let { Stabilizer(it, Stabilizer.rigFromImuFor(clip.cameraType)) }
            Exporter.Job(
                clip, e.inUs, e.outUs, { t -> e.sceneAt(t) },
                { t -> stab?.base(t / 1e6, e.stabMode, e.stabStrength, e.inUs / 1e6) },
                e.layout.rects, e.stitchInv, seg.speed,
                layers = e.layers,
                color = if (e.color.isIdentity) null else e.color.uniforms(),
                fadeInUs = if (i > 0 && segs[i - 1].transitionOut == Transition.FADE) fade else 0,
                fadeOutUs = if (i < segs.size - 1 && seg.transitionOut == Transition.FADE) fade else 0,
            )
        }
    }
}

package com.mhmmtbg.x5reframe

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Exporter
import com.mhmmtbg.x5reframe.media.Player
import com.mhmmtbg.x5reframe.ui.EditorActions
import com.mhmmtbg.x5reframe.ui.EditorController
import com.mhmmtbg.x5reframe.ui.EditorScreen
import com.mhmmtbg.x5reframe.ui.TimelineView
import com.mhmmtbg.x5reframe.ui.theme.Palette
import com.mhmmtbg.x5reframe.ui.theme.X5Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class EditorActivity : AppCompatActivity(), Player.Listener {

    private var controller: EditorController? = null
    private lateinit var store: com.mhmmtbg.x5reframe.project.ProjectStore
    private var project: com.mhmmtbg.x5reframe.project.Project? = null
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveTask = Runnable { saveNow() }
    private var player: Player? = null
    private var exporter: Exporter? = null
    private var userScrubbing = false
    private var loadError by mutableStateOf<String?>(null)
    private var loaded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = com.mhmmtbg.x5reframe.project.ProjectStore(this)
        val existing = intent.getStringExtra(EXTRA_PROJECT)?.let { store.get(it) }
        val uri: Uri = existing?.clipUri ?: intent.data ?: run { finish(); return }
        setContent {
            X5Theme {
                val c = controller
                if (loaded && c != null) {
                    EditorScreen(c, actions(c), ::createSurface, ::createTimeline)
                } else {
                    Box(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
                        Text(
                            loadError?.let { "Açılamadı: $it" } ?: "Klip okunuyor…",
                            color = if (loadError != null) Palette.Error else Palette.Muted,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
        lifecycleScope.launch {
            val lrvUri = existing?.lrvUri ?: intent.getStringExtra(EXTRA_LRV)?.let(Uri::parse)
            val r = withContext(Dispatchers.IO) { runCatching { ClipInfo.load(this@EditorActivity, uri, lrvUri) } }
            r.onSuccess { clip ->
                val name = existing?.clipName ?: displayName(uri)
                val proj = existing ?: withContext(Dispatchers.IO) { store.create(uri, name, clip.durationUs, clip.lrv?.uri) }
                project = proj
                val c = EditorController(clip, proj.name, proj.edit)
                val p = Player(this@EditorActivity, clip, this@EditorActivity)
                p.viewProvider = { t -> c.displayedView(t) }
                p.baseProvider = { t -> c.baseAt(t) }
                c.onChanged = {
                    p.requestRedraw()
                    saveHandler.removeCallbacks(saveTask)
                    saveHandler.postDelayed(saveTask, 800)
                }
                if (existing == null) saveHandler.postDelayed({ captureThumb() }, 1500)
                buildFilmstrip(clip)
                controller = c
                player = p
                loaded = true
            }.onFailure {
                loadError = (it.message ?: it.javaClass.simpleName) +
                    if (existing != null) "\n\nKlip taşınmış ya da silinmiş olabilir." else ""
            }
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "Klip")

    private fun actions(c: EditorController) = EditorActions(
        back = { finish() },
        playPause = {
            val p = player
            if (p != null) { if (p.playing) p.pause() else { c.clearLive(); p.play() } }
        },
        seek = { us -> c.clearLive(); c.positionUs = us; player?.seekTo(us) },
        startExport = { startExport(c) },
        cancelExport = { exporter?.cancelled = true },
        open = { uri ->
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        },
        share = { uri ->
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Paylaş"
                )
            )
        },
        setMuted = { player?.setMuted(it) },
        step = { fwd -> c.clearLive(); player?.step(fwd) },
    )

    // ---- Önizleme yüzeyi ----

    @SuppressLint("ClickableViewAccessibility")
    private fun createSurface(ctx: android.content.Context): SurfaceView {
        val sv = SurfaceView(ctx)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                player?.attachSurface(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { player?.detachSurface() }
        })
        var lastX = 0f
        var lastY = 0f
        var scaling = false
        val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                controller?.updateView { it.copy(fov = it.fov / d.scaleFactor) }
                return true
            }
        })
        sv.setOnTouchListener { v, e ->
            val c = controller ?: return@setOnTouchListener false
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (player?.playing == true) player?.pause()
                    c.beginViewGesture()
                    lastX = e.x; lastY = e.y; scaling = false
                }
                MotionEvent.ACTION_MOVE -> if (!scaling && e.pointerCount == 1) {
                    val degPerPx = c.currentView.fov / max(v.width, v.height)
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    c.updateView { it.copy(yaw = it.yaw - dx * degPerPx, pitch = it.pitch + dy * degPerPx) }
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx); lastY = e.getY(idx)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> c.endViewGesture()
            }
            true
        }
        return sv
    }

    private fun createTimeline(ctx: android.content.Context): TimelineView {
        val t = TimelineView(ctx)
        t.filmstrip = filmstrip
        timelineView = t
        t.listener = object : TimelineView.Listener {
            override fun onScrub(us: Long, fromUser: Boolean) {
                val c = controller ?: return
                c.clearLive()
                c.positionUs = us
                player?.seekTo(us)
            }
            override fun onScrubStart() { userScrubbing = true; player?.pause() }
            override fun onScrubEnd() {
                userScrubbing = false
                controller?.endTrimDrag()
            }
            override fun onTrimChanged(inUs: Long, outUs: Long) { controller?.dragTrim(inUs, outUs) }
        }
        return t
    }

    // ---- Dışa aktarım ----

    private fun startExport(c: EditorController) {
        if (exporter != null) return
        player?.pause()
        val s = c.state // dışa aktarım sırasında yapılan düzenlemeler bu işi etkilemesin
        val (w, h) = s.outputSize()
        val mbps = if (s.hevc) s.quality.mbpsAvc * 7 / 10 else s.quality.mbpsAvc
        val settings = Exporter.Settings(w, h, s.hevc, mbps * 1_000_000, s.inUs, s.outUs, s.includeAudio)
        c.exportUi = EditorController.ExportUi.Running(0f, 0f)
        val ex = Exporter(applicationContext, c.clip, settings, { t -> s.viewAt(t) }, baseAt = { t -> c.baseAt(t, s) }, listener = object : Exporter.Listener {
            override fun onProgress(fraction: Float, speed: Float) = runOnUiThread {
                if (exporter != null) c.exportUi = EditorController.ExportUi.Running(fraction, speed)
            }
            override fun onDone(uri: Uri, seconds: Float) = runOnUiThread {
                endExport()
                c.exportUi = EditorController.ExportUi.Done(uri, (s.outUs - s.inUs) / 1e6f, seconds)
                window.decorView.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                )
            }
            override fun onFailed(message: String) = runOnUiThread {
                endExport()
                if (message == CANCELLED) {
                    c.exportUi = EditorController.ExportUi.Idle
                    c.say("Dışa aktarım iptal edildi", false)
                } else {
                    c.exportUi = EditorController.ExportUi.Failed(message)
                }
            }
        })
        exporter = ex
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread({ ex.run() }, "export").start()
    }

    private fun endExport() {
        exporter = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- Player.Listener ----

    override fun onPosition(us: Long) {
        if (!userScrubbing) controller?.positionUs = us
    }

    override fun onPlayingChanged(playing: Boolean) {
        controller?.playing = playing
    }

    override fun onError(message: String) {
        controller?.say("Hata: $message", false)
    }

    private fun saveNow() {
        val c = controller ?: return
        val p = project ?: return
        val updated = p.copy(edit = c.state, updatedAt = System.currentTimeMillis())
        project = updated
        Thread { runCatching { store.save(updated) } }.start()
    }

    /** Zaman çizelgesi için LRV'den ön lens merkezini gösteren küçük kareler. */
    private var timelineView: TimelineView? = null
    private var filmstrip: List<android.graphics.Bitmap> = emptyList()

    private fun buildFilmstrip(clip: ClipInfo) {
        val lrv = clip.lrv ?: return
        Thread {
            val frames = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(this, lrv.uri)
                    val n = 12
                    val lens = clip.rig.lenses[0]
                    (0 until n).mapNotNull { i ->
                        val t = clip.durationUs * (i + 0.5) / n
                        r.getFrameAtTime(t.toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                            val half = f.width / 2
                            val cx = (lens.cx * half).toInt(); val cy = (lens.cy * f.height).toInt()
                            val side = (f.height * 0.55).toInt()
                            val x0 = (cx - side / 2).coerceIn(0, half - side); val y0 = (cy - side / 2).coerceIn(0, f.height - side)
                            android.graphics.Bitmap.createScaledBitmap(android.graphics.Bitmap.createBitmap(f, x0, y0, side, side), 96, 96, true)
                        }
                    }
                } finally { r.release() }
            }.getOrDefault(emptyList())
            runOnUiThread { filmstrip = frames; timelineView?.filmstrip = frames }
        }.start()
    }

    private fun captureThumb() {
        val p = project ?: return
        player?.captureThumbnail(384, 216) { bmp -> runCatching { store.saveThumbnail(p.id, bmp) } }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        saveHandler.removeCallbacks(saveTask)
        saveNow()
        captureThumb()
    }

    override fun onDestroy() {
        super.onDestroy()
        exporter?.cancelled = true
        player?.release()
    }

    companion object {
        const val CANCELLED = "İptal edildi"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_LRV = "lrv"
    }
}

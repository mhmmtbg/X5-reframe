package com.mhmmtbg.x5reframe

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Exporter
import com.mhmmtbg.x5reframe.media.Player
import com.mhmmtbg.x5reframe.model.Keyframe
import com.mhmmtbg.x5reframe.model.Keyframes
import com.mhmmtbg.x5reframe.model.ViewParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class EditorActivity : AppCompatActivity(), Player.Listener {

    private enum class Aspect(val label: String, val w: Int, val h: Int) {
        LANDSCAPE("16:9", 16, 9), PORTRAIT("9:16", 9, 16), SQUARE("1:1", 1, 1)
    }

    private enum class Quality(val label: String, val longSide: Int, val mbpsAvc: Int) {
        FHD("1080p", 1920, 18), QHD("2.7K", 2704, 32), UHD("4K", 3840, 55)
    }

    private lateinit var clip: ClipInfo
    private var player: Player? = null

    private lateinit var preview: SurfaceView
    private lateinit var container: FrameLayout
    private lateinit var timeText: TextView
    private lateinit var editText: TextView
    private lateinit var statusText: TextView
    private lateinit var seek: SeekBar
    private lateinit var progress: ProgressBar
    private lateinit var playBtn: Button
    private lateinit var exportBtn: Button

    private var aspect = Aspect.LANDSCAPE
    private var quality = Quality.FHD
    private var hevc = false

    private val keyframes = Keyframes()
    @Volatile private var editView = ViewParams()
    @Volatile private var manual = false
    private var positionUs = 0L
    private var inUs = 0L
    private var outUs = 0L
    private var userSeeking = false
    private var exporter: Exporter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        preview = findViewById(R.id.preview)
        container = findViewById(R.id.previewContainer)
        timeText = findViewById(R.id.timeText)
        editText = findViewById(R.id.editText)
        statusText = findViewById(R.id.statusText)
        seek = findViewById(R.id.seek)
        progress = findViewById(R.id.progress)
        playBtn = findViewById(R.id.playBtn)
        exportBtn = findViewById(R.id.exportBtn)

        val uri: Uri = intent.data ?: run { finish(); return }
        statusText.text = "Klip okunuyor…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ClipInfo.load(this@EditorActivity, uri) } }
            result.onSuccess { onClipLoaded(it) }.onFailure {
                statusText.text = "Açılamadı: ${it.message}"
            }
        }
    }

    private fun onClipLoaded(c: ClipInfo) {
        clip = c
        outUs = c.durationUs
        statusText.text = "${c.cameraType} · ${c.width}×${c.height} ×2 · ${"%.1f".format(c.durationUs / 1e6)} sn"
        val p = Player(this, c, this)
        p.viewProvider = { t -> currentView(t) }
        player = p
        setupSurface()
        setupControls()
        updateLabels()
    }

    /** O andaki kadraj: kullanıcı dokunuyorsa elle ayarlanan, değilse anahtar karelerden. */
    private fun currentView(t: Long): ViewParams = if (manual) editView else keyframes.at(t, editView)

    private fun setupSurface() {
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                player?.attachSurface(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { player?.detachSurface() }
        })
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
        fitPreview()
        setupGestures()
    }

    /** Önizlemeyi çıktı en-boy oranına sığdır. */
    private fun fitPreview() {
        val cw = container.width; val ch = container.height
        if (cw == 0 || ch == 0) return
        val target = aspect.w.toFloat() / aspect.h
        var w = cw; var h = (cw / target).toInt()
        if (h > ch) { h = ch; w = (ch * target).toInt() }
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h
            preview.layoutParams = lp
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        var lastX = 0f; var lastY = 0f
        var scaling = false
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                editView = editView.copy(fov = editView.fov / detector.scaleFactor).clamped()
                player?.requestRedraw(); updateLabels()
                return true
            }
        })
        preview.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!manual) { editView = currentView(positionUs); manual = true }
                    lastX = e.x; lastY = e.y; scaling = false
                }
                MotionEvent.ACTION_MOVE -> if (!scaling && e.pointerCount == 1) {
                    val degPerPx = editView.fov / max(v.width, v.height)
                    editView = editView.copy(
                        yaw = editView.yaw - (e.x - lastX) * degPerPx,
                        pitch = editView.pitch + (e.y - lastY) * degPerPx,
                    ).clamped()
                    lastX = e.x; lastY = e.y
                    player?.requestRedraw(); updateLabels()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Kalan parmaktan devam ederken sıçramayı önle.
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx); lastY = e.getY(idx)
                }
            }
            true
        }
    }

    private fun setupControls() {
        playBtn.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.playing) p.pause() else { manual = false; p.play() }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    manual = false
                    player?.seekTo(clip.durationUs * value / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true; player?.pause() }
            override fun onStopTrackingTouch(sb: SeekBar) { userSeeking = false }
        })
        findViewById<Button>(R.id.inBtn).setOnClickListener {
            inUs = positionUs.coerceAtMost(outUs - 100_000); updateLabels()
        }
        findViewById<Button>(R.id.outBtn).setOnClickListener {
            outUs = positionUs.coerceAtLeast(inUs + 100_000); updateLabels()
        }
        findViewById<Button>(R.id.keyBtn).setOnClickListener {
            keyframes.put(Keyframe(positionUs, currentView(positionUs)))
            editView = currentView(positionUs)
            manual = false
            updateLabels()
            toast("Anahtar kare eklendi (${fmt(positionUs)})")
        }
        findViewById<Button>(R.id.keyDelBtn).setOnClickListener {
            val removed = keyframes.removeNear(positionUs)
            if (!removed && !keyframes.isEmpty) toast("Bu konumda anahtar kare yok")
            player?.requestRedraw(); updateLabels()
        }
        findViewById<Button>(R.id.aspectBtn).setOnClickListener {
            aspect = Aspect.values()[(aspect.ordinal + 1) % Aspect.values().size]
            fitPreview(); updateLabels()
        }
        findViewById<Button>(R.id.resBtn).setOnClickListener {
            quality = Quality.values()[(quality.ordinal + 1) % Quality.values().size]; updateLabels()
        }
        findViewById<Button>(R.id.codecBtn).setOnClickListener { hevc = !hevc; updateLabels() }
        findViewById<Button>(R.id.wideBtn).setOnClickListener {
            if (!manual) { editView = currentView(positionUs); manual = true }
            editView = editView.copy(wide = !editView.wide).clamped()
            player?.requestRedraw(); updateLabels()
        }
        findViewById<Button>(R.id.levelBtn).setOnClickListener {
            if (!manual) { editView = currentView(positionUs); manual = true }
            editView = editView.copy(pitch = 0.0, roll = 0.0, fov = 90.0)
            player?.requestRedraw(); updateLabels()
        }
        exportBtn.setOnClickListener { if (exporter == null) startExport() else exporter?.cancelled = true }
    }

    private fun outputSize(): Pair<Int, Int> {
        val long = quality.longSide
        fun even(x: Int) = x / 2 * 2
        return when (aspect) {
            Aspect.LANDSCAPE -> long to even(long * 9 / 16)
            Aspect.PORTRAIT -> even(long * 9 / 16) to long
            Aspect.SQUARE -> even(long * 9 / 16) to even(long * 9 / 16)
        }
    }

    private fun startExport() {
        player?.pause()
        val (w, h) = outputSize()
        val mbps = if (hevc) quality.mbpsAvc * 7 / 10 else quality.mbpsAvc
        val kf = Keyframes().also { k -> keyframes.items.forEach { k.put(it) } }
        val fixed = if (keyframes.isEmpty) editView else null
        val settings = Exporter.Settings(w, h, hevc, mbps * 1_000_000, inUs, outUs)
        val ex = Exporter(applicationContext, clip, settings, { t -> fixed ?: kf.at(t, editView) }, object : Exporter.Listener {
            override fun onProgress(fraction: Float, speed: Float) = runOnUiThread {
                progress.progress = (fraction * 1000).toInt()
                if (speed > 0) statusText.text = "Dışa aktarılıyor… %${(fraction * 100).toInt()} · ${"%.1f".format(speed)}× hız"
            }
            override fun onDone(uri: Uri, seconds: Float) = runOnUiThread {
                endExport()
                val dur = (outUs - inUs) / 1e6f
                statusText.text = "Bitti: ${"%.1f".format(dur)} sn video ${"%.1f".format(seconds)} sn'de (${"%.1f".format(dur / seconds)}×). Filmler/X5Reframe"
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Videoyu aç"))
            }
            override fun onFailed(message: String) = runOnUiThread {
                endExport()
                statusText.text = "Dışa aktarılamadı: $message"
            }
        })
        exporter = ex
        exportBtn.text = "İptal"
        progress.visibility = View.VISIBLE
        progress.progress = 0
        statusText.text = "Dışa aktarılıyor… ${w}×$h ${if (hevc) "H.265" else "H.264"}"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread({ ex.run() }, "export").start()
    }

    private fun endExport() {
        exporter = null
        exportBtn.text = "Dışa aktar"
        progress.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateLabels() {
        if (!::clip.isInitialized) return
        val (w, h) = outputSize()
        findViewById<Button>(R.id.aspectBtn).text = aspect.label
        findViewById<Button>(R.id.resBtn).text = quality.label
        findViewById<Button>(R.id.codecBtn).text = if (hevc) "H.265" else "H.264"
        findViewById<Button>(R.id.wideBtn).text = if (currentView(positionUs).wide) "Geniş ✓" else "Geniş"
        val v = currentView(positionUs)
        timeText.text = "${fmt(positionUs)} / ${fmt(clip.durationUs)}   yön ${v.yaw.toInt()}° eğim ${v.pitch.toInt()}° açı ${v.fov.toInt()}°"
        editText.text = "Aralık ${fmt(inUs)} – ${fmt(outUs)} (${"%.1f".format((outUs - inUs) / 1e6)} sn) · " +
            "${keyframes.items.size} anahtar kare · çıktı ${w}×$h"
    }

    private fun fmt(us: Long): String {
        val s = us / 1_000_000.0
        return "%d:%04.1f".format((s / 60).toInt(), s % 60)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // Player.Listener
    override fun onPosition(us: Long) {
        positionUs = us
        if (!userSeeking && clip.durationUs > 0) seek.progress = (us * 1000 / clip.durationUs).toInt()
        updateLabels()
    }

    override fun onPlayingChanged(playing: Boolean) {
        playBtn.text = if (playing) "❚❚" else "▶"
    }

    override fun onError(message: String) {
        statusText.text = "Hata: $message"
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exporter?.cancelled = true
        player?.release()
    }
}

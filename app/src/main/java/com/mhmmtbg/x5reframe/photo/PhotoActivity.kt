package com.mhmmtbg.x5reframe.photo

import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.model.Aspect
import com.mhmmtbg.x5reframe.model.ColorGrade
import com.mhmmtbg.x5reframe.model.Projection
import com.mhmmtbg.x5reframe.model.ViewParams
import com.mhmmtbg.x5reframe.ui.Glyphs
import com.mhmmtbg.x5reframe.ui.theme.Palette
import com.mhmmtbg.x5reframe.ui.theme.X5Theme
import kotlin.math.max

/** 360 fotoğraf (.insp): serbest bakış, görünüm modları, oran, filtre ve tam çözünürlükte kaydetme. */
class PhotoActivity : AppCompatActivity() {

    private var info: PhotoInfo? = null
    private var error by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var view by mutableStateOf(ViewParams(fov = 100.0))
    private var aspect by mutableStateOf(Aspect.LANDSCAPE)
    private var filter by mutableStateOf(ColorGrade.Filter.NONE)
    private var saving by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    private val thread = HandlerThread("photo-gl").apply { start() }
    private val gl = Handler(thread.looper)
    private var egl: EglCore? = null
    private var program: ReframeProgram? = null
    private var source: PhotoFrameSource? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        setContent { X5Theme { Screen() } }
        gl.post {
            try {
                val pi = PhotoInfo.load(this, uri)
                info = pi
                val bmp = PhotoInfo.decode(this, pi, 6144)
                val e = egl ?: EglCore().also { egl = it }
                e.makeNothingCurrent()
                source = PhotoFrameSource(bmp)
                bmp.recycle()
                program = ReframeProgram(pi.rig, highQualityStitch = true).also { p ->
                    p.dynamicStitch = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dynamic_stitch", true)
                    if (p.dynamicStitch) repeat(2) { p.stitch.estimate(source!!, 1f) }
                }
                runOnUiThread { ready = true }
                draw()
            } catch (t: Throwable) {
                runOnUiThread { error = t.message ?: t.javaClass.simpleName }
            }
        }
    }

    private fun draw() = gl.post {
        val e = egl ?: return@post
        val p = program ?: return@post
        val s = source ?: return@post
        if (window == EGL14.EGL_NO_SURFACE || winW == 0) return@post
        e.makeCurrent(window)
        p.color = ColorGrade(filter).let { if (it.isIdentity) null else it.uniforms() }
        // Önizlemede seçili oranı ortala
        val ar = aspect.w.toFloat() / aspect.h
        var w = winW; var h = (winW / ar).toInt()
        if (h > winH) { h = winH; w = (winH * ar).toInt() }
        android.opengl.GLES30.glViewport(0, 0, winW, winH)
        android.opengl.GLES30.glClearColor(0f, 0f, 0f, 1f)
        android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT)
        p.drawPane((winW - w) / 2, (winH - h) / 2, w, h, view, s, null)
        e.swap(window)
    }

    private fun createSurface(ctx: android.content.Context): SurfaceView {
        val sv = SurfaceView(ctx)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                gl.post {
                    val e = egl ?: EglCore().also { egl = it }
                    if (window != EGL14.EGL_NO_SURFACE) e.releaseSurface(window)
                    window = e.createWindowSurface(holder.surface)
                    winW = width; winH = height
                }
                draw()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val done = java.util.concurrent.CountDownLatch(1)
                gl.post {
                    egl?.let { if (window != EGL14.EGL_NO_SURFACE) { it.makeNothingCurrent(); it.releaseSurface(window) } }
                    window = EGL14.EGL_NO_SURFACE
                    done.countDown()
                }
                done.await(1, java.util.concurrent.TimeUnit.SECONDS)
            }
        })
        var lastX = 0f; var lastY = 0f; var scaling = false
        val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                view = view.copy(fov = view.fov / d.scaleFactor).clamped(); draw(); return true
            }
        })
        sv.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; scaling = false }
                MotionEvent.ACTION_MOVE -> if (!scaling && e.pointerCount == 1) {
                    val degPerPx = view.fov / max(v.width, v.height)
                    view = view.copy(yaw = view.yaw - (e.x - lastX) * degPerPx, pitch = view.pitch + (e.y - lastY) * degPerPx).clamped()
                    lastX = e.x; lastY = e.y
                    draw()
                }
                MotionEvent.ACTION_POINTER_UP -> { val i = if (e.actionIndex == 0) 1 else 0; lastX = e.getX(i); lastY = e.getY(i) }
            }
            true
        }
        return sv
    }

    private enum class Mode(val label: String) { NORMAL("Doğrusal"), WIDE("Geniş"), PLANET("Küçük gezegen"), TUNNEL("Tünel"), PANO("360°") }

    private fun apply(m: Mode) {
        val W = Projection.WIDE
        view = when (m) {
            Mode.NORMAL -> view.copy(projection = Projection.RECTILINEAR, fov = 100.0, pitch = view.pitch.coerceIn(-45.0, 45.0))
            Mode.WIDE -> view.copy(projection = Projection.SUPERWIDE, fov = 140.0)
            Mode.PLANET -> view.copy(projection = W, pitch = -90.0, roll = 0.0, fov = 250.0)
            Mode.TUNNEL -> view.copy(projection = W, pitch = 90.0, roll = 0.0, fov = 250.0)
            Mode.PANO -> view.copy(projection = Projection.EQUIRECT, fov = 360.0, pitch = 0.0, roll = 0.0)
        }
        if (m == Mode.PANO) aspect = Aspect.PANO else if (aspect == Aspect.PANO) aspect = Aspect.LANDSCAPE
        if ((m == Mode.PLANET || m == Mode.TUNNEL) && aspect == Aspect.LANDSCAPE) aspect = Aspect.SQUARE
        draw()
    }

    private fun save() {
        val pi = info ?: return
        saving = true
        val pano = view.projection == Projection.EQUIRECT
        val long = if (pano) minOf(pi.width, 8192) else 4096
        val w = (if (aspect.w >= aspect.h) long else long * aspect.w / aspect.h) / 2 * 2
        val h = (if (aspect.w >= aspect.h) long * aspect.h / aspect.w else long) / 2 * 2
        val v = view; val f = filter
        Thread({
            val ok = runCatching {
                val src = PhotoInfo.decode(this, pi, 8192)
                val bmp = StillRenderer.render(pi.rig, w, h, listOf(floatArrayOf(0f, 0f, 1f, 1f)), listOf(v), null,
                    ColorGrade(f).let { if (it.isIdentity) null else it.uniforms() },
                    getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dynamic_stitch", true)) { PhotoFrameSource(src).also { src.recycle() } }
                StillRenderer.save(this, bmp, "X5P").also { bmp.recycle() } != null
            }.getOrElse { false }
            runOnUiThread { saving = false; message = if (ok) "Kaydedildi · ${w}×${h} · Resimler/X5Reframe" else "Kaydedilemedi" }
        }, "photo-save").start()
    }

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = ::createSurface, modifier = Modifier.fillMaxSize())
            Row(Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { finish() }, contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Back, "Geri", tint = Color.White)
                }
                Text(intent.data?.lastPathSegment?.substringAfterLast('/') ?: "Fotoğraf", Modifier.weight(1f), color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(if (saving) "Kaydediliyor…" else "Kaydet", color = Palette.OnAccent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Accent)
                        .clickable(enabled = ready && !saving) { save() }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            error?.let { Text("Açılamadı: $it", color = Palette.Error, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
            if (!ready && error == null) Text("Fotoğraf yükleniyor…", color = Color.White, modifier = Modifier.align(Alignment.Center))
            message?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).background(Color(0xCC202329), RoundedCornerShape(16.dp))
                    .clickable { message = null }.padding(horizontal = 14.dp, vertical = 8.dp)) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x80000000)).navigationBarsPadding().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chips(Mode.values().toList(), { it.label }, { false }) { apply(it) }
                Chips(listOf(Aspect.LANDSCAPE, Aspect.PORTRAIT, Aspect.SQUARE, Aspect.FOUR_FIVE, Aspect.PANO), { it.label }, { it == aspect }) { aspect = it; draw() }
                Chips(ColorGrade.Filter.values().toList(), { it.label }, { it == filter }) { filter = it; draw() }
            }
        }
    }

    @Composable
    private fun <T> Chips(items: List<T>, label: (T) -> String, selected: (T) -> Boolean, onClick: (T) -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                val on = selected(item)
                Text(label(item), color = if (on) Palette.OnAccent else Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Accent else Color(0x33FFFFFF))
                        .clickable { onClick(item) }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gl.post {
            runCatching { source?.release() }
            runCatching { program?.release() }
            runCatching { egl?.release() }
            thread.quitSafely()
        }
    }
}

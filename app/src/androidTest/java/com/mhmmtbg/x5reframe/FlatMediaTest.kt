package com.mhmmtbg.x5reframe

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES30
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.insv.ClipKind
import com.mhmmtbg.x5reframe.media.Exporter
import com.mhmmtbg.x5reframe.media.Sources
import com.mhmmtbg.x5reframe.model.FrameFit
import com.mhmmtbg.x5reframe.model.ViewParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v3.0 her format: telefonda üretilen düz MP4 (90° döndürme bilgili) ve PNG fotoğrafın
 * açılması, doğru yönde çizilmesi, sığdır/doldur yerleşimi ve 360° klip ile aynı projede dışa aktarımı.
 */
@RunWith(AndroidJUnit4::class)
class FlatMediaTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    /** 320x240 kare, sol üst çeyrek kırmızı, gerisi gri; 90° döndürme ipucu, [seconds] sn, 30 fps. */
    private fun makeVideo(file: File, seconds: Int = 2, rotation: Int = 90, ramp: Boolean = false) {
        val w = 320; val h = 240; val fps = 30
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = enc.createInputSurface()
        enc.start()
        val egl = EglCore()
        val win = egl.createWindowSurface(input)
        egl.makeCurrent(win)
        val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mux.setOrientationHint(rotation)
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return else continue }
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.outputFormat); mux.start(); continue }
                if (i < 0) continue
                val b = enc.getOutputBuffer(i)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && track >= 0) mux.writeSampleData(track, b, info)
                enc.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        for (k in 0 until seconds * fps) {
            GLES30.glViewport(0, 0, w, h)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            val g = if (ramp) 0.1f + 0.8f * k / (seconds * fps) else 0.5f
            GLES30.glClearColor(g, g, g, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(0, h / 2, w / 2, h / 2) // GL'de y aşağıdan: üst sol çeyrek
            GLES30.glClearColor(1f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            egl.setPresentationTime(win, k * 1_000_000_000L / fps)
            egl.swap(win)
            drain(false)
        }
        enc.signalEndOfInputStream()
        drain(true)
        mux.stop(); mux.release()
        enc.stop(); enc.release()
        egl.releaseSurface(win); input.release(); egl.release()
    }

    private fun makeImage(file: File) {
        val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(30, 30, 200))
        c.drawRect(0f, 0f, 200f, 150f, Paint().apply { color = Color.rgb(0, 220, 0) })
        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Klibi [w]x[h] pbuffer'a çiz ve pikselleri (üst satır başta) döndür. */
    private fun render(clip: ClipInfo, w: Int, h: Int, fit: FrameFit, view: ViewParams = ViewParams(), look: com.mhmmtbg.x5reframe.model.FlatLook? = null): IntArray {
        val egl = EglCore()
        val pb = egl.createPbuffer(w, h)
        egl.makeCurrent(pb)
        val pfd = ctx.contentResolver.openFileDescriptor(clip.uri, "r")!!
        val program = Sources.program(clip).apply { this.look = look ?: com.mhmmtbg.x5reframe.model.FlatLook(fit) }
        val src = Sources.open(ctx, clip, pfd.fileDescriptor, realtime = false)
        try {
            assertTrue(src.seekAndShow(500_000, 33_333) >= 0)
            program.draw(w, h, view, src)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val o = ((h - 1 - y) * w + x) * 4
                px[y * w + x] = Color.rgb(buf.get(o).toInt() and 255, buf.get(o + 1).toInt() and 255, buf.get(o + 2).toInt() and 255)
            }
            return px
        } finally {
            src.release(); program.release(); pfd.close(); egl.releaseSurface(pb); egl.release()
        }
    }

    private fun isRed(c: Int) = Color.red(c) > 170 && Color.green(c) < 90 && Color.blue(c) < 90
    private fun isGray(c: Int) = Color.red(c) in 90..170 && Color.green(c) in 90..170 && Color.blue(c) in 90..170
    private fun isBlack(c: Int) = Color.red(c) < 25 && Color.green(c) < 25 && Color.blue(c) < 25
    private fun isGreen(c: Int) = Color.green(c) > 170 && Color.red(c) < 90 && Color.blue(c) < 90

    @Test
    fun opensRotatedVideoAndPhoto() {
        val vid = File(ctx.cacheDir, "flat_rot90.mp4").also { makeVideo(it) }
        val clip = ClipInfo.load(ctx, Uri.fromFile(vid))
        android.util.Log.i("FlatMediaTest", "clip kind=${clip.kind} ${clip.width}x${clip.height} rot=${clip.rotation} dur=${clip.durationUs} fps=${clip.frameRate}")
        assertEquals(ClipKind.FLAT, clip.kind)
        assertEquals(90, clip.rotation)
        assertEquals(240, clip.displayWidth); assertEquals(320, clip.displayHeight)
        assertTrue(clip.durationUs in 1_800_000..2_200_000)

        // 240x320 çıktıya sığdır: 90° saat yönünde dönünce sol üst kırmızı çeyrek sağ üste gelir.
        val w = 240; val h = 320
        val px = render(clip, w, h, FrameFit.FIT)
        val tr = px[(h / 4) * w + 3 * w / 4]; val tl = px[(h / 4) * w + w / 4]; val br = px[(3 * h / 4) * w + 3 * w / 4]
        android.util.Log.i("FlatMediaTest", "rot: tl=${Integer.toHexString(tl)} tr=${Integer.toHexString(tr)} br=${Integer.toHexString(br)}")
        assertTrue("döndürme uygulanmadı (sağ üst kırmızı değil)", isRed(tr))
        assertTrue("sol üst gri olmalı", isGray(tl))
        assertTrue("sağ alt gri olmalı", isGray(br))

        // Yatay aynalama: kırmızı çeyrek sol üste geçer; sağ yarıyı kırpınca (yalnızca gri) kırmızı kalmaz.
        val flip = render(clip, w, h, FrameFit.FIT, look = com.mhmmtbg.x5reframe.model.FlatLook(flipH = true))
        assertTrue("aynalamada sol üst kırmızı olmalı", isRed(flip[(h / 4) * w + w / 4]))
        val crop = render(clip, w, h, FrameFit.FILL, look = com.mhmmtbg.x5reframe.model.FlatLook(FrameFit.FILL, com.mhmmtbg.x5reframe.model.CropRect(0f, 0.55f, 1f, 1f)))
        assertTrue("kırpılan alt kısımda kırmızı olmamalı", !isRed(crop[(h / 4) * w + 3 * w / 4]) && !isRed(crop[(3 * h / 4) * w + 3 * w / 4]))

        // Yatay 16:9 çıktıda sığdır → yanlar siyah; doldur → kenarlar görüntü.
        val W = 320; val H = 180
        val fitPx = render(clip, W, H, FrameFit.FIT)
        assertTrue("sığdırda kenar siyah olmalı", isBlack(fitPx[(H / 2) * W + 5]))
        val fillPx = render(clip, W, H, FrameFit.FILL)
        assertTrue("doldurda kenar siyah olmamalı", !isBlack(fillPx[(H / 2) * W + 5]))
        val blurPx = render(clip, W, H, FrameFit.BLUR)
        assertTrue("bulanık arka plan siyah olmamalı", !isBlack(blurPx[(H / 2) * W + 5]))
        // 2x yakınlaştırma: görüntü x≈93..227 yerine ≈26..294 aralığını kaplar
        assertTrue("sığdırda x=60 siyah olmalı", isBlack(fitPx[(H / 2) * W + 60]))
        val zoomPx = render(clip, W, H, FrameFit.FIT, ViewParams(fov = 45.0))
        assertTrue("yakınlaştırmada x=60 görüntü olmalı", !isBlack(zoomPx[(H / 2) * W + 60]))

        // Fotoğraf
        val img = File(ctx.cacheDir, "photo.png").also { makeImage(it) }
        val ic = ClipInfo.load(ctx, Uri.fromFile(img))
        assertEquals(ClipKind.IMAGE, ic.kind)
        assertEquals(400, ic.width)
        val ip = render(ic, 400, 300, FrameFit.FIT)
        val g = ip[75 * 400 + 100]; val bl = ip[225 * 400 + 300]
        android.util.Log.i("FlatMediaTest", "photo tl=${Integer.toHexString(g)} br=${Integer.toHexString(bl)}")
        assertTrue("fotoğraf sol üst yeşil olmalı: ${Integer.toHexString(g)} / sağ alt ${Integer.toHexString(bl)}", isGreen(g))
        assertTrue("fotoğraf sağ alt mavi olmalı: ${Integer.toHexString(bl)}", Color.blue(bl) > 150)
    }

    @Test
    fun exportsMixedSequence() {
        val vid = File(ctx.cacheDir, "flat_a.mp4").also { makeVideo(it, 2, 0) }
        val img = File(ctx.cacheDir, "photo_b.png").also { makeImage(it) }
        val flat = ClipInfo.load(ctx, Uri.fromFile(vid))
        val photo = ClipInfo.load(ctx, Uri.fromFile(img))
        val jobs = mutableListOf(
            Exporter.Job(flat, 0, 1_500_000, { listOf(ViewParams()) }, look = com.mhmmtbg.x5reframe.model.FlatLook(FrameFit.BLUR)),
            Exporter.Job(photo, 0, 1_000_000, { t -> listOf(ViewParams(fov = 90.0 / (1.0 + t / 2e6))) }, fadeInUs = 300_000),
        )
        val insv = File("/data/local/tmp/clip.insv")
        if (insv.exists()) jobs += Exporter.Job(ClipInfo.load(ctx, Uri.fromFile(insv)), 0, 1_000_000, { listOf(ViewParams()) })
        val done = CountDownLatch(1)
        var outUri: Uri? = null; var error: String? = null
        val ex = Exporter(ctx, Exporter.Output(640, 360, false, 4_000_000), jobs, object : Exporter.Listener {
            override fun onProgress(fraction: Float, speed: Float) {}
            override fun onDone(uri: Uri, seconds: Float) { outUri = uri; done.countDown() }
            override fun onFailed(message: String) { error = message; done.countDown() }
        })
        Thread { ex.run() }.start()
        assertTrue("zaman aşımı", done.await(10, TimeUnit.MINUTES))
        assertTrue("dışa aktarım hatası: $error", error == null && outUri != null)
        val mx = MediaExtractor()
        ctx.contentResolver.openFileDescriptor(outUri!!, "r")!!.use { mx.setDataSource(it.fileDescriptor) }
        var durUs = 0L
        for (i in 0 until mx.trackCount) {
            val f = mx.getTrackFormat(i)
            android.util.Log.i("FlatMediaTest", "out track $i: $f")
            if (f.getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) durUs = f.getLong(MediaFormat.KEY_DURATION)
        }
        mx.release()
        val expected = jobs.sumOf { it.endUs - it.startUs }
        android.util.Log.i("FlatMediaTest", "mixed export dur=$durUs expected=$expected frames=${ex.videoWritten} audio=${ex.audioWritten}")
        assertTrue("süre beklenenden farklı: $durUs / $expected", kotlin.math.abs(durUs - expected) < 200_000)
    }

    /** Ters oynatma: parlaklığı zamanla artan videonun tersinde ilk kare son kareden parlak olmalı. Hız eğrisiyle süre değişir. */
    @Test
    fun exportsReversedAndCurved() {
        val vid = File(ctx.cacheDir, "ramp.mp4").also { makeVideo(it, 3, 0, ramp = true) }
        val clip = ClipInfo.load(ctx, Uri.fromFile(vid))
        val jobs = listOf(
            Exporter.Job(clip, 0, 2_000_000, { listOf(ViewParams()) }, reverse = true),
            Exporter.Job(clip, 0, 2_000_000, { listOf(ViewParams()) }, curve = com.mhmmtbg.x5reframe.model.SpeedCurve.MONTAGE),
        )
        val done = CountDownLatch(1)
        var outUri: Uri? = null; var error: String? = null
        val ex = Exporter(ctx, Exporter.Output(320, 240, false, 2_000_000), jobs, object : Exporter.Listener {
            override fun onProgress(fraction: Float, speed: Float) {}
            override fun onDone(uri: Uri, seconds: Float) { outUri = uri; done.countDown() }
            override fun onFailed(message: String) { error = message; done.countDown() }
        })
        Thread { ex.run() }.start()
        assertTrue("zaman aşımı", done.await(10, TimeUnit.MINUTES))
        assertTrue("dışa aktarım hatası: $error", error == null && outUri != null)
        val r = android.media.MediaMetadataRetriever()
        ctx.contentResolver.openFileDescriptor(outUri!!, "r")!!.use { r.setDataSource(it.fileDescriptor) }
        fun lum(us: Long): Int = r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST)!!.let { b -> Color.red(b.getPixel(b.width / 2, b.height / 2)) }
        val first = lum(50_000); val last = lum(1_900_000)
        val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000
        r.release()
        val expected = 2_000_000 + com.mhmmtbg.x5reframe.model.SpeedMap(2_000_000, 1.0, com.mhmmtbg.x5reframe.model.SpeedCurve.MONTAGE).outputUs
        android.util.Log.i("FlatMediaTest", "reverse first=$first last=$last dur=$dur expected=$expected")
        assertTrue("ters oynatmada ilk kare daha parlak olmalı: $first / $last", first > last + 40)
        assertTrue("eğrili süre beklenenden farklı: $dur / $expected", kotlin.math.abs(dur - expected) < 250_000)
    }

    @Test
    fun editorOpensFlatClip() {
        val vid = File(ctx.cacheDir, "flat_ui.mp4").also { makeVideo(it, 3, 0) }
        ActivityScenario.launch<EditorActivity>(Intent(ctx, EditorActivity::class.java).setData(Uri.fromFile(vid))).use { sc ->
            Thread.sleep(5000)
            var loaded = false
            sc.onActivity { loaded = it.isLoadedForTest }
            shot("ui_dark_flat_editor")
            assertTrue("düz klip düzenleyicide açılmadı", loaded)
        }
    }

    private fun shot(name: String) {
        val bmp = inst.uiAutomation.takeScreenshot()
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/x5test")
        }
        runCatching {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

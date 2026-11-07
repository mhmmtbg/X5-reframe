package com.mhmmtbg.x5reframe

import android.content.ContentValues
import android.net.Uri
import android.opengl.GLES30
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Exporter
import com.mhmmtbg.x5reframe.model.ColorGrade
import com.mhmmtbg.x5reframe.model.ViewParams
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Performans ölçümleri (CI'da eğilim izlemek için): 720p normal video dışa aktarımı (düz ve renk + keskinlik ile),
 * örnek 360° klip varsa onun dışa aktarımı. Sonuçlar İndirilenler/x5test/perf.txt'ye yazılır.
 */
@RunWith(AndroidJUnit4::class)
class PerfTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val lines = StringBuilder()

    private fun note(s: String) { lines.appendLine(s); android.util.Log.i("PerfTest", s) }

    private fun makeVideo(file: File, w: Int, h: Int, seconds: Int) {
        val fps = 30
        val fmt = android.media.MediaFormat.createVideoFormat(android.media.MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = enc.createInputSurface(); enc.start()
        val egl = com.mhmmtbg.x5reframe.gl.EglCore(); val win = egl.createWindowSurface(input); egl.makeCurrent(win)
        val mux = android.media.MediaMuxer(file.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = android.media.MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (i == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return else continue }
                if (i == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.outputFormat); mux.start(); continue }
                if (i < 0) continue
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && track >= 0) mux.writeSampleData(track, enc.getOutputBuffer(i)!!, info)
                enc.releaseOutputBuffer(i, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        for (k in 0 until seconds * fps) {
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor((k % 30) / 30f, 0.4f, 0.6f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            egl.setPresentationTime(win, k * 1_000_000_000L / fps); egl.swap(win); drain(false)
        }
        enc.signalEndOfInputStream(); drain(true)
        mux.stop(); mux.release(); enc.stop(); enc.release()
        egl.releaseSurface(win); input.release(); egl.release()
    }

    private fun export(jobs: List<Exporter.Job>, w: Int, h: Int): Pair<Float, Float> {
        val done = CountDownLatch(1)
        var secs = 0f; var error: String? = null
        val ex = Exporter(ctx, Exporter.Output(w, h, false, 12_000_000), jobs, object : Exporter.Listener {
            override fun onProgress(fraction: Float, speed: Float) {}
            override fun onDone(uri: Uri, seconds: Float) { secs = seconds; done.countDown(); ctx.contentResolver.delete(uri, null, null) }
            override fun onFailed(message: String) { error = message; done.countDown() }
        })
        Thread { ex.run() }.start()
        assertTrue("zaman aşımı", done.await(15, TimeUnit.MINUTES))
        assertTrue("dışa aktarım hatası: $error", error == null)
        val dur = jobs.sumOf { it.outputDurationUs } / 1e6f
        return secs to dur / secs.coerceAtLeast(0.01f)
    }

    @Test
    fun exportSpeed() {
        try {
            val vid = File(ctx.cacheDir, "perf720.mp4").also { makeVideo(it, 1280, 720, 4) }
            val clip = ClipInfo.load(ctx, Uri.fromFile(vid))
            val (s1, x1) = export(listOf(Exporter.Job(clip, 0, 4_000_000, { listOf(ViewParams()) })), 1280, 720)
            note("flat 720p düz: ${"%.1f".format(s1)} sn, ${"%.2f".format(x1)}× gerçek zaman")
            val graded = ColorGrade(filter = ColorGrade.Filter.CINEMA, sharpen = 0.5f, vignette = 0.4f, grain = 0.3f).uniforms()
            val (s2, x2) = export(listOf(Exporter.Job(clip, 0, 4_000_000, { listOf(ViewParams()) }, color = graded)), 1280, 720)
            note("flat 720p renk+keskinlik+gren: ${"%.1f".format(s2)} sn, ${"%.2f".format(x2)}× gerçek zaman")
            val insv = File("/data/local/tmp/clip.insv")
            if (insv.exists()) {
                val c360 = ClipInfo.load(ctx, Uri.fromFile(insv))
                val (s3, x3) = export(listOf(Exporter.Job(c360, 0, 3_000_000, { listOf(ViewParams(fov = 100.0)) })), 1280, 720)
                note("360 → 720p: ${"%.1f".format(s3)} sn, ${"%.2f".format(x3)}× gerçek zaman")
            }
            assertTrue("dışa aktarım çok yavaş: $x1", x1 > 0.05f)
        } finally {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "perf.txt")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/x5test")
            }
            runCatching {
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(lines.toString().toByteArray()) }
            }
        }
    }
}

package com.mhmmtbg.x5reframe.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Sources
import com.mhmmtbg.x5reframe.media.Waveform
import com.mhmmtbg.x5reframe.model.Aspect
import com.mhmmtbg.x5reframe.model.Curve
import com.mhmmtbg.x5reframe.model.FlatLook
import com.mhmmtbg.x5reframe.model.FrameFit
import com.mhmmtbg.x5reframe.model.Keyframe
import com.mhmmtbg.x5reframe.model.ViewParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Normal videolar için otomatik düzenleme yardımcıları:
 * - [scan]: belirli aralıklarla hareket, ses ve yüz (konum, boyut) ölçümü
 * - [highlights]: en ilgi çekici bölümleri seçer (öne çıkanlar)
 * - [reframe]: dikey / kare çıktıda konuyu ortada tutan kadraj anahtar kareleri
 * - [silences]: sessiz aralıklar (kesip atmak için)
 */
object FlatAuto {
    /** faceX, faceY: kaynak görüntüde 0..1 (yüz yoksa NaN). */
    data class Sample(val tUs: Long, val motion: Float, val audio: Float, val faceX: Float, val faceY: Float, val faceSize: Float)

    fun scan(context: Context, clip: ClipInfo, fromUs: Long, toUs: Long, stepUs: Long = 400_000,
             cancelled: () -> Boolean = { false }, progress: (Float) -> Unit = {}): List<Sample> {
        val aspect = clip.displayAspect.coerceIn(0.3f, 3.5f)
        val w = 256; val h = (256 / aspect).toInt().coerceIn(64, 512) / 2 * 2
        val wave = clip.audioTrack?.let { Waveform.compute(context, clip.uri, it, cancelled) }
        val egl = EglCore()
        val pb = egl.createPbuffer(w, h)
        egl.makeCurrent(pb)
        val pfd = context.contentResolver.openFileDescriptor(clip.uri, "r")!!
        val program = Sources.program(clip).apply { look = FlatLook(FrameFit.FILL) }
        val source = Sources.open(context, clip, pfd.fileDescriptor, realtime = false)
        val faces = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.06f).build()
        )
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val out = ArrayList<Sample>()
        try {
            val frameDur = (1_000_000 / clip.frameRate).toLong()
            var prev: FloatArray? = null
            var t = fromUs
            while (t < toUs && !cancelled()) {
                val moved = if (!source.hasFrame || t < source.currentPts || t - source.currentPts > 3_000_000) source.seekAndShow(t, frameDur) >= 0
                else { var ok = true; while (source.currentPts + frameDur <= t) { if (source.next { it + frameDur > t } < 0) { ok = false; break } }; ok }
                if (!moved) break
                program.draw(w, h, ViewParams(), source)
                buf.rewind()
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                val lum = FloatArray((w / 4) * (h / 4)) { i ->
                    val x = (i % (w / 4)) * 4; val y = (i / (w / 4)) * 4
                    val o = (y * w + x) * 4
                    ((buf.get(o).toInt() and 255) + (buf.get(o + 1).toInt() and 255) + (buf.get(o + 2).toInt() and 255)) / 3f
                }
                val motion = prev?.let { p -> var d = 0f; for (i in lum.indices) d += abs(lum[i] - p[i]); d / lum.size / 255f } ?: 0f
                prev = lum
                buf.rewind()
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
                val bmp = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false)
                raw.recycle()
                val fs = runCatching { Tasks.await(faces.process(InputImage.fromBitmap(bmp, 0))) }.getOrDefault(emptyList())
                bmp.recycle()
                val big = fs.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val audio = wave?.let { wv -> val i = (t / Waveform.BIN_US).toInt(); if (i in wv.indices) wv[i] else 0f } ?: 0f
                out += if (big != null) Sample(t, motion, audio, big.boundingBox.exactCenterX() / w, big.boundingBox.exactCenterY() / h, big.boundingBox.width().toFloat() / w)
                else Sample(t, motion, audio, Float.NaN, Float.NaN, 0f)
                progress(((t - fromUs).toFloat() / (toUs - fromUs)).coerceIn(0f, 1f))
                t += stepUs
            }
        } finally {
            runCatching { faces.close() }
            runCatching { source.release() }
            runCatching { program.release() }
            runCatching { pfd.close() }
            runCatching { egl.releaseSurface(pb) }
            runCatching { egl.release() }
        }
        return out
    }

    /**
     * Öne çıkan bölümler: her an için ilgi puanı (hareket + ses + yüz), [shotUs] uzunluğunda pencerelerde toplanır;
     * en yüksek puanlı çakışmayan pencereler [targetUs] dolana kadar seçilir ve zaman sırasıyla döner.
     */
    fun highlights(samples: List<Sample>, targetUs: Long, shotUs: Long): List<LongRange> {
        if (samples.size < 3) return emptyList()
        val step = samples[1].tUs - samples[0].tUs
        val mMax = samples.maxOf { it.motion }.coerceAtLeast(1e-4f)
        val score = samples.map { s ->
            val m = s.motion / mMax
            // Çok hızlı hareket (sarsıntı) biraz cezalandırılır
            0.45f * (m - 0.6f * maxOf(0f, m - 0.8f)) + 0.35f * s.audio + if (!s.faceX.isNaN()) 0.25f + 0.4f * s.faceSize.coerceAtMost(0.5f) else 0f
        }
        val win = (shotUs / step).toInt().coerceAtLeast(1)
        val sums = (0..samples.size - win).map { i -> i to (i until i + win).sumOf { score[it].toDouble() } }
        val chosen = ArrayList<Int>()
        var total = 0L
        for ((i, _) in sums.sortedByDescending { it.second }) {
            if (total >= targetUs) break
            if (chosen.any { abs(it - i) < win }) continue
            chosen += i; total += shotUs
        }
        return chosen.sorted().map { i -> samples[i].tUs..(samples[i].tUs + shotUs).coerceAtMost(samples.last().tUs + step) }
    }

    /**
     * Konuyu (en büyük yüz) çıktıda ortada tutan kadraj: [aspect] çıktısı, [Doldur] yerleşiminde.
     * Yüz yoksa merkez; hareket yumuşatılır, kenarlar dışarı taşmaz.
     */
    fun reframe(samples: List<Sample>, srcAspect: Float, aspect: Aspect, everyUs: Long = 1_000_000): List<Keyframe> {
        if (samples.isEmpty()) return emptyList()
        val outW = aspect.w.toFloat(); val outH = aspect.h.toFloat()
        val (hx, hy) = ReframeProgram.flatHalf(outW, outH, srcAspect, FrameFit.FILL)
        val sx = if (outW >= outH) 1f else outW / outH
        val sy = if (outW >= outH) outH / outW else 1f
        // Yüz yokken son bilinen konumu koru, en başta merkez
        var lastX = 0.5f; var lastY = 0.45f
        val xs = samples.map { s -> if (!s.faceX.isNaN()) { lastX = s.faceX; lastY = s.faceY }; lastX to lastY }
        // Yumuşatma (±1,5 sn)
        val step = if (samples.size > 1) samples[1].tUs - samples[0].tUs else 400_000L
        val r = (1_500_000 / step).toInt().coerceAtLeast(1)
        val sm = xs.indices.map { i ->
            val a = maxOf(0, i - r); val b = minOf(xs.size - 1, i + r)
            (a..b).map { xs[it].first }.average().toFloat() to (a..b).map { xs[it].second }.average().toFloat()
        }
        val keys = ArrayList<Keyframe>()
        var next = samples.first().tUs
        for (i in samples.indices) {
            if (samples[i].tUs < next) continue
            next += everyUs
            val (u, v) = sm[i]
            // Kaynak noktasını ekran merkezine getiren kayma (çıktı uzayı), kenar sınırına kırpılır
            val cx = ((u - 0.5f) * 2 * hx).coerceIn(-(hx - sx).coerceAtLeast(0f), (hx - sx).coerceAtLeast(0f))
            val cy = (-(v - 0.5f) * 2 * hy).coerceIn(-(hy - sy).coerceAtLeast(0f), (hy - sy).coerceAtLeast(0f))
            keys += Keyframe(samples[i].tUs, ViewParams(yaw = cx * 45.0, pitch = cy * 45.0, fov = 90.0), Curve.EASE_IN_OUT)
        }
        return keys
    }

    /** Sessiz aralıklar (en az [minUs]); dalga biçiminin sessizlik eşiğine göre. */
    fun silences(wave: FloatArray, fromUs: Long, toUs: Long, minUs: Long = 700_000, padUs: Long = 150_000): List<LongRange> {
        val a = (fromUs / Waveform.BIN_US).toInt().coerceIn(0, wave.size); val b = (toUs / Waveform.BIN_US).toInt().coerceIn(0, wave.size)
        if (b - a < 4) return emptyList()
        val part = wave.copyOfRange(a, b).sorted()
        val floor = part[(part.size * 0.1).toInt()]; val loud = part[(part.size * 0.9).toInt().coerceAtMost(part.size - 1)]
        val th = floor + (loud - floor) * 0.12f + 0.02f
        val out = ArrayList<LongRange>()
        var i = a
        while (i < b) {
            if (wave[i] >= th) { i++; continue }
            var j = i
            while (j < b && wave[j] < th) j++
            val s = i * Waveform.BIN_US + padUs; val e = j * Waveform.BIN_US - padUs
            if (e - s >= minUs - 2 * padUs && j - i >= (minUs / Waveform.BIN_US)) out += s.coerceAtLeast(fromUs)..e.coerceAtMost(toUs)
            i = j
        }
        return out
    }
}

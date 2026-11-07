package com.mhmmtbg.x5reframe.stab

import android.content.Context
import android.opengl.GLES30
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Sources
import com.mhmmtbg.x5reframe.model.FlatLook
import com.mhmmtbg.x5reframe.model.FrameFit
import com.mhmmtbg.x5reframe.model.ViewParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Hareket sensörü olmayan (normal) videolar için görüntüden sabitleme.
 * Kareler arası kayma ve dönme, küçük gri tonlu karelerde blok eşleştirmeyle ölçülür; birikimli yörünge
 * zamanda yumuşatılır ve fark, örnekleme konumu kaydırılarak (hafif yakınlaştırmayla) telafi edilir.
 *
 * Yörünge kaynağın kendi birimindedir: x, y görüntü genişliği / yüksekliği oranı, a radyan.
 */
class FlatStab(val fps: Float, val x: FloatArray, val y: FloatArray, val a: FloatArray) {
    val size get() = x.size

    private var cacheStrength = -1f
    private var cx = FloatArray(0); private var cy = FloatArray(0); private var ca = FloatArray(0)
    private var zoom = 1f

    /** Gauss yumuşatma (σ kare cinsinden). */
    private fun smooth(v: FloatArray, sigma: Float): FloatArray {
        val r = (sigma * 3).toInt().coerceAtLeast(1)
        val w = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        return FloatArray(v.size) { i ->
            var s = 0f; var ws = 0f
            for (k in -r..r) {
                val j = (i + k).coerceIn(0, v.size - 1)
                s += v[j] * w[k + r]; ws += w[k + r]
            }
            s / ws
        }
    }

    @Synchronized private fun prepare(strength: Float) {
        if (strength == cacheStrength) return
        cacheStrength = strength
        val sigma = 3f + 45f * strength
        val sx = smooth(x, sigma); val sy = smooth(y, sigma); val sa = smooth(a, sigma)
        cx = FloatArray(size) { x[it] - sx[it] }
        cy = FloatArray(size) { y[it] - sy[it] }
        ca = FloatArray(size) { a[it] - sa[it] }
        // Kenarlar görünmesin diye gereken yakınlaştırma (aşırı uçlar hariç %98)
        val need = FloatArray(size) { i -> 2 * maxOf(abs(cx[i]), abs(cy[i])) + abs(ca[i]) * 0.9f }.sorted()
        zoom = (1f + (need.getOrElse((size * 0.98).toInt().coerceAtMost(size - 1)) { 0f })).coerceIn(1f, 1.35f)
    }

    /** t anındaki düzeltme: (kayma x, kayma y, dönme, yakınlaştırma) — shader'a "temel" olarak verilir. */
    fun correction(tUs: Long, strength: Float): DoubleArray? {
        if (size == 0) return null
        prepare(strength)
        val f = (tUs / 1e6 * fps).coerceIn(0.0, (size - 1).toDouble())
        val i = f.toInt(); val j = minOf(i + 1, size - 1); val u = (f - i).toFloat()
        fun lerp(v: FloatArray) = (v[i] + (v[j] - v[i]) * u).toDouble()
        return doubleArrayOf(lerp(cx), lerp(cy), lerp(ca), zoom.toDouble())
    }

    fun toFloats(): FloatArray = FloatArray(1 + size * 3).also { o ->
        o[0] = fps
        for (i in 0 until size) { o[1 + i * 3] = x[i]; o[2 + i * 3] = y[i]; o[3 + i * 3] = a[i] }
    }

    companion object {
        fun fromFloats(v: FloatArray): FlatStab? {
            if (v.size < 4) return null
            val n = (v.size - 1) / 3
            return FlatStab(v[0], FloatArray(n) { v[1 + it * 3] }, FloatArray(n) { v[2 + it * 3] }, FloatArray(n) { v[3 + it * 3] })
        }

        private const val W = 160

        /**
         * Klibin tamamını tarayıp kare kare hareketi ölçer.
         * @param progress 0..1
         */
        fun analyze(context: Context, clip: ClipInfo, cancelled: () -> Boolean, progress: (Float) -> Unit): FlatStab {
            val aspect = clip.displayAspect.coerceIn(0.3f, 3.5f)
            val w = W; val h = (W / aspect).toInt().coerceIn(48, 300) / 2 * 2
            val egl = EglCore()
            val pb = egl.createPbuffer(w, h)
            egl.makeCurrent(pb)
            val pfd = context.contentResolver.openFileDescriptor(clip.uri, "r")!!
            val program = Sources.program(clip).apply { look = FlatLook(FrameFit.FILL) }
            val source = Sources.open(context, clip, pfd.fileDescriptor, realtime = false)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val angs = ArrayList<Float>()
            try {
                val frameDur = (1_000_000 / clip.frameRate).toLong()
                var prev: FloatArray? = null
                var X = 0f; var Y = 0f; var A = 0f
                if (source.seekAndShow(0, frameDur) < 0) throw IllegalStateException("Video çözülemedi")
                while (!cancelled()) {
                    program.draw(w, h, ViewParams(), source)
                    buf.rewind()
                    GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                    val lum = FloatArray(w * h) { i ->
                        val r = buf.get(i * 4).toInt() and 255; val g = buf.get(i * 4 + 1).toInt() and 255; val b = buf.get(i * 4 + 2).toInt() and 255
                        (r * 0.299f + g * 0.587f + b * 0.114f)
                    }
                    val p = prev
                    if (p != null) {
                        val m = motion(p, lum, w, h)
                        // GL satırları aşağıdan yukarı: y işaretini çevir
                        X += m[0] / w; Y += -m[1] / h; A += -m[2]
                    }
                    xs += X; ys += Y; angs += A
                    prev = lum
                    progress((source.currentPts.toFloat() / clip.durationUs).coerceIn(0f, 1f))
                    if (source.next(true) < 0) break
                }
                val fps = if (xs.size > 1) (xs.size - 1) / (source.currentPts / 1e6f).coerceAtLeast(0.001f) else clip.frameRate
                return FlatStab(fps.coerceIn(1f, 240f), xs.toFloatArray(), ys.toFloatArray(), angs.toFloatArray())
            } finally {
                runCatching { source.release() }
                runCatching { program.release() }
                runCatching { pfd.close() }
                runCatching { egl.releaseSurface(pb) }
                runCatching { egl.release() }
            }
        }

        /**
         * İki kare arasındaki hareket (dx, dy piksel; dönme radyan): 5×3 blokta en iyi kayma, sonra
         * dayanıklı (ortanca) kayma ve en küçük kareler dönme.
         */
        fun motion(a: FloatArray, b: FloatArray, w: Int, h: Int): FloatArray {
            val bs = 16; val rng = 8
            val cols = 5; val rows = 3
            val px = ArrayList<Float>(); val py = ArrayList<Float>(); val vx = ArrayList<Float>(); val vy = ArrayList<Float>()
            for (r in 0 until rows) for (c in 0 until cols) {
                val x0 = rng + (w - 2 * rng - bs) * c / (cols - 1)
                val y0 = rng + (h - 2 * rng - bs) * r / (rows - 1)
                // Dokusuz bloklar güvenilmez
                var mean = 0f
                for (yy in 0 until bs step 2) for (xx in 0 until bs step 2) mean += a[(y0 + yy) * w + x0 + xx]
                mean /= (bs * bs / 4)
                var tex = 0f
                for (yy in 0 until bs step 2) for (xx in 0 until bs step 2) tex += abs(a[(y0 + yy) * w + x0 + xx] - mean)
                if (tex / (bs * bs / 4) < 3f) continue
                var best = Float.MAX_VALUE; var bdx = 0; var bdy = 0
                for (dy in -rng..rng) for (dx in -rng..rng) {
                    var sad = 0f
                    for (yy in 0 until bs step 2) {
                        val ra = (y0 + yy) * w + x0; val rb = (y0 + yy + dy) * w + x0 + dx
                        for (xx in 0 until bs step 2) sad += abs(a[ra + xx] - b[rb + xx])
                        if (sad >= best) break
                    }
                    if (sad < best) { best = sad; bdx = dx; bdy = dy }
                }
                px += (x0 + bs / 2f) - w / 2f; py += (y0 + bs / 2f) - h / 2f; vx += bdx.toFloat(); vy += bdy.toFloat()
            }
            if (vx.size < 3) return floatArrayOf(0f, 0f, 0f)
            val tx = vx.sorted()[vx.size / 2]; val ty = vy.sorted()[vy.size / 2]
            var num = 0f; var den = 0f
            for (i in vx.indices) {
                val rx = vx[i] - tx; val ry = vy[i] - ty
                if (abs(rx) > 4 || abs(ry) > 4) continue // aykırı (hareketli nesne)
                // v ≈ t + θ × (-py, px)
                num += rx * -py[i] + ry * px[i]
                den += px[i] * px[i] + py[i] * py[i]
            }
            val th = if (den > 0) (num / den).coerceIn(-0.05f, 0.05f) else 0f
            return floatArrayOf(tx, ty, th)
        }

        @Suppress("unused") private fun rot(x: Float, y: Float, t: Float) = floatArrayOf(x * cos(t) - y * sin(t), x * sin(t) + y * cos(t))
    }
}

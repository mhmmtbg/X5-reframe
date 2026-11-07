package com.mhmmtbg.x5reframe.ui

import com.mhmmtbg.x5reframe.model.Keyframe
import com.mhmmtbg.x5reframe.model.ViewParams
import kotlin.math.abs

/**
 * Kayıt modunda toplanan sık bakış örneklerini az sayıda anahtar kareye indirger:
 * 1) yön açısı sarılmadan açılır (−180/180 sıçraması yok),
 * 2) kısa hareketli ortalamayla titreme süzülür,
 * 3) Ramer–Douglas–Peucker ile doğrusal aradeğerlemeden en çok 1,5° sapan noktalar tutulur.
 */
object PathSimplifier {
    private const val TOL_DEG = 1.5
    private const val MAX_YAW_STEP = 90.0

    fun simplify(samples: List<Keyframe>): List<Keyframe> {
        if (samples.size < 3) return samples
        val n = samples.size
        val yaw = DoubleArray(n); val pitch = DoubleArray(n); val fov = DoubleArray(n)
        yaw[0] = samples[0].view.yaw
        for (i in 1 until n) yaw[i] = yaw[i - 1] + ViewParams.wrap(samples[i].view.yaw - samples[i - 1].view.yaw)
        for (i in 0 until n) { pitch[i] = samples[i].view.pitch; fov[i] = samples[i].view.fov }
        val sy = smooth(yaw); val sp = smooth(pitch); val sf = smooth(fov)
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        rdp(samples, sy, sp, sf, 0, n - 1, keep)
        // Bir aralıkta 90°'den fazla dönüş varsa ara noktalar ekle (kısa yol yorumu bozulmasın).
        var last = 0
        for (i in 1 until n) {
            if (abs(sy[i] - sy[last]) > MAX_YAW_STEP) { keep[i - 1] = true; last = i - 1 }
            if (keep[i]) last = i
        }
        return (0 until n).filter { keep[it] }.map { i ->
            Keyframe(samples[i].timeUs, samples[i].view.copy(yaw = sy[i], pitch = sp[i], fov = sf[i]).clamped(), com.mhmmtbg.x5reframe.model.Curve.LINEAR)
        }
    }

    private fun smooth(a: DoubleArray): DoubleArray {
        val r = 3
        return DoubleArray(a.size) { i ->
            var s = 0.0; var c = 0
            for (j in maxOf(0, i - r)..minOf(a.size - 1, i + r)) { s += a[j]; c++ }
            s / c
        }
    }

    private fun rdp(k: List<Keyframe>, y: DoubleArray, p: DoubleArray, f: DoubleArray, a: Int, b: Int, keep: BooleanArray) {
        if (b - a < 2) return
        val ta = k[a].timeUs.toDouble(); val tb = k[b].timeUs.toDouble()
        var worst = -1; var worstD = TOL_DEG
        for (i in a + 1 until b) {
            val u = if (tb > ta) (k[i].timeUs - ta) / (tb - ta) else 0.0
            val d = maxOf(
                abs(y[i] - (y[a] + (y[b] - y[a]) * u)),
                abs(p[i] - (p[a] + (p[b] - p[a]) * u)),
                abs(f[i] - (f[a] + (f[b] - f[a]) * u)) * 0.5,
            )
            if (d > worstD) { worstD = d; worst = i }
        }
        if (worst < 0) return
        keep[worst] = true
        rdp(k, y, p, f, a, worst, keep)
        rdp(k, y, p, f, worst, b, keep)
    }
}

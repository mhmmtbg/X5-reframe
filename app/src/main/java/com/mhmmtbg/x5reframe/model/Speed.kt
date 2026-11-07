package com.mhmmtbg.x5reframe.model

/**
 * Hız eğrisi: parçanın kaynağı boyunca hız çarpanı değişir (yavaş-hızlı-yavaş gibi).
 * [points] kaynağın eşit aralıklı konumlarındaki çarpanlar; aralar yumuşakça doldurulur.
 */
enum class SpeedCurve(val label: String, private val points: DoubleArray) {
    NONE("Yok", doubleArrayOf(1.0, 1.0)),
    MONTAGE("Montaj", doubleArrayOf(0.6, 0.6, 2.5, 2.5, 0.6, 0.6)),
    HERO("Kahraman", doubleArrayOf(2.0, 2.0, 0.3, 0.3, 2.0, 2.0)),
    BULLET("Mermi", doubleArrayOf(1.0, 1.0, 0.2, 0.2, 1.0, 1.0)),
    RAMP_UP("Hızlan", doubleArrayOf(0.5, 1.0, 2.0, 3.0)),
    RAMP_DOWN("Yavaşla", doubleArrayOf(3.0, 2.0, 1.0, 0.5));

    /** u ∈ [0, 1] kaynak konumunda çarpan. */
    fun mult(u: Double): Double {
        val n = points.size - 1
        val x = (u.coerceIn(0.0, 1.0) * n)
        val i = x.toInt().coerceAtMost(n - 1)
        val f = x - i
        val s = f * f * (3 - 2 * f)
        return points[i] + (points[i + 1] - points[i]) * s
    }
}

/**
 * Kaynak zamanı ↔ çıktı zamanı eşlemesi (temel hız × eğri).
 * Eğri yoksa doğrusal; varsa 256 adımlı tabloyla ters çevrilir.
 */
class SpeedMap(private val sourceUs: Long, private val base: Double, private val curve: SpeedCurve) {
    private val n = 256
    /** cum[i] = kaynakta i/n konumuna gelene kadar geçen çıktı süresi (µs). */
    private val cum = DoubleArray(n + 1)

    init {
        if (curve != SpeedCurve.NONE) {
            val du = sourceUs.toDouble() / n
            for (i in 1..n) {
                val u = (i - 0.5) / n
                cum[i] = cum[i - 1] + du / (base * curve.mult(u))
            }
        }
    }

    val outputUs: Long = if (curve == SpeedCurve.NONE) (sourceUs / base).toLong() else cum[n].toLong()

    /** Çıktı zamanı → kaynak içindeki kayma (0 .. sourceUs). */
    fun sourceAt(outUs: Long): Long {
        if (curve == SpeedCurve.NONE) return (outUs * base).toLong().coerceIn(0, sourceUs)
        val t = outUs.toDouble().coerceIn(0.0, cum[n])
        var lo = 0; var hi = n
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= t) lo = m else hi = m }
        val f = if (cum[hi] > cum[lo]) (t - cum[lo]) / (cum[hi] - cum[lo]) else 0.0
        return ((lo + f) / n * sourceUs).toLong().coerceIn(0, sourceUs)
    }
}

package com.mhmmtbg.x5reframe.model

/**
 * Hız eğrisi: parçanın kaynağı boyunca hız çarpanı değişir (yavaş-hızlı-yavaş gibi).
 * [points] kaynağın eşit aralıklı konumlarındaki çarpanlar; aralar yumuşakça doldurulur.
 */
enum class SpeedCurve(val label: String, val points: DoubleArray, val hint: String) {
    NONE("Yok", doubleArrayOf(1.0, 1.0), "Sabit hız"),
    MONTAGE("Montaj", doubleArrayOf(0.6, 0.6, 2.5, 2.5, 0.6, 0.6), "Yavaş başlar, ortada hızlanır, yavaş biter"),
    HERO("Kahraman", doubleArrayOf(2.0, 2.0, 0.3, 0.3, 2.0, 2.0), "Hızlı girer, ortada ağır çekim, hızlı çıkar"),
    BULLET("Mermi", doubleArrayOf(1.0, 1.0, 0.2, 0.2, 1.0, 1.0), "Ortada çok yavaş an"),
    JUMP("Sıçrama", doubleArrayOf(1.0, 3.0, 0.3, 3.0, 1.0), "Hızlı-yavaş-hızlı dalga"),
    FLASH_IN("Hızlı giriş", doubleArrayOf(4.0, 2.0, 1.0, 1.0, 1.0), "Çok hızlı başlar, normale iner"),
    FLASH_OUT("Hızlı çıkış", doubleArrayOf(1.0, 1.0, 1.0, 2.0, 4.0), "Normal başlar, çok hızlı biter"),
    RAMP_UP("Hızlan", doubleArrayOf(0.5, 1.0, 2.0, 3.0), "Gittikçe hızlanır"),
    RAMP_DOWN("Yavaşla", doubleArrayOf(3.0, 2.0, 1.0, 0.5), "Gittikçe yavaşlar"),
    CUSTOM("Özel", doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0), "Noktaları sürükleyerek kendi eğrin");

    /** u ∈ [0, 1] kaynak konumunda çarpan. */
    fun mult(u: Double): Double = SpeedPoints.mult(points, u)

    companion object {
        /** Kullanıcı eğrisi için hız sınırları (log ölçek). */
        const val MIN = 0.1
        const val MAX = 10.0
        const val CUSTOM_POINTS = 5
    }
}

/** Eşit aralıklı hız noktaları arasında yumuşak (smoothstep) ara değerleme. */
object SpeedPoints {
    fun mult(points: DoubleArray, u: Double): Double {
        if (points.size < 2) return points.firstOrNull() ?: 1.0
        val n = points.size - 1
        val x = (u.coerceIn(0.0, 1.0) * n)
        val i = x.toInt().coerceAtMost(n - 1)
        val f = x - i
        val s = f * f * (3 - 2 * f)
        return points[i] + (points[i + 1] - points[i]) * s
    }

    /** Parçanın etkin eğri noktaları (eğri yoksa null). */
    fun of(curve: SpeedCurve, custom: List<Double>?): DoubleArray? = when (curve) {
        SpeedCurve.NONE -> null
        SpeedCurve.CUSTOM -> (custom?.takeIf { it.size >= 2 }?.toDoubleArray() ?: curve.points)
            .map { it.coerceIn(SpeedCurve.MIN, SpeedCurve.MAX) }.toDoubleArray()
        else -> curve.points
    }
}

/**
 * Kaynak zamanı ↔ çıktı zamanı eşlemesi (temel hız × eğri).
 * Eğri yoksa doğrusal; varsa 256 adımlı tabloyla ters çevrilir.
 */
class SpeedMap(private val sourceUs: Long, private val base: Double, private val points: DoubleArray?) {
    constructor(sourceUs: Long, base: Double, curve: SpeedCurve, custom: List<Double>? = null) : this(sourceUs, base, SpeedPoints.of(curve, custom))

    private val n = 256
    /** cum[i] = kaynakta i/n konumuna gelene kadar geçen çıktı süresi (µs). */
    private val cum = DoubleArray(n + 1)
    val curved: Boolean get() = points != null

    init {
        if (points != null) {
            val du = sourceUs.toDouble() / n
            for (i in 1..n) {
                val u = (i - 0.5) / n
                cum[i] = cum[i - 1] + du / (base * SpeedPoints.mult(points, u))
            }
        }
    }

    val outputUs: Long = if (points == null) (sourceUs / base).toLong() else cum[n].toLong()

    /** Kaynakta [srcOffUs] konumundaki anlık hız (temel × eğri). */
    fun rateAt(srcOffUs: Long): Double =
        if (points == null) base else base * SpeedPoints.mult(points, if (sourceUs > 0) srcOffUs.toDouble() / sourceUs else 0.0)

    /** Kaynak kayması → çıktı zamanı. */
    fun outputAt(srcOffUs: Long): Long {
        if (points == null) return (srcOffUs / base).toLong()
        val x = (srcOffUs.toDouble() / sourceUs.coerceAtLeast(1)).coerceIn(0.0, 1.0) * n
        val i = x.toInt().coerceAtMost(n - 1)
        return (cum[i] + (cum[i + 1] - cum[i]) * (x - i)).toLong()
    }

    /** Çıktı zamanı → kaynak içindeki kayma (0 .. sourceUs). */
    fun sourceAt(outUs: Long): Long {
        if (points == null) return (outUs * base).toLong().coerceIn(0, sourceUs)
        val t = outUs.toDouble().coerceIn(0.0, cum[n])
        var lo = 0; var hi = n
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= t) lo = m else hi = m }
        val f = if (cum[hi] > cum[lo]) (t - cum[lo]) / (cum[hi] - cum[lo]) else 0.0
        return ((lo + f) / n * sourceUs).toLong().coerceIn(0, sourceUs)
    }
}

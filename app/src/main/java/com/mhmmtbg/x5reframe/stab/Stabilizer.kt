package com.mhmmtbg.x5reframe.stab

import com.mhmmtbg.x5reframe.insv.Imu
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

enum class StabMode(val label: String, val hint: String) {
    OFF("Kapalı", "Görüntü kamerayla birlikte hareket eder."),
    SMOOTH("Akıcı", "Sarsıntılar yumuşatılır, kamera hareketi korunur (FlowState benzeri)."),
    HORIZON("Ufuk kilidi", "Sarsıntı yumuşatılır ve ufuk hep düz tutulur."),
    LOCK("Yön kilidi", "Ufuk düz ve bakış yönü sabit: kamera dönse de görüntü aynı yöne bakar."),
}

/**
 * Jiroskop + ivmeölçerden kameranın dünyaya göre yönelimi.
 * Dünya çerçevesi: +y aşağı (yerçekimi), +z başlangıçtaki bakış yönünün yatay izdüşümü.
 */
class Stabilizer(imu: Imu, rigFromImu: DoubleArray) {

    private val n = imu.size
    private val t = imu.t
    /** world_from_rig */
    private val q: Array<Quat>

    init {
        val gyro = Array(n) { mul3(rigFromImu, imu.gyro[it]) }
        val acc = Array(n) { mul3(rigFromImu, imu.acc[it]) }

        // Başlangıç: ilk 0,1 sn'nin yerçekimi yönünü dünya +y'ye hizala.
        val m = minOf(n, 100)
        val down0 = DoubleArray(3)
        for (i in 0 until m) for (k in 0..2) down0[k] -= acc[i][k]
        normalize(down0)
        var q0 = alignDown(down0)
        // Yatay bakış yönü dünya +z olsun.
        val f = q0.rotate(doubleArrayOf(0.0, 0.0, 1.0))
        q0 = Quat.fromAxisAngle(0.0, 1.0, 0.0, -atan2(f[0], f[2])) * q0

        q = arrayOfNulls<Quat>(n).let { arr ->
            var cur = q0.normalized()
            arr[0] = cur
            val gain = 0.0015 // ~1 kHz'de yaklaşık 0,7 sn zaman sabiti
            for (i in 1 until n) {
                val dt = (t[i] - t[i - 1]).coerceIn(0.0, 0.01)
                val w = DoubleArray(3) { 0.5 * (gyro[i][it] + gyro[i - 1][it]) * dt }
                cur = (cur * Quat.fromRotVec(w[0], w[1], w[2])).normalized()
                // Yerçekimiyle eğim düzeltmesi (yalnızca ivme ~1 g iken).
                val a = acc[i]
                val an = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
                if (abs(an - 1.0) < 0.15) {
                    val dm = doubleArrayOf(-a[0] / an, -a[1] / an, -a[2] / an)
                    val dp = cur.conj().rotate(doubleArrayOf(0.0, 1.0, 0.0))
                    val e = cross(dp, dm)
                    cur = (cur * Quat.fromRotVec(-gain * e[0], -gain * e[1], -gain * e[2])).normalized()
                }
                arr[i] = cur
            }
            @Suppress("UNCHECKED_CAST")
            arr as Array<Quat>
        }
    }

    /** t saniyedeki world_from_rig. */
    fun orientation(sec: Double): Quat {
        if (sec <= t[0]) return q[0]
        if (sec >= t[n - 1]) return q[n - 1]
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (t[mid] <= sec) lo = mid else hi = mid }
        val f = (sec - t[lo]) / (t[hi] - t[lo])
        return Quat.slerp(q[lo], q[hi], f)
    }

    private fun heading(o: Quat): Double {
        val f = o.rotate(doubleArrayOf(0.0, 0.0, 1.0))
        return atan2(f[0], f[2])
    }

    // ---- Yumuşatılmış eğriler (200 Hz ızgara, önbellekli) ----

    private val gridHz = 200.0
    private val g0 = t[0]
    private val gn = ((t[n - 1] - t[0]) * gridHz).toInt() + 1
    private val gridQ: Array<Quat> by lazy { Array(gn) { orientation(g0 + it / gridHz) } }
    private val gridH: DoubleArray by lazy {
        val h = DoubleArray(gn) { heading(gridQ[it]) }
        for (i in 1 until gn) { // sarmayı aç
            var d = h[i] - h[i - 1]
            while (d > PI) { h[i] -= 2 * PI; d -= 2 * PI }
            while (d < -PI) { h[i] += 2 * PI; d += 2 * PI }
        }
        h
    }
    private var cacheKey: Pair<StabMode, Double>? = null
    private var smoothQ: Array<Quat>? = null
    private var smoothH: DoubleArray? = null

    private fun tau(strength: Double) = 0.06 + 1.6 * strength * strength

    @Synchronized
    private fun ensureSmooth(mode: StabMode, strength: Double) {
        if (cacheKey == mode to strength) return
        val a = 1 - exp(-1.0 / gridHz / tau(strength))
        when (mode) {
            StabMode.SMOOTH -> {
                val s = gridQ.copyOf()
                for (i in 1 until gn) s[i] = Quat.slerp(s[i - 1], gridQ[i], a)
                for (i in gn - 2 downTo 0) s[i] = Quat.slerp(s[i + 1], s[i], a)
                smoothQ = s
            }
            StabMode.HORIZON -> {
                val s = gridH.copyOf()
                for (i in 1 until gn) s[i] = s[i - 1] + a * (gridH[i] - s[i - 1])
                for (i in gn - 2 downTo 0) s[i] = s[i + 1] + a * (s[i] - s[i + 1])
                smoothH = s
            }
            else -> {}
        }
        cacheKey = mode to strength
    }

    private fun gridIndex(sec: Double) = ((sec - g0) * gridHz).toInt().coerceIn(0, gn - 1)

    /**
     * rig_from_view temel dönüşü (kullanıcı kadrajı bunun üstüne uygulanır), satır öncelikli 3x3.
     * @param lockSec yön kilidinde bakış yönünün alındığı an
     */
    fun base(sec: Double, mode: StabMode, strength: Double, lockSec: Double): DoubleArray? {
        if (mode == StabMode.OFF) return null
        val o = orientation(sec)
        val target: Quat = when (mode) {
            StabMode.SMOOTH -> { ensureSmooth(mode, strength); interpGrid(smoothQ!!, sec) }
            StabMode.HORIZON -> { ensureSmooth(mode, strength); Quat.fromAxisAngle(0.0, 1.0, 0.0, interpGridD(smoothH!!, sec)) }
            StabMode.LOCK -> Quat.fromAxisAngle(0.0, 1.0, 0.0, gridH[gridIndex(lockSec)])
            StabMode.OFF -> Quat.IDENTITY
        }
        return (o.conj() * target).toMatrix()
    }

    private fun interpGrid(arr: Array<Quat>, sec: Double): Quat {
        val x = ((sec - g0) * gridHz).coerceIn(0.0, (gn - 1).toDouble())
        val i = x.toInt().coerceAtMost(gn - 2).coerceAtLeast(0)
        return if (gn < 2) arr[0] else Quat.slerp(arr[i], arr[i + 1], x - i)
    }

    private fun interpGridD(arr: DoubleArray, sec: Double): Double {
        val x = ((sec - g0) * gridHz).coerceIn(0.0, (gn - 1).toDouble())
        val i = x.toInt().coerceAtMost(gn - 2).coerceAtLeast(0)
        return if (gn < 2) arr[0] else arr[i] + (arr[i + 1] - arr[i]) * (x - i)
    }

    companion object {
        /**
         * IMU eksenlerinden gövde eksenlerine işaretli eşleme (satır öncelikli).
         * Geçici değer; CI'daki görüntü-jiroskop kalibrasyonu sonucu ile değiştirilecek.
         */
        fun rigFromImuFor(@Suppress("UNUSED_PARAMETER") camera: String): DoubleArray =
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun mul3(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        )

        private fun normalize(v: DoubleArray) {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (l > 0) for (k in 0..2) v[k] /= l
        }

        /** rig'deki aşağı yönünü dünya +y'ye götüren world_from_rig. */
        private fun alignDown(down: DoubleArray): Quat {
            val y = doubleArrayOf(0.0, 1.0, 0.0)
            val axis = cross(down, y)
            val s = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
            val c = down[1]
            if (s < 1e-9) return if (c > 0) Quat.IDENTITY else Quat.fromAxisAngle(1.0, 0.0, 0.0, PI)
            return Quat.fromAxisAngle(axis[0] / s, axis[1] / s, axis[2] / s, atan2(s, c))
        }
    }
}

private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
)

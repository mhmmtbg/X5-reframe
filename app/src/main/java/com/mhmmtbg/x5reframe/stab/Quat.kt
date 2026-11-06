package com.mhmmtbg.x5reframe.stab

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Birim dördey (w, x, y, z). */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conj() = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return Quat(w / n, x / n, y / n, z / n)
    }

    fun dot(o: Quat) = w * o.w + x * o.x + y * o.y + z * o.z

    /** v' = q v q* */
    fun rotate(v: DoubleArray): DoubleArray {
        val r = this * Quat(0.0, v[0], v[1], v[2]) * conj()
        return doubleArrayOf(r.x, r.y, r.z)
    }

    /** Satır öncelikli 3x3 dönüş matrisi. */
    fun toMatrix(): DoubleArray {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        )
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(ax: Double, ay: Double, az: Double, angle: Double): Quat {
            val s = sin(angle / 2)
            return Quat(cos(angle / 2), ax * s, ay * s, az * s)
        }

        /** Dönüş vektöründen (rad). */
        fun fromRotVec(vx: Double, vy: Double, vz: Double): Quat {
            val a = sqrt(vx * vx + vy * vy + vz * vz)
            if (a < 1e-12) return Quat(1.0, vx / 2, vy / 2, vz / 2).normalized()
            return fromAxisAngle(vx / a, vy / a, vz / a, a)
        }

        fun fromMatrix(m: DoubleArray): Quat {
            val tr = m[0] + m[4] + m[8]
            return if (tr > 0) {
                val s = sqrt(tr + 1.0) * 2
                Quat(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
            } else if (m[0] > m[4] && m[0] > m[8]) {
                val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2
                Quat((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
            } else if (m[4] > m[8]) {
                val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2
                Quat((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
            } else {
                val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2
                Quat((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
            }.normalized()
        }

        fun slerp(a: Quat, b0: Quat, t: Double): Quat {
            var b = b0
            var d = a.dot(b)
            if (d < 0) { b = Quat(-b.w, -b.x, -b.y, -b.z); d = -d }
            if (d > 0.9995) {
                return Quat(a.w + t * (b.w - a.w), a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z)).normalized()
            }
            val th = acos(d)
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return Quat(wa * a.w + wb * b.w, wa * a.x + wb * b.x, wa * a.y + wb * b.y, wa * a.z + wb * b.z)
        }
    }
}

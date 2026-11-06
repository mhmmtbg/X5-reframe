package com.mhmmtbg.x5reframe.insv

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * İki lensli gövdenin kalibrasyonu.
 *
 * Lens modeli: birleşik küre (Mei, ξ) + OpenCV sırasıyla Brown radyal/teğetsel bozulma (k1 k2 k3 p1 p2).
 * offset_v3 dizisinin çözümü ve eksen kuralları telemetry-parser'ın `insta360/rig.rs`
 * belgelerini izler (MIT/Apache-2.0). Gövde ekseni: +x sağ, +y aşağı, +z ileri (lens 0).
 */
class Lens(
    val xi: Double,
    /** fx, fy, cx, cy — iz görüntüsünün [0,1] doku koordinatlarında. */
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val k: DoubleArray, // k1 k2 k3 p1 p2
    /** cam_from_rig, satır öncelikli 3x3. */
    val camFromRig: DoubleArray,
    /** Bu lensin görüntüsünü taşıyan video izinin sırası (0 ya da 1). */
    val videoTrackOrder: Int,
)

class Rig(val lenses: List<Lens>) {
    companion object {
        private const val FIELDS_V3 = 19

        /**
         * @param offsetV3 "2_xi_fx_fy_cx_cy_yaw_pitch_roll_tx_ty_tz_k1_k2_k3_p1_p2_w_h_type_..._tail"
         * @param crop pencere kırpması (X5: 5376 → 5312), null ise yok
         * @param tracksReversed X4/X5 "Track0Stream10": iz 0 = lens 1
         */
        fun fromOffsetV3(offsetV3: String, crop: WindowCrop?, tracksReversed: Boolean): Rig {
            val t = offsetV3.split('_').map { it.trim().toDouble() }
            val tail = t.last().toLong()
            require((tail shr 16) == 3L) { "offset_v3 sürümü beklenmedik: ${tail shr 16}" }
            val n = t[0].toInt()
            require(n == 2 && t.size == n * FIELDS_V3 + 2) { "beklenmeyen offset_v3 uzunluğu" }

            val base = vendorRotation(doubleArrayOf(0.0, 0.0, 90.0), 0, n)
            val out = ArrayList<Lens>()
            for (i in 0 until n) {
                val b = 1 + i * FIELDS_V3
                val xi = t[b]
                var fx = t[b + 1]
                var fy = t[b + 2]
                var cx = t[b + 3]
                var cy = t[b + 4]
                val ypr = doubleArrayOf(t[b + 5], t[b + 6], t[b + 7])
                val k = DoubleArray(5) { t[b + 11 + it] }
                val frameW = t[b + 16] / n // tek lensin kalibrasyon karesi
                cx -= frameW * i // yan yana düzenden lensin kendi karesine

                var calibW = frameW
                if (crop != null && crop.srcW.toDouble() / frameW == crop.srcH.toDouble() / t[b + 17]) {
                    val s = crop.srcW / frameW
                    cx = s * (cx + 0.5) - 0.5 - (crop.srcW - crop.dstW) * 0.5 - crop.offX
                    cy = s * (cy + 0.5) - 0.5 - (crop.srcH - crop.dstH) * 0.5 - crop.offY
                    fx *= s; fy *= s
                    calibW = crop.dstW.toDouble()
                }
                // Kalibrasyon pikseli x/(w-1) doku konumuna düşer.
                val norm = calibW - 1.0
                val rigFromCam = mul(base, transpose(vendorRotation(ypr, i, n)))
                val track = if (tracksReversed) (n - 1 - i) else i
                out += Lens(xi, fx / norm, fy / norm, cx / norm, cy / norm, k, transpose(rigFromCam), track)
            }
            return Rig(out)
        }

        private fun vendorRotation(yprDeg: DoubleArray, lensIndex: Int, n: Int): DoubleArray {
            val yaw = Math.toRadians(yprDeg[0])
            val pitch = Math.toRadians(yprDeg[1])
            val roll = Math.toRadians(yprDeg[2])
            var r = mul(mul(rotY(pitch + PI / 2), rotZ(yaw)), rotX(roll))
            if (lensIndex == 0 && n == 2) r = mul(r, rotZ(PI))
            return r
        }

        fun rotX(a: Double) = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
        fun rotY(a: Double) = doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
        fun rotZ(a: Double) = doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)

        fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
                r[i * 3 + j] = s
            }
            return r
        }

        fun transpose(a: DoubleArray) = doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])
    }
}

data class WindowCrop(val srcW: Int, val srcH: Int, val dstW: Int, val dstH: Int, val offX: Int, val offY: Int)

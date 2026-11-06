package com.mhmmtbg.x5reframe.model

/** Kadraj: derece cinsinden yön ve uzun kenar boyunca görüş açısı. */
data class ViewParams(
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val fov: Double = 90.0,
    /** true: geniş açı (stereografik) görünüm, büyük açılarda daha az uzama. */
    val wide: Boolean = false,
) {
    fun clamped(): ViewParams {
        val maxFov = if (wide) 220.0 else 120.0
        return copy(
            yaw = wrap(yaw),
            pitch = pitch.coerceIn(-90.0, 90.0),
            fov = fov.coerceIn(20.0, maxFov),
        )
    }

    companion object {
        fun wrap(a: Double): Double {
            var x = (a + 180.0) % 360.0
            if (x < 0) x += 360.0
            return x - 180.0
        }
    }
}

data class Keyframe(val timeUs: Long, val view: ViewParams)

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

/** Anahtar kareler arasında yumuşak geçiş. Kare yoksa sabit görünüm. */
class Keyframes {
    private val list = ArrayList<Keyframe>()

    val items: List<Keyframe> get() = list
    val isEmpty get() = list.isEmpty()

    fun put(k: Keyframe) {
        list.removeAll { kotlin.math.abs(it.timeUs - k.timeUs) < 40_000 }
        list += k
        list.sortBy { it.timeUs }
    }

    fun removeNear(timeUs: Long, toleranceUs: Long = 300_000): Boolean =
        list.removeAll { kotlin.math.abs(it.timeUs - timeUs) <= toleranceUs }

    fun clear() = list.clear()

    fun at(timeUs: Long, fallback: ViewParams): ViewParams {
        if (list.isEmpty()) return fallback
        if (timeUs <= list.first().timeUs) return list.first().view
        if (timeUs >= list.last().timeUs) return list.last().view
        val i = list.indexOfFirst { it.timeUs > timeUs }
        val a = list[i - 1]; val b = list[i]
        val t0 = (timeUs - a.timeUs).toDouble() / (b.timeUs - a.timeUs)
        val t = t0 * t0 * (3 - 2 * t0) // yumuşak giriş/çıkış
        val dyaw = ViewParams.wrap(b.view.yaw - a.view.yaw)
        return ViewParams(
            yaw = ViewParams.wrap(a.view.yaw + dyaw * t),
            pitch = a.view.pitch + (b.view.pitch - a.view.pitch) * t,
            roll = a.view.roll + (b.view.roll - a.view.roll) * t,
            fov = a.view.fov + (b.view.fov - a.view.fov) * t,
            wide = if (t < 0.5) a.view.wide else b.view.wide,
        )
    }
}

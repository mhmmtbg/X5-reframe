package com.mhmmtbg.x5reframe.model

/** Kadraj: derece cinsinden yön ve uzun kenar boyunca görüş açısı. */
data class ViewParams(
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val fov: Double = 90.0,
    val projection: Projection = Projection.RECTILINEAR,
) {
    fun clamped(): ViewParams = copy(
        yaw = wrap(yaw),
        pitch = pitch.coerceIn(-90.0, 90.0),
        roll = wrap(roll),
        fov = fov.coerceIn(projection.minFov, projection.maxFov),
    )

    companion object {
        fun wrap(a: Double): Double {
            var x = (a + 180.0) % 360.0
            if (x < 0) x += 360.0
            return x - 180.0
        }
    }
}

/** Çıktı görüntüsünün izdüşümü (dewarp / görünüm modu). */
enum class Projection(val label: String, val minFov: Double, val maxFov: Double, val defaultFov: Double, val hint: String) {
    RECTILINEAR("Doğrusal", 20.0, 120.0, 90.0, "Düz çizgiler düz kalır (dewarp). Normal kamera görünümü."),
    WIDE("Geniş", 20.0, 300.0, 140.0, "Stereografik: geniş açıda kenarlar daha az uzar. Küçük gezegen için de kullanılır."),
    SUPERWIDE("Süper geniş", 60.0, 170.0, 130.0, "Pannini: dikey çizgiler düz, yatayda çok geniş görüş."),
    FISHEYE("Fisheye", 60.0, 360.0, 180.0, "Balık gözü (eşit uzaklık) görünümü."),
    EQUIRECT("360°", 360.0, 360.0, 360.0, "Tam küre (2:1). YouTube / VR için 360° video."),
}

/** Bu anahtar kareden bir sonrakine geçişin hız eğrisi. */
enum class Curve(val label: String) {
    EASE_IN_OUT("Yumuşak giriş/çıkış"),
    LINEAR("Doğrusal"),
    EASE_IN("Yumuşak giriş"),
    DELAYED("Gecikmeli geçiş"),
    EASE_OUT("Yumuşak çıkış"),
    FAST_IN_OUT("Hızlı giriş/çıkış"),
    HOLD("Sert kesme");

    /** 0..1 zaman → 0..1 ilerleme */
    fun apply(t: Double): Double = when (this) {
        LINEAR -> t
        EASE_IN_OUT -> t * t * (3 - 2 * t)
        EASE_IN -> t * t
        EASE_OUT -> 1 - (1 - t) * (1 - t)
        DELAYED -> if (t < 0.5) 0.0 else { val u = (t - 0.5) * 2; u * u * (3 - 2 * u) }
        FAST_IN_OUT -> { val u = t - 0.5; 0.5 + 4 * u * u * u }
        HOLD -> if (t < 1.0) 0.0 else 1.0
    }
}

data class Keyframe(val timeUs: Long, val view: ViewParams, val curve: Curve = Curve.EASE_IN_OUT)

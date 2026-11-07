package com.mhmmtbg.x5reframe.ui

/**
 * Önizleme alanında çıktı çerçevesinin yeri. Oynatıcı (çizim) ve dokunma işleme aynı hesabı kullanır.
 * Tuval kipinde çerçeve küçültülür ki etrafında videonun taşan kısmı görünsün.
 */
object FrameGeometry {
    const val CANVAS_SCALE = 0.74f

    /** (x, y, w, h) — sol üst başlangıçlı, [w]×[h] alanda; [aspect] = çıktı genişlik/yükseklik. */
    fun frameRect(w: Float, h: Float, aspect: Float, canvas: Boolean): FloatArray {
        var fw: Float; var fh: Float
        if (w / h > aspect) { fh = h; fw = h * aspect } else { fw = w; fh = w / aspect }
        if (canvas) { fw *= CANVAS_SCALE; fh *= CANVAS_SCALE }
        return floatArrayOf((w - fw) / 2, (h - fh) / 2, fw, fh)
    }
}

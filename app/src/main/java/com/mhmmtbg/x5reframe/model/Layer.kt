package com.mhmmtbg.x5reframe.model

/**
 * Görüntünün üstüne binen katman: metin ya da görsel (logo, çıkartma).
 * Konum ve boyut çıktı karesine göre normalize; zaman aralığı parçanın kaynak zamanında.
 */
data class Layer(
    val id: String,
    val kind: Kind,
    /** Metin katmanında yazı; görsel katmanında uygulama içindeki dosya yolu. */
    val content: String,
    /** Merkez konumu (0..1, sol üstten). */
    val x: Float = 0.5f,
    val y: Float = 0.85f,
    /** Genişlik, çıktı genişliğine oranla. */
    val scale: Float = 0.5f,
    val opacity: Float = 1f,
    val startUs: Long = 0,
    /** -1: parçanın sonuna kadar. */
    val endUs: Long = -1,
    val visible: Boolean = true,
    val style: TextStyle = TextStyle.WHITE,
) {
    enum class Kind { TEXT, IMAGE }

    enum class TextStyle(val label: String, val fg: Int, val bg: Int) {
        WHITE("Beyaz", 0xFFFFFFFF.toInt(), 0x00000000),
        WHITE_BOX("Kutu", 0xFFFFFFFF.toInt(), 0xB0000000.toInt()),
        YELLOW("Sarı", 0xFFFFC94D.toInt(), 0x00000000),
        DARK("Koyu", 0xFF111111.toInt(), 0xE6FFFFFF.toInt()),
    }

    fun activeAt(t: Long) = visible && t >= startUs && (endUs < 0 || t <= endUs)

    /** Doku önbelleği anahtarı: görünümü değiştiren alanlar. */
    val textureKey: String get() = "$kind|$content|${style.name}"
}

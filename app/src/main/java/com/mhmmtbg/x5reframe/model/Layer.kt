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
    /** Saat yönünde döndürme (derece). */
    val rotation: Float = 0f,
    val blend: Blend = Blend.NORMAL,
    val mask: Mask = Mask.NONE,
    val flipH: Boolean = false,
    /** Görsel katmanda yeşil / mavi ekranı kaldır. */
    val chroma: ChromaKey = ChromaKey(),
    /** Metin: yazı tipi, renk (0 = stilin rengi), hizalama, giriş / çıkış animasyonu. */
    val font: Font = Font.SANS_BOLD,
    val color: Int = 0,
    val align: Align = Align.CENTER,
    val animIn: Anim = Anim.NONE,
    val animOut: Anim = Anim.NONE,
) {
    /** Sistem yazı tipleri (ek dosya gerektirmez). */
    enum class Font(val label: String, val family: String, val bold: Boolean) {
        SANS_BOLD("Kalın", "sans-serif", true), SANS("Düz", "sans-serif", false), CONDENSED("Dar", "sans-serif-condensed", true),
        BLACK("Ağır", "sans-serif-black", false), LIGHT("İnce", "sans-serif-light", false), SERIF("Tırnaklı", "serif", true),
        MONO("Daktilo", "monospace", false), CURSIVE("El yazısı", "cursive", false), CASUAL("Rahat", "casual", false),
    }

    enum class Align(val label: String) { LEFT("Sol"), CENTER("Orta"), RIGHT("Sağ") }

    /** Giriş / çıkış animasyonu (0,4 sn). */
    enum class Anim(val label: String) { NONE("Yok"), FADE("Belir"), SLIDE_UP("Yukarı kay"), SLIDE_LEFT("Yandan gel"), POP("Büyü"), TYPE("Daktilo") }

    /** t anında animasyon durumu: saydamlık, kayma (x, y — çıktı oranı), ölçek, görünen karakter oranı. */
    fun animAt(t: Long, segEndUs: Long): FloatArray {
        val dur = 400_000f
        val end = if (endUs < 0) segEndUs else endUs
        var alpha = 1f; var dx = 0f; var dy = 0f; var sc = 1f; var chars = 1f
        fun apply(a: Anim, p: Float) { // p: 0 = görünmez, 1 = tam
            val e = p * p * (3 - 2 * p)
            when (a) {
                Anim.NONE -> {}
                Anim.FADE -> alpha *= e
                Anim.SLIDE_UP -> { alpha *= e; dy += (1 - e) * 0.08f }
                Anim.SLIDE_LEFT -> { alpha *= e; dx += (1 - e) * 0.25f }
                Anim.POP -> { alpha *= minOf(1f, p * 2); sc *= 0.6f + 0.4f * e + 0.08f * kotlin.math.sin(e * 3.14159f) }
                Anim.TYPE -> chars = minOf(chars, p)
            }
        }
        if (animIn != Anim.NONE) apply(animIn, ((t - startUs) / (if (animIn == Anim.TYPE) dur * 3 else dur)).coerceIn(0f, 1f))
        if (animOut != Anim.NONE && animOut != Anim.TYPE) apply(animOut, ((end - t) / dur).coerceIn(0f, 1f))
        return floatArrayOf(alpha, dx, dy, sc, chars)
    }
    enum class Kind { TEXT, IMAGE }

    /** Katmanın alttaki görüntüyle karışımı. */
    enum class Blend(val label: String) { NORMAL("Normal"), SCREEN("Aydınlat"), MULTIPLY("Çarp"), ADD("Ekle") }

    /** Görselin biçimi (resim içinde resim için). */
    enum class Mask(val label: String) { NONE("Yok"), ROUNDED("Yuvarlak köşe"), CIRCLE("Daire") }

    enum class TextStyle(val label: String, val fg: Int, val bg: Int) {
        WHITE("Beyaz", 0xFFFFFFFF.toInt(), 0x00000000),
        WHITE_BOX("Kutu", 0xFFFFFFFF.toInt(), 0xB0000000.toInt()),
        YELLOW("Sarı", 0xFFFFC94D.toInt(), 0x00000000),
        DARK("Koyu", 0xFF111111.toInt(), 0xE6FFFFFF.toInt()),
        /** Kontursuz (çıkartmalar, emoji). */
        PLAIN("Yalın", 0xFFFFFFFF.toInt(), 0x00000000),
    }

    fun activeAt(t: Long) = visible && t >= startUs && (endUs < 0 || t <= endUs)

    /** Doku önbelleği anahtarı: görünümü değiştiren alanlar. */
    val textureKey: String get() = "$kind|$content|${style.name}|${font.name}|$color|${align.name}"

    /** Hazır metin şablonları: biçim + konum + animasyon. */
    enum class Template(val label: String, val sample: String) {
        TITLE("Başlık", "Başlık"), SUBTITLE("Alt yazı", "Alt yazı"), LOWER_THIRD("Alt bant", "Ad Soyad\nUnvan"),
        QUOTE("Alıntı", "“Güzel bir gün”"), BIG("Büyük", "WOW"), MINIMAL("Sade", "minimal");

        fun apply(l: Layer): Layer = when (this) {
            TITLE -> l.copy(font = Font.BLACK, style = TextStyle.WHITE, x = 0.5f, y = 0.42f, scale = 0.7f, animIn = Anim.POP, animOut = Anim.FADE)
            SUBTITLE -> l.copy(font = Font.SANS, style = TextStyle.WHITE_BOX, x = 0.5f, y = 0.86f, scale = 0.62f, animIn = Anim.FADE, animOut = Anim.FADE)
            LOWER_THIRD -> l.copy(font = Font.CONDENSED, style = TextStyle.WHITE_BOX, align = Align.LEFT, x = 0.3f, y = 0.8f, scale = 0.42f, animIn = Anim.SLIDE_LEFT, animOut = Anim.FADE)
            QUOTE -> l.copy(font = Font.SERIF, style = TextStyle.WHITE, x = 0.5f, y = 0.5f, scale = 0.62f, animIn = Anim.TYPE, animOut = Anim.FADE)
            BIG -> l.copy(font = Font.BLACK, style = TextStyle.YELLOW, x = 0.5f, y = 0.5f, scale = 0.85f, animIn = Anim.POP, animOut = Anim.POP)
            MINIMAL -> l.copy(font = Font.LIGHT, style = TextStyle.WHITE, x = 0.5f, y = 0.9f, scale = 0.35f, animIn = Anim.SLIDE_UP, animOut = Anim.FADE)
        }
    }
}

package com.mhmmtbg.x5reframe.model

/**
 * Renk düzenlemesi: hazır filtre (+ şiddet) ve elle ayarlar. Tüm değerler -1..1 (pozlama EV: -2..2).
 * GPU'da kadraj shader'ının son adımında uygulanır; önizleme ve dışa aktarım aynıdır.
 */
data class ColorGrade(
    val filter: Filter = Filter.NONE,
    val filterStrength: Float = 1f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val fade: Float = 0f,
) {
    val isIdentity: Boolean get() = copy(filterStrength = 1f) == ColorGrade() || (filter != Filter.NONE && filterStrength == 0f && copy(filter = Filter.NONE, filterStrength = 1f) == ColorGrade())

    /**
     * Filtre ve elle ayarların birleşimi, shader sırasıyla:
     * [pozlama, kontrast, doygunluk, canlılık, sıcaklık, ton, parlak, gölge, soldurma, bölünmüş ton miktarı, gölge rgb, parlak rgb]
     */
    fun uniforms(): FloatArray {
        val f = filter.preset
        val k = filterStrength
        return floatArrayOf(
            exposure + f.exposure * k, contrast + f.contrast * k, saturation + f.saturation * k, vibrance + f.vibrance * k,
            temperature + f.temperature * k, tint + f.tint * k, highlights + f.highlights * k, shadows + f.shadows * k,
            (fade + f.fade * k).coerceIn(0f, 1f), f.splitAmount * k,
            f.split[0], f.split[1], f.split[2], f.split[3], f.split[4], f.split[5],
        )
    }

    enum class Adjust(val label: String, val min: Float, val max: Float) {
        EXPOSURE("Pozlama", -2f, 2f), CONTRAST("Kontrast", -1f, 1f), SATURATION("Doygunluk", -1f, 1f), VIBRANCE("Canlılık", -1f, 1f),
        TEMPERATURE("Sıcaklık", -1f, 1f), TINT("Ton", -1f, 1f), HIGHLIGHTS("Parlak alanlar", -1f, 1f), SHADOWS("Gölgeler", -1f, 1f),
        FADE("Soldurma", 0f, 1f);

        fun get(g: ColorGrade): Float = when (this) {
            EXPOSURE -> g.exposure; CONTRAST -> g.contrast; SATURATION -> g.saturation; VIBRANCE -> g.vibrance
            TEMPERATURE -> g.temperature; TINT -> g.tint; HIGHLIGHTS -> g.highlights; SHADOWS -> g.shadows; FADE -> g.fade
        }

        fun set(g: ColorGrade, v: Float): ColorGrade = when (this) {
            EXPOSURE -> g.copy(exposure = v); CONTRAST -> g.copy(contrast = v); SATURATION -> g.copy(saturation = v); VIBRANCE -> g.copy(vibrance = v)
            TEMPERATURE -> g.copy(temperature = v); TINT -> g.copy(tint = v); HIGHLIGHTS -> g.copy(highlights = v); SHADOWS -> g.copy(shadows = v)
            FADE -> g.copy(fade = v)
        }
    }

    class Preset(
        val exposure: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val vibrance: Float = 0f,
        val temperature: Float = 0f, val tint: Float = 0f, val highlights: Float = 0f, val shadows: Float = 0f, val fade: Float = 0f,
        /** gölge rgb, parlak rgb (işaretli kaydırma) */
        val split: FloatArray = FloatArray(6), val splitAmount: Float = 0f,
    )

    enum class Filter(val label: String, val preset: Preset) {
        NONE("Orijinal", Preset()),
        VIVID("Canlı", Preset(contrast = 0.15f, vibrance = 0.45f, saturation = 0.1f)),
        CINEMA("Sinema", Preset(contrast = 0.2f, saturation = -0.1f, highlights = -0.2f, shadows = 0.1f,
            split = floatArrayOf(-0.04f, 0.02f, 0.06f, 0.06f, 0.02f, -0.04f), splitAmount = 1f)),
        WARM("Sıcak", Preset(temperature = 0.35f, vibrance = 0.15f, highlights = -0.1f)),
        COOL("Soğuk", Preset(temperature = -0.35f, tint = -0.05f, contrast = 0.05f)),
        SUNSET("Gün batımı", Preset(temperature = 0.5f, tint = 0.1f, saturation = 0.15f, shadows = 0.1f,
            split = floatArrayOf(0.02f, -0.02f, 0.05f, 0.07f, 0.03f, -0.03f), splitAmount = 1f)),
        FADED("Solgun", Preset(contrast = -0.15f, saturation = -0.25f, fade = 0.35f)),
        MONO("Siyah beyaz", Preset(saturation = -1f, contrast = 0.2f)),
        NIGHT("Gece", Preset(exposure = 0.3f, shadows = 0.35f, highlights = -0.25f, temperature = -0.15f, saturation = -0.1f)),
    }
}

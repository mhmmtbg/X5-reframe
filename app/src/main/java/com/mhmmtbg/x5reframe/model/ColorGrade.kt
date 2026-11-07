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
    /** Kenar karartma 0..1, film greni 0..1, keskinlik 0..1 */
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val sharpen: Float = 0f,
    /** Ton eğrisi: koyu / orta / açık tonlarda kaydırma (-1..1, ≈ ±0,2 parlaklık) */
    val curveDark: Float = 0f,
    val curveMid: Float = 0f,
    val curveLight: Float = 0f,
    /** Renk aralıklarına göre ton / doygunluk / parlaklık (Kırmızı, Turuncu, Sarı, Yeşil, Mavi, Mor). */
    val hsl: List<Hsl> = DEFAULT_HSL,
    /** İçe aktarılmış .cube LUT dosyası (uygulama klasöründe) ve şiddeti. */
    val lut: String? = null,
    val lutStrength: Float = 1f,
) {
    data class Hsl(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f)

    val isIdentity: Boolean get() {
        val base = copy(filter = Filter.NONE, filterStrength = 1f, lut = null, lutStrength = 1f)
        val filterOff = filter == Filter.NONE || filterStrength == 0f
        val lutOff = lut == null || lutStrength == 0f
        return filterOff && lutOff && base == ColorGrade()
    }

    companion object {
        val DEFAULT_HSL = List(6) { Hsl() }
        val HSL_BANDS = listOf("Kırmızı", "Turuncu", "Sarı", "Yeşil", "Mavi", "Mor")
        /** [uniforms] dizisinin uzunluğu. */
        const val UNIFORM_COUNT = 16 + 4 + 4 + 18
    }

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
            // 16: efektler (kenar karartma, gren, keskinlik, LUT kimliği)
            vignette, grain, sharpen, lut?.let { LutStore.idOf(it).toFloat() } ?: -1f,
            // 20: eğri (koyu, orta, açık, LUT şiddeti)
            curveDark, curveMid, curveLight, if (lut != null) lutStrength else 0f,
            // 24: HSL bantları (ton, doygunluk, parlaklık) × 6
            *FloatArray(18) { i -> hsl.getOrElse(i / 3) { Hsl() }.let { h -> when (i % 3) { 0 -> h.hue; 1 -> h.sat; else -> h.lum } } },
        )
    }

    /** Ayar grupları (sekmeler). */
    enum class Group { LIGHT, EFFECT, CURVE }

    enum class Adjust(val label: String, val min: Float, val max: Float, val group: Group = Group.LIGHT) {
        EXPOSURE("Pozlama", -2f, 2f), CONTRAST("Kontrast", -1f, 1f), SATURATION("Doygunluk", -1f, 1f), VIBRANCE("Canlılık", -1f, 1f),
        TEMPERATURE("Sıcaklık", -1f, 1f), TINT("Ton", -1f, 1f), HIGHLIGHTS("Parlak alanlar", -1f, 1f), SHADOWS("Gölgeler", -1f, 1f),
        FADE("Soldurma", 0f, 1f),
        SHARPEN("Keskinlik", 0f, 1f, Group.EFFECT), VIGNETTE("Kenar karartma", 0f, 1f, Group.EFFECT), GRAIN("Film greni", 0f, 1f, Group.EFFECT),
        CURVE_DARK("Koyu tonlar", -1f, 1f, Group.CURVE), CURVE_MID("Orta tonlar", -1f, 1f, Group.CURVE), CURVE_LIGHT("Açık tonlar", -1f, 1f, Group.CURVE);

        fun get(g: ColorGrade): Float = when (this) {
            EXPOSURE -> g.exposure; CONTRAST -> g.contrast; SATURATION -> g.saturation; VIBRANCE -> g.vibrance
            TEMPERATURE -> g.temperature; TINT -> g.tint; HIGHLIGHTS -> g.highlights; SHADOWS -> g.shadows; FADE -> g.fade
            SHARPEN -> g.sharpen; VIGNETTE -> g.vignette; GRAIN -> g.grain
            CURVE_DARK -> g.curveDark; CURVE_MID -> g.curveMid; CURVE_LIGHT -> g.curveLight
        }

        fun set(g: ColorGrade, v: Float): ColorGrade = when (this) {
            EXPOSURE -> g.copy(exposure = v); CONTRAST -> g.copy(contrast = v); SATURATION -> g.copy(saturation = v); VIBRANCE -> g.copy(vibrance = v)
            TEMPERATURE -> g.copy(temperature = v); TINT -> g.copy(tint = v); HIGHLIGHTS -> g.copy(highlights = v); SHADOWS -> g.copy(shadows = v)
            FADE -> g.copy(fade = v)
            SHARPEN -> g.copy(sharpen = v); VIGNETTE -> g.copy(vignette = v); GRAIN -> g.copy(grain = v)
            CURVE_DARK -> g.copy(curveDark = v); CURVE_MID -> g.copy(curveMid = v); CURVE_LIGHT -> g.copy(curveLight = v)
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

/**
 * İçe aktarılmış LUT dosyaları için süreç içi kimlik tablosu: renk dizisi (FloatArray) yalnızca sayı taşıdığından
 * dosya yolu buraya kaydedilir, shader programı kimlikten yolu bulup 3B dokuyu yükler.
 */
object LutStore {
    private val ids = HashMap<String, Int>()
    private val paths = ArrayList<String>()

    @Synchronized fun idOf(path: String): Int = ids.getOrPut(path) { paths.add(path); paths.size - 1 }
    @Synchronized fun pathOf(id: Int): String? = paths.getOrNull(id)
}

/** Adobe / Resolve .cube biçimli 3B LUT (LUT_3D_SIZE N; satırlar r g b, kırmızı en hızlı değişir). */
class CubeLut(val size: Int, val data: FloatArray) {
    companion object {
        fun parse(text: String): CubeLut {
            var size = 0
            var dmin = floatArrayOf(0f, 0f, 0f); var dmax = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val up = line.uppercase()
                when {
                    up.startsWith("LUT_3D_SIZE") -> size = line.split(Regex("\\s+"))[1].toInt()
                    up.startsWith("LUT_1D_SIZE") -> throw IllegalArgumentException("1B LUT desteklenmiyor; 3B .cube dosyası seçin")
                    up.startsWith("DOMAIN_MIN") -> dmin = line.split(Regex("\\s+")).drop(1).map { it.toFloat() }.toFloatArray()
                    up.startsWith("DOMAIN_MAX") -> dmax = line.split(Regex("\\s+")).drop(1).map { it.toFloat() }.toFloatArray()
                    up.first().isLetter() -> {} // TITLE vb.
                    else -> line.split(Regex("\\s+")).take(3).forEach { values += it.toFloat() }
                }
            }
            require(size in 2..128) { "LUT boyutu okunamadı" }
            require(values.size >= size * size * size * 3) { "LUT verisi eksik (${values.size / 3} / ${size * size * size})" }
            val d = FloatArray(size * size * size * 3)
            for (i in d.indices) {
                val c = i % 3
                d[i] = ((values[i] - dmin[c]) / (dmax[c] - dmin[c]).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            }
            return CubeLut(size, d)
        }
    }
}

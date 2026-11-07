package com.mhmmtbg.x5reframe.model

enum class Aspect(val label: String, val w: Int, val h: Int) {
    LANDSCAPE("16:9", 16, 9), PORTRAIT("9:16", 9, 16), SQUARE("1:1", 1, 1), FOUR_FIVE("4:5", 4, 5), PANO("2:1", 2, 1)
}

enum class Quality(val label: String, val longSide: Int, val mbpsAvc: Int) {
    FHD("1080p", 1920, 18), QHD("2.7K", 2704, 32), UHD("4K", 3840, 55)
}

/** Çoklu açı düzeni: bölmelerin normalize dikdörtgenleri (x, y, w, h; sol üst köşe). */
enum class Layout(val label: String, val rects: List<FloatArray>) {
    SINGLE("Tek", listOf(floatArrayOf(0f, 0f, 1f, 1f))),
    SIDE("Yan yana", listOf(floatArrayOf(0f, 0f, .5f, 1f), floatArrayOf(.5f, 0f, .5f, 1f))),
    STACK("Üst-alt", listOf(floatArrayOf(0f, 0f, 1f, .5f), floatArrayOf(0f, .5f, 1f, .5f))),
    PIP("Resim içinde", listOf(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(.62f, .04f, .34f, .34f))),
    TRIPLE("Üçlü", listOf(floatArrayOf(0f, 0f, 1f, .5f), floatArrayOf(0f, .5f, .5f, .5f), floatArrayOf(.5f, .5f, .5f, .5f))),
    QUAD("Dörtlü", listOf(floatArrayOf(0f, 0f, .5f, .5f), floatArrayOf(.5f, 0f, .5f, .5f), floatArrayOf(0f, .5f, .5f, .5f), floatArrayOf(.5f, .5f, .5f, .5f)));

    val count get() = rects.size
}

/** Bir bölmenin kadrajı: anahtar kareler ve sabit görünüm. */
data class Pane(val keyframes: List<Keyframe> = emptyList(), val baseView: ViewParams = ViewParams()) {
    fun viewAt(t: Long) = keyframes.viewAt(t, baseView)
    fun keyframeNear(t: Long, tolUs: Long = 300_000): Keyframe? =
        keyframes.minByOrNull { kotlin.math.abs(it.timeUs - t) }?.takeIf { kotlin.math.abs(it.timeUs - t) <= tolUs }
    fun withKeyframe(k: Keyframe) =
        copy(keyframes = (keyframes.filter { kotlin.math.abs(it.timeUs - k.timeUs) >= 40_000 } + k).sortedBy { it.timeUs })
}

/** Yeni bölmelerin varsayılan bakış yönleri: ön, arka, sağ, sol. */
private val defaultPaneYaw = doubleArrayOf(0.0, 180.0, 90.0, -90.0)

enum class BitrateLevel(val label: String, val factor: Float) { STANDARD("Standart", 1f), HIGH("Yüksek", 1.6f), MAX("En yüksek", 2.5f) }

/** Geri alınabilir düzenleme durumu (değişmez). */
data class EditState(
    val inUs: Long = 0,
    val outUs: Long = 0,
    val keyframes: List<Keyframe> = emptyList(),
    /** Anahtar kare yokken kullanılan sabit kadraj. */
    val baseView: ViewParams = ViewParams(),
    val aspect: Aspect = Aspect.LANDSCAPE,
    val quality: Quality = Quality.FHD,
    val hevc: Boolean = false,
    val includeAudio: Boolean = true,
    val stabMode: com.mhmmtbg.x5reframe.stab.StabMode = com.mhmmtbg.x5reframe.stab.StabMode.OFF,
    /** 0..1 yumuşatma şiddeti */
    val stabStrength: Double = 0.5,
    /** Dikiş mesafesi (m); 0 = uzak (sonsuz). */
    val stitchDistance: Double = 0.0,
    val layout: Layout = Layout.SINGLE,
    /** Bölme 1..3 (bölme 0 = keyframes + baseView). */
    val extraPanes: List<Pane> = emptyList(),
    /** Üst katmanlar (metin, görsel); listede sonraki üstte çizilir. */
    val layers: List<Layer> = emptyList(),
    val bitrate: BitrateLevel = BitrateLevel.STANDARD,
    /** Derin takip uygulanan aralıklar (zaman çizelgesinde yeşil). */
    val tracks: List<LongRange> = emptyList(),
    val color: ColorGrade = ColorGrade(),
    /** Çıktı kare hızı; 0 = kaynakla aynı. */
    val fps: Int = 0,
) {
    fun pane(i: Int): Pane = if (i == 0) Pane(keyframes, baseView)
        else extraPanes.getOrNull(i - 1) ?: Pane(baseView = baseView.copy(yaw = ViewParams.wrap(baseView.yaw + defaultPaneYaw[i % 4])))

    fun withPane(i: Int, p: Pane): EditState = if (i == 0) copy(keyframes = p.keyframes, baseView = p.baseView) else {
        val list = (0 until maxOf(extraPanes.size, i)).map { j -> extraPanes.getOrNull(j) ?: pane(j + 1) }.toMutableList()
        list[i - 1] = p
        copy(extraPanes = list)
    }

    fun viewAt(t: Long, pane: Int): ViewParams = pane(pane).viewAt(t)

    /** Düzendeki tüm bölmelerin o anki kadrajları. */
    fun sceneAt(t: Long): List<ViewParams> = (0 until layout.count).map { viewAt(t, it) }

    val stitchInv: Float get() = if (stitchDistance <= 0) 0f else (1.0 / stitchDistance).toFloat()

    fun viewAt(t: Long): ViewParams = keyframes.viewAt(t, baseView)

    fun withKeyframe(k: Keyframe): EditState =
        copy(keyframes = (keyframes.filter { kotlin.math.abs(it.timeUs - k.timeUs) >= 40_000 } + k).sortedBy { it.timeUs })

    fun keyframeNear(t: Long, tolUs: Long = 300_000): Keyframe? =
        keyframes.minByOrNull { kotlin.math.abs(it.timeUs - t) }?.takeIf { kotlin.math.abs(it.timeUs - t) <= tolUs }

    /** Çıktı 360° mi (tüm anahtar kareler ve sabit kadraj equirect ise). */
    val isSpherical: Boolean get() = aspect == Aspect.PANO &&
        layout == Layout.SINGLE && (keyframes.map { it.view } + baseView).all { it.projection == Projection.EQUIRECT }

    fun outputSize(): Pair<Int, Int> {
        fun even(x: Int) = x / 2 * 2
        val long = quality.longSide
        return if (aspect.w >= aspect.h) long to even(long * aspect.h / aspect.w)
        else even(long * aspect.w / aspect.h) to long
    }
}

/** Anahtar kareler arasında yumuşak geçiş; kare yoksa [fallback]. */
fun List<Keyframe>.viewAt(timeUs: Long, fallback: ViewParams): ViewParams {
    if (isEmpty()) return fallback
    if (timeUs <= first().timeUs) return first().view
    if (timeUs >= last().timeUs) return last().view
    val i = indexOfFirst { it.timeUs > timeUs }
    val a = this[i - 1]; val b = this[i]
    val t0 = (timeUs - a.timeUs).toDouble() / (b.timeUs - a.timeUs)
    val t = a.curve.apply(t0)
    val dyaw = ViewParams.wrap(b.view.yaw - a.view.yaw)
    return ViewParams(
        yaw = ViewParams.wrap(a.view.yaw + dyaw * t),
        pitch = a.view.pitch + (b.view.pitch - a.view.pitch) * t,
        roll = a.view.roll + (b.view.roll - a.view.roll) * t,
        fov = a.view.fov + (b.view.fov - a.view.fov) * t,
        projection = if (t < 0.5) a.view.projection else b.view.projection,
    )
}

/** Basit geri al / yinele geçmişi. */
class History<T>(initial: T, private val limit: Int = 100) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(next: T) {
        if (next == current) return
        undo.addLast(current)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
        current = next
    }

    /** Sürükleme gibi sürekli değişikliklerde geçmişe eklemeden güncelle. */
    fun replace(next: T) { current = next }

    fun undo(): T? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        current = prev
        return prev
    }

    fun redo(): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        current = next
        return next
    }
}

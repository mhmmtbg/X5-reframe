package com.mhmmtbg.x5reframe.model

enum class Aspect(val label: String, val w: Int, val h: Int) {
    LANDSCAPE("16:9", 16, 9), PORTRAIT("9:16", 9, 16), SQUARE("1:1", 1, 1), FOUR_FIVE("4:5", 4, 5)
}

enum class Quality(val label: String, val longSide: Int, val mbpsAvc: Int) {
    FHD("1080p", 1920, 18), QHD("2.7K", 2704, 32), UHD("4K", 3840, 55)
}

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
) {
    fun viewAt(t: Long): ViewParams = keyframes.viewAt(t, baseView)

    fun withKeyframe(k: Keyframe): EditState =
        copy(keyframes = (keyframes.filter { kotlin.math.abs(it.timeUs - k.timeUs) >= 40_000 } + k).sortedBy { it.timeUs })

    fun keyframeNear(t: Long, tolUs: Long = 300_000): Keyframe? =
        keyframes.minByOrNull { kotlin.math.abs(it.timeUs - t) }?.takeIf { kotlin.math.abs(it.timeUs - t) <= tolUs }

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
    val t = t0 * t0 * (3 - 2 * t0)
    val dyaw = ViewParams.wrap(b.view.yaw - a.view.yaw)
    return ViewParams(
        yaw = ViewParams.wrap(a.view.yaw + dyaw * t),
        pitch = a.view.pitch + (b.view.pitch - a.view.pitch) * t,
        roll = a.view.roll + (b.view.roll - a.view.roll) * t,
        fov = a.view.fov + (b.view.fov - a.view.fov) * t,
        wide = if (t < 0.5) a.view.wide else b.view.wide,
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

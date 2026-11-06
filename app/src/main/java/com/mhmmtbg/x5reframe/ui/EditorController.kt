package com.mhmmtbg.x5reframe.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.model.EditState
import com.mhmmtbg.x5reframe.model.History
import com.mhmmtbg.x5reframe.model.Keyframe
import com.mhmmtbg.x5reframe.model.ViewParams

/** Ekranın tüm durumu ve düzenleme işlemleri. UI iş parçacığından çağrılır. */
class EditorController(val clip: ClipInfo, val fileName: String, initial: EditState? = null) {

    sealed interface ExportUi {
        data object Idle : ExportUi
        data class Running(val fraction: Float, val speed: Float) : ExportUi
        data class Done(val uri: Uri, val videoSec: Float, val tookSec: Float) : ExportUi
        data class Failed(val message: String) : ExportUi
    }

    data class Feedback(val text: String, val positive: Boolean, val id: Int)

    private val history = History(
        (initial ?: EditState()).let { if (it.outUs <= it.inUs || it.outUs > clip.durationUs) it.copy(inUs = 0, outUs = clip.durationUs) else it }
    )

    var state by mutableStateOf(history.current)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var positionUs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    /** Anahtar kareler varken elle sürüklenen (henüz kaydedilmemiş) kadraj. */
    var liveView by mutableStateOf<ViewParams?>(null)
        private set
    var feedback by mutableStateOf<Feedback?>(null)
        private set
    var exportUi by mutableStateOf<ExportUi>(ExportUi.Idle)
    var previewMuted by mutableStateOf(false)
    private var feedbackId by mutableIntStateOf(0)

    /** Oynatıcı iş parçacığının okuduğu anlık kopyalar. */
    @Volatile var snapshotState: EditState = state
        private set
    @Volatile var snapshotLive: ViewParams? = null
        private set

    var onChanged: () -> Unit = {}

    fun displayedView(t: Long): ViewParams = snapshotLive ?: snapshotState.viewAt(t)

    /** Hareket sensörü varsa sabitleyici (yoksa sabitleme seçenekleri kapalı). */
    val stabilizer: com.mhmmtbg.x5reframe.stab.Stabilizer? = clip.imu?.let {
        runCatching { com.mhmmtbg.x5reframe.stab.Stabilizer(it, com.mhmmtbg.x5reframe.stab.Stabilizer.rigFromImuFor(clip.cameraType)) }.getOrNull()
    }

    /** Oynatıcı ve dışa aktarım için sabitleme temel dönüşü. */
    fun baseAt(t: Long, s: EditState = snapshotState): DoubleArray? =
        stabilizer?.base(t / 1e6, s.stabMode, s.stabStrength, s.inUs / 1e6)

    private fun sync() {
        state = history.current
        snapshotState = state
        canUndo = history.canUndo
        canRedo = history.canRedo
        onChanged()
    }

    fun commit(next: EditState) { history.push(next); sync() }

    fun undo() { if (history.undo() != null) { liveView = null; snapshotLive = null; sync(); say("Geri alındı") } }
    fun redo() { if (history.redo() != null) { liveView = null; snapshotLive = null; sync(); say("Yinelendi") } }

    fun say(text: String, positive: Boolean = true) {
        feedbackId++
        feedback = Feedback(text, positive, feedbackId)
    }

    // ---- Kadraj ----

    private var gestureStart: EditState? = null

    fun beginViewGesture() {
        gestureStart = state
        if (state.keyframes.isNotEmpty() && liveView == null) setLive(state.viewAt(positionUs))
    }

    fun updateView(transform: (ViewParams) -> ViewParams) {
        if (state.keyframes.isEmpty()) {
            history.replace(state.copy(baseView = transform(state.baseView).clamped()))
            state = history.current
            snapshotState = state
            onChanged()
        } else {
            setLive(transform(liveView ?: state.viewAt(positionUs)).clamped())
        }
    }

    /** Kaydırıcı gibi başlangıç olayı olmayan kontroller için. */
    fun beginViewGestureOnce() { if (gestureStart == null) beginViewGesture() }
    fun endViewGestureOnce() = endViewGesture()

    fun endViewGesture() {
        val before = gestureStart ?: return
        gestureStart = null
        if (state.keyframes.isEmpty() && before != state) {
            val after = state
            history.replace(before)
            history.push(after)
            sync()
        }
    }

    /** Tek seferlik kadraj değişikliği (düğmeler). */
    fun changeView(transform: (ViewParams) -> ViewParams) {
        if (state.keyframes.isEmpty()) commit(state.copy(baseView = transform(state.baseView).clamped()))
        else { setLive(transform(liveView ?: state.viewAt(positionUs)).clamped()); onChanged() }
    }

    private fun setLive(v: ViewParams?) { liveView = v; snapshotLive = v }

    fun clearLive() { setLive(null) }

    val currentView: ViewParams get() = liveView ?: state.viewAt(positionUs)

    fun addOrUpdateKeyframe() {
        val existed = state.keyframeNear(positionUs, 40_000) != null
        commit(state.withKeyframe(Keyframe(positionUs, currentView)))
        setLive(null)
        say(if (existed) "Anahtar kare güncellendi · ${fmt(positionUs)}" else "Anahtar kare eklendi · ${fmt(positionUs)}")
    }

    fun deleteKeyframe() {
        val near = state.keyframeNear(positionUs)
        if (near == null) {
            say(if (state.keyframes.isEmpty()) "Silinecek anahtar kare yok" else "Oynatma çizgisini bir anahtar karenin üstüne getir", false)
            return
        }
        commit(state.copy(keyframes = state.keyframes - near))
        setLive(null)
        say("Anahtar kare silindi · ${fmt(near.timeUs)}")
    }

    fun clearKeyframes() {
        if (state.keyframes.isEmpty()) return
        val view = currentView
        commit(state.copy(keyframes = emptyList(), baseView = view))
        setLive(null)
        say("Tüm anahtar kareler silindi")
    }

    fun previousKeyframeTime(): Long? = state.keyframes.lastOrNull { it.timeUs < positionUs - 40_000 }?.timeUs
    fun nextKeyframeTime(): Long? = state.keyframes.firstOrNull { it.timeUs > positionUs + 40_000 }?.timeUs

    // ---- Kesme ----

    fun setIn() {
        val v = positionUs.coerceIn(0, state.outUs - 200_000)
        commit(state.copy(inUs = v)); say("Başlangıç · ${fmt(v)}")
    }

    fun setOut() {
        val v = positionUs.coerceIn(state.inUs + 200_000, clip.durationUs)
        commit(state.copy(outUs = v)); say("Bitiş · ${fmt(v)}")
    }

    fun resetTrim() {
        commit(state.copy(inUs = 0, outUs = clip.durationUs)); say("Kesme sıfırlandı")
    }

    /** Zaman çizelgesinde tutamak sürüklenirken. */
    fun dragTrim(inUs: Long, outUs: Long) {
        if (gestureStart == null) gestureStart = state
        history.replace(state.copy(inUs = inUs, outUs = outUs))
        state = history.current
        snapshotState = state
    }

    fun endTrimDrag() {
        val before = gestureStart ?: return
        gestureStart = null
        if (before != state) {
            val after = state
            history.replace(before); history.push(after); sync()
            say("Aralık ${fmt(after.inUs)} – ${fmt(after.outUs)}")
        }
    }

    companion object {
        fun fmt(us: Long): String {
            val s = us / 1_000_000.0
            return "%d:%04.1f".format((s / 60).toInt(), s % 60)
        }
    }
}

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

    /** Düzenlenen bölme (çoklu açıda). */
    var activePane by mutableIntStateOf(0)
        private set
    @Volatile private var snapshotActive = 0

    fun selectPane(i: Int) {
        val n = state.layout.count
        val v = i.coerceIn(0, n - 1)
        if (v != activePane) { clearLive(); activePane = v; snapshotActive = v; onChanged(); say("Bölme ${v + 1} seçildi") }
    }

    /** Oynatıcı / dışa aktarım: düzendeki tüm bölmelerin kadrajları (etkin bölmede canlı düzenleme dahil). */
    fun sceneAt(t: Long): List<ViewParams> {
        val s = snapshotState
        val live = snapshotLive
        return (0 until s.layout.count).map { i -> if (i == snapshotActive && live != null) live else s.viewAt(t, i) }
    }

    private val ap: com.mhmmtbg.x5reframe.model.Pane get() = state.pane(activePane)
    val activeKeyframes: List<Keyframe> get() = ap.keyframes
    fun activeKeyframeNear(t: Long) = ap.keyframeNear(t)
    private fun withActive(p: com.mhmmtbg.x5reframe.model.Pane) = state.withPane(activePane, p)

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
        if (ap.keyframes.isNotEmpty() && liveView == null) setLive(ap.viewAt(positionUs))
    }

    fun updateView(transform: (ViewParams) -> ViewParams) {
        val p = ap
        if (p.keyframes.isEmpty()) {
            history.replace(withActive(p.copy(baseView = transform(p.baseView).clamped())))
            state = history.current
            snapshotState = state
            onChanged()
        } else {
            setLive(transform(liveView ?: p.viewAt(positionUs)).clamped())
            onChanged()
        }
    }

    /** Kaydırıcı gibi başlangıç olayı olmayan kontroller için. */
    fun beginViewGestureOnce() { if (gestureStart == null) beginViewGesture() }
    fun endViewGestureOnce() = endViewGesture()

    fun endViewGesture() {
        val before = gestureStart ?: return
        gestureStart = null
        if (ap.keyframes.isEmpty() && before != state) {
            val after = state
            history.replace(before)
            history.push(after)
            sync()
        }
    }

    /** Tek seferlik kadraj değişikliği (düğmeler). */
    fun changeView(transform: (ViewParams) -> ViewParams) {
        val p = ap
        if (p.keyframes.isEmpty()) commit(withActive(p.copy(baseView = transform(p.baseView).clamped())))
        else { setLive(transform(liveView ?: p.viewAt(positionUs)).clamped()); onChanged() }
    }

    private fun setLive(v: ViewParams?) { liveView = v; snapshotLive = v }

    fun clearLive() { setLive(null) }

    val currentView: ViewParams get() = liveView ?: ap.viewAt(positionUs)

    fun addOrUpdateKeyframe() {
        val existed = ap.keyframeNear(positionUs, 40_000) != null
        commit(withActive(ap.withKeyframe(Keyframe(positionUs, currentView))))
        setLive(null)
        val pane = if (state.layout.count > 1) " (bölme ${activePane + 1})" else ""
        say((if (existed) "Anahtar kare güncellendi · " else "Anahtar kare eklendi · ") + fmt(positionUs) + pane)
    }

    fun deleteKeyframe() {
        val near = ap.keyframeNear(positionUs)
        if (near == null) {
            say(if (ap.keyframes.isEmpty()) "Silinecek anahtar kare yok" else "Oynatma çizgisini bir anahtar karenin üstüne getir", false)
            return
        }
        commit(withActive(ap.copy(keyframes = ap.keyframes - near)))
        setLive(null)
        say("Anahtar kare silindi · ${fmt(near.timeUs)}")
    }

    fun clearKeyframes() {
        if (ap.keyframes.isEmpty()) return
        val view = currentView
        commit(withActive(ap.copy(keyframes = emptyList(), baseView = view)))
        setLive(null)
        say("Tüm anahtar kareler silindi")
    }

    fun previousKeyframeTime(): Long? = ap.keyframes.lastOrNull { it.timeUs < positionUs - 40_000 }?.timeUs
    fun nextKeyframeTime(): Long? = ap.keyframes.firstOrNull { it.timeUs > positionUs + 40_000 }?.timeUs

    fun setLayout(l: com.mhmmtbg.x5reframe.model.Layout) {
        var next = state.copy(layout = l)
        // Yeni bölmeler varsayılan yönleriyle oluşsun (ön, arka, sağ, sol).
        for (i in 1 until l.count) if (next.extraPanes.size < i) next = next.withPane(i, next.pane(i))
        if (l != com.mhmmtbg.x5reframe.model.Layout.SINGLE && next.aspect == com.mhmmtbg.x5reframe.model.Aspect.PANO) {
            next = next.copy(aspect = com.mhmmtbg.x5reframe.model.Aspect.LANDSCAPE)
        }
        commit(next)
        clearLive()
        activePane = 0; snapshotActive = 0
        say("Düzen: ${l.label}")
    }

    // ---- Otomatik düzenleme ----

    /** null: boşta; değilse çalışan işin adı ve ilerlemesi. */
    var autoTask by mutableStateOf<Pair<String, Float>?>(null)

    /** Etkin bölmenin [from, to] aralığındaki anahtar karelerini yenileriyle değiştir. */
    fun replaceKeyframes(from: Long, to: Long, keys: List<Keyframe>) {
        val kept = ap.keyframes.filter { it.timeUs < from || it.timeUs > to }
        commit(withActive(ap.copy(keyframes = (kept + keys).sortedBy { it.timeUs })))
        setLive(null)
    }

    // ---- Katmanlar ----

    var selectedLayer by mutableStateOf<String?>(null)
    /** Katman sekmesi açıkken önizlemede sürükleme katmanı taşır. */
    var layerTool by mutableStateOf(false)

    val currentLayer: com.mhmmtbg.x5reframe.model.Layer? get() = state.layers.firstOrNull { it.id == selectedLayer }

    fun addLayer(kind: com.mhmmtbg.x5reframe.model.Layer.Kind, content: String) {
        val l = com.mhmmtbg.x5reframe.model.Layer(
            java.util.UUID.randomUUID().toString(), kind, content,
            y = if (kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) 0.85f else 0.15f,
            x = if (kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) 0.5f else 0.85f,
            scale = if (kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) 0.6f else 0.2f,
            startUs = state.inUs, endUs = -1,
        )
        commit(state.copy(layers = state.layers + l))
        selectedLayer = l.id
        say(if (kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) "Metin eklendi" else "Görsel eklendi")
    }

    private var layerGestureStart: EditState? = null

    /** Sürekli değişiklik (sürükleme, kaydırıcı): geçmişe tek adım olarak yazılır. */
    fun updateLayerLive(transform: (com.mhmmtbg.x5reframe.model.Layer) -> com.mhmmtbg.x5reframe.model.Layer) {
        val id = selectedLayer ?: return
        if (layerGestureStart == null) layerGestureStart = state
        history.replace(state.copy(layers = state.layers.map { if (it.id == id) transform(it) else it }))
        state = history.current
        snapshotState = state
        onChanged()
    }

    fun endLayerGesture() {
        val before = layerGestureStart ?: return
        layerGestureStart = null
        val after = state
        if (before != after) { history.replace(before); history.push(after); sync() }
    }

    fun updateLayer(transform: (com.mhmmtbg.x5reframe.model.Layer) -> com.mhmmtbg.x5reframe.model.Layer) {
        val id = selectedLayer ?: return
        commit(state.copy(layers = state.layers.map { if (it.id == id) transform(it) else it }))
    }

    fun deleteLayer() {
        val id = selectedLayer ?: return
        commit(state.copy(layers = state.layers.filterNot { it.id == id }))
        selectedLayer = state.layers.lastOrNull()?.id
        say("Katman silindi")
    }

    fun moveLayer(dir: Int) {
        val id = selectedLayer ?: return
        val list = state.layers.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + dir
        if (i < 0 || j !in list.indices) return
        java.util.Collections.swap(list, i, j)
        commit(state.copy(layers = list))
        say(if (dir > 0) "Katman öne alındı" else "Katman arkaya alındı")
    }

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

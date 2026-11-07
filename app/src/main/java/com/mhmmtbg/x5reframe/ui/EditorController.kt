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
    /** Kuyrukta bekleyen dışa aktarım sayısı. */
    var exportQueued by mutableIntStateOf(0)
    var previewMuted by mutableStateOf(false)
    /** Geliştirici göstergesi: önizleme kare zamanlaması (kadraj bilgisine dokununca açılır). */
    var statsText by mutableStateOf<String?>(null)
    var showStats by mutableStateOf(false)
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
    fun activeKeyframeNear(t: Long, tolUs: Long = 300_000) = ap.keyframeNear(t, tolUs)
    private fun withActive(p: com.mhmmtbg.x5reframe.model.Pane) = state.withPane(activePane, p)

    /** Hareket sensörü varsa sabitleyici (yoksa sabitleme seçenekleri kapalı). */
    val stabilizer: com.mhmmtbg.x5reframe.stab.Stabilizer? = clip.imu?.let {
        runCatching { com.mhmmtbg.x5reframe.stab.Stabilizer(it, com.mhmmtbg.x5reframe.stab.Stabilizer.rigFromImuFor(clip.cameraType)) }.getOrNull()
    }

    /** Oynatıcı ve dışa aktarım için sabitleme temel dönüşü. */
    fun baseAt(t: Long, s: EditState = snapshotState): DoubleArray? =
        if (!clip.is360) { if (s.stabMode == com.mhmmtbg.x5reframe.stab.StabMode.OFF) null else s.flatStab?.correction(t, s.stabStrength.toFloat()) }
        else stabilizer?.base(t / 1e6, s.stabMode, s.stabStrength, s.inUs / 1e6)

    private fun sync() {
        state = history.current
        snapshotState = state
        refreshColor()
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
        if (p.keyframes.isEmpty() && !recording) {
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

    fun setLive(v: ViewParams?) { liveView = v; snapshotLive = v }

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

    // ---- Renk ----

    /** Oynatıcı için hazır renk değerleri (her değişiklikte yeniden hesaplanır). */
    @Volatile var colorUniforms: FloatArray? = null
        private set
    private var colorGestureStart: EditState? = null

    private fun refreshColor() { colorUniforms = state.color.let { if (it.isIdentity) null else it.uniforms() } }
    init { refreshColor() }

    /** Kaydırıcı sürüklenirken: geçmişe yazmadan uygula. */
    fun updateColorLive(f: (com.mhmmtbg.x5reframe.model.ColorGrade) -> com.mhmmtbg.x5reframe.model.ColorGrade) {
        if (colorGestureStart == null) colorGestureStart = state
        history.replace(state.copy(color = f(state.color)))
        state = history.current; snapshotState = state
        refreshColor(); onChanged()
    }

    fun endColorGesture() {
        val before = colorGestureStart ?: return
        colorGestureStart = null
        val after = state
        if (before != after) { history.replace(before); history.push(after); sync() }
    }

    fun setColor(g: com.mhmmtbg.x5reframe.model.ColorGrade) { commit(state.copy(color = g)) }

    private var liveStart: EditState? = null

    /** Kaydırıcı sürüklenirken genel durum değişikliği (geçmişe tek adım olarak yazılır). */
    fun updateLive(f: (EditState) -> EditState) {
        if (liveStart == null) liveStart = state
        history.replace(f(state))
        state = history.current; snapshotState = state
        refreshColor(); onChanged()
    }

    fun endLive() {
        val before = liveStart ?: return
        liveStart = null
        val after = state
        if (before != after) { history.replace(before); history.push(after); sync() }
    }

    /** Tuval kipi: kadraj ayarlanırken çerçevenin etrafı ve videonun sınırları görünür. */
    @Volatile var canvasMode = false

    /** Önizleme kopyası durumu (üst çubukta). */
    var proxyNote by mutableStateOf<String?>(null)

    /** Normal video hareket analizi ilerlemesi (null = çalışmıyor). */
    var stabProgress by mutableStateOf<Float?>(null)

    /** Önce / sonra: basılı tutulurken renk düzenlemesi gösterilmez. */
    @Volatile var compareHold = false
    /** Renk aracındaki histogram (r, g, b × 64). */
    var histogram by mutableStateOf<FloatArray?>(null)

    // ---- Yapay zekâ ile düzenleme ----

    class AiMessage(val fromUser: Boolean, val text: String, val error: Boolean = false)

    /** Sohbet geçmişi; etkinlik tarafından parçalar arasında paylaşılır. */
    var aiLogState: androidx.compose.runtime.MutableState<List<AiMessage>> = mutableStateOf(emptyList())
    var aiLog: List<AiMessage>
        get() = aiLogState.value
        set(v) { aiLogState.value = v }
    /** Çalışan istek: durum metni (null = boşta). */
    var aiBusy by mutableStateOf<String?>(null)
    var aiCanUndo by mutableStateOf(false)
    /** Seçili sağlayıcı ve model (ayarlardan). */
    var aiProvider by mutableStateOf("")
    var aiReady by mutableStateOf(false)
    var aiNeedsConsent by mutableStateOf(false)

    fun aiAppend(m: AiMessage) { aiLog = (aiLog + m).takeLast(30) }

    // ---- Derin takip ----

    /** Önizlemede hedef seçimi açık mı. */
    var trackSelecting by mutableStateOf(false)
    var trackCandidates by mutableStateOf<List<com.mhmmtbg.x5reframe.auto.AutoEditor.Box>>(emptyList())
    var trackTarget by mutableStateOf<com.mhmmtbg.x5reframe.auto.AutoEditor.Box?>(null)
    /** Takip sürerken canlı kutu (önizlemede yeşil; null = hedef şu an görünmüyor). */
    var trackLive by mutableStateOf<com.mhmmtbg.x5reframe.auto.AutoEditor.Box?>(null)
    var tracking by mutableStateOf(false)

    /** Duraklatılan takip: kaldığı zaman ve son kutu. */
    var trackResume by mutableStateOf<Pair<Long, com.mhmmtbg.x5reframe.auto.AutoEditor.Box>?>(null)

    /** Takip sonucunu uygula ve aralığı işaretle. */
    fun applyTrack(from: Long, keys: List<Keyframe>) {
        if (keys.size < 2) return
        val to = keys.last().timeUs
        val kept = ap.keyframes.filter { it.timeUs < from || it.timeUs > to }
        // Bitişik aralıkları birleştir
        val merged = (state.tracks + listOf(from..to)).sortedBy { it.first }.fold(mutableListOf<LongRange>()) { acc, r ->
            val last = acc.lastOrNull()
            if (last != null && r.first <= last.last + 200_000) acc[acc.size - 1] = last.first..maxOf(last.last, r.last) else acc += r
            acc
        }
        commit(withActive(ap.copy(keyframes = (kept + keys).sortedBy { it.timeUs })).copy(tracks = merged))
        setLive(null)
    }

    fun activeViewAt(t: Long): ViewParams = ap.viewAt(t)

    fun clearTracks() {
        val spans = state.tracks
        if (spans.isEmpty()) return
        val kept = ap.keyframes.filter { k -> spans.none { k.timeUs in it } }
        commit(withActive(ap.copy(keyframes = kept)).copy(tracks = emptyList()))
        trackResume = null
        say("Takipler kaldırıldı")
    }

    // ---- Eğriler ve hareket şablonları ----

    /** Yakındaki anahtar karenin bir sonrakine geçiş eğrisini değiştir. */
    fun setCurve(curve: com.mhmmtbg.x5reframe.model.Curve) {
        val k = ap.keyframeNear(positionUs) ?: return
        commit(withActive(ap.copy(keyframes = ap.keyframes.map { if (it === k) it.copy(curve = curve) else it })))
        say("Geçiş: ${curve.label}")
    }

    enum class Template(val label: String, val seconds: Double) {
        PLANET("Küçük gezegen", 3.0), HERO("Kahraman", 4.0), RABBIT("Tavşan deliği", 3.0), PUNCH("Yakınlaş", 1.6)
    }

    /** Oynatma çizgisinden başlayan hazır kamera hareketi (kesme sonunu aşmaz). */
    fun applyTemplate(t: Template) {
        val start = positionUs
        val end = (start + (t.seconds * 1e6).toLong()).coerceAtMost(state.outUs)
        if (end - start < 500_000) { say("Şablon için yeterli süre yok", false); return }
        val v = currentView
        val d = end - start
        val W = com.mhmmtbg.x5reframe.model.Projection.WIDE
        val L = com.mhmmtbg.x5reframe.model.Curve.LINEAR
        val keys = when (t) {
            Template.PLANET -> listOf(
                Keyframe(start, v),
                Keyframe(end, v.copy(projection = W, pitch = -90.0, roll = 0.0, fov = 250.0)),
            )
            Template.RABBIT -> listOf(
                Keyframe(start, v),
                Keyframe(end, v.copy(projection = W, pitch = 90.0, roll = 0.0, fov = 250.0)),
            )
            Template.HERO -> (0..4).map { i ->
                // Tam tur: dört çeyrek, doğrusal hız; ilk ve son çeyrekte yumuşak
                val curve = if (i == 0) com.mhmmtbg.x5reframe.model.Curve.EASE_IN else if (i == 3) com.mhmmtbg.x5reframe.model.Curve.EASE_OUT else L
                Keyframe(start + d * i / 4, v.copy(yaw = ViewParams.wrap(v.yaw + 90.0 * i)), curve)
            }
            Template.PUNCH -> listOf(
                Keyframe(start, v, com.mhmmtbg.x5reframe.model.Curve.FAST_IN_OUT),
                Keyframe(start + d / 3, v.copy(fov = (v.fov * 0.5).coerceAtLeast(v.projection.minFov))),
                Keyframe(end, v),
            )
        }
        replaceKeyframes(start, end, keys)
        say("${t.label}: ${keys.size} anahtar kare")
    }

    // ---- Kayıt modu: oynarken parmakla / telefonu çevirerek yapılan bakış anahtar karelere dönüşür ----

    var recording by mutableStateOf(false)
        private set
    /** Telefon hareketiyle bakış (jiroskop). */
    var recordGyro by mutableStateOf(false)
    private val recBuf = ArrayList<Keyframe>()

    fun startRecording(t: Long) {
        recBuf.clear()
        val v = (liveView ?: ap.viewAt(t)).clamped()
        setLive(v)
        recording = true
        recBuf += Keyframe(t, v)
        say(if (recordGyro) "Kayıt: telefonu çevirerek bak" else "Kayıt: parmağınla bak")
    }

    /** Oynatıcının her karesinde çağrılır. */
    fun recordSample(t: Long) {
        if (!recording) return
        val v = liveView ?: return
        val last = recBuf.lastOrNull()
        if (last == null || t - last.timeUs >= 40_000) recBuf += Keyframe(t, v)
    }

    /** Kaydı bitir: yumuşat, sadeleştir ve aralıktaki anahtar karelerin yerine koy. */
    fun stopRecording() {
        if (!recording) return
        recording = false
        val keys = PathSimplifier.simplify(recBuf)
        recBuf.clear()
        if (keys.size >= 2) {
            replaceKeyframes(keys.first().timeUs, keys.last().timeUs, keys)
            say("Kayıt: ${keys.size} anahtar kare · ${fmt(keys.last().timeUs - keys.first().timeUs)}")
        } else { clearLive(); say("Kayıt çok kısa", false) }
    }

    // ---- Katmanlar ----

    var selectedLayer by mutableStateOf<String?>(null)
    /** Zaman çizelgesinden istenen araç (ör. katman çubuğuna dokununca "layer"). */
    var openToolRequest by mutableStateOf<String?>(null)
    /** Klip sesinin dalga biçimi (zaman çizelgesi için). */
    var waveform by mutableStateOf<FloatArray?>(null)
    /** Katman sekmesi açıkken önizlemede sürükleme katmanı taşır. */
    var layerTool by mutableStateOf(false)

    val currentLayer: com.mhmmtbg.x5reframe.model.Layer? get() = state.layers.firstOrNull { it.id == selectedLayer }

    /** Şablondan metin katmanı. */
    fun addTemplate(t: com.mhmmtbg.x5reframe.model.Layer.Template, text: String = t.sample) {
        val l = t.apply(com.mhmmtbg.x5reframe.model.Layer(java.util.UUID.randomUUID().toString(), com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT, text,
            startUs = positionUs.coerceIn(state.inUs, state.outUs), endUs = (positionUs + 3_000_000).coerceAtMost(state.outUs)))
        commit(state.copy(layers = state.layers + l))
        selectedLayer = l.id
        say("Şablon: ${t.label}")
    }

    /** Çıkartma (emoji) katmanı. */
    fun addSticker(emoji: String) {
        val l = com.mhmmtbg.x5reframe.model.Layer(java.util.UUID.randomUUID().toString(), com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT, emoji,
            x = 0.5f, y = 0.5f, scale = 0.22f, style = com.mhmmtbg.x5reframe.model.Layer.TextStyle.PLAIN, font = com.mhmmtbg.x5reframe.model.Layer.Font.SANS,
            startUs = positionUs.coerceIn(state.inUs, state.outUs), endUs = -1, animIn = com.mhmmtbg.x5reframe.model.Layer.Anim.POP)
        commit(state.copy(layers = state.layers + l))
        selectedLayer = l.id
        say("Çıkartma eklendi")
    }

    /** Otomatik altyazı satırlarını alt yazı biçiminde ekle (öncekileri değiştirir). */
    fun addCaptions(caps: List<com.mhmmtbg.x5reframe.captions.Caption>) {
        val keep = state.layers.filterNot { it.id.startsWith("cap-") }
        val news = caps.mapIndexed { i, cp ->
            com.mhmmtbg.x5reframe.model.Layer.Template.SUBTITLE.apply(com.mhmmtbg.x5reframe.model.Layer(
                "cap-$i-" + java.util.UUID.randomUUID().toString().take(8), com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT,
                com.mhmmtbg.x5reframe.captions.Captions.wrap(cp.text), startUs = cp.startUs, endUs = cp.endUs,
            )).let { l ->
                // Yazı boyu her satırda aynı kalsın: genişlik en uzun satırın karakter sayısıyla orantılı
                val longest = l.content.split('\n').maxOf { it.length }
                l.copy(animIn = com.mhmmtbg.x5reframe.model.Layer.Anim.NONE, animOut = com.mhmmtbg.x5reframe.model.Layer.Anim.NONE,
                    scale = (longest * 0.021f + 0.04f).coerceIn(0.12f, 0.85f))
            }
        }
        commit(state.copy(layers = keep + news))
        say("${news.size} altyazı satırı eklendi")
    }

    /** Müzik vuruşları (parçanın kaynak zamanında) ve seslendirme kaydı durumu. */
    var beatMarks by mutableStateOf<List<Long>>(emptyList())
    var voiceRecording by mutableStateOf(false)

    /** Altyazı oluşturma ilerlemesi (null = çalışmıyor). */
    var captionProgress by mutableStateOf<Float?>(null)

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

package com.mhmmtbg.x5reframe.ui

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.viewinterop.AndroidView
import com.mhmmtbg.x5reframe.model.Aspect
import com.mhmmtbg.x5reframe.model.ViewParams
import com.mhmmtbg.x5reframe.model.Quality
import com.mhmmtbg.x5reframe.ui.EditorController.Companion.fmt
import com.mhmmtbg.x5reframe.ui.theme.Palette
import kotlinx.coroutines.delay

class EditorActions(
    val back: () -> Unit,
    val playPause: () -> Unit,
    val seek: (Long) -> Unit,
    val startExport: (whole: Boolean, format: com.mhmmtbg.x5reframe.media.Exporter.Format) -> Unit,
    val cancelExport: () -> Unit,
    val open: (Uri) -> Unit,
    val share: (Uri) -> Unit,
    val setMuted: (Boolean) -> Unit,
    val pickImage: () -> Unit,
    val autoFrame: () -> Unit,
    val autoHighlights: (targetSec: Int) -> Unit,
    val autoTrack: () -> Unit,
    val cancelAuto: () -> Unit,
    val step: (Boolean) -> Unit,
    val snapshot: () -> Unit = {},
    /** Yapay zekâ ile düzenleme */
    val aiSend: (String) -> Unit = {},
    val aiUndo: () -> Unit = {},
    val aiCancel: () -> Unit = {},
    val aiConsent: () -> Unit = {},
    val aiSettings: () -> Unit = {},
    /** Derin takip: hedef öner, başlat, devam et. */
    val suggestTargets: () -> Unit = {},
    val deepTrack: () -> Unit = {},
    val resumeTrack: () -> Unit = {},
    val dynamicStitch: () -> Boolean = { true },
    val setDynamicStitch: (Boolean) -> Unit = {},
    /** Renk aracı: histogram iste, önizlemeyi yeniden çiz, LUT içe aktar / listele. */
    val requestHistogram: () -> Unit = {},
    val redraw: () -> Unit = {},
    val importLut: () -> Unit = {},
    val lutFiles: () -> List<java.io.File> = { emptyList() },
    /** O anki kareyi verilen renk ayarlarıyla küçük çiz (filtre / LUT önizlemeleri). */
    val renderVariants: (List<FloatArray?>, (List<androidx.compose.ui.graphics.ImageBitmap>) -> Unit) -> Unit = { _, _ -> },
    /** Normal video: görüntüden hareket analizi (sabitleme için). */
    val analyzeFlatStab: () -> Unit = {},
    /** Otomatik altyazı: kullanılabilir yöntemler (kimlik, açıklama) ve başlatma. */
    val captionModes: () -> List<Pair<String, String>> = { emptyList() },
    /** Ses: seslendirme kaydını başlat / durdur, ritme göre böl. */
    val toggleVoice: () -> Unit = {},
    /** Akıllı araçlar (normal video): akıllı kadraj, sessizlik kesme, projeyi otomatik kurgula. */
    val smartReframe: (Aspect) -> Unit = {},
    val removeSilences: () -> Unit = {},
    val autoEditAll: (Int) -> Unit = {},
    val splitOnBeats: (Int) -> Unit = {},
    val autoCaptions: (String) -> Unit = {},
)

/** Düzenleyici araç çubuğu (yatay kaydırılır). Paneli olmayanlar (Anlık görüntü) doğrudan çalışır. */
private enum class Tool(val label: String) {
    AI("Yapay zekâ"), SMART("Akıllı"), TRIM("Kes"), SPEED("Hız"), FRAME("Görünüm"), AUTO("Derin takip"), KEYFRAME("Anahtar kare"), COLOR("Renk"), MULTI("Çoklu açı"),
    STAB("Sabitleme"), AUDIO("Ses"), LAYER("Metin"), SNAPSHOT("Anlık görüntü"), STITCH("Dikiş");

    val icon: androidx.compose.ui.graphics.vector.ImageVector get() = when (this) {
        AI -> Glyphs.Chat; SMART -> Glyphs.Sparkle; TRIM -> Glyphs.Cut; SPEED -> Glyphs.Speed; FRAME -> Glyphs.Tune; AUTO -> Glyphs.Sparkle; KEYFRAME -> Glyphs.Keyframe; COLOR -> Glyphs.Palette
        MULTI -> Glyphs.Album; STAB -> Glyphs.Horizon; AUDIO -> Glyphs.VolumeOn; LAYER -> Glyphs.Text
        SNAPSHOT -> Glyphs.Camera; STITCH -> Glyphs.Stitch
    }
}

/** Düz video / fotoğrafta yalnızca 360°'ye özgü olmayan araçlar. */
private val FLAT_TOOLS = listOf(Tool.SMART, Tool.TRIM, Tool.SPEED, Tool.FRAME, Tool.STAB, Tool.KEYFRAME, Tool.COLOR, Tool.AUDIO, Tool.LAYER, Tool.SNAPSHOT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    c: EditorController,
    actions: EditorActions,
    surfaceFactory: (Context) -> SurfaceView,
    timelineFactory: (Context) -> TimelineView,
    seq: SequenceUi,
    startTool: String? = null,
) {
    var tool by rememberSaveable(startTool) {
        mutableStateOf<Tool?>(if (startTool == "trim") Tool.TRIM else startTool?.let { n -> Tool.values().firstOrNull { it.name == n } })
    }
    var showExportInit = startTool == "EXPORT"
    LaunchedEffect(tool) {
        c.layerTool = tool == Tool.LAYER
        // Kadraj ayarlanırken (araç açık ya da parmakla yakınlaştırırken) çerçevenin etrafı da görünsün
        val canvas = (tool == Tool.FRAME || tool == Tool.SMART || tool == Tool.KEYFRAME) && !c.clip.is360 && c.state.layout.count <= 1
        if (canvas != c.canvasMode) { c.canvasMode = canvas; actions.redraw() }
    }
    LaunchedEffect(c.openToolRequest) {
        when (c.openToolRequest) { "layer" -> tool = Tool.LAYER; "trim" -> tool = Tool.TRIM }
        c.openToolRequest = null
    }
    var showExport by remember { mutableStateOf(showExportInit) }
    val s = c.state

    com.mhmmtbg.x5reframe.ui.theme.ThemedSystemBars()
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // ---- Parçalar (telefon ve tablet düzeninde aynı) ----
    val preview: @Composable (Modifier) -> Unit = { mod ->
        BoxWithConstraints(mod.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val ratio = s.aspect.w.toFloat() / s.aspect.h
            val fr = FrameGeometry.frameRect(maxWidth.value, maxHeight.value, ratio, c.canvasMode)
            val w = fr[2].dp
            val h = fr[3].dp
            // Yüzey tüm alanı kaplar; çerçeve oynatıcıda ortaya çizilir (tuval kipinde etrafı da)
            AndroidView(factory = surfaceFactory, modifier = Modifier.fillMaxSize().semantics {
                contentDescription = "Önizleme. Kadrajı değiştirmek için sürükle, yakınlaştırmak için iki parmakla aç."
            })
            if (s.layout.count > 1) {
                val r = s.layout.rects[c.activePane.coerceIn(0, s.layout.count - 1)]
                Box(Modifier.size(w, h)) {
                    Box(
                        Modifier.padding(start = w * r[0], top = h * r[1]).size(w * r[2], h * r[3])
                            .border(2.dp, Palette.Accent)
                    )
                }
            }
            if (c.trackSelecting) TrackOverlay(c, Modifier.size(w, h))
            if (c.tracking) TrackLiveOverlay(c, Modifier.size(w, h))
            FeedbackPill(c, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            ViewHud(c, Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
    val timeline: @Composable () -> Unit = {
        // Ortası sabit, kayan zaman çizelgesi
        AndroidView(
            factory = timelineFactory,
            modifier = Modifier.fillMaxWidth(),
            update = { t ->
                t.durationUs = c.clip.durationUs
                t.inUs = s.inUs
                t.outUs = s.outUs
                t.keyframes = c.activeKeyframes.map { it.timeUs }
                t.trackRanges = s.tracks
                t.waveform = c.waveform
                t.beats = c.beatMarks
                t.layerBars = s.layers.map { l ->
                    TimelineView.LayerBar(
                        l.id, if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) l.content else "Görsel",
                        l.startUs, if (l.endUs < 0) s.outUs else l.endUs,
                        l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.IMAGE, l.id == c.selectedLayer,
                    )
                }
                t.setDark(Palette.dark)
                if (!c.playing) t.positionUs = c.positionUs
            },
        )
    }
    val tools = if (c.clip.is360) Tool.values().toList() else FLAT_TOOLS
    val selectTool: (Tool) -> Unit = { t ->
        if (t == Tool.SNAPSHOT) actions.snapshot()
        else tool = if (tool == t) null else t
    }
    val panel: @Composable (Tool, Modifier) -> Unit = { open, mod ->
        Box(mod.fillMaxWidth().background(Palette.Surface).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            when (open) {
                Tool.FRAME -> if (c.clip.is360) FramePanel(c) else FlatFramePanel(c)
                Tool.TRIM -> TrimPanel(c)
                Tool.SMART -> SmartPanel(c, actions)
                Tool.SPEED -> SpeedPanel(c, seq)
                Tool.STAB -> if (c.clip.is360) StabPanel(c) else FlatStabPanel(c, actions)
                Tool.MULTI -> MultiPanel(c)
                Tool.LAYER -> LayerPanel(c, actions)
                Tool.AUTO -> AutoPanel(c, actions)
                Tool.AUDIO -> AudioPanel(c, actions, seq)
                Tool.KEYFRAME -> KeyframePanel(c, actions)
                Tool.STITCH -> StitchPanel(c, actions)
                Tool.COLOR -> ColorPanel(c, actions, seq)
                Tool.AI -> AiPanel(c, actions)
                Tool.SNAPSHOT -> {}
            }
        }
    }
    val transport: @Composable () -> Unit = { Transport(c, actions, fullscreen) { fullscreen = !fullscreen } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val totalH = maxHeight
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) TopBar(c, actions, onExport = { showExport = true })
            val ex = c.exportUi
            if (ex is EditorController.ExportUi.Running) {
                LinearProgressIndicator(
                    progress = { ex.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Palette.Accent, trackColor = Palette.SurfaceHigh,
                )
            }
            val open = tool?.takeIf { it != Tool.SNAPSHOT }
            if (wide) {
                // Tablet / yatay: solda büyük önizleme, sağda araçlar; zaman çizelgesi altta
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        preview(Modifier.weight(1f))
                        transport()
                    }
                    if (!fullscreen) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Outline))
                        Column(Modifier.width((maxWidth * 0.36f).coerceIn(300.dp, 460.dp)).fillMaxHeight().background(Palette.Surface)) {
                            ToolBar(open, tools, navPadding = false, onSelect = selectTool)
                            if (open != null) panel(open, Modifier.weight(1f))
                            else ToolGrid(tools, selectTool, Modifier.weight(1f))
                        }
                    }
                }
                if (!fullscreen) {
                    SequenceStrip(seq)
                    timeline()
                    QuickBar(c, seq) { tool = it }
                    Spacer(Modifier.navigationBarsPadding())
                }
            } else {
                preview(Modifier.weight(1f))
                transport()
                if (!fullscreen) {
                    // Küçük ekranda bazı panellerde zaman çizelgesi gizlenir ki önizleme küçülmesin
                    val needsTimeline = open == null || open in TIMELINE_TOOLS || totalH >= 760.dp
                    if (open == null) SequenceStrip(seq)
                    if (needsTimeline) timeline()
                    if (open == null) QuickBar(c, seq) { tool = it }
                    else panel(open, Modifier.height((totalH * 0.3f).coerceIn(150.dp, 340.dp)))
                    ToolBar(open, tools, onSelect = selectTool)
                } else Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (showExport) {
        ModalBottomSheet(
            onDismissRequest = { showExport = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface,
            tonalElevation = 0.dp,
        ) {
            ExportSheet(c, actions, seq)
        }
    }
}

@Composable
private fun TopBar(c: EditorController, a: EditorActions, onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Palette.Surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = a.back) { Icon(Glyphs.Back, "Geri", tint = Palette.Text) }
        Column(Modifier.weight(1f)) {
            Text(c.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    c.clip.isImage -> "Fotoğraf · ${c.clip.displayWidth}×${c.clip.displayHeight}"
                    !c.clip.is360 -> "Video · ${c.clip.displayWidth}×${c.clip.displayHeight} · ${"%.1f".format(c.clip.durationUs / 1e6)} sn" +
                        (if (c.clip.hdrTransfer != 0) " · HDR" else "") + (c.proxyNote?.let { " · $it" } ?: "")
                    else -> "${c.clip.cameraType} · ${"%.1f".format(c.clip.durationUs / 1e6)} sn" + if (c.clip.lrv != null) " · hızlı önizleme" else ""
                },
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = c::undo, enabled = c.canUndo) {
            Icon(Glyphs.Undo, "Geri al", tint = if (c.canUndo) Palette.Text else Palette.Disabled)
        }
        IconButton(onClick = c::redo, enabled = c.canRedo) {
            Icon(Glyphs.Redo, "Yinele", tint = if (c.canRedo) Palette.Text else Palette.Disabled)
        }
        Button(
            onClick = onExport,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            elevation = null,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(end = 8.dp),
        ) { Text("Dışa aktar") }
    }
}

@Composable
internal fun FeedbackPill(c: EditorController, modifier: Modifier) {
    val fb = c.feedback
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(fb?.id) {
        if (fb != null) { visible = true; delay(1600); visible = false }
    }
    AnimatedVisibility(visible && fb != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val positive = fb?.positive ?: true
        Row(
            Modifier.background(Color(0xEE1C1E22), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (positive) {
                Icon(Glyphs.Check, null, tint = Color(0xFF8DAEFF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(fb?.text ?: "", color = if (positive) Color.White else Color(0xFFFF8A8A), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ViewHud(c: EditorController, modifier: Modifier) {
    val v = c.currentView
    val editing = c.liveView != null
    Text(
        (c.statsText?.takeIf { c.showStats }?.let { "$it\n" } ?: "") +
            (if (editing) "● kaydedilmedi  " else "") + if (c.clip.is360) "yön ${v.yaw.toInt()}°  eğim ${v.pitch.toInt()}°  açı ${v.fov.toInt()}°"
                else "yakınlaştırma ${"%.2f".format(com.mhmmtbg.x5reframe.gl.ReframeProgram.flatZoom(v))}×" + if (v.roll != 0.0) "  döndürme ${v.roll.toInt()}°" else "",
        modifier = modifier.clickable { c.showStats = !c.showStats }.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = if (editing) Color(0xFF8DAEFF) else Color.White,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Zaman çizelgesi gerektiren araçlar (küçük ekranda panel açıkken bile çizelge görünür). */
private val TIMELINE_TOOLS = setOf(Tool.TRIM, Tool.KEYFRAME, Tool.LAYER, Tool.AUDIO, Tool.AUTO)

@Composable
private fun Transport(c: EditorController, a: EditorActions, fullscreen: Boolean, toggleFullscreen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Background).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${fmt(c.positionUs)} / ${fmt(c.clip.durationUs)}",
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
            modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        )
        IconButton(onClick = { c.previousKeyframeTime()?.let(a.seek) }, enabled = c.previousKeyframeTime() != null) {
            Icon(Glyphs.PrevKey, "Önceki anahtar kare", tint = if (c.previousKeyframeTime() != null) Palette.Text else Palette.Disabled)
        }
        IconButton(onClick = { a.step(false) }) { Icon(Glyphs.FrameBack, "Bir kare geri", tint = Palette.Text) }
        FilledIconButton(
            onClick = a.playPause,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Text, contentColor = Palette.Background),
        ) { Icon(if (c.playing) Glyphs.Pause else Glyphs.Play, if (c.playing) "Duraklat" else "Oynat") }
        IconButton(onClick = { a.step(true) }) { Icon(Glyphs.FrameFwd, "Bir kare ileri", tint = Palette.Text) }
        IconButton(onClick = { c.nextKeyframeTime()?.let(a.seek) }, enabled = c.nextKeyframeTime() != null) {
            Icon(Glyphs.NextKey, "Sonraki anahtar kare", tint = if (c.nextKeyframeTime() != null) Palette.Text else Palette.Disabled)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { c.previewMuted = !c.previewMuted; a.setMuted(c.previewMuted) }) {
                Icon(if (c.previewMuted) Glyphs.VolumeOff else Glyphs.VolumeOn, "Önizleme sesi", tint = Palette.Muted)
            }
            IconButton(onClick = toggleFullscreen) {
                Icon(if (fullscreen) Glyphs.FullscreenExit else Glyphs.Fullscreen, if (fullscreen) "Tam ekrandan çık" else "Önizlemeyi büyüt", tint = Palette.Muted)
            }
        }
    }
}

@Composable
private fun ToolBar(selected: Tool?, tools: List<Tool>, navPadding: Boolean = true, onSelect: (Tool) -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
    Row(
        Modifier.fillMaxWidth().background(Palette.Background).horizontalScroll(rememberScrollState())
            .then(if (navPadding) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        tools.forEach { t ->
            val on = t == selected
            Column(
                Modifier.width(66.dp).clip(RoundedCornerShape(10.dp)).clickable { onSelect(t) }
                    .background(if (on) Palette.SurfaceHigh else Color.Transparent).padding(vertical = 6.dp)
                    .semantics { this.selected = on; role = androidx.compose.ui.semantics.Role.Tab },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, null, Modifier.size(22.dp), tint = if (on) Palette.AccentText else Palette.Text)
                Spacer(Modifier.height(3.dp))
                Text(t.label, color = if (on) Palette.AccentText else Palette.Muted, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Tablet: araç seçilmemişken sağ bölmede büyük araç kutucukları. */
@Composable
private fun ToolGrid(tools: List<Tool>, onSelect: (Tool) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Araçlar", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        tools.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Column(
                        Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).clickable { onSelect(t) }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(t.icon, null, Modifier.size(26.dp), tint = Palette.Text)
                        Spacer(Modifier.height(6.dp))
                        Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Oynatma çizgisinin altında bağlama göre hızlı işlemler: böl, anahtar kare, metin. */
@Composable
private fun QuickBar(c: EditorController, seq: SequenceUi, openTool: (Tool) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickChip(Glyphs.Cut, "Böl") { seq.split(seq.current) }
        KeyframeButton(c)
        QuickChip(Glyphs.Text, "Metin") { openTool(Tool.LAYER) }
    }
}

@Composable
private fun QuickChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(18.dp)).background(Palette.SurfaceHigh).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = Palette.Text)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

/** Anahtar kare ekle / sil. */
@Composable
private fun KeyframeButton(c: EditorController) {
    val near = c.activeKeyframeNear(c.positionUs, 120_000) != null
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(if (near) Palette.Accent else Palette.SurfaceHigh)
                .clickable { if (near) c.deleteKeyframe() else c.addOrUpdateKeyframe() }.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Keyframe, null, Modifier.size(14.dp), tint = if (near) Palette.OnAccent else Palette.Keyframe)
            Spacer(Modifier.width(6.dp))
            Text(if (near) "Anahtar kareyi sil" else "Anahtar kare ekle", color = if (near) Palette.OnAccent else Palette.Text,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun KeyframePanel(c: EditorController, a: EditorActions) {
    val near = c.activeKeyframeNear(c.positionUs)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (near != null) "Kareyi güncelle" else "Anahtar kare ekle", Modifier.weight(1.4f), primary = true) { c.addOrUpdateKeyframe() }
            ActionButton("Sil", Modifier.weight(0.8f), enabled = near != null) { c.deleteKeyframe() }
        }
        if (near != null) {
            Text("Bu kareden sonrakine geçiş", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.mhmmtbg.x5reframe.model.Curve.values().forEach { cv ->
                    CurveChip(cv, cv == near.curve) { c.setCurve(cv) }
                }
            }
        }
        Text("Hareket şablonları (oynatma çizgisinden başlar)", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorController.Template.values().forEach { t ->
                Text(t.label, color = Palette.Text, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh).clickable { c.applyTemplate(t) }
                        .padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("◀ Önceki", Modifier.weight(1f), enabled = c.previousKeyframeTime() != null) { c.previousKeyframeTime()?.let(a.seek) }
            ActionButton("Sonraki ▶", Modifier.weight(1f), enabled = c.nextKeyframeTime() != null) { c.nextKeyframeTime()?.let(a.seek) }
        }
        Text(
            if (c.activeKeyframes.isEmpty()) "Önizlemede sürükleyerek yönü, iki parmakla açıyı ayarla; sonra anahtar kare ekle. Kamera kareler arasında kendiliğinden hareket eder."
            else "${c.activeKeyframes.size} anahtar kare.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (c.activeKeyframes.isNotEmpty()) {
            Text("Tüm anahtar kareleri temizle", color = Palette.Error, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { c.clearKeyframes() }.padding(vertical = 6.dp))
        }
    }
}

/** Yapay zekâ ile düzenleme: istek yaz, plan uygulanır, geri alınabilir, sohbetle değiştirilir. */
@Composable
private fun AiPanel(c: EditorController, a: EditorActions) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.aiProvider, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("Ayarlar", color = Palette.AccentText, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = a.aiSettings).padding(4.dp))
        }
        if (!c.aiReady) {
            Text("API anahtarı yok. Claude, Gemini ya da ChatGPT anahtarını Ayarlar > Yapay zekâ bölümüne ekle.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            ActionButton("Ayarlara git", Modifier.fillMaxWidth(), primary = true) { a.aiSettings() }
            return
        }
        if (c.aiNeedsConsent) {
            Text("Gönderilecekler: kliplerden birkaç saniyede bir alınan küçük kareler (dört yönden), yüz konumları, ses/hareket düzeyleri ve isteğin. " +
                "Video dosyası gönderilmez. Kareler, içindeki kişilerle birlikte seçtiğin sağlayıcının sunucularına gider ve kullanım API hesabına yazılır.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            ActionButton("Anladım, devam", Modifier.fillMaxWidth(), primary = true) { a.aiConsent() }
            return
        }
        // Sohbet
        c.aiLog.takeLast(6).forEach { m ->
            Text(
                m.text, style = MaterialTheme.typography.bodySmall,
                color = when { m.error -> Palette.Error; m.fromUser -> Palette.Text; else -> Palette.Muted },
                modifier = Modifier.fillMaxWidth()
                    .background(if (m.fromUser) Palette.SurfaceHigh else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        val busy = c.aiBusy
        if (busy != null) {
            Text(busy, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
            ActionButton("Durdur", Modifier.fillMaxWidth()) { a.aiCancel() }
            return
        }
        if (c.aiLog.isEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "30 sn'lik hareketli bir kurgu yap, en iyi anları seç",
                    "Beni takip et, yürüdüğüm yönü de göster",
                    "Dikey (9:16) sosyal medya videosu yap",
                    "Sinematik renk ver, küçük gezegenle aç",
                ).forEach { sug ->
                    Text(sug, color = Palette.Text, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh).clickable { text = sug }
                            .padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (c.aiLog.isEmpty()) "Ne yapılsın? Örn. bisiklet turundan 30 sn'lik video" else "Değişiklik iste: ikinci çekimi kısalt…", color = Palette.Muted) },
            minLines = 2, maxLines = 4, textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline,
                focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text, cursorColor = Palette.Accent),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Gönder", Modifier.weight(1.4f), primary = true, enabled = text.isNotBlank()) { a.aiSend(text.trim()); text = "" }
            ActionButton("Geri al", Modifier.weight(1f), enabled = c.aiCanUndo) { a.aiUndo() }
        }
    }
}

@Composable
private fun ColorPanel(c: EditorController, a: EditorActions, seq: SequenceUi) {
    val g = c.state.color
    var tab by remember { mutableStateOf(0) }
    // Histogram: panel açıkken ve renk değişince yenilenir
    LaunchedEffect(g, c.positionUs) { delay(250); a.requestHistogram() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Histogram(c.histogram, Modifier.weight(1f).height(44.dp))
            // Önce / sonra: basılı tut
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            c.compareHold = true; a.redraw()
                            tryAwaitRelease()
                            c.compareHold = false; a.redraw()
                        })
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
            ) { Text("Önce / sonra", style = MaterialTheme.typography.labelLarge) }
        }
        Segmented(listOf(0, 1, 2, 3, 4), tab, { listOf("Filtre", "Işık", "Efekt", "Eğri", "HSL")[it] }, { tab = it }, Modifier.fillMaxWidth())
        when (tab) {
            0 -> {
                // Her filtre o anki karenin üstünde küçük önizleme olarak gösterilir
                val filters = com.mhmmtbg.x5reframe.model.ColorGrade.Filter.values().toList()
                val luts = a.lutFiles()
                var thumbs by remember { mutableStateOf<List<androidx.compose.ui.graphics.ImageBitmap>>(emptyList()) }
                val base = g.copy(filter = com.mhmmtbg.x5reframe.model.ColorGrade.Filter.NONE, filterStrength = 1f, lut = null)
                LaunchedEffect(base, c.positionUs, luts.size, c.playing) {
                    if (c.playing) return@LaunchedEffect
                    delay(200)
                    val variants = filters.map { f -> base.copy(filter = f, filterStrength = 1f).uniforms() } +
                        luts.map { lf -> base.copy(lut = lf.absolutePath, lutStrength = 1f).uniforms() }
                    a.renderVariants(variants) { thumbs = it }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEachIndexed { i, f ->
                        PreviewTile(f.label, thumbs.getOrNull(i), f == g.filter && g.lut == null) {
                            c.setColor(g.copy(filter = f, filterStrength = 1f, lut = null)); c.say("Filtre: ${f.label}")
                        }
                    }
                }
                Text(g.filter.desc, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                if (g.filter != com.mhmmtbg.x5reframe.model.ColorGrade.Filter.NONE) {
                    LabeledRow("Şiddet") {
                        Slider(
                            value = g.filterStrength, onValueChange = { v -> c.updateColorLive { it.copy(filterStrength = v) } },
                            onValueChangeFinished = { c.endColorGesture() }, modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                        )
                        Text("%${(g.filterStrength * 100).toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                    }
                }
                Text("LUT (.cube) — renk tablosu; profesyonel renk görünümleri", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    luts.forEachIndexed { i, f ->
                        PreviewTile(f.nameWithoutExtension, thumbs.getOrNull(filters.size + i), g.lut == f.absolutePath) {
                            c.setColor(g.copy(lut = f.absolutePath, lutStrength = 1f)); c.say("LUT: ${f.nameWithoutExtension}")
                        }
                    }
                    if (g.lut != null) Chip("LUT'u kaldır", false) { c.setColor(g.copy(lut = null)) }
                    Chip("+ İçe aktar", false) { a.importLut() }
                }
                if (g.lut != null) {
                    LabeledRow("LUT şiddeti") {
                        Slider(
                            value = g.lutStrength, onValueChange = { v -> c.updateColorLive { it.copy(lutStrength = v) } },
                            onValueChangeFinished = { c.endColorGesture() }, modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                        )
                    }
                }
            }
            1, 2, 3 -> {
                val group = when (tab) { 1 -> com.mhmmtbg.x5reframe.model.ColorGrade.Group.LIGHT; 2 -> com.mhmmtbg.x5reframe.model.ColorGrade.Group.EFFECT; else -> com.mhmmtbg.x5reframe.model.ColorGrade.Group.CURVE }
                if (tab == 3) CurvePreview(g, Modifier.fillMaxWidth().height(64.dp))
                com.mhmmtbg.x5reframe.model.ColorGrade.Adjust.values().filter { it.group == group }.forEach { ad -> AdjustRow(c, g, ad) }
            }
            else -> {
                var band by remember { mutableStateOf(0) }
                val bandColors = listOf(Color(0xFFE5484D), Color(0xFFF07A2B), Color(0xFFE5C33B), Color(0xFF3FAF5B), Color(0xFF3D6DF2), Color(0xFF9A5CE6))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    bandColors.forEachIndexed { i, col ->
                        Box(
                            Modifier.minimumInteractiveComponentSize().semantics { contentDescription = com.mhmmtbg.x5reframe.model.ColorGrade.HSL_BANDS[i]; selected = i == band }
                                .size(30.dp).clip(RoundedCornerShape(15.dp)).background(col)
                                .border(if (i == band) 3.dp else 0.dp, Palette.Text, RoundedCornerShape(15.dp)).clickable { band = i },
                        )
                    }
                }
                Text(com.mhmmtbg.x5reframe.model.ColorGrade.HSL_BANDS[band], color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                val h = g.hsl[band]
                listOf("Ton" to h.hue, "Doygunluk" to h.sat, "Parlaklık" to h.lum).forEachIndexed { k, (label, v) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
                        Slider(
                            value = v, onValueChange = { nv ->
                                c.updateColorLive { cg -> cg.copy(hsl = cg.hsl.mapIndexed { i, x -> if (i != band) x else when (k) { 0 -> x.copy(hue = nv); 1 -> x.copy(sat = nv); else -> x.copy(lum = nv) } }) }
                            },
                            onValueChangeFinished = { c.endColorGesture() }, valueRange = -1f..1f, modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                        )
                        Text("%+d".format((v * 100).toInt()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (seq.segments.size > 1) ActionButton("Tüm parçalara uygula", Modifier.weight(1f)) { seq.applyColorToAll(g); c.say("Renk ${seq.segments.size} parçaya uygulandı") }
            if (!g.isIdentity) ActionButton("Sıfırla", Modifier.weight(0.6f)) { c.setColor(com.mhmmtbg.x5reframe.model.ColorGrade()); c.say("Renk sıfırlandı") }
        }
    }
}

/** Seçenek kutucuğu: üstte o anki karenin bu seçenekle önizlemesi, altta adı. */
@Composable
private fun PreviewTile(label: String, image: androidx.compose.ui.graphics.ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .semantics { this.selected = selected; role = androidx.compose.ui.semantics.Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh)
                .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline, RoundedCornerShape(8.dp)),
        ) {
            if (image != null) androidx.compose.foundation.Image(image, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) Palette.AccentText else Palette.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun AdjustRow(c: EditorController, g: com.mhmmtbg.x5reframe.model.ColorGrade, a: com.mhmmtbg.x5reframe.model.ColorGrade.Adjust) {
    val v = a.get(g)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(a.label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
        Slider(
            value = v, onValueChange = { nv -> c.updateColorLive { a.set(it, nv) } },
            onValueChangeFinished = { c.endColorGesture() }, valueRange = a.min..a.max, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
        )
        Text((if (a == com.mhmmtbg.x5reframe.model.ColorGrade.Adjust.EXPOSURE) "%+.1f".format(v) else if (a.min >= 0f) "%d".format((v * 100).toInt()) else "%+d".format((v * 100).toInt())),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp).clickable { c.setColor(a.set(g, 0f)) })
    }
}

/** RGB histogramı: üç kanal üst üste, yarı saydam. */
@Composable
private fun Histogram(h: FloatArray?, modifier: Modifier) {
    val bg = Palette.SurfaceHigh
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(bg)) {
        if (h == null) return@Canvas
        val cols = listOf(Color(0x99FF5A5A), Color(0x9955D16E), Color(0x995A8CFF))
        for (ch in 0..2) {
            val path = androidx.compose.ui.graphics.Path()
            path.moveTo(0f, size.height)
            for (k in 0 until 64) {
                val x = k / 63f * size.width
                path.lineTo(x, size.height * (1f - h[ch * 64 + k] * 0.95f))
            }
            path.lineTo(size.width, size.height); path.close()
            drawPath(path, cols[ch])
        }
    }
}

/** Ton eğrisinin görünümü (girdi → çıktı). */
@Composable
private fun CurvePreview(g: com.mhmmtbg.x5reframe.model.ColorGrade, modifier: Modifier) {
    val line = Palette.AccentText; val grid = Palette.Outline; val bg = Palette.SurfaceHigh
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(bg)) {
        for (i in 1..3) {
            val x = size.width * i / 4f
            drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height))
        }
        drawLine(grid, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, 0f))
        fun bump(y: Float, cpos: Float): Float { val b = (1f - kotlin.math.abs(y - cpos) / 0.25f).coerceAtLeast(0f); return b * b * (3 - 2 * b) }
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0..48) {
            val y = i / 48f
            val o = (y + 0.2f * (g.curveDark * bump(y, 0.25f) + g.curveMid * bump(y, 0.5f) + g.curveLight * bump(y, 0.75f))).coerceIn(0f, 1f)
            val px = y * size.width; val py = size.height * (1f - o)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

/** Eğri seçimi: küçük grafik + ad. */
@Composable
private fun CurveChip(cv: com.mhmmtbg.x5reframe.model.Curve, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Palette.SurfaceHigh else Color.Transparent)
            .border(1.dp, if (selected) Palette.Accent else Palette.Outline, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val col = if (selected) Palette.AccentText else Palette.Muted
        androidx.compose.foundation.Canvas(Modifier.size(56.dp, 30.dp)) {
            val path = androidx.compose.ui.graphics.Path()
            val n = 24
            for (i in 0..n) {
                val t = i / n.toDouble()
                val x = size.width * t.toFloat()
                val y = size.height * (1f - cv.apply(t).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, col, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
        Text(cv.label, color = if (selected) Palette.Text else Palette.Muted, style = MaterialTheme.typography.labelSmall,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun StitchPanel(c: EditorController, a: EditorActions) {
    val s = c.state
    var dyn by remember { mutableStateOf(a.dynamicStitch()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow("Dinamik dikiş", dyn) { dyn = it; a.setDynamicStitch(it); c.say(if (it) "Dinamik dikiş açık" else "Dinamik dikiş kapalı") }
        Text("Dikiş çizgisi her bölgede yakındaki nesnelere göre hizalanır ve iki lensin rengi eşitlenir. Dışa aktarımda daha ince taneli çalışır.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        LabeledRow("Mesafe") {
            Segmented(
                listOf(0.0, 5.0, 2.0, 1.0, 0.5), s.stitchDistance, { if (it == 0.0) "Oto" else "${if (it < 1) it.toString().replace('.', ',') else it.toInt().toString()} m" },
                { d -> c.commit(s.copy(stitchDistance = d)); c.say(if (d == 0.0) "Dikiş: otomatik" else "Dikiş mesafesi ${d} m") },
                Modifier.weight(1f),
            )
        }
        Text("Dinamik dikiş kapalıyken tüm dikişe tek bir mesafe uygulanır.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.border(1.dp, Palette.Outline, RoundedCornerShape(8.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (on) Palette.SurfaceHigh else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), color = if (on) Palette.AccentText else Palette.Muted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        content()
    }
}

@Composable
private fun ActionButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp), elevation = null,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Accent,
                disabledContainerColor = Palette.SurfaceHigh, disabledContentColor = Palette.Disabled),
        ) { Text(text, maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Palette.Outline else Palette.SurfaceHigh),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Text, disabledContentColor = Palette.Disabled),
        ) { Text(text, maxLines = 1) }
    }
}

@Composable
private fun FramePanel(c: EditorController) {
    val s = c.state
    val v = c.currentView
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabeledRow("Oran") {
            Segmented(Aspect.values().toList(), s.aspect, { it.label }, { c.commit(s.copy(aspect = it)); c.say("Oran ${it.label}") }, Modifier.weight(1f))
        }
        Segmented(
            com.mhmmtbg.x5reframe.model.Projection.values().toList(), v.projection, { it.label },
            { p ->
                c.changeView { it.copy(projection = p, fov = if (it.fov in p.minFov..p.maxFov) it.fov else p.defaultFov) }
                if (p == com.mhmmtbg.x5reframe.model.Projection.EQUIRECT && s.aspect != Aspect.PANO) c.commit(c.state.copy(aspect = Aspect.PANO))
                c.say(p.label)
            },
            Modifier.fillMaxWidth(),
        )
        Text(v.projection.hint, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        if (v.projection != com.mhmmtbg.x5reframe.model.Projection.EQUIRECT) {
            LabeledRow("Açı ${v.fov.toInt()}°") {
                Slider(
                    value = v.fov.toFloat(),
                    onValueChange = { f -> c.beginViewGestureOnce(); c.updateView { it.copy(fov = f.toDouble()) } },
                    onValueChangeFinished = { c.endViewGestureOnce() },
                    valueRange = v.projection.minFov.toFloat()..v.projection.maxFov.toFloat(),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                )
            }
        }
        LabeledRow("Hazır") {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val W = com.mhmmtbg.x5reframe.model.Projection.WIDE
                ActionButton("Ön", Modifier.weight(1f)) { c.changeView { it.copy(yaw = 0.0, pitch = 0.0, roll = 0.0) }; c.say("Ön lens yönü") }
                ActionButton("Arka", Modifier.weight(1f)) { c.changeView { it.copy(yaw = 180.0, pitch = 0.0, roll = 0.0) }; c.say("Arka lens yönü") }
                ActionButton("Gezegen", Modifier.weight(1.2f)) {
                    c.changeView { it.copy(projection = W, pitch = -90.0, roll = 0.0, fov = 250.0) }
                    if (s.aspect == Aspect.LANDSCAPE || s.aspect == Aspect.PANO) c.commit(c.state.copy(aspect = Aspect.SQUARE))
                    c.say("Küçük gezegen")
                }
                ActionButton("Tünel", Modifier.weight(1f)) {
                    c.changeView { it.copy(projection = W, pitch = 90.0, roll = 0.0, fov = 250.0) }
                    if (s.aspect == Aspect.LANDSCAPE || s.aspect == Aspect.PANO) c.commit(c.state.copy(aspect = Aspect.SQUARE))
                    c.say("Tavşan deliği")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Ufku sıfırla", Modifier.weight(1f)) { c.changeView { it.copy(pitch = 0.0, roll = 0.0) }; c.say("Ufuk sıfırlandı") }
        }
    }
}

/** Yeşil / mavi ekran: aç-kapa, anahtar renk, benzerlik ve kenar yumuşaklığı. */
@Composable
private fun ChromaControls(
    k: com.mhmmtbg.x5reframe.model.ChromaKey, commit: (com.mhmmtbg.x5reframe.model.ChromaKey) -> Unit,
    live: (com.mhmmtbg.x5reframe.model.ChromaKey) -> Unit, end: () -> Unit,
) {
    SwitchRow("Yeşil / mavi ekranı kaldır", k.on) { commit(k.copy(on = it)) }
    if (!k.on) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Yeşil", k.color == 0xFF00B140.toInt()) { commit(k.copy(color = 0xFF00B140.toInt())) }
        Chip("Mavi", k.color == 0xFF0047BB.toInt()) { commit(k.copy(color = 0xFF0047BB.toInt())) }
    }
    LabeledRow("Benzerlik") {
        Slider(value = k.similarity, onValueChange = { live(k.copy(similarity = it)) }, onValueChangeFinished = end, valueRange = 0.05f..1f,
            modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh))
    }
    LabeledRow("Yumuşaklık") {
        Slider(value = k.smooth, onValueChange = { live(k.copy(smooth = it)) }, onValueChangeFinished = end, valueRange = 0.01f..0.5f,
            modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh))
    }
}

/** Düz klip kadrajı: oran, yerleşim, yakınlaştırma, döndürme. Kaydırma için önizlemeyi sürükleyin. */
@Composable
private fun FlatFramePanel(c: EditorController) {
    val s = c.state
    val v = c.currentView
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Önizlemedeki çizgilerin anlamı
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(14.dp, 3.dp).background(Color.White))
            Text("çıktı çerçevesi", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(14.dp, 3.dp).background(Color(0xFF3D6DF2)))
            Text("videonun sınırı", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
        }
        LabeledRow("Oran") {
            Segmented(Aspect.values().filter { it != Aspect.PANO }, s.aspect, { it.label }, { c.commit(s.copy(aspect = it)); c.say("Oran ${it.label}") }, Modifier.weight(1f))
        }
        Text("Yerleşim", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.mhmmtbg.x5reframe.model.FrameFit.values().forEach { f ->
                FitTile(f, s.aspect.w.toFloat() / s.aspect.h, c.clip.displayAspect, f == s.fit) { c.commit(s.copy(fit = f)); c.say(f.label) }
            }
        }
        Text(when (s.fit) {
            com.mhmmtbg.x5reframe.model.FrameFit.FIT -> "Sığdır: videonun tamamı görünür, boşluklar siyah kalır."
            com.mhmmtbg.x5reframe.model.FrameFit.FILL -> "Doldur: çerçeve tamamen dolar, taşan kenarlar kesilir."
            else -> "Bulanık arka plan: video tam görünür, boşluklar videonun bulanık hâliyle dolar."
        }, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        val zoom = com.mhmmtbg.x5reframe.gl.ReframeProgram.flatZoom(v)
        LabeledRow("Yakınlaştır ${"%.1f".format(zoom)}×") {
            Slider(
                value = zoom.coerceIn(0.75f, 4f),
                onValueChange = { z -> c.beginViewGestureOnce(); c.updateView { it.copy(fov = 90.0 / z) } },
                onValueChangeFinished = { c.endViewGestureOnce() },
                valueRange = 0.75f..4f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
            )
        }
        LabeledRow("Kırp") {
            val a0 = c.clip.displayAspect
            val presets = listOf("Yok" to 0f, "1:1" to 1f, "16:9" to 16f / 9f, "9:16" to 9f / 16f, "4:5" to 0.8f)
            val cur = presets.firstOrNull { (_, r) -> if (r == 0f) s.crop.isFull else kotlin.math.abs(a0 * s.crop.w / s.crop.h - r) < 0.02f }?.first
            Segmented(presets.map { it.first }, cur ?: "", { it }, { name ->
                val r = presets.first { it.first == name }.second
                val crop = if (r == 0f) com.mhmmtbg.x5reframe.model.CropRect() else if (r < a0) {
                    val w = r / a0; com.mhmmtbg.x5reframe.model.CropRect((1 - w) / 2, 0f, (1 + w) / 2, 1f)
                } else {
                    val h = a0 / r; com.mhmmtbg.x5reframe.model.CropRect(0f, (1 - h) / 2, 1f, (1 + h) / 2)
                }
                c.commit(s.copy(crop = crop)); c.say(if (r == 0f) "Kırpma kaldırıldı" else "Kırpıldı $name")
            }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Ayna ↔", Modifier.weight(1f)) { c.commit(s.copy(flipH = !s.flipH)); c.say("Yatay aynalama") }
            ActionButton("Ayna ↕", Modifier.weight(1f)) { c.commit(s.copy(flipV = !s.flipV)); c.say("Dikey aynalama") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Döndür 90°", Modifier.weight(1f)) { c.changeView { it.copy(roll = ViewParams.wrap(it.roll + 90.0)) }; c.say("Döndürüldü") }
            ActionButton("Ortala", Modifier.weight(1f)) { c.changeView { it.copy(yaw = 0.0, pitch = 0.0) }; c.say("Ortalandı") }
            ActionButton("Sıfırla", Modifier.weight(1f)) { c.changeView { ViewParams() }; c.say("Kadraj sıfırlandı") }
        }
        Text("Kaydırmak için önizlemeyi sürükle; iki parmakla yakınlaştır ve döndür. Anahtar kare eklersen kadraj kareler arasında yumuşakça değişir.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        ChromaControls(s.chroma, { k -> c.commit(c.state.copy(chroma = k)) }, { k -> c.updateLive { it.copy(chroma = k) } }, { c.endLive() })
        if (s.chroma.on) LabeledRow("Arka plan") {
            Segmented(com.mhmmtbg.x5reframe.model.ChromaBg.values().toList(), s.chromaBg, { it.label }, { b -> c.commit(s.copy(chromaBg = b)) }, Modifier.weight(1f))
        }
        Text("Kişiyi arka plandan ayır", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        val personOpts: List<com.mhmmtbg.x5reframe.model.ChromaBg?> = listOf(null, com.mhmmtbg.x5reframe.model.ChromaBg.BLUR, com.mhmmtbg.x5reframe.model.ChromaBg.BLACK, com.mhmmtbg.x5reframe.model.ChromaBg.WHITE)
        Segmented(personOpts, s.personBg, { it?.label ?: "Kapalı" }, { b -> c.commit(s.copy(personBg = b)); c.say(if (b == null) "Kişi ayırma kapalı" else "Arka plan: ${b.label}") }, Modifier.fillMaxWidth())
        if (s.personBg != null) Text("Cihaz içi yapay zekâ ile kişi algılanır (internet gerekmez). Önizleme oynarken her 3 karede bir güncellenir; dışa aktarımda her kare işlenir.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Yerleşim seçeneğinin küçük çizimi: çerçeve (beyaz) içinde videonun nasıl durduğu. */
@Composable
private fun FitTile(fit: com.mhmmtbg.x5reframe.model.FrameFit, outAspect: Float, srcAspect: Float, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).semantics { this.selected = selected; role = androidx.compose.ui.semantics.Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Canvas(
            Modifier.size(76.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh)
                .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline, RoundedCornerShape(8.dp)),
        ) {
            val pad = 8.dp.toPx()
            val aw = size.width - 2 * pad; val ah = size.height - 2 * pad
            // Çerçeve
            var fw = aw; var fh = aw / outAspect
            if (fh > ah) { fh = ah; fw = ah * outAspect }
            val fx = (size.width - fw) / 2; val fy = (size.height - fh) / 2
            // Video
            val fitScale = minOf(fw / srcAspect, fh) ; val fillScale = maxOf(fw / srcAspect, fh)
            val vh = if (fit == com.mhmmtbg.x5reframe.model.FrameFit.FILL) fillScale else fitScale
            val vw = vh * srcAspect
            clipRect(fx, fy, fx + fw, fy + fh) {
                if (fit == com.mhmmtbg.x5reframe.model.FrameFit.BLUR) drawRect(Color(0xFF4A5B7A), androidx.compose.ui.geometry.Offset(fx, fy), androidx.compose.ui.geometry.Size(fw, fh))
                drawRect(Color(0xFF3D6DF2), androidx.compose.ui.geometry.Offset(fx + (fw - vw) / 2, fy + (fh - vh) / 2), androidx.compose.ui.geometry.Size(vw, vh))
                // Videoda basit bir manzara: güneş
                drawCircle(Color(0xFFFFD43B), radius = vh * 0.14f, center = androidx.compose.ui.geometry.Offset(fx + fw / 2 + vw * 0.2f, fy + fh / 2 - vh * 0.15f))
            }
            drawRect(Color.White, androidx.compose.ui.geometry.Offset(fx, fy), androidx.compose.ui.geometry.Size(fw, fh), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
        Text(fit.label, style = MaterialTheme.typography.labelSmall, color = if (selected) Palette.AccentText else Palette.Text, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Parça hızı (0,1×–10×), hız eğrisi, ters oynatma, kare dondurma. */
@Composable
private fun SpeedPanel(c: EditorController, seq: SequenceUi) {
    val i = seq.current
    val seg = seq.segments.getOrNull(i) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Logaritmik kaydırıcı: 0,1× … 10×
        var draft by remember(seg.speed) { mutableStateOf(kotlin.math.log10(seg.speed).toFloat()) }
        val shown = Math.pow(10.0, draft.toDouble())
        LabeledRow("Hız ${fmtSpeed(roundSpeed(shown))}×") {
            Slider(
                value = draft, onValueChange = { draft = it },
                onValueChangeFinished = { val sp = roundSpeed(Math.pow(10.0, draft.toDouble())); seq.updateSegment(i) { it.copy(speed = sp) }; c.say("Hız ${fmtSpeed(sp)}×") },
                valueRange = -1f..1f, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 10.0).forEach { sp ->
                Chip("${fmtSpeed(sp)}×", seg.speed == sp) { seq.updateSegment(i) { it.copy(speed = sp) }; c.say("Hız ${fmtSpeed(sp)}×") }
            }
        }
        Text("Hız eğrisi", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            com.mhmmtbg.x5reframe.model.SpeedCurve.values().forEach { cv ->
                val pts = com.mhmmtbg.x5reframe.model.SpeedPoints.of(cv, if (cv == com.mhmmtbg.x5reframe.model.SpeedCurve.CUSTOM) seg.curvePoints else null) ?: cv.points
                CurveThumb(cv, pts, seg.curve == cv) {
                    seq.updateSegment(i) { s ->
                        // Özel eğri: hazır bir eğriden geçiliyorsa onun noktalarıyla başlar, böylece düzenlenebilir
                        if (cv == com.mhmmtbg.x5reframe.model.SpeedCurve.CUSTOM && s.curvePoints == null)
                            s.copy(curve = cv, curvePoints = resample(s.curve.points, com.mhmmtbg.x5reframe.model.SpeedCurve.CUSTOM_POINTS))
                        else s.copy(curve = cv)
                    }
                    c.say("Hız eğrisi: ${cv.label} — ${cv.hint}")
                }
            }
        }
        if (seg.curve == com.mhmmtbg.x5reframe.model.SpeedCurve.CUSTOM) {
            val pts = seg.curvePoints ?: com.mhmmtbg.x5reframe.model.SpeedCurve.CUSTOM.points.toList()
            val st = c.state
            val playU = ((c.positionUs - st.inUs).toFloat() / (st.outUs - st.inUs).coerceAtLeast(1)).coerceIn(0f, 1f)
            CurveEditor(pts, playU, onChange = {}, onCommit = { np -> seq.updateSegment(i) { it.copy(curvePoints = np) } })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Nokta ekle", false) {
                    if (pts.size < 9) seq.updateSegment(i) { it.copy(curvePoints = resample(pts.toDoubleArray(), pts.size + 1)) }
                }
                Chip("Nokta sil", false) {
                    if (pts.size > 3) seq.updateSegment(i) { it.copy(curvePoints = resample(pts.toDoubleArray(), pts.size - 1)) }
                }
                Chip("Sıfırla", false) { seq.updateSegment(i) { it.copy(curvePoints = List(pts.size) { 1.0 }) } }
            }
        } else if (seg.curve != com.mhmmtbg.x5reframe.model.SpeedCurve.NONE) {
            Text(seg.curve.hint, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (seg.reverse) "Ters: açık" else "Ters oynat", Modifier.weight(1f), primary = seg.reverse) {
                seq.updateSegment(i) { it.copy(reverse = !it.reverse) }; c.say(if (seg.reverse) "Ters oynatma kapandı" else "Parça tersten oynar")
            }
            ActionButton("Kareyi dondur", Modifier.weight(1f)) { seq.freeze(c.positionUs) }
        }
        Text(
            "Çıktı süresi ${"%.1f".format(seg.outputDurationUs / 1e6)} sn. Ses 0,25×–4× arasında perdesi korunarak hızlanır; ters ve eğrili parçalarda orijinal ses kapanır. " +
                "Hız, eğri ve ters oynatma önizlemede de uygulanır.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Eğri noktalarını [n] eşit aralıklı noktaya yeniden örnekle (şekil korunur). */
private fun resample(points: DoubleArray, n: Int): List<Double> =
    List(n) { k -> Math.round(com.mhmmtbg.x5reframe.model.SpeedPoints.mult(points, k / (n - 1.0)) * 100) / 100.0 }

private fun roundSpeed(s: Double): Double = when {
    s < 1 -> Math.round(s * 20) / 20.0
    s < 3 -> Math.round(s * 4) / 4.0
    else -> Math.round(s * 2) / 2.0
}.coerceIn(0.1, 10.0)

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.minimumInteractiveComponentSize().semantics { selected = on; role = androidx.compose.ui.semantics.Role.Tab }.clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Accent else Palette.SurfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, color = if (on) Palette.OnAccent else Palette.Text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun TrimPanel(c: EditorController) {
    val s = c.state
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Aralık  ${fmt(s.inUs)} – ${fmt(s.outUs)}   ·   ${"%.1f".format((s.outUs - s.inUs) / 1e6)} sn",
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("⟦ Başlangıç", Modifier.weight(1f), primary = true) { c.setIn() }
            ActionButton("Bitiş ⟧", Modifier.weight(1f), primary = true) { c.setOut() }
        }
        ActionButton("Kesmeyi sıfırla", Modifier.fillMaxWidth(), enabled = s.inUs != 0L || s.outUs != c.clip.durationUs) { c.resetTrim() }
        Text(
            "Düğmeler oynatma çizgisinin olduğu yeri kullanır. Zaman çizelgesindeki mavi tutamakları sürükleyerek de ayarlayabilirsin.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Normal video için akıllı araçlar. */
@Composable
private fun SmartPanel(c: EditorController, a: EditorActions) {
    val task = c.autoTask
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task != null) {
            Text(task.first, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { task.second }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
            ActionButton("Durdur", Modifier.fillMaxWidth()) { a.cancelAuto() }
            return@Column
        }
        Text("Akıllı kadraj", style = MaterialTheme.typography.titleSmall)
        Text("Yatay videoyu dikey ya da kareye çevirirken yüzleri izleyip konuyu ortada tutar.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Aspect.PORTRAIT, Aspect.SQUARE, Aspect.FOUR_FIVE, Aspect.LANDSCAPE).forEach { asp -> ActionButton(asp.label, Modifier.weight(1f)) { a.smartReframe(asp) } }
        }
        Text("Öne çıkanlar", style = MaterialTheme.typography.titleSmall)
        Text("Tüm projeden hareketli, sesli ve yüz içeren anları seçip kısa bir kurgu yapar (müzik varsa ritme uyar).", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 60).forEach { sec -> ActionButton("$sec sn", Modifier.weight(1f)) { a.autoEditAll(sec) } }
        }
        Text("Sessizlikler", style = MaterialTheme.typography.titleSmall)
        ActionButton("Sessizlikleri kes", Modifier.fillMaxWidth()) { a.removeSilences() }
        Text("Konuşmalı videolarda uzun duraklamaları çıkarır; parça sesli bölümlere bölünür.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AutoPanel(c: EditorController, a: EditorActions) {
    val task = c.autoTask
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task != null) {
            Text(task.first, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { task.second }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
            ActionButton(if (task.first.startsWith("Takip")) "Duraklat" else "Durdur", Modifier.fillMaxWidth()) { a.cancelAuto() }
            return
        }
        Text("Derin takip", style = MaterialTheme.typography.titleSmall)
        if (c.trackSelecting) {
            Text("Önizlemedeki yeşil kutulardan birine dokun ya da hedefin çevresine parmağınla kutu çiz.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Takibi başlat", Modifier.weight(1.4f), primary = true, enabled = c.trackTarget != null) { a.deepTrack() }
                ActionButton("Vazgeç", Modifier.weight(1f)) { c.trackSelecting = false; c.trackTarget = null; c.trackCandidates = emptyList() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Hedef seç", Modifier.weight(1f), primary = true) { a.suggestTargets() }
                c.trackResume?.let { (t, _) -> ActionButton("Devam et · ${fmt(t)}", Modifier.weight(1f)) { a.resumeTrack() } }
            }
            Text("Kişi, araç, hayvan ya da herhangi bir nesneyi seç; kamera oynatma çizgisinden itibaren onu kadrajın ortasında tutar. Takip edilen aralık zaman çizelgesinde yeşil görünür.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            if (c.state.tracks.isNotEmpty()) {
                Text("Takipleri kaldır", color = Palette.Error, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { c.clearTracks() }.padding(vertical = 4.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
        ActionButton("Akıllı kadraj: kişileri takip et", Modifier.fillMaxWidth()) { a.autoFrame() }
        Text("Klipteki yüzleri bulur, kamerayı en belirgin kişiye yumuşakça çevirir (kesme aralığında).",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        LabeledRow("Öne çıkan") {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60).forEach { sec -> ActionButton("$sec sn", Modifier.weight(1f)) { a.autoHighlights(sec) } }
            }
        }
        Text("Kişi, ses ve hareketin en yoğun olduğu anlardan kısa bir kurgu oluşturur. Tümü telefonda, internetsiz çalışır.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Takip sürerken: hedefin canlı yeşil kutusu; kaybolunca uyarı. */
@Composable
private fun TrackLiveOverlay(c: EditorController, modifier: Modifier) {
    val green = Color(0xFF3DDC84)
    Box(modifier) {
        val b = c.trackLive
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            if (b != null) drawRect(green,
                topLeft = androidx.compose.ui.geometry.Offset((b.cx - b.w / 2) * size.width, (b.cy - b.h / 2) * size.height),
                size = androidx.compose.ui.geometry.Size(b.w * size.width, b.h * size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        }
        Text(if (b != null) "Takip ediliyor" else "Hedef aranıyor…", color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Hedef seçimi: önerilen kutular (ince yeşil), seçilen (kalın yeşil); dokun ya da kutu çiz. */
@Composable
private fun TrackOverlay(c: EditorController, modifier: Modifier) {
    val green = Color(0xFF3DDC84)
    var drawStart by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var drawEnd by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    androidx.compose.foundation.Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val nx = p.x / size.width; val ny = p.y / size.height
                    val hit = c.trackCandidates.filter { kotlin.math.abs(nx - it.cx) <= it.w / 2 && kotlin.math.abs(ny - it.cy) <= it.h / 2 }
                        .minByOrNull { it.w * it.h }
                    if (hit != null) { c.trackTarget = hit; c.say("Hedef: ${hit.label.ifEmpty { "seçildi" }}") }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { drawStart = it; drawEnd = it },
                    onDrag = { ch, _ -> drawEnd = ch.position },
                    onDragEnd = {
                        val s0 = drawStart; val e0 = drawEnd
                        if (s0 != null && e0 != null) {
                            val bw = kotlin.math.abs(e0.x - s0.x) / size.width; val bh = kotlin.math.abs(e0.y - s0.y) / size.height
                            if (bw > 0.03f && bh > 0.03f) {
                                c.trackTarget = com.mhmmtbg.x5reframe.auto.AutoEditor.Box(
                                    (s0.x + e0.x) / 2 / size.width, (s0.y + e0.y) / 2 / size.height, bw, bh, "Seçim")
                                c.say("Hedef seçildi")
                            }
                        }
                        drawStart = null; drawEnd = null
                    },
                )
            }
    ) {
        fun box(b: com.mhmmtbg.x5reframe.auto.AutoEditor.Box, width: Float) {
            drawRect(green, topLeft = androidx.compose.ui.geometry.Offset((b.cx - b.w / 2) * size.width, (b.cy - b.h / 2) * size.height),
                size = androidx.compose.ui.geometry.Size(b.w * size.width, b.h * size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width))
        }
        c.trackCandidates.forEach { if (it != c.trackTarget) box(it, 1.5.dp.toPx()) }
        c.trackTarget?.let { box(it, 3.dp.toPx()) }
        val s0 = drawStart; val e0 = drawEnd
        if (s0 != null && e0 != null) {
            drawRect(green, topLeft = androidx.compose.ui.geometry.Offset(minOf(s0.x, e0.x), minOf(s0.y, e0.y)),
                size = androidx.compose.ui.geometry.Size(kotlin.math.abs(e0.x - s0.x), kotlin.math.abs(e0.y - s0.y)),
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun LayerPanel(c: EditorController, a: EditorActions) {
    val s = c.state
    var editing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var stickers by remember { mutableStateOf(false) }
        var captions by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("+ Metin", Modifier.weight(1f), primary = true) { editing = true }
            ActionButton("+ Görsel", Modifier.weight(1f)) { a.pickImage() }
            ActionButton("Çıkartma", Modifier.weight(1f)) { stickers = true }
            ActionButton("Altyazı", Modifier.weight(1f)) { captions = true }
        }
        c.captionProgress?.let { p ->
            Text("Altyazı oluşturuluyor… %${(p * 100).toInt()}", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.mhmmtbg.x5reframe.model.Layer.Template.values().forEach { t -> Chip(t.label, false) { c.addTemplate(t) } }
        }
        if (stickers) StickerDialog({ stickers = false }) { e -> stickers = false; c.addSticker(e) }
        if (captions) CaptionDialog(a.captionModes(), { captions = false }) { mode -> captions = false; a.autoCaptions(mode) }
        if (s.layers.isEmpty()) {
            Text("Başlık, alt yazı, logo ya da resim içinde resim ekle. Seçili katmanı önizlemede tek parmakla taşı, iki parmakla büyüt ve döndür; zamanını çizelgedeki çubuktan ayarla.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            if (editing) TextDialog("", com.mhmmtbg.x5reframe.model.Layer.TextStyle.WHITE, { editing = false }) { t, _ -> c.addLayer(com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT, t); editing = false }
            return
        }
        // Katman listesi (üstteki en önde)
        s.layers.asReversed().forEach { l ->
            val sel = l.id == c.selectedLayer
            Row(
                Modifier.fillMaxWidth().background(if (sel) Palette.SurfaceHigh else Color.Transparent, RoundedCornerShape(8.dp))
                    .border(1.dp, if (sel) Palette.Accent else Palette.Outline, RoundedCornerShape(8.dp))
                    .clickable { c.selectedLayer = l.id }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) "T" else "▣", color = Palette.AccentText, modifier = Modifier.width(20.dp))
                Text(
                    if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) l.content.replace('\n', ' ') else "Görsel",
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    fmt(l.startUs) + " – " + (if (l.endUs < 0) "son" else fmt(l.endUs)),
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val l = c.currentLayer
        if (l != null) {
            LabeledRow("Boyut") {
                Slider(
                    value = l.scale, onValueChange = { v -> c.updateLayerLive { it.copy(scale = v) } }, onValueChangeFinished = { c.endLayerGesture() },
                    valueRange = 0.05f..1f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                )
            }
            LabeledRow("Saydamlık") {
                Slider(
                    value = l.opacity, onValueChange = { v -> c.updateLayerLive { it.copy(opacity = v) } }, onValueChangeFinished = { c.endLayerGesture() },
                    valueRange = 0.1f..1f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                )
            }
            LabeledRow("Döndür ${l.rotation.toInt()}°") {
                Slider(
                    value = l.rotation, onValueChange = { v -> c.updateLayerLive { it.copy(rotation = if (kotlin.math.abs(v) < 3f) 0f else v) } }, onValueChangeFinished = { c.endLayerGesture() },
                    valueRange = -180f..180f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                )
            }
            LabeledRow("Karışım") {
                Segmented(com.mhmmtbg.x5reframe.model.Layer.Blend.values().toList(), l.blend, { it.label }, { b -> c.updateLayer { it.copy(blend = b) } }, Modifier.weight(1f))
            }
            if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.IMAGE) {
                ChromaControls(l.chroma, { k -> c.updateLayer { it.copy(chroma = k) } }, { k -> c.updateLayerLive { it.copy(chroma = k) } }, { c.endLayerGesture() })
                LabeledRow("Biçim") {
                    Segmented(com.mhmmtbg.x5reframe.model.Layer.Mask.values().toList(), l.mask, { it.label }, { m -> c.updateLayer { it.copy(mask = m) } }, Modifier.weight(1f))
                }
            }
            if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) {
                LabeledRow("Stil") {
                    Segmented(com.mhmmtbg.x5reframe.model.Layer.TextStyle.values().toList(), l.style, { it.label }, { st -> c.updateLayer { it.copy(style = st) } }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.mhmmtbg.x5reframe.model.Layer.Font.values().forEach { f -> Chip(f.label, l.font == f) { c.updateLayer { it.copy(font = f) } } }
                }
                val swatches = listOf(0, 0xFFFFFFFF.toInt(), 0xFF111318.toInt(), 0xFFFFD43B.toInt(), 0xFFFF6B6B.toInt(), 0xFF4ADE80.toInt(), 0xFF5B8CFF.toInt(), 0xFFFF8FD1.toInt())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Renk", color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                    swatches.forEach { col ->
                        Box(
                            Modifier.minimumInteractiveComponentSize().semantics { contentDescription = if (col == 0) "Stilin rengi" else "Renk #%06X".format(col and 0xFFFFFF); selected = l.color == col }
                                .size(26.dp).clip(RoundedCornerShape(13.dp))
                                .background(if (col == 0) Palette.SurfaceHigh else Color(col))
                                .border(if (l.color == col) 2.dp else 1.dp, if (l.color == col) Palette.Accent else Palette.Outline, RoundedCornerShape(13.dp))
                                .clickable { c.updateLayer { it.copy(color = col) } },
                            contentAlignment = Alignment.Center,
                        ) { if (col == 0) Text("A", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                LabeledRow("Hizalama") {
                    Segmented(com.mhmmtbg.x5reframe.model.Layer.Align.values().toList(), l.align, { it.label }, { al -> c.updateLayer { it.copy(align = al) } }, Modifier.weight(1f))
                }
            }
            Text("Giriş", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.mhmmtbg.x5reframe.model.Layer.Anim.values().forEach { an -> Chip(an.label, l.animIn == an) { c.updateLayer { it.copy(animIn = an) } } }
            }
            Text("Çıkış", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.mhmmtbg.x5reframe.model.Layer.Anim.values().filter { it != com.mhmmtbg.x5reframe.model.Layer.Anim.TYPE }.forEach { an -> Chip(an.label, l.animOut == an) { c.updateLayer { it.copy(animOut = an) } } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("Baş: şimdi", Modifier.weight(1f)) { c.updateLayer { it.copy(startUs = c.positionUs) }; c.say("Katman ${fmt(c.positionUs)}'de başlar") }
                ActionButton("Son: şimdi", Modifier.weight(1f)) {
                    val p = c.positionUs
                    if (p <= l.startUs) c.say("Bitiş başlangıçtan sonra olmalı", false) else { c.updateLayer { it.copy(endUs = p) }; c.say("Katman ${fmt(p)}'de biter") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) ActionButton("Düzenle", Modifier.weight(1f)) { editing = true }
                ActionButton("Öne", Modifier.weight(0.8f)) { c.moveLayer(1) }
                ActionButton("Arkaya", Modifier.weight(0.8f)) { c.moveLayer(-1) }
                if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.IMAGE) ActionButton("Ayna", Modifier.weight(0.8f)) { c.updateLayer { it.copy(flipH = !it.flipH) } }
                ActionButton(if (l.visible) "Gizle" else "Göster", Modifier.weight(0.8f)) { c.updateLayer { it.copy(visible = !it.visible) } }
                ActionButton("Sil", Modifier.weight(0.7f)) { c.deleteLayer() }
            }
            if (editing) {
                TextDialog(if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) l.content else "", l.style, { editing = false }) { t, _ ->
                    if (l.kind == com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT) c.updateLayer { it.copy(content = t) } else c.addLayer(com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT, t)
                    editing = false
                }
            }
        } else if (editing) {
            TextDialog("", com.mhmmtbg.x5reframe.model.Layer.TextStyle.WHITE, { editing = false }) { t, _ -> c.addLayer(com.mhmmtbg.x5reframe.model.Layer.Kind.TEXT, t); editing = false }
        }
    }
}

private val STICKERS = listOf("😀", "😂", "😍", "😎", "🤩", "🥳", "😮", "🤔", "👍", "👏", "🙌", "💪", "❤️", "🔥", "✨", "⭐", "🎉", "🎬", "📍", "➡️", "⬇️", "✅", "❌", "💯", "🏔️", "🌊", "🌅", "🚴", "🏄", "⛷️", "🚗", "✈️")

@Composable
private fun StickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Çıkartma") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                STICKERS.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { e ->
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh).clickable { onPick(e) }, contentAlignment = Alignment.Center) {
                                Text(e, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Kapat") } },
        containerColor = Palette.Surface, tonalElevation = 0.dp,
    )
}

/** Otomatik altyazı: nasıl tanınacağını seç. */
@Composable
private fun CaptionDialog(modes: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Otomatik altyazı") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Parçadaki konuşma yazıya dökülür ve alt yazı olarak eklenir (öncekilerin yerine). Satırları sonra düzenleyebilirsin.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                modes.forEach { (id, label) ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh).clickable { onPick(id) }.padding(12.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (modes.isEmpty()) Text("Bu klipte ses yok.", color = Palette.Error, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Vazgeç") } },
        containerColor = Palette.Surface, tonalElevation = 0.dp,
    )
}

@Composable
private fun TextDialog(initial: String, style: com.mhmmtbg.x5reframe.model.Layer.TextStyle, onDismiss: () -> Unit, onOk: (String, com.mhmmtbg.x5reframe.model.Layer.TextStyle) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Metin") },
        text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, minLines = 1, maxLines = 4) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim(), style) }) { Text("Tamam", color = Palette.AccentText) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Vazgeç") } },
        containerColor = Palette.Surface, tonalElevation = 0.dp,
    )
}

@Composable
private fun MultiPanel(c: EditorController) {
    val s = c.state
    val layouts = com.mhmmtbg.x5reframe.model.Layout.values().toList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segmented(layouts.take(3), s.layout, { it.label }, { c.setLayout(it) }, Modifier.fillMaxWidth())
        Segmented(layouts.drop(3), s.layout, { it.label }, { c.setLayout(it) }, Modifier.fillMaxWidth())
        if (s.layout.count > 1) {
            LabeledRow("Bölme") {
                Segmented((0 until s.layout.count).toList(), c.activePane, { "${it + 1}" }, { c.selectPane(it) }, Modifier.weight(1f))
            }
            Text(
                "Önizlemede bir bölmeye dokunarak da seçebilirsin. Kadraj, anahtar kare ve açı ayarları seçili bölmeye uygulanır.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("Aynı 360° çekimden birden fazla açıyı tek videoda göstermek için bir düzen seç.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Normal video sabitleme: önce hareket analizi, sonra şiddet. */
@Composable
private fun FlatStabPanel(c: EditorController, a: EditorActions) {
    val s = c.state
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val p = c.stabProgress
        if (p != null) {
            Text("Hareket analiz ediliyor… %${(p * 100).toInt()}", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
            return@Column
        }
        if (s.flatStab == null) {
            Text("Titrek videoyu görüntüden sabitler: kamera hareketi kare kare ölçülür, yumuşatılır ve hafif yakınlaştırmayla telafi edilir.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            ActionButton("Analiz et ve sabitle", Modifier.fillMaxWidth(), primary = true) { a.analyzeFlatStab() }
            return@Column
        }
        Segmented(listOf(com.mhmmtbg.x5reframe.stab.StabMode.OFF, com.mhmmtbg.x5reframe.stab.StabMode.SMOOTH), s.stabMode,
            { if (it == com.mhmmtbg.x5reframe.stab.StabMode.OFF) "Kapalı" else "Sabit" },
            { m -> c.commit(s.copy(stabMode = m)); c.say(if (m == com.mhmmtbg.x5reframe.stab.StabMode.OFF) "Sabitleme kapalı" else "Sabitleme açık") }, Modifier.fillMaxWidth())
        if (s.stabMode != com.mhmmtbg.x5reframe.stab.StabMode.OFF) {
            LabeledRow("Şiddet") {
                Slider(
                    value = s.stabStrength.toFloat(), onValueChange = { v -> c.updateLive { it.copy(stabStrength = v.toDouble()) } }, onValueChangeFinished = { c.endLive() },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                )
            }
            val z = s.flatStab.correction(0, s.stabStrength.toFloat())?.get(3) ?: 1.0
            Text("Kenarlar görünmesin diye ${"%.2f".format(z)}× yakınlaştırılır. Şiddet arttıkça hareket daha akıcı olur, kırpma artar.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("Yeniden analiz et", color = Palette.AccentText, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clickable { a.analyzeFlatStab() }.padding(vertical = 4.dp))
    }
}

@Composable
private fun StabPanel(c: EditorController) {
    val s = c.state
    if (c.stabilizer == null) {
        Text("Bu klipte hareket sensörü verisi bulunamadı; sabitleme kullanılamıyor.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segmented(
            com.mhmmtbg.x5reframe.stab.StabMode.values().toList(), s.stabMode, { it.label },
            { m -> c.commit(s.copy(stabMode = m)); c.say("Sabitleme: ${m.label}") }, Modifier.fillMaxWidth(),
        )
        Text(s.stabMode.hint, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        val adjustable = s.stabMode == com.mhmmtbg.x5reframe.stab.StabMode.SMOOTH || s.stabMode == com.mhmmtbg.x5reframe.stab.StabMode.HORIZON
        if (adjustable) {
            var draft by remember(s.stabStrength) { mutableStateOf(s.stabStrength.toFloat()) }
            LabeledRow("Şiddet") {
                Slider(
                    value = draft, onValueChange = { draft = it },
                    onValueChangeFinished = { c.commit(s.copy(stabStrength = draft.toDouble())); c.say("Şiddet %${(draft * 100).toInt()}") },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
                )
                Text("%${(draft * 100).toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
        }
        if (s.stabMode == com.mhmmtbg.x5reframe.stab.StabMode.LOCK) {
            Text("Bakış yönü, kesme başlangıcındaki yöne kilitlenir. Kadraj sekmesindeki yön ve anahtar kareler bu yöne göre uygulanır.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AudioPanel(c: EditorController, a: EditorActions, seq: SequenceUi) {
    val s = c.state
    val au = seq.audio
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow("Orijinal ses", s.includeAudio) {
            c.commit(s.copy(includeAudio = it)); c.say(if (it) "Orijinal ses açık" else "Orijinal ses kapalı")
        }
        if (s.includeAudio) {
            VolumeRow("Seviye", au.originalVolume) { seq.updateAudio(au.copy(originalVolume = it)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
        if (au.musicUri == null) {
            ActionButton("+ Müzik ekle", Modifier.fillMaxWidth(), primary = true) { seq.pickMusic() }
            Text("Telefondaki bir şarkıyı arka plana ekle. Video uzunluğuna göre kırpılır ya da döngüye alınır.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("♪ " + au.musicName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text("Değiştir", color = Palette.AccentText, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { seq.pickMusic() }.padding(8.dp))
                Text("Kaldır", color = Palette.Error, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { seq.updateAudio(au.copy(musicUri = null, musicName = "")); c.say("Müzik kaldırıldı") }.padding(8.dp))
            }
            VolumeRow("Müzik", au.musicVolume) { seq.updateAudio(au.copy(musicVolume = it)) }
            SwitchRow("Döngü (müzik kısaysa başa sar)", au.loop) { seq.updateAudio(au.copy(loop = it)) }
            SwitchRow("Başta yükselsin, sonda azalsın", au.fade) { seq.updateAudio(au.copy(fade = it)) }
        }
        if (au.musicUri != null) {
            SwitchRow("Konuşmada müziği kıs", au.duck) { seq.updateAudio(au.copy(duck = it)) }
            if (c.beatMarks.isNotEmpty()) {
                Text("Ritim: ${c.beatMarks.size} vuruş bu parçada (çizelgede mavi noktalar, tutamaklar yapışır)", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ritme göre böl", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                    listOf(1, 2, 4).forEach { n -> Chip(if (n == 1) "Her vuruş" else "$n vuruşta bir", false) { a.splitOnBeats(n) } }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
        // Bu parça
        val segI = seq.current
        seq.segments.getOrNull(segI)?.let { sg ->
            Text("Bu parça", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            VolumeRow("Parça sesi", sg.volume) { v -> seq.updateSegment(segI) { it.copy(volume = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Yükselme", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
                listOf(0L, 500_000L, 1_000_000L, 2_000_000L).forEach { us -> Chip(if (us == 0L) "Yok" else "${us / 1e6} sn", sg.audioFadeInUs == us) { seq.updateSegment(segI) { it.copy(audioFadeInUs = us) } } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Alçalma", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
                listOf(0L, 500_000L, 1_000_000L, 2_000_000L).forEach { us -> Chip(if (us == 0L) "Yok" else "${us / 1e6} sn", sg.audioFadeOutUs == us) { seq.updateSegment(segI) { it.copy(audioFadeOutUs = us) } } }
            }
        }
        SwitchRow("Gürültü azaltma (rüzgâr, uğultu)", au.denoise) { seq.updateAudio(au.copy(denoise = it)) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
        // Seslendirme
        ActionButton(if (c.voiceRecording) "■ Kaydı durdur" else "● Seslendirme kaydet", Modifier.fillMaxWidth(), primary = !c.voiceRecording) { a.toggleVoice() }
        Text("Oynatma çizgisinden başlar; kayıt sırasında video sessiz oynar.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        au.voices.forEachIndexed { vi, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎙 ${"%.1f".format(v.startUs / 1e6)} sn · ${"%.1f".format(v.durationUs / 1e6)} sn", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("Sil", color = Palette.Error, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { seq.updateAudio(au.copy(voices = au.voices.filterIndexed { k, _ -> k != vi })); c.say("Seslendirme silindi") }.padding(8.dp))
            }
        }
        SwitchRow("Önizlemede sesi kapat", c.previewMuted) { c.previewMuted = it; a.setMuted(it) }
        if (au.duck || au.denoise) Text("Kısma ve gürültü azaltma dışa aktarımda uygulanır.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        if (au.needsMix) Text("Ses karıştırılacağı için dışa aktarımda AAC olarak yeniden kodlanır.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        if (seq.segments.any { it.reverse || it.curve != com.mhmmtbg.x5reframe.model.SpeedCurve.NONE || !com.mhmmtbg.x5reframe.media.AudioMixer.hasAudioAt(it.speed) })
            Text("Ters, hız eğrili ya da 0,25×–4× dışındaki parçalarda orijinal ses kapanır.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun VolumeRow(label: String, value: Float, onCommit: (Float) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    LabeledRow(label) {
        Slider(
            value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onCommit(draft) },
            valueRange = 0f..1.5f, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
        )
        Text("%${(draft * 100).toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
}

private class ExportPreset(val label: String, val aspect: Aspect, val quality: Quality, val fps: Int,
                           val bitrate: com.mhmmtbg.x5reframe.model.BitrateLevel = com.mhmmtbg.x5reframe.model.BitrateLevel.STANDARD, val only360: Boolean = false)

private val EXPORT_PRESETS = listOf(
    ExportPreset("Reels · TikTok", Aspect.PORTRAIT, Quality.FHD, 30, com.mhmmtbg.x5reframe.model.BitrateLevel.HIGH),
    ExportPreset("YouTube", Aspect.LANDSCAPE, Quality.FHD, 0, com.mhmmtbg.x5reframe.model.BitrateLevel.HIGH),
    ExportPreset("YouTube 4K", Aspect.LANDSCAPE, Quality.UHD, 0, com.mhmmtbg.x5reframe.model.BitrateLevel.HIGH),
    ExportPreset("Instagram kare", Aspect.SQUARE, Quality.FHD, 30),
    ExportPreset("Instagram 4:5", Aspect.FOUR_FIVE, Quality.FHD, 30),
    ExportPreset("WhatsApp", Aspect.LANDSCAPE, Quality.HD, 30),
    ExportPreset("YouTube 360°", Aspect.PANO, Quality.UHD, 0, com.mhmmtbg.x5reframe.model.BitrateLevel.HIGH, only360 = true),
)

@Composable
internal fun ExportSheet(c: EditorController, a: EditorActions, seq: SequenceUi) {
    val s = c.state
    val (w, h) = s.outputSize()
    var whole by remember { mutableStateOf(seq.segments.size > 1) }
    var format by remember { mutableStateOf(com.mhmmtbg.x5reframe.media.Exporter.Format.MP4) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dışa aktar", style = MaterialTheme.typography.titleMedium)
        Text(
            "${w}×$h · ${s.aspect.label} · ${"%.1f".format((s.outUs - s.inUs) / 1e6)} sn · ${if (s.includeAudio) "sesli" else "sessiz"}",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
        when (val ex = c.exportUi) {
            is EditorController.ExportUi.Running -> {
                LinearProgressIndicator(
                    progress = { ex.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Palette.Accent, trackColor = Palette.SurfaceHigh,
                )
                Text(
                    "%${(ex.fraction * 100).toInt()}" + if (ex.speed > 0) "  ·  ${"%.1f".format(ex.speed)}× gerçek zaman" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (c.exportQueued > 0) Text("Sırada ${c.exportQueued} iş daha var.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                Text("Uygulamadan çıkabilir ya da ekranı kapatabilirsin; dışa aktarım bildirimde sürer.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                ActionButton("İptal", Modifier.fillMaxWidth()) { a.cancelExport() }
            }
            is EditorController.ExportUi.Done -> {
                Text(
                    "Kaydedildi (X5Reframe klasörü)\n${"%.1f".format(ex.videoSec)} sn ${"%.1f".format(ex.tookSec)} sn'de hazırlandı (${"%.1f".format(ex.videoSec / ex.tookSec.coerceAtLeast(0.1f))}×)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Aç", Modifier.weight(1f), primary = true) { a.open(ex.uri) }
                    ActionButton("Paylaş", Modifier.weight(1f)) { a.share(ex.uri) }
                }
                ActionButton("Yeni dışa aktarım", Modifier.fillMaxWidth()) { c.exportUi = EditorController.ExportUi.Idle }
            }
            else -> {
                if (c.exportUi is EditorController.ExportUi.Failed) {
                    Text("Olmadı: ${(c.exportUi as EditorController.ExportUi.Failed).message}", color = Palette.Error, style = MaterialTheme.typography.bodyMedium)
                }
                LabeledRow("Biçim") {
                    Segmented(com.mhmmtbg.x5reframe.media.Exporter.Format.values().toList(), format,
                        { when (it) { com.mhmmtbg.x5reframe.media.Exporter.Format.MP4 -> "Video"; com.mhmmtbg.x5reframe.media.Exporter.Format.GIF -> "GIF"; else -> "Yalnız ses" } },
                        { format = it }, Modifier.weight(1f))
                }
                if (format == com.mhmmtbg.x5reframe.media.Exporter.Format.MP4) {
                    Text("Hazır ayarlar", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EXPORT_PRESETS.filter { !it.only360 || c.clip.is360 }.forEach { p ->
                            Chip(p.label, s.aspect == p.aspect && s.quality == p.quality && s.fps == p.fps) {
                                c.commit(s.copy(aspect = p.aspect, quality = p.quality, fps = p.fps, hevc = false, bitrate = p.bitrate)); c.say(p.label)
                            }
                        }
                    }
                }
                if (format != com.mhmmtbg.x5reframe.media.Exporter.Format.AUDIO) LabeledRow("Oran") {
                    Segmented(Aspect.values().toList(), s.aspect, { it.label }, { c.commit(s.copy(aspect = it)) }, Modifier.weight(1f))
                }
                if (format == com.mhmmtbg.x5reframe.media.Exporter.Format.MP4) LabeledRow("Çözünürlük") {
                    Segmented(Quality.selectable, s.quality, { it.label }, { c.commit(s.copy(quality = it)) }, Modifier.weight(1f))
                }
                if (format == com.mhmmtbg.x5reframe.media.Exporter.Format.MP4) LabeledRow("Kare hızı") {
                    val src = c.clip.frameRate.roundToInt()
                    Segmented(listOf(0, 24, 25, 30, 50, 60).filter { it == 0 || it <= src + 1 }.distinct(), s.fps,
                        { if (it == 0) "Orijinal ($src)" else "$it" }, { c.commit(s.copy(fps = it)) }, Modifier.weight(1f))
                }
                if (format == com.mhmmtbg.x5reframe.media.Exporter.Format.GIF)
                    Text("GIF: uzun kenar 480 px, 15 kare/sn, sessiz. Kısa anlar (≤ 15 sn) için uygundur; dosya Resimler/X5Reframe'e kaydedilir.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                if (format == com.mhmmtbg.x5reframe.media.Exporter.Format.AUDIO)
                    Text("Yalnız ses: orijinal ses, müzik ve seslendirmeler karıştırılıp M4A (AAC) olarak Müzik/X5Reframe'e kaydedilir.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                if (format == com.mhmmtbg.x5reframe.media.Exporter.Format.MP4) {
                LabeledRow("Bit hızı") {
                    Segmented(com.mhmmtbg.x5reframe.model.BitrateLevel.values().toList(), s.bitrate, { it.label }, { c.commit(s.copy(bitrate = it)) }, Modifier.weight(1f))
                }
                LabeledRow("Kodek") {
                    Segmented(listOf(false, true), s.hevc, { if (it) "H.265" else "H.264" }, { c.commit(s.copy(hevc = it)) }, Modifier.weight(1f))
                }
                Text(
                    if (s.hevc) "H.265: aynı kalitede daha küçük dosya; bazı eski cihazlar oynatamayabilir." else "H.264: her yerde oynar.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                val durSec = if (whole) seq.segments.sumOf { it.outputDurationUs } / 1e6 else (s.outUs - s.inUs) / 1e6
                val mbps = (if (s.hevc) s.quality.mbpsAvc * 0.7f else s.quality.mbpsAvc.toFloat()) * s.bitrate.factor
                Text("Tahmini boyut: ~${(mbps * durSec / 8).roundToInt()} MB · ${"%.0f".format(durSec)} sn",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (seq.segments.size > 1) {
                    LabeledRow("Kapsam") {
                        Segmented(listOf(true, false), whole, { if (it) "Tüm proje (${seq.segments.size})" else "Bu parça" }, { whole = it }, Modifier.weight(1f))
                    }
                }
                Button(
                    onClick = { a.startExport(whole, format) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), elevation = null,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                ) { Text("Başlat") }
            }
        }
    }
}

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
import androidx.compose.ui.viewinterop.AndroidView
import com.mhmmtbg.x5reframe.model.Aspect
import com.mhmmtbg.x5reframe.model.Quality
import com.mhmmtbg.x5reframe.ui.EditorController.Companion.fmt
import com.mhmmtbg.x5reframe.ui.theme.Palette
import kotlinx.coroutines.delay

class EditorActions(
    val back: () -> Unit,
    val playPause: () -> Unit,
    val seek: (Long) -> Unit,
    val startExport: () -> Unit,
    val cancelExport: () -> Unit,
    val open: (Uri) -> Unit,
    val share: (Uri) -> Unit,
    val setMuted: (Boolean) -> Unit,
    val step: (Boolean) -> Unit,
)

private enum class Tool(val label: String) { FRAME("Kadraj"), TRIM("Kes"), AUDIO("Ses") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    c: EditorController,
    actions: EditorActions,
    surfaceFactory: (Context) -> SurfaceView,
    timelineFactory: (Context) -> TimelineView,
) {
    var tool by rememberSaveable { mutableStateOf(Tool.FRAME) }
    var showExport by remember { mutableStateOf(false) }
    val s = c.state

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        TopBar(c, actions, onExport = { showExport = true })
        val ex = c.exportUi
        if (ex is EditorController.ExportUi.Running) {
            LinearProgressIndicator(
                progress = { ex.fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Palette.Accent, trackColor = Palette.SurfaceHigh,
            )
        }

        // Önizleme
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = s.aspect.w.toFloat() / s.aspect.h
            val boxRatio = maxWidth.value / maxHeight.value
            val w = if (ratio > boxRatio) maxWidth else maxHeight * ratio
            val h = if (ratio > boxRatio) maxWidth / ratio else maxHeight
            AndroidView(factory = surfaceFactory, modifier = Modifier.size(w, h))
            FeedbackPill(c, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            ViewHud(c, Modifier.align(Alignment.BottomStart).padding(10.dp))
        }

        Transport(c, actions)

        AndroidView(
            factory = timelineFactory,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            update = { t ->
                t.durationUs = c.clip.durationUs
                t.inUs = s.inUs
                t.outUs = s.outUs
                t.keyframes = s.keyframes.map { it.timeUs }
                t.positionUs = c.positionUs
            },
        )

        ToolTabs(tool) { tool = it }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 170.dp, max = 220.dp).background(Palette.Surface)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when (tool) {
                Tool.FRAME -> FramePanel(c)
                Tool.TRIM -> TrimPanel(c)
                Tool.AUDIO -> AudioPanel(c, actions)
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
            ExportSheet(c, actions)
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
                "${c.clip.cameraType} · ${"%.1f".format(c.clip.durationUs / 1e6)} sn" + if (c.clip.lrv != null) " · hızlı önizleme" else "",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            )
        }
        IconButton(onClick = c::undo, enabled = c.canUndo) {
            Icon(Glyphs.Undo, "Geri al", tint = if (c.canUndo) Palette.Text else Palette.Outline)
        }
        IconButton(onClick = c::redo, enabled = c.canRedo) {
            Icon(Glyphs.Redo, "Yinele", tint = if (c.canRedo) Palette.Text else Palette.Outline)
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
private fun FeedbackPill(c: EditorController, modifier: Modifier) {
    val fb = c.feedback
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(fb?.id) {
        if (fb != null) { visible = true; delay(1600); visible = false }
    }
    AnimatedVisibility(visible && fb != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val positive = fb?.positive ?: true
        Row(
            Modifier.background(Color(0xEE202329), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (positive) {
                Icon(Glyphs.Check, null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(fb?.text ?: "", color = if (positive) Palette.Text else Palette.Error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ViewHud(c: EditorController, modifier: Modifier) {
    val v = c.currentView
    val editing = c.liveView != null
    Text(
        (if (editing) "● kaydedilmedi  " else "") + "yön ${v.yaw.toInt()}°  eğim ${v.pitch.toInt()}°  açı ${v.fov.toInt()}°",
        modifier = modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = if (editing) Palette.Keyframe else Palette.Text,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Transport(c: EditorController, a: EditorActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${fmt(c.positionUs)} / ${fmt(c.clip.durationUs)}",
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { c.previousKeyframeTime()?.let(a.seek) }, enabled = c.previousKeyframeTime() != null) {
            Icon(Glyphs.PrevKey, "Önceki anahtar kare", tint = if (c.previousKeyframeTime() != null) Palette.Text else Palette.Outline)
        }
        IconButton(onClick = { a.step(false) }) { Icon(Glyphs.FrameBack, "Bir kare geri", tint = Palette.Text) }
        FilledIconButton(
            onClick = a.playPause,
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Text, contentColor = Palette.Background),
        ) { Icon(if (c.playing) Glyphs.Pause else Glyphs.Play, if (c.playing) "Duraklat" else "Oynat") }
        IconButton(onClick = { a.step(true) }) { Icon(Glyphs.FrameFwd, "Bir kare ileri", tint = Palette.Text) }
        IconButton(onClick = { c.nextKeyframeTime()?.let(a.seek) }, enabled = c.nextKeyframeTime() != null) {
            Icon(Glyphs.NextKey, "Sonraki anahtar kare", tint = if (c.nextKeyframeTime() != null) Palette.Text else Palette.Outline)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconButton(onClick = { c.previewMuted = !c.previewMuted; a.setMuted(c.previewMuted) }) {
                Icon(if (c.previewMuted) Glyphs.VolumeOff else Glyphs.VolumeOn, "Önizleme sesi", tint = Palette.Muted)
            }
        }
    }
}

@Composable
private fun ToolTabs(selected: Tool, onSelect: (Tool) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(top = 4.dp)) {
        Tool.values().forEach { t ->
            val on = t == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.label, color = if (on) Palette.Text else Palette.Muted, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(32.dp).height(3.dp).background(if (on) Palette.Accent else Color.Transparent, RoundedCornerShape(2.dp)))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
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
                Text(label(o), color = if (on) Palette.Accent else Palette.Muted, style = MaterialTheme.typography.labelLarge)
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
            colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Accent),
        ) { Text(text, maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.5f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Text),
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
        LabeledRow("Lens") {
            Segmented(listOf(false, true), v.wide, { if (it) "Geniş" else "Doğrusal" }, { w -> c.changeView { it.copy(wide = w) } }, Modifier.weight(1f))
        }
        LabeledRow("Açı ${v.fov.toInt()}°") {
            Slider(
                value = v.fov.toFloat(),
                onValueChange = { f -> c.beginViewGestureOnce(); c.updateView { it.copy(fov = f.toDouble()) } },
                onValueChangeFinished = { c.endViewGestureOnce() },
                valueRange = 20f..(if (v.wide) 220f else 120f),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHigh),
            )
        }
        val near = s.keyframeNear(c.positionUs) != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (near) "Kareyi güncelle" else "Anahtar kare ekle", Modifier.weight(1.4f), primary = true) { c.addOrUpdateKeyframe() }
            ActionButton("Sil", Modifier.weight(0.8f), enabled = near) { c.deleteKeyframe() }
            ActionButton("Ufku sıfırla", Modifier.weight(1f)) { c.changeView { it.copy(pitch = 0.0, roll = 0.0) }; c.say("Ufuk sıfırlandı") }
        }
        Text(
            if (s.keyframes.isEmpty()) "Önizlemede sürükle: yön · iki parmak: yakınlaştır. Anahtar kare eklersen kamera kareler arasında yumuşakça hareket eder."
            else "${s.keyframes.size} anahtar kare. Değiştirdiğin kadraj, Kareyi güncelle / ekle ile kaydedilir.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (s.keyframes.isNotEmpty()) {
            Text(
                "Tüm anahtar kareleri temizle", color = Palette.Error, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { c.clearKeyframes() }.padding(vertical = 6.dp),
            )
        }
    }
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
            "Düğmeler oynatma çizgisinin olduğu yeri kullanır. Zaman çizelgesindeki sarı tutamakları sürükleyerek de ayarlayabilirsin.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AudioPanel(c: EditorController, a: EditorActions) {
    val s = c.state
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow("Orijinal ses dışa aktarılsın", s.includeAudio) {
            c.commit(s.copy(includeAudio = it)); c.say(if (it) "Ses dahil" else "Ses çıkarıldı")
        }
        SwitchRow("Önizlemede sesi kapat", c.previewMuted) { c.previewMuted = it; a.setMuted(it) }
        Text("Müzik ekleme ve ses seviyeleri sonraki aşamalarda gelecek.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun ExportSheet(c: EditorController, a: EditorActions) {
    val s = c.state
    val (w, h) = s.outputSize()
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
                ActionButton("İptal", Modifier.fillMaxWidth()) { a.cancelExport() }
            }
            is EditorController.ExportUi.Done -> {
                Text(
                    "Kaydedildi: Filmler/X5Reframe\n${"%.1f".format(ex.videoSec)} sn video ${"%.1f".format(ex.tookSec)} sn'de hazırlandı (${"%.1f".format(ex.videoSec / ex.tookSec.coerceAtLeast(0.1f))}×)",
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
                LabeledRow("Kalite") {
                    Segmented(Quality.values().toList(), s.quality, { it.label }, { c.commit(s.copy(quality = it)) }, Modifier.weight(1f))
                }
                LabeledRow("Kodek") {
                    Segmented(listOf(false, true), s.hevc, { if (it) "H.265" else "H.264" }, { c.commit(s.copy(hevc = it)) }, Modifier.weight(1f))
                }
                Text(
                    if (s.hevc) "H.265: aynı kalitede daha küçük dosya; bazı eski cihazlar oynatamayabilir." else "H.264: her yerde oynar.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = a.startExport, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), elevation = null,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                ) { Text("Başlat") }
            }
        }
    }
}

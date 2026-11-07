package com.mhmmtbg.x5reframe.ui

import android.content.Context
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mhmmtbg.x5reframe.stab.StabMode
import com.mhmmtbg.x5reframe.ui.EditorController.Companion.fmt
import com.mhmmtbg.x5reframe.ui.theme.Palette
import kotlinx.coroutines.delay

/** Oynatıcı sayfasındaki görünüm modları. */
enum class ViewMode(val label: String) { FREE("Serbest 360°"), FORWARD("İleri"), SELFIE("Selfie"), SMART("Akıllı kadraj") }

class ViewerActions(
    val editor: EditorActions,
    val openEditor: (tool: String?) -> Unit,
    val snapshot: () -> Unit,
    /** Kayıt modunu başlat / bitir. */
    val record: () -> Unit,
    val highlights: () -> Unit,
    val autoEdit: () -> Unit,
    /** İleri (false) ya da selfie (true) yönüne çevir; çekimi yapan kişi görüntüden bulunur. */
    val orient: (selfie: Boolean) -> Unit,
    val isFavorite: () -> Boolean,
    val toggleFavorite: () -> Unit,
    /** Kameradaki dosyayı sil (izin yoksa null). */
    val delete: (() -> Unit)?,
    /** Kameradan kablosuz önizleme: düzenleme/kaydetme yok, yalnızca bakış ve aktarma. */
    val previewOnly: Boolean = false,
    val download: (() -> Unit)? = null,
)

/** Kaydırma yerine dokunma: sol kenar düğmesi. */
private class SideItem(val label: String, val icon: ImageVector, val active: Boolean = false, val onClick: () -> Unit)

/**
 * Oynatıcı sayfası: tam ekran 360 görüntü, solda dikey araç çubuğu (etiketler 3 sn sonra gizlenir),
 * altta görünüm modları, ilerleme çubuğu ve Oto Düzenle / Düzenleyici / Kaydet.
 * Görüntüye çift dokunmak tüm arayüzü gizler (temiz ekran).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    c: EditorController,
    a: ViewerActions,
    surfaceFactory: (Context) -> SurfaceView,
    cleanToggles: Int,
    seq: SequenceUi,
) {
    var clean by remember { mutableStateOf(false) }
    LaunchedEffect(cleanToggles) { if (cleanToggles > 0) clean = !clean }
    var labelsVisible by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(poke) { labelsVisible = true; delay(3000); labelsVisible = false }
    var mode by remember { mutableStateOf(ViewMode.FREE) }
    var showExport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val fav = a.isFavorite()

    com.mhmmtbg.x5reframe.ui.theme.BlackSystemBars()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = surfaceFactory, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(!clean, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Üst çubuk
                Row(
                    Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIcon(Glyphs.Back, "Geri") { a.editor.back() }
                    Text(c.fileName, Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, color = Color.White)
                    RoundIcon(if (c.previewMuted) Glyphs.VolumeOff else Glyphs.VolumeOn, "Ses") {
                        c.previewMuted = !c.previewMuted; a.editor.setMuted(c.previewMuted)
                    }
                }

                // Sol araç çubuğu
                val items = if (a.previewOnly) emptyList() else listOf(
                    SideItem("Kes", Glyphs.Cut) { a.openEditor("trim") },
                    SideItem("Anahtar kare", Glyphs.Keyframe, c.activeKeyframeNear(c.positionUs) != null) { c.addOrUpdateKeyframe() },
                    SideItem("Anlık görüntü", Glyphs.Camera) { a.snapshot() },
                    SideItem("Öne çıkanlar", Glyphs.Sparkle) { a.highlights() },
                    SideItem(if (fav) "Favoride" else "Favori", if (fav) Glyphs.Star else Glyphs.StarOutline, fav) { a.toggleFavorite() },
                ) + listOfNotNull(a.delete?.let { SideItem("Sil", Glyphs.Delete) { confirmDelete = true } })
                Column(
                    Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items.forEach { item ->
                        Row(
                            Modifier.clip(RoundedCornerShape(22.dp)).clickable { poke++; item.onClick() }
                                .background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(item.icon, item.label, Modifier.size(22.dp), tint = if (item.active) Palette.AccentText else Color.White)
                            AnimatedVisibility(labelsVisible) {
                                Text(item.label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
                            }
                        }
                    }
                }

                FeedbackPill(c, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))

                // Alt bölüm
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x80000000)).navigationBarsPadding().padding(bottom = 6.dp)) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        ViewMode.values().forEach { m ->
                            val on = m == mode
                            Text(
                                m.label, color = if (on) Palette.OnAccent else Color.White, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Accent else Color(0x33FFFFFF))
                                    .clickable { mode = m; applyMode(c, a, m) }.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Seek(c, a.editor, a.record)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (a.previewOnly) {
                            Text("Kameradan önizleme · telefona indirilmedi", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1.4f).align(Alignment.CenterVertically))
                            a.download?.let { BottomButton("Telefona aktar", Glyphs.Save, Modifier.weight(1f), primary = true, onClick = it) }
                        } else {
                            BottomButton("Oto Düzenle", Glyphs.Sparkle, Modifier.weight(1f)) { a.autoEdit() }
                            BottomButton("Düzenleyici", Glyphs.Tune, Modifier.weight(1f)) { a.openEditor(null) }
                            BottomButton("Kaydet", Glyphs.Save, Modifier.weight(1f), primary = true) { showExport = true }
                        }
                    }
                }
            }
        }
        c.autoTask?.let { (label, p) ->
            Column(
                Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(progress = { p }, Modifier.width(180.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
                TextButton(onClick = a.editor.cancelAuto) { Text("İptal", color = Palette.Muted) }
            }
        }
    }

    if (showExport) {
        ModalBottomSheet(
            onDismissRequest = { showExport = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface, tonalElevation = 0.dp,
        ) { ExportSheet(c, a.editor, seq) }
    }
    if (confirmDelete && a.delete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Çekim silinsin mi?") },
            text = { Text("\"${c.fileName}\" ve önizleme dosyası kamera klasöründen kalıcı olarak silinir.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; a.delete?.invoke() }) { Text("Sil", color = Palette.Error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } },
            containerColor = Palette.Surface, tonalElevation = 0.dp,
        )
    }
}

private fun applyMode(c: EditorController, a: ViewerActions, m: ViewMode) {
    // Insta360 gibi: her modda ufuk düz ve görüntü kameranın gidişini yumuşakça izler (FlowState).
    val hasImu = c.stabilizer != null
    if (hasImu && c.state.stabMode != StabMode.HORIZON) c.commit(c.state.copy(stabMode = StabMode.HORIZON))
    when (m) {
        ViewMode.FREE -> c.say("Serbest 360°: sürükleyerek bak")
        ViewMode.FORWARD -> a.orient(false)
        ViewMode.SELFIE -> a.orient(true)
        ViewMode.SMART -> a.editor.autoFrame()
    }
}

@Composable
private fun Seek(c: EditorController, a: EditorActions, record: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = c.clip.durationUs.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = a.playPause), contentAlignment = Alignment.Center) {
            Icon(if (c.playing) Glyphs.Pause else Glyphs.Play, if (c.playing) "Duraklat" else "Oynat", Modifier.size(28.dp), tint = Color.White)
        }
        Text(fmt(if (dragging) (dragValue * dur).toLong() else c.positionUs), color = Color.White, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
        Slider(
            value = if (dragging) dragValue else (c.positionUs.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it; a.seek((it * dur).toLong()) },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.Accent, inactiveTrackColor = Color(0x55FFFFFF)),
        )
        Text(fmt(c.clip.durationUs), color = Color(0xCCFFFFFF), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        // Kayıt: oynarken yapılan bakış hareketi anahtar karelere dönüşür
        Box(Modifier.padding(start = 8.dp).size(44.dp).clip(CircleShape).clickable(onClick = record), contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (c.recording) 12.dp else 20.dp).clip(if (c.recording) RoundedCornerShape(2.dp) else CircleShape).background(Palette.Error))
            }
        }
    }
    if (!c.recording) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
            listOf(false to "Parmak", true to "Jiroskop").forEach { (g, label) ->
                val on = c.recordGyro == g
                Text(label, color = if (on) Color.White else Color(0x99FFFFFF), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { c.recordGyro = g }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    } else {
        Text("● Kayıt sürüyor — bakmak istediğin yöne çevir; bitirmek için kare düğmeye dokun",
            color = Palette.Error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp), tint = Color.White)
    }
}

@Composable
private fun BottomButton(label: String, icon: ImageVector, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (primary) Palette.Accent else Color(0x33FFFFFF)).clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (primary) Palette.OnAccent else Color.White)
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (primary) Palette.OnAccent else Color.White, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

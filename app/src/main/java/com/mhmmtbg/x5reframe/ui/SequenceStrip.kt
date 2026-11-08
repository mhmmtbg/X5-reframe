package com.mhmmtbg.x5reframe.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import com.mhmmtbg.x5reframe.project.Segment
import com.mhmmtbg.x5reframe.project.Transition
import com.mhmmtbg.x5reframe.ui.theme.Palette

/** Projenin parçaları ve parça işlemleri. */
class SequenceUi(
    val segments: List<Segment>,
    val current: Int,
    val select: (Int) -> Unit,
    val add: () -> Unit,
    val move: (Int, Int) -> Unit,
    val split: (Int) -> Unit,
    val duplicate: (Int) -> Unit,
    val delete: (Int) -> Unit,
    val setSpeed: (Int, Double) -> Unit,
    val setTransition: (Int, Transition) -> Unit,
    /** Parçanın hız / ters / eğri alanlarını değiştir. */
    val updateSegment: (Int, (Segment) -> Segment) -> Unit = { _, _ -> },
    /** Oynatma çizgisindeki kareyi dondur (fotoğraf parçası olarak araya ekle). */
    val freeze: (Long) -> Unit = {},
    /** Rengi projedeki tüm parçalara kopyala. */
    val applyColorToAll: (com.mhmmtbg.x5reframe.model.ColorGrade) -> Unit = {},
    /** Proje sesi */
    val audio: com.mhmmtbg.x5reframe.project.ProjectAudio = com.mhmmtbg.x5reframe.project.ProjectAudio(),
    val pickMusic: () -> Unit = {},
    val updateAudio: (com.mhmmtbg.x5reframe.project.ProjectAudio) -> Unit = {},
)

/** Parça şeridi: dokun = seç, uzun bas = işlemler menüsü. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SequenceStrip(seq: SequenceUi) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(seq.segments, key = { _, s -> s.id }) { i, seg ->
            var menu by remember { mutableStateOf(false) }
            val selected = i == seq.current
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box {
                Column(
                    Modifier.width(92.dp).height(44.dp)
                        .background(if (selected) Palette.SurfaceHigh else Palette.Surface, RoundedCornerShape(8.dp))
                        .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline, RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { seq.select(i) }, onLongClick = { menu = true })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("${i + 1} · ${"%.1f".format(seg.outputDurationUs / 1e6)} sn", style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Palette.Text else Palette.Muted)
                    val tags = buildList {
                        if (seg.speed != 1.0) add("${fmtSpeed(seg.speed)}×")
                        if (seg.reverse) add("◀◀")
                        if (seg.curve != com.mhmmtbg.x5reframe.model.SpeedCurve.NONE) add(seg.curve.label)
                        if (seg.edit.layout.count > 1) add(seg.edit.layout.label)
                        if (seg.transitionOut != Transition.CUT) add("◐")
                    }
                    Text(tags.joinToString(" · ").ifEmpty { seg.clipName.substringAfterLast('_').substringBefore('.') },
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Oynatma çizgisinden böl") }, onClick = { menu = false; seq.split(i) })
                    DropdownMenuItem(text = { Text("Çoğalt") }, onClick = { menu = false; seq.duplicate(i) })
                    if (i > 0) DropdownMenuItem(text = { Text("◀ Sola taşı") }, onClick = { menu = false; seq.move(i, -1) })
                    if (i < seq.segments.size - 1) DropdownMenuItem(text = { Text("Sağa taşı ▶") }, onClick = { menu = false; seq.move(i, 1) })
                    HorizontalDivider(color = Palette.Outline)
                    listOf(0.25, 0.5, 1.0, 2.0, 4.0, 10.0).forEach { sp ->
                        DropdownMenuItem(
                            text = { Text("Hız ${fmtSpeed(sp)}×" + if (sp == seg.speed) "  ✓" else "") },
                            onClick = { menu = false; seq.setSpeed(i, sp) },
                        )
                    }
                    HorizontalDivider(color = Palette.Outline)
                    DropdownMenuItem(text = { Text("Sil", color = Palette.Error) }, onClick = { menu = false; seq.delete(i) })
                }
              }
            if (i < seq.segments.size - 1) {
                // Parçalar arasındaki geçiş düğmesi: dokununca canlandırmalı seçici açılır
                var picker by remember { mutableStateOf(false) }
                val on = seg.transitionOut != Transition.CUT
                Box(
                    Modifier.padding(start = 6.dp).size(28.dp, 28.dp)
                        .background(if (on) Palette.Accent else Palette.SurfaceHigh, RoundedCornerShape(6.dp))
                        .combinedClickable(onClick = { picker = true })
                        .semantics { contentDescription = "Geçiş: ${seg.transitionOut.label}. Değiştirmek için dokun." },
                    contentAlignment = Alignment.Center,
                ) { Text(if (on) "◐" else "|", color = if (on) Palette.OnAccent else Palette.Muted, style = MaterialTheme.typography.labelMedium) }
                if (picker) TransitionPicker(seg.transitionOut, { picker = false }) { tr -> picker = false; seq.setTransition(i, tr) }
            }
            }
        }
        item {
            Box(
                Modifier.width(56.dp).height(44.dp).border(1.dp, Palette.Outline, RoundedCornerShape(8.dp))
                    .background(Color.Transparent).combinedClickable(onClick = seq.add),
                contentAlignment = Alignment.Center,
            ) { Text("+ Klip", style = MaterialTheme.typography.labelMedium, color = Palette.AccentText) }
        }
    }
}

internal fun fmtSpeed(s: Double) = if (s % 1.0 == 0.0) s.toInt().toString() else s.toString().replace('.', ',')

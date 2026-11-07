package com.mhmmtbg.x5reframe.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mhmmtbg.x5reframe.album.Album
import com.mhmmtbg.x5reframe.album.AlbumItem
import com.mhmmtbg.x5reframe.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Albüm kaynağı: kameranın SD kartı ya da bu uygulamadan dışa aktarılanlar. */
enum class AlbumSource(val label: String) { CAMERA("Kamera"), EXPORTS("Dışa aktarılanlar") }

/**
 * Tarihe göre gruplanmış 3 sütunlu ızgara. Uzun basınca çoklu seçim başlar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGrid(
    items: List<AlbumItem>,
    selected: Set<String>,
    onOpen: (AlbumItem) -> Unit,
    onToggle: (AlbumItem) -> Unit,
    selecting: Boolean,
    header: @Composable () -> Unit = {},
) {
    val groups = items.groupBy { dayKey(it.takenAt) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        groups.forEach { (day, list) ->
            item(key = "h$day", span = { GridItemSpan(maxLineSpan) }) {
                Text(dayLabel(list.first().takenAt), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 8.dp))
            }
            items(list, key = { it.uri.toString() }) { item ->
                val sel = item.uri.toString() in selected
                Tile(item, sel, selecting, Modifier.combinedClickable(
                    onClick = { if (selecting) onToggle(item) else onOpen(item) },
                    onLongClick = { onToggle(item) },
                ))
            }
        }
    }
}

@Composable
private fun Tile(item: AlbumItem, selected: Boolean, selecting: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(Album.cachedThumb(item), item.uri) {
        if (value == null) value = withContext(Dispatchers.IO) { Album.thumbnail(ctx, item) }
    }
    Box(modifier.aspectRatio(1f).background(Palette.SurfaceHigh)) {
        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (item.is360) {
            Text("360°", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        val dur = Album.duration(item)
        if (!item.isPhoto && dur != null) {
            Text(DateUtils.formatElapsedTime(dur / 1_000_000), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        if (selecting) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(if (selected) Palette.Accent else Color(0x66000000))
                    .border(1.5.dp, if (selected) Palette.Accent else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Glyphs.Check, null, Modifier.size(16.dp), tint = Palette.OnAccent) }
            if (selected) Box(Modifier.fillMaxSize().border(2.dp, Palette.Accent))
        }
    }
}

/** Kaynak seçici: iki parçalı düz anahtar. */
@Composable
fun SourceSwitch(current: AlbumSource, onChange: (AlbumSource) -> Unit) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).background(Palette.Surface, RoundedCornerShape(10.dp)).padding(3.dp)) {
        AlbumSource.values().forEach { s ->
            val on = s == current
            Text(
                s.label, color = if (on) Palette.OnAccent else Palette.Muted, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Palette.Accent else Palette.Surface)
                    .clickable { onChange(s) }.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** Seçim çubuğu: seçilenlerle düzenleyiciye geç. */
@Composable
fun SelectionBar(count: Int, onCancel: () -> Unit, onEdit: () -> Unit, onAuto: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Close, "Vazgeç", Modifier.size(22.dp), tint = Palette.Text)
        }
        Text("$count seçildi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
        Text(
            "Oto Düzenle", color = Palette.Text, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
                .clickable(enabled = count > 0, onClick = onAuto).padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Text(
            if (count > 1) "Birleştir" else "Düzenle", color = if (count > 0) Palette.OnAccent else Palette.Disabled, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (count > 0) Palette.Accent else Palette.SurfaceHigh)
                .clickable(enabled = count > 0, onClick = onEdit).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun AlbumEmpty(title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Text(action, color = Palette.OnAccent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Accent).clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

private fun dayKey(t: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

private val dayFmt = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("tr"))

private fun dayLabel(t: Long): String {
    val today = dayKey(System.currentTimeMillis())
    val k = dayKey(t)
    return when (k) {
        today -> "Bugün"
        dayKey(System.currentTimeMillis() - 86_400_000L) -> "Dün"
        else -> dayFmt.format(t)
    }
}

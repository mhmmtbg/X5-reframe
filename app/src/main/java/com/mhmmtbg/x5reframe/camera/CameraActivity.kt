package com.mhmmtbg.x5reframe.camera

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mhmmtbg.x5reframe.ui.Glyphs
import com.mhmmtbg.x5reframe.ui.theme.Palette
import com.mhmmtbg.x5reframe.ui.theme.X5Theme

/**
 * Kameraya kablosuz bağlanıp çekimleri görme, telefona aktarma ve kameradan silme.
 * Aktarılan dosyalar albüm klasörüne yazılır, Albüm'de hemen görünür.
 */
class CameraActivity : AppCompatActivity() {

    private enum class Phase { IDLE, CONNECTING, LISTING, READY }

    private var phase by mutableStateOf(Phase.IDLE)
    private var message by mutableStateOf<String?>(null)
    private var info by mutableStateOf<CamInfo?>(null)
    private var camState by mutableStateOf<CamState?>(null)
    private var files by mutableStateOf<List<CamFile>>(emptyList())
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private var listProgress by mutableStateOf("")
    /** Telefondaki (albüm klasöründeki) dosya adları */
    private var localNames by mutableStateOf<Map<String, Long>>(emptyMap())

    private fun refreshLocal() = Thread {
        val dir = CameraDownloads.target(this)
        val m = dir?.listFiles()?.associate { (it.name ?: "") to it.length() }.orEmpty()
        runOnUiThread { localNames = m }
    }.start()
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            prefs.edit().putString("folder", uri.toString()).apply()
            pendingDownload?.let { startDownload(it) }
        }
        pendingDownload = null
    }
    private var pendingDownload: List<CamFile>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { X5Theme { Screen() } }
        // Bağlantı sürüyorsa (ör. bildirimden dönüldü) doğrudan listele
        if (CameraLink.network != null) load() else Thread {
            val n = CameraLink.findExisting(this)
            if (n != null) runOnUiThread { load() }
        }.start()
    }

    private fun connect() {
        phase = Phase.CONNECTING
        message = null
        val prefix = prefs.getString("cam_ssid", "X5")!!
        val pass = prefs.getString("cam_pass", "88888888")!!
        CameraLink.connect(this, prefix, pass) { n, err ->
            runOnUiThread {
                if (n == null) { phase = Phase.IDLE; message = err } else load()
            }
        }
    }

    private fun load() {
        phase = Phase.LISTING
        listProgress = "Kameraya ulaşılıyor…"
        Thread {
            try {
                val c = CameraLink.client()
                val i = c.info()
                val st = runCatching { c.state() }.getOrNull()
                runOnUiThread { info = i; camState = st; listProgress = "Dosyalar okunuyor…" }
                val list = c.listFiles { got, total -> runOnUiThread { listProgress = "Dosyalar okunuyor… $got / $total" } }
                runOnUiThread { files = list; phase = Phase.READY }
                refreshLocal()
            } catch (e: Throwable) {
                runOnUiThread { phase = Phase.IDLE; message = "Kameraya ulaşılamadı: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    /** LRV'ler ayrı gösterilmez: her çekimin yanında indirilir (hızlı önizleme). */
    private fun shots(): List<CamFile> = files.filter { !it.isLrv }.sortedByDescending { it.date }
    private fun lrvFor(f: CamFile): CamFile? = files.firstOrNull { it.isLrv && it.shotKey == f.shotKey }

    private fun startDownload(list: List<CamFile>) {
        if (CameraDownloads.target(this) == null) {
            pendingDownload = list
            message = "Aktarılacak klasörü seç (Albüm'deki kamera klasörü ile aynı olabilir)."
            folderPicker.launch(null)
            return
        }
        val withLrv = list.flatMap { f -> listOfNotNull(f, lrvFor(f)) }
        CameraDownloads.enqueue(this, withLrv)
        selected = emptySet()
        message = "${list.size} çekim aktarılıyor"
    }

    private fun delete(list: List<CamFile>) {
        Thread {
            val urls = list.flatMap { f -> listOfNotNull(f, lrvFor(f)) }.map { it.url }
            val r = runCatching { CameraLink.client().delete(urls) }
            runOnUiThread {
                r.onSuccess { files = files.filter { it.url !in urls }; selected = emptySet(); message = "${list.size} çekim kameradan silindi" }
                    .onFailure { message = "Silinemedi: ${it.message}" }
            }
        }.start()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Screen() {
        var confirmDelete by remember { mutableStateOf<List<CamFile>?>(null) }
        BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
        Column(Modifier.fillMaxSize().background(Palette.Background)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 8.dp).height(52.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { if (selected.isNotEmpty()) selected = emptySet() else finish() },
                    contentAlignment = Alignment.Center) { Icon(if (selected.isNotEmpty()) Glyphs.Close else Glyphs.Back, "Geri", tint = Palette.Text) }
                Column(Modifier.weight(1f)) {
                    Text(if (selected.isNotEmpty()) "${selected.size} seçildi" else info?.model ?: "Kamera", style = MaterialTheme.typography.titleMedium)
                    camState?.let { s ->
                        Text(listOfNotNull(
                            if (s.battery >= 0) "Pil %${(s.battery * 100).toInt()}" else null,
                            when (s.card) { "pass" -> "SD kart hazır"; "noCard" -> "SD kart yok"; "noSpace" -> "SD kart dolu"; "" -> null; else -> "SD: ${s.card}" },
                            if (files.isNotEmpty()) "${shots().size} dosya" else null,
                        ).joinToString(" · "), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (phase == Phase.READY && selected.isEmpty()) Text("Yenile", color = Palette.Accent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { load() }.padding(8.dp))
            }
            message?.let {
                Text(it, color = Palette.Text, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(Palette.SurfaceHigh, RoundedCornerShape(8.dp)).clickable { message = null }.padding(10.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (phase) {
                    Phase.IDLE, Phase.CONNECTING -> ConnectHelp(phase == Phase.CONNECTING)
                    Phase.LISTING -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(listProgress, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.width(200.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
                    }
                    Phase.READY -> {
                        val list = shots()
                        if (list.isEmpty()) Text("Kamerada çekim yok.", color = Palette.Muted, modifier = Modifier.align(Alignment.Center))
                        else LazyVerticalGrid(
                            columns = GridCells.Fixed(3), contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(list, key = { it.url }) { f ->
                                val sel = f.url in selected
                                val dl = CameraDownloads.items.lastOrNull { it.file.url == f.url }
                                val have = localNames[f.name]?.let { it == f.size || f.size <= 0 } ?: false
                                Tile(f, sel, selected.isNotEmpty(), have, dl, Modifier.combinedClickable(
                                    onClick = {
                                        val k = f.url
                                        selected = if (selected.isNotEmpty()) { if (k in selected) selected - k else selected + k } else setOf(k)
                                    },
                                    onLongClick = { selected = selected + f.url },
                                ))
                            }
                        }
                    }
                }
            }
            Transfers()
            if (selected.isNotEmpty()) {
                val chosen = files.filter { it.url in selected }
                val bytes = chosen.sumOf { f -> f.size + (lrvFor(f)?.size ?: 0) }
                Row(Modifier.fillMaxWidth().background(Palette.Surface).navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${"%.1f".format(bytes / 1e9)} GB", color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("Kameradan sil", color = Palette.Error, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Palette.Outline, RoundedCornerShape(10.dp))
                            .clickable { confirmDelete = chosen }.padding(horizontal = 14.dp, vertical = 10.dp))
                    Text("Telefona aktar", color = Palette.OnAccent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Accent)
                            .clickable { startDownload(chosen) }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        confirmDelete?.let { list ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Kameradan silinsin mi?") },
                text = { Text("${list.size} çekim (önizleme dosyalarıyla) kameranın SD kartından kalıcı olarak silinir.") },
                confirmButton = { TextButton(onClick = { confirmDelete = null; delete(list) }) { Text("Sil", color = Palette.Error) } },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Vazgeç") } },
                containerColor = Palette.Surface, tonalElevation = 0.dp,
            )
        }
    }

    @Composable
    private fun ConnectHelp(connecting: Boolean) {
        var ssid by remember { mutableStateOf(prefs.getString("cam_ssid", "X5")!!) }
        var pass by remember { mutableStateOf(prefs.getString("cam_pass", "88888888")!!) }
        val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline,
            focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text, cursorColor = Palette.Accent,
            focusedLabelColor = Palette.Accent, unfocusedLabelColor = Palette.Muted)
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kameraya kablosuz bağlan", style = MaterialTheme.typography.titleMedium)
            listOf(
                "1. Kamerada ekranı aşağı kaydırıp Wi-Fi'yi aç (kamera kendi ağını kurar).",
                "2. Aşağıdan \"Bağlan\"a dokun; açılan pencerede kameranın ağını (adı \"X5 …\" ile başlar) seç.",
                "3. Bağlanınca çekimler listelenir. Seç ve \"Telefona aktar\": video ve hızlı önizleme dosyası albüm klasörüne iner.",
            ).forEach { Text(it, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium) }
            Text("Resmi uygulama kameraya bağlıysa önce onu kapat; kamera aynı anda tek bağlantı kabul eder. Parola, kamerada Ayarlar > Wi-Fi bölümünde yazar.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(ssid, { ssid = it; prefs.edit().putString("cam_ssid", it).apply() }, Modifier.fillMaxWidth(),
                label = { Text("Ağ adı başlangıcı") }, singleLine = true, colors = colors)
            OutlinedTextField(pass, { pass = it; prefs.edit().putString("cam_pass", it).apply() }, Modifier.fillMaxWidth(),
                X, singleLine = true, colors = colors)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (connecting) "Bağlanıyor…" else "Bağlan", color = Palette.OnAccent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Accent)
                        .clickable(enabled = !connecting) { connect() }.padding(horizontal = 20.dp, vertical = 11.dp))
                Text("Telefon zaten bağlı", color = Palette.Accent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(enabled = !connecting) {
                        Thread {
                            val n = CameraLink.findExisting(this@CameraActivity)
                            runOnUiThread { if (n != null) load() else message = "Bağlı Wi-Fi'de kamera bulunamadı. Wi-Fi ayarlarında kameranın ağına bağlı olduğundan emin ol." }
                        }.start()
                    }.padding(8.dp))
            }
        }
    }

    @Composable
    private fun Tile(f: CamFile, sel: Boolean, selecting: Boolean, have: Boolean, dl: CameraDownloads.Item?, modifier: Modifier) {
        val bmp = remember(f.url) { f.thumbnail?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
        Box(modifier.aspectRatio(1f).background(Palette.SurfaceHigh)) {
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (bmp == null) Text(f.name, color = Palette.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(6.dp))
            Text(if (f.isPhoto) "Foto" else "${"%.1f".format(f.size / 1e9)} GB", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            val badge = when {
                dl?.status == CameraDownloads.Status.RUNNING -> "%${if (f.size > 0) (dl.bytes * 100 / f.size) else 0}"
                dl?.status == CameraDownloads.Status.WAITING -> "Sırada"
                dl?.status == CameraDownloads.Status.FAILED -> "Hata"
                have -> "Telefonda"
                else -> null
            }
            badge?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            if (selecting) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(if (sel) Palette.Accent else Color(0x66000000)).border(1.5.dp, if (sel) Palette.Accent else Color.White, CircleShape),
                    contentAlignment = Alignment.Center) { if (sel) Icon(Glyphs.Check, null, Modifier.size(16.dp), tint = Palette.OnAccent) }
                if (sel) Box(Modifier.fillMaxSize().border(2.dp, Palette.Accent))
            }
        }
    }

    /** Süren aktarımlar. */
    @Composable
    private fun Transfers() {
        val items = CameraDownloads.items
        val doneCount = items.count { it.status == CameraDownloads.Status.DONE }
        androidx.compose.runtime.LaunchedEffect(doneCount) { if (doneCount > 0) refreshLocal() }
        if (items.isEmpty()) return
        val running = items.firstOrNull { it.status == CameraDownloads.Status.RUNNING }
        val waiting = items.count { it.status == CameraDownloads.Status.WAITING }
        val done = items.count { it.status == CameraDownloads.Status.DONE }
        val failed = items.filter { it.status == CameraDownloads.Status.FAILED }
        Column(Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (running != null) {
                val frac = if (running.file.size > 0) running.bytes.toFloat() / running.file.size else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${running.file.name} · %${(frac * 100).toInt()} · ${"%.1f".format(CameraDownloads.speedMBs)} MB/sn",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("İptal", color = Palette.Error, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { CameraDownloads.cancel() }.padding(start = 8.dp))
                }
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
            }
            Text(listOfNotNull(
                if (waiting > 0) "sırada $waiting" else null, if (done > 0) "$done tamam" else null,
                if (failed.isNotEmpty()) "${failed.size} hata: ${failed.first().error}" else null,
            ).joinToString(" · "), color = if (failed.isNotEmpty()) Palette.Error else Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

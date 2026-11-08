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

    // ---- Önizleme: yalnızca küçük LRV ve .insv'nin sonundaki kalibrasyon verisi geçici olarak alınır ----

    /** Önizleme hazırlanıyor: (dosya adı, ilerleme 0..1) */
    private var previewing by mutableStateOf<Pair<String, Float>?>(null)
    @Volatile private var previewCancel = false
    private var previewFile: CamFile? = null

    private val previewResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val f = previewFile
        if (r.resultCode == RESULT_OK && f != null &&
            r.data?.getBooleanExtra(com.mhmmtbg.x5reframe.EditorActivity.RESULT_DOWNLOAD, false) == true) startDownload(listOf(f))
    }

    private fun previewDir() = java.io.File(cacheDir, "camera-preview").apply { mkdirs() }

    private fun preview(f: CamFile) {
        if (previewing != null) return
        if (f.isPhoto) { message = "Fotoğrafı görmek için telefona aktar (fotoğraflar küçüktür)"; return }
        val lrv = lrvFor(f) ?: run { message = "Bu çekimin önizleme dosyası (LRV) kamerada yok; görmek için telefona aktar."; return }
        previewCancel = false
        previewing = f.name to 0f
        Thread {
            try {
                val dir = previewDir()
                // Yalnızca son önizleme saklanır
                dir.listFiles()?.filter { !it.name.startsWith(f.shotKey) }?.forEach { it.delete() }
                val c = CameraLink.client()
                val meta = java.io.File(dir, f.shotKey + ".meta")
                if (!meta.exists() || meta.length() == 0L) meta.writeBytes(c.fetchTrailer(f))
                val out = java.io.File(dir, f.shotKey + ".lrv")
                if (out.length() != lrv.size) {
                    val part = java.io.File(dir, f.shotKey + ".lrv.part")
                    c.download(lrv.url, part.length(), { append -> java.io.FileOutputStream(part, append) }, { previewCancel }) { b ->
                        runOnUiThread { previewing = f.name to (if (lrv.size > 0) b.toFloat() / lrv.size else 0f) }
                    }
                    part.renameTo(out)
                }
                runOnUiThread {
                    previewing = null
                    previewFile = f
                    previewResult.launch(Intent(this, com.mhmmtbg.x5reframe.EditorActivity::class.java)
                        .setData(android.net.Uri.fromFile(out))
                        .putExtra(com.mhmmtbg.x5reframe.EditorActivity.EXTRA_VIEWER, true)
                        .putExtra(com.mhmmtbg.x5reframe.EditorActivity.EXTRA_PREVIEW_META, android.net.Uri.fromFile(meta).toString()))
                }
            } catch (e: InterruptedException) {
                runOnUiThread { previewing = null }
            } catch (e: Throwable) {
                runOnUiThread { previewing = null; message = "Önizleme açılamadı: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbs?.shutdown()
        // Geçici önizleme dosyalarını temizle (telefonda yer kaplamasın)
        if (isFinishing) previewDir().deleteRecursively()
    }

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

    private var thumbs: CamThumbs? = null
    private var listSource by mutableStateOf("")
    private var byDay by mutableStateOf(false)

    private fun load() {
        phase = Phase.LISTING
        listProgress = "Kameraya ulaşılıyor…"
        Thread {
            try {
                val c = CameraLink.client()
                val i = runCatching { c.info() }.getOrNull()
                val st = runCatching { c.state() }.getOrNull()
                runOnUiThread { info = i; camState = st; listProgress = "Dosyalar okunuyor…" }
                val r = CameraLister.listAll(c, { InstaClient(network = CameraLink.network).open() }, "http://192.168.42.1:80", CameraLink.network) { p ->
                    runOnUiThread { listProgress = p }
                }
                if (r.files.isEmpty() && i == null) throw java.io.IOException("kamera yanıt vermiyor")
                runOnUiThread {
                    thumbs?.shutdown()
                    thumbs = CamThumbs(this, CameraLink.network)
                    files = r.files; listSource = r.source; phase = Phase.READY
                }
                refreshLocal()
            } catch (e: Throwable) {
                runOnUiThread { phase = Phase.IDLE; message = "Kameraya ulaşılamadı: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    /** LRV'ler ayrı gösterilmez: her çekimin yanında indirilir (hızlı önizleme). */
    private fun allShots(): List<CamFile> = files.filter { !it.isLrv }.sortedByDescending { it.date }
    /** Üstteki sekme: 0 tümü, 1 video, 2 fotoğraf. */
    private var kindTab by mutableStateOf(0)
    private fun shots(): List<CamFile> = allShots().filter { f -> when (kindTab) { 1 -> f.isVideo; 2 -> f.isPhoto; else -> true } }
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
                    Text(if (selected.isNotEmpty()) "${selected.size} seçildi" else (info?.model ?: "Insta360").replace("Insta360 ", ""), style = MaterialTheme.typography.headlineSmall)
                    camState?.let { s ->
                        Text(listOfNotNull(
                            if (s.battery >= 0) "Pil %${(s.battery * 100).toInt()}" else null,
                            when (s.card) { "pass" -> "SD kart hazır"; "noCard" -> "SD kart yok"; "noSpace" -> "SD kart dolu"; "" -> null; else -> "SD: ${s.card}" },
                            if (files.isNotEmpty()) "${allShots().size} medya" else null,
                        ).joinToString(" · "), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (phase == Phase.READY && selected.isEmpty()) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { byDay = !byDay }, contentAlignment = Alignment.Center) {
                        Icon(if (byDay) Glyphs.Album else Glyphs.Projects, if (byDay) "Izgara" else "Güne göre", tint = Palette.Text)
                    }
                    Text("Yenile", color = Palette.AccentText, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { load() }.padding(8.dp))
                }
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
                        if (allShots().isEmpty()) Text("Kamerada çekim yok.", color = Palette.Muted, modifier = Modifier.align(Alignment.Center))
                        else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Telefonda 3, tablette daha çok sütun
                        val cols = (maxWidth.value / 130f).toInt().coerceIn(3, 8)
                        val groups = if (byDay) list.groupBy { it.date.take(10) } else mapOf("" to list)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(cols), contentPadding = PaddingValues(0.dp, 0.dp, 0.dp, 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    // Insta360 albümündeki gibi tür sekmeleri
                                    val all = allShots()
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        listOf("Tümü" to all.size, "Video" to all.count { it.isVideo }, "Fotoğraf" to all.count { it.isPhoto }).forEachIndexed { k, (label, n) ->
                                            Column(Modifier.clickable { kindTab = k }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("$label $n", style = MaterialTheme.typography.titleSmall, color = if (kindTab == k) Palette.Text else Palette.Muted)
                                                Box(Modifier.padding(top = 4.dp).width(28.dp).height(3.dp).background(if (kindTab == k) Palette.Accent else Color.Transparent, RoundedCornerShape(2.dp)))
                                            }
                                        }
                                    }
                                    Text("Dokun: önizle (indirmeden) · Uzun bas: seç ve telefona aktar", color = Palette.Muted,
                                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                            }
                            groups.forEach { (day, dayList) ->
                            if (day.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "d$day") {
                                Text(dayLabel(day) + " · ${dayList.size}", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
                            }
                            items(dayList, key = { it.url }) { f ->
                                val sel = f.url in selected
                                val dl = CameraDownloads.items.lastOrNull { it.file.url == f.url }
                                val have = localNames[f.name]?.let { it == f.size || f.size <= 0 } ?: false
                                Tile(f, sel, selected.isNotEmpty(), have, dl, Modifier.combinedClickable(
                                    onClick = {
                                        val k = f.url
                                        if (selected.isNotEmpty()) selected = if (k in selected) selected - k else selected + k
                                        else preview(f)
                                    },
                                    onLongClick = { selected = selected + f.url },
                                ))
                            }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("${list.size} medya" + when (listSource) { "osc" -> " · kameranın tam listesi alınamadı (yalnız OSC)"; "dizin" -> " · SD kart klasörlerinden"; else -> "" },
                                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(18.dp))
                            }
                        }
                        }
                    }
                }
            }
            previewing?.let { (name, p) ->
                Column(Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Önizleme hazırlanıyor · $name · %${(p * 100).toInt()}", style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("Vazgeç", color = Palette.Error, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable { previewCancel = true }.padding(start = 8.dp))
                    }
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
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
                "3. Bağlanınca çekimler listelenir. Hiçbir şey kendiliğinden inmez: dokununca önizlenir (telefona kaydedilmez), uzun basıp seçtiklerini \"Telefona aktar\" ile indirirsin.",
            ).forEach { Text(it, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium) }
            Text("Resmi uygulama kameraya bağlıysa önce onu kapat; kamera aynı anda tek bağlantı kabul eder. Parola, kamerada Ayarlar > Wi-Fi bölümünde yazar.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(ssid, { ssid = it; prefs.edit().putString("cam_ssid", it).apply() }, Modifier.fillMaxWidth(),
                label = { Text("Ağ adı başlangıcı") }, singleLine = true, colors = colors)
            OutlinedTextField(pass, { pass = it; prefs.edit().putString("cam_pass", it).apply() }, Modifier.fillMaxWidth(),
                label = { Text("Wi-Fi parolası") }, singleLine = true, colors = colors)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (connecting) "Bağlanıyor…" else "Bağlan", color = Palette.OnAccent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Accent)
                        .clickable(enabled = !connecting) { connect() }.padding(horizontal = 20.dp, vertical = 11.dp))
                Text("Telefon zaten bağlı", color = Palette.AccentText, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(enabled = !connecting) {
                        Thread {
                            val n = CameraLink.findExisting(this@CameraActivity)
                            runOnUiThread { if (n != null) load() else message = "Bağlı Wi-Fi'de kamera bulunamadı. Wi-Fi ayarlarında kameranın ağına bağlı olduğundan emin ol." }
                        }.start()
                    }.padding(8.dp))
            }
        }
    }

    private fun dayLabel(d: String): String {
        val p = d.split(':')
        if (p.size < 3) return d
        val months = listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")
        return "${p[2].toIntOrNull() ?: p[2]} ${months.getOrElse((p[1].toIntOrNull() ?: 1) - 1) { p[1] }} ${p[0]}"
    }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60) }

    @Composable
    private fun Tile(f: CamFile, sel: Boolean, selecting: Boolean, have: Boolean, dl: CameraDownloads.Item?, modifier: Modifier) {
        val th = thumbs
        androidx.compose.runtime.LaunchedEffect(f.url, th) { th?.request(f, lrvFor(f)) }
        val info = th?.get(f)
        val oscBmp = remember(f.url) { f.thumbnail?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
        val bmp = info?.bitmap?.asImageBitmap() ?: oscBmp
        val is360 = f.name.endsWith(".insv", true) || f.name.endsWith(".insp", true)
        Box(modifier.aspectRatio(1f).background(Palette.SurfaceHigh)) {
            if (bmp != null) Image(bmp, f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (info == null) LinearProgressIndicator(Modifier.align(Alignment.Center).width(40.dp), color = Palette.Muted, trackColor = Palette.SurfaceHigh)
            else Text(f.name, color = Palette.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(6.dp))
            // Alt şerit: tür simgesi, 360 rozeti, süre
            Row(Modifier.align(Alignment.BottomEnd).fillMaxWidth().background(Color(0x73000000)).padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (f.isVideo) Icon(Glyphs.Video, null, Modifier.size(14.dp), tint = Color.White)
                if (is360) Text("360", color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp).border(1.dp, Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
                Spacer(Modifier.width(4.dp))
                Text(when {
                    f.isPhoto -> "Foto"
                    info != null && info.durationMs > 0 -> fmtDur(info.durationMs)
                    else -> "${"%.1f".format(f.size / 1e9)} GB"
                }, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
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
            if (lrvFor(f) == null && f.isVideo && info != null)
                Text("LRV yok", color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
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

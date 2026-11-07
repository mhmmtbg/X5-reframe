package com.mhmmtbg.x5reframe

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mhmmtbg.x5reframe.project.Project
import com.mhmmtbg.x5reframe.project.ProjectStore
import com.mhmmtbg.x5reframe.ui.Glyphs
import com.mhmmtbg.x5reframe.ui.theme.Palette
import com.mhmmtbg.x5reframe.ui.theme.X5Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ana ekran: projeler ve medya. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ProjectStore
    private var projects by mutableStateOf<List<Project>>(emptyList())
    private var folder by mutableStateOf<Uri?>(null)
    private var media by mutableStateOf<List<com.mhmmtbg.x5reframe.album.AlbumItem>>(emptyList())
    private var exports by mutableStateOf<List<com.mhmmtbg.x5reframe.album.AlbumItem>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var source by mutableStateOf(com.mhmmtbg.x5reframe.ui.AlbumSource.CAMERA)
    private var selected by mutableStateOf<Set<String>>(emptySet())

    /** Yeni proje (dosyalardan): .insv ile birlikte LRV de seçilirse önizleme hızlı olur; her video ve fotoğraf açılır. */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importMedia(uris) }

    /** Yeni proje (galeriden): sistem fotoğraf seçicisi, video + fotoğraf, çoklu seçim. */
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris -> importMedia(uris) }

    private var chooseSource by mutableStateOf(false)

    /** Seçilen dosyalardan proje: ilk dosya açılır, kalanlar seçim sırasıyla arkasına eklenir. */
    private fun importMedia(uris: List<Uri>?) {
        if (uris.isNullOrEmpty()) return
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        val named = uris.map { it to nameOf(it) }
        val clips = named.filterNot { it.second.endsWith(".lrv", true) }
        val first = clips.firstOrNull() ?: return
        val lrv = com.mhmmtbg.x5reframe.project.LrvFinder.pair(listOf(first.first) + named.filter { it.second.endsWith(".lrv", true) }.map { it.first }) { u -> nameOf(u) }?.second
        startActivity(
            Intent(this, EditorActivity::class.java).setData(first.first).putExtra(EditorActivity.EXTRA_LRV, lrv?.toString())
                .putExtra(EditorActivity.EXTRA_MORE, clips.drop(1).map { it.first.toString() }.toTypedArray())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun nameOf(u: Uri): String = runCatching {
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: (u.lastPathSegment ?: "")

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("folder", uri.toString()).apply()
            folder = uri
            loadMedia()
        }
    }

    private fun openNew(uri: Uri, lrv: Uri? = null) {
        startActivity(
            Intent(this, EditorActivity::class.java).setData(uri).putExtra(EditorActivity.EXTRA_LRV, lrv?.toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun openProject(p: Project) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROJECT, p.id))
    }

    private fun refresh() { projects = store.list() }

    private fun loadMedia() {
        val f = folder
        scanning = true
        Thread {
            val list = f?.let { runCatching { com.mhmmtbg.x5reframe.album.Album.scanCamera(this, it) }.getOrDefault(emptyList()) }.orEmpty()
            val ex = com.mhmmtbg.x5reframe.album.Album.scanExports(this)
            runOnUiThread { media = list; exports = ex; scanning = false }
        }.start()
    }

    private fun openItem(item: com.mhmmtbg.x5reframe.album.AlbumItem) {
        when {
            !item.is360 -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            item.isPhoto -> startActivity(
                Intent(this, com.mhmmtbg.x5reframe.photo.PhotoActivity::class.java).setData(item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            else -> startActivity(
                Intent(this, EditorActivity::class.java).setData(item.uri).putExtra(EditorActivity.EXTRA_LRV, item.lrv?.toString())
                    .putExtra(EditorActivity.EXTRA_VIEWER, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    /** Seçilen çekimleri çekim sırasıyla tek projede birleştir. */
    private fun editSelected() = openSelected(auto = false)

    private fun openSelected(auto: Boolean) {
        val chosen = media.filter { it.uri.toString() in selected && !it.isPhoto }.sortedBy { it.takenAt }
        selected = emptySet()
        val first = chosen.firstOrNull() ?: return
        startActivity(
            Intent(this, EditorActivity::class.java).setData(first.uri).putExtra(EditorActivity.EXTRA_LRV, first.lrv?.toString())
                .putExtra(EditorActivity.EXTRA_MORE, chosen.drop(1).map { it.uri.toString() }.toTypedArray())
                .putExtra(EditorActivity.EXTRA_AUTO, auto)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        folder = getSharedPreferences("prefs", MODE_PRIVATE).getString("folder", null)?.let(Uri::parse)
        setContent { X5Theme { Home() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        loadMedia()
    }

    @Composable
    private fun Home() {
        // Alt gezinme: Ana sayfa (yeni proje + projeler), 360° albüm, ayarlar.
        var tab by remember { mutableStateOf(intent.getIntExtra(EXTRA_TAB, prefs.getInt(KEY_TAB, 0))) }
        var about by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(Palette.Background)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    when (tab) {
                        0 -> HomeTab(onAlbum = { tab = 1; prefs.edit().putInt(KEY_TAB, 1).apply() })
                        1 -> {
                            Header("360° Albüm") {
                                HeaderAction(Glyphs.Wifi, "Kameraya bağlan") {
                                    startActivity(Intent(this@MainActivity, com.mhmmtbg.x5reframe.camera.CameraActivity::class.java))
                                }
                                HeaderAction(Glyphs.Folder, "Klasör") { folderPicker.launch(null) }
                            }
                            ExportBanner(); MediaTab()
                        }
                        else -> { Header("Ayarlar") {}; SettingsTab { about = true } }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
            Row(Modifier.fillMaxWidth().background(Palette.Background).navigationBarsPadding().height(60.dp)) {
                listOf(Triple("Ana sayfa", Glyphs.Home, 0), Triple("360° Albüm", Glyphs.Sphere, 1), Triple("Ayarlar", Glyphs.Settings, 2)).forEach { (label, icon, i) ->
                    val sel = tab == i
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable { tab = i; prefs.edit().putInt(KEY_TAB, i).apply() },
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(icon, label, Modifier.size(24.dp), tint = if (sel) Palette.AccentText else Palette.Muted)
                        Text(label, color = if (sel) Palette.Text else Palette.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (about) AboutDialog { about = false }
        if (chooseSource) NewProjectDialog()
    }

    /** Yeni proje: kaynak seçimi. */
    @Composable
    private fun NewProjectDialog() {
        AlertDialog(
            onDismissRequest = { chooseSource = false },
            title = { Text("Yeni proje") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceOption(Glyphs.Album, "Galeri", "Telefondaki videolar ve fotoğraflar") {
                        chooseSource = false
                        galleryPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    SourceOption(Glyphs.Folder, "Dosyalar", "360° .insv klipleri (LRV ile), MKV, WebM ve diğer dosyalar") {
                        chooseSource = false
                        picker.launch(arrayOf("video/*", "image/*", "application/octet-stream", "*/*"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseSource = false }) { Text("Vazgeç", color = Palette.Muted) } },
            containerColor = Palette.Surface, tonalElevation = 0.dp,
        )
    }

    @Composable
    private fun SourceOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).clickable(onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = Palette.Text)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    @Composable
    private fun Header(title: String, actions: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            actions()
        }
    }

    @Composable
    private fun HeaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(24.dp), tint = Palette.Text)
        }
    }

    @Composable
    private fun SettingsTab(onAbout: () -> Unit) {
        var dyn by remember { mutableStateOf(prefs.getBoolean(KEY_DYNAMIC_STITCH, true)) }
        var lrv by remember { mutableStateOf(prefs.getBoolean(KEY_PREFER_LRV, true)) }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            item { SectionTitle("Görünüm") }
            item {
                var theme by remember { mutableStateOf(com.mhmmtbg.x5reframe.ui.theme.ThemeMode.load(this@MainActivity)) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Tema", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().background(Palette.SurfaceHigh, RoundedCornerShape(10.dp)).padding(3.dp)) {
                        com.mhmmtbg.x5reframe.ui.theme.ThemeMode.values().forEach { m ->
                            val on = m == theme
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Palette.Accent else Palette.SurfaceHigh)
                                    .clickable {
                                        if (m != theme) { theme = m; com.mhmmtbg.x5reframe.ui.theme.ThemeMode.set(this@MainActivity, m) }
                                    }.padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(m.label, color = if (on) Palette.OnAccent else Palette.Text, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                    Text("Koyu tema saf siyahtır (AMOLED ekranda pil dostu). Otomatik, telefonun ayarını izler.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item { SectionTitle("Yapay zekâ") }
            item { AiSettingsBlock() }
            item { SectionTitle("Medya") }
            item {
                SettingRow("Kamera klasörü", folder?.let { DocumentFile.fromTreeUri(this@MainActivity, it)?.name } ?: "Seçilmedi") { folderPicker.launch(null) }
            }
            item {
                SwitchRow("Hızlı önizleme (LRV)", "Varsa düşük çözünürlüklü kopya ile oynat; dışa aktarım her zaman tam kalite.", lrv) {
                    lrv = it; prefs.edit().putBoolean(KEY_PREFER_LRV, it).apply()
                }
            }
            item { SectionTitle("Depolama") }
            item {
                var size by remember { mutableStateOf(-1L) }
                LaunchedEffect(Unit) { size = withContext(Dispatchers.IO) { cleanableBytes() } }
                SettingRow("Önbelleği temizle", if (size < 0) "Hesaplanıyor…" else "Geçici dosyalar ve kullanılmayan kayıtlar: ${"%.1f".format(size / 1048576.0)} MB") {
                    Thread { val freed = cleanCache(); runOnUiThread { size = 0; android.widget.Toast.makeText(this@MainActivity, "${"%.1f".format(freed / 1048576.0)} MB boşaltıldı", android.widget.Toast.LENGTH_SHORT).show() } }.start()
                }
            }
            item { SectionTitle("Dikiş") }
            item {
                SwitchRow("Dinamik dikiş", "Dikiş çizgisini her bölgede yakındaki nesnelere göre hizalar ve iki lensin rengini eşitler.", dyn) {
                    dyn = it; prefs.edit().putBoolean(KEY_DYNAMIC_STITCH, it).apply()
                }
            }
            item { SectionTitle("Uygulama") }
            item { SettingRow("Dışa aktarılanlar", "Filmler / X5Reframe") {} }
            item { SettingRow("Son sürümü indir", "Sürüm $version") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL))) }
            } }
            item { SettingRow("Hakkında", "Lisanslar ve teşekkürler", onAbout) }
        }
    }

    /** Sağlayıcı seçimi, API anahtarı (şifreli saklanır), model ve bağlantı testi. */
    @Composable
    private fun AiSettingsBlock() {
        val ctx = this@MainActivity
        var provider by remember { mutableStateOf(com.mhmmtbg.x5reframe.ai.AiSettings.provider(ctx)) }
        var keyInput by remember(provider) { mutableStateOf("") }
        var hasKey by remember(provider) { mutableStateOf(com.mhmmtbg.x5reframe.ai.AiSettings.hasKey(ctx, provider)) }
        var model by remember(provider) { mutableStateOf(com.mhmmtbg.x5reframe.ai.AiSettings.model(ctx, provider)) }
        var review by remember { mutableStateOf(com.mhmmtbg.x5reframe.ai.AiSettings.review(ctx)) }
        var status by remember(provider) { mutableStateOf<Pair<String, Boolean>?>(null) }
        var testing by remember { mutableStateOf(false) }
        val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline,
            focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text, cursorColor = Palette.Accent,
            focusedLabelColor = Palette.Accent, unfocusedLabelColor = Palette.Muted,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Düzenleyicideki \"Yapay zekâ\" aracı isteğini seçtiğin modele gönderir; model kurguyu planlar, uygulama uygular. Anahtar yalnızca bu telefonda, şifreli saklanır.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().background(Palette.SurfaceHigh, RoundedCornerShape(10.dp)).padding(3.dp)) {
                com.mhmmtbg.x5reframe.ai.AiProvider.values().forEach { p ->
                    val on = p == provider
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Palette.Accent else Palette.SurfaceHigh)
                            .clickable { provider = p; com.mhmmtbg.x5reframe.ai.AiSettings.setProvider(ctx, p) }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(p.label, color = if (on) Palette.OnAccent else Palette.Text, style = MaterialTheme.typography.labelLarge) }
                }
            }
            androidx.compose.material3.OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (hasKey) "API anahtarı kayıtlı · değiştirmek için yaz" else "${provider.label} API anahtarı") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), colors = fieldColors,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Kaydet", color = if (keyInput.isNotBlank()) Palette.OnAccent else Palette.Disabled, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (keyInput.isNotBlank()) Palette.Accent else Palette.SurfaceHigh)
                        .clickable(enabled = keyInput.isNotBlank()) {
                            com.mhmmtbg.x5reframe.ai.AiSettings.setKey(ctx, provider, keyInput); keyInput = ""; hasKey = true
                            status = "Anahtar kaydedildi" to true
                        }.padding(horizontal = 14.dp, vertical = 8.dp))
                if (hasKey) Text("Sil", color = Palette.Error, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { com.mhmmtbg.x5reframe.ai.AiSettings.setKey(ctx, provider, null); hasKey = false; status = "Anahtar silindi" to true }
                        .padding(horizontal = 8.dp, vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                Text("Anahtar al", color = Palette.AccentText, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl))) } }.padding(8.dp))
            }
            androidx.compose.material3.OutlinedTextField(
                value = model, onValueChange = { model = it; com.mhmmtbg.x5reframe.ai.AiSettings.setModel(ctx, provider, it) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Model") }, colors = fieldColors,
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                provider.models.forEach { m ->
                    val on = m == model
                    Text(m, color = if (on) Palette.OnAccent else Palette.Text, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Palette.Accent else Palette.SurfaceHigh)
                            .clickable { model = m; com.mhmmtbg.x5reframe.ai.AiSettings.setModel(ctx, provider, m) }
                            .padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            Text("İlk öneri hızlı ve uygun fiyatlıdır; diğerleri daha güçlü ya da daha ucuzdur. Model adı sağlayıcı yeni sürüm çıkardığında elle değiştirilebilir.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (testing) "Deneniyor…" else "Bağlantıyı test et", color = Palette.Text, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Outline, RoundedCornerShape(8.dp))
                        .clickable(enabled = hasKey && !testing) {
                            testing = true; status = null
                            val p = provider; val m = model
                            Thread {
                                val r = runCatching {
                                    com.mhmmtbg.x5reframe.ai.AiClient.test(p, com.mhmmtbg.x5reframe.ai.AiSettings.key(ctx, p) ?: error("anahtar okunamadı"), m)
                                }
                                runOnUiThread {
                                    testing = false
                                    status = r.fold({ "Çalışıyor: $it" to true }, { "Hata: ${it.message}" to false })
                                }
                            }.start()
                        }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            status?.let { (t, ok) -> Text(t, color = if (ok) Palette.Muted else Palette.Error, style = MaterialTheme.typography.bodySmall) }
        }
        SwitchRow("Sonucu görerek düzelt", "Model, uyguladığı kurgudan kareleri görüp bir kez düzeltir. Daha iyi sonuç; iki kat istek.", review) {
            review = it; com.mhmmtbg.x5reframe.ai.AiSettings.setReview(ctx, it)
        }
    }

    @Composable
    private fun SectionTitle(t: String) {
        Text(t, color = Palette.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp))
    }

    @Composable
    private fun SettingRow(title: String, sub: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(sub, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Glyphs.Chevron, null, Modifier.size(20.dp), tint = Palette.Muted)
        }
    }

    @Composable
    private fun SwitchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(sub, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.Switch(value, onChange, colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent, checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.SurfaceHigh, uncheckedBorderColor = Palette.Muted,
            ))
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        /** Seçili alt sekme (v3.1 düzeni: 0 ana sayfa, 1 albüm, 2 ayarlar). */
        const val KEY_TAB = "tab31"
        const val KEY_DYNAMIC_STITCH = "dynamic_stitch"
        const val KEY_PREFER_LRV = "prefer_lrv"
        const val UPDATE_URL = "https://github.com/mhmmtbg/X5-reframe/raw/builds/x5-reframe.apk"
    }

    /** Arka planda süren ya da yeni biten dışa aktarım. */
    @Composable
    private fun ExportBanner() {
        val st by com.mhmmtbg.x5reframe.export.ExportManager.state.collectAsState()
        when (val s = st) {
            is com.mhmmtbg.x5reframe.export.ExportManager.Status.Running -> Column(
                Modifier.fillMaxWidth().padding(16.dp).background(Palette.Surface, RoundedCornerShape(12.dp)).padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dışa aktarılıyor: ${s.request.title}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { com.mhmmtbg.x5reframe.export.ExportManager.cancelCurrent() }) { Text("İptal", color = Palette.Error) }
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { s.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Palette.Accent, trackColor = Palette.SurfaceHigh,
                )
                Text("%${(s.fraction * 100).toInt()}" + (if (s.speed > 0) " · ${"%.1f".format(s.speed)}× hız" else "") +
                    (if (s.queued > 0) " · sırada ${s.queued}" else ""), color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp))
            }
            is com.mhmmtbg.x5reframe.export.ExportManager.Status.Done -> Row(
                Modifier.fillMaxWidth().padding(16.dp).background(Palette.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓ Hazır: ${s.request.title}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                }) { Text("Aç", color = Palette.AccentText) }
            }
            else -> {}
        }
    }

    @Composable
    private fun AboutDialog(onClose: () -> Unit) {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("X5 Reframe $version") },
            text = {
                Text(
                    "Telefonda internetsiz video düzenleyici: Insta360 X5 360° klipleri ve her türlü video ile fotoğrafı kadrajlar, düzenler ve hızlıca dışa aktarır.\n\n" +
                        "Insta360 dosya yapısı ve lens modeli için telemetry-parser (AdrianEddy, MIT/Apache-2.0) belgelerinden yararlanıldı. " +
                        "Yüz ve nesne algılama: Google ML Kit (cihaz içi). Simgeler: Material Icons (Apache-2.0).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Tamam", color = Palette.AccentText) } },
            containerColor = Palette.Surface, tonalElevation = 0.dp,
        )
    }

    /** Ana sayfa: yeni proje (galeri / dosyalar / kamera), ipucu, projeler. */
    @Composable
    private fun HomeTab(onAlbum: () -> Unit) {
        var tipHidden by remember { mutableStateOf(prefs.getInt("tip_hidden_day", -1) == dayIndex()) }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Header("X5 Reframe") {} }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Birincil eylem: galeriden yeni proje
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Accent)
                            .clickable { galleryPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Palette.OnAccent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(Glyphs.Add, null, Modifier.size(26.dp), tint = Palette.OnAccent)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Yeni proje", style = MaterialTheme.typography.titleLarge, color = Palette.OnAccent)
                            Text("Galeriden video ve fotoğraf seç", style = MaterialTheme.typography.bodyMedium, color = Palette.OnAccent.copy(alpha = 0.85f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction(Glyphs.Folder, "Dosyalar", "360° .insv, MKV, WebM", Modifier.weight(1f)) {
                            picker.launch(arrayOf("video/*", "image/*", "application/octet-stream", "*/*"))
                        }
                        QuickAction(Glyphs.Wifi, "Kamera", "Kablosuz bağlan", Modifier.weight(1f)) {
                            startActivity(Intent(this@MainActivity, com.mhmmtbg.x5reframe.camera.CameraActivity::class.java))
                        }
                        QuickAction(Glyphs.Sphere, "360° albüm", "SD kart klasörü", Modifier.weight(1f), onAlbum)
                    }
                }
            }
            item { ExportBanner() }
            val lastId = prefs.getString("last_project", null)
            projects.firstOrNull { it.id == lastId }?.let { last ->
                item {
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface)
                            .border(1.dp, Palette.Outline, RoundedCornerShape(14.dp)).clickable { openProject(last) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Glyphs.Play, null, Modifier.size(22.dp), tint = Palette.AccentText)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Kaldığın yerden devam et", style = MaterialTheme.typography.titleSmall)
                            Text(last.name, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (!tipHidden) item {
                TipCard(TIPS[dayIndex() % TIPS.size]) { prefs.edit().putInt("tip_hidden_day", dayIndex()).apply(); tipHidden = true }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Projeler", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (projects.isNotEmpty()) Text("${projects.size}", color = Palette.Muted, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (projects.isEmpty()) item {
                Text(
                    "Henüz proje yok. Yeni proje ile galeriden video ve fotoğraf ya da Dosyalar ile 360° .insv klibi seç; .insv ile aynı adlı LRV dosyasını da seçersen önizleme çok daha akıcı olur.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(projects, key = { it.id }) { p -> Box(Modifier.padding(horizontal = 16.dp)) { ProjectCard(p) } }
        }
    }

    /** Hiçbir projenin kullanmadığı dondurulmuş kare / seslendirme dosyaları ve önbellek. */
    private fun orphanFiles(): List<java.io.File> {
        val refs = store.list().joinToString("\n") { ProjectStore.toJson(it).toString() }
        return listOf("freeze", "voice").flatMap { d -> java.io.File(filesDir, d).listFiles().orEmpty().toList() }
            .filter { f -> !refs.contains(f.name) }
    }

    private fun dirSize(f: java.io.File): Long = if (f.isDirectory) f.listFiles().orEmpty().sumOf { dirSize(it) } else f.length()

    private fun cleanableBytes(): Long = dirSize(cacheDir) + orphanFiles().sumOf { it.length() }

    private fun cleanCache(): Long {
        val before = cleanableBytes()
        cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        orphanFiles().forEach { it.delete() }
        return before - cleanableBytes()
    }

    private fun dayIndex() = (System.currentTimeMillis() / 86_400_000L).toInt()

    @Composable
    private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Surface).border(1.dp, Palette.Outline, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = Palette.Text)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun TipCard(tip: String, onClose: () -> Unit) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Lightbulb, null, Modifier.size(20.dp), tint = Palette.AccentText)
            Spacer(Modifier.width(12.dp))
            Text(tip, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Glyphs.Close, "İpucunu gizle", Modifier.size(18.dp), tint = Palette.Muted)
            }
        }
    }

    @Composable
    private fun ProjectCard(p: Project) {
        var menu by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }
        val thumb by produceState<Bitmap?>(null, p.id, p.updatedAt) {
            value = withContext(Dispatchers.IO) { store.thumbnail(p.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } }
        }
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(12.dp)).clickable { openProject(p) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh)) {
                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    run {
                        val n360 = p.segments.count { it.clipName.endsWith(".insv", true) }
                        val kind = when (n360) { p.segments.size -> "360°"; 0 -> "Video"; else -> "360° + video" }
                        "$kind · ${"%.1f".format(p.totalDurationUs / 1e6)} sn · ${p.segments.size} parça"
                    },
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                Text(DateUtils.getRelativeTimeSpanString(p.updatedAt).toString(), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", color = Palette.Muted, style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Çoğalt") }, onClick = { menu = false; store.duplicate(p.id); refresh() })
                    DropdownMenuItem(text = { Text("Sil", color = Palette.Error) }, onClick = { menu = false; deleting = true })
                }
            }
        }
        if (renaming) {
            var name by remember { mutableStateOf(p.name) }
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text("Yeniden adlandır") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { store.rename(p.id, name.trim().ifEmpty { p.name }); renaming = false; refresh() }) { Text("Kaydet") } },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text("Vazgeç") } },
                containerColor = Palette.Surface, tonalElevation = 0.dp,
            )
        }
        if (deleting) {
            AlertDialog(
                onDismissRequest = { deleting = false },
                title = { Text("Proje silinsin mi?") },
                text = { Text("\"${p.name}\" projesi silinir. Kameradaki orijinal klip ve dışa aktardığın videolar silinmez.") },
                confirmButton = { TextButton(onClick = { store.delete(p.id); deleting = false; refresh() }) { Text("Sil", color = Palette.Error) } },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text("Vazgeç") } },
                containerColor = Palette.Surface, tonalElevation = 0.dp,
            )
        }
    }

    @Composable
    private fun MediaTab() {
        val selecting = selected.isNotEmpty()
        androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
        Box(Modifier.fillMaxSize()) {
            val list = if (source == com.mhmmtbg.x5reframe.ui.AlbumSource.CAMERA) media else exports
            val switch: @Composable () -> Unit = {
                com.mhmmtbg.x5reframe.ui.SourceSwitch(source) { source = it; selected = emptySet() }
            }
            when {
                source == com.mhmmtbg.x5reframe.ui.AlbumSource.CAMERA && folder == null -> Column {
                    switch()
                    com.mhmmtbg.x5reframe.ui.AlbumEmpty("Kamera klasörünü seç",
                        "SD kart okuyucudaki DCIM ya da Camera01 klasörünü bir kez seç; çekimlerin tarihe göre burada listelenir.", "Klasör seç") { folderPicker.launch(null) }
                }
                list.isEmpty() -> Column {
                    switch()
                    if (scanning) com.mhmmtbg.x5reframe.ui.AlbumEmpty("Taranıyor…", "", null) {}
                    else if (source == com.mhmmtbg.x5reframe.ui.AlbumSource.CAMERA)
                        com.mhmmtbg.x5reframe.ui.AlbumEmpty("Çekim bulunamadı", "Bu klasörde .insv dosyası yok. DCIM/Camera01 klasörünü seçtiğinden emin ol.", "Başka klasör") { folderPicker.launch(null) }
                    else com.mhmmtbg.x5reframe.ui.AlbumEmpty("Henüz dışa aktarım yok", "Düzenleyip kaydettiğin videolar burada görünür.", null) {}
                }
                else -> com.mhmmtbg.x5reframe.ui.AlbumGrid(
                    items = list, selected = selected, selecting = selecting,
                    onOpen = ::openItem,
                    onToggle = { item ->
                        if (item.is360 && !item.isPhoto) {
                            val k = item.uri.toString()
                            selected = if (k in selected) selected - k else selected + k
                        }
                    },
                    header = switch,
                )
            }
            if (selecting) Box(Modifier.align(Alignment.BottomCenter)) {
                com.mhmmtbg.x5reframe.ui.SelectionBar(selected.size, { selected = emptySet() }, ::editSelected) { openSelected(auto = true) }
            }
        }
    }

    private val TIPS = listOf(
        "Düz videoda önizlemeyi sürükleyip iki parmakla yakınlaştır, sonra anahtar kare ekle: yavaş kaydırma ve yakınlaşma etkisi.",
        "Dikey bir videoyu yatay projede kullanırken Görünüm › Yerleşim › Bulanık arka plan siyah boşlukları doldurur.",
        "360° klibin yanında aynı adlı LRV dosyasını da seç: önizleme çok daha akıcı olur.",
        "Dışa aktarım arka planda sürer; uygulamadan çıkabilirsin, bitince bildirim gelir.",
        "Kablosuz bağlantıda yalnızca seçtiğin çekimler telefona iner; diğerlerini indirmeden önizleyebilirsin.",
        "Fotoğraf klibinin süresini Kes aracındaki tutamaklarla 30 saniyeye kadar uzatabilirsin.",
    )

    @Composable
    private fun EmptyState(title: String, body: String) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

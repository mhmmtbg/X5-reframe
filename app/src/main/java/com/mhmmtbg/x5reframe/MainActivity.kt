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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.mhmmtbg.x5reframe.ui.theme.Palette
import com.mhmmtbg.x5reframe.ui.theme.X5Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ana ekran: projeler ve medya. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ProjectStore
    private var projects by mutableStateOf<List<Project>>(emptyList())
    private var folder by mutableStateOf<Uri?>(null)
    private var media by mutableStateOf<List<DocumentFile>>(emptyList())

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            openNew(uri)
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
        val f = folder ?: return
        Thread {
            val list = runCatching {
                DocumentFile.fromTreeUri(this, f)?.let { root ->
                    // Hem seçilen klasörü hem içindeki DCIM/Camera01 düzenini tara.
                    val dirs = listOf(root) + root.listFiles().filter { it.isDirectory }.flatMap { d ->
                        listOf(d) + d.listFiles().filter { it.isDirectory }
                    }
                    dirs.flatMap { d -> d.listFiles().filter { it.name?.endsWith(".insv", true) == true } }
                        .sortedByDescending { it.lastModified() }
                }.orEmpty()
            }.getOrDefault(emptyList())
            runOnUiThread { media = list }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        folder = getSharedPreferences("prefs", MODE_PRIVATE).getString("folder", null)?.let(Uri::parse)
        loadMedia()
        setContent { X5Theme { Home() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Composable
    private fun Home() {
        var tab by remember { mutableStateOf(0) }
        Column(Modifier.fillMaxSize().background(Palette.Background)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(52.dp))
                Text("X5 Reframe", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(
                    onClick = { picker.launch(arrayOf("video/*", "application/octet-stream", "*/*")) },
                    shape = RoundedCornerShape(10.dp), elevation = null,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                ) { Text("Yeni proje") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf("Projeler", "Medya").forEachIndexed { i, label ->
                    Column(Modifier.weight(1f).clickable { tab = i }.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = if (tab == i) Palette.Text else Palette.Muted, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(32.dp).height(3.dp).background(if (tab == i) Palette.Accent else Palette.Background, RoundedCornerShape(2.dp)))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
            if (tab == 0) ProjectsTab() else MediaTab()
        }
    }

    @Composable
    private fun ProjectsTab() {
        if (projects.isEmpty()) {
            EmptyState("Henüz proje yok", "Yeni proje ile kameranın SD kartından bir .insv klibi seç. Yaptığın düzenlemeler otomatik kaydedilir.")
            return
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(projects, key = { it.id }) { p -> ProjectCard(p) }
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
                    "${"%.1f".format((p.edit.outUs - p.edit.inUs) / 1e6)} sn · ${p.edit.keyframes.size} anahtar kare",
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
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    folder?.let { DocumentFile.fromTreeUri(this@MainActivity, it)?.name } ?: "Klasör seçilmedi",
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { folderPicker.launch(null) }) { Text(if (folder == null) "Klasör seç" else "Değiştir", color = Palette.Accent) }
            }
            if (folder == null) {
                EmptyState("Klasör seç", "SD kart okuyucudaki DCIM ya da Camera01 klasörünü bir kez seç; klipler burada listelenir.")
                return
            }
            if (media.isEmpty()) {
                EmptyState("Klip bulunamadı", "Bu klasörde .insv dosyası yok. DCIM/Camera01 klasörünü seçtiğinden emin ol.")
                return
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(media, key = { it.uri.toString() }) { f -> MediaRow(f) }
            }
        }
    }

    @Composable
    private fun MediaRow(f: DocumentFile) {
        val thumb by produceState<Bitmap?>(null, f.uri) { value = withContext(Dispatchers.IO) { lrvThumbnail(f) } }
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(12.dp)).clickable { openNew(f.uri, lrvFor(f)?.uri) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(128.dp).aspectRatio(2f).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh)) {
                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(f.name ?: "?", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${f.length() / (1024 * 1024)} MB · ${DateUtils.getRelativeTimeSpanString(f.lastModified())}",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    /** Aynı çekimin LRV önizleme dosyası: VID_..._00_024.insv → LRV_..._01_024.lrv */
    private fun lrvFor(f: DocumentFile): DocumentFile? = runCatching {
        val name = f.name ?: return null
        val lrvName = name.replaceFirst("VID_", "LRV_").replace("_00_", "_01_").replaceAfterLast('.', "lrv")
        val parent = f.parentFile ?: findParent(f) ?: return null
        parent.findFile(lrvName) ?: parent.findFile(lrvName.uppercase().replace(".LRV", ".lrv"))
    }.getOrNull()

    private fun findParent(f: DocumentFile): DocumentFile? {
        val root = folder?.let { DocumentFile.fromTreeUri(this, it) } ?: return null
        val dirs = listOf(root) + root.listFiles().filter { it.isDirectory }.flatMap { d -> listOf(d) + d.listFiles().filter { it.isDirectory } }
        return dirs.firstOrNull { d -> d.listFiles().any { it.uri == f.uri } }
    }

    /** LRV önizleme dosyasından küçük resim (yoksa null). */
    private fun lrvThumbnail(f: DocumentFile): Bitmap? = runCatching {
        val lrv = lrvFor(f) ?: return null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, lrv.uri)
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 192)
        } finally { r.release() }
    }.getOrNull()

    @Composable
    private fun EmptyState(title: String, body: String) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

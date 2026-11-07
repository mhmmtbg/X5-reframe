package com.mhmmtbg.x5reframe

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Exporter
import com.mhmmtbg.x5reframe.media.Player
import com.mhmmtbg.x5reframe.ui.EditorActions
import com.mhmmtbg.x5reframe.ui.EditorController
import com.mhmmtbg.x5reframe.ui.EditorScreen
import com.mhmmtbg.x5reframe.ui.TimelineView
import com.mhmmtbg.x5reframe.ui.theme.Palette
import com.mhmmtbg.x5reframe.ui.theme.X5Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class EditorActivity : AppCompatActivity(), Player.Listener {

    private var controller: EditorController? = null
    private lateinit var store: com.mhmmtbg.x5reframe.project.ProjectStore
    private var project: com.mhmmtbg.x5reframe.project.Project? = null
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveTask = Runnable { saveNow() }
    private var player: Player? = null
    private var userScrubbing = false
    private var loadError by mutableStateOf<String?>(null)
    private var loaded by mutableStateOf(false)

    /** Oynatıcı sayfası (albümden açılınca) ya da düzenleyici. */
    private var viewer by mutableStateOf(false)
    private var cameFromViewer = false
    private var editorStartTool by mutableStateOf<String?>(null)
    private var cleanToggles by mutableStateOf(0)
    private var favorite by mutableStateOf(false)

    /** Projedeki parçalar ve seçili parça (arayüz için). */
    private var segments by mutableStateOf<List<com.mhmmtbg.x5reframe.project.Segment>>(emptyList())
    private var segIndex by mutableStateOf(0)
    private val clipCache = HashMap<String, ClipInfo>()
    private var surface: android.view.Surface? = null
    private var surfW = 0
    private var surfH = 0

    private val imagePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importImage(uri)
    }

    /** Seçilen görseli uygulamanın kendi klasörüne (en fazla 1024 px) kopyala ve katman yap. */
    private fun importImage(uri: Uri) {
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val src = contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                    val k = minOf(1f, 1024f / maxOf(src.width, src.height))
                    val bmp = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
                    val dir = java.io.File(filesDir, "layers").apply { mkdirs() }
                    val f = java.io.File(dir, java.util.UUID.randomUUID().toString() + ".png")
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    f.absolutePath
                }.getOrNull()
            }
            val c = controller ?: return@launch
            if (path == null) c.say("Görsel açılamadı", false) else c.addLayer(com.mhmmtbg.x5reframe.model.Layer.Kind.IMAGE, path)
        }
    }

    private var projectAudio by mutableStateOf(com.mhmmtbg.x5reframe.project.ProjectAudio())
    private var musicPreview: android.media.MediaPlayer? = null

    private val musicPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setAudio(projectAudio.copy(musicUri = uri, musicName = displayName(uri)))
            controller?.say("Müzik eklendi")
        }
    }

    private fun setAudio(a: com.mhmmtbg.x5reframe.project.ProjectAudio) {
        projectAudio = a
        project = project?.copy(audio = a, updatedAt = System.currentTimeMillis())
        project?.let { p -> Thread { runCatching { store.save(p) } }.start() }
        player?.setVolume(a.originalVolume)
        setupMusicPreview()
    }

    /** Önizlemede müzik: MediaPlayer, zaman çizelgesi konumuna göre başlatılır. */
    private fun setupMusicPreview() {
        musicPreview?.release(); musicPreview = null
        val uri = projectAudio.musicUri ?: return
        musicPreview = runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(this@EditorActivity, uri)
                isLooping = projectAudio.loop
                setVolume(projectAudio.musicVolume.coerceAtMost(1f), projectAudio.musicVolume.coerceAtMost(1f))
                prepare()
            }
        }.getOrNull()
    }

    /** Seçili parçadaki konumun proje zaman çizelgesindeki karşılığı (µs). */
    private fun timelinePosition(): Long {
        val c = controller ?: return 0
        var off = 0L
        for (i in 0 until segIndex) off += segments[i].outputDurationUs
        val seg = segments.getOrNull(segIndex) ?: return off
        return off + ((c.positionUs - seg.edit.inUs).coerceAtLeast(0) / seg.speed).toLong()
    }

    private fun syncMusic(playing: Boolean) {
        val mp = musicPreview ?: return
        if (playing && controller?.previewMuted != true) {
            val ms = (timelinePosition() / 1000).toInt()
            val d = mp.duration
            mp.seekTo(if (projectAudio.loop && d > 0) ms % d else ms.coerceAtMost(d))
            mp.start()
        } else if (mp.isPlaying) mp.pause()
    }

    @Volatile private var autoEditor: com.mhmmtbg.x5reframe.auto.AutoEditor? = null

    /** Otomatik düzenleme işini arka planda çalıştır. */
    private fun runAuto(c: EditorController, job: (com.mhmmtbg.x5reframe.auto.AutoEditor) -> Unit) {
        if (autoEditor != null) return
        player?.pause()
        val ed = com.mhmmtbg.x5reframe.auto.AutoEditor(applicationContext, c.clip) { t -> c.baseAt(t) }
        autoEditor = ed
        c.autoTask = "Hazırlanıyor…" to 0f
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread({
            runCatching { job(ed) }.onFailure { e -> runOnUiThread { c.say("Otomatik düzenleme hatası: ${e.message}", false) } }
            runOnUiThread {
                if (ed.cancelled) c.say("Durduruldu", false)
                c.autoTask = null
                autoEditor = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }, "auto-edit").start()
    }

    /** Seçili parçayı öne çıkan aralıklara böl (siyaha geçişlerle). */
    private fun replaceWithHighlights(ranges: List<LongRange>) {
        storeCurrentEdit()
        val seg = segments[segIndex]
        val parts = ranges.mapIndexed { k, r ->
            val e = seg.edit.copy(inUs = r.first.coerceAtLeast(0), outUs = r.last.coerceAtMost(seg.durationUs))
            val base = if (k == 0) seg.copy(edit = e) else store.newSegment(seg.clipUri, seg.clipName, seg.durationUs, seg.lrvUri, e).copy(speed = seg.speed)
            base.copy(transitionOut = if (k < ranges.size - 1) com.mhmmtbg.x5reframe.project.Transition.FADE else seg.transitionOut)
        }
        val list = segments.toMutableList().apply { removeAt(segIndex); addAll(segIndex, parts) }
        updateSegments(list, segIndex, reopen = true)
        controller?.say("Kurgu: ${parts.size} öne çıkan an")
    }

    private val addClipPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            addSegment(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = com.mhmmtbg.x5reframe.project.ProjectStore(this)
        val existing = intent.getStringExtra(EXTRA_PROJECT)?.let { store.get(it) }
        val newUri: Uri? = if (existing == null) intent.data ?: run { finish(); return } else null
        viewer = intent.getBooleanExtra(EXTRA_VIEWER, false)
        cameFromViewer = viewer
        setContent {
            X5Theme {
                val c = controller
                if (loaded && c != null) {
                    androidx.activity.compose.BackHandler(enabled = !viewer && cameFromViewer) { showViewer() }
                    if (viewer) com.mhmmtbg.x5reframe.ui.ViewerScreen(c, viewerActions(c), ::createSurface, cleanToggles, sequenceUi())
                    else EditorScreen(c, actions(c), ::createSurface, ::createTimeline, sequenceUi(), editorStartTool)
                } else {
                    Box(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
                        Text(
                            loadError?.let { "Açılamadı: $it" } ?: "Klip okunuyor…",
                            color = if (loadError != null) Palette.Error else Palette.Muted,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
        lifecycleScope.launch {
            val reuse = if (existing == null && viewer) withContext(Dispatchers.IO) {
                store.list().firstOrNull { it.segments.size == 1 && it.segments[0].clipUri == newUri }
            } else null
            val proj = existing ?: reuse ?: run {
                // LRV: seçimle geldiyse onu, gelmediyse kaydedilmiş medya klasöründe ada göre ara.
                val lrvUri = intent.getStringExtra(EXTRA_LRV)?.let(Uri::parse) ?: withContext(Dispatchers.IO) {
                    com.mhmmtbg.x5reframe.project.LrvFinder.findInTree(
                        this@EditorActivity, com.mhmmtbg.x5reframe.project.LrvFinder.savedFolder(this@EditorActivity), displayName(newUri!!),
                    )
                }
                val r = withContext(Dispatchers.IO) { runCatching { ClipInfo.load(this@EditorActivity, newUri!!, lrvUri) } }
                val clip = r.getOrElse { loadError = it.message ?: it.javaClass.simpleName; return@launch }
                clipCache[newUri.toString()] = clip
                withContext(Dispatchers.IO) {
                    var p = store.create(newUri!!, displayName(newUri), clip.durationUs, clip.lrv?.uri)
                    // Albümde birden fazla çekim seçildiyse arkaya ekle
                    val more = intent.getStringArrayExtra(EXTRA_MORE).orEmpty().map(Uri::parse)
                    if (more.isNotEmpty()) {
                        val folder = com.mhmmtbg.x5reframe.project.LrvFinder.savedFolder(this@EditorActivity)
                        val extra = more.mapNotNull { u ->
                            val lrv = com.mhmmtbg.x5reframe.project.LrvFinder.findInTree(this@EditorActivity, folder, displayName(u))
                            runCatching { ClipInfo.load(this@EditorActivity, u, lrv) }.getOrNull()?.let { ci ->
                                clipCache[u.toString()] = ci
                                store.newSegment(u, displayName(u), ci.durationUs, ci.lrv?.uri)
                            }
                        }
                        p = p.copy(segments = p.segments + extra)
                        store.save(p)
                    }
                    p
                }.also { saveHandler.postDelayed({ captureThumb() }, 1500) }
            }
            project = proj
            favorite = proj.segments.firstOrNull()?.clipUri?.toString()?.let {
                getSharedPreferences("prefs", MODE_PRIVATE).getStringSet("favorites", emptySet()).orEmpty().contains(it)
            } ?: false
            segments = proj.segments
            projectAudio = proj.audio
            setupMusicPreview()
            openSegment(0)
            observeExports()
            // Kare zamanlaması göstergesi (açıksa yarım saniyede bir güncellenir)
            while (true) {
                kotlinx.coroutines.delay(500)
                val c = controller ?: continue
                if (c.showStats) c.statsText = player?.stats?.snapshot()?.toString() + (if (c.clip.lrv != null) " · LRV" else " · tam çözünürlük")
            }
        }
    }

    /** Bir parçayı düzenleyicide aç (öncekinin düzenlemesi kaydedilir). */
    private fun openSegment(i: Int) {
        val proj = project ?: return
        storeCurrentEdit()
        val seg = proj.segments.getOrNull(i) ?: return
        player?.pause()
        player?.detachSurface()
        player?.release()
        player = null
        loaded = false
        loadError = null
        lifecycleScope.launch {
            val clip = clipCache[seg.clipUri.toString()] ?: withContext(Dispatchers.IO) {
                runCatching { ClipInfo.load(this@EditorActivity, seg.clipUri, seg.lrvUri) }
            }.getOrElse {
                loadError = (it.message ?: it.javaClass.simpleName) + "\n\nKlip taşınmış ya da silinmiş olabilir."
                return@launch
            }
            clipCache[seg.clipUri.toString()] = clip
            val c = EditorController(clip, proj.name, seg.edit)
            val p = Player(this@EditorActivity, clip, this@EditorActivity)
            p.sceneProvider = { t -> c.sceneAt(t) }
            p.layoutProvider = { c.snapshotState.layout.rects }
            p.baseProvider = { t -> c.baseAt(t) }
            p.layersProvider = { c.snapshotState.layers }
            p.setVolume(projectAudio.originalVolume)
            p.setStitchInv(c.state.stitchInv)
            c.onChanged = {
                p.setStitchInv(c.state.stitchInv)
                p.requestRedraw()
                saveHandler.removeCallbacks(saveTask)
                saveHandler.postDelayed(saveTask, 800)
            }
            filmstrip = emptyList()
            timelineView?.filmstrip = emptyList()
            buildFilmstrip(clip)
            segIndex = i
            controller = c
            player = p
            surface?.let { sf -> if (sf.isValid) p.attachSurface(sf, surfW, surfH) }
            loaded = true
        }
    }

    /** Seçili parçanın düzenlemesini proje nesnesine yaz. */
    private fun storeCurrentEdit() {
        val c = controller ?: return
        val proj = project ?: return
        if (segIndex !in proj.segments.indices) return
        val segs = proj.segments.toMutableList()
        segs[segIndex] = segs[segIndex].copy(edit = c.state)
        project = proj.copy(segments = segs)
        segments = project!!.segments
    }

    private fun updateSegments(newList: List<com.mhmmtbg.x5reframe.project.Segment>, select: Int, reopen: Boolean) {
        val proj = project ?: return
        project = proj.copy(segments = newList, updatedAt = System.currentTimeMillis())
        segments = newList
        Thread { runCatching { store.save(project!!) } }.start()
        if (reopen) { controller = null; openSegmentNoStore(select) } else segIndex = select
    }

    private fun openSegmentNoStore(i: Int) {
        val keep = controller
        controller = null
        openSegment(i)
        if (keep != null) { /* düzenleme zaten storeCurrentEdit ile yazıldı */ }
    }

    private fun addSegment(uri: Uri) {
        lifecycleScope.launch {
            val clip = withContext(Dispatchers.IO) {
                val lrv = com.mhmmtbg.x5reframe.project.LrvFinder.findInTree(
                    this@EditorActivity, com.mhmmtbg.x5reframe.project.LrvFinder.savedFolder(this@EditorActivity), displayName(uri),
                )
                runCatching { ClipInfo.load(this@EditorActivity, uri, lrv) }
            }.getOrElse {
                controller?.say("Eklenemedi: ${it.message}", false); return@launch
            }
            clipCache[uri.toString()] = clip
            storeCurrentEdit()
            val proj = project ?: return@launch
            val seg = store.newSegment(uri, displayName(uri), clip.durationUs, clip.lrv?.uri)
            val list = proj.segments + seg
            updateSegments(list, list.size - 1, reopen = true)
        }
    }

    private fun sequenceUi() = com.mhmmtbg.x5reframe.ui.SequenceUi(
        segments = segments,
        current = segIndex,
        select = { i -> if (i != segIndex) openSegment(i) },
        add = { addClipPicker.launch(arrayOf("video/*", "application/octet-stream", "*/*")) },
        move = { i, dir ->
            storeCurrentEdit()
            val list = segments.toMutableList()
            val j = i + dir
            if (j in list.indices) {
                java.util.Collections.swap(list, i, j)
                val sel = when (segIndex) { i -> j; j -> i; else -> segIndex }
                updateSegments(list, sel, reopen = false)
                controller?.say("Parça taşındı")
            }
        },
        split = { i ->
            storeCurrentEdit()
            val c = controller
            val seg = segments[i]
            val pos = if (i == segIndex && c != null) c.positionUs else (seg.edit.inUs + seg.edit.outUs) / 2
            if (pos - seg.edit.inUs < 300_000 || seg.edit.outUs - pos < 300_000) {
                c?.say("Bölmek için oynatma çizgisini parçanın ortasına doğru getir", false)
            } else {
                val a = seg.copy(edit = seg.edit.copy(outUs = pos), transitionOut = com.mhmmtbg.x5reframe.project.Transition.CUT)
                val b = store.newSegment(seg.clipUri, seg.clipName, seg.durationUs, seg.lrvUri, seg.edit.copy(inUs = pos))
                    .copy(speed = seg.speed, transitionOut = seg.transitionOut)
                val list = segments.toMutableList().apply { set(i, a); add(i + 1, b) }
                updateSegments(list, i + 1, reopen = true)
                c?.say("Parça bölündü")
            }
        },
        duplicate = { i ->
            storeCurrentEdit()
            val seg = segments[i]
            val copy = store.newSegment(seg.clipUri, seg.clipName, seg.durationUs, seg.lrvUri, seg.edit)
                .copy(speed = seg.speed, transitionOut = seg.transitionOut)
            val list = segments.toMutableList().apply { add(i + 1, copy) }
            updateSegments(list, segIndex, reopen = false)
            controller?.say("Parça çoğaltıldı")
        },
        delete = { i ->
            if (segments.size <= 1) {
                controller?.say("Son parça silinemez", false)
            } else {
                storeCurrentEdit()
                val list = segments.toMutableList().apply { removeAt(i) }
                val sel = (if (i < segIndex) segIndex - 1 else segIndex).coerceIn(0, list.size - 1)
                updateSegments(list, sel, reopen = i == segIndex)
                controller?.say("Parça silindi")
            }
        },
        setSpeed = { i, sp ->
            storeCurrentEdit()
            val list = segments.toMutableList().apply { set(i, get(i).copy(speed = sp)) }
            updateSegments(list, segIndex, reopen = false)
            controller?.say("Hız ${sp}×" + if (sp != 1.0) " (bu parçada ses kapanır)" else "")
        },
        audio = projectAudio,
        pickMusic = { musicPicker.launch(arrayOf("audio/*")) },
        updateAudio = { a -> setAudio(a) },
        setTransition = { i, tr ->
            storeCurrentEdit()
            val list = segments.toMutableList().apply { set(i, get(i).copy(transitionOut = tr)) }
            updateSegments(list, segIndex, reopen = false)
            controller?.say("Geçiş: ${tr.label}")
        },
    )

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "Klip")

    // ---- Kayıt modu ----

    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager }
    private var gyroRef: FloatArray? = null
    private var gyroStartView: com.mhmmtbg.x5reframe.model.ViewParams? = null
    private val gyroListener = object : android.hardware.SensorEventListener {
        private val r = FloatArray(9); private val rr = FloatArray(9); private val o = FloatArray(3)
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val c = controller ?: return
            android.hardware.SensorManager.getRotationMatrixFromVector(r, e.values)
            @Suppress("DEPRECATION")
            val rot = windowManager.defaultDisplay.rotation
            val (ax, ay) = when (rot) {
                android.view.Surface.ROTATION_90 -> android.hardware.SensorManager.AXIS_Z to android.hardware.SensorManager.AXIS_MINUS_X
                android.view.Surface.ROTATION_270 -> android.hardware.SensorManager.AXIS_MINUS_Z to android.hardware.SensorManager.AXIS_X
                else -> android.hardware.SensorManager.AXIS_X to android.hardware.SensorManager.AXIS_Z
            }
            android.hardware.SensorManager.remapCoordinateSystem(r, ax, ay, rr)
            android.hardware.SensorManager.getOrientation(rr, o)
            val ref = gyroRef ?: o.copyOf().also { gyroRef = it }
            val start = gyroStartView ?: return
            val dyaw = Math.toDegrees((o[0] - ref[0]).toDouble())
            val dpitch = Math.toDegrees((o[1] - ref[1]).toDouble())
            c.updateView { start.copy(yaw = start.yaw + dyaw, pitch = start.pitch - dpitch) }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private fun toggleRecording(c: EditorController) {
        if (c.recording) { stopRecording(); return }
        val p = player ?: return
        c.startRecording(c.positionUs)
        if (c.recordGyro) {
            gyroRef = null; gyroStartView = c.currentView
            sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
                sensors.registerListener(gyroListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            } ?: c.say("Telefonda hareket sensörü yok; parmakla bak", false)
        }
        if (!p.playing) p.play()
    }

    private fun stopRecording() {
        sensors.unregisterListener(gyroListener)
        gyroStartView = null
        player?.pause()
        controller?.stopRecording()
    }

    private fun showViewer() { viewer = true; editorStartTool = null }

    private fun viewerActions(c: EditorController) = com.mhmmtbg.x5reframe.ui.ViewerActions(
        editor = actions(c),
        openEditor = { tool -> player?.pause(); editorStartTool = tool; viewer = false },
        snapshot = { snapshot(c) },
        record = { toggleRecording(c) },
        highlights = { actions(c).autoHighlights(15) },
        autoEdit = { actions(c).autoHighlights(30) },
        isFavorite = { favorite },
        toggleFavorite = {
            val key = c.clip.uri.toString()
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            val set = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
            favorite = if (key in set) { set.remove(key); false } else { set.add(key); true }
            prefs.edit().putStringSet("favorites", set).apply()
            c.say(if (favorite) "Favorilere eklendi" else "Favorilerden çıkarıldı")
        },
        delete = if (android.provider.DocumentsContract.isDocumentUri(this, c.clip.uri)) ({ deleteClip(c) }) else null,
    )

    /** Kameradaki çekimi ve LRV kopyasını sil; projesini de kaldır. */
    private fun deleteClip(c: EditorController) {
        player?.pause()
        val ok = runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, c.clip.uri) }.getOrDefault(false)
        if (!ok) { c.say("Silinemedi: klasöre yazma izni yok. Ayarlar'dan klasörü yeniden seç.", false); return }
        c.clip.lrv?.uri?.let { runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, it) } }
        project?.let { store.delete(it.id) }
        project = null
        finish()
    }

    /** O anki kadrajı fotoğraf olarak Resimler/X5Reframe klasörüne kaydet. */
    private fun snapshot(c: EditorController) {
        val p = player ?: return
        val aspect = c.state.aspect
        val w = 1920; val h = (1920L * aspect.h / aspect.w).toInt().coerceIn(480, 3840) / 2 * 2
        p.captureThumbnail(w, h) { bmp ->
            val name = "X5_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/X5Reframe")
            }
            val ok = runCatching {
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            }.isSuccess
            runOnUiThread { c.say(if (ok) "Anlık görüntü kaydedildi · Resimler/X5Reframe" else "Anlık görüntü kaydedilemedi", ok) }
        }
    }

    private fun actions(c: EditorController) = EditorActions(
        back = { if (!viewer && cameFromViewer) showViewer() else finish() },
        playPause = {
            val p = player
            if (p != null) { if (p.playing) p.pause() else { c.clearLive(); p.play() } }
        },
        seek = { us -> c.clearLive(); c.positionUs = us; player?.seekTo(us) },
        startExport = { whole -> startExport(c, whole) },
        cancelExport = { com.mhmmtbg.x5reframe.export.ExportManager.cancelCurrent() },
        open = { uri ->
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        },
        share = { uri ->
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Paylaş"
                )
            )
        },
        setMuted = { player?.setMuted(it); syncMusic(player?.playing == true) },
        pickImage = { imagePicker.launch("image/*") },
        autoFrame = { runAuto(c) { ed ->
            val s0 = c.state
            val samples = ed.scan(s0.inUs, s0.outUs) { p -> runOnUiThread { c.autoTask = "Yüzler aranıyor…" to p } }
            val keys = ed.smartFraming(samples, c.currentView.fov.coerceIn(50.0, 100.0))
            val faces = samples.count { it.faces.isNotEmpty() }
            runOnUiThread {
                if (faces == 0) c.say("Klipte yüz bulunamadı", false)
                else { c.replaceKeyframes(s0.inUs, s0.outUs, keys); c.say("Akıllı kadraj: ${keys.size} anahtar kare") }
            }
        } },
        autoTrack = { runAuto(c) { ed ->
            val from = c.positionUs; val to = c.state.outUs
            val start = c.currentView
            val keys = ed.track(from, to, start) { p -> runOnUiThread { c.autoTask = "Takip ediliyor…" to p } }
            runOnUiThread {
                if (keys.size < 3) c.say("Ortada takip edilecek bir şey bulunamadı", false)
                else {
                    c.replaceKeyframes(from, keys.last().timeUs, keys)
                    c.say("Takip: ${"%.1f".format((keys.last().timeUs - from) / 1e6)} sn")
                }
            }
        } },
        autoHighlights = { sec -> runAuto(c) { ed ->
            val s0 = c.state
            val samples = ed.scan(s0.inUs, s0.outUs) { p -> runOnUiThread { c.autoTask = "Öne çıkan anlar aranıyor…" to p } }
            val windowUs = if (sec <= 15) 2_500_000L else 4_000_000L
            val ranges = ed.highlights(samples, windowUs, sec * 1_000_000L)
            runOnUiThread {
                if (ranges.size < 2) c.say("Klip kısa: öne çıkan an bulunamadı", false)
                else replaceWithHighlights(ranges)
            }
        } },
        cancelAuto = { autoEditor?.cancelled = true },
        step = { fwd -> c.clearLive(); player?.step(fwd) },
        snapshot = { snapshot(c) },
        dynamicStitch = { getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dynamic_stitch", true) },
        setDynamicStitch = { on ->
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("dynamic_stitch", on).apply()
            player?.setDynamicStitch(on)
        },
    )

    // ---- Önizleme yüzeyi ----

    @SuppressLint("ClickableViewAccessibility")
    private fun createSurface(ctx: android.content.Context): SurfaceView {
        val sv = SurfaceView(ctx)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surface = holder.surface; surfW = width; surfH = height
                player?.attachSurface(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null; player?.detachSurface() }
        })
        var lastX = 0f
        var lastY = 0f
        var scaling = false
        val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                controller?.updateView { it.copy(fov = it.fov / d.scaleFactor) }
                return true
            }
        })
        val taps = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { if (viewer) cleanToggles++; return true }
        })
        sv.setOnTouchListener { v, e ->
            val c = controller ?: return@setOnTouchListener false
            taps.onTouchEvent(e)
            if (c.layerTool && c.currentLayer != null) {
                // Katman sekmesi: seçili katmanı parmakla taşı.
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (player?.playing == true) player?.pause()
                        val nx = (e.x / v.width).coerceIn(0f, 1f); val ny = (e.y / v.height).coerceIn(0f, 1f)
                        c.updateLayerLive { it.copy(x = nx, y = ny) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> c.endLayerGesture()
                }
                return@setOnTouchListener true
            }
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Oynatıcı sayfasında video akarken etrafa bakılabilir; düzenleyicide durur.
                    if (player?.playing == true && !viewer) player?.pause()
                    // Çoklu açıda dokunulan bölmeyi seç (küçük bölmeler üstte olduğundan sondan başla).
                    val rects = c.state.layout.rects
                    if (rects.size > 1) {
                        val nx = e.x / v.width; val ny = e.y / v.height
                        val hit = rects.indices.reversed().firstOrNull { i ->
                            val r = rects[i]; nx >= r[0] && nx <= r[0] + r[2] && ny >= r[1] && ny <= r[1] + r[3]
                        }
                        if (hit != null) c.selectPane(hit)
                    }
                    c.beginViewGesture()
                    lastX = e.x; lastY = e.y; scaling = false
                }
                MotionEvent.ACTION_MOVE -> if (!scaling && e.pointerCount == 1) {
                    val r = c.state.layout.rects[c.activePane.coerceIn(0, c.state.layout.count - 1)]
                    val degPerPx = c.currentView.fov / max(v.width * r[2], v.height * r[3])
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    c.updateView { it.copy(yaw = it.yaw - dx * degPerPx, pitch = it.pitch + dy * degPerPx) }
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx); lastY = e.getY(idx)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> c.endViewGesture()
            }
            true
        }
        return sv
    }

    private fun createTimeline(ctx: android.content.Context): TimelineView {
        val t = TimelineView(ctx)
        t.filmstrip = filmstrip
        timelineView = t
        t.listener = object : TimelineView.Listener {
            override fun onScrub(us: Long, fromUser: Boolean) {
                val c = controller ?: return
                c.clearLive()
                c.positionUs = us
                player?.seekTo(us)
            }
            private var peeked = false
            override fun onScrubStart() { userScrubbing = true; player?.pause() }
            override fun onScrubEnd() {
                userScrubbing = false
                controller?.endTrimDrag()
                if (peeked) { peeked = false; controller?.let { player?.seekTo(it.positionUs) } }
            }
            override fun onPeek(us: Long) { peeked = true; player?.seekTo(us) }
            override fun onTrimChanged(inUs: Long, outUs: Long) { controller?.dragTrim(inUs, outUs) }
        }
        return t
    }

    // ---- Dışa aktarım ----

    private val notifPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    /** Dışa aktarımı kuyruğa ekle; arka planda (ön plan hizmetinde) çalışır. */
    private fun startExport(c: EditorController, whole: Boolean) {
        player?.pause()
        storeCurrentEdit()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val cur = c.state
        val segs = if (whole) segments else listOf(segments[segIndex].copy(edit = cur))
        val title = project?.name ?: c.fileName
        val req = com.mhmmtbg.x5reframe.export.ExportManager.enqueue(this, title, segs, cur, projectAudio, cur.bitrate.factor)
        if (com.mhmmtbg.x5reframe.export.ExportManager.queuedCount > 0 && req.id > 1) c.say("Sıraya eklendi")
    }

    /** Kuyruk durumunu düzenleyici arayüzüne yansıt. */
    private fun observeExports() {
        lifecycleScope.launch {
            com.mhmmtbg.x5reframe.export.ExportManager.state.collect { st ->
                val c = controller ?: return@collect
                c.exportUi = when (st) {
                    is com.mhmmtbg.x5reframe.export.ExportManager.Status.Idle -> EditorController.ExportUi.Idle
                    is com.mhmmtbg.x5reframe.export.ExportManager.Status.Running -> EditorController.ExportUi.Running(st.fraction, st.speed)
                    is com.mhmmtbg.x5reframe.export.ExportManager.Status.Done -> {
                        window.decorView.performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                        )
                        EditorController.ExportUi.Done(st.uri, st.request.durationUs / 1e6f, st.seconds)
                    }
                    is com.mhmmtbg.x5reframe.export.ExportManager.Status.Failed -> EditorController.ExportUi.Failed(st.message)
                }
                c.exportQueued = when (st) {
                    is com.mhmmtbg.x5reframe.export.ExportManager.Status.Running -> st.queued
                    else -> com.mhmmtbg.x5reframe.export.ExportManager.queuedCount
                }
            }
        }
    }



    // ---- Player.Listener ----

    private var lastUiPositionMs = 0L

    /**
     * Oynatma sırasında her karede yalnızca zaman çizelgesi çizgisi güncellenir; arayüzün geri kalanı
     * (Compose) saniyede ~8 kez. Her karede tüm ekranın yeniden oluşturulması önizlemeyi takıltıyordu.
     */
    override fun onPosition(us: Long) {
        if (userScrubbing) return
        timelineView?.positionUs = us
        val c = controller ?: return
        c.recordSample(us)
        val now = android.os.SystemClock.uptimeMillis()
        if (!c.playing || now - lastUiPositionMs >= 120) {
            lastUiPositionMs = now
            c.positionUs = us
        }
    }

    override fun onPlayingChanged(playing: Boolean) {
        controller?.playing = playing
        if (!playing && controller?.recording == true) stopRecording()
        // Durunca konumu tam kareye eşitle
        if (!playing) timelineView?.positionUs?.let { controller?.positionUs = it }
        syncMusic(playing)
    }

    override fun onError(message: String) {
        controller?.say("Hata: $message", false)
    }

    private fun saveNow() {
        storeCurrentEdit()
        val p = project ?: return
        val updated = p.copy(updatedAt = System.currentTimeMillis())
        project = updated
        Thread { runCatching { store.save(updated) } }.start()
    }

    /** Zaman çizelgesi için LRV'den ön lens merkezini gösteren küçük kareler. */
    private var timelineView: TimelineView? = null
    private var filmstrip: List<android.graphics.Bitmap> = emptyList()

    private fun buildFilmstrip(clip: ClipInfo) {
        val lrv = clip.lrv ?: return
        Thread {
            val frames = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(this, lrv.uri)
                    val n = 12
                    val lens = clip.rig.lenses[0]
                    (0 until n).mapNotNull { i ->
                        val t = clip.durationUs * (i + 0.5) / n
                        r.getFrameAtTime(t.toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                            val half = f.width / 2
                            val cx = (lens.cx * half).toInt(); val cy = (lens.cy * f.height).toInt()
                            val side = (f.height * 0.55).toInt()
                            val x0 = (cx - side / 2).coerceIn(0, half - side); val y0 = (cy - side / 2).coerceIn(0, f.height - side)
                            android.graphics.Bitmap.createScaledBitmap(android.graphics.Bitmap.createBitmap(f, x0, y0, side, side), 96, 96, true)
                        }
                    }
                } finally { r.release() }
            }.getOrDefault(emptyList())
            runOnUiThread { filmstrip = frames; timelineView?.filmstrip = frames }
        }.start()
    }

    private fun captureThumb() {
        val p = project ?: return
        player?.captureThumbnail(384, 216) { bmp -> runCatching { store.saveThumbnail(p.id, bmp) } }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        saveHandler.removeCallbacks(saveTask)
        saveNow()
        captureThumb()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoEditor?.cancelled = true
        player?.release()
        musicPreview?.release()
    }

    companion object {
        const val CANCELLED = "İptal edildi"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_LRV = "lrv"
        const val EXTRA_MORE = "more"
        const val EXTRA_VIEWER = "viewer"
    }
}

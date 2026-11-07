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
    /** Test için: klip açıldı ve düzenleyici hazır. */
    val isLoadedForTest get() = loaded && loadError == null

    /** Oynatıcı sayfası (albümden açılınca) ya da düzenleyici. */
    private var viewer by mutableStateOf(false)
    private var cameFromViewer = false
    /** Kameradan kablosuz önizleme (indirilmemiş çekim): yalnızca bakılır, kaydedilmez. */
    private var previewOnly = false
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

    /**
     * Oto Düzenle: projedeki her çekimi çözümle, kişileri takip eden kadraj ve en iyi anları seç,
     * hepsini geçişlerle tek kurguda birleştir (toplam ~[targetSec] sn).
     */
    // ---- Yapay zekâ ile düzenleme ----

    /** Klip başına tarama önbelleği (takip isteklerinde yeniden taranmaz). */
    private val aiLogState = mutableStateOf<List<EditorController.AiMessage>>(emptyList())
    private val aiScans = HashMap<String, com.mhmmtbg.x5reframe.ai.SceneContext.ClipScan>()
    private var aiUndo: Pair<List<com.mhmmtbg.x5reframe.project.Segment>, com.mhmmtbg.x5reframe.project.ProjectAudio>? = null
    @Volatile private var aiCancelled = false
    /** Son istekler (bağlam için). */
    private val aiHistory = ArrayList<Pair<String, String>>()

    private fun refreshAiState(c: EditorController) {
        val p = com.mhmmtbg.x5reframe.ai.AiSettings.provider(this)
        c.aiProvider = "${p.label} · ${com.mhmmtbg.x5reframe.ai.AiSettings.model(this, p)}"
        c.aiReady = com.mhmmtbg.x5reframe.ai.AiSettings.hasKey(this, p)
        c.aiNeedsConsent = !com.mhmmtbg.x5reframe.ai.AiSettings.consented(this, p)
        c.aiCanUndo = aiUndo != null
    }

    private fun aiSend(c: EditorController, request: String) {
        if (c.aiBusy != null || request.isBlank()) return
        val provider = com.mhmmtbg.x5reframe.ai.AiSettings.provider(this)
        val key = com.mhmmtbg.x5reframe.ai.AiSettings.key(this, provider)
        if (key == null) { c.aiAppend(EditorController.AiMessage(false, "Önce Ayarlar > Yapay zekâ bölümünden ${provider.label} API anahtarını ekle.", true)); return }
        val model = com.mhmmtbg.x5reframe.ai.AiSettings.model(this, provider)
        val review = com.mhmmtbg.x5reframe.ai.AiSettings.review(this)
        storeCurrentEdit()
        player?.pause()
        val segs = segments.toList()
        if (segs.isEmpty()) return
        // Kaynak klipler (proje sırasıyla, tekrarsız)
        val srcSegs = segs.distinctBy { it.clipUri.toString() }
        val srcIndex = srcSegs.withIndex().associate { (i, sg) -> sg.clipUri.toString() to i }
        val firstEdit = srcSegs.map { sg -> sg.edit }
        val audio = projectAudio
        c.aiAppend(EditorController.AiMessage(true, request))
        c.aiBusy = "Sahne hazırlanıyor…"
        aiCancelled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread({
            try {
                // 1) Tarama + kareler (önbellekli)
                val rowsPer = (20 / srcSegs.size).coerceIn(3, 10)
                val scans = srcSegs.mapIndexed { i, sg ->
                    aiScans.getOrPut(sg.clipUri.toString()) {
                        val clip = clipCache[sg.clipUri.toString()] ?: ClipInfo.load(this, sg.clipUri, sg.lrvUri).also { clipCache[sg.clipUri.toString()] = it }
                        val ed = com.mhmmtbg.x5reframe.auto.AutoEditor(applicationContext, clip, autoStab(clip, 0) ?: { null })
                        autoEditor = ed
                        val samples = ed.scan(0, clip.durationUs, stepUs = 1_000_000L) { p ->
                            runOnUiThread { c.aiBusy = "Klip ${i + 1}/${srcSegs.size} çözümleniyor… %${(p * 100).toInt()}" }
                        }
                        val times = com.mhmmtbg.x5reframe.ai.SceneContext.timesFor(0, clip.durationUs, rowsPer)
                        val frames = ed.renderViews(times, com.mhmmtbg.x5reframe.ai.SceneContext.views(),
                            com.mhmmtbg.x5reframe.ai.SceneContext.TILE_W, com.mhmmtbg.x5reframe.ai.SceneContext.TILE_H)
                        autoEditor = null
                        if (ed.cancelled || aiCancelled) throw InterruptedException()
                        com.mhmmtbg.x5reframe.ai.SceneContext.ClipScan(i, sg.clipName, clip.durationUs, samples, times.zip(frames).filter { it.second.isNotEmpty() })
                    }.let { com.mhmmtbg.x5reframe.ai.SceneContext.ClipScan(i, it.name, it.durationUs, it.samples, it.frames) }
                }
                val current = com.mhmmtbg.x5reframe.ai.PlanApplier.describe(segs.map { Triple(srcIndex[it.clipUri.toString()] ?: 0, it.edit, it.speed) })
                val ctx = com.mhmmtbg.x5reframe.ai.SceneContext.build(scans, current, audio.musicUri != null)
                val user = buildString {
                    if (aiHistory.isNotEmpty()) {
                        append("Önceki istekler ve yanıtların:\n")
                        aiHistory.takeLast(4).forEach { (q, a) -> append("- İstek: ").append(q).append("\n  Yanıt: ").append(a).append("\n") }
                        append("\n")
                    }
                    append("Kullanıcının isteği: ").append(request).append("\n\n")
                    append("Mevcut oran: ").append(firstEdit.first().aspect.label).append("\n")
                    append("Sahne verisi (JSON):\n").append(ctx.json.toString())
                }
                if (aiCancelled) throw InterruptedException()
                runOnUiThread { c.aiBusy = "${provider.label} kurguyu planlıyor… (${ctx.images.size} görsel)" }
                // 2) Plan
                var reply = com.mhmmtbg.x5reframe.ai.AiClient.request(provider, key, model, com.mhmmtbg.x5reframe.ai.Prompt.SYSTEM, user, ctx.images)
                var plan = com.mhmmtbg.x5reframe.ai.EditPlan.parse(reply.json)
                var usage = reply.usage
                val sources = srcSegs.mapIndexed { i, sg ->
                    com.mhmmtbg.x5reframe.ai.PlanSource(sg.durationUs, firstEdit[i].copy(inUs = 0, outUs = sg.durationUs), clipCache[sg.clipUri.toString()]?.imu != null)
                }
                var (applied, warns) = com.mhmmtbg.x5reframe.ai.PlanApplier.apply(plan, sources)
                if (applied.isEmpty()) throw com.mhmmtbg.x5reframe.ai.AiException(warns.joinToString("; ").ifBlank { plan.explanation.ifBlank { "plan boş" } })
                // 3) Kontrol turu: planlanan görünümlerle kareler alıp modele göster
                if (review && !aiCancelled) {
                    runOnUiThread { c.aiBusy = "${provider.label} sonucu kontrol ediyor…" }
                    val tiles = ArrayList<Pair<String, android.graphics.Bitmap>>()
                    applied.withIndex().groupBy { it.value.source }.forEach { (src, list) ->
                        val sg = srcSegs[src]
                        val clip = clipCache[sg.clipUri.toString()] ?: return@forEach
                        val ed = com.mhmmtbg.x5reframe.auto.AutoEditor(applicationContext, clip, autoStab(clip, 0) ?: { null })
                        autoEditor = ed
                        for ((k, a) in list) {
                            val t = (a.edit.inUs + a.edit.outUs) / 2
                            val v = a.edit.viewAt(t).let { if (it.projection == com.mhmmtbg.x5reframe.model.Projection.EQUIRECT) it.copy(projection = com.mhmmtbg.x5reframe.model.Projection.WIDE, fov = 200.0) else it }
                            val bm = ed.renderViews(listOf(t), listOf(v), 320, 240).firstOrNull()?.firstOrNull() ?: continue
                            tiles += "#${k + 1} t=${"%.1f".format(t / 1e6)}" to bm
                        }
                        autoEditor = null
                    }
                    if (tiles.isNotEmpty() && !aiCancelled) {
                        val sorted = tiles.sortedBy { it.first.substringAfter('#').substringBefore(' ').toIntOrNull() ?: 0 }
                        val sheet = com.mhmmtbg.x5reframe.ai.SceneContext.reviewSheet(sorted)
                        val reviewUser = com.mhmmtbg.x5reframe.ai.Prompt.REVIEW + "\n\nKullanıcının isteği: " + request +
                            "\n\nUyguladığın plan:\n" + plan.toJson().toString() + "\n\nSahne verisi (JSON):\n" + ctx.json.toString()
                        runCatching {
                            val r2 = com.mhmmtbg.x5reframe.ai.AiClient.request(provider, key, model, com.mhmmtbg.x5reframe.ai.Prompt.SYSTEM, reviewUser, listOf(sheet))
                            val p2 = com.mhmmtbg.x5reframe.ai.EditPlan.parse(r2.json)
                            val (a2, w2) = com.mhmmtbg.x5reframe.ai.PlanApplier.apply(p2, sources)
                            if (a2.isNotEmpty()) { plan = p2; applied = a2; warns = w2 }
                            usage = "$usage; kontrol: ${r2.usage}"
                        }
                    }
                }
                if (aiCancelled) throw InterruptedException()
                val newSegs = applied.map { a ->
                    val sg = srcSegs[a.source]
                    store.newSegment(sg.clipUri, sg.clipName, sg.durationUs, sg.lrvUri, a.edit).copy(
                        speed = a.speed,
                        transitionOut = if (a.fade) com.mhmmtbg.x5reframe.project.Transition.FADE else com.mhmmtbg.x5reframe.project.Transition.CUT,
                    )
                }
                val newAudio = audio.copy(
                    originalVolume = plan.originalVolume?.toFloat()?.coerceIn(0f, 1.5f) ?: audio.originalVolume,
                    musicVolume = plan.musicVolume?.toFloat()?.coerceIn(0f, 1.5f) ?: audio.musicVolume,
                )
                runOnUiThread {
                    aiUndo = segs to audio
                    if (newAudio != audio) setAudio(newAudio)
                    viewer = false
                    updateSegments(newSegs, 0, reopen = true)
                    val total = newSegs.sumOf { it.outputDurationUs } / 1e6
                    val note = buildString {
                        append(plan.explanation.ifBlank { "Kurgu uygulandı." })
                        append("\n${newSegs.size} çekim · ${"%.0f".format(total)} sn")
                        if (warns.isNotEmpty()) append("\nUyarı: ").append(warns.joinToString("; "))
                        if (usage.isNotBlank()) append("\n").append(usage)
                    }
                    aiHistory += request to plan.explanation
                    controller?.let { nc ->
                        nc.aiAppend(EditorController.AiMessage(false, note))
                        nc.aiBusy = null
                        refreshAiState(nc)
                        nc.say("Yapay zekâ kurgusu uygulandı")
                    }
                    c.aiBusy = null
                }
            } catch (e: InterruptedException) {
                runOnUiThread { c.aiBusy = null; c.aiAppend(EditorController.AiMessage(false, "Durduruldu", true)) }
            } catch (e: Throwable) {
                runOnUiThread {
                    c.aiBusy = null
                    c.aiAppend(EditorController.AiMessage(false, "Hata: ${e.message ?: e.javaClass.simpleName}", true))
                }
            } finally {
                autoEditor = null
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }, "ai-edit").start()
    }

    private fun aiUndoLast() {
        val (segs, audio) = aiUndo ?: return
        aiUndo = null
        if (audio != projectAudio) setAudio(audio)
        updateSegments(segs, 0, reopen = true)
        controller?.let { it.aiAppend(EditorController.AiMessage(false, "Önceki kurguya dönüldü")); refreshAiState(it) }
    }

    /** Oto düzenleme ve akıllı kadrajda kullanılan sabitleme: ufuk düz, yön kameranın gidişini izler. */
    private fun autoStab(clip: ClipInfo, inUs: Long): ((Long) -> DoubleArray?)? {
        val imu = clip.imu ?: return null
        val st = runCatching { com.mhmmtbg.x5reframe.stab.Stabilizer(imu, com.mhmmtbg.x5reframe.stab.Stabilizer.rigFromImuFor(clip.cameraType)) }.getOrNull() ?: return null
        return { t -> st.base(t / 1e6, com.mhmmtbg.x5reframe.stab.StabMode.HORIZON, AUTO_STAB_STRENGTH, inUs / 1e6) }
    }

    /** Müzik varsa vuruş aralığı (sn). */
    private fun musicBeat(): Double? {
        val uri = projectAudio.musicUri ?: return null
        return runCatching {
            val r = com.mhmmtbg.x5reframe.media.PcmReader(this, uri)
            val hz = 100.0
            val per = (com.mhmmtbg.x5reframe.media.MIX_RATE / hz).toInt()
            val buf = ShortArray(per * 2)
            val env = FloatArray(6000)
            var n = 0
            while (n < env.size && !r.eos) {
                r.read(per, buf, 1f, false)
                var e = 0.0
                for (k in 0 until per * 2) { val v = buf[k] / 32768.0; e += v * v }
                env[n++] = e.toFloat()
            }
            r.close()
            com.mhmmtbg.x5reframe.auto.AutoDirector.beatPeriod(env.copyOf(n), hz)
        }.getOrNull()
    }

    /**
     * Oto Düzenle: projedeki her çekimi sabitlenmiş görünümde çözümle; çekimi yapan kişiyi, diğer kişileri,
     * sesi ve hareketi değerlendirip kısa çekimlerden (ileri, selfie, kişi, panorama; ilkinde gezegen açılışı)
     * bir kurgu oluştur. Müzik eklenmişse kesmeler vuruşlara oturur.
     */
    private fun autoEditProject(targetSec: Int = 30) {
        val c = controller ?: return
        if (autoEditor != null) return
        storeCurrentEdit()
        val segs = segments.toList()
        if (segs.isEmpty()) return
        player?.pause()
        c.autoTask = "Oto düzenleme hazırlanıyor…" to 0f
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread({
            val out = ArrayList<com.mhmmtbg.x5reframe.project.Segment>()
            val perClipUs = (targetSec * 1_000_000L / segs.size).coerceAtLeast(4_000_000L)
            val beat = musicBeat()
            // Çekim süresi: ~3 sn; müzik varsa 4'ün katı vuruş
            val shotUs = if (beat != null) {
                val beats = (Math.round(3.0 / beat / 4.0) * 4).coerceAtLeast(4)
                (beats * beat * 1e6).toLong()
            } else 3_000_000L
            var cancelled = false
            var lastType: com.mhmmtbg.x5reframe.auto.AutoDirector.ShotType? = null
            runCatching {
                segs.forEachIndexed { i, seg ->
                    if (cancelled) { out += seg; return@forEachIndexed }
                    val clip = clipCache[seg.clipUri.toString()] ?: ClipInfo.load(this, seg.clipUri, seg.lrvUri).also { clipCache[seg.clipUri.toString()] = it }
                    val stab = autoStab(clip, seg.edit.inUs)
                    val ed = com.mhmmtbg.x5reframe.auto.AutoEditor(applicationContext, clip, stab ?: { null })
                    autoEditor = ed
                    val samples = ed.scan(seg.edit.inUs, seg.edit.outUs) { p ->
                        runOnUiThread { c.autoTask = "Çekim ${i + 1}/${segs.size} çözümleniyor…" to ((i + p) / segs.size) }
                    }
                    if (ed.cancelled) { cancelled = true; out += seg; return@forEachIndexed }
                    val shots = com.mhmmtbg.x5reframe.auto.AutoDirector.compile(samples, shotUs, perClipUs, first = i == 0, lastType = lastType)
                    val base = seg.edit.copy(
                        stabMode = if (stab != null) com.mhmmtbg.x5reframe.stab.StabMode.HORIZON else seg.edit.stabMode,
                        stabStrength = if (stab != null) AUTO_STAB_STRENGTH else seg.edit.stabStrength,
                        layout = com.mhmmtbg.x5reframe.model.Layout.SINGLE, extraPanes = emptyList(), tracks = emptyList(),
                    )
                    if (shots.isEmpty()) { out += seg; return@forEachIndexed }
                    shots.forEach { sh ->
                        val e = base.copy(
                            inUs = sh.range.first.coerceAtLeast(seg.edit.inUs), outUs = sh.range.last.coerceAtMost(seg.edit.outUs),
                            keyframes = sh.keys,
                        )
                        out += store.newSegment(seg.clipUri, seg.clipName, seg.durationUs, seg.lrvUri, e)
                            .copy(transitionOut = com.mhmmtbg.x5reframe.project.Transition.CUT)
                    }
                    lastType = shots.last().type
                }
            }.onFailure { e -> runOnUiThread { c.say("Oto düzenleme hatası: ${e.message}", false) } }
            runOnUiThread {
                autoEditor = null
                c.autoTask = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (out.isNotEmpty() && out != segs && !cancelled) {
                    // Son çekim siyaha kararır
                    val list = out.mapIndexed { k, sg -> if (k == out.size - 1) sg.copy(transitionOut = com.mhmmtbg.x5reframe.project.Transition.FADE) else sg }
                    val totalSec = list.sumOf { (it.edit.outUs - it.edit.inUs) / 1e6 }
                    viewer = false
                    updateSegments(list, 0, reopen = true)
                    controller?.say("Oto düzenleme: ${list.size} çekim · ${"%.0f".format(totalSec)} sn" + if (beat != null) " · müziğe göre" else "")
                } else if (cancelled) c.say("Durduruldu", false)
            }
        }, "auto-edit-all").start()
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

    /** .cube LUT içe aktar: doğrulanıp uygulama klasörüne kopyalanır ve hemen uygulanır. */
    private val lutPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val c = controller ?: return@registerForActivityResult
        Thread {
            val res = runCatching {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val lut = com.mhmmtbg.x5reframe.model.CubeLut.parse(text)
                val name = displayName(uri).substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(40).ifBlank { "lut" }
                val dir = java.io.File(filesDir, "luts").apply { mkdirs() }
                val f = java.io.File(dir, "$name.cube")
                f.writeText(text)
                f to lut.size
            }
            runOnUiThread {
                res.onSuccess { (f, n) ->
                    c.setColor(c.state.color.copy(lut = f.absolutePath, lutStrength = 1f))
                    c.say("LUT eklendi: ${f.nameWithoutExtension} (${n}³)")
                }.onFailure { c.say("LUT okunamadı: ${it.message}", false) }
            }
        }.start()
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
        previewOnly = intent.getStringExtra(EXTRA_PREVIEW_META) != null
        // Kablosuz önizleme: proje kaydedilmez
        if (previewOnly) store.readOnly = true
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
            val reuse = if (existing == null && viewer && !previewOnly) withContext(Dispatchers.IO) {
                store.list().firstOrNull { it.segments.size == 1 && it.segments[0].clipUri == newUri }
            } else null
            val proj = existing ?: reuse ?: run {
                // LRV: seçimle geldiyse onu, gelmediyse kaydedilmiş medya klasöründe ada göre ara.
                val lrvUri = intent.getStringExtra(EXTRA_LRV)?.let(Uri::parse) ?: withContext(Dispatchers.IO) {
                    com.mhmmtbg.x5reframe.project.LrvFinder.findInTree(
                        this@EditorActivity, com.mhmmtbg.x5reframe.project.LrvFinder.savedFolder(this@EditorActivity), displayName(newUri!!),
                    )
                }
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val meta = intent.getStringExtra(EXTRA_PREVIEW_META)?.let(Uri::parse)
                        if (meta != null) ClipInfo.forPreview(this@EditorActivity, meta, newUri!!) else ClipInfo.load(this@EditorActivity, newUri!!, lrvUri)
                    }
                }
                val clip = r.getOrElse { loadError = it.message ?: it.javaClass.simpleName; return@launch }
                clipCache[newUri.toString()] = clip
                withContext(Dispatchers.IO) {
                    var p = store.create(newUri!!, displayName(newUri), clip.durationUs, clip.lrv?.uri, defaultEdit(clip, null))
                    // Albümde birden fazla çekim seçildiyse arkaya ekle
                    val more = intent.getStringArrayExtra(EXTRA_MORE).orEmpty().map(Uri::parse)
                    if (more.isNotEmpty()) {
                        val folder = com.mhmmtbg.x5reframe.project.LrvFinder.savedFolder(this@EditorActivity)
                        val extra = more.mapNotNull { u ->
                            val lrv = com.mhmmtbg.x5reframe.project.LrvFinder.findInTree(this@EditorActivity, folder, displayName(u))
                            runCatching { ClipInfo.load(this@EditorActivity, u, lrv) }.getOrNull()?.let { ci ->
                                clipCache[u.toString()] = ci
                                store.newSegment(u, displayName(u), ci.durationUs, ci.lrv?.uri, defaultEdit(ci, p.segments.first().edit))
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
            if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
                intent.removeExtra(EXTRA_AUTO)
                // Oynatıcı hazır olunca başlat
                saveHandler.postDelayed({ autoEditProject(30) }, 1200)
            }
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
                autoPlayNext = false; autoAdvancing = false
                loadError = (it.message ?: it.javaClass.simpleName) + "\n\nKlip taşınmış ya da silinmiş olabilir."
                return@launch
            }
            clipCache[seg.clipUri.toString()] = clip
            val c = EditorController(clip, proj.name, seg.edit)
            val p = Player(this@EditorActivity, clip, this@EditorActivity)
            p.sceneProvider = { t -> c.sceneAt(t) }
            p.layoutProvider = { c.snapshotState.layout.rects }
            p.baseProvider = { t -> c.baseAt(t) }
            p.colorProvider = { if (c.compareHold) null else c.colorUniforms }
            p.lookProvider = { c.snapshotState.flatLook }
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
            buildWaveform(c)
            segIndex = i
            c.aiLogState = aiLogState
            refreshAiState(c)
            controller = c
            player = p
            surface?.let { sf -> if (sf.isValid) p.attachSurface(sf, surfW, surfH) }
            // Parça giriş noktasından başlar; proje oynatılıyorsa kesintisiz devam eder.
            if (seg.edit.inUs > 0) { c.positionUs = seg.edit.inUs; timelineView?.positionUs = seg.edit.inUs; p.seekTo(seg.edit.inUs) }
            if (autoPlayNext) { autoPlayNext = false; p.play() }
            autoAdvancing = false
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

    /**
     * Yeni parçanın başlangıç düzenlemesi. Düz video / fotoğrafta sabitleme kapalı, fotoğraf 4 sn.
     * Projede başka parça varsa çıktı biçimi (en/boy, kalite) ondan alınır; yoksa klibe en yakın oran seçilir.
     */
    private fun defaultEdit(clip: ClipInfo, projectEdit: com.mhmmtbg.x5reframe.model.EditState?): com.mhmmtbg.x5reframe.model.EditState {
        val out = if (clip.isImage) ClipInfo.IMAGE_DEFAULT_US else clip.durationUs
        var e = com.mhmmtbg.x5reframe.model.EditState(outUs = out)
        if (!clip.is360) {
            e = e.copy(stabMode = com.mhmmtbg.x5reframe.stab.StabMode.OFF,
                aspect = com.mhmmtbg.x5reframe.model.Aspect.values().filter { it != com.mhmmtbg.x5reframe.model.Aspect.PANO }
                    .minBy { kotlin.math.abs(kotlin.math.ln(it.w.toFloat() / it.h / clip.displayAspect)) })
        }
        if (projectEdit != null) e = e.copy(aspect = projectEdit.aspect, quality = projectEdit.quality, hevc = projectEdit.hevc, bitrate = projectEdit.bitrate)
        return e
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
            val seg = store.newSegment(uri, displayName(uri), clip.durationUs, clip.lrv?.uri, defaultEdit(clip, proj.segments.firstOrNull()?.edit))
            val list = proj.segments + seg
            updateSegments(list, list.size - 1, reopen = true)
        }
    }

    private fun sequenceUi() = com.mhmmtbg.x5reframe.ui.SequenceUi(
        segments = segments,
        current = segIndex,
        select = { i -> if (i != segIndex) openSegment(i) },
        add = { addClipPicker.launch(arrayOf("video/*", "image/*", "application/octet-stream", "*/*")) },
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
            controller?.say("Hız ${sp}×")
        },
        updateSegment = { i, f ->
            storeCurrentEdit()
            val list = segments.toMutableList().apply { set(i, f(get(i))) }
            updateSegments(list, segIndex, reopen = false)
        },
        freeze = { t -> freezeFrame(t) },
        applyColorToAll = { g ->
            storeCurrentEdit()
            val list = segments.map { it.copy(edit = it.edit.copy(color = g)) }
            updateSegments(list, segIndex, reopen = false)
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

    private fun startDeepTrack(c: EditorController, from: Long, view: com.mhmmtbg.x5reframe.model.ViewParams, box: com.mhmmtbg.x5reframe.auto.AutoEditor.Box) {
        c.trackSelecting = false
        c.trackCandidates = emptyList()
        c.trackResume = null
        val to = c.state.outUs
        runAuto(c) { ed ->
            val a = c.state.aspect
            runOnUiThread { c.tracking = true; c.trackLive = box }
            val r = try { ed.deepTrack(from, to, view, box, a.w, a.h, { p, t ->
                runOnUiThread { c.autoTask = "Takip ediliyor… ${EditorController.fmt(t)}" to p }
            }) { t, v, b ->
                // Canlı önizleme: takipçinin gördüğü kare ve hedef kutusu
                runOnUiThread {
                    if (c.tracking) {
                        c.trackLive = b
                        c.setLive(v)
                        c.positionUs = t
                        timelineView?.positionUs = t
                        player?.seekTo(t)
                    }
                }
            } } catch (e: Throwable) { runOnUiThread { c.tracking = false; c.trackLive = null; c.setLive(null) }; throw e }
            runOnUiThread {
                c.tracking = false
                c.trackLive = null
                c.applyTrack(from, r.keys)
                val dur = EditorController.fmt(r.endUs - from)
                c.trackResume = if (!r.lost && r.lastBox != null && r.endUs < to - 200_000) r.endUs to r.lastBox else null
                when {
                    r.keys.size < 2 -> c.say("Hedef takip edilemedi", false)
                    r.lost -> c.say("Hedef kayboldu · $dur takip edildi", false)
                    ed.cancelled -> c.say("Takip duraklatıldı · $dur")
                    else -> c.say("Takip tamam · $dur")
                }
                c.trackTarget = null
                // Sonucu göster: takip edilen aralığı baştan oynat
                if (r.keys.size >= 2) {
                    c.positionUs = from
                    player?.seekTo(from)
                    saveHandler.postDelayed({ player?.play() }, 400)
                }
            }
        }
    }

    /** Klip başına bulunan "çekimi yapan kişi" yönü (sabitlenmiş çerçevede). */
    private val holderYaw = HashMap<String, Double?>()

    private fun orient(c: EditorController, selfie: Boolean) {
        fun apply(h: Double?) {
            val yaw = when {
                h == null -> if (selfie) 180.0 else 0.0
                selfie -> h
                else -> com.mhmmtbg.x5reframe.model.ViewParams.wrap(h + 180.0)
            }
            c.changeView { it.copy(yaw = yaw, pitch = if (selfie) -8.0 else -4.0, roll = 0.0, fov = if (selfie) 110.0 else 100.0,
                projection = com.mhmmtbg.x5reframe.model.Projection.SUPERWIDE) }
            c.say(when {
                selfie && h != null -> "Selfie: çekimi yapan kişi"
                selfie -> "Selfie: kişi bulunamadı, arka lens yönü"
                h != null -> "İleri: gidiş yönü"
                else -> "İleri: kameranın ön yönü"
            })
        }
        val key = c.clip.uri.toString()
        if (holderYaw.containsKey(key)) { apply(holderYaw[key]); return }
        runAuto(c) { ed ->
            val st = c.state
            val samples = ed.scan(st.inUs, st.outUs, stepUs = 1_000_000L) { p -> runOnUiThread { c.autoTask = "Çekimi yapan kişi aranıyor…" to p } }
            val track = com.mhmmtbg.x5reframe.auto.AutoDirector.holderTrack(samples)
            val h = track?.let { t ->
                // Açısal ortalama
                val sx = t.sumOf { kotlin.math.sin(Math.toRadians(it)) }; val cx = t.sumOf { kotlin.math.cos(Math.toRadians(it)) }
                Math.toDegrees(kotlin.math.atan2(sx, cx))
            }
            runOnUiThread { if (!ed.cancelled) { holderYaw[key] = h; apply(h) } }
        }
    }

    private fun showViewer() { viewer = true; editorStartTool = null }

    private fun viewerActions(c: EditorController) = com.mhmmtbg.x5reframe.ui.ViewerActions(
        editor = actions(c),
        openEditor = { tool -> player?.pause(); editorStartTool = tool; viewer = false },
        snapshot = { snapshot(c) },
        record = { toggleRecording(c) },
        highlights = { actions(c).autoHighlights(15) },
        autoEdit = { autoEditProject(30) },
        orient = { selfie -> orient(c, selfie) },
        isFavorite = { favorite },
        toggleFavorite = {
            val key = c.clip.uri.toString()
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            val set = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
            favorite = if (key in set) { set.remove(key); false } else { set.add(key); true }
            prefs.edit().putStringSet("favorites", set).apply()
            c.say(if (favorite) "Favorilere eklendi" else "Favorilerden çıkarıldı")
        },
        delete = if (!previewOnly && android.provider.DocumentsContract.isDocumentUri(this, c.clip.uri)) ({ deleteClip(c) }) else null,
        previewOnly = previewOnly,
        download = if (previewOnly) ({ setResult(RESULT_OK, Intent().putExtra(RESULT_DOWNLOAD, true)); finish() }) else null,
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

    /**
     * Kare dondurma: oynatma çizgisindeki kare, düzenlemeleriyle birlikte çıktı oranında bir fotoğrafa çizilir
     * ve parça o noktadan bölünerek araya 2 sn'lik fotoğraf parçası olarak eklenir.
     */
    private fun freezeFrame(t: Long) {
        val c = controller ?: return
        player?.pause()
        storeCurrentEdit()
        val st = c.state
        if (t <= st.inUs + 100_000 || t >= st.outUs - 100_000) { c.say("Dondurmak için oynatma çizgisini parçanın içine getir", false); return }
        val (w, h) = st.outputSize()
        val views = c.sceneAt(t); val base = c.baseAt(t); val color = c.colorUniforms
        val dyn = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dynamic_stitch", true)
        c.say("Kare donduruluyor…")
        Thread({
            val file = runCatching {
                contentResolver.openFileDescriptor(c.clip.uri, "r")!!.use { pfd ->
                    val bmp = com.mhmmtbg.x5reframe.photo.StillRenderer.render(c.clip.rig, w, h, st.layout.rects, views, base, color, dyn, c.clip.displayAspect, st.flatLook) {
                        com.mhmmtbg.x5reframe.media.Sources.open(this, c.clip, pfd.fileDescriptor, realtime = false).also { it.seekAndShow(t, (1_000_000 / c.clip.frameRate).toLong()) }
                    }
                    val dir = java.io.File(filesDir, "freeze").apply { mkdirs() }
                    java.io.File(dir, "freeze_${System.currentTimeMillis()}.jpg").also { f -> f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }; bmp.recycle() }
                }
            }.getOrNull()
            runOnUiThread {
                if (file == null) { c.say("Kare dondurulamadı", false); return@runOnUiThread }
                val i = segIndex
                val seg = segments[i]
                val uri = Uri.fromFile(file)
                val a = seg.copy(edit = seg.edit.copy(outUs = t), transitionOut = com.mhmmtbg.x5reframe.project.Transition.CUT)
                val still = store.newSegment(uri, "Dondurulmuş kare", ClipInfo.IMAGE_MAX_US, null,
                    com.mhmmtbg.x5reframe.model.EditState(outUs = 2_000_000, aspect = st.aspect, quality = st.quality, hevc = st.hevc, bitrate = st.bitrate,
                        stabMode = com.mhmmtbg.x5reframe.stab.StabMode.OFF, fit = com.mhmmtbg.x5reframe.model.FrameFit.FILL))
                val b = store.newSegment(seg.clipUri, seg.clipName, seg.durationUs, seg.lrvUri, seg.edit.copy(inUs = t))
                    .copy(speed = seg.speed, transitionOut = seg.transitionOut, reverse = seg.reverse, curve = seg.curve)
                val list = segments.toMutableList().apply { set(i, a); add(i + 1, still); add(i + 2, b) }
                updateSegments(list, i + 1, reopen = true)
                controller?.say("Kare donduruldu · 2 sn")
            }
        }, "freeze").start()
    }

    /** O anki kareyi tam çözünürlüklü izlerden (dışa aktarım kalitesinde) fotoğraf olarak kaydet. */
    private fun snapshot(c: EditorController) {
        player?.pause()
        val t = c.positionUs
        val st = c.state
        val pano = c.currentView.projection == com.mhmmtbg.x5reframe.model.Projection.EQUIRECT
        val long = if (pano) 5760 else 3840
        val aw = st.aspect.w; val ah = st.aspect.h
        val w = (if (aw >= ah) long else long * aw / ah) / 2 * 2
        val h = (if (aw >= ah) long * ah / aw else long) / 2 * 2
        val views = c.sceneAt(t)
        val base = c.baseAt(t)
        val color = c.colorUniforms
        val dyn = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dynamic_stitch", true)
        c.say("Anlık görüntü hazırlanıyor…")
        Thread({
            val ok = runCatching {
                val pfd = contentResolver.openFileDescriptor(c.clip.uri, "r")!!
                pfd.use {
                    val bmp = com.mhmmtbg.x5reframe.photo.StillRenderer.render(c.clip.rig, w, h, st.layout.rects, views, base, color, dyn, c.clip.displayAspect, st.flatLook) {
                        com.mhmmtbg.x5reframe.media.Sources.open(this, c.clip, pfd.fileDescriptor, realtime = false).also {
                            it.seekAndShow(t, (1_000_000 / c.clip.frameRate).toLong())
                        }
                    }
                    com.mhmmtbg.x5reframe.photo.StillRenderer.save(this, bmp).also { bmp.recycle() } != null
                }
            }.getOrElse { false }
            runOnUiThread { c.say(if (ok) "Anlık görüntü kaydedildi · ${w}×${h} · Resimler/X5Reframe" else "Anlık görüntü kaydedilemedi", ok) }
        }, "snapshot").start()
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
        autoFrame = {
            // Akıllı kadraj tek çekimdir: sabitlenmiş (ufuk düz, gidişi izleyen) görünümde kişileri / ileri yönü izler.
            val s0 = c.state
            if (c.stabilizer != null && s0.stabMode != com.mhmmtbg.x5reframe.stab.StabMode.HORIZON)
                c.commit(s0.copy(stabMode = com.mhmmtbg.x5reframe.stab.StabMode.HORIZON, stabStrength = AUTO_STAB_STRENGTH))
            runAuto(c) { ed ->
                val st = c.state
                val samples = ed.scan(st.inUs, st.outUs) { p -> runOnUiThread { c.autoTask = "Sahne çözümleniyor…" to p } }
                val keys = com.mhmmtbg.x5reframe.auto.AutoDirector.singleTake(samples, c.currentView.fov.coerceIn(70.0, 100.0))
                val people = samples.count { it.faces.isNotEmpty() }
                runOnUiThread {
                    c.replaceKeyframes(st.inUs, st.outUs, keys)
                    c.say(if (people == 0) "Akıllı kadraj: kişi yok, ileri yön izleniyor" else "Akıllı kadraj: ${keys.size} anahtar kare")
                }
            }
        },
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
        aiSend = { text -> aiSend(c, text) },
        aiUndo = { aiUndoLast() },
        aiCancel = { aiCancelled = true; autoEditor?.cancelled = true },
        aiConsent = {
            com.mhmmtbg.x5reframe.ai.AiSettings.setConsented(this, com.mhmmtbg.x5reframe.ai.AiSettings.provider(this))
            refreshAiState(c)
        },
        aiSettings = {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 2).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        },
        suggestTargets = {
            c.trackTarget = null
            runAuto(c) { ed ->
                val a = c.state.aspect
                val boxes = ed.suggestTargets(c.positionUs, c.currentView, a.w, a.h)
                runOnUiThread {
                    c.trackCandidates = boxes
                    c.trackSelecting = true
                    c.say(if (boxes.isEmpty()) "Öneri yok: hedefin çevresine kutu çiz" else "${boxes.size} hedef önerildi")
                }
            }
        },
        deepTrack = { c.trackTarget?.let { startDeepTrack(c, c.positionUs, c.currentView, it) } },
        resumeTrack = { c.trackResume?.let { (t, box) -> c.positionUs = t; player?.seekTo(t); startDeepTrack(c, t, c.activeViewAt(t), box) } },
        requestHistogram = { player?.requestHistogram { h -> controller?.histogram = h } },
        redraw = { player?.requestRedraw() },
        importLut = { lutPicker.launch(arrayOf("*/*")) },
        lutFiles = { java.io.File(filesDir, "luts").listFiles { f -> f.extension.equals("cube", true) }.orEmpty().sortedBy { it.name.lowercase() } },
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
        // İki parmak: açı ve uzaklık (döndürme / büyütme için)
        var prevAngle = Float.NaN
        var prevDist = Float.NaN
        var rotAccum = 0f
        var rotActive = false
        fun twoFinger(e: MotionEvent): Pair<Float, Float>? {
            if (e.pointerCount < 2) return null
            val dx = e.getX(1) - e.getX(0); val dy = e.getY(1) - e.getY(0)
            return Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat() to kotlin.math.hypot(dx, dy)
        }
        fun angleDelta(a: Float, b: Float): Float { var d = a - b; while (d > 180) d -= 360; while (d < -180) d += 360; return d }
        sv.setOnTouchListener { v, e ->
            val c = controller ?: return@setOnTouchListener false
            taps.onTouchEvent(e)
            if (c.layerTool && c.currentLayer != null) {
                // Katman sekmesi: tek parmak taşır, iki parmak büyütür ve döndürür.
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (player?.playing == true) player?.pause()
                        prevAngle = Float.NaN; prevDist = Float.NaN
                        val nx = (e.x / v.width).coerceIn(0f, 1f); val ny = (e.y / v.height).coerceIn(0f, 1f)
                        c.updateLayerLive { it.copy(x = nx, y = ny) }
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> { twoFinger(e)?.let { prevAngle = it.first; prevDist = it.second } }
                    MotionEvent.ACTION_MOVE -> {
                        val tf = twoFinger(e)
                        if (tf != null && !prevAngle.isNaN()) {
                            val da = angleDelta(tf.first, prevAngle); val k = tf.second / prevDist.coerceAtLeast(1f)
                            c.updateLayerLive { it.copy(scale = (it.scale * k).coerceIn(0.03f, 1.5f), rotation = (it.rotation + da).let { r -> ((r + 540f) % 360f) - 180f }) }
                            prevAngle = tf.first; prevDist = tf.second
                        } else if (e.pointerCount == 1) {
                            val nx = (e.x / v.width).coerceIn(0f, 1f); val ny = (e.y / v.height).coerceIn(0f, 1f)
                            c.updateLayerLive { it.copy(x = nx, y = ny) }
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> { prevAngle = Float.NaN; prevDist = Float.NaN }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> c.endLayerGesture()
                }
                return@setOnTouchListener true
            }
            // İki parmakla döndürme (kadrajın eğimi)
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> twoFinger(e)?.let { prevAngle = it.first; rotAccum = 0f; rotActive = false }
                MotionEvent.ACTION_MOVE -> twoFinger(e)?.let { (ang, _) ->
                    if (!prevAngle.isNaN()) {
                        val da = angleDelta(ang, prevAngle).takeIf { kotlin.math.abs(it) < 30 } ?: 0f
                        rotAccum += da
                        // Yakınlaştırırken istemeden dönmesin: ancak ~8° çevrilince başlar
                        if (!rotActive && kotlin.math.abs(rotAccum) > 8f) { rotActive = true; c.updateView { it.copy(roll = it.roll - rotAccum) } }
                        else if (rotActive && da != 0f) c.updateView { it.copy(roll = it.roll - da) }
                    }
                    prevAngle = ang
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> prevAngle = Float.NaN
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
            override fun onLayerSelected(id: String) {
                val c = controller ?: return
                c.selectedLayer = id
                c.openToolRequest = "layer"
            }
            override fun onLayerTimeChanged(id: String, startUs: Long, endUs: Long, done: Boolean) {
                val c = controller ?: return
                c.selectedLayer = id
                c.updateLayerLive { it.copy(startUs = startUs, endUs = endUs) }
                if (done) { c.endLayerGesture(); c.say("Metin ${fmtSec(startUs)} – ${fmtSec(endUs)}") }
            }
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
    /** Proje boyunca oynatma: parçanın çıkış noktasına gelince sıradakine geç ve çalmaya devam et. */
    private var autoPlayNext = false
    private var autoAdvancing = false

    private fun maybeAdvance(c: EditorController, us: Long) {
        val p = player ?: return
        if (!p.playing || autoAdvancing || viewer || c.recording || c.tracking) return
        if (us >= c.state.outUs - 20_000 && us >= c.state.inUs && segIndex < segments.size - 1) {
            autoAdvancing = true
            autoPlayNext = true
            p.pause()
            openSegment(segIndex + 1)
        }
    }

    override fun onPosition(us: Long) {
        if (userScrubbing) return
        timelineView?.positionUs = us
        val c = controller ?: return
        maybeAdvance(c, us)
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

    /** Düz video / fotoğraf: kareleri doğrudan dosyadan, kare olarak ortadan kırpıp al. */
    private fun buildFlatFilmstrip(clip: ClipInfo) {
        Thread {
            fun square(f: android.graphics.Bitmap): android.graphics.Bitmap {
                val side = minOf(f.width, f.height)
                return android.graphics.Bitmap.createScaledBitmap(
                    android.graphics.Bitmap.createBitmap(f, (f.width - side) / 2, (f.height - side) / 2, side, side), 96, 96, true)
            }
            val frames = runCatching {
                if (clip.isImage) {
                    val b = com.mhmmtbg.x5reframe.insv.MediaProbe.decodeImage(this, clip.uri, 512)
                    val sq = square(b); b.recycle()
                    List(12) { sq }
                } else {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(this, clip.uri)
                        val n = 12
                        (0 until n).mapNotNull { i ->
                            val t = (clip.durationUs * (i + 0.5) / n).toLong()
                            (if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
                            else r.getFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC))?.let { f -> square(f).also { if (it !== f) f.recycle() } }
                        }
                    } finally { r.release() }
                }
            }.getOrDefault(emptyList())
            runOnUiThread { filmstrip = frames; timelineView?.filmstrip = frames }
        }.start()
    }

    private fun fmtSec(us: Long) = "%.1f sn".format(us / 1e6)

    /** Klip sesinin dalga biçimi (bir kez hesaplanır, önbellekte kalır). */
    private fun buildWaveform(c: EditorController) {
        val track = c.clip.audioTrack ?: run { c.waveform = null; return }
        com.mhmmtbg.x5reframe.media.Waveform.cached(c.clip.uri)?.let { c.waveform = it; return }
        Thread {
            val w = com.mhmmtbg.x5reframe.media.Waveform.compute(this, c.clip.uri, track)
            runOnUiThread { if (controller === c) c.waveform = w }
        }.start()
    }

    private fun buildFilmstrip(clip: ClipInfo) {
        if (!clip.is360) { buildFlatFilmstrip(clip); return }
        val lrv = clip.lrv ?: return
        Thread {
            val frames = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(this, lrv.uri)
                    val n = 12
                    val lens = clip.rig!!.lenses[0]
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

    override fun onResume() {
        super.onResume()
        controller?.let { refreshAiState(it) }
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
        const val EXTRA_AUTO = "auto"
        /** Kablosuz önizleme: .insv'nin son baytları (Insta360 verisi); data = önbellekteki LRV */
        const val EXTRA_PREVIEW_META = "preview_meta"
        /** Önizlemeden "Telefona aktar" denince sonuçta döner */
        const val RESULT_DOWNLOAD = "download"
        /** Oto düzenlemede yön takibinin yumuşaklığı (FlowState'e yakın, ~0,6 sn). */
        const val AUTO_STAB_STRENGTH = 0.6
    }
}

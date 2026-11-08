package com.mhmmtbg.x5reframe

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES30
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mhmmtbg.x5reframe.gl.EglCore
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Uygulamayı kullanıcı gibi gezer: her araca gerçekten dokunur, seçenekleri dener ve sonucun
 * düzenleme durumunda / dosyada gerçekleştiğini denetler. Her adımda ekran görüntüsü alınır.
 * Rapor İndirilenler/x5test/tour.txt'ye yazılır; tek bir adımın hatası diğerlerini durdurmaz.
 */
@RunWith(AndroidJUnit4::class)
class FeatureTourTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val report = StringBuilder()
    private val fails = ArrayList<String>()
    private var scenario: ActivityScenario<EditorActivity>? = null
    private var shotNo = 0

    // ---------- yardımcılar ----------

    private fun log(s: String) { report.appendLine(s); android.util.Log.i("FeatureTour", s) }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        log((if (ok) "✓ " else "✗ ") + name + if (detail.isNotEmpty()) "  [$detail]" else "")
        if (!ok) fails += "$name $detail"
    }

    private fun step(name: String, block: () -> Unit) {
        log("— $name")
        val r = runCatching(block)
        if (scenario != null) runCatching {
            val l = segs()
            log("  parçalar: ${l.size}, toplam ${"%.1f".format(l.sumOf { it.outputDurationUs } / 1e6)} sn, seçili ${ui { it.segIndexForTest }}: ${c().clip.kind}")
        }
        r.exceptionOrNull()?.let { e ->
            val msg = (e.message ?: e.javaClass.simpleName).lineSequence().take(3).joinToString(" | ")
            log("✗ $name: HATA $msg")
            fails += "$name: $msg"
        }
        shot(name)
    }

    private fun <T> ui(f: (EditorActivity) -> T): T {
        var out: T? = null
        scenario!!.onActivity { out = f(it) }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun c() = ui { it.controllerForTest!! }
    private fun st() = ui { it.controllerForTest!!.state }
    private fun seg() = ui { it.segmentsForTest[it.segIndexForTest] }
    private fun segs() = ui { it.segmentsForTest }

    private fun nodes(text: String, sub: Boolean) = rule.onAllNodesWithText(text, substring = sub, useUnmergedTree = true)

    private fun click(n: SemanticsNodeInteraction, text: String? = null, sub: Boolean = false) {
        // İç içe kaydırmalarda (panel dikey, satır yatay) önce dıştaki kaydırılır
        if (text != null) {
            val m = androidx.compose.ui.test.hasText(text, substring = sub)
            val scrollers = rule.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(m), useUnmergedTree = true)
            val k = scrollers.fetchSemanticsNodes().size
            for (i in 0 until k) runCatching { scrollers[i].performScrollToNode(m) }
        }
        runCatching { n.performScrollTo() }
        n.performClick()
        Thread.sleep(400)
    }

    /** Yazıya dokun; [last] = ağaçtaki son eşleşme (alttaki araç çubuğu en sonda çizilir). */
    private fun tap(text: String, sub: Boolean = false, last: Boolean = false) {
        val all = nodes(text, sub)
        val n = all.fetchSemanticsNodes().size
        if (n == 0) throw AssertionError("'$text' ekranda yok")
        click(if (last) all[n - 1] else all[0], text, sub)
    }

    private fun tapDesc(desc: String, sub: Boolean = false) {
        val all = rule.onAllNodesWithContentDescription(desc, substring = sub, useUnmergedTree = true)
        if (all.fetchSemanticsNodes().isEmpty()) throw AssertionError("'$desc' (açıklama) ekranda yok")
        click(all[0])
    }

    private fun exists(text: String, sub: Boolean = false) = nodes(text, sub).fetchSemanticsNodes().isNotEmpty()

    /** Tembel listelerde (LazyColumn) henüz çizilmemiş öğeye kaydır. */
    private fun scrollToText(text: String) {
        val m = androidx.compose.ui.test.hasText(text)
        val lists = rule.onAllNodes(androidx.compose.ui.test.hasScrollToIndexAction())
        for (i in 0 until lists.fetchSemanticsNodes().size) runCatching { lists[i].performScrollToNode(m) }
        Thread.sleep(400)
    }

    /** Alttaki araç çubuğundan aracı aç (zaten açıksa dokunulmaz). */
    private fun tool(label: String) { tap(label, last = true); Thread.sleep(500) }

    /** Açık aracı kapat (aynı araca tekrar dokununca kapanır). */
    private fun closeTool(label: String) { tap(label, last = true); Thread.sleep(300) }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (runCatching(cond).getOrDefault(false)) return true; Thread.sleep(250) }
        log("  (bekleme süresi doldu: $what)")
        return false
    }

    private fun seek(us: Long) { ui { it.playerForTest?.seekTo(us) }; Thread.sleep(700) }

    private fun shot(name: String) {
        val b = inst.uiAutomation.takeScreenshot() ?: return
        val file = "tour_%02d_%s".format(++shotNo, name.lowercase().replace(Regex("[^a-z0-9ğüşıöç]+"), "_").take(40))
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$file.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/x5test")
        }
        runCatching {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    private fun writeReport() {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "tour.txt")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/x5test")
        }
        runCatching {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(report.toString().toByteArray()) }
        }
    }

    private fun sh(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

    // ---------- test medyası ----------

    /**
     * 8 sn, 640×360, 30 kare/sn; hareketli desen (biraz sarsıntılı) ve ses: 0–3 sn ton, 3–5 sn sessizlik, 5–8 sn ton.
     */
    private fun makeAv(file: File) {
        val w = 640; val h = 360; val fps = 30; val secs = 8
        // --- ses (AAC) önceden kodlanır ---
        val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000); setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aEnc.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); aEnc.start()
        val aSamples = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
        var aOutFmt: MediaFormat? = null
        val total = 48_000 * secs
        var fed = 0; var inEos = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (!inEos) {
                val ii = aEnc.dequeueInputBuffer(5000)
                if (ii >= 0) {
                    val buf = aEnc.getInputBuffer(ii)!!
                    val n = minOf(1024, total - fed)
                    if (n <= 0) { aEnc.queueInputBuffer(ii, 0, 0, fed * 1_000_000L / 48_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                    else {
                        for (k in 0 until n) {
                            val i = fed + k; val t = i / 48_000.0
                            val on = t < 3.0 || t >= 5.0
                            val v = if (on) 9000 * sin(2 * PI * 330 * t) * (0.6 + 0.4 * sin(2 * PI * 4 * t)) else 0.0
                            buf.put((v.toInt() and 0xff).toByte()); buf.put(((v.toInt() shr 8) and 0xff).toByte())
                        }
                        aEnc.queueInputBuffer(ii, 0, n * 2, fed * 1_000_000L / 48_000, 0); fed += n
                    }
                }
            }
            val oi = aEnc.dequeueOutputBuffer(info, 5000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) aOutFmt = aEnc.outputFormat
            else if (oi >= 0) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val src = aEnc.getOutputBuffer(oi)!!
                    val copy = ByteBuffer.allocate(info.size); src.position(info.offset); src.limit(info.offset + info.size); copy.put(src); copy.flip()
                    aSamples += copy to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                }
                aEnc.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        aEnc.stop(); aEnc.release()
        // --- görüntü ---
        val vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = enc.createInputSurface(); enc.start()
        val egl = EglCore(); val win = egl.createWindowSurface(input); egl.makeCurrent(win)
        val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var vTrack = -1
        fun drain(end: Boolean) {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return else continue }
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = mux.addTrack(enc.outputFormat)
                    val at = mux.addTrack(aOutFmt!!)
                    mux.start()
                    aSamples.forEach { (b, inf) -> mux.writeSampleData(at, b, inf) }
                    continue
                }
                if (i < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && vTrack >= 0) mux.writeSampleData(vTrack, enc.getOutputBuffer(i)!!, info)
                enc.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        val rnd = java.util.Random(3)
        for (k in 0 until secs * fps) {
            val jx = if (k % 2 == 0) 0 else 5; val jy = if (k % 3 == 0) 0 else 3
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0.25f, 0.4f, 0.6f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            rnd.setSeed(3)
            for (q in 0 until 60) {
                val v = rnd.nextFloat()
                GLES30.glScissor(rnd.nextInt(w) + jx + k * 2 % w, rnd.nextInt(h) + jy, 10 + rnd.nextInt(30), 10 + rnd.nextInt(30))
                GLES30.glClearColor(v, 1f - v, 0.3f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            egl.setPresentationTime(win, k * 1_000_000_000L / fps); egl.swap(win); drain(false)
        }
        enc.signalEndOfInputStream(); drain(true)
        mux.stop(); mux.release(); enc.stop(); enc.release()
        egl.releaseSurface(win); input.release(); egl.release()
    }

    /** 120 BPM tıklamalı 20 sn müzik (WAV). */
    private fun makeMusic(file: File) {
        val n = 48_000 * 20
        val bb = ByteBuffer.allocate(44 + n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 4); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(2); bb.putInt(48_000); bb.putInt(48_000 * 4); bb.putShort(4); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 4)
        for (i in 0 until n) {
            val ph = i % 24_000
            val v = (if (ph < 600) 12_000 * sin(ph * 0.3) * (1 - ph / 600.0) else 1500 * sin(2 * PI * 220 * i / 48_000)).toInt().toShort()
            bb.putShort(v); bb.putShort(v)
        }
        file.writeBytes(bb.array())
    }

    private fun durationOf(uri: Uri): Long = runCatching {
        val mx = MediaExtractor()
        ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { mx.setDataSource(it.fileDescriptor) }
        val d = (0 until mx.trackCount).maxOf { mx.getTrackFormat(it).let { f -> if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L } }
        mx.release(); d
    }.getOrDefault(-1)

    // ---------- tur ----------

    @Test
    fun tour() {
        sh("wm size 1080x2340"); sh("wm density 420")
        // Sistem izin penceresi turu bölmesin
        sh("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        inst.runOnMainSync { com.mhmmtbg.x5reframe.ui.theme.ThemeMode.set(ctx, com.mhmmtbg.x5reframe.ui.theme.ThemeMode.DARK) }
        try {
            flatVideo()
            mainScreens()
            video360()
        } finally {
            sh("wm size reset"); sh("wm density reset")
            inst.runOnMainSync { com.mhmmtbg.x5reframe.ui.theme.ThemeMode.set(ctx, com.mhmmtbg.x5reframe.ui.theme.ThemeMode.SYSTEM) }
            log("\nSONUÇ: ${fails.size} sorun")
            fails.forEach { log("  - $it") }
            writeReport()
        }
        assertTrue("Turda ${fails.size} sorun:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    private fun flatVideo() {
        val vid = File(ctx.cacheDir, "tour.mp4").also { makeAv(it) }
        val music = File(ctx.cacheDir, "tour_music.wav").also { makeMusic(it) }
        scenario = ActivityScenario.launch(Intent(ctx, EditorActivity::class.java).setData(Uri.fromFile(vid)))
        try {
            step("Açılış") {
                check("düzenleyici açıldı", waitFor(30_000, "yükleme") { ui { it.isLoadedForTest } })
                Thread.sleep(2500)
                check("klip süresi ~8 sn", kotlin.math.abs(c().clip.durationUs - 8_000_000) < 300_000, "${c().clip.durationUs}")
                check("ses izi var", c().clip.audioTrack != null)
            }
            step("Oynat ve duraklat") {
                seek(0)
                tapDesc("Oynat"); Thread.sleep(2000); ui { it.playerForTest?.pause() }; Thread.sleep(500)
                val p = c().positionUs
                check("oynatma ilerledi (1×)", p in 1_200_000..3_000_000, "konum $p")
                check("duraklat düğmesi", exists("Oynat") || rule.onAllNodesWithContentDescription("Oynat", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            }
            step("Hız 2×") {
                tool("Hız")
                tap("2×")
                check("parça hızı 2×", seg().speed == 2.0, "${seg().speed}")
                seek(0)
                tapDesc("Oynat"); Thread.sleep(1500); ui { it.playerForTest?.pause() }; Thread.sleep(500)
                val p = c().positionUs
                check("önizleme 2× hızlı", p in 2_000_000..4_200_000, "1,5 sn'de konum $p")
                tap("1×")
            }
            step("Hız eğrisi ve ters") {
                tap("Kahraman")
                check("eğri Kahraman", seg().curve == com.mhmmtbg.x5reframe.model.SpeedCurve.HERO, "${seg().curve}")
                tap("Özel")
                check("eğri Özel", seg().curve == com.mhmmtbg.x5reframe.model.SpeedCurve.CUSTOM, "${seg().curve}")
                tap("Yok")
                tap("Ters", sub = true, last = true)
                check("ters açık", seg().reverse)
                tap("Ters", sub = true, last = true)
                check("ters kapalı", !seg().reverse)
                closeTool("Hız")
            }
            step("Kes") {
                tool("Kes")
                seek(1_000_000)
                tap("⟦ Başlangıç")
                check("başlangıç 1 sn", kotlin.math.abs(st().inUs - 1_000_000) < 100_000, "${st().inUs}")
                seek(st().outUs - 1_000_000)
                tap("Bitiş ⟧")
                check("bitiş kısaldı", st().outUs < c().clip.durationUs - 500_000, "${st().outUs}")
                tap("Kesmeyi sıfırla")
                check("kesme sıfırlandı", st().inUs == 0L)
                closeTool("Kes")
            }
            step("Böl") {
                val n = segs().size
                seek(1_500_000)
                tap("Böl")
                Thread.sleep(1500)
                check("parça bölündü", segs().size == n + 1, "${n} → ${segs().size}")
            }
            step("Geçiş") {
                tapDesc("Geçiş:", sub = true)
                Thread.sleep(800)
                tap("Çapraz geçiş")
                tap("Uygula")
                Thread.sleep(800)
                check("geçiş Çapraz", segs()[0].transitionOut == com.mhmmtbg.x5reframe.project.Transition.DISSOLVE, "${segs()[0].transitionOut}")
            }
            step("Anahtar kare") {
                seek(500_000)
                val n = st().keyframes.size
                tap("Anahtar kare ekle")
                check("anahtar kare eklendi", st().keyframes.size == n + 1, "${n} → ${st().keyframes.size}")
                tool("Anahtar kare")
                Thread.sleep(500)
                tap("Tüm anahtar kareleri temizle")
                check("anahtar kareler temizlendi", st().keyframes.isEmpty(), "${st().keyframes.size}")
                closeTool("Anahtar kare")
            }
            step("Görünüm") {
                tool("Görünüm")
                tap("9:16")
                check("oran 9:16", st().aspect == com.mhmmtbg.x5reframe.model.Aspect.PORTRAIT, "${st().aspect}")
                tap("Doldur")
                check("yerleşim Doldur", st().fit == com.mhmmtbg.x5reframe.model.FrameFit.FILL, "${st().fit}")
                tap("Bulanık arka plan")
                check("yerleşim Bulanık", st().fit == com.mhmmtbg.x5reframe.model.FrameFit.BLUR, "${st().fit}")
                tap("Ayna ↔")
                check("yatay ayna", st().flipH)
                tap("1:1", last = true)
                check("kırpma 1:1", st().crop != com.mhmmtbg.x5reframe.model.CropRect(), "${st().crop}")
                tap("Döndür 90°")
                check("döndürme", kotlin.math.abs(c().currentView.roll) > 1.0, "roll ${c().currentView.roll}")
                tap("Yeşil / mavi ekranı kaldır")
                check("yeşil ekran açık", st().chroma.on)
                tap("Yeşil / mavi ekranı kaldır")
                tap("Bulanık", last = true)
                check("kişi ayırma açık", st().personBg != null, "${st().personBg}")
                Thread.sleep(1500)
                shot("kişi ayırma")
                tap("Kapalı", last = true)
                check("kişi ayırma kapalı", st().personBg == null, "${st().personBg}")
                tap("Sıfırla", last = true)
                tap("16:9")
                tap("Sığdır")
                closeTool("Görünüm")
            }
            step("Renk") {
                tool("Renk")
                Thread.sleep(2000)
                tap("Canlı")
                check("filtre Canlı", st().color.filter == com.mhmmtbg.x5reframe.model.ColorGrade.Filter.VIVID, "${st().color.filter}")
                for (tab in listOf("Işık", "Efekt", "Eğri", "HSL")) { tap(tab); Thread.sleep(500); shot("renk $tab") }
                tap("Filtre")
                tap("Orijinal")
                closeTool("Renk")
            }
            step("Metin") {
                tool("Metin")
                val n = st().layers.size
                tap("+ Metin")
                Thread.sleep(600)
                rule.onAllNodes(hasSetTextAction())[0].performTextInput("Merhaba")
                tap("Tamam")
                Thread.sleep(600)
                check("metin katmanı eklendi", st().layers.size == n + 1 && st().layers.last().content == "Merhaba", "${st().layers.map { it.content }}")
                tap("Tırnaklı")
                check("yazı tipi", st().layers.lastOrNull()?.font == com.mhmmtbg.x5reframe.model.Layer.Font.SERIF, "${st().layers.lastOrNull()?.font}")
                tap("Büyü")
                check("giriş animasyonu", st().layers.lastOrNull()?.animIn == com.mhmmtbg.x5reframe.model.Layer.Anim.POP, "${st().layers.lastOrNull()?.animIn}")
            }
            step("Şablon ve çıkartma") {
                val n = st().layers.size
                // Seçim kalkınca şablonlar ve düğmeler görünür
                ui { it.controllerForTest!!.selectedLayer = null }; Thread.sleep(500)
                tap("Başlık")
                check("şablon katmanı", st().layers.size == n + 1, "${st().layers.size}")
                ui { it.controllerForTest!!.selectedLayer = null }; Thread.sleep(500)
                tap("Çıkartma")
                tap("🔥")
                check("çıkartma eklendi", st().layers.any { it.content == "🔥" })
                ui { it.controllerForTest!!.selectedLayer = null }; Thread.sleep(500)
                tap("Otomatik altyazı")
                Thread.sleep(600)
                check("altyazı seçenekleri", exists("OpenAI Whisper", sub = true))
                shot("altyazı penceresi")
                tap("Vazgeç")
                closeTool("Metin")
            }
            step("Ses") {
                tool("Ses")
                tap("Gürültü azaltma (rüzgâr, uğultu)")
                check("gürültü azaltma", ui { it.audioForTest.denoise })
                tap("0.5 sn")
                check("parça ses yükselmesi", seg().audioFadeInUs == 500_000L, "${seg().audioFadeInUs}")
                ui { it.setMusicForTest(Uri.fromFile(music)) }
                check("müzik vuruşları", waitFor(20_000, "vuruşlar") { c().beatMarks.isNotEmpty() }, "${c().beatMarks.size}")
                Thread.sleep(800)
                tap("Konuşmada müziği kıs")
                check("müziği kıs", ui { it.audioForTest.duck })
                val n = segs().size
                tap("4 vuruşta bir")
                Thread.sleep(1500)
                check("ritme göre böl", segs().size > n, "${n} → ${segs().size}")
                closeTool("Ses")
            }
            step("Sabitleme") {
                tool("Sabitleme")
                tap("Analiz et ve sabitle")
                check("hareket analizi", waitFor(120_000, "analiz") { st().flatStab != null }, "")
                Thread.sleep(800)
                check("sabitleme açık", st().stabMode != com.mhmmtbg.x5reframe.stab.StabMode.OFF, "${st().stabMode}")
                closeTool("Sabitleme")
            }
            step("Akıllı kadraj") {
                tool("Akıllı")
                tap("9:16")
                check("akıllı kadraj bitti", waitFor(120_000, "kadraj") { c().autoTask == null && st().aspect == com.mhmmtbg.x5reframe.model.Aspect.PORTRAIT })
                check("kadraj anahtar kareleri", st().keyframes.isNotEmpty(), "${st().keyframes.size}")
                tapDesc("Geri al")
                Thread.sleep(500)
                check("geri al", st().aspect != com.mhmmtbg.x5reframe.model.Aspect.PORTRAIT, "${st().aspect}")
                tapDesc("Yinele")
                Thread.sleep(500)
                check("yinele", st().aspect == com.mhmmtbg.x5reframe.model.Aspect.PORTRAIT, "${st().aspect}")
                tapDesc("Geri al")
            }
            step("Sessizlikleri kes") {
                // İlk parçaya dön (ses bölümü 3–5 sn sessiz)
                val before = segs().sumOf { it.outputDurationUs }
                tap("Sessizlikleri kes")
                Thread.sleep(4000)
                val after = segs().sumOf { it.outputDurationUs }
                check("sessizlik çıkarıldı", after < before || exists("Kesilecek sessizlik bulunamadı", sub = true), "$before → $after")
                closeTool("Akıllı")
            }
            step("Tam ekran") {
                tapDesc("Önizlemeyi büyüt")
                Thread.sleep(600)
                check("tam ekranda araçlar gizli", !exists("Renk"))
                tapDesc("Tam ekrandan çık")
                Thread.sleep(600)
                check("araçlar geri geldi", exists("Renk"))
            }
            step("Dışa aktar MP4") {
                tap("Dışa aktar")
                Thread.sleep(1200)
                tap("WhatsApp")
                tap("Başlat")
                check("MP4 dışa aktarım bitti", waitFor(300_000, "mp4") { c().exportUi is com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Done || c().exportUi is com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Failed },
                    "${c().exportUi}")
                val d = (c().exportUi as? com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Done)?.uri?.let { durationOf(it) } ?: -1
                check("MP4 dosyası geçerli", d > 1_000_000, "süre $d")
            }
            step("Dışa aktar GIF") {
                tap("Yeni dışa aktarım")
                Thread.sleep(800)
                tap("GIF")
                tap("Başlat")
                waitFor(300_000, "gif") { c().exportUi is com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Done || c().exportUi is com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Failed }
                val u = (c().exportUi as? com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Done)?.uri
                check("GIF oluştu", u != null && ctx.contentResolver.getType(u) == "image/gif", "${c().exportUi}")
            }
            step("Dışa aktar ses") {
                tap("Yeni dışa aktarım")
                Thread.sleep(800)
                tap("Yalnız ses")
                tap("Başlat")
                waitFor(300_000, "ses") { c().exportUi is com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Done || c().exportUi is com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Failed }
                val u = (c().exportUi as? com.mhmmtbg.x5reframe.ui.EditorController.ExportUi.Done)?.uri
                check("M4A oluştu", u != null && durationOf(u) > 1_000_000, "${c().exportUi}")
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(800)
            }
            step("Anlık görüntü") {
                tap("Anlık görüntü", last = true)
                check("anlık görüntü kaydedildi", waitFor(30_000, "anlık") { c().feedback?.text?.startsWith("Anlık görüntü kaydedildi") == true }, "${c().feedback?.text}")
            }
            step("Kare dondur") {
                tool("Hız")
                val n = segs().size
                seek(1_000_000)
                tap("Kareyi dondur")
                check("kare dondurma parça ekledi", waitFor(20_000, "dondurma") { segs().size >= n + 2 }, "${n} → ${segs().size}")
                check("dondurulmuş kare 2 sn", segs().any { it.clipName == "Dondurulmuş kare" && it.outputDurationUs == 2_000_000L })
            }
        } finally {
            scenario?.close(); scenario = null
        }
    }

    private fun mainScreens() {
        ActivityScenario.launch(MainActivity::class.java).use {
            step("Ana sayfa") {
                Thread.sleep(2500)
                check("son projeler / devam kartı", exists("Kaldığın yerden devam et") || exists("Yeni proje", sub = true))
            }
            step("Proje menüsü") {
                val n = nodes("tour", false).fetchSemanticsNodes().size
                tap("⋮")
                tap("Çoğalt")
                check("proje çoğaltıldı", waitFor(5000, "çoğalt") { nodes("tour", true).fetchSemanticsNodes().size > n })
            }
            step("Albüm") {
                tap("360° Albüm", last = true)
                check("albüm açıldı", waitFor(5000, "albüm") { exists("Kamera klasörünü seç") || exists("Dışa aktarılanlar") })
            }
            step("Ayarlar") {
                tap("Ayarlar", last = true)
                check("ayarlar açıldı", waitFor(5000, "ayarlar") { exists("Tema", sub = true) || exists("Görünüm") })
                scrollToText("Önbelleği temizle")
                check("önbellek satırı", exists("Önbelleği temizle"))
                tap("Önbelleği temizle"); Thread.sleep(1500)
            }
            step("Projeyi aç") {
                tap("Ana sayfa", last = true)
                waitFor(5000, "ana sayfa") { exists("Kaldığın yerden devam et") }
                tap("Kaldığın yerden devam et")
                check("proje düzenleyicide açıldı", waitFor(30_000, "proje") { exists("Dışa aktar") && true })
                Thread.sleep(3000)
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(1500)
            }
        }
        ActivityScenario.launch(com.mhmmtbg.x5reframe.camera.CameraActivity::class.java).use {
            step("Kamera ekranı") { Thread.sleep(2500) }
        }
    }

    private fun video360() {
        val clip = File("/data/local/tmp/clip.insv")
        if (!clip.exists()) { log("360 örnek klip yok, atlandı"); return }
        val lrv = File("/data/local/tmp/clip.lrv")
        scenario = ActivityScenario.launch(Intent(ctx, EditorActivity::class.java).setData(Uri.fromFile(clip))
            .putExtra(EditorActivity.EXTRA_LRV, if (lrv.exists()) Uri.fromFile(lrv).toString() else null))
        try {
            step("360 açılış") {
                check("360 düzenleyici açıldı", waitFor(40_000, "yükleme") { ui { it.isLoadedForTest } })
                Thread.sleep(3000)
            }
            step("360 görünüm") {
                tool("Görünüm")
                tap("Gezegen")
                Thread.sleep(800)
                check("küçük gezegen", c().currentView.projection == com.mhmmtbg.x5reframe.model.Projection.WIDE, "${c().currentView}")
                tap("Ön")
                closeTool("Görünüm")
            }
            step("360 çoklu açı") {
                tool("Çoklu açı")
                tap("Yan yana")
                check("yan yana düzen", st().layout.count == 2, "${st().layout}")
                tap("Tek")
                closeTool("Çoklu açı")
            }
            step("360 sabitleme") {
                tool("Sabitleme")
                tap("Yön kilidi")
                check("yön kilidi", st().stabMode == com.mhmmtbg.x5reframe.stab.StabMode.LOCK, "${st().stabMode}")
                tap("FlowState")
                closeTool("Sabitleme")
            }
            for (t in listOf("Derin takip", "Dikiş", "Yapay zekâ", "Renk")) step("360 $t") { tool(t); Thread.sleep(1200); closeTool(t) }
            step("360 oynat") {
                seek(0)
                tapDesc("Oynat"); Thread.sleep(2000); ui { it.playerForTest?.pause() }; Thread.sleep(400)
                check("360 oynatma ilerledi", c().positionUs > 800_000, "${c().positionUs}")
            }
        } finally {
            scenario?.close(); scenario = null
        }
    }
}

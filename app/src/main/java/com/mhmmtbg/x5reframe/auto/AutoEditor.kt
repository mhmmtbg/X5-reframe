package com.mhmmtbg.x5reframe.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.insv.Rig
import com.mhmmtbg.x5reframe.media.DualFrameSource
import com.mhmmtbg.x5reframe.media.FrameSource
import com.mhmmtbg.x5reframe.media.PcmReader
import com.mhmmtbg.x5reframe.media.SideBySideFrameSource
import com.mhmmtbg.x5reframe.model.Keyframe
import com.mhmmtbg.x5reframe.model.ViewParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Cihaz üzerinde, internetsiz otomatik düzenleme.
 * - Akıllı kadraj: en belirgin kişiyi (yüz) takip eden anahtar kareler
 * - Öne çıkan anlar: kişi + ses + hareket skoruna göre en iyi aralıklar
 * - Konu takibi: ortadaki nesneyi/kişiyi nesne izleyiciyle takip eden anahtar kareler
 *
 * Görüntüler uygulamanın kendi GPU hattıyla küçük düz görünümlere açılır, ML Kit ile çözümlenir.
 */
class AutoEditor(
    private val context: Context,
    private val clip: ClipInfo,
    /** Sabitleme temel dönüşü (kullanıcının gördüğü çerçeveyle aynı olsun diye). */
    private val baseAt: (Long) -> DoubleArray?,
) {
    @Volatile var cancelled = false

    data class Face(val yaw: Double, val pitch: Double, val size: Float)
    data class Sample(val tUs: Long, val faces: List<Face>, val audio: Float, val motion: Float)

    private val W = 480
    private val H = 360
    private val scanFov = 100.0

    private inner class Gl(val w: Int = W, val h: Int = H) : AutoCloseable {
        val egl = EglCore()
        val pb = egl.createPbuffer(w, h).also { egl.makeCurrent(it) }
        val pfd = context.contentResolver.openFileDescriptor(clip.lrv?.uri ?: clip.uri, "r")!!
        val program = ReframeProgram(clip.rig)
        val source: FrameSource = clip.lrv?.let { SideBySideFrameSource(pfd.fileDescriptor, it.videoTrack, it.width, it.height) }
            ?: DualFrameSource(pfd.fileDescriptor, clip, realtime = false)
        val buf: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val frameDur = (1_000_000 / clip.frameRate).toLong()

        /** Kaynağı t'ye getir (ileri yönde ucuz ilerler). */
        fun moveTo(t: Long): Boolean {
            if (!source.hasFrame || t < source.currentPts || t - source.currentPts > 2_000_000) {
                return source.seekAndShow(t, frameDur) >= 0
            }
            while (source.currentPts + frameDur <= t) {
                if (source.next { it + frameDur > t } < 0) return false
            }
            return true
        }

        fun render(view: ViewParams): Bitmap {
            program.draw(w, h, view, source, baseAt(source.currentPts))
            buf.rewind()
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            return Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false).also { bmp.recycle() }
        }

        override fun close() {
            runCatching { source.release() }
            runCatching { program.release() }
            runCatching { egl.releaseSurface(pb) }
            runCatching { egl.release() }
            runCatching { pfd.close() }
        }
    }

    /** Görüntüdeki bir pikselin kullanıcı çerçevesindeki yönü (yaw, pitch derece). */
    private fun pixelDirection(view: ViewParams, px: Float, py: Float, w: Int = W, h: Int = H): Pair<Double, Double> {
        // Uzun kenar = görüş açısı (ReframeProgram ile aynı)
        val k = tan(Math.toRadians(view.fov) / 2)
        val sx = if (w >= h) 1.0 else w.toDouble() / h
        val sy = if (w >= h) h.toDouble() / w else 1.0
        val nx = (px / w * 2 - 1) * k * sx
        val ny = -(py / h * 2 - 1) * k * sy
        val dv = doubleArrayOf(nx, -ny, 1.0)
        val r = Rig.mul(Rig.mul(Rig.rotY(Math.toRadians(view.yaw)), Rig.rotX(Math.toRadians(view.pitch))), Rig.rotZ(Math.toRadians(view.roll)))
        val d = DoubleArray(3) { i -> r[i * 3] * dv[0] + r[i * 3 + 1] * dv[1] + r[i * 3 + 2] * dv[2] }
        val n = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return Math.toDegrees(atan2(d[0], d[2])) to Math.toDegrees(asin((-d[1] / n).coerceIn(-1.0, 1.0)))
    }

    /** Klibi tara: her adımda dört yöne bakıp yüz bul; ses ve hareket ölç. */
    fun scan(fromUs: Long, toUs: Long, stepUs: Long = 250_000, progress: (Float) -> Unit): List<Sample> {
        val faces = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.08f).build()
        )
        val out = ArrayList<Sample>()
        val audio = audioEnergy(fromUs, toUs, stepUs)
        val motion = motionEnergy(fromUs, toUs, stepUs)
        Gl().use { gl ->
            var t = fromUs
            var i = 0
            while (t <= toUs && !cancelled) {
                if (!gl.moveTo(t)) break
                val found = ArrayList<Face>()
                for (yaw in listOf(0.0, 90.0, 180.0, -90.0)) {
                    val v = ViewParams(yaw = yaw, fov = scanFov)
                    val bmp = gl.render(v)
                    val res = runCatching { Tasks.await(faces.process(InputImage.fromBitmap(bmp, 0))) }.getOrDefault(emptyList())
                    for (f in res) {
                        val b = f.boundingBox
                        // Kenarlarda yarım kalan yüzleri komşu görünüm yakalar.
                        if (b.centerX() < W * 0.1 || b.centerX() > W * 0.9) continue
                        val (fy, fp) = pixelDirection(v, b.exactCenterX(), b.exactCenterY())
                        found += Face(fy, fp, b.width().toFloat() / W)
                    }
                    bmp.recycle()
                }
                out += Sample(t, found, audio.getOrElse(i) { 0f }, motion.getOrElse(i) { 0f })
                progress(((t - fromUs).toFloat() / (toUs - fromUs).coerceAtLeast(1)).coerceIn(0f, 1f))
                t += stepUs; i++
            }
        }
        faces.close()
        return out
    }

    /** Akıllı kadraj: en büyük yüzü yumuşakça takip eden anahtar kareler. */
    fun smartFraming(samples: List<Sample>, fov: Double = 80.0): List<Keyframe> {
        if (samples.isEmpty()) return emptyList()
        // Hedef yönler (yüz yoksa önceki korunur)
        var lastYaw = samples.firstNotNullOfOrNull { s -> s.faces.maxByOrNull { it.size }?.yaw } ?: 0.0
        var lastPitch = 0.0
        val yaws = DoubleArray(samples.size); val pitches = DoubleArray(samples.size)
        for ((i, s) in samples.withIndex()) {
            val f = s.faces.maxByOrNull { it.size }
            if (f != null) {
                // Sarmayı aç: önceki yöne en yakın eşdeğer
                var y = f.yaw
                while (y - lastYaw > 180) y -= 360
                while (y - lastYaw < -180) y += 360
                lastYaw = y; lastPitch = f.pitch.coerceIn(-30.0, 30.0) * 0.6
            }
            yaws[i] = lastYaw; pitches[i] = lastPitch
        }
        // ~1,5 sn hareketli ortalama: kamera yumuşak dönsün
        val w = 3
        val sy = DoubleArray(yaws.size) { i -> (maxOf(0, i - w)..minOf(yaws.size - 1, i + w)).map { yaws[it] }.average() }
        val sp = DoubleArray(pitches.size) { i -> (maxOf(0, i - w)..minOf(pitches.size - 1, i + w)).map { pitches[it] }.average() }
        val keys = ArrayList<Keyframe>()
        for (i in samples.indices step 2) {
            keys += Keyframe(samples[i].tUs, ViewParams(yaw = ViewParams.wrap(sy[i]), pitch = sp[i], fov = fov))
        }
        return keys
    }

    /** Öne çıkan aralıklar: kişi + ses + hareket skoru en yüksek, örtüşmeyen pencereler (kronolojik). */
    fun highlights(samples: List<Sample>, windowUs: Long, targetUs: Long): List<LongRange> {
        if (samples.isEmpty()) return emptyList()
        val step = if (samples.size > 1) samples[1].tUs - samples[0].tUs else 250_000
        val per = (windowUs / step).toInt().coerceAtLeast(1)
        val maxA = samples.maxOf { it.audio }.coerceAtLeast(1e-6f)
        val maxM = samples.maxOf { it.motion }.coerceAtLeast(1e-6f)
        val score = samples.map { s ->
            val people = minOf(s.faces.size, 3) / 3f + (s.faces.maxOfOrNull { it.size } ?: 0f)
            val a = s.audio / maxA
            val m = s.motion / maxM
            // Orta düzey hareket ilginç, aşırı sarsıntı değil
            people * 1.2f + a * 1.0f + (m * (1.3f - m)) * 0.8f
        }
        val windows = (0..samples.size - per).map { i -> i to (i until i + per).sumOf { score[it].toDouble() } }
            .sortedByDescending { it.second }
        val chosen = ArrayList<Int>()
        var total = 0L
        for ((i, _) in windows) {
            if (total >= targetUs) break
            if (chosen.any { kotlin.math.abs(it - i) < per }) continue
            chosen += i; total += windowUs
        }
        return chosen.sorted().map { i -> samples[i].tUs..(samples[i].tUs + windowUs) }
    }

    /** Konu takibi: [startView] ortasındaki nesneyi [toUs]'a kadar izler. */
    fun track(fromUs: Long, toUs: Long, startView: ViewParams, progress: (Float) -> Unit): List<Keyframe> {
        val det = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.STREAM_MODE).enableMultipleObjects().build()
        )
        val keys = ArrayList<Keyframe>()
        var view = startView.copy(fov = startView.fov.coerceIn(50.0, 100.0), projection = com.mhmmtbg.x5reframe.model.Projection.RECTILINEAR)
        var lockId: Int? = null
        var lostSince = -1L
        val step = 100_000L
        Gl().use { gl ->
            var t = fromUs
            var n = 0
            while (t <= toUs && !cancelled) {
                if (!gl.moveTo(t)) break
                val bmp = gl.render(view)
                val objs = runCatching { Tasks.await(det.process(InputImage.fromBitmap(bmp, 0))) }.getOrDefault(emptyList())
                bmp.recycle()
                val target = when {
                    lockId == null -> objs.minByOrNull { o ->
                        val dx = o.boundingBox.exactCenterX() - W / 2; val dy = o.boundingBox.exactCenterY() - H / 2; dx * dx + dy * dy
                    }?.also { lockId = it.trackingId }
                    else -> objs.firstOrNull { it.trackingId == lockId } ?: objs.minByOrNull { o ->
                        val dx = o.boundingBox.exactCenterX() - W / 2; val dy = o.boundingBox.exactCenterY() - H / 2; dx * dx + dy * dy
                    }?.takeIf { o ->
                        val dx = o.boundingBox.exactCenterX() - W / 2; val dy = o.boundingBox.exactCenterY() - H / 2
                        dx * dx + dy * dy < (W * 0.15f) * (W * 0.15f)
                    }?.also { lockId = it.trackingId }
                }
                if (target != null) {
                    lostSince = -1
                    val (yaw, pitch) = pixelDirection(view, target.boundingBox.exactCenterX(), target.boundingBox.exactCenterY())
                    // Yumuşak takip: hedefe doğru %35 yaklaş
                    val dy = ViewParams.wrap(yaw - view.yaw)
                    view = view.copy(yaw = ViewParams.wrap(view.yaw + dy * 0.35), pitch = (view.pitch + (pitch - view.pitch) * 0.35).coerceIn(-60.0, 60.0))
                } else {
                    if (lostSince < 0) lostSince = t
                    if (t - lostSince > 1_500_000) break // konu kayboldu
                }
                if (n % 3 == 0) keys += Keyframe(t, view.copy(fov = startView.fov, projection = startView.projection), com.mhmmtbg.x5reframe.model.Curve.LINEAR)
                progress(((t - fromUs).toFloat() / (toUs - fromUs).coerceAtLeast(1)).coerceIn(0f, 1f))
                t += step; n++
            }
        }
        det.close()
        return keys
    }

    // ---- Derin takip v2: hedef önerisi + şablon eşleştirme + nesne algılayıcıyla yeniden bulma ----

    /** Önizleme karesinde normalize (0..1) kutu. */
    data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val label: String = "")

    class TrackResult(val keys: List<Keyframe>, val endUs: Long, val lastBox: Box?, val lost: Boolean)

    private fun sizeFor(aspectW: Int, aspectH: Int): Pair<Int, Int> =
        if (aspectW >= aspectH) 480 to (480 * aspectH / aspectW).coerceAtLeast(120) else (480 * aspectW / aspectH).coerceAtLeast(120) to 480

    /** O anki karede takip edilebilecek hedefleri öner (kişiler ve nesneler). */
    fun suggestTargets(t: Long, view: ViewParams, aspectW: Int, aspectH: Int): List<Box> {
        val (w, h) = sizeFor(aspectW, aspectH)
        val out = ArrayList<Box>()
        Gl(w, h).use { gl ->
            if (!gl.moveTo(t)) return emptyList()
            val bmp = gl.render(view.copy(projection = com.mhmmtbg.x5reframe.model.Projection.RECTILINEAR, fov = view.fov.coerceIn(40.0, 110.0)))
            val img = InputImage.fromBitmap(bmp, 0)
            val od = ObjectDetection.getClient(ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects().enableClassification().build())
            runCatching { Tasks.await(od.process(img)) }.getOrDefault(emptyList()).forEach { o ->
                val b = o.boundingBox
                val label = o.labels.maxByOrNull { it.confidence }?.text ?: "Nesne"
                out += Box(b.exactCenterX() / w, b.exactCenterY() / h, b.width().toFloat() / w, b.height().toFloat() / h, trLabel(label))
            }
            od.close()
            val fd = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
            runCatching { Tasks.await(fd.process(img)) }.getOrDefault(emptyList()).forEach { f ->
                val b = f.boundingBox
                // Yüzü omuzlarla birlikte al: takip daha kararlı
                out += Box(b.exactCenterX() / w, (b.exactCenterY() + b.height() * 0.6f) / h, b.width() * 1.8f / w, b.height() * 2.2f / h, "Kişi")
            }
            fd.close()
            bmp.recycle()
        }
        return out.filter { it.w in 0.02f..0.95f && it.h in 0.02f..0.95f }
    }

    private fun trLabel(en: String) = when (en.lowercase()) {
        "fashion good" -> "Kıyafet"; "food" -> "Yiyecek"; "home good" -> "Eşya"; "place" -> "Mekân"; "plant" -> "Bitki"
        else -> "Nesne"
    }

    /**
     * Seçilen kutuyu [fromUs]'tan itibaren takip et. Kamera hedefe yumuşakça döner (hedef ortada kalır).
     * Önce şablon eşleştirme (her türlü konu için), tutmazsa ML Kit nesne algılayıcıyla yeniden bulma.
     * [cancelled] ile durdurulursa o ana kadarki sonuç döner (Devam ile sürdürülebilir).
     */
    fun deepTrack(fromUs: Long, toUs: Long, startView: ViewParams, target: Box, aspectW: Int, aspectH: Int,
                  progress: (Float, Long) -> Unit): TrackResult {
        val (w, h) = sizeFor(aspectW, aspectH)
        val det = ObjectDetection.getClient(ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.STREAM_MODE).enableMultipleObjects().build())
        val keys = ArrayList<Keyframe>()
        // Çözümleme düz (doğrusal) görünümde yapılır; anahtar karelere kullanıcının izdüşümü yazılır.
        var view = startView.copy(projection = com.mhmmtbg.x5reframe.model.Projection.RECTILINEAR, fov = startView.fov.coerceIn(40.0, 110.0))
        val step = 66_000L
        // Kutu piksel cinsinden (yarım çözünürlük gri görüntüde)
        val gw = w / 2; val gh = h / 2
        var bw = (target.w * gw).coerceIn(8f, gw * 0.8f)
        var bh = (target.h * gh).coerceIn(8f, gh * 0.8f)
        var px = target.cx * gw; var py = target.cy * gh
        var template: FloatArray? = null
        val TS = 24
        var lostSince = -1L
        var t = fromUs
        var n = 0
        var lastBox: Box? = null
        var lost = false
        Gl(w, h).use { gl ->
            while (t <= toUs && !cancelled) {
                if (!gl.moveTo(t)) break
                val bmp = gl.render(view)
                val gray = grayHalf(bmp)
                var found = false
                if (template == null) {
                    template = patch(gray, gw, gh, px, py, bw, bh, TS)
                    found = true
                } else {
                    // Şablonu beklenen konum çevresinde ara (3 ölçek)
                    var best = -1f; var bx = px; var by = py; var bs = 1f
                    for (sc in floatArrayOf(0.92f, 1f, 1.08f)) {
                        val sw = bw * sc; val sh = bh * sc
                        val r = (maxOf(sw, sh) * 0.8f).toInt().coerceAtLeast(6)
                        var yy = -r
                        while (yy <= r) {
                            var xx = -r
                            while (xx <= r) {
                                val cx = px + xx; val cy = py + yy
                                if (cx - sw / 2 >= 0 && cy - sh / 2 >= 0 && cx + sw / 2 < gw && cy + sh / 2 < gh) {
                                    val sc2 = ncc(template!!, patch(gray, gw, gh, cx, cy, sw, sh, TS))
                                    if (sc2 > best) { best = sc2; bx = cx; by = cy; bs = sc }
                                }
                                xx += 2
                            }
                            yy += 2
                        }
                    }
                    if (best > 0.55f) {
                        px = bx; py = by; bw *= bs; bh *= bs
                        val fresh = patch(gray, gw, gh, px, py, bw, bh, TS)
                        for (i in fresh.indices) template!![i] = template!![i] * 0.9f + fresh[i] * 0.1f
                        found = true
                    } else {
                        // Yeniden bulma: beklenen konuma en yakın algılanan nesne
                        val objs = runCatching { Tasks.await(det.process(InputImage.fromBitmap(bmp, 0))) }.getOrDefault(emptyList())
                        val near = objs.minByOrNull { o ->
                            val dx = o.boundingBox.exactCenterX() / 2 - px; val dy = o.boundingBox.exactCenterY() / 2 - py; dx * dx + dy * dy
                        }?.takeIf { o ->
                            val dx = o.boundingBox.exactCenterX() / 2 - px; val dy = o.boundingBox.exactCenterY() / 2 - py
                            dx * dx + dy * dy < (maxOf(bw, bh) * 1.5f).let { it * it }
                        }
                        if (near != null) {
                            px = near.boundingBox.exactCenterX() / 2; py = near.boundingBox.exactCenterY() / 2
                            bw = near.boundingBox.width() / 2f; bh = near.boundingBox.height() / 2f
                            template = patch(gray, gw, gh, px, py, bw, bh, TS)
                            found = true
                        }
                    }
                }
                bmp.recycle()
                if (found) {
                    lostSince = -1
                    val (yaw, pitch) = pixelDirection(view, px * 2, py * 2, w, h)
                    // Kamerayı hedefe yumuşakça çevir; görüntüdeki hedef de aynı oranda ortaya kayar
                    val a = 0.35
                    val dyaw = ViewParams.wrap(yaw - view.yaw)
                    val newView = view.copy(yaw = ViewParams.wrap(view.yaw + dyaw * a), pitch = (view.pitch + (pitch - view.pitch) * a).coerceIn(-75.0, 75.0))
                    val (nx, ny) = directionToPixel(newView, yaw, pitch, w, h)
                    view = newView
                    px = (nx / 2).coerceIn(bw / 2, gw - bw / 2); py = (ny / 2).coerceIn(bh / 2, gh - bh / 2)
                    lastBox = Box(px / gw, py / gh, bw / gw, bh / gh)
                } else {
                    if (lostSince < 0) lostSince = t
                    if (t - lostSince > 1_500_000) { lost = true; break }
                }
                if (n % 3 == 0) keys += Keyframe(t, view.copy(projection = startView.projection, fov = startView.fov), com.mhmmtbg.x5reframe.model.Curve.LINEAR)
                progress(((t - fromUs).toFloat() / (toUs - fromUs).coerceAtLeast(1)).coerceIn(0f, 1f), t)
                t += step; n++
            }
        }
        det.close()
        val end = keys.lastOrNull()?.timeUs ?: fromUs
        return TrackResult(keys, end, lastBox, lost)
    }

    /** Bir yönün (yaw, pitch) görünümdeki piksel konumu. */
    private fun directionToPixel(view: ViewParams, yaw: Double, pitch: Double, w: Int, h: Int): Pair<Float, Float> {
        val cy = kotlin.math.cos(Math.toRadians(pitch))
        val d = doubleArrayOf(cy * kotlin.math.sin(Math.toRadians(yaw)), -kotlin.math.sin(Math.toRadians(pitch)), cy * kotlin.math.cos(Math.toRadians(yaw)))
        val r = Rig.mul(Rig.mul(Rig.rotY(Math.toRadians(view.yaw)), Rig.rotX(Math.toRadians(view.pitch))), Rig.rotZ(Math.toRadians(view.roll)))
        // görünüm = R^T * d
        val v = DoubleArray(3) { i -> r[i] * d[0] + r[3 + i] * d[1] + r[6 + i] * d[2] }
        val k = tan(Math.toRadians(view.fov) / 2)
        val sx = if (w >= h) 1.0 else w.toDouble() / h
        val sy = if (w >= h) h.toDouble() / w else 1.0
        val nx = v[0] / v[2] / (k * sx); val ny = v[1] / v[2] / (k * sy)
        return ((nx + 1) / 2 * w).toFloat() to ((ny + 1) / 2 * h).toFloat()
    }

    private fun grayHalf(b: Bitmap): FloatArray {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val gw = w / 2; val gh = h / 2
        val g = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            var s = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val c = px[(y * 2 + dy) * w + x * 2 + dx]
                s += ((c shr 16) and 0xff) * 0.299f + ((c shr 8) and 0xff) * 0.587f + (c and 0xff) * 0.114f
            }
            g[y * gw + x] = s / 4f
        }
        return g
    }

    /** Kutuyu TS x TS ızgaraya örnekle (çift doğrusal). */
    private fun patch(g: FloatArray, gw: Int, gh: Int, cx: Float, cy: Float, bw: Float, bh: Float, ts: Int): FloatArray {
        val out = FloatArray(ts * ts)
        for (j in 0 until ts) for (i in 0 until ts) {
            val x = (cx - bw / 2 + (i + 0.5f) * bw / ts).coerceIn(0f, gw - 1.001f)
            val y = (cy - bh / 2 + (j + 0.5f) * bh / ts).coerceIn(0f, gh - 1.001f)
            val x0 = x.toInt(); val y0 = y.toInt(); val fx = x - x0; val fy = y - y0
            val a = g[y0 * gw + x0]; val b = g[y0 * gw + x0 + 1]; val c = g[(y0 + 1) * gw + x0]; val d = g[(y0 + 1) * gw + x0 + 1]
            out[j * ts + i] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        }
        return out
    }

    private fun ncc(a: FloatArray, b: FloatArray): Float {
        var ma = 0f; var mb = 0f
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size; mb /= b.size
        var sab = 0f; var saa = 0f; var sbb = 0f
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y }
        return if (saa < 1f || sbb < 1f) 0f else sab / kotlin.math.sqrt(saa * sbb)
    }

    private fun audioEnergy(fromUs: Long, toUs: Long, stepUs: Long): FloatArray {
        val a = clip.audioTrack ?: return FloatArray(0)
        val n = ((toUs - fromUs) / stepUs + 1).toInt()
        val out = FloatArray(n)
        runCatching {
            val r = PcmReader(context, clip.uri, a, fromUs)
            val per = (stepUs * com.mhmmtbg.x5reframe.media.MIX_RATE / 1_000_000).toInt()
            val buf = ShortArray(per * 2)
            for (i in 0 until n) {
                if (cancelled) break
                r.read(per, buf, 1f, false)
                var e = 0.0
                for (k in 0 until per * 2) { val v = buf[k] / 32768.0; e += v * v }
                out[i] = sqrt(e / (per * 2)).toFloat()
            }
            r.close()
        }
        return out
    }

    private fun motionEnergy(fromUs: Long, toUs: Long, stepUs: Long): FloatArray {
        val imu = clip.imu ?: return FloatArray(0)
        val n = ((toUs - fromUs) / stepUs + 1).toInt()
        val out = FloatArray(n)
        var j = 0
        for (i in 0 until n) {
            val t0 = (fromUs + i * stepUs) / 1e6; val t1 = t0 + stepUs / 1e6
            var e = 0.0; var c = 0
            while (j < imu.size && imu.t[j] < t0) j++
            var k = j
            while (k < imu.size && imu.t[k] < t1) {
                val g = imu.gyro[k]; e += sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]); c++; k++
            }
            out[i] = if (c > 0) (e / c / PI).toFloat() else 0f
        }
        return out
    }
}

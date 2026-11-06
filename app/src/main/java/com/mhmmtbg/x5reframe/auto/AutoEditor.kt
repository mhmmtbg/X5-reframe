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

    private inner class Gl : AutoCloseable {
        val egl = EglCore()
        val pb = egl.createPbuffer(W, H).also { egl.makeCurrent(it) }
        val pfd = context.contentResolver.openFileDescriptor(clip.lrv?.uri ?: clip.uri, "r")!!
        val program = ReframeProgram(clip.rig)
        val source: FrameSource = clip.lrv?.let { SideBySideFrameSource(pfd.fileDescriptor, it.videoTrack, it.width, it.height) }
            ?: DualFrameSource(pfd.fileDescriptor, clip, realtime = false)
        val buf: ByteBuffer = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
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
            program.draw(W, H, view, source, baseAt(source.currentPts))
            buf.rewind()
            GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            return Bitmap.createBitmap(bmp, 0, 0, W, H, Matrix().apply { preScale(1f, -1f) }, false)
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
    private fun pixelDirection(view: ViewParams, px: Float, py: Float): Pair<Double, Double> {
        val k = tan(Math.toRadians(view.fov) / 2)
        val nx = (px / W * 2 - 1) * k
        val ny = -(py / H * 2 - 1) * k * H / W
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
                if (n % 3 == 0) keys += Keyframe(t, view.copy(fov = startView.fov, projection = startView.projection))
                progress(((t - fromUs).toFloat() / (toUs - fromUs).coerceAtLeast(1)).coerceIn(0f, 1f))
                t += step; n++
            }
        }
        det.close()
        return keys
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

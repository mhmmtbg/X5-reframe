package com.mhmmtbg.x5reframe

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.Exporter
import com.mhmmtbg.x5reframe.model.ViewParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Örnek X5 klibiyle uçtan uca dışa aktarım testi (CI emülatöründe çalışır).
 * Klip /data/local/tmp/clip.insv konumuna adb ile konur.
 */
@RunWith(AndroidJUnit4::class)
class ExportTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipFile = File("/data/local/tmp/clip.insv")
    private val report = StringBuilder()

    private fun log(s: String) {
        report.appendLine(s)
        android.util.Log.i("ExportTest", s)
    }

    private fun writeReport() {
        File(ctx.getExternalFilesDir(null), "report.txt").writeText(report.toString())
    }

    @Test
    fun parsesCalibration() {
        assumeTrue("örnek klip yok", clipFile.exists())
        val clip = ClipInfo.load(ctx, Uri.fromFile(clipFile))
        assertEquals("Insta360 X5", clip.cameraType)
        assertEquals(2, clip.videoTracks.size)
        // X5 + Track0Stream10: iz 0 = lens 1
        assertEquals(1, clip.rig.lenses[0].videoTrackOrder)
        assertTrue(clip.audioTrack != null)
    }

    @Test
    fun horizonLockLevelsView() {
        assumeTrue("örnek klip yok", clipFile.exists())
        val clip = ClipInfo.load(ctx, Uri.fromFile(clipFile))
        val imu = clip.imu
        assertTrue("IMU okunamadı", imu != null && imu.size > 1000)
        val st = com.mhmmtbg.x5reframe.stab.Stabilizer(imu!!, com.mhmmtbg.x5reframe.stab.Stabilizer.rigFromImuFor(clip.cameraType))
        val m = com.mhmmtbg.x5reframe.stab.Stabilizer.rigFromImuFor(clip.cameraType)
        var worst = 0.0
        for (sec in listOf(1.0, 4.0, 8.0, 12.0, 16.0)) {
            val base = st.base(sec, com.mhmmtbg.x5reframe.stab.StabMode.HORIZON, 0.5, 0.0)!!
            // Görünümün "aşağı"sı rig çerçevesinde:
            val viewDown = doubleArrayOf(base[1], base[4], base[7])
            // Ölçülen yerçekimi (o ana yakın 0,2 sn ortalaması), rig çerçevesinde aşağı yön:
            val g = DoubleArray(3)
            for (i in 0 until imu.size) if (kotlin.math.abs(imu.t[i] - sec - com.mhmmtbg.x5reframe.stab.Stabilizer.IMU_TIME_OFFSET) < 0.1) {
                val a = com.mhmmtbg.x5reframe.stab.Stabilizer.mul3(m, imu.acc[i]); for (k in 0..2) g[k] -= a[k]
            }
            val gl = kotlin.math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            val cos = (viewDown[0] * g[0] + viewDown[1] * g[1] + viewDown[2] * g[2]) / gl
            val ang = Math.toDegrees(kotlin.math.acos(cos.coerceIn(-1.0, 1.0)))
            log("t=$sec horizon error=${"%.2f".format(ang)} deg")
            worst = maxOf(worst, ang)
        }
        writeReport()
        assertTrue("ufuk hatası çok büyük: $worst", worst < 8.0)
    }

    @Test
    fun exports360WithSphericalMetadata() {
        assumeTrue("örnek klip yok", clipFile.exists())
        val clip = ClipInfo.load(ctx, Uri.fromFile(clipFile))
        val done = CountDownLatch(1)
        var outUri: Uri? = null
        var error: String? = null
        val view = ViewParams(projection = com.mhmmtbg.x5reframe.model.Projection.EQUIRECT, fov = 360.0)
        val settings = Exporter.Settings(1920, 960, false, 10_000_000, 1_000_000, 2_000_000, true, spherical = true)
        val ex = Exporter(ctx, clip, settings, { view }, object : Exporter.Listener {
            override fun onProgress(fraction: Float, speed: Float) {}
            override fun onDone(uri: Uri, seconds: Float) { outUri = uri; done.countDown() }
            override fun onFailed(message: String) { error = message; done.countDown() }
        })
        Thread { ex.run() }.start()
        assertTrue("zaman aşımı", done.await(10, TimeUnit.MINUTES))
        log("360 export error=$error tagged=${ex.sphericalTagged} frames=${ex.videoWritten}")
        writeReport()
        assertTrue("dışa aktarım hatası: $error", error == null && outUri != null)
        assertTrue("küresel bilgi eklenmedi", ex.sphericalTagged)
        val bytes = ctx.contentResolver.openInputStream(outUri!!)!!.use { it.readBytes() }
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("dosyada küresel XML yok", text.contains("GSpherical:Spherical>true"))
        val mx = MediaExtractor()
        ctx.contentResolver.openFileDescriptor(outUri!!, "r")!!.use { mx.setDataSource(it.fileDescriptor) }
        log("360 out tracks=${mx.trackCount}")
        assertTrue("360 dosyası okunamıyor", mx.trackCount >= 1)
        mx.release()
    }

    @Test
    fun lrvPreviewDecodes() {
        val lrvFile = File("/data/local/tmp/clip.lrv")
        assumeTrue("örnek LRV yok", clipFile.exists() && lrvFile.exists())
        val clip = ClipInfo.load(ctx, Uri.fromFile(clipFile), Uri.fromFile(lrvFile))
        val lrv = clip.lrv
        assertTrue("LRV tanınmadı", lrv != null)
        val egl = com.mhmmtbg.x5reframe.gl.EglCore()
        val pfd = ctx.contentResolver.openFileDescriptor(lrv!!.uri, "r")!!
        try {
            val program = com.mhmmtbg.x5reframe.gl.ReframeProgram(clip.rig)
            val src = com.mhmmtbg.x5reframe.media.SideBySideFrameSource(pfd.fileDescriptor, lrv.videoTrack, lrv.width, lrv.height)
            val pts = src.seekAndShow(2_000_000, 33_366)
            log("lrv ${lrv.width}x${lrv.height} seek pts=$pts")
            assertTrue("LRV karesi çözülemedi", pts >= 1_900_000)
            val pb = egl.createPbuffer(64, 36)
            egl.makeCurrent(pb)
            program.draw(64, 36, ViewParams(), src)
            assertEquals(android.opengl.GLES30.GL_NO_ERROR, android.opengl.GLES30.glGetError())
            src.release()
            program.release()
        } finally {
            pfd.close()
            egl.release()
            writeReport()
        }
    }

    @Test
    fun exportsVideoWithAudio() {
        assumeTrue("örnek klip yok", clipFile.exists())
        try {
            val clip = ClipInfo.load(ctx, Uri.fromFile(clipFile))
            log("clip: ${clip.cameraType} ${clip.width}x${clip.height} dur=${clip.durationUs} fps=${clip.frameRate} audio=${clip.audioTrack}")

            val done = CountDownLatch(1)
            var outUri: Uri? = null
            var error: String? = null
            // Tezgâh tarafına bakan sabit kadraj.
            val view = ViewParams(yaw = 180.0, pitch = 0.0, fov = 100.0)
            val settings = Exporter.Settings(1280, 720, false, 8_000_000, 0, 3_000_000)
            val ex = Exporter(ctx, clip, settings, { view }, object : Exporter.Listener {
                override fun onProgress(fraction: Float, speed: Float) {}
                override fun onDone(uri: Uri, seconds: Float) { outUri = uri; done.countDown() }
                override fun onFailed(message: String) { error = message; done.countDown() }
            })
            val t0 = System.currentTimeMillis()
            Thread { ex.run() }.start()
            assertTrue("zaman aşımı", done.await(15, TimeUnit.MINUTES))
            val seconds = (System.currentTimeMillis() - t0) / 1000f
            log("export error=$error uri=$outUri seconds=$seconds videoSamples=${ex.videoWritten} audioSamples=${ex.audioWritten}")
            assertTrue("dışa aktarım hatası: $error", error == null && outUri != null)

            // Çıktıyı incele ve CI'nın alabileceği yere kopyala.
            val out = File(ctx.getExternalFilesDir(null), "export.mp4")
            ctx.contentResolver.openInputStream(outUri!!)!!.use { inp -> out.outputStream().use { inp.copyTo(it) } }
            val mx = MediaExtractor()
            mx.setDataSource(out.absolutePath)
            var hasVideo = false; var hasAudio = false
            for (i in 0 until mx.trackCount) {
                val f = mx.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                log("out track $i: $f")
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
            mx.release()
            log("hasVideo=$hasVideo hasAudio=$hasAudio")
            assertTrue("görüntü izi yok", hasVideo)
            assertTrue("ses izi yok", hasAudio)
            assertTrue("ses örneği yazılmadı", ex.audioWritten > 50)
        } catch (t: Throwable) {
            log("FAIL: ${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            writeReport()
        }
    }
}

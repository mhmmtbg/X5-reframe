package com.mhmmtbg.x5reframe

import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.media.DualFrameSource
import com.mhmmtbg.x5reframe.model.Projection
import com.mhmmtbg.x5reframe.model.ViewParams
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dinamik dikiş: tam çözünürlüklü izlerden 360 (equirect) kare çizilir, dikiş bölgeleri
 * dinamik dikiş kapalı / açık olarak kaydedilir; tahmincinin maliyet düşüşü loglanır.
 */
@RunWith(AndroidJUnit4::class)
class StitchTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dynamicStitchAlignsSeam() {
        val clipFile = File("/data/local/tmp/clip.insv")
        assumeTrue("örnek klip yok", clipFile.exists())
        val clip = ClipInfo.load(ctx, Uri.fromFile(clipFile))
        val pfd = ctx.contentResolver.openFileDescriptor(Uri.fromFile(clipFile), "r")!!
        val egl = EglCore()
        val w = 2048; val h = 1024
        val pb = egl.createPbuffer(w, h)
        egl.makeCurrent(pb)
        val program = ReframeProgram(clip.rig, highQualityStitch = true)
        val source = DualFrameSource(pfd.fileDescriptor, clip, realtime = false)
        val out = ctx.getExternalFilesDir(null)!!
        val view = ViewParams(projection = Projection.EQUIRECT, fov = 360.0)
        val log = StringBuilder()
        val frameDur = (1_000_000 / clip.frameRate).toLong()
        var improved = 0; var measured = 0
        for (t in listOf(3_800_000L, 9_600_000L)) {
            if (t >= clip.durationUs) continue
            source.seekAndShow(t, frameDur)
            program.dynamicStitch = false
            program.draw(w, h, view, source)
            val off = read(w, h)
            program.dynamicStitch = true
            program.resetStitch()
            // Birkaç ardışık karede yakınsat
            repeat(4) {
                source.next(true)
                program.updateStitch(source, 1, 0.5f)
            }
            program.dynamicStitch = false
            program.draw(w, h, view, source)
            val off2 = read(w, h)
            program.dynamicStitch = true
            program.draw(w, h, view, source)
            val on = read(w, h)
            val rep = program.stitch.lastReport
            log.append("t=${t / 1000} ms $rep\n")
            val st = program.stitch
            // Orta kuşak, 4 hücrede bir
            val mid = (st.bands / 2) * st.bins
            log.append("delta=" + (0 until st.bins step 4).joinToString(",") { "%.1f".format(st.delta[mid + it]) } + "\n")
            log.append("gain=" + (0 until st.bins step 4).joinToString(",") { "%.2f".format(st.gain[mid + it]) } + "\n")
            Regex("maliyet ([0-9.]+) -> ([0-9.]+)").find(rep)?.let {
                measured++
                if (it.groupValues[2].toFloat() < it.groupValues[1].toFloat()) improved++
            }
            saveSeams(off2, File(out, "seam_off_${t / 1000}.jpg"))
            saveSeams(on, File(out, "seam_on_${t / 1000}.jpg"))
            off.recycle(); off2.recycle(); on.recycle()
        }
        android.util.Log.i("StitchTest", log.toString())
        File(out, "stitch.txt").writeText(log.toString())
        source.release(); program.release(); egl.releaseSurface(pb); egl.release(); pfd.close()
        assertTrue("ölçüm yok", measured > 0)
        assertTrue("dikiş maliyeti düşmedi: $log", improved == measured)
    }

    private fun read(w: Int, h: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind(); bmp.copyPixelsFromBuffer(buf)
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, false).also { bmp.recycle() }
    }

    /** İki dikiş şeridini (boylam ±90°) yan yana kaydet. */
    private fun saveSeams(b: Bitmap, f: File) {
        val sw = b.width / 8
        val res = Bitmap.createBitmap(sw * 2, b.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(res)
        for ((i, cx) in listOf(b.width / 4, b.width * 3 / 4).withIndex()) {
            val src = android.graphics.Rect(cx - sw / 2, 0, cx + sw / 2, b.height)
            c.drawBitmap(b, src, android.graphics.Rect(i * sw, 0, (i + 1) * sw, b.height), null)
        }
        f.outputStream().use { res.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        // Uygulama test sonrası kaldırılınca kendi klasörü silinir; İndirilenler'e de kopyala.
        runCatching {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/x5test")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { res.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        res.recycle()
    }
}

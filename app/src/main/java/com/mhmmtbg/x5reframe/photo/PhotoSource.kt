package com.mhmmtbg.x5reframe.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.Proto
import com.mhmmtbg.x5reframe.insv.Rig
import com.mhmmtbg.x5reframe.insv.Trailer
import com.mhmmtbg.x5reframe.insv.long
import com.mhmmtbg.x5reframe.insv.message
import com.mhmmtbg.x5reframe.insv.string
import com.mhmmtbg.x5reframe.insv.WindowCrop
import com.mhmmtbg.x5reframe.media.FrameSource
import java.io.FileInputStream

/** .insp 360 fotoğraf: iki balık gözü tek JPEG'de yan yana + .insv ile aynı kuyruk (kalibrasyon). */
class PhotoInfo(val uri: Uri, val cameraType: String, val rig: Rig, val width: Int, val height: Int) {
    companion object {
        fun load(context: Context, uri: Uri): PhotoInfo {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("dosya açılamadı")
            pfd.use {
                val meta = FileInputStream(it.fileDescriptor).channel.use { ch ->
                    val tr = Trailer.open(ch)
                    Proto(tr.read(Trailer.ID_METADATA) ?: throw IllegalStateException("metadata kaydı yok")).fields()
                }
                val camera = meta.string(2) ?: "?"
                val offset = meta.string(54) ?: throw IllegalStateException("Bu fotoğrafta lens kalibrasyonu yok")
                val crop = meta.message(27)?.let { m ->
                    val sw = (m.long(1) ?: 0).toInt(); val sh = (m.long(2) ?: 0).toInt()
                    val dw = (m.long(3) ?: 0).toInt(); val dh = (m.long(4) ?: 0).toInt()
                    if (sw > 0 && sh > 0 && dw > 0 && dh > 0) WindowCrop(sw, sh, dw, dh, (m.long(5) ?: 0).toInt(), (m.long(6) ?: 0).toInt()) else null
                }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s, null, opts) }
                if (opts.outWidth <= 0) throw IllegalStateException("fotoğraf çözülemedi")
                return PhotoInfo(uri, camera, Rig.fromOffsetV3(offset, crop, false), opts.outWidth, opts.outHeight)
            }
        }

        /** GPU doku sınırına sığacak şekilde çöz (en fazla [maxW] genişlik). */
        fun decode(context: Context, info: PhotoInfo, maxW: Int): Bitmap {
            var sample = 1
            while (info.width / sample > maxW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return context.contentResolver.openInputStream(info.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IllegalStateException("fotoğraf çözülemedi")
        }
    }
}

/**
 * Fotoğrafı OES dokusuna yükler (SurfaceTexture'a donanım tuvaliyle çizerek), böylece videoyla
 * aynı kadraj/dikiş shader'ı kullanılır. Sol yarı lens 0, sağ yarı lens 1.
 */
class PhotoFrameSource(bitmap: Bitmap) : FrameSource {
    private val texture = ReframeProgram.createOesTexture()
    private val texMatrix = FloatArray(16)
    private val st = SurfaceTexture(texture)
    private val subs = arrayOf(floatArrayOf(0.5f, 0f, 1f, 0f), floatArrayOf(0.5f, 0.5f, 1f, 0f))

    override val hasFrame = true
    override val currentPts = 0L
    override val lastRendered = true

    init {
        st.setDefaultBufferSize(bitmap.width, bitmap.height)
        val surface = Surface(st)
        val canvas = surface.lockHardwareCanvas()
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        surface.unlockCanvasAndPost(canvas)
        // Kare SurfaceTexture'a ulaşana kadar kısa bekle
        var tries = 0
        while (tries++ < 50) {
            try { st.updateTexImage(); break } catch (e: Exception) { Thread.sleep(10) }
        }
        st.getTransformMatrix(texMatrix)
        surface.release()
    }

    override fun lensTexture(lens: Int) = texture
    override fun lensTexMatrix(lens: Int) = texMatrix
    override fun lensSubRect(lens: Int) = subs[lens]
    override fun seek(us: Long) {}
    override fun next(renderIf: (Long) -> Boolean): Long = -1
    override fun release() {
        st.release()
        android.opengl.GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

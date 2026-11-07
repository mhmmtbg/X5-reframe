package com.mhmmtbg.x5reframe.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES30
import android.provider.MediaStore
import com.mhmmtbg.x5reframe.gl.EglCore
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.Rig
import com.mhmmtbg.x5reframe.media.FrameSource
import com.mhmmtbg.x5reframe.model.ViewParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tek kareyi tam çözünürlükte (dışa aktarım kalitesinde dikişle) çizip JPEG olarak kaydeder.
 * Kendi GL bağlamını açar; arka plan iş parçacığında çağrılmalı.
 */
object StillRenderer {

    fun render(
        rig: Rig?, w: Int, h: Int, rects: List<FloatArray>, views: List<ViewParams>, base: DoubleArray?, color: FloatArray?,
        dynamicStitch: Boolean, flatAspect: Float = 1f, look: com.mhmmtbg.x5reframe.model.FlatLook = com.mhmmtbg.x5reframe.model.FlatLook(),
        openSource: () -> FrameSource,
    ): Bitmap {
        val egl = EglCore()
        val pb = egl.createPbuffer(w, h)
        egl.makeCurrent(pb)
        val program = ReframeProgram(rig, highQualityStitch = true).apply { this.flatAspect = flatAspect; this.look = look }
        val src = openSource()
        try {
            program.dynamicStitch = dynamicStitch
            program.color = color
            if (dynamicStitch) repeat(2) { program.stitch?.estimate(src, 1f) }
            program.drawScene(w, h, rects, views, src, base)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind(); bmp.copyPixelsFromBuffer(buf)
            return Bitmap.createBitmap(bmp, 0, 0, w, h, android.graphics.Matrix().apply { preScale(1f, -1f) }, false).also { bmp.recycle() }
        } finally {
            runCatching { src.release() }
            runCatching { program.release() }
            runCatching { egl.releaseSurface(pb) }
            runCatching { egl.release() }
        }
    }

    /** Resimler/X5Reframe klasörüne kaydet; 360 (2:1) ise panorama bilgisi eklenmez ama dosya adı belirtir. */
    fun save(context: Context, bmp: Bitmap, prefix: String = "X5"): Uri? {
        val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/X5Reframe")
        }
        return runCatching {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            uri
        }.getOrNull()
    }
}

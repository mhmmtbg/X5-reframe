package com.mhmmtbg.x5reframe.person

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.mhmmtbg.x5reframe.gl.Fbo
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.media.FrameSource
import com.mhmmtbg.x5reframe.model.FlatLook
import com.mhmmtbg.x5reframe.model.FrameFit
import com.mhmmtbg.x5reframe.model.ViewParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Kişi maskesi (cihaz içi ML Kit selfie segmentasyonu). Kare küçük bir tampona ham haliyle çizilir,
 * okunur, kişi olasılığı hesaplanır ve tek kanallı bir dokuya yüklenir; ReframeProgram bu dokuyla
 * kişiyi bulanık / düz renk arka plan üstüne bindirir. GL iş parçacığında kullanılmalıdır.
 * Önceki çerçeve tamponu bağlantısı korunur (geçiş ara görüntüleri bozulmasın).
 */
class PersonMask(aspect: Float, stream: Boolean) {
    private val w = 256
    private val h = (256 / aspect.coerceIn(0.4f, 2.5f)).toInt() / 2 * 2
    private val fbo = Fbo(w, h)
    private val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    private val maskBytes = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder())
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(if (stream) SelfieSegmenterOptions.STREAM_MODE else SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
    )
    val texture: Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0].also { t ->
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val empty = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(byteArrayOf(-1, -1, -1, -1)).also { it.position(0) }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, empty)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
    }
    var valid = false
        private set

    /** O anki kare için maskeyi güncelle. Çizim durumu (çerçeve tamponu) çağrıdan sonra 0'a döner. */
    fun update(program: ReframeProgram, src: FrameSource): Boolean {
        val look = program.look; val color = program.color; val fade = program.fade
        val prevFbo = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
        try {
            program.look = FlatLook(FrameFit.FILL); program.color = null; program.fade = 1f
            program.personMask = 0
            fbo.bind()
            program.draw(w, h, ViewParams(), src, null)
            buf.rewind()
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo)
            program.look = look; program.color = color; program.fade = fade
        }
        buf.rewind()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        // GL satırları aşağıdan yukarı: düzelt
        val bmp = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false)
        raw.recycle()
        val mask = runCatching { Tasks.await(segmenter.process(InputImage.fromBitmap(bmp, 0)), 3, TimeUnit.SECONDS) }.getOrNull()
        bmp.recycle()
        if (mask == null) return false
        val fb = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val mw = mask.width; val mh = mask.height
        maskBytes.rewind()
        // Maske görüntü boyutundadır; değilse en yakın komşu ile örnekle
        for (y in 0 until h) for (x in 0 until w) {
            val mx = x * mw / w; val my = y * mh / h
            maskBytes.put((fb.get(my * mw + mx).coerceIn(0f, 1f) * 255f).toInt().toByte())
        }
        maskBytes.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskBytes)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        valid = true
        return true
    }

    fun release() {
        runCatching { segmenter.close() }
        fbo.release()
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

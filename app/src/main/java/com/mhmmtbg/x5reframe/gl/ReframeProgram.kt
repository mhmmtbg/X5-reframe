package com.mhmmtbg.x5reframe.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.mhmmtbg.x5reframe.insv.Lens
import com.mhmmtbg.x5reframe.insv.Rig
import com.mhmmtbg.x5reframe.model.Projection
import com.mhmmtbg.x5reframe.model.ViewParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tan

/**
 * İki fisheye izi doğrudan düz kadraja çizer: çıktı pikseli → ışın → lens → doku.
 * Ara 360 (equirect) görüntü üretilmez; dışa aktarımı hızlı yapan budur.
 *
 * [rig] null ise düz klip (normal video / fotoğraf): kadraj kaydırma-yakınlaştırma olarak yorumlanır
 * (yaw/pitch = kaydırma, fov = yakınlaştırma, roll = döndürme); renk, geçiş ve katmanlar aynı yoldan geçer.
 */
class ReframeProgram(private val rig: Rig?, highQualityStitch: Boolean = false) {

    private val program: Int
    private val vbo: Int
    private val uTex = IntArray(2)
    private val uTexMat = IntArray(2)
    private val uCamFromRig = IntArray(2)
    private val uIntr = IntArray(2)
    private val uDist = IntArray(2)
    private val uDist2 = IntArray(2)
    private val uSub = IntArray(2)
    private val uView: Int
    private val uScale: Int
    private val uMode: Int
    private val uStitch: Int
    private val uTrans = IntArray(2)

    /** 1 / dikiş mesafesi (m); 0 = sonsuz. Yakın nesnelerde çift görüntüyü azaltır. */
    var stitchInvDistance = 0f

    /** Renk düzenlemesi (ColorGrade.uniforms()); null = dokunulmaz. */
    var color: FloatArray? = null
    private val uGrade = IntArray(4)
    private val uGradeOn: Int
    private val uFx: Int
    private val uCurve: Int
    private val uHsl: Int
    private val uLut: Int
    private val uLutN: Int
    /** Gren için karenin zamanı (sn). */
    var frameTime = 0f
    private var lutTex = 0
    private var lutId = -2
    private var lutSize = 0

    /** 0..1 parlaklık çarpanı (siyaha geçiş). */
    var fade = 1f
    private val uFade: Int
    private val uSeam: Int
    private val uSeamOn: Int
    private val uSeamRange: Int
    private val uSeamBands: Int

    /** Bölgesel (dinamik) dikiş; kapalıyken klasik tek mesafeli dikiş. */
    var dynamicStitch = true
    /** Önizleme: 48 bölge; dışa aktarım: 192 bölge x 3 kuşak (ince taneli). */
    /** Shader'ı derlenemeyen GPU'larda null: klasik dikişe düşülür, uygulama çökmez. */
    val stitch: StitchEstimator? = rig?.let { r -> runCatching {
        if (highQualityStitch) StitchEstimator(r, 192, 5).apply { async = false } else StitchEstimator(r, 48, 1)
    }.onFailure { android.util.Log.w("ReframeProgram", "dinamik dikiş kapalı: ${it.message}") }.getOrNull() }
    private var framesSinceEstimate = Int.MAX_VALUE / 2

    /** Düz klip: görüntünün en/boy oranı (döndürme uygulanmış) ve çerçeveye yerleşimi. */
    var flatAspect = 1f
    var look = com.mhmmtbg.x5reframe.model.FlatLook()
    var fit: com.mhmmtbg.x5reframe.model.FrameFit
        get() = look.fit
        set(v) { look = look.copy(fit = v) }
    private val uCrop: Int
    private val uFlip: Int
    private val uKey: Int
    private val uKeyP: Int
    private val uStab: Int
    private val uSrcAspect: Int
    private val uPerson: Int
    private val uPersonP: Int
    /** Kişi maskesi dokusu (0 = yok); [look].person açıkken kullanılır. */
    var personMask = 0
    private val uFlat: Int
    private val uFlatHalf: Int
    private val uFillHalf: Int
    private val uFlatXf: Int
    private val uFlatBlur: Int
    private val uHdr: Int

    init {
        program = link(VS, FS)
        for (i in 0..1) {
            uTex[i] = GLES30.glGetUniformLocation(program, "uTex$i")
            uTexMat[i] = GLES30.glGetUniformLocation(program, "uTexMat$i")
            uCamFromRig[i] = GLES30.glGetUniformLocation(program, "uCamFromRig$i")
            uIntr[i] = GLES30.glGetUniformLocation(program, "uIntr$i")
            uDist[i] = GLES30.glGetUniformLocation(program, "uDist$i")
            uDist2[i] = GLES30.glGetUniformLocation(program, "uDist2$i")
            uSub[i] = GLES30.glGetUniformLocation(program, "uSub$i")
        }
        uView = GLES30.glGetUniformLocation(program, "uView")
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        uMode = GLES30.glGetUniformLocation(program, "uMode")
        uStitch = GLES30.glGetUniformLocation(program, "uStitchInv")
        uFade = GLES30.glGetUniformLocation(program, "uFade")
        for (i in 0..1) uTrans[i] = GLES30.glGetUniformLocation(program, "uTrans$i")
        for (i in 0..3) uGrade[i] = GLES30.glGetUniformLocation(program, "uGrade$i")
        uGradeOn = GLES30.glGetUniformLocation(program, "uGradeOn")
        uFx = GLES30.glGetUniformLocation(program, "uFx")
        uCurve = GLES30.glGetUniformLocation(program, "uCurve")
        uHsl = GLES30.glGetUniformLocation(program, "uHsl")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
        uLutN = GLES30.glGetUniformLocation(program, "uLutN")
        uSeam = GLES30.glGetUniformLocation(program, "uSeam")
        uSeamOn = GLES30.glGetUniformLocation(program, "uSeamOn")
        uSeamRange = GLES30.glGetUniformLocation(program, "uSeamRange")
        uSeamBands = GLES30.glGetUniformLocation(program, "uSeamBands")
        uFlat = GLES30.glGetUniformLocation(program, "uFlat")
        uFlatHalf = GLES30.glGetUniformLocation(program, "uFlatHalf")
        uFillHalf = GLES30.glGetUniformLocation(program, "uFillHalf")
        uFlatXf = GLES30.glGetUniformLocation(program, "uFlatXf")
        uFlatBlur = GLES30.glGetUniformLocation(program, "uFlatBlur")
        uHdr = GLES30.glGetUniformLocation(program, "uHdr")
        uCrop = GLES30.glGetUniformLocation(program, "uCrop")
        uFlip = GLES30.glGetUniformLocation(program, "uFlip")
        uKey = GLES30.glGetUniformLocation(program, "uKey")
        uKeyP = GLES30.glGetUniformLocation(program, "uKeyP")
        uStab = GLES30.glGetUniformLocation(program, "uStab")
        uSrcAspect = GLES30.glGetUniformLocation(program, "uSrcAspect")
        uPerson = GLES30.glGetUniformLocation(program, "uPerson")
        uPersonP = GLES30.glGetUniformLocation(program, "uPersonP")

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    /**
     * Yeni karede dikişi gerekirse yeniden tahmin et.
     * @param every kaç karede bir ölçülür (önizleme 6, dışa aktarım 3)
     */
    fun updateStitch(src: com.mhmmtbg.x5reframe.media.FrameSource, every: Int, smoothing: Float) {
        if (!dynamicStitch || !src.hasFrame) return
        if (++framesSinceEstimate < every) return
        framesSinceEstimate = 0
        stitch?.estimate(src, smoothing)
    }

    /** Arama / klip değişimi: sonraki ölçüm doğrudan uygulanır. */
    fun resetStitch() { stitch?.reset(); framesSinceEstimate = Int.MAX_VALUE / 2 }

    /** @param base sabitlemeden gelen rig_from_view temel dönüşü (yoksa kamera çerçevesi) */
    fun draw(width: Int, height: Int, view: ViewParams, src: com.mhmmtbg.x5reframe.media.FrameSource, base: DoubleArray? = null) =
        drawPane(0, 0, width, height, view, src, base)

    /**
     * Çoklu açı: her bölme kendi kadrajıyla aynı dokulardan çizilir.
     * @param rects normalize (x, y, w, h), sol üst köşe başlangıçlı
     */
    fun drawScene(width: Int, height: Int, rects: List<FloatArray>, views: List<ViewParams>, src: com.mhmmtbg.x5reframe.media.FrameSource, base: DoubleArray?) {
        if (rects.size <= 1) { draw(width, height, views.first(), src, base); return }
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val gap = maxOf(2, width / 400)
        for (i in rects.indices) {
            val r = rects[i]
            var x = (r[0] * width).toInt(); var w = (r[2] * width).toInt()
            var yTop = (r[1] * height).toInt(); var h = (r[3] * height).toInt()
            // Bölmeler arasında ince ayraç
            if (r[0] > 0f) { x += gap / 2; w -= gap / 2 }
            if (r[0] + r[2] < 1f) w -= gap / 2
            if (r[1] > 0f) { yTop += gap / 2; h -= gap / 2 }
            if (r[1] + r[3] < 1f) h -= gap / 2
            val y = height - yTop - h // GL'de başlangıç sol alt
            drawPane(x, y, w, h, views.getOrElse(i) { views.first() }, src, base)
        }
    }

    fun drawPane(x: Int, y: Int, width: Int, height: Int, view: ViewParams, src: com.mhmmtbg.x5reframe.media.FrameSource, base: DoubleArray?) {
        GLES30.glViewport(x, y, width, height)
        GLES30.glUseProgram(program)
        if (rig == null) { drawFlat(width, height, view, src, base); return }
        GLES30.glUniform1i(uFlat, 0)
        for (i in 0..1) {
            val lens: Lens = rig.lenses[i]
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, src.lensTexture(i))
            GLES30.glUniform1i(uTex[i], i)
            GLES30.glUniformMatrix4fv(uTexMat[i], 1, false, src.lensTexMatrix(i), 0)
            val sub = src.lensSubRect(i)
            GLES30.glUniform4f(uSub[i], sub[0], sub[1], sub[2], sub[3])
            GLES30.glUniformMatrix3fv(uCamFromRig[i], 1, true, lens.camFromRig.toFloats(), 0)
            GLES30.glUniform4f(uIntr[i], lens.fx.toFloat(), lens.fy.toFloat(), lens.cx.toFloat(), lens.cy.toFloat())
            GLES30.glUniform4f(uDist[i], lens.k[0].toFloat(), lens.k[1].toFloat(), lens.k[2].toFloat(), lens.k[3].toFloat())
            GLES30.glUniform2f(uDist2[i], lens.k[4].toFloat(), lens.xi.toFloat())
        }
        val user = Rig.mul(Rig.mul(Rig.rotY(Math.toRadians(view.yaw)), Rig.rotX(Math.toRadians(view.pitch))), Rig.rotZ(Math.toRadians(view.roll)))
        val rigFromView = if (base != null) Rig.mul(base, user) else user
        GLES30.glUniformMatrix3fv(uView, 1, true, rigFromView.toFloats(), 0)

        // Uzun kenar 1 olacak şekilde normalize edilmiş ekran koordinatları.
        val sx: Float; val sy: Float
        if (width >= height) { sx = 1f; sy = height.toFloat() / width } else { sx = width.toFloat() / height; sy = 1f }
        val half = Math.toRadians(view.fov) / 2.0
        val k = when (view.projection) {
            Projection.RECTILINEAR -> tan(half)
            Projection.WIDE -> 2.0 * tan(minOf(half, Math.toRadians(179.0)) / 2.0)
            Projection.SUPERWIDE -> 2.0 * tan(half / 2.0)
            Projection.FISHEYE -> half
            Projection.EQUIRECT -> 1.0
        }.toFloat()
        if (view.projection == Projection.EQUIRECT) GLES30.glUniform2f(uScale, 1f, 1f)
        else GLES30.glUniform2f(uScale, sx * k, sy * k)
        GLES30.glUniform1i(uMode, view.projection.ordinal)
        GLES30.glUniform1f(uStitch, stitchInvDistance)
        GLES30.glUniform1f(uFade, fade)
        uploadGrade()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stitch?.seamTexture ?: 0)
        GLES30.glUniform1i(uSeam, 2)
        GLES30.glUniform1f(uSeamOn, if (dynamicStitch && stitch != null) 1f else 0f)
        GLES30.glUniform2f(uSeamRange, StitchEstimator.DELTA_MIN, StitchEstimator.DELTA_MAX)
        GLES30.glUniform2f(uSeamBands, (stitch?.bands ?: 1).toFloat(), StitchEstimator.BAND_SPAN)
        for (i in 0..1) {
            val t = rig.lenses[i].translationRig
            GLES30.glUniform3f(uTrans[i], t[0].toFloat(), t[1].toFloat(), t[2].toFloat())
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Düz klip: sığdır / doldur / bulanık arka plan + kaydırma, yakınlaştırma, döndürme. */
    private fun drawFlat(width: Int, height: Int, view: ViewParams, src: com.mhmmtbg.x5reframe.media.FrameSource, base: DoubleArray?) {
        GLES30.glUniform1i(uFlat, 1)
        // Görüntüden sabitleme: (kayma x, kayma y, dönme, yakınlaştırma); yoksa kapalı
        if (base != null && base.size == 4) GLES30.glUniform4f(uStab, base[0].toFloat(), base[1].toFloat(), base[2].toFloat(), base[3].toFloat())
        else GLES30.glUniform4f(uStab, 0f, 0f, 0f, 0f)
        GLES30.glUniform1f(uSrcAspect, flatAspect)
        val person = look.person
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (person != null) personMask else 0)
        GLES30.glUniform1i(uPerson, 4)
        GLES30.glUniform2f(uPersonP, if (person != null && personMask != 0) 1f else 0f, person?.ordinal?.toFloat() ?: 0f)
        for (i in 0..1) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, src.lensTexture(0))
            GLES30.glUniform1i(uTex[i], i)
            GLES30.glUniformMatrix4fv(uTexMat[i], 1, false, src.lensTexMatrix(0), 0)
            val sub = src.lensSubRect(0)
            GLES30.glUniform4f(uSub[i], sub[0], sub[1], sub[2], sub[3])
        }
        val sx: Float; val sy: Float
        if (width >= height) { sx = 1f; sy = height.toFloat() / width } else { sx = width.toFloat() / height; sy = 1f }
        GLES30.glUniform2f(uScale, sx, sy)
        val cr = look.crop
        val a = (flatAspect * cr.w / cr.h).coerceAtLeast(0.01f)
        GLES30.glUniform4f(uCrop, cr.l, cr.t, cr.w, cr.h)
        GLES30.glUniform2f(uFlip, if (look.flipH) 1f else 0f, if (look.flipV) 1f else 0f)
        val k = look.chroma
        GLES30.glUniform4f(uKey, ((k.color shr 16) and 255) / 255f, ((k.color shr 8) and 255) / 255f, (k.color and 255) / 255f, if (k.on) 1f else 0f)
        GLES30.glUniform4f(uKeyP, k.similarity, k.smooth.coerceAtLeast(0.001f), k.spill, look.chromaBg.ordinal.toFloat())
        val fitHy = minOf(sy, sx / a)
        val fillHy = maxOf(sy, sx / a)
        val hy = if (fit == com.mhmmtbg.x5reframe.model.FrameFit.FILL) fillHy else fitHy
        GLES30.glUniform2f(uFlatHalf, a * hy, hy)
        GLES30.glUniform2f(uFillHalf, a * fillHy, fillHy)
        GLES30.glUniform1f(uFlatBlur, if (fit == com.mhmmtbg.x5reframe.model.FrameFit.BLUR) 1f else 0f)
        GLES30.glUniform4f(uFlatXf, flatZoom(view), (view.yaw / 45.0).toFloat(), (view.pitch / 45.0).toFloat(), Math.toRadians(view.roll).toFloat())
        GLES30.glUniform1i(uHdr, src.hdrTransfer)
        GLES30.glUniform1f(uFade, fade)
        uploadGrade()
        GLES30.glUniform1f(uSeamOn, 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUniform1i(uSeam, 2)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Renk ayarları, efektler, eğri, HSL ve LUT dokusu. */
    private fun uploadGrade() {
        val g = color
        GLES30.glUniform1f(uGradeOn, if (g != null) 1f else 0f)
        // Sampler türleri aynı doku birimini paylaşmasın (sürücü doğrulaması): LUT hep birim 3'te
        GLES30.glUniform1i(uLut, 3)
        if (g == null) {
            GLES30.glUniform4f(uFx, 0f, 0f, 0f, 0f)
            return
        }
        for (i in 0..3) GLES30.glUniform4f(uGrade[i], g[i * 4], g[i * 4 + 1], g[i * 4 + 2], g[i * 4 + 3])
        val ext = g.size >= com.mhmmtbg.x5reframe.model.ColorGrade.UNIFORM_COUNT
        val lutAmt = if (ext) g[23] else 0f
        val lutOk = ext && lutAmt > 0f && bindLut(g[19].toInt())
        GLES30.glUniform4f(uFx, if (ext) g[16] else 0f, if (ext) g[17] else 0f, if (ext) g[18] else 0f, frameTime % 1000f)
        GLES30.glUniform4f(uCurve, if (ext) g[20] else 0f, if (ext) g[21] else 0f, if (ext) g[22] else 0f, if (lutOk) lutAmt else 0f)
        if (ext) GLES30.glUniform3fv(uHsl, 6, g, 24) else GLES30.glUniform3fv(uHsl, 6, FloatArray(18), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, if (lutOk) lutTex else 0)
        GLES30.glUniform1i(uLut, 3)
        GLES30.glUniform1f(uLutN, lutSize.coerceAtLeast(2).toFloat())
    }

    /** LUT kimliğine ait 3B dokuyu yükle (değiştiyse). */
    private fun bindLut(id: Int): Boolean {
        if (id < 0) return false
        if (id == lutId) return lutTex != 0
        lutId = id
        if (lutTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(lutTex), 0); lutTex = 0 }
        val path = com.mhmmtbg.x5reframe.model.LutStore.pathOf(id) ?: return false
        val lut = runCatching { com.mhmmtbg.x5reframe.model.CubeLut.parse(java.io.File(path).readText()) }.getOrNull() ?: return false
        val n = lut.size
        val bytes = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder())
        for (v in lut.data) bytes.put((v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
        bytes.position(0)
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, t[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, n, n, n, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bytes)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        lutTex = t[0]; lutSize = n
        return true
    }

    fun release() {
        if (lutTex != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTex), 0)
        stitch?.release()
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }

    private fun DoubleArray.toFloats() = FloatArray(size) { this[it].toFloat() }

    companion object {
        /** Düz klipte görüş açısı → yakınlaştırma (90° = 1×). */
        fun flatZoom(view: ViewParams): Float = (90.0 / view.fov.coerceAtLeast(1.0)).toFloat()

        fun createOesTexture(): Int {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                GLES30.glDeleteShader(s)
                throw IllegalStateException("shader derlenemedi: $log")
            }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glBindAttribLocation(p, 0, "aPos")
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program bağlanamadı: ${GLES30.glGetProgramInfoLog(p)}" }
            return p
        }

        private const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vNdc;
void main() {
    vNdc = aPos;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private val FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vNdc;
out vec4 outColor;

uniform samplerExternalOES uTex0;
uniform samplerExternalOES uTex1;
uniform mat4 uTexMat0;
uniform mat4 uTexMat1;
uniform mat3 uCamFromRig0;
uniform mat3 uCamFromRig1;
uniform vec4 uIntr0;   // fx fy cx cy (doku koordinatı)
uniform vec4 uIntr1;
uniform vec4 uDist0;   // k1 k2 k3 p1
uniform vec4 uDist1;
uniform vec2 uDist20;  // p2 xi
uniform vec2 uDist21;
uniform vec4 uSub0;    // lensin doku içindeki alt bölgesi (LRV: yan yana)
uniform vec4 uSub1;
uniform mat3 uView;    // rig_from_view
uniform vec2 uScale;
uniform int uMode;     // 0 doğrusal, 1 stereografik, 2 pannini, 3 fisheye, 4 equirect
uniform float uStitchInv; // 1 / dikiş mesafesi
uniform float uFade;
uniform vec3 uTrans0;  // lens merkezleri (rig, metre)
uniform vec3 uTrans1;
uniform sampler2D uSeam;  // bölgesel dikiş: R kayma, G lens1 kazancı
uniform float uSeamOn;
uniform vec2 uSeamRange; // kayma aralığı (derece)
uniform vec2 uSeamBands; // kuşak sayısı, kuşak merkezlerinin dikişe uzaklığı (derece)
uniform float uGradeOn;
uniform vec4 uGrade0;   // pozlama, kontrast, doygunluk, canlılık
uniform vec4 uGrade1;   // sıcaklık, ton, parlak alanlar, gölgeler
uniform vec4 uGrade2;   // soldurma, bölünmüş ton, gölge r, gölge g
uniform vec4 uGrade3;   // gölge b, parlak r, parlak g, parlak b
uniform vec4 uFx;       // kenar karartma, gren, keskinlik, zaman (sn)
uniform vec4 uCurve;    // eğri: koyu, orta, açık; LUT şiddeti
uniform vec3 uHsl[6];   // renk aralıkları: ton, doygunluk, parlaklık
uniform mediump sampler3D uLut;
uniform float uLutN;
uniform int uFlat;      // 1 = düz klip
uniform vec2 uFlatHalf; // görüntünün yarı boyu (uzun kenar = 1 birimlik çıktı uzayında)
uniform vec2 uFillHalf; // doldurma yerleşiminde yarı boy (bulanık arka plan için)
uniform vec4 uFlatXf;   // yakınlaştırma, merkez x, merkez y, döndürme (radyan)
uniform float uFlatBlur;
uniform int uHdr;       // 6 = PQ (HDR10), 7 = HLG; 0 = SDR
uniform vec4 uCrop;     // kırpma: sol, üst, genişlik, yükseklik (kaynakta normalize)
uniform vec2 uFlip;     // yatay / dikey aynalama
uniform vec4 uStab;     // görüntüden sabitleme: kayma x, y, dönme, yakınlaştırma (0 = kapalı)
uniform float uSrcAspect;
uniform sampler2D uPerson; // kişi maskesi (ham görüntü koordinatında)
uniform vec2 uPersonP;     // açık mı, arka plan (0 siyah, 1 beyaz, 2 bulanık)
uniform vec4 uKey;      // renk anahtarı: rgb, açık mı
uniform vec4 uKeyP;     // benzerlik, yumuşaklık, taşma bastırma, arka plan (0 siyah, 1 beyaz, 2 bulanık)

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

vec3 grade(vec3 c) {
    c *= exp2(uGrade0.x);
    c *= vec3(1.0 + 0.12 * uGrade1.x, 1.0 - 0.06 * uGrade1.y, 1.0 - 0.12 * uGrade1.x);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float sh = 1.0 - smoothstep(0.0, 0.55, l);
    float hi = smoothstep(0.45, 1.0, l);
    c += 0.25 * uGrade1.w * sh + 0.25 * uGrade1.z * hi;
    c = (c - 0.5) * (1.0 + uGrade0.y) + 0.5;
    l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    c = mix(vec3(l), c, (1.0 + uGrade0.z) * (1.0 + uGrade0.w * (1.0 - clamp(sat * 2.0, 0.0, 1.0))));
    c += uGrade2.y * (vec3(uGrade2.z, uGrade2.w, uGrade3.x) * sh + uGrade3.yzw * hi);
    c = mix(c, 0.08 + c * 0.84, uGrade2.x);
    c = clamp(c, 0.0, 1.0);
    // Ton eğrisi (parlaklığa göre, üç düğüm)
    if (any(notEqual(uCurve.xyz, vec3(0.0)))) {
        float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float b1 = max(0.0, 1.0 - abs(y - 0.25) / 0.25);
        float b2 = max(0.0, 1.0 - abs(y - 0.5) / 0.25);
        float b3 = max(0.0, 1.0 - abs(y - 0.75) / 0.25);
        float dy = 0.2 * (uCurve.x * b1 * b1 * (3.0 - 2.0 * b1) + uCurve.y * b2 * b2 * (3.0 - 2.0 * b2) + uCurve.z * b3 * b3 * (3.0 - 2.0 * b3));
        c = clamp(c + dy, 0.0, 1.0);
    }
    // HSL: renk aralığına göre ağırlıklı ton kaydırma, doygunluk, parlaklık
    vec3 hsv = rgb2hsv(c);
    if (hsv.y > 0.02) {
        float shift = 0.0, sat = 0.0, lum = 0.0;
        float centers[6] = float[6](0.0, 30.0, 60.0, 120.0, 220.0, 280.0);
        for (int i = 0; i < 6; i++) {
            float d = abs(hsv.x * 360.0 - centers[i]);
            d = min(d, 360.0 - d);
            float w = max(0.0, 1.0 - d / (i == 3 || i == 4 ? 60.0 : 30.0));
            shift += w * uHsl[i].x; sat += w * uHsl[i].y; lum += w * uHsl[i].z;
        }
        if (shift != 0.0 || sat != 0.0 || lum != 0.0) {
            hsv.x = fract(hsv.x + shift * 30.0 / 360.0);
            hsv.y = clamp(hsv.y * (1.0 + sat), 0.0, 1.0);
            hsv.z = clamp(hsv.z * (1.0 + 0.5 * lum * hsv.y), 0.0, 1.0);
            c = hsv2rgb(hsv);
        }
    }
    if (uCurve.w > 0.0) c = mix(c, texture(uLut, c * ((uLutN - 1.0) / uLutN) + 0.5 / uLutN).rgb, uCurve.w);
    return c;
}

// Kenar karartma ve film greni (çıktı pikseline göre)
vec3 finishFx(vec3 c) {
    if (uFx.x > 0.0) {
        vec2 q = vNdc;
        float v = smoothstep(0.35, 1.45, length(q));
        c *= 1.0 - uFx.x * 0.75 * v;
    }
    if (uFx.y > 0.0) {
        float n = fract(sin(dot(gl_FragCoord.xy + fract(uFx.w * 7.13) * 431.0, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
        float y = dot(c, vec3(0.299, 0.587, 0.114));
        c += n * uFx.y * 0.18 * (1.0 - y * 0.6);
    }
    return clamp(c, 0.0, 1.0);
}

const float COS_LIMIT = -0.17365; // lens alanı ~100 derece

""" + GlslCommon.LENS + """
// HDR → SDR: eğriyi doğrusallaştır, BT.2020 → BT.709, yumuşak ton eşleme, ekran gaması.
vec3 hdrToSdr(vec3 e) {
    vec3 lin;
    if (uHdr == 6) {
        vec3 p = pow(max(e, 0.0), vec3(1.0 / 78.84375));
        lin = pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578)) * (10000.0 / 203.0);
    } else {
        vec3 a = max(e, 0.0);
        lin = mix(a * a / 3.0, (exp((a - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, step(0.5, a)) * (1000.0 / 203.0);
    }
    lin = max(mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187) * lin, 0.0);
    float w = 4.9;
    lin = lin * (1.0 + lin / (w * w)) / (1.0 + lin);
    return pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2));
}

// Çerçeve uv → ham görüntü uv (aynalama, kırpma, sabitleme)
vec2 flatUv(vec2 uv) {
    uv = mix(uv, 1.0 - uv, uFlip);
    uv = uCrop.xy + uv * uCrop.zw;
    if (uStab.w > 0.0) {
        vec2 q = (uv - 0.5) / uStab.w;
        q.x *= uSrcAspect;
        float cs = cos(uStab.z), sn = sin(uStab.z);
        q = vec2(cs * q.x - sn * q.y, sn * q.x + cs * q.y);
        q.x /= uSrcAspect;
        uv = q + 0.5 + uStab.xy;
    }
    return uv;
}

vec3 flatColor(vec2 uv) {
    vec3 c = texture(uTex0, texCoord(uTexMat0, uSub0, uv)).rgb;
    return uHdr != 0 ? hdrToSdr(c) : c;
}

vec3 flatSample(vec2 uv) { return flatColor(flatUv(uv)); }

vec2 cbcr(vec3 c) { return vec2(-0.1687 * c.r - 0.3313 * c.g + 0.5 * c.b, 0.5 * c.r - 0.4187 * c.g - 0.0813 * c.b); }

/** Renk anahtarı: görünürlük (0 = anahtar renk) ve taşması bastırılmış renk. */
vec4 keyed(vec3 c) {
    float d = distance(cbcr(c), cbcr(uKey.rgb));
    float a = smoothstep(uKeyP.x * 0.3, uKeyP.x * 0.3 + uKeyP.y * 0.3, d);
    // Taşma: anahtar kanalı diğer ikisinin ortalamasını aşmasın
    vec3 k = uKey.rgb;
    if (k.g >= k.r && k.g >= k.b) c.g = mix(c.g, min(c.g, (c.r + c.b) * 0.5 + 0.02), uKeyP.z * (1.0 - a * 0.5));
    else if (k.b >= k.r) c.b = mix(c.b, min(c.b, (c.r + c.g) * 0.5 + 0.02), uKeyP.z * (1.0 - a * 0.5));
    return vec4(c, a);
}

vec3 flatBlur(vec2 uv) {
    vec3 s = vec3(0.0);
    for (int i = 0; i < 16; i++) {
        float a = float(i) * 2.39996;
        float r = 0.07 * sqrt((float(i) + 0.5) / 16.0);
        s += flatSample(clamp(uv + vec2(cos(a), sin(a)) * r, 0.0, 1.0));
    }
    return s / 16.0;
}

void main() {
    if (uFlat == 1) {
        vec2 fp = vNdc * uScale;                 // uzun kenar [-1, 1], y yukarı
        float cr = cos(uFlatXf.w), sr = sin(uFlatXf.w);
        vec2 q = vec2(cr * fp.x + sr * fp.y, -sr * fp.x + cr * fp.y) / uFlatXf.x + uFlatXf.yz;
        vec2 uv = vec2(q.x / uFlatHalf.x * 0.5 + 0.5, 0.5 - q.y / uFlatHalf.y * 0.5);
        vec2 ddx = dFdx(uv), ddy = dFdy(uv);
        vec3 fc;
        if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
            fc = flatSample(uv);
            if (uFx.z > 0.0) {
                vec3 nb = flatSample(uv + ddx) + flatSample(uv - ddx) + flatSample(uv + ddy) + flatSample(uv - ddy);
                fc = clamp(fc + uFx.z * 1.2 * (fc - nb * 0.25), 0.0, 1.0);
            }
            if (uPersonP.x > 0.5) {
                float pa = smoothstep(0.35, 0.65, texture(uPerson, flatUv(uv)).r);
                vec3 bg = uPersonP.y > 1.5 ? flatBlur(uv) * 0.9
                        : vec3(uPersonP.y > 0.5 ? 1.0 : 0.0);
                fc = mix(bg, fc, pa);
            }
            if (uKey.w > 0.5) {
                vec4 kc = keyed(fc);
                vec3 bg = uKeyP.w > 1.5 ? flatBlur(vec2(fp.x / uFillHalf.x * 0.5 + 0.5, 0.5 - fp.y / uFillHalf.y * 0.5)) * 0.8
                        : vec3(uKeyP.w > 0.5 ? 1.0 : 0.0);
                fc = mix(bg, kc.rgb, kc.a);
            }
        }
        else if (uFlatBlur > 0.5) fc = flatBlur(vec2(fp.x / uFillHalf.x * 0.5 + 0.5, 0.5 - fp.y / uFillHalf.y * 0.5)) * 0.55;
        else fc = vec3(0.0);
        if (uGradeOn > 0.5) fc = finishFx(grade(fc));
        outColor = vec4(fc * uFade, 1.0);
        return;
    }
    vec2 p = vNdc * uScale;
    vec3 dv;
    if (uMode == 1) {            // stereografik
        float r = length(p);
        float theta = 2.0 * atan(r * 0.5);
        vec2 dir = r > 1e-6 ? p / r : vec2(0.0);
        dv = vec3(dir.x * sin(theta), -dir.y * sin(theta), cos(theta));
    } else if (uMode == 2) {     // pannini (d = 1)
        float phi = 2.0 * atan(p.x * 0.5);
        float t = p.y * (1.0 + cos(phi)) * 0.5;
        dv = normalize(vec3(sin(phi), -t, cos(phi)));
    } else if (uMode == 3) {     // eşit uzaklıklı fisheye
        float theta = length(p);
        vec2 dir = theta > 1e-6 ? p / theta : vec2(0.0);
        dv = vec3(dir.x * sin(theta), -dir.y * sin(theta), cos(theta));
    } else if (uMode == 4) {     // equirect
        float lon = vNdc.x * 3.14159265;
        float lat = -vNdc.y * 1.57079633;
        dv = vec3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon));
    } else {
        dv = normalize(vec3(p.x, -p.y, 1.0)); // +y aşağı
    }
    vec3 d = uView * dv;

    // Bölgesel dikiş: dikiş çemberindeki açıya göre iki lens ters yönlerde kaydırılır
    // (paralaks telafisi), etki dikişten uzaklaştıkça sönümlenir.
    float near = 1.0 - smoothstep(0.25, 0.6, abs(d.z));
    vec3 e0 = d, e1 = d;
    float g0 = 1.0, g1 = 1.0;
    if (uSeamOn > 0.5 && near > 0.0) {
        float u = atan(d.y, d.x) * 0.15915494 + 0.5;
        float t = clamp((degrees(asin(clamp(d.z, -1.0, 1.0))) + uSeamBands.y) / (2.0 * uSeamBands.y), 0.0, 1.0);
        float v = (0.5 + t * (uSeamBands.x - 1.0)) / uSeamBands.x;
        vec4 sm = texture(uSeam, vec2(u, v));
        float delta = radians(mix(uSeamRange.x, uSeamRange.y, sm.r));
        float k = tan(delta * 0.5) * near;
        e0 = normalize(d + vec3(0.0, 0.0, k));
        e1 = normalize(d - vec3(0.0, 0.0, k));
        float g = sm.g + 0.5;               // lens0 / lens1 parlaklık oranı
        float sg = sqrt(g);
        g0 = mix(1.0, 1.0 / sg, near);
        g1 = mix(1.0, sg, near);
    }
    vec3 c0 = uCamFromRig0 * (e0 - uTrans0 * uStitchInv);
    vec3 c1 = uCamFromRig1 * (e1 - uTrans1 * uStitchInv);
    // Yumuşak geçiş (~±3,5 derece): dar tutulur ki kalan küçük hizasızlık çift görüntüye dönüşmesin.
    float w0 = smoothstep(-0.12, 0.12, d.z * 2.0);
    if (c0.z < COS_LIMIT) w0 = 0.0;
    if (c1.z < COS_LIMIT) w0 = 1.0;

    vec2 tc0 = texCoord(uTexMat0, uSub0, project(c0, uIntr0, uDist0, uDist20));
    vec2 tc1 = texCoord(uTexMat1, uSub1, project(c1, uIntr1, uDist1, uDist21));
    vec3 col = vec3(0.0);
    if (w0 > 0.0) col += w0 * g0 * texture(uTex0, tc0).rgb;
    if (w0 < 1.0) col += (1.0 - w0) * g1 * texture(uTex1, tc1).rgb;
    if (uFx.z > 0.0) {
        // Keskinlik: baskın lensin dokusunda çıktı pikseli kadar komşularla (laplace)
        vec2 dx0 = dFdx(tc0), dy0 = dFdy(tc0), dx1 = dFdx(tc1), dy1 = dFdy(tc1);
        vec3 nb;
        if (w0 >= 0.5) nb = texture(uTex0, tc0 + dx0).rgb + texture(uTex0, tc0 - dx0).rgb + texture(uTex0, tc0 + dy0).rgb + texture(uTex0, tc0 - dy0).rgb;
        else nb = texture(uTex1, tc1 + dx1).rgb + texture(uTex1, tc1 - dx1).rgb + texture(uTex1, tc1 + dy1).rgb + texture(uTex1, tc1 - dy1).rgb;
        nb *= (w0 >= 0.5 ? g0 : g1);
        col = clamp(col + uFx.z * 1.2 * (col - nb * 0.25), 0.0, 1.0);
    }
    if (uGradeOn > 0.5) col = finishFx(grade(col));
    outColor = vec4(col * uFade, 1.0);
}
"""
    }
}

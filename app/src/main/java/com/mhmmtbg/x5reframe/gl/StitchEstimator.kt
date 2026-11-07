package com.mhmmtbg.x5reframe.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.mhmmtbg.x5reframe.insv.Rig
import com.mhmmtbg.x5reframe.media.FrameSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Dinamik dikiş: dikiş çemberi (rig z = 0) [BINS] bölgeye ayrılır; her bölgede iki lensin
 * birbirine göre ne kadar kayması gerektiği (paralaks, derece) GPU'da aranır ve sonuç
 * [seamTexture] dokusuna yazılır. Kadraj shader'ı bu dokudan okuyarak dikişi bölgesel hizalar.
 *
 * Maliyet: dikiş penceresinde iki lensin parlaklık farkının varyansı / toplam varyans
 * (pozlama farkından etkilenmez). Dokusuz bölgeler (gökyüzü) güvenilmez sayılır, komşulardan alınır.
 * Renk: aynı pencerelerden lensler arası parlaklık oranı da ölçülür.
 */
class StitchEstimator(private val rig: Rig) {

    companion object {
        const val BINS = 48
        const val NDELTA = 33
        const val DELTA_MIN = -2.0f
        const val DELTA_MAX = 6.0f
        private const val ROWS = NDELTA + 1

        private val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        private val FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
out vec4 outColor;
uniform samplerExternalOES uTex0;
uniform samplerExternalOES uTex1;
uniform mat4 uTexMat0;
uniform mat4 uTexMat1;
uniform mat3 uCamFromRig0;
uniform mat3 uCamFromRig1;
uniform vec4 uIntr0;
uniform vec4 uIntr1;
uniform vec4 uDist0;
uniform vec4 uDist1;
uniform vec2 uDist20;
uniform vec2 uDist21;
uniform vec4 uSub0;
uniform vec4 uSub1;

const int BINS = $BINS;
const int NDELTA = $NDELTA;
const float DMIN = $DELTA_MIN;
const float DMAX = $DELTA_MAX;

${GlslCommon.LENS}

float luma0(vec3 d) {
    vec2 uv = project(uCamFromRig0 * d, uIntr0, uDist0, uDist20);
    return dot(texture(uTex0, texCoord(uTexMat0, uSub0, uv)).rgb, vec3(0.299, 0.587, 0.114));
}
float luma1(vec3 d) {
    vec2 uv = project(uCamFromRig1 * d, uIntr1, uDist1, uDist21);
    return dot(texture(uTex1, texCoord(uTexMat1, uSub1, uv)).rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
    int bin = int(gl_FragCoord.x);
    int row = int(gl_FragCoord.y);
    float phi = (float(bin) + 0.5) / float(BINS) * 6.2831853 - 3.1415927;
    float delta = radians(DMIN + (DMAX - DMIN) * float(min(row, NDELTA - 1)) / float(NDELTA - 1));
    float k = row < NDELTA ? tan(delta * 0.5) : 0.0;
    float sa = 0.0, sb = 0.0, saa = 0.0, sbb = 0.0, sd = 0.0, sdd = 0.0;
    float n = 0.0;
    for (int i = 0; i < 8; i++) {
        float along = radians(-3.5 + float(i));
        float cp = cos(phi + along), sp = sin(phi + along);
        for (int j = 0; j < 9; j++) {
            float off = radians(-4.0 + float(j));
            vec3 d = vec3(cos(off) * cp, cos(off) * sp, sin(off));
            float a = luma0(normalize(d + vec3(0.0, 0.0, k)));
            float b = luma1(normalize(d - vec3(0.0, 0.0, k)));
            sa += a; sb += b; saa += a * a; sbb += b * b;
            float df = a - b; sd += df; sdd += df * df;
            n += 1.0;
        }
    }
    float ma = sa / n, mb = sb / n;
    float va = max(saa / n - ma * ma, 0.0), vb = max(sbb / n - mb * mb, 0.0);
    if (row < NDELTA) {
        float vd = max(sdd / n - (sd / n) * (sd / n), 0.0);
        float c = clamp(vd / (va + vb + 1e-4), 0.0, 1.999) * 0.5;   // 0..1
        float hi = floor(c * 255.0) / 255.0;
        outColor = vec4(hi, fract(c * 255.0), 0.0, 1.0);
    } else {
        outColor = vec4(sqrt(va), 0.0, ma, mb);
    }
}
"""
    }

    /** BINS x 1 RGBA8: R = kayma (DELTA_MIN..DELTA_MAX), G = lens1 kazancı (0.5..1.5). */
    val seamTexture: Int
    private val fbo: Int
    private val fboTex: Int
    private val program: Int
    private val vbo: Int
    private val loc = HashMap<String, Int>()
    private val pixels = ByteBuffer.allocateDirect(BINS * ROWS * 4).order(ByteOrder.nativeOrder())
    private val upload = ByteBuffer.allocateDirect(BINS * 4).order(ByteOrder.nativeOrder())

    /** Zamanla yumuşatılmış sonuçlar */
    val delta = FloatArray(BINS)
    val gain = FloatArray(BINS) { 1f }
    private var initialized = false

    /** Son ölçümün özeti (test/istatistik): güvenilir bölge sayısı, kayma 0'daki ve seçilen kaymadaki ortalama maliyet. */
    var lastReport = ""
        private set

    init {
        program = GlUtil.link(VS, FS)
        listOf("uTex0", "uTex1", "uTexMat0", "uTexMat1", "uCamFromRig0", "uCamFromRig1", "uIntr0", "uIntr1",
            "uDist0", "uDist1", "uDist20", "uDist21", "uSub0", "uSub1").forEach { loc[it] = GLES30.glGetUniformLocation(program, it) }

        val t = IntArray(2)
        GLES30.glGenTextures(2, t, 0)
        fboTex = t[0]; seamTexture = t[1]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, BINS, ROWS, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, seamTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        writeTexture()

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    /** Sabit kayma (elle seçilen dikiş mesafesi) yaz; tahmin yapılmaz. */
    fun setFixed(deltaDeg: Float) {
        delta.fill(deltaDeg); gain.fill(1f); initialized = false
        writeTexture()
    }

    /** Ani sahne değişiminde (arama) bir sonraki tahmin yumuşatılmadan uygulanır. */
    fun reset() { initialized = false }

    /**
     * Geçerli karelerden dikişi tahmin et ve dokuyu güncelle.
     * @param smoothing 0..1, yeni ölçümün ağırlığı (önizleme ~0,35, dışa aktarım ~0,5)
     */
    fun estimate(src: FrameSource, smoothing: Float) {
        val prevFbo = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        val prevVp = IntArray(4); GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevVp, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, BINS, ROWS)
        GLES30.glUseProgram(program)
        for (i in 0..1) {
            val lens = rig.lenses[i]
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, src.lensTexture(i))
            GLES30.glUniform1i(loc["uTex$i"]!!, i)
            GLES30.glUniformMatrix4fv(loc["uTexMat$i"]!!, 1, false, src.lensTexMatrix(i), 0)
            val sub = src.lensSubRect(i)
            GLES30.glUniform4f(loc["uSub$i"]!!, sub[0], sub[1], sub[2], sub[3])
            GLES30.glUniformMatrix3fv(loc["uCamFromRig$i"]!!, 1, true, FloatArray(9) { lens.camFromRig[it].toFloat() }, 0)
            GLES30.glUniform4f(loc["uIntr$i"]!!, lens.fx.toFloat(), lens.fy.toFloat(), lens.cx.toFloat(), lens.cy.toFloat())
            GLES30.glUniform4f(loc["uDist$i"]!!, lens.k[0].toFloat(), lens.k[1].toFloat(), lens.k[2].toFloat(), lens.k[3].toFloat())
            GLES30.glUniform2f(loc["uDist2$i"]!!, lens.k[4].toFloat(), lens.xi.toFloat())
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        pixels.rewind()
        GLES30.glReadPixels(0, 0, BINS, ROWS, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])

        solve(smoothing)
        writeTexture()
    }

    private fun px(x: Int, y: Int, c: Int) = (pixels.get((y * BINS + x) * 4 + c).toInt() and 0xff)

    private fun solve(smoothing: Float) {
        val raw = FloatArray(BINS)
        val conf = FloatArray(BINS)
        val g = FloatArray(BINS) { 1f }
        for (k in 0 until BINS) {
            // Son satır: doku gücü ve lens ortalamaları
            val texture = px(k, NDELTA, 0) / 255f
            val m0 = px(k, NDELTA, 2) / 255f
            val m1 = px(k, NDELTA, 3) / 255f
            if (m1 > 0.03f && m0 > 0.03f) g[k] = (m0 / m1).coerceIn(0.6f, 1.6f)
            var best = 0; var bestC = Float.MAX_VALUE
            var sum = 0f
            val costs = FloatArray(NDELTA)
            for (j in 0 until NDELTA) {
                val c = (px(k, j, 0) + px(k, j, 1) / 255f) / 255f * 2f
                costs[j] = c; sum += c
                if (c < bestC) { bestC = c; best = j }
            }
            val mean = sum / NDELTA
            // Alt-adım (parabol) ince ayarı
            var jf = best.toFloat()
            if (best in 1 until NDELTA - 1) {
                val a = costs[best - 1]; val b = costs[best]; val c = costs[best + 1]
                val den = a - 2 * b + c
                if (den > 1e-6f) jf += (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f)
            }
            raw[k] = DELTA_MIN + (DELTA_MAX - DELTA_MIN) * jf / (NDELTA - 1)
            // Güven: belirgin minimum ve yeterli doku
            conf[k] = (((mean - bestC) / (mean + 1e-4f)) * 3f).coerceIn(0f, 1f) * (texture * 10f).coerceIn(0f, 1f)
        }
        run {
            val zeroIdx = ((0f - DELTA_MIN) / (DELTA_MAX - DELTA_MIN) * (NDELTA - 1) + 0.5f).toInt()
            var n = 0; var c0 = 0f; var cb = 0f
            for (k in 0 until BINS) if (conf[k] > 0.3f) {
                n++
                c0 += (px(k, zeroIdx, 0) + px(k, zeroIdx, 1) / 255f) / 255f * 2f
                var m = Float.MAX_VALUE
                for (j in 0 until NDELTA) m = minOf(m, (px(k, j, 0) + px(k, j, 1) / 255f) / 255f * 2f)
                cb += m
            }
            lastReport = if (n > 0) "bölge=$n maliyet ${"%.3f".format(c0 / n)} -> ${"%.3f".format(cb / n)}" else "bölge=0"
        }
        // Güvenilir komşularla ağırlıklı medyan benzeri yumuşatma (dairesel)
        val sm = FloatArray(BINS)
        for (k in 0 until BINS) {
            var ws = 0f; var vs = 0f
            for (o in -3..3) {
                val q = (k + o + BINS) % BINS
                val w = conf[q] * (1f - abs(o) / 4f)
                ws += w; vs += w * raw[q]
            }
            val prev = if (initialized) delta[k] else 0f
            // Güven azsa önceki değere (ilk karede uzak = 0'a) yakın kal
            sm[k] = if (ws > 0.15f) vs / ws else prev * 0.9f
        }
        val a = if (initialized) smoothing else 1f
        for (k in 0 until BINS) {
            delta[k] += a * (sm[k] - delta[k])
            gain[k] += a * (g[k] - gain[k])
        }
        initialized = true
    }

    private fun writeTexture() {
        upload.rewind()
        for (k in 0 until BINS) {
            val d = ((delta[k] - DELTA_MIN) / (DELTA_MAX - DELTA_MIN)).coerceIn(0f, 1f)
            val gg = (gain[k] - 0.5f).coerceIn(0f, 1f)
            upload.put((d * 255f + 0.5f).toInt().toByte())
            upload.put((gg * 255f + 0.5f).toInt().toByte())
            upload.put(0); upload.put(-1)
        }
        upload.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, seamTexture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, BINS, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, upload)
    }

    fun release() {
        GLES30.glDeleteTextures(2, intArrayOf(fboTex, seamTexture), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }
}

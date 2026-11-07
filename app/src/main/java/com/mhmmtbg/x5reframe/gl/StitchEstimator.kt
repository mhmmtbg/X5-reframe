package com.mhmmtbg.x5reframe.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.mhmmtbg.x5reframe.insv.Rig
import com.mhmmtbg.x5reframe.media.FrameSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Dinamik dikiş: dikiş çemberi (rig z = 0) [bins] bölgeye, dikişe dik yönde [bands] kuşağa ayrılır.
 * Her hücrede iki lensin birbirine göre ne kadar kayması gerektiği (paralaks, derece) GPU'da
 * blok eşleştirmeyle aranır ve sonuç [seamTexture] (bins x bands) dokusuna yazılır.
 * Kadraj shader'ı bu dokudan okuyarak dikişi hücre hücre hizalar (bir tür seyrek optik akış).
 *
 * - Önizleme: 48 bölge x 1 kuşak, 6 karede bir.
 * - Dışa aktarım: 192 bölge x 3 kuşak, 3 karede bir (ince taneli akış).
 *
 * Maliyet: penceredeki iki lens parlaklık farkının varyansı / toplam varyans (pozlamadan bağımsız).
 * Dokusuz hücreler (gökyüzü) güvenilmez sayılır, komşulardan ve önceki kareden doldurulur.
 */
class StitchEstimator(private val rig: Rig, val bins: Int = 48, val bands: Int = 1) {

    companion object {
        const val NDELTA = 37
        const val DELTA_MIN = -3.0f
        const val DELTA_MAX = 9.0f
        /** Kuşak merkezlerinin dikişe uzaklığı (derece, ±) */
        const val BAND_SPAN = 8.0f
        private const val ROWS = NDELTA + 1

        private const val VS = """#version 300 es
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
uniform int uBins;
uniform int uBands;

const int NDELTA = $NDELTA;
const int ROWS = $ROWS;
const float DMIN = $DELTA_MIN;
const float DMAX = $DELTA_MAX;
const float SPAN = $BAND_SPAN;

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
    int y = int(gl_FragCoord.y);
    int band = y / ROWS;
    int row = y - band * ROWS;
    float binW = 6.2831853 / float(uBins);
    float phi = (float(bin) + 0.5) * binW - 3.1415927;
    float center = uBands > 1 ? radians(mix(-SPAN, SPAN, float(band) / float(uBands - 1))) : 0.0;
    // Pencere: dikiş boyunca bölge genişliğinin ~2 katı (en az 7 derece), dikişe dik ±4 derece
    float alongStep = max(binW * 2.0, radians(7.0)) / 8.0;
    float delta = radians(DMIN + (DMAX - DMIN) * float(min(row, NDELTA - 1)) / float(NDELTA - 1));
    float k = row < NDELTA ? tan(delta * 0.5) : 0.0;
    float sa = 0.0, sb = 0.0, saa = 0.0, sbb = 0.0, sd = 0.0, sdd = 0.0;
    float n = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = phi + (float(i) - 3.5) * alongStep;
        float cp = cos(a), sp = sin(a);
        for (int j = 0; j < 9; j++) {
            float off = center + radians(-4.0 + float(j));
            vec3 d = vec3(cos(off) * cp, cos(off) * sp, sin(off));
            float l0 = luma0(normalize(d + vec3(0.0, 0.0, k)));
            float l1 = luma1(normalize(d - vec3(0.0, 0.0, k)));
            sa += l0; sb += l1; saa += l0 * l0; sbb += l1 * l1;
            float df = l0 - l1; sd += df; sdd += df * df;
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

    private val height = bands * ROWS

    /** bins x bands RGBA8: R = kayma (DELTA_MIN..DELTA_MAX), G = lens0/lens1 parlaklık oranı - 0,5. */
    val seamTexture: Int
    private val fbo: Int
    private val fboTex: Int
    private val program: Int
    private val vbo: Int
    private val loc = HashMap<String, Int>()
    private var pixels: ByteBuffer = ByteBuffer.allocateDirect(bins * height * 4).order(ByteOrder.nativeOrder())
    /** İki PBO ile eşzamansız okuma: GPU'yu bekletmeden bir önceki ölçüm işlenir. */
    private val pbo = IntArray(2)
    private var pboIndex = 0
    private var pboPending = false
    /** true: okuma bir ölçüm gecikmeli ama GPU beklemez (önizleme). */
    var async = true
    private val upload = ByteBuffer.allocateDirect(bins * bands * 4).order(ByteOrder.nativeOrder())

    /** Zamanla yumuşatılmış sonuçlar, [band * bins + bin] */
    val delta = FloatArray(bins * bands)
    val gain = FloatArray(bins * bands) { 1f }
    private var initialized = false

    /** Son ölçümün özeti (test/istatistik): güvenilir hücre sayısı, kayma 0'daki ve seçilen kaymadaki ortalama maliyet. */
    var lastReport = ""
        private set

    init {
        program = GlUtil.link(VS, FS)
        listOf("uTex0", "uTex1", "uTexMat0", "uTexMat1", "uCamFromRig0", "uCamFromRig1", "uIntr0", "uIntr1",
            "uDist0", "uDist1", "uDist20", "uDist21", "uSub0", "uSub1", "uBins", "uBands").forEach { loc[it] = GLES30.glGetUniformLocation(program, it) }

        val t = IntArray(2)
        GLES30.glGenTextures(2, t, 0)
        fboTex = t[0]; seamTexture = t[1]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bins, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        GLES30.glGenBuffers(2, pbo, 0)
        for (b in pbo) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bins * height * 4, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

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

    /** Ani sahne değişiminde (arama) bir sonraki tahmin yumuşatılmadan uygulanır. */
    fun reset() { initialized = false; pboPending = false }

    /**
     * Geçerli karelerden dikişi tahmin et ve dokuyu güncelle.
     * @param smoothing 0..1, yeni ölçümün ağırlığı
     */
    fun estimate(src: FrameSource, smoothing: Float) {
        val prevFbo = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        val prevVp = IntArray(4); GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevVp, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, bins, height)
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
        GLES30.glUniform1i(loc["uBins"]!!, bins)
        GLES30.glUniform1i(loc["uBands"]!!, bands)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        var ready = false
        if (async) {
            // Bu ölçümü PBO'ya yaz; varsa bir öncekini oku (o çoktan bitmiştir).
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[pboIndex])
            GLES30.glReadPixels(0, 0, bins, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
            val prev = pbo[1 - pboIndex]
            if (pboPending) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, prev)
                val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bins * height * 4, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
                if (mapped != null) {
                    pixels.rewind(); mapped.order(ByteOrder.nativeOrder()); pixels.put(mapped); pixels.rewind()
                    ready = true
                }
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            pboPending = true
            pboIndex = 1 - pboIndex
        } else {
            pixels.rewind()
            GLES30.glReadPixels(0, 0, bins, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            ready = true
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])

        if (ready) {
            solve(smoothing)
            writeTexture()
        }
    }

    private fun px(x: Int, y: Int, c: Int) = (pixels.get((y * bins + x) * 4 + c).toInt() and 0xff)
    private fun cost(x: Int, band: Int, j: Int) = (px(x, band * ROWS + j, 0) + px(x, band * ROWS + j, 1) / 255f) / 255f * 2f

    private fun solve(smoothing: Float) {
        val cells = bins * bands
        val raw = FloatArray(cells)
        val conf = FloatArray(cells)
        val g = FloatArray(cells) { 1f }
        val costs = FloatArray(NDELTA)
        val zeroIdx = ((0f - DELTA_MIN) / (DELTA_MAX - DELTA_MIN) * (NDELTA - 1) + 0.5f).toInt()
        var nRep = 0; var c0Rep = 0f; var cbRep = 0f
        for (b in 0 until bands) for (k in 0 until bins) {
            val cell = b * bins + k
            val statRow = b * ROWS + NDELTA
            val texture = px(k, statRow, 0) / 255f
            val m0 = px(k, statRow, 2) / 255f
            val m1 = px(k, statRow, 3) / 255f
            if (m1 > 0.03f && m0 > 0.03f) g[cell] = (m0 / m1).coerceIn(0.6f, 1.5f)
            var best = 0; var bestC = Float.MAX_VALUE
            var sum = 0f
            for (j in 0 until NDELTA) {
                val c = cost(k, b, j)
                costs[j] = c; sum += c
                if (c < bestC) { bestC = c; best = j }
            }
            val mean = sum / NDELTA
            // Alt-adım (parabol) ince ayarı
            var jf = best.toFloat()
            if (best in 1 until NDELTA - 1) {
                val a = costs[best - 1]; val bb = costs[best]; val c = costs[best + 1]
                val den = a - 2 * bb + c
                if (den > 1e-6f) jf += (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f)
            }
            raw[cell] = DELTA_MIN + (DELTA_MAX - DELTA_MIN) * jf / (NDELTA - 1)
            // Güven: belirgin minimum ve yeterli doku
            conf[cell] = (((mean - bestC) / (mean + 1e-4f)) * 3f).coerceIn(0f, 1f) * (texture * 10f).coerceIn(0f, 1f)
            if (conf[cell] > 0.3f) { nRep++; c0Rep += costs[zeroIdx]; cbRep += bestC }
        }
        lastReport = if (nRep > 0) "bölge=$nRep maliyet ${"%.3f".format(c0Rep / nRep)} -> ${"%.3f".format(cbRep / nRep)}" else "bölge=0"

        // Güven ağırlıklı yumuşatma: dikiş boyunca (dairesel) ve kuşaklar arasında
        val reach = maxOf(3, bins / 16)
        val sm = FloatArray(cells)
        for (b in 0 until bands) for (k in 0 until bins) {
            var ws = 0f; var vs = 0f
            for (bb in maxOf(0, b - 1)..minOf(bands - 1, b + 1)) {
                val bw = if (bb == b) 1f else 0.35f
                for (o in -reach..reach) {
                    val q = bb * bins + (k + o + bins) % bins
                    val w = conf[q] * bw * (1f - abs(o) / (reach + 1f))
                    ws += w; vs += w * raw[q]
                }
            }
            val cell = b * bins + k
            val prev = if (initialized) delta[cell] else 0f
            // Güven azsa önceki değere (ilk karede uzak = 0'a) yakın kal
            sm[cell] = if (ws > 0.15f) vs / ws else prev * 0.9f
        }
        // Renk: lensler arası oran sahneye göre çok oynak ölçülür (hizasız içerik, gölge).
        // Güvenilir hücrelerin ortancası etrafında ±%8 ile sınırla ve dikiş boyunca geniş yumuşat.
        val valid = (0 until cells).filter { conf[it] > 0.2f }.map { g[it] }.sorted()
        val gMed = if (valid.size >= 4) valid[valid.size / 2] else 1f
        val gc = FloatArray(cells) { g[it].coerceIn(gMed * 0.92f, gMed * 1.08f) }
        val gs = FloatArray(cells)
        val greach = maxOf(4, bins / 8)
        for (b in 0 until bands) for (k in 0 until bins) {
            var ws = 0f; var vs = 0f
            for (o in -greach..greach) {
                val q = b * bins + (k + o + bins) % bins
                val w = 0.2f + conf[q]
                ws += w; vs += w * gc[q]
            }
            gs[b * bins + k] = vs / ws
        }
        val a = if (initialized) smoothing else 1f
        for (i in 0 until cells) {
            delta[i] += a * (sm[i] - delta[i])
            gain[i] += a * 0.5f * (gs[i] - gain[i])
        }
        initialized = true
    }

    private fun writeTexture() {
        upload.rewind()
        for (i in 0 until bins * bands) {
            val d = ((delta[i] - DELTA_MIN) / (DELTA_MAX - DELTA_MIN)).coerceIn(0f, 1f)
            val gg = (gain[i] - 0.5f).coerceIn(0f, 1f)
            upload.put((d * 255f + 0.5f).toInt().toByte())
            upload.put((gg * 255f + 0.5f).toInt().toByte())
            upload.put(0); upload.put(-1)
        }
        upload.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, seamTexture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bins, bands, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, upload)
    }

    fun release() {
        GLES30.glDeleteTextures(2, intArrayOf(fboTex, seamTexture), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteBuffers(2, pbo, 0)
    }
}

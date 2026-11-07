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
 */
class ReframeProgram(private val rig: Rig) {

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

    /** 0..1 parlaklık çarpanı (siyaha geçiş). */
    var fade = 1f
    private val uFade: Int
    private val uSeam: Int
    private val uSeamOn: Int
    private val uSeamRange: Int

    /** Bölgesel (dinamik) dikiş; kapalıyken klasik tek mesafeli dikiş. */
    var dynamicStitch = true
    val stitch = StitchEstimator(rig)
    private var framesSinceEstimate = Int.MAX_VALUE / 2

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
        uSeam = GLES30.glGetUniformLocation(program, "uSeam")
        uSeamOn = GLES30.glGetUniformLocation(program, "uSeamOn")
        uSeamRange = GLES30.glGetUniformLocation(program, "uSeamRange")

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
        stitch.estimate(src, smoothing)
    }

    /** Arama / klip değişimi: sonraki ölçüm doğrudan uygulanır. */
    fun resetStitch() { stitch.reset(); framesSinceEstimate = Int.MAX_VALUE / 2 }

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
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stitch.seamTexture)
        GLES30.glUniform1i(uSeam, 2)
        GLES30.glUniform1f(uSeamOn, if (dynamicStitch) 1f else 0f)
        GLES30.glUniform2f(uSeamRange, StitchEstimator.DELTA_MIN, StitchEstimator.DELTA_MAX)
        for (i in 0..1) {
            val t = rig.lenses[i].translationRig
            GLES30.glUniform3f(uTrans[i], t[0].toFloat(), t[1].toFloat(), t[2].toFloat())
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        stitch.release()
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }

    private fun DoubleArray.toFloats() = FloatArray(size) { this[it].toFloat() }

    companion object {
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

const float COS_LIMIT = -0.17365; // lens alanı ~100 derece

""" + GlslCommon.LENS + """
void main() {
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
        vec4 sm = texture(uSeam, vec2(u, 0.5));
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
    // Geniş, yumuşak geçiş (~±6 derece); hizalama sonrası çift görüntü oluşmaz.
    float w0 = smoothstep(-0.21, 0.21, d.z * 2.0);
    if (c0.z < COS_LIMIT) w0 = 0.0;
    if (c1.z < COS_LIMIT) w0 = 1.0;

    vec3 col = vec3(0.0);
    if (w0 > 0.0) col += w0 * g0 * texture(uTex0, texCoord(uTexMat0, uSub0, project(c0, uIntr0, uDist0, uDist20))).rgb;
    if (w0 < 1.0) col += (1.0 - w0) * g1 * texture(uTex1, texCoord(uTexMat1, uSub1, project(c1, uIntr1, uDist1, uDist21))).rgb;
    outColor = vec4(col * uFade, 1.0);
}
"""
    }
}

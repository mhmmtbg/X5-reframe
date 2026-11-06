package com.mhmmtbg.x5reframe.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.mhmmtbg.x5reframe.insv.Lens
import com.mhmmtbg.x5reframe.insv.Rig
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
    private val uView: Int
    private val uScale: Int
    private val uMode: Int

    init {
        program = link(VS, FS)
        for (i in 0..1) {
            uTex[i] = GLES30.glGetUniformLocation(program, "uTex$i")
            uTexMat[i] = GLES30.glGetUniformLocation(program, "uTexMat$i")
            uCamFromRig[i] = GLES30.glGetUniformLocation(program, "uCamFromRig$i")
            uIntr[i] = GLES30.glGetUniformLocation(program, "uIntr$i")
            uDist[i] = GLES30.glGetUniformLocation(program, "uDist$i")
            uDist2[i] = GLES30.glGetUniformLocation(program, "uDist2$i")
        }
        uView = GLES30.glGetUniformLocation(program, "uView")
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        uMode = GLES30.glGetUniformLocation(program, "uMode")

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
     * @param trackTex izin sırasına göre OES doku kimlikleri
     * @param trackTexMat izin sırasına göre SurfaceTexture dönüşüm matrisleri
     */
    fun draw(width: Int, height: Int, view: ViewParams, trackTex: IntArray, trackTexMat: Array<FloatArray>) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)
        for (i in 0..1) {
            val lens: Lens = rig.lenses[i]
            val track = lens.videoTrackOrder
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, trackTex[track])
            GLES30.glUniform1i(uTex[i], i)
            GLES30.glUniformMatrix4fv(uTexMat[i], 1, false, trackTexMat[track], 0)
            GLES30.glUniformMatrix3fv(uCamFromRig[i], 1, true, lens.camFromRig.toFloats(), 0)
            GLES30.glUniform4f(uIntr[i], lens.fx.toFloat(), lens.fy.toFloat(), lens.cx.toFloat(), lens.cy.toFloat())
            GLES30.glUniform4f(uDist[i], lens.k[0].toFloat(), lens.k[1].toFloat(), lens.k[2].toFloat(), lens.k[3].toFloat())
            GLES30.glUniform2f(uDist2[i], lens.k[4].toFloat(), lens.xi.toFloat())
        }
        val rigFromView = Rig.mul(Rig.mul(Rig.rotY(Math.toRadians(view.yaw)), Rig.rotX(Math.toRadians(view.pitch))), Rig.rotZ(Math.toRadians(view.roll)))
        GLES30.glUniformMatrix3fv(uView, 1, true, rigFromView.toFloats(), 0)

        // Uzun kenar 1 olacak şekilde normalize edilmiş ekran koordinatları.
        val sx: Float; val sy: Float
        if (width >= height) { sx = 1f; sy = height.toFloat() / width } else { sx = width.toFloat() / height; sy = 1f }
        val half = Math.toRadians(view.fov) / 2.0
        val k = if (view.wide) (2.0 * tan(half / 2.0)).toFloat() else tan(half).toFloat()
        GLES30.glUniform2f(uScale, sx * k, sy * k)
        GLES30.glUniform1i(uMode, if (view.wide) 1 else 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
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

        private const val FS = """#version 300 es
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
uniform mat3 uView;    // rig_from_view
uniform vec2 uScale;
uniform int uMode;     // 0: düz (rectilinear), 1: geniş (stereografik)

const float COS_LIMIT = -0.17365; // lens alanı ~100 derece

vec2 project(vec3 c, vec4 intr, vec4 d1, vec2 d2) {
    c = normalize(c);
    vec2 m = c.xy / (c.z + d2.y);
    float r2 = dot(m, m);
    float rad = 1.0 + d1.x * r2 + d1.y * r2 * r2 + d1.z * r2 * r2 * r2;
    float p1 = d1.w;
    float p2 = d2.x;
    vec2 md = m * rad + vec2(2.0 * p1 * m.x * m.y + p2 * (r2 + 2.0 * m.x * m.x),
                             2.0 * p2 * m.x * m.y + p1 * (r2 + 2.0 * m.y * m.y));
    return vec2(intr.x * md.x + intr.z, intr.y * md.y + intr.w);
}

vec2 texCoord(mat4 texMat, vec2 uv) {
    return (texMat * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;
}

void main() {
    vec2 p = vNdc * uScale;
    vec3 dv;
    if (uMode == 1) {
        float r = length(p);
        float theta = 2.0 * atan(r * 0.5);
        vec2 dir = r > 1e-6 ? p / r : vec2(0.0);
        dv = vec3(dir.x * sin(theta), -dir.y * sin(theta), cos(theta));
    } else {
        dv = normalize(vec3(p.x, -p.y, 1.0)); // +y aşağı
    }
    vec3 d = uView * dv;

    vec3 c0 = uCamFromRig0 * d;
    vec3 c1 = uCamFromRig1 * d;
    // Dikiş: iki lensin optik eksen farkına göre yumuşak geçiş (~±5 derece).
    float w0 = smoothstep(-0.09, 0.09, c0.z - c1.z);
    if (c0.z < COS_LIMIT) w0 = 0.0;
    if (c1.z < COS_LIMIT) w0 = 1.0;

    vec3 col = vec3(0.0);
    if (w0 > 0.0) col += w0 * texture(uTex0, texCoord(uTexMat0, project(c0, uIntr0, uDist0, uDist20))).rgb;
    if (w0 < 1.0) col += (1.0 - w0) * texture(uTex1, texCoord(uTexMat1, project(c1, uIntr1, uDist1, uDist21))).rgb;
    outColor = vec4(col, 1.0);
}
"""
    }
}

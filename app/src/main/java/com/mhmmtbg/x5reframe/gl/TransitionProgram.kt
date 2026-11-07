package com.mhmmtbg.x5reframe.gl

import android.opengl.GLES30
import com.mhmmtbg.x5reframe.project.Transition
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** İki ara görüntüyü (A: biten parça, B: başlayan parça) geçiş türüne göre birleştirir. */
class TransitionProgram {
    private val program = GlUtil.link(VS, FS)
    private val uA = GLES30.glGetUniformLocation(program, "uA")
    private val uB = GLES30.glGetUniformLocation(program, "uB")
    private val uP = GLES30.glGetUniformLocation(program, "uP")
    private val uType = GLES30.glGetUniformLocation(program, "uType")
    private val uAspect = GLES30.glGetUniformLocation(program, "uAspect")
    private val vbo: Int

    init {
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    /** @param p 0..1 geçiş ilerlemesi */
    fun draw(texA: Int, texB: Int, w: Int, h: Int, p: Float, type: Transition) {
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texA)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texB)
        GLES30.glUniform1i(uA, 0); GLES30.glUniform1i(uB, 1)
        // Yumuşak giriş-çıkış
        val e = p * p * (3 - 2 * p)
        GLES30.glUniform1f(uP, e)
        GLES30.glUniform1i(uType, type.shader)
        GLES30.glUniform1f(uAspect, w.toFloat() / h)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }

    companion object {
        private const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
        private const val FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uP;
uniform int uType;   // 1 çapraz, 2 sola kaydır, 3 yukarı kaydır, 4 silme, 5 yakınlaş, 6 daire, 7 bulanık
uniform float uAspect;
out vec4 outColor;

vec3 A(vec2 uv) { return texture(uA, clamp(uv, 0.0, 1.0)).rgb; }
vec3 B(vec2 uv) { return texture(uB, clamp(uv, 0.0, 1.0)).rgb; }

vec3 blurA(vec2 uv, float r) {
    vec3 s = vec3(0.0);
    for (int i = 0; i < 12; i++) { float a = float(i) * 0.5236; s += A(uv + vec2(cos(a), sin(a) * uAspect) * r); }
    return s / 12.0;
}
vec3 blurB(vec2 uv, float r) {
    vec3 s = vec3(0.0);
    for (int i = 0; i < 12; i++) { float a = float(i) * 0.5236; s += B(uv + vec2(cos(a), sin(a) * uAspect) * r); }
    return s / 12.0;
}

void main() {
    float p = uP;
    vec2 uv = vUv;
    vec3 c;
    if (uType == 2) {          // B sağdan gelir, A sola gider
        c = uv.x < 1.0 - p ? A(uv + vec2(p, 0.0)) : B(uv - vec2(1.0 - p, 0.0));
    } else if (uType == 3) {   // B alttan gelir
        c = uv.y > p ? A(uv - vec2(0.0, p)) : B(uv + vec2(0.0, 1.0 - p));
    } else if (uType == 4) {   // yumuşak kenarlı silme
        float e = smoothstep(p - 0.04, p + 0.04, uv.x * 1.08 - 0.04);
        c = mix(B(uv), A(uv), e);
    } else if (uType == 5) {   // A büyüyerek söner, B gelir
        vec2 q = (uv - 0.5) / (1.0 + p * 0.6) + 0.5;
        vec2 r = (uv - 0.5) * (1.0 + (1.0 - p) * 0.25) + 0.5;
        c = mix(A(q), B(r), smoothstep(0.2, 0.8, p));
    } else if (uType == 6) {   // B ortadan daire olarak açılır
        vec2 d = (uv - 0.5) * vec2(uAspect, 1.0);
        float rad = p * length(vec2(uAspect, 1.0)) * 0.55;
        c = mix(B(uv), A(uv), smoothstep(rad - 0.01, rad + 0.01, length(d)));
    } else if (uType == 7) {   // bulanıklaşarak geçiş
        float r = sin(p * 3.14159) * 0.025;
        c = mix(blurA(uv, r), blurB(uv, r), p);
    } else {                   // çapraz geçiş
        c = mix(A(uv), B(uv), p);
    }
    outColor = vec4(c, 1.0);
}
"""
    }
}
